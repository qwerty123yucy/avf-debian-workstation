package org.keepalive.binder;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.system.Os;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Standalone, app-agnostic Android Binder keepalive daemon and CLI client.
 *
 * Runs inside a host shell environment (such as Termux) via app_process so it
 * shares the host app's UID and co-freezes automatically whenever the host app
 * itself is frozen by the OS.
 *
 * Protocol:
 *   1. Bootstraps an IBinder handle from an explicitly specified exported
 *      Service component via standard Intent.EXTRA_RESULT_RECEIVER ("binder").
 *   2. If the target Service returns an IBinder, performs lightweight
 *      synchronous /dev/binder transactions (default every 2s) paired with
 *      periodic IActivityManager.startService refreshes (default every 10s),
 *      and falls back to immediate startService unfreeze if BR_FROZEN_REPLY
 *      occurs on wakeup.
 *   3. If an unmodified target Service ignores EXTRA_RESULT_RECEIVER, degrades
 *      cleanly to periodic startService refreshes.
 */
public final class BinderKeepAlive {
    private static final String EXTRA_BINDER = "binder";
    private static final long DEFAULT_PING_MS = 2000L;
    private static final long DEFAULT_START_MS = 10000L;
    private static final long MIN_PING_MS = 500L;
    private static final long MAX_PING_MS = 60000L;
    private static final long MIN_START_MS = 2000L;
    private static final long MAX_START_MS = 300000L;
    private static final long HANDSHAKE_WAIT_MS = 450L;

    private static final class AmsBridge {
        final int uid;
        final int userId;
        final String callingPackage;
        final Object ams;
        final Method startServiceMethod;
        final int paramCount;

        AmsBridge(String overridePkg) throws Exception {
            this.uid = Os.getuid();
            this.userId = this.uid / 100000;
            this.ams = resolveAms();
            Method found = null;
            int count = 0;
            for (Method m : this.ams.getClass().getMethods()) {
                if (!"startService".equals(m.getName())) continue;
                int n = m.getParameterTypes().length;
                if (n == 7 || n == 6) {
                    found = m;
                    count = n;
                    if (n == 7) break;
                }
            }
            if (found == null) {
                throw new NoSuchMethodException("IActivityManager.startService not found");
            }
            found.setAccessible(true);
            this.startServiceMethod = found;
            this.paramCount = count;
            this.callingPackage = (overridePkg != null && !overridePkg.isEmpty())
                ? overridePkg
                : resolveCallingPackage(this.uid);
        }

        private static Object resolveAms() throws Exception {
            try {
                Method getSvc = ActivityManager.class.getDeclaredMethod("getService");
                getSvc.setAccessible(true);
                Object obj = getSvc.invoke(null);
                if (obj != null) return obj;
            } catch (Throwable ignored) {}
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "activity");
            Class<?> stub = Class.forName("android.app.IActivityManager$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        }

        private static String resolveCallingPackage(int uid) throws Exception {
            if (uid == 0) return "root";
            if (uid == 2000) return "com.android.shell";
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                IBinder pmBinder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "package");
                Class<?> pmStub = Class.forName("android.content.pm.IPackageManager$Stub");
                Object pm = pmStub.getMethod("asInterface", IBinder.class).invoke(null, pmBinder);
                String[] pkgs = (String[]) pm.getClass().getMethod("getPackagesForUid", int.class).invoke(pm, uid);
                if (pkgs != null && pkgs.length > 0 && pkgs[0] != null && !pkgs[0].isEmpty()) {
                    return pkgs[0];
                }
            } catch (Throwable ignored) {}
            String envPkg = System.getenv("TERMUX_APP__PACKAGE_NAME");
            if (envPkg != null && !envPkg.trim().isEmpty()) {
                return envPkg.trim();
            }
            for (String pathEnv : new String[]{System.getenv("PREFIX"), System.getenv("HOME")}) {
                String inferred = inferPkgFromDataPath(pathEnv);
                if (inferred != null) return inferred;
            }
            throw new IllegalStateException("Cannot resolve package name for UID " + uid);
        }

