package com.avf.linux;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Formalized Mealy state machine governing VM and disk lifecycle.
 *
 * Control state tuple: (P, T, L)
 *   - P in Phase: STOPPED | ERROR | STARTING | RUNNING | STOPPING | IMPORTING | RESETTING
 *   - T in Target: IDLE | RUN(desiredGen) | IMPORT(job) | RESET(job)
 *   - L in Lifecycle: RESIDENT | EXIT_ON_IDLE(startId) | DISPOSED
 *
 * Structural invariants enforced on every transition (assertInvariantLocked):
 *   I1 (Session Presence):  (P in {RUNNING, STOPPING}) <==> (mSession != null)
 *   I2 (Single In-Flight):  At most one Effect (Boot | Stop | Maintain) is active at any instant,
 *                           strictly while P in {STARTING, STOPPING, IMPORTING, RESETTING}.
 *   I3 (Disk Exclusivity):  P in {IMPORTING, RESETTING} ==> mSession == null.
 *   I4 (Pending Job):       (T in {IMPORT, RESET}) <==> (mPendingJob != null).
 *
 * Every external command mutates only (T, L) (or rejects invalid preconditions) and invokes
 * the pure deterministic selector reconcileLocked(), which emits at most one Effect executed
 * strictly outside mLock.
 */
public final class VmController {
    public static final long GRACEFUL_STOP_TIMEOUT_MS = 15000L;
    public static final long EXIT_STOP_TIMEOUT_MS = 4500L;
    public static final long DESTROY_STOP_TIMEOUT_MS = 2500L;
    private static final int MAX_VMLOG_CHARS = 65536;

    private enum Target {
        IDLE,
        RUN,
        IMPORT,
        RESET;

        boolean isMaintenance() {
            return this == IMPORT || this == RESET;
        }
    }

    private enum Lifecycle {
        RESIDENT,
        EXIT_ON_IDLE,
        DISPOSED
    }

    public interface SessionHandle {
        long id();
        long generation();
        int memMib();
        int diskGb();
        int sshPort();
        boolean keepAwake();
        String startupBanner();
        void activateBridges();
        void markIntentionalStop();
        /** Records the first terminal event of this VM run and wakes awaitStopped(). */
        void recordExit(SessionExit exit);
        /** The recorded terminal event, or null while the VM is still running. */
        SessionExit exit();
        boolean isTerminated();
        boolean sendPoweroffOnce();
        boolean awaitStopped(long timeoutMs) throws InterruptedException;
        void flushOutput(long timeoutMs);
        void writeConsoleInput(byte[] data);
        void close(boolean stopVmInstance);
    }

    /**
     * Terminal event of one VM run. Stored on its session (first event wins) so that an exit
     * arriving before the boot effect installs the session is replayed rather than lost.
     */
    public static final class SessionExit {
        final boolean rebootRequested;
        final int reasonCode;
        final String error;

        private SessionExit(boolean rebootRequested, int reasonCode, String error) {
            this.rebootRequested = rebootRequested;
            this.reasonCode = reasonCode;
            this.error = error;
        }

        public static SessionExit stopped(boolean rebootRequested, int reasonCode) {
            return new SessionExit(rebootRequested, reasonCode, null);
        }

        public static SessionExit failed(int errorCode, String message) {
            return new SessionExit(false, errorCode, "VM error (" + errorCode + "): " + message);
        }
    }

    public interface MaintenanceJob {
        String run(int diskGb) throws Exception;
    }

    public interface Host {
        boolean hasDiskImage();
        String getDiskPath();
        boolean hasStagingImage();
        boolean hasSeedImage();
        void saveKeepAwake(boolean enable);
        SessionHandle bootSession(long sessionId, long generation, ExecutorService callbackExecutor) throws Exception;
        String runImportStaging(int diskGb) throws Exception;
        String runResetSeed(int diskGb) throws Exception;
        void persistSessionLogs(String consoleText, String vmLogText);
        void onStateInvalidated();
        void onIdleServiceExitRequested(int startId);
        void logInfo(String msg);
        void logWarn(String msg);
        void logError(String msg, Throwable t);
    }

