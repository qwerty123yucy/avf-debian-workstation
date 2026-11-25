package com.avf.linux;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pipe-scoped console and vmlog stream multiplexer bound 1-to-1 to a VirtualMachine instance.
 *
 * Android's VirtualMachine allocates its console/log pipe pairs once per VirtualMachine object
 * and keeps the write ends open inside the app process across stop/run cycles, while returning
 * non-owning FileInputStreams from getConsoleOutput()/getLogOutput() whose close() is a no-op.
 *
 * Aligning reader thread lifetime with the VirtualMachine pipe (rather than VmSession) prevents
 * blocked reader threads from accumulating across reboots and prevents stale readers from
 * stealing the first U-Boot chunk of the next boot session.
 */
final class VmStreamPump {
    interface Sink {
        long sessionId();
        boolean isClosed();
        void onConsoleChars(char[] buf, int len);
        void onVmLogLine(String line);
    }

    interface StreamSupplier {
        InputStream open();
    }

    private static final int READ_BUF_BYTES = 4096;
    private static final int MAX_LOG_LINE_BYTES = 4096;
    private static final AtomicInteger sPumpIds = new AtomicInteger(0);
    private static final WeakHashMap<Object, VmStreamPump> sPumps = new WeakHashMap<>();

    private final int mPumpId = sPumpIds.incrementAndGet();
    private final Object mStateLock = new Object();
    private final AtomicReference<Sink> mTarget = new AtomicReference<>();
    private final AtomicBoolean mConAlive = new AtomicBoolean(false);
    private final AtomicBoolean mLogAlive = new AtomicBoolean(false);
    private final ByteArrayOutputStream mLogLineBuf = new ByteArrayOutputStream(256);

    private volatile InputStream mConIn;
    private volatile InputStream mLogIn;
    private boolean mConBusy = false;
    private boolean mLogBusy = false;
    private long mLogLineSid = -1L;
    private long mDispatchSeq = 0L;

    static VmStreamPump rebindIfPresent(Object vmKey, Sink sink) {
        if (vmKey == null || sink == null) return null;
        synchronized (sPumps) {
            VmStreamPump existing = sPumps.get(vmKey);
            if (existing != null && existing.isHealthy()) {
                existing.bind(sink);
                return existing;
            }
            return null;
        }
    }

    static VmStreamPump attachOrBind(
            Object vmKey,
            StreamSupplier conSupplier,
            StreamSupplier logSupplier,
            Sink sink) {
        synchronized (sPumps) {
            VmStreamPump pump = sPumps.get(vmKey);
            if (pump == null || !pump.isHealthy()) {
                pump = new VmStreamPump();
                sPumps.put(vmKey, pump);
            }
            pump.bind(sink);
            pump.ensureStreamsStarted(conSupplier, logSupplier);
            return pump;
        }
    }

    VmStreamPump() {}

    static VmStreamPump createForTest(InputStream conIn, InputStream logIn, Sink initialSink) {
        VmStreamPump p = new VmStreamPump();
        if (initialSink != null) {
            p.bind(initialSink);
        }
        p.ensureStreamsStarted(() -> conIn, () -> logIn);
        return p;
    }

    boolean isHealthy() {
        boolean conOk = (mConIn == null) || mConAlive.get();
        boolean logOk = (mLogIn == null) || mLogAlive.get();
        return conOk && logOk && (mConIn != null || mLogIn != null);
    }

    void bind(Sink sink) {
        mTarget.set(sink);
    }

    void ensureStreamsStarted(StreamSupplier conSupplier, StreamSupplier logSupplier) {
        synchronized (mStateLock) {
            if ((mConIn == null || !mConAlive.get()) && conSupplier != null) {
                InputStream in = conSupplier.open();
                if (in != null) {
                    mConIn = in;
                    mConAlive.set(true);
                    Thread t = new Thread(() -> runConsoleLoop(in), "vm-con-pump-" + mPumpId);
                    t.setDaemon(true);
                    t.start();
                }
            }
            if ((mLogIn == null || !mLogAlive.get()) && logSupplier != null) {
                InputStream in = logSupplier.open();
                if (in != null) {
                    mLogIn = in;
                    mLogAlive.set(true);
                    Thread t = new Thread(() -> runLogLoop(in), "vm-log-pump-" + mPumpId);
                    t.setDaemon(true);
                    t.start();
                }
            }
        }
    }

