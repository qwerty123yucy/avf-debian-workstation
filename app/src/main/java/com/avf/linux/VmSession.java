package com.avf.linux;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineCallback;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import android.system.virtualmachine.VirtualMachineManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cohesive runtime unit for a single VM boot session.
 *
 * Encapsulates VirtualMachine creation and startup, console/vmlog stream capture
 * (started immediately at boot so early UEFI lines are never missed), vsock:2224
 * graceful poweroff signaling, SSH TCP-to-vsock bridging, and per-session libvmnet.so
 * userspace networking.
 */
final class VmSession implements VmController.SessionHandle, VmStreamPump.Sink {
    private static final String TAG = "AvfLinux";
    private static final int VMNET_VSOCK_PORT = 2223;
    private static final int POWER_VSOCK_PORT = 2224;

    interface Observer {
        void onConsoleChars(VmSession session, char[] buf, int len);
        void onSessionNotice(VmSession session, String text);
        void onVmLogLine(VmSession session, String line);
        void onVmNetStateChanged(VmSession session, boolean connected, String guestIp);
        void onSessionStopped(VmSession session, boolean rebootRequested, int reason);
        void onSessionError(VmSession session, int errorCode, String message);
    }

    private final long mId;
    private final long mGeneration;
    private final VirtualMachine mVm;
    private final int mMemMib;
    private final int mDiskGb;
    private final int mSshLocalPort;
    private final int mSshVsockPort;
    private final boolean mKeepAwake;
    private final File mNativeLibDir;
    private final File mVmNetSockFile;
    private final File mDnsFile;
    private final Observer mObserver;