        private static String inferPkgFromDataPath(String path) {
            if (path == null) return null;
            // Matches /data/data/<pkg>/... or /data/user/<id>/<pkg>/...
            String[] segs = path.split("/");
            if (segs.length >= 4 && "data".equals(segs[1]) && "data".equals(segs[2]) && !segs[3].isEmpty()) {
                return segs[3];
            }
            if (segs.length >= 5 && "data".equals(segs[1]) && "user".equals(segs[2]) && !segs[4].isEmpty()) {
                return segs[4];
            }
            return null;
        }

        ComponentName startService(ComponentName target, ResultReceiver wireReceiver) throws Exception {
            Intent intent = new Intent();
            intent.setComponent(target);
            if (wireReceiver != null) {
                intent.putExtra(Intent.EXTRA_RESULT_RECEIVER, wireReceiver);
            }
            try {
                Object res;
                if (paramCount == 7) {
                    res = startServiceMethod.invoke(
                        ams, null, intent, null, Boolean.FALSE, callingPackage, null, Integer.valueOf(userId));
                } else {
                    res = startServiceMethod.invoke(
                        ams, null, intent, null, Boolean.FALSE, callingPackage, Integer.valueOf(userId));
                }
                return (ComponentName) res;
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getTargetException();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new RuntimeException(cause);
            }
        }
    }

    private static final class TargetEntry implements IBinder.DeathRecipient {
        final ComponentName component;
        final AmsBridge bridge;
        final Object wakeLock = new Object();
        final long createdElapsedMs = SystemClock.elapsedRealtime();

        // Keep strong anchor to local ResultReceiver subclass while exposing a
        // pure bootclasspath android.os.ResultReceiver wrapper over IPC.
        @SuppressWarnings("unused")
        private final ResultReceiver receiverAnchor;
        private final ResultReceiver wireReceiver;

        volatile long pingIntervalMs;
        volatile long startIntervalMs;
        volatile boolean running = true;

        volatile IBinder remoteBinder;
        volatile int binderTxMode = 1; // 1 = FIRST_CALL_TRANSACTION, 2 = PING_TRANSACTION
        volatile long binderTxOk = 0L;
        volatile long serviceStartOk = 0L;
        volatile long unfreezeAssists = 0L;
        volatile long errorCount = 0L;
        volatile String lastError = "";

        private final CountDownLatch firstOutcomeLatch = new CountDownLatch(1);
        private final Object binderWait = new Object();
        private volatile String firstOutcome = null;
        private Thread worker;

        TargetEntry(ComponentName component, AmsBridge bridge, long pingMs, long startMs) {
            this.component = component;
            this.bridge = bridge;
            this.pingIntervalMs = clamp(pingMs, MIN_PING_MS, MAX_PING_MS);
            this.startIntervalMs = clamp(startMs, MIN_START_MS, MAX_START_MS);

            ResultReceiver anchor = new ResultReceiver(null) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    if (resultData == null || !running) return;
                    IBinder b = resultData.getBinder(EXTRA_BINDER);
                    if (b != null) {
                        installBinder(b);
                    }
                }
            };
            this.receiverAnchor = anchor;
            Parcel p = Parcel.obtain();
            try {
                anchor.writeToParcel(p, 0);
                p.setDataPosition(0);
                this.wireReceiver = ResultReceiver.CREATOR.createFromParcel(p);
            } finally {
                p.recycle();
            }
        }

        private void installBinder(IBinder next) {
            IBinder prev;
            synchronized (binderWait) {
                prev = this.remoteBinder;
                if (prev == next) return;
                this.remoteBinder = next;
                this.binderTxMode = 1;
                binderWait.notifyAll();
            }
            if (prev != null) {
                try { prev.unlinkToDeath(this, 0); } catch (Throwable ignored) {}
            }
            try {
                next.linkToDeath(this, 0);
            } catch (RemoteException re) {
                clearBinder(next);
            }
        }

        private void clearBinder(IBinder expected) {
            synchronized (binderWait) {
                if (this.remoteBinder == expected) {
                    try { expected.unlinkToDeath(this, 0); } catch (Throwable ignored) {}
                    this.remoteBinder = null;
                }
            }
        }

        @Override
        public void binderDied() {
            IBinder cur = this.remoteBinder;
            if (cur != null) {
                clearBinder(cur);
            }
            wakeWorker();
        }

        void updateIntervals(long pingMs, long startMs) {
            this.pingIntervalMs = clamp(pingMs, MIN_PING_MS, MAX_PING_MS);
            this.startIntervalMs = clamp(startMs, MIN_START_MS, MAX_START_MS);
            wakeWorker();
        }

        void wakeWorker() {
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }
        }

        void stop() {
            running = false;
            IBinder cur = this.remoteBinder;
            if (cur != null) {
                clearBinder(cur);
            }
            wakeWorker();
            synchronized (binderWait) {
                binderWait.notifyAll();
            }
            Thread t = worker;
            if (t != null) {
                t.interrupt();
            }
        }

        String startAndAwaitInitial(long timeoutMs) {
            worker = new Thread(this::runLoop, "ka-" + component.getShortClassName());
            worker.setDaemon(true);
            worker.start();
            try {
                firstOutcomeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            String out = firstOutcome;
            if (out != null) return out;
            return "OK " + describeSummary();
        }

        private boolean performBinderPulse(IBinder b) throws RemoteException {
            if (binderTxMode == 1) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    boolean handled = b.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
                    if (handled) {
                        reply.readException();
                        return true;
                    }
                    binderTxMode = 2;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
            return b.pingBinder();
        }

        private void runLoop() {
            // 1. Initial startService + ResultReceiver handshake
            try {
                ComponentName started = bridge.startService(component, wireReceiver);
                if (started == null) {
                    lastError = "Service not found: " + component.flattenToShortString();
                    firstOutcome = "ERR " + lastError;
                    running = false;
                    firstOutcomeLatch.countDown();
                    return;
                }
                serviceStartOk++;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + safeMsg(e.getMessage());
                firstOutcome = "ERR " + lastError;
                running = false;
                firstOutcomeLatch.countDown();
                return;
            }

            // Wait briefly for optional ResultReceiver binder reply
            long deadline = SystemClock.elapsedRealtime() + HANDSHAKE_WAIT_MS;
            synchronized (binderWait) {
                while (running && remoteBinder == null) {
                    long rem = deadline - SystemClock.elapsedRealtime();
                    if (rem <= 0L) break;
                    try {
                        binderWait.wait(rem);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }

            IBinder initialBinder = remoteBinder;
            if (initialBinder != null) {
                try {
                    if (performBinderPulse(initialBinder)) {
                        binderTxOk++;
                    }
                } catch (Throwable ignored) {}
            }
            firstOutcome = "OK " + describeSummary();
            firstOutcomeLatch.countDown();

            long nextStartAt = SystemClock.elapsedRealtime() + startIntervalMs;
            long nextPingAt = SystemClock.elapsedRealtime() + pingIntervalMs;

            while (running) {
                long now = SystemClock.elapsedRealtime();
                IBinder b = remoteBinder;
                long wakeAt = (b != null) ? Math.min(nextPingAt, nextStartAt) : nextStartAt;
                long sleepMs = wakeAt - now;
                if (sleepMs > 0L) {
                    synchronized (wakeLock) {
                        if (!running) break;
                        try {
                            wakeLock.wait(sleepMs);
                        } catch (InterruptedException ie) {
                            if (!running) break;
                        }
                    }
                    continue;
                }

                now = SystemClock.elapsedRealtime();
                b = remoteBinder;

                if (b != null && now >= nextPingAt) {
                    try {
                        boolean alive = performBinderPulse(b);
                        if (alive) {
                            binderTxOk++;
                            lastError = "";
                        } else {
                            clearBinder(b);
                            nextStartAt = now;
                        }
                    } catch (DeadObjectException doe) {
                        clearBinder(b);
                        nextStartAt = now;
                    } catch (Throwable t) {
                        // Includes BR_FROZEN_REPLY if target froze while host was also frozen:
                        // immediately ask system_server to thaw the target service.
                        errorCount++;
                        lastError = t.getClass().getSimpleName() + ": " + safeMsg(t.getMessage());
                        try {
                            ComponentName cn = bridge.startService(component, null);
                            if (cn != null) {
                                serviceStartOk++;
                                unfreezeAssists++;
                            }
                            Thread.sleep(150L);
                        } catch (Throwable ignored) {}
                    }
                    nextPingAt = SystemClock.elapsedRealtime() + pingIntervalMs;
                }

                now = SystemClock.elapsedRealtime();
                if (running && now >= nextStartAt) {
                    try {
                        ResultReceiver rr = (remoteBinder == null) ? wireReceiver : null;
                        ComponentName cn = bridge.startService(component, rr);
                        if (cn != null) {
                            serviceStartOk++;
                            lastError = "";
                        } else {
                            errorCount++;
                            lastError = "Service returned null";
                        }
                    } catch (IllegalStateException bgEx) {
                        // Caller temporarily in background without exemption; keep binder loop alive.
                        errorCount++;
                        lastError = "BackgroundStartRestricted";
                    } catch (Throwable t) {
                        errorCount++;
                        lastError = t.getClass().getSimpleName() + ": " + safeMsg(t.getMessage());
                    }
                    nextStartAt = SystemClock.elapsedRealtime() + startIntervalMs;
                    if (remoteBinder == null) {
                        nextPingAt = nextStartAt;
                    }
                }
            }
        }

        String modeName() {
            return (remoteBinder != null) ? "binder+service" : "service-only";
        }

        String describeSummary() {
            if (remoteBinder != null) {
                return component.flattenToShortString()
                    + " (mode=binder+service, ping=" + pingIntervalMs
                    + "ms, start=" + startIntervalMs + "ms)";
            }
            return component.flattenToShortString()
                + " (mode=service-only, start=" + startIntervalMs + "ms)";
        }

        String toJson() {
            long upSec = Math.max(0L, (SystemClock.elapsedRealtime() - createdElapsedMs) / 1000L);
            return "{"
                + "\"component\":\"" + esc(component.flattenToShortString()) + "\""
                + ",\"package\":\"" + esc(component.getPackageName()) + "\""
                + ",\"class\":\"" + esc(component.getClassName()) + "\""
                + ",\"mode\":\"" + modeName() + "\""
                + ",\"binder_connected\":" + (remoteBinder != null)
                + ",\"ping_interval_ms\":" + pingIntervalMs
                + ",\"start_interval_ms\":" + startIntervalMs
                + ",\"binder_tx_ok\":" + binderTxOk
                + ",\"service_start_ok\":" + serviceStartOk
                + ",\"unfreeze_assists\":" + unfreezeAssists
                + ",\"errors\":" + errorCount
                + ",\"uptime_sec\":" + upSec
                + ",\"last_error\":\"" + esc(lastError) + "\""
                + "}";
        }
    }

    private static long clamp(long val, long lo, long hi) {
        return Math.max(lo, Math.min(hi, val));
    }

    private static String safeMsg(String m) {
        if (m == null) return "";
        return m.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    private static String socketNameForUid(int uid) {
        return "binder-keepalive-" + uid;
    }

    private static ComponentName parseComponent(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash >= s.length() - 1 || s.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        String pkg = s.substring(0, slash).trim();
        String cls = s.substring(slash + 1).trim();
        if (pkg.isEmpty() || cls.isEmpty() || pkg.contains(" ") || cls.contains(" ")) {
            return null;
        }
        if (cls.charAt(0) == '.') {
            cls = pkg + cls;
        }
        return new ComponentName(pkg, cls);
    }

    private static void relaxHiddenApiIfPossible() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", new Class<?>[0]);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", new Class<?>[]{String[].class});
            Object rt = getRuntime.invoke(null);
            setExemptions.invoke(rt, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Daemon Mode
    // -------------------------------------------------------------------------

    private final AmsBridge mBridge;
    private final LocalServerSocket mServer;
    private final ConcurrentHashMap<ComponentName, TargetEntry> mTargets = new ConcurrentHashMap<>();
    private final long mDaemonStartedElapsedMs = SystemClock.elapsedRealtime();
    private volatile boolean mAccepting = true;

    private BinderKeepAlive(AmsBridge bridge, LocalServerSocket server) {
        this.mBridge = bridge;
        this.mServer = server;
    }

    private void serve() {
        while (mAccepting) {
            final LocalSocket client;
            try {
                client = mServer.accept();
            } catch (IOException ioe) {
                break;
            }
            if (client == null) continue;
            Thread t = new Thread(() -> handleClient(client), "ka-ipc");
            t.setDaemon(true);
            t.start();
        }
        for (TargetEntry entry : mTargets.values()) {
            entry.stop();
        }
        mTargets.clear();
    }

    private void shutdownDaemon() {
        mAccepting = false;
        try {
            mServer.close();
        } catch (IOException ignored) {}
    }

    private void handleClient(LocalSocket client) {
        boolean closeDaemonAfterReply = false;
        try {
            Credentials creds = client.getPeerCredentials();
            if (creds == null || creds.getUid() != mBridge.uid) {
                return;
            }
            client.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));

            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.trim().split("\\s+");
            String cmd = parts.length > 0 ? parts[0] : "";

            if ("PING".equals(cmd)) {
                out.write("OK PONG pid=" + Os.getpid() + "\n");
            } else if ("START".equals(cmd)) {
                if (parts.length < 2) {
                    out.write("ERR Missing <package>/<service> component\n");
                    closeDaemonAfterReply = mTargets.isEmpty();
                } else {
                    ComponentName cn = parseComponent(parts[1]);
                    if (cn == null) {
                        out.write("ERR Invalid component '" + parts[1]
                            + "'; expected <package>/<service> (e.g. com.example/.MyService)\n");
                        closeDaemonAfterReply = mTargets.isEmpty();
                    } else {
                        long pingMs = (parts.length >= 3) ? parseLongOr(parts[2], DEFAULT_PING_MS) : DEFAULT_PING_MS;
                        long startMs = (parts.length >= 4) ? parseLongOr(parts[3], DEFAULT_START_MS) : DEFAULT_START_MS;
                        TargetEntry existing = mTargets.get(cn);
                        if (existing != null && existing.running) {
                            existing.updateIntervals(pingMs, startMs);
                            out.write("OK Updated keepalive: " + existing.describeSummary() + "\n");
                        } else {
                            TargetEntry created = new TargetEntry(cn, mBridge, pingMs, startMs);
                            mTargets.put(cn, created);
                            String res = created.startAndAwaitInitial(1800L);
                            if (res.startsWith("ERR ")) {
                                created.stop();
                                mTargets.remove(cn, created);
                                out.write(res + "\n");
                                closeDaemonAfterReply = mTargets.isEmpty();
                            } else {
                                out.write("OK Started keepalive: " + res.substring(3) + "\n");
                            }
                        }
                    }
                }
            } else if ("STOP".equals(cmd)) {
                String arg = (parts.length >= 2) ? parts[1] : "--all";
                if ("--all".equals(arg) || "all".equals(arg)) {
                    int n = mTargets.size();
                    for (Map.Entry<ComponentName, TargetEntry> e : mTargets.entrySet()) {
                        e.getValue().stop();
                    }
                    mTargets.clear();
                    out.write("OK Stopped all keepalive targets (" + n + "); daemon exiting\n");
                    closeDaemonAfterReply = true;
                } else {
                    ComponentName cn = parseComponent(arg);
                    if (cn == null) {
                        out.write("ERR Invalid component '" + arg + "'; expected <package>/<service> or --all\n");
                    } else {
                        TargetEntry removed = mTargets.remove(cn);
                        if (removed == null) {
                            out.write("ERR Target not active: " + cn.flattenToShortString() + "\n");
                        } else {
                            removed.stop();
                            if (mTargets.isEmpty()) {
                                out.write("OK Stopped " + cn.flattenToShortString() + "; no targets left, daemon exiting\n");
                                closeDaemonAfterReply = true;
                            } else {
                                out.write("OK Stopped " + cn.flattenToShortString()
                                    + " (" + mTargets.size() + " still active)\n");
                            }
                        }
                    }
                }
            } else if ("STATUS".equals(cmd)) {
                out.write(formatHumanStatus());
            } else if ("STATUS_JSON".equals(cmd)) {
                out.write(formatJsonStatus() + "\n");
            } else {
                out.write("ERR Unknown command: " + cmd + "\n");
            }
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            if (closeDaemonAfterReply) {
                shutdownDaemon();
            }
        }
    }

    private List<TargetEntry> sortedTargets() {
        List<TargetEntry> list = new ArrayList<>(mTargets.values());
        Collections.sort(list, Comparator.comparing(a -> a.component.flattenToShortString()));
        return list;
    }

    private String formatHumanStatus() {
        long upSec = Math.max(0L, (SystemClock.elapsedRealtime() - mDaemonStartedElapsedMs) / 1000L);
        StringBuilder sb = new StringBuilder();
        sb.append("Daemon   : RUNNING (pid=").append(Os.getpid())
          .append(", caller=").append(mBridge.callingPackage)
          .append(", uptime=").append(upSec).append("s)\n");
        List<TargetEntry> list = sortedTargets();
        sb.append("Targets  : ").append(list.size()).append('\n');
        for (TargetEntry e : list) {
            sb.append("  - ").append(e.component.flattenToShortString())
              .append(" | mode=").append(e.modeName())
              .append(" | ping=").append(e.pingIntervalMs).append("ms")
              .append(" | start=").append(e.startIntervalMs).append("ms")
              .append(" | binder_tx=").append(e.binderTxOk)
              .append(" | svc_tx=").append(e.serviceStartOk);
            if (e.unfreezeAssists > 0L) {
                sb.append(" | thaws=").append(e.unfreezeAssists);
            }
            if (!e.lastError.isEmpty()) {
                sb.append(" | last_err=").append(e.lastError);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String formatJsonStatus() {
        long upSec = Math.max(0L, (SystemClock.elapsedRealtime() - mDaemonStartedElapsedMs) / 1000L);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"running\":true,\"pid\":").append(Os.getpid())
          .append(",\"uid\":").append(mBridge.uid)
          .append(",\"calling_package\":\"").append(esc(mBridge.callingPackage)).append("\"")
          .append(",\"uptime_sec\":").append(upSec)
          .append(",\"targets\":[");
        List<TargetEntry> list = sortedTargets();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i).toJson());
        }
        sb.append("]}");
        return sb.toString();
    }

    private static long parseLongOr(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    // -------------------------------------------------------------------------
    // CLI Client Mode (Fallback when python3 is unavailable)
    // -------------------------------------------------------------------------

    private static int runCtl(String[] args) {
        if (args.length < 2) {
            System.err.println("Missing ctl command");
            return 2;
        }
        StringBuilder cmd = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) cmd.append(' ');
            cmd.append(args[i]);
        }
        String sockName = socketNameForUid(Os.getuid());
        try (LocalSocket sock = new LocalSocket()) {
            sock.connect(new LocalSocketAddress(sockName, LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(4000);
            BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
            out.write(cmd.toString());
            out.write('\n');
            out.flush();

            BufferedReader in = new BufferedReader(
                new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String first = in.readLine();
            if (first == null) return 1;
            if (first.startsWith("ERR ")) {
                System.err.println("Error: " + first.substring(4));
                return 1;
            }
            if (first.startsWith("OK ")) {
                System.out.println(first.substring(3));
            } else {
                System.out.println(first);
            }
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
            return 0;
        } catch (IOException ioe) {
            return 3; // Daemon not running
        }
    }

    public static void main(String[] args) {
        relaxHiddenApiIfPossible();
        String mode = (args.length > 0) ? args[0] : "daemon";
        if ("ctl".equals(mode)) {
            System.exit(runCtl(args));
            return;
        }

        String overridePkg = (args.length >= 2) ? args[1] : "";
        final LocalServerSocket server;
        try {
            server = new LocalServerSocket(socketNameForUid(Os.getuid()));
        } catch (IOException alreadyRunning) {
            // Another daemon instance already holds the per-UID abstract socket.
            System.exit(0);
            return;
        }

        final AmsBridge bridge;
        try {
            bridge = new AmsBridge(overridePkg);
        } catch (Throwable t) {
            try { server.close(); } catch (IOException ignored) {}
            System.err.println("Failed to initialize AmsBridge: " + t);
            System.exit(1);
            return;
        }

        new BinderKeepAlive(bridge, server).serve();
        System.exit(0);
    }
}