    void flushPending(Sink expected, long timeoutMs) {
        if (expected == null || timeoutMs <= 0L || mTarget.get() != expected) {
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (mStateLock) {
            while (mTarget.get() == expected) {
                long now = System.currentTimeMillis();
                if (now >= deadline) break;
                if (!mConBusy && !mLogBusy && !hasAvailableBytes(mConIn) && !hasAvailableBytes(mLogIn)) {
                    long seqBefore = mDispatchSeq;
                    long confirmWait = Math.min(2L, deadline - now);
                    if (confirmWait > 0L) {
                        try {
                            mStateLock.wait(confirmWait);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (mDispatchSeq == seqBefore
                            && !mConBusy
                            && !mLogBusy
                            && !hasAvailableBytes(mConIn)
                            && !hasAvailableBytes(mLogIn)) {
                        break;
                    }
                } else {
                    long waitMs = Math.min(5L, deadline - now);
                    try {
                        mStateLock.wait(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    void flushAndDetach(Sink expected, long timeoutMs) {
        if (expected == null) return;
        flushPending(expected, timeoutMs);
        synchronized (mStateLock) {
            if (mTarget.get() == expected) {
                if (mLogLineSid == expected.sessionId() && mLogLineBuf.size() > 0 && !expected.isClosed()) {
                    String tail = new String(mLogLineBuf.toByteArray(), StandardCharsets.UTF_8);
                    mLogLineBuf.reset();
                    try {
                        expected.onVmLogLine(tail);
                    } catch (Throwable ignored) {}
                }
                mTarget.compareAndSet(expected, null);
            }
        }
    }

    void detachImmediate(Sink expected) {
        if (expected == null) return;
        mTarget.compareAndSet(expected, null);
    }

    private static boolean hasAvailableBytes(InputStream in) {
        if (in == null) return false;
        try {
            return in.available() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void runConsoleLoop(final InputStream in) {
        final byte[] raw = new byte[READ_BUF_BYTES];
        final ByteBuffer utf8Buf = ByteBuffer.allocate(READ_BUF_BYTES + 8);
        final CharBuffer charBuf = CharBuffer.allocate(READ_BUF_BYTES + 8);
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        long lastSid = -1L;

        try {
            int n;
            while ((n = in.read(raw)) >= 0) {
                if (n == 0) continue;
                synchronized (mStateLock) {
                    mConBusy = true;
                }
                try {
                    Sink sink = mTarget.get();
                    if (sink == null || sink.isClosed()) {
                        if (lastSid != -1L) {
                            decoder.reset();
                            utf8Buf.clear();
                            lastSid = -1L;
                        }
                        continue;
                    }
                    long sid = sink.sessionId();
                    if (sid != lastSid) {
                        decoder.reset();
                        utf8Buf.clear();
                        lastSid = sid;
                    }
                    int off = 0;
                    while (off < n) {
                        int chunk = Math.min(n - off, utf8Buf.remaining());
                        utf8Buf.put(raw, off, chunk);
                        off += chunk;
                        utf8Buf.flip();
                        CoderResult cr;
                        do {
                            charBuf.clear();
                            cr = decoder.decode(utf8Buf, charBuf, false);
                            int decoded = charBuf.position();
                            if (decoded > 0 && !sink.isClosed()) {
                                sink.onConsoleChars(charBuf.array(), decoded);
                            }
                        } while (cr.isOverflow());
                        utf8Buf.compact();
                    }
                } finally {
                    synchronized (mStateLock) {
                        mConBusy = false;
                        mDispatchSeq++;
                        mStateLock.notifyAll();
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            mConAlive.set(false);
            synchronized (mStateLock) {
                if (mConIn == in) mConBusy = false;
                mStateLock.notifyAll();
            }
        }
    }

    private void runLogLoop(final InputStream in) {
        final byte[] raw = new byte[READ_BUF_BYTES];
        try {
            int n;
            while ((n = in.read(raw)) >= 0) {
                if (n == 0) continue;
                String[] readyLines = null;
                int lineCount = 0;
                Sink sink;
                synchronized (mStateLock) {
                    mLogBusy = true;
                    sink = mTarget.get();
                    if (sink == null || sink.isClosed()) {
                        mLogLineBuf.reset();
                        mLogLineSid = -1L;
                    } else {
                        long sid = sink.sessionId();
                        if (sid != mLogLineSid) {
                            mLogLineBuf.reset();
                            mLogLineSid = sid;
                        }
                        for (int i = 0; i < n; i++) {
                            byte b = raw[i];
                            if (b == '\n') {
                                String line = new String(mLogLineBuf.toByteArray(), StandardCharsets.UTF_8);
                                mLogLineBuf.reset();
                                if (readyLines == null) readyLines = new String[8];
                                else if (lineCount == readyLines.length) {
                                    String[] grown = new String[readyLines.length * 2];
                                    System.arraycopy(readyLines, 0, grown, 0, lineCount);
                                    readyLines = grown;
                                }
                                readyLines[lineCount++] = line;
                            } else if (b != '\r' && mLogLineBuf.size() < MAX_LOG_LINE_BYTES) {
                                mLogLineBuf.write(b);
                            }
                        }
                    }
                }
                try {
                    if (sink != null && readyLines != null) {
                        for (int i = 0; i < lineCount; i++) {
                            if (sink.isClosed()) break;
                            sink.onVmLogLine(readyLines[i]);
                        }
                    }
                } finally {
                    synchronized (mStateLock) {
                        mLogBusy = false;
                        mDispatchSeq++;
                        mStateLock.notifyAll();
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            mLogAlive.set(false);
            synchronized (mStateLock) {
                if (mLogIn == in) mLogBusy = false;
                mStateLock.notifyAll();
            }
        }
    }
}