    private final CountDownLatch mStoppedLatch = new CountDownLatch(1);
    private final CountDownLatch mPowerAttemptDone = new CountDownLatch(1);
    private final AtomicBoolean mIntentionalStop = new AtomicBoolean(false);
    private final AtomicBoolean mPoweroffSent = new AtomicBoolean(false);
    private final AtomicBoolean mBridgesActivated = new AtomicBoolean(false);
    private final AtomicBoolean mClosing = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);
    private final AtomicReference<VmController.SessionExit> mExit = new AtomicReference<>();
    private final Set<Closeable> mActiveSshRelays =
        Collections.newSetFromMap(new ConcurrentHashMap<Closeable, Boolean>());

    private volatile boolean mPoweroffAckOk = false;
    private volatile VmStreamPump mStreamPump;
    private volatile OutputStream mConsoleIn;
    private volatile Thread mVmNetBridgeThread;
    private volatile ServerSocket mSshServer;
    private volatile Process mVmNetProc;
    private volatile OutputStream mVmNetStdin;
    private volatile LocalSocket mVmNetCtrlSock;

    static VmSession boot(
            Context context,
            VmConfigStore store,
            long sessionId,
            long generation,
            boolean fallbackKeepAwake,
            ExecutorService callbackExecutor,
            Observer observer) throws Exception {
        Properties p = store.loadSettings();
        int memMib = store.getIntProp(p, "memory_mib", VmConfigStore.DEFAULT_MEM_MIB);
        int diskGb = store.getIntProp(p, "disk_gb", VmConfigStore.DEFAULT_DISK_GB);
        int sshPort = store.getIntProp(p, "ssh_local_port", VmConfigStore.DEFAULT_SSH_LOCAL_PORT);
        int vsockPort = store.getIntProp(p, "ssh_vsock_port", VmConfigStore.DEFAULT_SSH_VSOCK_PORT);
        boolean keepAwake = store.getBoolProp(p, "keep_awake", fallbackKeepAwake);

        File diskFile = store.getDiskFile();
        VmImageManager.ensureDiskLogicalSize(diskFile, diskGb);

        VirtualMachineCustomImageConfig custom =
            new VirtualMachineCustomImageConfig.Builder()
                .setName(VmConfigStore.VM_NAME)
                .setBootloaderPath(VmConfigStore.BOOTLOADER_PATH)
                .addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(diskFile.getAbsolutePath()))
                .useNetwork(false)
                .build();

        VirtualMachineManager vmm = context.getSystemService(VirtualMachineManager.class);
        if (vmm == null) {
            throw new IllegalStateException("VirtualMachineManager service is unavailable");
        }

        VirtualMachineConfig config =
            new VirtualMachineConfig.Builder(context)
                .setProtectedVm(false)
                .setMemoryBytes(((long) memMib) * 1024L * 1024L)
                .setCpuTopology(VirtualMachineConfig.CPU_TOPOLOGY_MATCH_HOST)
                .setDebugLevel(VirtualMachineConfig.DEBUG_LEVEL_FULL)
                .setConsoleInputDevice("hvc0")
                .setVmOutputCaptured(true)
                .setVmConsoleInputSupported(true)
                .setCustomImageConfig(custom)
                .build();

        VirtualMachine vm = vmm.getOrCreate(VmConfigStore.VM_NAME, config);
        try {
            vm.setConfig(config);
        } catch (Throwable ignored) {
            try { vmm.delete(VmConfigStore.VM_NAME); } catch (Throwable ignored2) {}
            vm = vmm.getOrCreate(VmConfigStore.VM_NAME, config);
        }

        final VmSession session = new VmSession(
            sessionId,
            generation,
            vm,
            memMib,
            diskGb,
            sshPort,
            vsockPort,
            keepAwake,
            new File(context.getApplicationInfo().nativeLibraryDir),
            store.getSessionVmNetSocket(sessionId),
            store.getDnsFile(),
            observer
        );

        vm.setCallback(callbackExecutor, new VirtualMachineCallback() {
            @Override public void onPayloadStarted(VirtualMachine v) {}
            @Override public void onPayloadReady(VirtualMachine v) {}
            @Override public void onPayloadFinished(VirtualMachine v, int exitCode) {}

            @Override
            public void onError(VirtualMachine v, int errorCode, String message) {
                observer.onSessionError(session, errorCode, message);
            }

            @Override
            public void onStopped(VirtualMachine v, int reason) {
                boolean reboot = (reason == VirtualMachineCallback.STOP_REASON_REBOOT);
                observer.onSessionStopped(session, reboot, reason);
            }
        });

        // Rebind any existing pipe pump before vm.run() so zero early U-Boot bytes can
        // be routed to a previous session or dropped across restarts.
        final VirtualMachine vmRef = vm;
        VmStreamPump preboundPump = VmStreamPump.rebindIfPresent(vmRef, session);
        try {
            vmRef.run();
            VmStreamPump pump = (preboundPump != null)
                ? preboundPump
                : VmStreamPump.attachOrBind(
                    vmRef,
                    () -> openConsoleOutputQuietly(vmRef),
                    () -> openLogOutputQuietly(vmRef),
                    session);
            pump.ensureStreamsStarted(
                () -> openConsoleOutputQuietly(vmRef),
                () -> openLogOutputQuietly(vmRef));
            session.attachStreams(pump, openConsoleInputQuietly(vmRef));
            return session;
        } catch (Throwable t) {
            if (preboundPump != null) {
                preboundPump.detachImmediate(session);
            }
            session.close(true);
            throw t;
        }
    }

    private static InputStream openConsoleOutputQuietly(VirtualMachine vm) {
        try {
            return vm.getConsoleOutput();
        } catch (Throwable t) {
            Log.w(TAG, "getConsoleOutput failed: " + t.getMessage());
            return null;
        }
    }

    private static InputStream openLogOutputQuietly(VirtualMachine vm) {
        try {
            return vm.getLogOutput();
        } catch (Throwable t) {
            Log.w(TAG, "getLogOutput failed: " + t.getMessage());
            return null;
        }
    }

    private static OutputStream openConsoleInputQuietly(VirtualMachine vm) {
        try {
            return vm.getConsoleInput();
        } catch (Throwable t) {
            Log.w(TAG, "getConsoleInput failed: " + t.getMessage());
            return null;
        }
    }

    private VmSession(
            long id,
            long generation,
            VirtualMachine vm,
            int memMib,
            int diskGb,
            int sshLocalPort,
            int sshVsockPort,
            boolean keepAwake,
            File nativeLibDir,
            File vmNetSockFile,
            File dnsFile,
            Observer observer) {
        mId = id;
        mGeneration = generation;
        mVm = vm;
        mMemMib = memMib;
        mDiskGb = diskGb;
        mSshLocalPort = sshLocalPort;
        mSshVsockPort = sshVsockPort;
        mKeepAwake = keepAwake;
        mNativeLibDir = nativeLibDir;
        mVmNetSockFile = vmNetSockFile;
        mDnsFile = dnsFile;
        mObserver = observer;
    }

    @Override public long id() { return mId; }
    @Override public long sessionId() { return mId; }
    @Override public boolean isClosed() { return mClosed.get(); }

    @Override
    public void onConsoleChars(char[] buf, int len) {
        if (!mClosed.get()) {
            mObserver.onConsoleChars(this, buf, len);
        }
    }

    @Override
    public void onVmLogLine(String line) {
        if (!mClosed.get()) {
            Log.i(TAG, "[vmlog] " + line);
            mObserver.onVmLogLine(this, line);
        }
    }
    @Override public long generation() { return mGeneration; }
    @Override public int memMib() { return mMemMib; }
    @Override public int diskGb() { return mDiskGb; }
    @Override public int sshPort() { return mSshLocalPort; }
    @Override public boolean keepAwake() { return mKeepAwake; }

    @Override
    public String startupBanner() {
        return "[AVF Linux] VM started (UEFI self-boot, match_host, "
            + mMemMib + " MiB on-demand, WakeLock=" + (mKeepAwake ? "ON" : "OFF")
            + ", SSH 127.0.0.1:" + mSshLocalPort + ")\n";
    }

    @Override
    public void markIntentionalStop() {
        mIntentionalStop.set(true);
    }

    @Override
    public void recordExit(VmController.SessionExit exit) {
        mExit.compareAndSet(null, exit);
        mStoppedLatch.countDown();
    }

    @Override
    public VmController.SessionExit exit() {
        return mExit.get();
    }

    @Override
    public boolean isTerminated() {
        return mStoppedLatch.getCount() == 0L;
    }

    @Override
    public boolean awaitStopped(long timeoutMs) throws InterruptedException {
        return mStoppedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void attachStreams(VmStreamPump pump, OutputStream consoleIn) {
        if (mClosing.get()) {
            if (pump != null) {
                pump.detachImmediate(this);
            }
            return;
        }
        mStreamPump = pump;
        mConsoleIn = consoleIn;
    }

    @Override
    public void activateBridges() {
        if (mClosing.get() || !mBridgesActivated.compareAndSet(false, true)) {
            return;
        }
        startSshBridge();
        startVmNetDaemon();
    }

    @Override
    public void flushOutput(long timeoutMs) {
        VmStreamPump pump = mStreamPump;
        if (pump != null) {
            pump.flushPending(this, timeoutMs);
        }
    }

    @Override
    public void writeConsoleInput(byte[] data) {
        if (data == null || data.length == 0 || mClosing.get()) return;
        OutputStream out = mConsoleIn;
        if (out == null) return;
        try {
            out.write(data);
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "Console input write error: " + e.getMessage());
        }
    }

    @Override
    public boolean sendPoweroffOnce() {
        if (isTerminated()) {
            return true;
        }
        if (mPoweroffSent.compareAndSet(false, true)) {
            try {
                mPoweroffAckOk = sendGuestPowerCommand("POWEROFF");
            } finally {
                mPowerAttemptDone.countDown();
            }
            return mPoweroffAckOk;
        }
        try {
            mPowerAttemptDone.await(2000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return mPoweroffAckOk;
    }

    @Override
    public void close(boolean stopVmInstance) {
        if (!mClosing.compareAndSet(false, true)) {
            return;
        }
        mConsoleIn = null;
        stopVmNetDaemon();

        ServerSocket ssh = mSshServer;
        mSshServer = null;
        if (ssh != null) {
            try { ssh.close(); } catch (IOException ignored) {}
        }
        for (Closeable relay : mActiveSshRelays) {
            try { relay.close(); } catch (IOException ignored) {}
        }
        mActiveSshRelays.clear();

        try { mVm.clearCallback(); } catch (Throwable ignored) {}
        if (stopVmInstance) {
            try {
                mVm.stop();
            } catch (Throwable t) {
                Log.w(TAG, "vm.stop error", t);
            }
        }

        VmStreamPump pump = mStreamPump;
        mStreamPump = null;
        if (pump != null) {
            pump.flushAndDetach(this, 25L);
        }
        mClosed.set(true);

        mStoppedLatch.countDown();
        mPowerAttemptDone.countDown();
    }

    private boolean sendGuestPowerCommand(String cmd) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = mVm.connectVsock(POWER_VSOCK_PORT);
            clearNonBlocking(pfd);
            FileDescriptor fd = pfd.getFileDescriptor();
            byte[] payload = (cmd + "\n").getBytes(StandardCharsets.US_ASCII);
            int off = 0;
            while (off < payload.length) {
                int w = Os.write(fd, payload, off, payload.length - off);
                if (w <= 0) throw new IOException("Short write to vsock:" + POWER_VSOCK_PORT);
                off += w;
            }

            StructPollfd p = new StructPollfd();
            p.fd = fd;
            p.events = (short) OsConstants.POLLIN;
            int rc = Os.poll(new StructPollfd[]{p}, 1500);
            if (rc > 0 && (p.revents & OsConstants.POLLIN) != 0) {
                byte[] buf = new byte[32];
                int n = Os.read(fd, buf, 0, buf.length);
                if (n > 0) {
                    String resp = new String(buf, 0, n, StandardCharsets.US_ASCII).trim();
                    Log.i(TAG, "Guest power control (" + cmd + ") ack: " + resp);
                    return resp.startsWith("OK");
                }
            }
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "Guest power control vsock:" + POWER_VSOCK_PORT + " unavailable: " + t.getMessage());
            return false;
        } finally {
            if (pfd != null) {
                try { pfd.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void startSshBridge() {
        try {
            final ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), mSshLocalPort), 32);
            if (mClosing.get()) {
                server.close();
                return;
            }
            mSshServer = server;
            Thread acceptThread = new Thread(() -> {
                while (!mClosing.get() && !server.isClosed()) {
                    try {
                        final Socket sock = server.accept();
                        if (mClosing.get()) {
                            try { sock.close(); } catch (IOException ignored) {}
                            break;
                        }
                        Thread ct = new Thread(() -> relayTcpToVsock(sock), "ssh-relay-" + mId);
                        ct.setDaemon(true);
                        ct.start();
                    } catch (IOException e) {
                        break;
                    }
                }
            }, "ssh-accept-" + mId);
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            mObserver.onSessionNotice(this,
                "[AVF Linux] SSH bridge bind failed on 127.0.0.1:" + mSshLocalPort + ": " + e.getMessage() + "\n");
        }
    }

    private void relayTcpToVsock(final Socket sock) {
        ParcelFileDescriptor vsock = null;
        Closeable relayHandle = null;
        try {
            sock.setTcpNoDelay(true);
            vsock = mVm.connectVsock(mSshVsockPort);
            final ParcelFileDescriptor vfd = vsock;
            clearNonBlocking(vfd);

            final AtomicBoolean relayClosed = new AtomicBoolean(false);
            relayHandle = () -> {
                if (relayClosed.compareAndSet(false, true)) {
                    try { sock.close(); } catch (IOException ignored) {}
                    try { vfd.close(); } catch (IOException ignored) {}
                }
            };
            mActiveSshRelays.add(relayHandle);
            if (mClosing.get()) {
                relayHandle.close();
                return;
            }

            final InputStream sockIn = sock.getInputStream();
            final OutputStream sockOut = sock.getOutputStream();
            final FileDescriptor rawFd = vfd.getFileDescriptor();
            final AtomicInteger rem = new AtomicInteger(2);
            final Closeable finalHandle = relayHandle;

            Runnable done = () -> {
                if (rem.decrementAndGet() == 0) {
                    mActiveSshRelays.remove(finalHandle);
                    try { finalHandle.close(); } catch (IOException ignored) {}
                }
            };

            Thread up = new Thread(() -> {
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while (!relayClosed.get() && (n = sockIn.read(buf)) > 0) {
                        int off = 0;
                        while (off < n) {
                            int w = Os.write(rawFd, buf, off, n - off);
                            if (w <= 0) throw new IOException("Short vsock write");
                            off += w;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (!relayClosed.get()) {
                        try { Os.shutdown(rawFd, OsConstants.SHUT_WR); } catch (Throwable ignored) {}
                    }
                    done.run();
                }
            }, "tcp2vsock-" + mId);
            up.setDaemon(true);
            up.start();

            try {
                byte[] buf = new byte[65536];
                while (!relayClosed.get()) {
                    int n = Os.read(rawFd, buf, 0, buf.length);
                    if (n <= 0) break;
                    sockOut.write(buf, 0, n);
                    sockOut.flush();
                }
            } catch (Throwable ignored) {
            } finally {
                if (!relayClosed.get()) {
                    try { sock.shutdownOutput(); } catch (IOException ignored) {}
                }
                done.run();
            }
        } catch (Throwable e) {
            Log.w(TAG, "SSH vsock relay error: " + e.getMessage());
            if (relayHandle != null) {
                mActiveSshRelays.remove(relayHandle);
                try { relayHandle.close(); } catch (IOException ignored) {}
            } else {
                try { sock.close(); } catch (IOException ignored) {}
                if (vsock != null) {
                    try { vsock.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    private void startVmNetDaemon() {
        File bin = new File(mNativeLibDir, "libvmnet.so");
        if (!bin.isFile()) {
            Log.e(TAG, "libvmnet.so missing at " + bin.getAbsolutePath());
            mObserver.onSessionNotice(this, "[AVF Linux] Warning: libvmnet.so not found; guest networking disabled\n");
            return;
        }
        if (mVmNetSockFile.exists()) {
            mVmNetSockFile.delete();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                bin.getAbsolutePath(),
                "-sock", mVmNetSockFile.getAbsolutePath(),
                "-dns-file", mDnsFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            final Process proc = pb.start();
            if (mClosing.get()) {
                proc.destroy();
                return;
            }
            mVmNetProc = proc;
            mVmNetStdin = proc.getOutputStream();

            Thread logT = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.i(TAG, "[vmnet] " + line);
                    }
                } catch (Throwable ignored) {}
            }, "vmnet-log-" + mId);
            logT.setDaemon(true);
            logT.start();

            Thread bridgeT = new Thread(() -> {
                for (int i = 0; i < 40 && !mClosing.get() && !mVmNetSockFile.exists(); i++) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                }
                while (!mClosing.get()) {
                    ParcelFileDescriptor vsock = null;
                    LocalSocket local = null;
                    boolean notifiedUp = false;
                    try {
                        vsock = mVm.connectVsock(VMNET_VSOCK_PORT);
                        if (mClosing.get()) break;

                        local = new LocalSocket();
                        local.connect(new LocalSocketAddress(
                            mVmNetSockFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
                        mVmNetCtrlSock = local;

                        local.setFileDescriptorsForSend(new FileDescriptor[]{vsock.getFileDescriptor()});
                        OutputStream lsOut = local.getOutputStream();
                        lsOut.write(1);
                        lsOut.flush();
                        local.setFileDescriptorsForSend(null);

                        try { vsock.close(); } catch (IOException ignored) {}
                        vsock = null;

                        if (!mClosing.get()) {
                            notifiedUp = true;
                            mObserver.onVmNetStateChanged(this, true, "10.0.2.2");
                            Log.i(TAG, "vmnet vsock:2223 FD handed off to libvmnet.so (SCM_RIGHTS)");
                        }

                        InputStream lsIn = local.getInputStream();
                        while (!mClosing.get() && lsIn.read() >= 0) {
                            // Wait until libvmnet.so closes control socket on tunnel EOF
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        mVmNetCtrlSock = null;
                        if (local != null) { try { local.close(); } catch (IOException ignored) {} }
                        if (vsock != null) { try { vsock.close(); } catch (IOException ignored) {} }
                        if (notifiedUp && !mClosing.get()) {
                            mObserver.onVmNetStateChanged(this, false, "");
                        }
                    }
                    if (mClosing.get()) break;
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
            }, "vmnet-ctrl-" + mId);
            bridgeT.setDaemon(true);
            mVmNetBridgeThread = bridgeT;
            bridgeT.start();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start libvmnet.so", t);
            mObserver.onSessionNotice(this, "[AVF Linux] vmnet start error: " + t.getMessage() + "\n");
        }
    }

    private void stopVmNetDaemon() {
        Thread bt = mVmNetBridgeThread;
        mVmNetBridgeThread = null;
        if (bt != null) {
            bt.interrupt();
        }
        LocalSocket ctrl = mVmNetCtrlSock;
        mVmNetCtrlSock = null;
        if (ctrl != null) {
            try { ctrl.close(); } catch (Throwable ignored) {}
        }
        OutputStream stdin = mVmNetStdin;
        mVmNetStdin = null;
        if (stdin != null) {
            try { stdin.close(); } catch (Throwable ignored) {}
        }
        Process proc = mVmNetProc;
        mVmNetProc = null;
        if (proc != null) {
            try { proc.destroy(); } catch (Throwable ignored) {}
            try {
                if (!proc.waitFor(150L, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly();
                }
            } catch (Throwable ignored) {}
        }
        if (mVmNetSockFile.exists()) {
            mVmNetSockFile.delete();
        }
    }

    private static void clearNonBlocking(ParcelFileDescriptor pfd) {
        try {
            Method fcntl = Os.class.getMethod("fcntlInt", FileDescriptor.class, int.class, int.class);
            int fl = (Integer) fcntl.invoke(null, pfd.getFileDescriptor(), OsConstants.F_GETFL, 0);
            if ((fl & OsConstants.O_NONBLOCK) != 0) {
                fcntl.invoke(null, pfd.getFileDescriptor(), OsConstants.F_SETFL, fl & ~OsConstants.O_NONBLOCK);
            }
        } catch (Throwable ignored) {}
    }
}
