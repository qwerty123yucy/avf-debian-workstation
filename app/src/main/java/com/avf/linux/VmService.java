package com.avf.linux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin Android Service shell adapting platform events to VmController and
 * projecting immutable VmStatus snapshots onto Foreground Notification and WakeLock.
 */
public class VmService extends Service
        implements VmController.Host, VmControlServer.Controller, VmSession.Observer {
    private static final String TAG = "AvfLinux";
    private static final String CHANNEL_ID = "avf_linux_vm";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.avf.linux.ACTION_START";
    public static final String ACTION_STOP = "com.avf.linux.ACTION_STOP";
    public static final String ACTION_RESTART = "com.avf.linux.ACTION_RESTART";
    public static final String ACTION_IMPORT = "com.avf.linux.ACTION_IMPORT";
    public static final String ACTION_RESET = "com.avf.linux.ACTION_RESET";
    public static final String ACTION_TOGGLE_WAKELOCK = "com.avf.linux.ACTION_TOGGLE_WAKELOCK";
    public static final String ACTION_SET_WAKELOCK = "com.avf.linux.ACTION_SET_WAKELOCK";
    public static final String ACTION_TOGGLE_OVERLAY = "com.avf.linux.ACTION_TOGGLE_OVERLAY";
    public static final String ACTION_SET_OVERLAY = "com.avf.linux.ACTION_SET_OVERLAY";
    public static final String ACTION_ENSURE_FOREGROUND = "com.avf.linux.ACTION_ENSURE_FOREGROUND";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_ENABLE = "enable";
    private static final long FREEZE_TICK_MS = 1000L;
    private static final long FREEZE_STALL_THRESHOLD_MS = 2000L;

    public final class LocalBinder extends Binder {
        public VmService getService() {
            return VmService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mFgSyncScheduled = new AtomicBoolean(false);
    private final Object mWakeLockGuard = new Object();

    private VmConfigStore mStore;
    private String mToken;
    private byte[] mTokenBytes;
    private VmController mController;
    private VmControlServer mCtlServer;
    private VmOverlayManager mOverlayManager;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    private volatile boolean mWakeLockHeld = false;
    private volatile boolean mWifiLockHeld = false;
    private volatile boolean mServiceStarted = false;
    private volatile boolean mForegroundActive = false;
    private boolean mFgDegradedWarned = false;
    private long mLastNotifiedSeq = -1L;
    private volatile int mLastStartId = -1;
    private volatile boolean mDestroyed = false;

    private final AtomicInteger mFreezeCount = new AtomicInteger(0);
    private volatile long mLastFreezeMs = 0L;
    private volatile long mLastFreezeAtElapsedMs = 0L;
    private volatile boolean mNetBlocked = false;
    private final AtomicInteger mNetBlockedCount = new AtomicInteger(0);
    private Thread mFreezeMonitorThread;

    private ConnectivityManager mConnMgr;
    private ConnectivityManager.NetworkCallback mNetCb;
    private BroadcastReceiver mShutdownReceiver;

    private final Runnable mSyncForegroundRunnable = new Runnable() {
        @Override
        public void run() {
            mFgSyncScheduled.set(false);
            if (mDestroyed || mController == null) return;
            applyForegroundProjection(mController.getStatus());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mStore = new VmConfigStore(this);
        mToken = mStore.getOrCreateToken();
        mTokenBytes = mToken.getBytes(StandardCharsets.UTF_8);
        mStore.cleanupStaleVmNetSockets();

        Properties initialProps = mStore.loadSettings();
        mController = new VmController(this, initialProps, mStore);
        mOverlayManager = new VmOverlayManager(this, mStore);

        ensureNotificationChannel();
        registerShutdownReceiver();
        registerNetworkMonitor();
        startFreezeMonitor();
        mOverlayManager.sync(mController.getStatus());

        mCtlServer = new VmControlServer(mController.getStatus().ctlPort, mToken, this);
        mCtlServer.start();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mOverlayManager != null) {
            mOverlayManager.onConfigurationChanged();
        }
    }

    public void syncOverlay() {
        if (mOverlayManager != null && mController != null) {
            mOverlayManager.sync(mController.getStatus());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public VmController getController() {
        return mController;
    }

    public VmStatus getStatus() {
        return mController.getStatus();
    }

    public boolean isServiceStarted() {
        return mServiceStarted;
    }

    public boolean isForegroundActive() {
        return mForegroundActive;
    }

    public boolean isBatteryOptimizationIgnored() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mServiceStarted = true;
        mLastStartId = startId;

        String action = (intent != null) ? intent.getAction() : null;
        if (action == null) {
            scheduleForegroundSync();
            return mController.getStatus().state.isActive() ? START_STICKY : START_NOT_STICKY;
        }

        // Fulfill startForegroundService() contract immediately when launched by action intent
        promoteToForegroundNow(mController.getStatus());

        if (ACTION_ENSURE_FOREGROUND.equals(action)) {
            scheduleForegroundSync();
            return START_STICKY;
        }

        String tok = intent.getStringExtra(EXTRA_TOKEN);
        if (!isValidToken(tok)) {
            Log.w(TAG, "Rejected unauthorized action: " + action);
            mController.appendLog("[AVF Linux] Rejected unauthorized intent action: " + action + "\n");
            scheduleForegroundSync();
            return START_STICKY;
        }

        if (ACTION_IMPORT.equals(action)) {
            Uri dataUri = intent.getData();
            if (dataUri != null) requestImportUri(dataUri);
            else mController.requestImportStaging();
        } else if (ACTION_RESET.equals(action)) {
            mController.requestReset();
        } else if (ACTION_START.equals(action)) {
            mController.requestStart();
        } else if (ACTION_STOP.equals(action)) {
            mController.requestStop();
        } else if (ACTION_RESTART.equals(action)) {
            mController.requestRestart();
        } else if (ACTION_TOGGLE_WAKELOCK.equals(action)) {
            mController.toggleKeepAwake();
        } else if (ACTION_SET_WAKELOCK.equals(action)) {
            mController.setKeepAwake(intent.getBooleanExtra(EXTRA_ENABLE, false));
        } else if (ACTION_TOGGLE_OVERLAY.equals(action)) {
            mController.toggleShowOverlay();
        } else if (ACTION_SET_OVERLAY.equals(action)) {
            mController.setShowOverlay(intent.getBooleanExtra(EXTRA_ENABLE, true));
        }

        scheduleForegroundSync();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "App task removed; converging VM to clean stop");
        mController.onTaskRemoved(mLastStartId);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        stopFreezeMonitor();
        if (mOverlayManager != null) {
            mOverlayManager.dispose();
        }
        mMainHandler.removeCallbacks(mSyncForegroundRunnable);
        unregisterShutdownReceiver();
        unregisterNetworkMonitor();
        if (mCtlServer != null) {
            mCtlServer.close();
        }
        if (mController != null) {
            mController.dispose(VmController.DESTROY_STOP_TIMEOUT_MS);
        }
        releaseWakeLock();
        super.onDestroy();
    }

    public int requestImportUri(final Uri uri) {
        if (uri == null) return 400;
        final VmImageManager.UriFormat fmt;
        try {
            fmt = VmImageManager.probeUri(getContentResolver(), uri);
        } catch (Throwable t) {
            String msg = "Import rejected: " + t.getMessage();
            Log.w(TAG, msg);
            mController.reportRejectedMaintenance(msg);
            return 400;
        }
        return mController.requestCustomImport(
            "[AVF Linux] Importing selected image file...\n",
            diskGb -> VmImageManager.importFromUri(
                getContentResolver(), uri, fmt, mStore, diskGb, mController::appendLog)
        );
    }

    // -------------------------------------------------------------------------
    // VmControlServer.Controller
    // -------------------------------------------------------------------------

    @Override
    public String getStatusJson() {
        long nowElapsed = SystemClock.elapsedRealtime();
        long lastFreezeAt = mLastFreezeAtElapsedMs;
        long freezeAgoSec = (lastFreezeAt > 0L)
            ? Math.max(0L, (nowElapsed - lastFreezeAt) / 1000L)
            : -1L;
        VmStatus.RuntimeDiag diag = new VmStatus.RuntimeDiag(
            mWakeLockHeld,
            mWifiLockHeld,
            isBatteryOptimizationIgnored(),
            mForegroundActive,
            mOverlayManager != null && mOverlayManager.isAttached(),
            mFreezeCount.get(),
            mLastFreezeMs,
            freezeAgoSec,
            mNetBlocked,
            mNetBlockedCount.get()
        );
        return mController.getStatus().toJson(mStore, diag);
    }
    @Override public String getLiveConsoleText() { return mController.getConsole().getTextSnapshot(); }
    @Override public String getLastConsoleText() { return mStore.readLastConsoleLog(); }

    @Override
    public String getLastVmLogText() {
        String cur = mController.getVmLogSnapshot();
        return !cur.isEmpty() ? cur : mStore.readLastVmLog();
    }

    @Override public void sendConsoleInput(byte[] data) { mController.sendConsoleInput(data); }
    @Override public int requestStart() { return mController.requestStart(); }
    @Override public int requestStop() { return mController.requestStop(); }
    @Override public boolean requestRestart() { return mController.requestRestart(); }
    @Override public int requestImportStaging() { return mController.requestImportStaging(); }
    @Override public int requestReset() { return mController.requestReset(); }
    @Override public void setKeepAwake(boolean enable) { mController.setKeepAwake(enable); }
    @Override public void toggleKeepAwake() { mController.toggleKeepAwake(); }
    @Override public void setShowOverlay(boolean enable) { mController.setShowOverlay(enable); }
    @Override public void toggleShowOverlay() { mController.toggleShowOverlay(); }

    // -------------------------------------------------------------------------
    // VmSession.Observer
    // -------------------------------------------------------------------------

    @Override
    public void onConsoleChars(VmSession session, char[] buf, int len) {
        mController.appendSessionConsoleChars(session, buf, len);
    }

    @Override
    public void onSessionNotice(VmSession session, String text) {
        mController.appendLog(text);
    }

    @Override
    public void onVmLogLine(VmSession session, String line) {
        mController.appendSessionVmLogLine(session, line);
    }

    @Override
    public void onVmNetStateChanged(VmSession session, boolean connected, String guestIp) {
        mController.onSessionVmNetStateChanged(session, connected, guestIp);
    }

    @Override
    public void onSessionStopped(VmSession session, boolean rebootRequested, int reason) {
        mController.onSessionStopped(session, rebootRequested, reason);
    }

    @Override
    public void onSessionError(VmSession session, int errorCode, String message) {
        mController.onSessionError(session, errorCode, message);
    }

    // -------------------------------------------------------------------------
    // VmController.Host
    // -------------------------------------------------------------------------

    @Override public boolean hasDiskImage() { return mStore.hasImages(); }
    @Override public String getDiskPath() { return mStore.getDiskFile().getAbsolutePath(); }
    @Override public boolean hasStagingImage() { return mStore.findStagingImage() != null; }
    @Override public boolean hasSeedImage() { return mStore.hasSeed(); }
    @Override public void saveKeepAwake(boolean enable) { mStore.saveKeepAwake(enable); }
    @Override public void saveShowOverlay(boolean enable) { mStore.saveShowOverlay(enable); }

    @Override
    public VmController.SessionHandle bootSession(
            long sessionId, long generation, ExecutorService callbackExecutor) throws Exception {
        refreshNetworkAndDns();
        return VmSession.boot(
            this,
            mStore,
            sessionId,
            generation,
            mController.getStatus().keepAwake,
            callbackExecutor,
            this
        );
    }

    @Override
    public String runImportStaging(int diskGb) throws Exception {
        return VmImageManager.importFromStaging(mStore, diskGb, mController::appendLog);
    }

    @Override
    public String runResetSeed(int diskGb) throws Exception {
        return VmImageManager.resetFromSeed(mStore, diskGb, mController::appendLog);
    }

    @Override
    public void persistSessionLogs(String consoleText, String vmLogText) {
        mStore.saveSessionLogs(consoleText, vmLogText);
    }

    @Override
    public void onStateInvalidated() {
        applyWakeLockProjection();
        scheduleForegroundSync();
        if (mOverlayManager != null && mController != null) {
            mOverlayManager.sync(mController.getStatus());
        }
    }

    @Override
    public void onIdleServiceExitRequested(final int startId) {
        mMainHandler.post(() -> {
            if (mDestroyed) return;
            if (mOverlayManager != null && mController != null) {
                mOverlayManager.sync(mController.getStatus());
            }
            mServiceStarted = false;
            mForegroundActive = false;
            mLastNotifiedSeq = -1L;
            if (startId > 0) stopSelfResult(startId);
            else stopSelf();
        });
    }

    @Override public void logInfo(String msg) { Log.i(TAG, msg); }
    @Override public void logWarn(String msg) { Log.w(TAG, msg); }
    @Override public void logError(String msg, Throwable t) { Log.e(TAG, msg, t); }

    // -------------------------------------------------------------------------
    // Idempotent Platform Projections (Notification, WakeLock, Broadcasts, DNS)
    // -------------------------------------------------------------------------

    private boolean isValidToken(String candidate) {
        if (candidate == null) return false;
        return MessageDigest.isEqual(mTokenBytes, candidate.getBytes(StandardCharsets.UTF_8));
    }

    private void scheduleForegroundSync() {
        if (mDestroyed) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mFgSyncScheduled.set(false);
            if (mController != null) {
                applyForegroundProjection(mController.getStatus());
            }
        } else if (mFgSyncScheduled.compareAndSet(false, true)) {
            mMainHandler.post(mSyncForegroundRunnable);
        }
    }

    private void promoteToForegroundNow(VmStatus status) {
        Notification notif = buildNotification(status);
        try {
            startForeground(NOTIF_ID, notif);
            mForegroundActive = true;
            mFgDegradedWarned = false;
            mLastNotifiedSeq = status.seq;
        } catch (Throwable t) {
            mForegroundActive = false;
            mLastNotifiedSeq = -1L;
            Log.e(TAG, "startForeground failed; running without FGS elevation", t);
            boolean consoleReady = (status.state == VmStatus.State.RUNNING || status.state.isMaintenance());
            if (!mFgDegradedWarned && mController != null && consoleReady) {
                mFgDegradedWarned = true;
                mController.appendLog("[AVF Linux] Warning: OS blocked foreground service elevation ("
                    + t.getClass().getSimpleName()
                    + "); allow unrestricted battery usage in AVF Linux to prevent background freezes.\n");
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, notif);
        }
    }

    private void applyForegroundProjection(VmStatus status) {
        if (status.state.isActive()) {
            if (!mServiceStarted) {
                try {
                    Intent ensure = new Intent(this, VmService.class).setAction(ACTION_ENSURE_FOREGROUND);
                    startForegroundService(ensure);
                    mServiceStarted = true;
                } catch (Throwable t) {
                    Log.w(TAG, "startForegroundService self-start failed: " + t.getMessage());
                }
            }
            if (mForegroundActive && status.seq == mLastNotifiedSeq) {
                return;
            }
            promoteToForegroundNow(status);
        } else {
            mForegroundActive = false;
            mFgDegradedWarned = false;
            mLastNotifiedSeq = -1L;
            try {
                stopForeground(true);
            } catch (Throwable ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private void applyWakeLockProjection() {
        boolean heldNow;
        synchronized (mWakeLockGuard) {
            VmStatus latest = (mController != null) ? mController.getStatus() : null;
            boolean shouldHold = !mDestroyed && latest != null && (
                latest.state.isMaintenance()
                || latest.state == VmStatus.State.STOPPING
                || (latest.keepAwake
                    && (latest.state == VmStatus.State.STARTING || latest.state == VmStatus.State.RUNNING))
            );
            if (shouldHold) {
                if (mWakeLock == null) {
                    PowerManager pm = getSystemService(PowerManager.class);
                    if (pm != null) {
                        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AvfLinux:VmCpu");
                        mWakeLock.setReferenceCounted(false);
                    }
                }
                if (mWakeLock != null && !mWakeLock.isHeld()) {
                    try {
                        mWakeLock.acquire();
                    } catch (Throwable t) {
                        Log.w(TAG, "WakeLock acquire failed: " + t.getMessage());
                    }
                }
                if (mWifiLock == null) {
                    WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
                    if (wm != null) {
                        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AvfLinux:VmWifi");
                        mWifiLock.setReferenceCounted(false);
                    }
                }
                if (mWifiLock != null && !mWifiLock.isHeld()) {
                    try {
                        mWifiLock.acquire();
                    } catch (Throwable t) {
                        Log.w(TAG, "WifiLock acquire failed: " + t.getMessage());
                    }
                }
            } else {
                if (mWifiLock != null && mWifiLock.isHeld()) {
                    try { mWifiLock.release(); } catch (Throwable ignored) {}
                }
                if (mWakeLock != null && mWakeLock.isHeld()) {
                    try { mWakeLock.release(); } catch (Throwable ignored) {}
                }
            }
            heldNow = (mWakeLock != null && mWakeLock.isHeld());
            mWakeLockHeld = heldNow;
            mWifiLockHeld = (mWifiLock != null && mWifiLock.isHeld());
        }
        if (mController != null) {
            mController.updateWakeLockHeld(heldNow);
        }
    }

    private void releaseWakeLock() {
        synchronized (mWakeLockGuard) {
            if (mWifiLock != null && mWifiLock.isHeld()) {
                try { mWifiLock.release(); } catch (Throwable ignored) {}
            }
            if (mWakeLock != null && mWakeLock.isHeld()) {
                try { mWakeLock.release(); } catch (Throwable ignored) {}
            }
            mWifiLockHeld = false;
            mWakeLockHeld = false;
        }
    }

    private void registerShutdownReceiver() {
        try {
            IntentFilter sf = new IntentFilter();
            sf.addAction(Intent.ACTION_SHUTDOWN);
            sf.addAction("android.intent.action.QUICKBOOT_POWEROFF");
            mShutdownReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (mController == null || !mController.getStatus().state.isActive()) {
                        return;
                    }
                    Log.i(TAG, "Host shutdown broadcast (" + intent.getAction()
                        + "); shutting down guest gracefully");
                    final PendingResult pr = goAsync();
                    mController.onHostShutdown(VmController.EXIT_STOP_TIMEOUT_MS, pr::finish);
                }
            };
            registerReceiver(mShutdownReceiver, sf);
        } catch (Throwable t) {
            Log.w(TAG, "Shutdown receiver registration failed: " + t.getMessage());
        }
    }

    private void unregisterShutdownReceiver() {
        if (mShutdownReceiver != null) {
            try { unregisterReceiver(mShutdownReceiver); } catch (Throwable ignored) {}
            mShutdownReceiver = null;
        }
    }

    private void registerNetworkMonitor() {
        try {
            mConnMgr = getSystemService(ConnectivityManager.class);
            refreshNetworkAndDns();
            if (mConnMgr != null) {
                mNetCb = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { refreshNetworkAndDns(); }
                    @Override public void onLost(Network network) { refreshNetworkAndDns(); }
                    @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                        refreshNetworkAndDns();
                    }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                        refreshNetworkAndDns();
                    }
                    @Override public void onBlockedStatusChanged(Network network, boolean blocked) {
                        mNetBlocked = blocked;
                        if (blocked) {
                            mNetBlockedCount.incrementAndGet();
                        }
                    }
                };
                mConnMgr.registerDefaultNetworkCallback(mNetCb);
            }
        } catch (Throwable t) {
            Log.w(TAG, "NetworkCallback registration failed: " + t.getMessage());
        }
    }

    private void startFreezeMonitor() {
        mFreezeMonitorThread = new Thread(() -> {
            long prevUptime = SystemClock.uptimeMillis();
            long prevElapsed = SystemClock.elapsedRealtime();
            while (!mDestroyed) {
                try {
                    Thread.sleep(FREEZE_TICK_MS);
                } catch (InterruptedException ie) {
                    break;
                }
                if (mDestroyed) break;
                long nowUptime = SystemClock.uptimeMillis();
                long nowElapsed = SystemClock.elapsedRealtime();
                long uptimeDelta = nowUptime - prevUptime;
                long elapsedDelta = nowElapsed - prevElapsed;
                prevUptime = nowUptime;
                prevElapsed = nowElapsed;

                long stallMs = uptimeDelta - FREEZE_TICK_MS;
                if (stallMs >= FREEZE_STALL_THRESHOLD_MS) {
                    long deepSleepMs = Math.max(0L, elapsedDelta - uptimeDelta);
                    int count = mFreezeCount.incrementAndGet();
                    mLastFreezeMs = stallMs;
                    mLastFreezeAtElapsedMs = nowElapsed;
                    Log.w(TAG, "Process freeze stall detected: " + stallMs
                        + "ms (deepSleep=" + deepSleepMs + "ms, count=" + count + ")");
                }
            }
        }, "vm-freeze-mon");
        mFreezeMonitorThread.setDaemon(true);
        mFreezeMonitorThread.start();
    }

    private void stopFreezeMonitor() {
        Thread t = mFreezeMonitorThread;
        mFreezeMonitorThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void unregisterNetworkMonitor() {
        if (mConnMgr != null && mNetCb != null) {
            try { mConnMgr.unregisterNetworkCallback(mNetCb); } catch (Throwable ignored) {}
            mNetCb = null;
        }
    }

    private void refreshNetworkAndDns() {
        List<String> v4 = new ArrayList<>();
        List<String> v6 = new ArrayList<>();
        boolean hasVpn = false;
        boolean hasNet = false;
        try {
            if (mConnMgr != null) {
                Network active = mConnMgr.getActiveNetwork();
                if (active != null) {
                    hasNet = true;
                    NetworkCapabilities caps = mConnMgr.getNetworkCapabilities(active);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        hasVpn = true;
                    }
                    LinkProperties lp = mConnMgr.getLinkProperties(active);
                    if (lp != null) {
                        for (InetAddress addr : lp.getDnsServers()) {
                            String host = addr.getHostAddress();
                            if (host == null || addr.isLoopbackAddress()) continue;
                            int pct = host.indexOf('%');
                            if (pct > 0) host = host.substring(0, pct);
                            if (addr instanceof Inet4Address) v4.add(host);
                            else v6.add(host);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "DNS inspect error: " + t.getMessage());
        }

        String mode = hasVpn ? "Host VPN" : (hasNet ? "Host Net" : "Offline");
        if (mController != null) {
            mController.updateNetworkMode(mode);
        }

        StringBuilder sb = new StringBuilder();
        for (String s : v4) sb.append(s).append('\n');
        for (String s : v6) sb.append(s).append('\n');
        if (sb.length() == 0) sb.append("1.1.1.1\n8.8.8.8\n");
        mStore.writeActiveDns(sb.toString());
    }

    private void ensureNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "AVF Linux VM", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Status and quick controls for the local Debian 13 AVF VM");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(VmStatus st) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        boolean running = (st.state == VmStatus.State.RUNNING || st.state == VmStatus.State.STARTING);
        boolean stopping = (st.state == VmStatus.State.STOPPING);
        boolean maintenance = st.state.isMaintenance();
        boolean active = st.state.isActive();

        Intent toggleIntent = new Intent(this, VmService.class);
        toggleIntent.setAction(running ? ACTION_STOP : ACTION_START);
        toggleIntent.putExtra(EXTRA_TOKEN, mToken);
        PendingIntent togglePi = PendingIntent.getForegroundService(
            this, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String sub;
        if (running) {
            sub = "SSH 127.0.0.1:" + st.sshPort + " · " + st.netMode
                + (st.keepAwake ? " · WakeLock ON" : "");
        } else if (stopping) {
            sub = "Shutting down Debian 13 gracefully...";
        } else if (st.state == VmStatus.State.IMPORTING) {
            sub = "Importing VM disk image...";
        } else if (st.state == VmStatus.State.RESETTING) {
            sub = "Resetting VM disk from golden seed...";
        } else {
            sub = st.lastError.isEmpty() ? "Stopped" : st.lastError;
        }

        Notification.Builder nb = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("AVF Linux (" + st.state.name() + ")")
            .setContentText(sub)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setContentIntent(openPi);
        if (!maintenance && !stopping) {
            nb.addAction(new Notification.Action.Builder(
                null, running ? "Stop VM" : "Start VM", togglePi).build());
            if (running) {
                Intent wlIntent = new Intent(this, VmService.class);
                wlIntent.setAction(ACTION_TOGGLE_WAKELOCK);
                wlIntent.putExtra(EXTRA_TOKEN, mToken);
                PendingIntent wlPi = PendingIntent.getForegroundService(
                    this, 2, wlIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                nb.addAction(new Notification.Action.Builder(
                    null, st.keepAwake ? "WakeLock: ON" : "WakeLock: OFF", wlPi).build());
            }
        }
        return nb.build();
    }
}