    public interface Listener {
        void onVmUpdated();
    }

    /**
     * Side-effect descriptor computed purely inside mLock and executed strictly outside mLock.
     */
    private static final class Transition {
        VmStatus publishedStatus;
        String logNotice;
        SessionHandle immediatePoweroffSession;
        Runnable asyncEffect;
        SessionHandle activateBridgesSession;
        List<Runnable> quiescentCallbacks;
        boolean fireServiceExit;
        int serviceExitStartId;
    }

    private final Host mHost;
    private final ConsoleBuffer mConsole = new ConsoleBuffer();
    private final StringBuilder mVmLogBuf = new StringBuilder();
    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();
    private final Object mLock = new Object();
    private final List<Runnable> mQuiescentWaiters = new ArrayList<>(2);

    private final ExecutorService mWorkExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vm-fsm-worker");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService mCbExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vm-cb-exec");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService mPowerExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vm-power-exec");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService mConsoleInExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vm-con-in");
        t.setDaemon(true);
        return t;
    });

    // Formal state tuple (P, T, L) + orthogonal projection attributes (all guarded by mLock)
    private VmStatus.State mPhase = VmStatus.State.STOPPED;
    private Target mTarget = Target.IDLE;
    private Lifecycle mLifecycle = Lifecycle.RESIDENT;

    private SessionHandle mSession;
    private long mDesiredGen = 1L;
    private long mNextSessionId = 0L;
    private long mStopTimeoutMs = GRACEFUL_STOP_TIMEOUT_MS;
    private String mPendingExitNotice;
    private String mPendingAsyncError;

    private MaintenanceJob mPendingJob;
    private String mPendingBanner;

    private boolean mExitNotificationArmed = false;
    private int mExitStartId = -1;

    private long mSeq = 0L;
    private String mGuestIp = "";
    private String mNetMode = "Host Net";
    private boolean mVmNetConnected = false;
    private boolean mKeepAwake = false;
    private boolean mWakeLockHeld = false;
    private int mMemMib = VmConfigStore.DEFAULT_MEM_MIB;
    private int mDiskGb = VmConfigStore.DEFAULT_DISK_GB;
    private int mSshPort = VmConfigStore.DEFAULT_SSH_LOCAL_PORT;
    private int mCtlPort = VmConfigStore.DEFAULT_CTL_PORT;
    private long mStartedAtMs = 0L;
    private String mLastError = "";

    private volatile VmStatus mPublishedStatus = VmStatus.initialDefaults();
    private volatile long mActiveConsoleSessionId = 0L;

    public VmController(Host host, Properties initialProps, VmConfigStore store) {
        mHost = host;
        synchronized (mLock) {
            if (initialProps != null && store != null) {
                mMemMib = store.getIntProp(initialProps, "memory_mib", VmConfigStore.DEFAULT_MEM_MIB);
                mDiskGb = store.getIntProp(initialProps, "disk_gb", VmConfigStore.DEFAULT_DISK_GB);
                mSshPort = store.getIntProp(initialProps, "ssh_local_port", VmConfigStore.DEFAULT_SSH_LOCAL_PORT);
                mCtlPort = store.getIntProp(initialProps, "ctl_port", VmConfigStore.DEFAULT_CTL_PORT);
                mKeepAwake = store.getBoolProp(initialProps, "keep_awake", false);
            }
            assertInvariantLocked();
            mPublishedStatus = snapshotStatusLocked();
        }
    }

    public VmStatus getStatus() {
        return mPublishedStatus;
    }

    public ConsoleBuffer getConsole() {
        return mConsole;
    }

    public void addListener(Listener listener) {
        if (listener != null) mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        if (listener != null) mListeners.remove(listener);
    }

    private void notifyListeners() {
        for (Listener l : mListeners) {
            try { l.onVmUpdated(); } catch (Throwable ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Session Output & Orthogonal Environment Projections
    // -------------------------------------------------------------------------

    public void appendLog(String raw) {
        if (raw == null || raw.isEmpty()) return;
        mConsole.append(raw);
        notifyListeners();
    }

    public void appendSessionConsoleChars(SessionHandle source, char[] buf, int len) {
        if (source == null || buf == null || len <= 0) return;
        if (source.id() < mActiveConsoleSessionId) return;
        mConsole.append(buf, 0, len);
        notifyListeners();
    }

    public void appendSessionVmLogLine(SessionHandle source, String line) {
        if (source == null || line == null) return;
        if (source.id() < mActiveConsoleSessionId) return;
        synchronized (mVmLogBuf) {
            mVmLogBuf.append(line).append('\n');
            if (mVmLogBuf.length() > MAX_VMLOG_CHARS) {
                mVmLogBuf.delete(0, mVmLogBuf.length() - MAX_VMLOG_CHARS);
            }
        }
    }

    public String getVmLogSnapshot() {
        synchronized (mVmLogBuf) {
            return mVmLogBuf.toString();
        }
    }

    public void clearConsole() {
        mConsole.clear();
        notifyListeners();
    }

    private void clearVmLog() {
        synchronized (mVmLogBuf) {
            mVmLogBuf.setLength(0);
        }
    }

    public void sendConsoleInput(final byte[] data) {
        if (data == null || data.length == 0) return;
        final SessionHandle session;
        synchronized (mLock) {
            if (mLifecycle == Lifecycle.DISPOSED || mPhase != VmStatus.State.RUNNING) return;
            session = mSession;
        }
        if (session == null) return;
        try {
            mConsoleInExec.execute(() -> session.writeConsoleInput(data));
        } catch (Throwable ignored) {}
    }

    public void setKeepAwake(boolean enable) {
        VmStatus snap;
        synchronized (mLock) {
            mKeepAwake = enable;
            snap = snapshotStatusLocked();
            mPublishedStatus = snap;
        }
        mHost.saveKeepAwake(enable);
        appendLog("[AVF Linux] WakeLock policy: "
            + (enable ? "ON (PARTIAL_WAKE_LOCK held while VM runs)" : "OFF (host suspend allowed)") + "\n");
        mHost.onStateInvalidated();
        notifyListeners();
    }

    public void toggleKeepAwake() {
        boolean next;
        synchronized (mLock) {
            next = !mKeepAwake;
        }
        setKeepAwake(next);
    }

    public void updateWakeLockHeld(boolean held) {
        boolean changed = false;
        synchronized (mLock) {
            if (mWakeLockHeld != held) {
                mWakeLockHeld = held;
                mPublishedStatus = snapshotStatusLocked();
                changed = true;
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void updateNetworkMode(String netMode) {
        if (netMode == null) return;
        VmStatus snap = null;
        synchronized (mLock) {
            if (!netMode.equals(mNetMode)) {
                mNetMode = netMode;
                snap = snapshotStatusLocked();
                mPublishedStatus = snap;
            }
        }
        if (snap != null) {
            mHost.onStateInvalidated();
            notifyListeners();
        }
    }

    public void onSessionVmNetStateChanged(SessionHandle session, boolean connected, String guestIp) {
        VmStatus snap = null;
        synchronized (mLock) {
            if (mSession != session || mPhase != VmStatus.State.RUNNING) return;
            String nextIp = (guestIp != null) ? guestIp : "";
            if (mVmNetConnected != connected || !nextIp.equals(mGuestIp)) {
                mVmNetConnected = connected;
                mGuestIp = nextIp;
                snap = snapshotStatusLocked();
                mPublishedStatus = snap;
            }
        }
        if (snap != null) {
            mHost.onStateInvalidated();
            notifyListeners();
        }
    }

    // -------------------------------------------------------------------------
    // External Commands (Input Alphabet -> Target/Lifecycle Mutations)
    // -------------------------------------------------------------------------

    public int requestStart() {
        boolean hasDisk = mHost.hasDiskImage();
        String diskPath = hasDisk ? "" : mHost.getDiskPath();
        Transition tr = new Transition();
        int rc;

        synchronized (mLock) {
            if (mLifecycle == Lifecycle.DISPOSED || isTransitioningLocked()) {
                return 409;
            }
            mLifecycle = Lifecycle.RESIDENT;
            mExitNotificationArmed = false;

            if (mPhase == VmStatus.State.RUNNING) {
                mTarget = Target.RUN;
                mDesiredGen = mSession.generation();
                assertInvariantLocked();
                rc = 200;
            } else if (!hasDisk) {
                mTarget = Target.IDLE;
                mPhase = VmStatus.State.ERROR;
                mLastError = "Disk image not found at " + diskPath;
                tr.logNotice = "[AVF Linux] " + mLastError + "\n";
                reconcileLocked(tr, true);
                rc = 400;
            } else {
                mTarget = Target.RUN;
                reconcileLocked(tr, false);
                rc = 202;
            }
        }

        if (rc == 200) {
            appendLog("[AVF Linux] VM is already running.\n");
            return 200;
        }
        executeTransition(tr);
        return rc;
    }

    public int requestStop() {
        Transition tr = new Transition();
        int rc;

        synchronized (mLock) {
            if (mLifecycle == Lifecycle.DISPOSED
                    || mPhase.isMaintenance()
                    || mTarget.isMaintenance()) {
                return 409;
            }
            boolean wasQuiescent = isQuiescentLocked();
            mTarget = Target.IDLE;
            mStopTimeoutMs = GRACEFUL_STOP_TIMEOUT_MS;
            reconcileLocked(tr, false);
            rc = wasQuiescent ? 200 : 202;
        }

        executeTransition(tr);
        return rc;
    }

    public boolean requestRestart() {
        if (!mHost.hasDiskImage()) {
            return false;
        }
        Transition tr = new Transition();

        synchronized (mLock) {
            if (mLifecycle == Lifecycle.DISPOSED || isTransitioningLocked()) {
                return false;
            }
            mLifecycle = Lifecycle.RESIDENT;
            mExitNotificationArmed = false;
            mTarget = Target.RUN;
            mDesiredGen++;
            mStopTimeoutMs = GRACEFUL_STOP_TIMEOUT_MS;
            mLastError = "";
            reconcileLocked(tr, false);
        }

        executeTransition(tr);
        return true;
    }

    public int requestImportStaging() {
        boolean hasStaging = mHost.hasStagingImage();
        return enqueueMaintenance(
            Target.IMPORT,
            hasStaging ? null : "No seed.simg.gz, disk.simg, or disk.img found in staging",
            "[AVF Linux] Importing VM disk image from staging...\n",
            mHost::runImportStaging
        );
    }

    public int requestReset() {
        boolean hasSeed = mHost.hasSeedImage();
        return enqueueMaintenance(
            Target.RESET,
            hasSeed ? null : "Golden seed (seed.simg.gz) not found; cannot reset",
            "[AVF Linux] Resetting VM disk from golden seed...\n",
            mHost::runResetSeed
        );
    }

    public int requestCustomImport(String banner, MaintenanceJob job) {
        return enqueueMaintenance(Target.IMPORT, null, banner, job);
    }

    public void reportRejectedMaintenance(String message) {
        Transition tr = new Transition();
        synchronized (mLock) {
            tr.logNotice = "[AVF Linux] " + message + "\n";
            if (isQuiescentLocked()) {
                mPhase = VmStatus.State.ERROR;
                mLastError = message;
                reconcileLocked(tr, true);
            }
        }
        executeTransition(tr);
    }

    private int enqueueMaintenance(
            Target maintTarget, String preconditionError, String banner, MaintenanceJob job) {
        Transition tr = new Transition();
        int rc;

        synchronized (mLock) {
            if (mLifecycle == Lifecycle.DISPOSED || isTransitioningLocked()) {
                return 409;
            }
            if (preconditionError != null) {
                tr.logNotice = "[AVF Linux] " + preconditionError + "\n";
                if (mPhase != VmStatus.State.RUNNING) {
                    mPhase = VmStatus.State.ERROR;
                    mLastError = preconditionError;
                    reconcileLocked(tr, true);
                }
                rc = 400;
            } else {
                mLifecycle = Lifecycle.RESIDENT;
                mExitNotificationArmed = false;
                mTarget = maintTarget;
                mPendingJob = job;
                mPendingBanner = banner;
                mStopTimeoutMs = GRACEFUL_STOP_TIMEOUT_MS;
                mLastError = "";
                reconcileLocked(tr, false);
                rc = 202;
            }
        }

        executeTransition(tr);
        return rc;
    }

    public void onTaskRemoved(int startId) {
        Transition tr = new Transition();
        synchronized (mLock) {
            if (mLifecycle == Lifecycle.DISPOSED) return;
            mLifecycle = Lifecycle.EXIT_ON_IDLE;
            mExitNotificationArmed = true;
            mExitStartId = startId;
            if (!mPhase.isMaintenance() && !mTarget.isMaintenance()) {
                mTarget = Target.IDLE;
                mStopTimeoutMs = GRACEFUL_STOP_TIMEOUT_MS;
            }
            reconcileLocked(tr, false);
        }
        executeTransition(tr);
    }

    public void onHostShutdown(long timeoutMs, Runnable onQuiescent) {
        Transition tr = new Transition();
        synchronized (mLock) {
            if (mLifecycle == Lifecycle.DISPOSED) {
                if (onQuiescent != null) onQuiescent.run();
                return;
            }
            if (onQuiescent != null) {
                mQuiescentWaiters.add(onQuiescent);
            }
            // Cancel any not-yet-started target so host shutdown converges straight to IDLE
            if (!mPhase.isMaintenance()) {
                mTarget = Target.IDLE;
                mPendingJob = null;
                mPendingBanner = null;
            }
            mStopTimeoutMs = timeoutMs;
            reconcileLocked(tr, false);
        }
        executeTransition(tr);
    }

    public void dispose(long maxWaitMs) {
        final CountDownLatch quiescentLatch = new CountDownLatch(1);
        Transition tr = new Transition();
        synchronized (mLock) {
            if (mLifecycle == Lifecycle.DISPOSED) return;
            mLifecycle = Lifecycle.DISPOSED;
            mExitNotificationArmed = false;
            mTarget = Target.IDLE;
            mPendingJob = null;
            mPendingBanner = null;
            mStopTimeoutMs = maxWaitMs;
            mQuiescentWaiters.add(quiescentLatch::countDown);
            reconcileLocked(tr, false);
        }
        executeTransition(tr);

        if (quiescentLatch.getCount() > 0L) {
            try {
                quiescentLatch.await(Math.max(50L, maxWaitMs + 250L), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        // Emergency backstop if an in-flight stop exceeded the destroy deadline
        SessionHandle residual;
        synchronized (mLock) {
            residual = mSession;
        }
        if (residual != null) {
            residual.close(true);
        }

        mHost.persistSessionLogs(mConsole.getTextSnapshot(), getVmLogSnapshot());
        mPowerExec.shutdownNow();
        mWorkExec.shutdownNow();
        mCbExec.shutdownNow();
        mConsoleInExec.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Asynchronous Session Exit Signals (from VirtualMachineCallback on mCbExec)
    // -------------------------------------------------------------------------

    public void onSessionStopped(SessionHandle session, boolean guestRebootRequested, int reasonCode) {
        onSessionExit(session, SessionExit.stopped(guestRebootRequested, reasonCode));
    }

    public void onSessionError(SessionHandle session, int errorCode, String message) {
        onSessionExit(session, SessionExit.failed(errorCode, message));
    }

    private void onSessionExit(SessionHandle session, SessionExit exit) {
        if (session == null) return;
        // Record before taking mLock: if the boot effect has not installed this session yet,
        // it replays the recorded exit when it does (runBootEffect).
        session.recordExit(exit);

        Transition tr;
        synchronized (mLock) {
            // Outside RUNNING, recordExit() has already woken Effect.Stop
            if (mPhase != VmStatus.State.RUNNING || mSession != session) {
                return;
            }
            applyExitLocked(session.exit());
            tr = new Transition();
            reconcileLocked(tr, false);
        }
        executeTransition(tr);
    }

    /** Retargets after a session exit; the next reconcileLocked() then applies Rule R1. */
    private void applyExitLocked(SessionExit exit) {
        boolean reboot = exit.error == null
            && exit.rebootRequested
            && mTarget == Target.RUN
            && mLifecycle == Lifecycle.RESIDENT;
        if (reboot) {
            mDesiredGen++;
        } else {
            mTarget = Target.IDLE;
        }
        if (exit.error != null) {
            mPendingAsyncError = exit.error;
            mPendingExitNotice = "[AVF Linux] " + exit.error + "\n";
        } else {
            mPendingExitNotice = "[AVF Linux] VM stopped (reason=" + exit.reasonCode
                + (reboot ? ", rebooting..." : "") + ")\n";
        }
    }

    // -------------------------------------------------------------------------
    // Effect Implementations & Atomic Completion Transitions (run on mWorkExec)
    // -------------------------------------------------------------------------

    private void runBootEffect(final long sessionId, final long generation) {
        mHost.persistSessionLogs(mConsole.getTextSnapshot(), getVmLogSnapshot());
        mActiveConsoleSessionId = sessionId;
        clearConsole();
        clearVmLog();

        SessionHandle booted = null;
        Throwable failure = null;
        try {
            booted = mHost.bootSession(sessionId, generation, mCbExec);
        } catch (Throwable t) {
            failure = t;
        }

        Transition tr = new Transition();
        synchronized (mLock) {
            if (booted != null) {
                mSession = booted;
                mMemMib = booted.memMib();
                mDiskGb = booted.diskGb();
                mSshPort = booted.sshPort();
                mKeepAwake = booted.keepAwake();
                mStartedAtMs = System.currentTimeMillis();
                mPhase = VmStatus.State.RUNNING;

                // The VM may have exited before this install, when its callback found no
                // RUNNING session to act on; replay that exit here.
                SessionExit early = booted.exit();
                if (early != null) {
                    applyExitLocked(early);
                }

                // Reconcile immediately: if Target changed during boot (or the VM already
                // exited), Rule R1 transitions straight into STOPPING without ever exposing
                // RUNNING or starting bridges.
                reconcileLocked(tr, true);
                if (mPhase == VmStatus.State.RUNNING && mSession == booted) {
                    tr.activateBridgesSession = booted;
                    tr.logNotice = booted.startupBanner();
                }
            } else {
                boolean wasCancelled = (mTarget != Target.RUN || mLifecycle != Lifecycle.RESIDENT);
                if (wasCancelled) {
                    mPhase = VmStatus.State.STOPPED;
                    mLastError = "";
                } else {
                    mTarget = Target.IDLE;
                    mPhase = VmStatus.State.ERROR;
                    mLastError = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    tr.logNotice = "[AVF Linux] Start failed: " + mLastError + "\n";
                }
                reconcileLocked(tr, true);
            }
        }

        if (failure != null && tr.logNotice != null) {
            mHost.logError("Failed to start VM", failure);
        }
        executeTransition(tr);
    }

    private void runStopEffect(
            final SessionHandle session,
            final long timeoutMs,
            final String exitNotice,
            final String asyncError) {
        if (!session.isTerminated()) {
            if (timeoutMs > 0L) {
                appendLog("[AVF Linux] Requesting graceful guest shutdown...\n");
                mHost.logInfo("Requesting graceful guest shutdown (session=" + session.id() + ")");
                boolean sent = session.sendPoweroffOnce();
                if (sent) {
                    boolean clean = false;
                    try {
                        clean = session.awaitStopped(timeoutMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    if (clean) {
                        session.flushOutput(50L);
                        appendLog("[AVF Linux] Guest powered off cleanly.\n");
                        mHost.logInfo("Guest powered off cleanly (session=" + session.id() + ")");
                    } else {
                        appendLog("[AVF Linux] Graceful shutdown timed out; forcing VM stop.\n");
                        mHost.logWarn("Graceful shutdown timed out after " + timeoutMs + "ms; forcing vm.stop()");
                    }
                } else {
                    appendLog("[AVF Linux] Guest control channel unavailable; stopping VM directly.\n");
                }
            }
        } else {
            session.flushOutput(50L);
            if (exitNotice != null && !exitNotice.isEmpty()) {
                appendLog(exitNotice);
            }
        }

        session.close(!session.isTerminated());
        mHost.persistSessionLogs(mConsole.getTextSnapshot(), getVmLogSnapshot());

        Transition tr = new Transition();
        synchronized (mLock) {
            mSession = null;
            mGuestIp = "";
            mVmNetConnected = false;
            mStartedAtMs = 0L;
            if (asyncError != null) {
                mPhase = VmStatus.State.ERROR;
                mLastError = asyncError;
            } else {
                mPhase = VmStatus.State.STOPPED;
            }
            reconcileLocked(tr, true);
        }
        executeTransition(tr);
    }

    private void runMaintEffect(
            final VmStatus.State maintPhase,
            final String banner,
            final MaintenanceJob job,
            final int diskGb) {
        if (banner != null && !banner.isEmpty()) {
            appendLog(banner);
            mHost.logInfo(banner.trim());
        }

        String doneMsg = null;
        Throwable failure = null;
        try {
            doneMsg = job.run(diskGb);
        } catch (Throwable t) {
            failure = t;
        }

        String prefix = (maintPhase == VmStatus.State.RESETTING) ? "Reset failed: " : "Import failed: ";
        Transition tr = new Transition();
        synchronized (mLock) {
            if (failure == null) {
                mPhase = VmStatus.State.STOPPED;
                mLastError = "";
                tr.logNotice = "[AVF Linux] " + doneMsg + "\n";
            } else {
                mPhase = VmStatus.State.ERROR;
                mLastError = prefix + failure.getMessage();
                tr.logNotice = "[AVF Linux] " + mLastError + "\n";
            }
            reconcileLocked(tr, true);
        }

        if (failure == null) {
            mHost.logInfo(doneMsg);
        } else {
            mHost.logError("Maintenance task failed", failure);
        }
        executeTransition(tr);
    }

    // -------------------------------------------------------------------------
    // Core Formal Automaton Reconciler (Rules R1 - R4) & Invariant Verifier
    // -------------------------------------------------------------------------

    private boolean isQuiescentPhaseLocked() {
        return mPhase == VmStatus.State.STOPPED || mPhase == VmStatus.State.ERROR;
    }

    private boolean isQuiescentLocked() {
        return isQuiescentPhaseLocked() && mTarget == Target.IDLE;
    }

    private boolean isTransitioningLocked() {
        return mPhase.isBusy() || mTarget.isMaintenance();
    }

    /**
     * Deterministic 4-rule transition selector. Must be called under mLock.
     */
    private void reconcileLocked(Transition out, boolean forcePublish) {
        VmStatus.State prevPhase = mPhase;

        // Rule R1: Active RUNNING session must stop when Target/Lifecycle/Generation diverges
        if (mPhase == VmStatus.State.RUNNING
                && !(mTarget == Target.RUN
                     && mLifecycle == Lifecycle.RESIDENT
                     && mSession.generation() == mDesiredGen)) {
            final SessionHandle s = mSession;
            final long timeout = mStopTimeoutMs;
            final String exitNotice = mPendingExitNotice;
            final String asyncError = mPendingAsyncError;
            mPendingExitNotice = null;
            mPendingAsyncError = null;
            s.markIntentionalStop();
            mPhase = VmStatus.State.STOPPING;
            if (!s.isTerminated() && timeout > 0L) {
                out.immediatePoweroffSession = s;
            }
            out.asyncEffect = () -> runStopEffect(s, timeout, exitNotice, asyncError);
        }
        // Rule R2: Quiescent phase with pending disk maintenance job enters IMPORTING/RESETTING
        else if (isQuiescentPhaseLocked()
                && mTarget.isMaintenance()
                && mLifecycle != Lifecycle.DISPOSED) {
            final VmStatus.State maintPhase = (mTarget == Target.RESET)
                ? VmStatus.State.RESETTING : VmStatus.State.IMPORTING;
            final MaintenanceJob job = mPendingJob;
            final String banner = mPendingBanner;
            final int diskGb = mDiskGb;
            mPendingJob = null;
            mPendingBanner = null;
            mTarget = Target.IDLE;
            mPhase = maintPhase;
            mLastError = "";
            out.asyncEffect = () -> runMaintEffect(maintPhase, banner, job, diskGb);
        }
        // Rule R3: Quiescent phase with RUN target and RESIDENT lifecycle enters STARTING
        else if (isQuiescentPhaseLocked()
                && mTarget == Target.RUN
                && mLifecycle == Lifecycle.RESIDENT) {
            final long sid = ++mNextSessionId;
            final long gen = mDesiredGen;
            mPhase = VmStatus.State.STARTING;
            mGuestIp = "";
            mVmNetConnected = false;
            mLastError = "";
            mStartedAtMs = 0L;
            out.asyncEffect = () -> runBootEffect(sid, gen);
        }

        // Rule R4: Quiescent convergence wakes barriers and triggers deferred service exit
        if (isQuiescentLocked()) {
            if (!mQuiescentWaiters.isEmpty()) {
                out.quiescentCallbacks = new ArrayList<>(mQuiescentWaiters);
                mQuiescentWaiters.clear();
            }
            if (mLifecycle == Lifecycle.EXIT_ON_IDLE && mExitNotificationArmed) {
                mExitNotificationArmed = false;
                out.fireServiceExit = true;
                out.serviceExitStartId = mExitStartId;
            }
        }

        assertInvariantLocked();

        if (forcePublish || mPhase != prevPhase) {
            VmStatus next = snapshotStatusLocked();
            mPublishedStatus = next;
            out.publishedStatus = next;
        }
    }

    private void assertInvariantLocked() {
        boolean expectsSession = (mPhase == VmStatus.State.RUNNING || mPhase == VmStatus.State.STOPPING);
        if (expectsSession != (mSession != null)) {
            throw new IllegalStateException(
                "FSM invariant I1 violated: phase=" + mPhase + ", session=" + mSession);
        }
        if (mPhase.isMaintenance() && mSession != null) {
            throw new IllegalStateException("FSM invariant I3 violated: maintenance with active session");
        }
        if (mTarget.isMaintenance() != (mPendingJob != null)) {
            throw new IllegalStateException(
                "FSM invariant I4 violated: target=" + mTarget + ", pendingJob=" + mPendingJob);
        }
        if (mPendingExitNotice != null || mPendingAsyncError != null) {
            throw new IllegalStateException("FSM invariant I5 violated: unconsumed exit metadata");
        }
    }

    private VmStatus snapshotStatusLocked() {
        return new VmStatus(
            ++mSeq,
            mPhase,
            mGuestIp,
            mNetMode,
            mVmNetConnected,
            mKeepAwake,
            mWakeLockHeld,
            mMemMib,
            mDiskGb,
            mSshPort,
            mCtlPort,
            mStartedAtMs,
            mLastError
        );
    }

    private void executeTransition(Transition tr) {
        if (tr == null) return;
        if (tr.activateBridgesSession != null) {
            tr.activateBridgesSession.activateBridges();
        }
        if (tr.logNotice != null && !tr.logNotice.isEmpty()) {
            appendLog(tr.logNotice);
        }
        if (tr.publishedStatus != null) {
            mHost.onStateInvalidated();
            notifyListeners();
        }
        if (tr.immediatePoweroffSession != null) {
            final SessionHandle s = tr.immediatePoweroffSession;
            try {
                mPowerExec.execute(s::sendPoweroffOnce);
            } catch (Throwable ignored) {}
        }
        if (tr.asyncEffect != null) {
            try {
                mWorkExec.execute(tr.asyncEffect);
            } catch (Throwable ignored) {}
        }
        if (tr.quiescentCallbacks != null) {
            for (Runnable cb : tr.quiescentCallbacks) {
                try { cb.run(); } catch (Throwable ignored) {}
            }
        }
        if (tr.fireServiceExit) {
            mHost.onIdleServiceExitRequested(tr.serviceExitStartId);
        }
    }
}
