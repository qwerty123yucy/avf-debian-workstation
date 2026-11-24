package com.avf.linux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;
import android.system.virtualmachine.VirtualMachine;
import android.system.virtualmachine.VirtualMachineCallback;
import android.system.virtualmachine.VirtualMachineConfig;
import android.system.virtualmachine.VirtualMachineCustomImageConfig;
import android.system.virtualmachine.VirtualMachineManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class VmService extends Service {
    private static final String TAG = "AvfLinux";
    private static final String CHANNEL_ID = "avf_linux_vm";
    private static final int NOTIF_ID = 1001;
    private static final int VMNET_VSOCK_PORT = 2223;

    public static final String ACTION_START = "com.avf.linux.ACTION_START";
    public static final String ACTION_STOP = "com.avf.linux.ACTION_STOP";
    public static final String ACTION_RESTART = "com.avf.linux.ACTION_RESTART";
    public static final String ACTION_IMPORT = "com.avf.linux.ACTION_IMPORT";
    public static final String ACTION_RESET = "com.avf.linux.ACTION_RESET";
    public static final String ACTION_TOGGLE_WAKELOCK = "com.avf.linux.ACTION_TOGGLE_WAKELOCK";
    public static final String ACTION_SET_WAKELOCK = "com.avf.linux.ACTION_SET_WAKELOCK";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_ENABLE = "enable";

    private static final int MAX_LOG_CHARS = 49152;
    private static final Pattern ANSI_PAT = Pattern.compile(
        "\\u001B\\[[0-9;?]*[ -/]*[@-~]|\\u001B\\][^\\u0007\\u001B]*(\\u0007|\\u001B\\\\)|\\u001B[@-Z\\\\-_]");

    public static volatile String sState = "STOPPED";
    public static volatile String sGuestIp = "";
    public static volatile String sNetMode = "Host VPN";
    public static volatile String sLastError = "";
    public static volatile long sStartedAtMs = 0L;
    public static volatile int sMemMib = VmConfigStore.DEFAULT_MEM_MIB;
    public static volatile int sDiskGb = VmConfigStore.DEFAULT_DISK_GB;
    public static volatile int sSshPort = VmConfigStore.DEFAULT_SSH_LOCAL_PORT;
    public static volatile int sCtlPort = VmConfigStore.DEFAULT_CTL_PORT;
    public static volatile boolean sVmNetConnected = false;
    public static volatile boolean sKeepAwake = false;
    public static volatile boolean sWakeLockHeld = false;

    private static final StringBuilder sConsoleBuf = new StringBuilder();
    private static volatile long sConsoleSeq = 0L;
    private static volatile long sStateSeq = 0L;
    private static volatile Runnable sUiListener;
    private static volatile VmService sInstance;

    private VmConfigStore mStore;
    private String mToken;
    private String mLastDnsContent = "";
    private final ExecutorService mVmExec = Executors.newSingleThreadExecutor();
    private final ExecutorService mCbExec = Executors.newSingleThreadExecutor();
    private final ExecutorService mConsoleInExec = Executors.newSingleThreadExecutor();
    private final Object mLock = new Object();
    private final Object mStateLock = new Object();
    private final Object mPrefLock = new Object();

    private VirtualMachine mVm;
    private long mRunId = 0L;
    private volatile OutputStream mConsoleIn;
    private ServerSocket mSshServer;
    private ServerSocket mCtlServer;
    private Process mVmNetProc;
    private OutputStream mVmNetStdin;
    private volatile LocalSocket mVmNetCtrlSock;
    private volatile boolean mVmNetActive = false;
    private PowerManager.WakeLock mWakeLock;
    private ConnectivityManager mConnMgr;
    private ConnectivityManager.NetworkCallback mNetCb;
    private volatile boolean mShuttingDown = false;

    public static VmService getInstance() { return sInstance; }
    public static long getConsoleSeq() { return sConsoleSeq; }
    public static long getStateSeq() { return sStateSeq; }
    public static void setUiListener(Runnable listener) { sUiListener = listener; }

    private static void notifyUi() {
        Runnable r = sUiListener;
        if (r != null) r.run();
    }

    public static void clearConsole() {
        synchronized (sConsoleBuf) {
            sConsoleBuf.setLength(0);
            sConsoleSeq++;
        }
        notifyUi();
    }

    public static int getConsoleLength() {
        synchronized (sConsoleBuf) {
            return sConsoleBuf.length();
        }
    }

    public static String getConsoleSnapshot() {
        synchronized (sConsoleBuf) {
            return sConsoleBuf.toString();
        }
    }

    public static String getConsoleTail(int maxChars) {
        synchronized (sConsoleBuf) {
            int len = sConsoleBuf.length();
            if (len <= maxChars) return sConsoleBuf.toString();
            int start = len - maxChars;
            int nl = sConsoleBuf.indexOf("\n", start);
            if (nl >= 0 && nl + 1 < len && (nl - start) < 256) {
                start = nl + 1;
            }
            return sConsoleBuf.substring(start);
        }
    }

    public static void sendConsoleInput(final byte[] data) {
        if (data == null || data.length == 0) return;
        final VmService svc = sInstance;
        if (svc == null) return;
        svc.mConsoleInExec.execute(() -> {
            OutputStream out = svc.mConsoleIn;
            if (out == null) return;
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                Log.w(TAG, "Console input write error: " + e.getMessage());
            }
        });
    }

    private static void appendLog(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String cleaned = ANSI_PAT.matcher(raw).replaceAll("");
        synchronized (sConsoleBuf) {
            for (int i = 0; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (c == '\r') {
                    if (i + 1 < cleaned.length() && cleaned.charAt(i + 1) == '\n') {
                        continue;
                    }
                    int lastNl = sConsoleBuf.lastIndexOf("\n");
                    if (lastNl >= 0) {
                        sConsoleBuf.setLength(lastNl + 1);
                    } else {
                        sConsoleBuf.setLength(0);
                    }
                } else if (c == '\b' || c == 0x7f) {
                    int len = sConsoleBuf.length();
                    if (len > 0 && sConsoleBuf.charAt(len - 1) != '\n') {
                        sConsoleBuf.deleteCharAt(len - 1);
                    }
                } else if (c == '\n' || c == '\t' || (c >= 0x20 && c != 0x9b)) {
                    sConsoleBuf.append(c);
                }
            }
            if (sConsoleBuf.length() > MAX_LOG_CHARS) {
                sConsoleBuf.delete(0, sConsoleBuf.length() - MAX_LOG_CHARS);
            }
            sConsoleSeq++;
        }
        notifyUi();
    }

    private static int findIncompleteAnsiTail(String s) {
        int len = s.length();
        int scanFrom = Math.max(0, len - 24);
        int esc = s.lastIndexOf('\u001B');
        if (esc < scanFrom) return -1;
        if (esc == len - 1) return esc;
        char next = s.charAt(esc + 1);
        if (next == '[') {
            for (int i = esc + 2; i < len; i++) {
                char ch = s.charAt(i);
                if (ch >= '@' && ch <= '~') return -1;
            }
            return esc;
        } else if (next == ']') {
            for (int i = esc + 2; i < len; i++) {
                char ch = s.charAt(i);
                if (ch == '\u0007') return -1;
                if (ch == '\u001B' && i + 1 < len && s.charAt(i + 1) == '\\') return -1;
            }
            return esc;
        }
        return -1;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mStore = new VmConfigStore(this);
        mToken = mStore.getOrCreateToken();
        Properties p = mStore.loadSettings();
        sMemMib = mStore.getIntProp(p, "memory_mib", VmConfigStore.DEFAULT_MEM_MIB);
        sDiskGb = mStore.getIntProp(p, "disk_gb", VmConfigStore.DEFAULT_DISK_GB);
        sSshPort = mStore.getIntProp(p, "ssh_local_port", VmConfigStore.DEFAULT_SSH_LOCAL_PORT);
        sCtlPort = mStore.getIntProp(p, "ctl_port", VmConfigStore.DEFAULT_CTL_PORT);
        sKeepAwake = mStore.getBoolProp(p, "keep_awake", false);
        ensureNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        registerNetworkMonitor();
        startControlServer();
    }

    private static boolean isMaintenanceState() {
        return "IMPORTING".equals(sState) || "RESETTING".equals(sState);
    }

    private int enqueueImport() {
        String errMsg = null;
        synchronized (mStateLock) {
            if (isMaintenanceState()) return 409;
            if (mStore.findStagingImage() == null) {
                errMsg = "No seed.simg.gz, disk.simg, or disk.img found in staging";
                if (!"RUNNING".equals(sState) && !"STARTING".equals(sState)) {
                    sLastError = errMsg;
                }
            } else {
                sState = "IMPORTING";
                sLastError = "";
            }
        }
        if (errMsg != null) {
            appendLog("[AVF Linux] " + errMsg + "\n");
            syncForegroundState();
            return 400;
        }
        syncForegroundState();
        mVmExec.execute(this::importFromStaging);
        return 202;
    }

    private int enqueueImportFromUri(final Uri uri) {
        synchronized (mStateLock) {
            if (isMaintenanceState()) return 409;
            sState = "IMPORTING";
            sLastError = "";
        }
        syncForegroundState();
        mVmExec.execute(() -> importFromUri(uri));
        return 202;
    }

    private int enqueueReset() {
        String errMsg = null;
        synchronized (mStateLock) {
            if (isMaintenanceState()) return 409;
            if (!mStore.hasSeed()) {
                errMsg = "Golden seed (seed.simg.gz) not found; cannot reset";
                if (!"RUNNING".equals(sState) && !"STARTING".equals(sState)) {
                    sLastError = errMsg;
                }
            } else {
                sState = "RESETTING";
                sLastError = "";
            }
        }
        if (errMsg != null) {
            appendLog("[AVF Linux] " + errMsg + "\n");
            syncForegroundState();
            return 400;
        }
        syncForegroundState();
        mVmExec.execute(this::resetFromSeed);
        return 202;
    }

    private boolean enqueueRestart() {
        synchronized (mStateLock) {
            if (isMaintenanceState()) return false;
            sState = "STARTING";
            sLastError = "";
        }
        syncForegroundState();
        mVmExec.execute(() -> {
            stopVmInternal(true);
            startVmInternal();
        });
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        if (intent == null) {
            syncForegroundState();
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action == null) {
            syncForegroundState();
            return START_STICKY;
        }

        String tok = intent.getStringExtra(EXTRA_TOKEN);
        if (!mToken.equals(tok)) {
            Log.w(TAG, "Rejected unauthorized action: " + action);
            appendLog("[AVF Linux] Rejected unauthorized intent action: " + action + "\n");
            syncForegroundState();
            return START_STICKY;
        }

        if (ACTION_IMPORT.equals(action)) {
            Uri dataUri = intent.getData();
            if (dataUri != null) enqueueImportFromUri(dataUri);
            else enqueueImport();
        } else if (ACTION_RESET.equals(action)) {
            enqueueReset();
        } else if (ACTION_START.equals(action)) {
            mVmExec.execute(this::startVmInternal);
        } else if (ACTION_STOP.equals(action)) {
            mVmExec.execute(() -> stopVmInternal(false));
        } else if (ACTION_RESTART.equals(action)) {
            enqueueRestart();
        } else if (ACTION_TOGGLE_WAKELOCK.equals(action)) {
            setKeepAwake(!sKeepAwake);
        } else if (ACTION_SET_WAKELOCK.equals(action)) {
            setKeepAwake(intent.getBooleanExtra(EXTRA_ENABLE, false));
        } else {
            syncForegroundState();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mShuttingDown = true;
        unregisterNetworkMonitor();
        closeQuietly(mCtlServer);
        stopVmInternal(false);
        mVmExec.shutdownNow();
        mCbExec.shutdownNow();
        mConsoleInExec.shutdownNow();
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public long getActualDiskBytes() {
        return mStore.getActualDiskBytes();
    }

    private void setKeepAwake(boolean enable) {
        synchronized (mPrefLock) {
            sKeepAwake = enable;
            mStore.saveKeepAwake(enable);
        }
        appendLog("[AVF Linux] WakeLock policy: "
            + (enable ? "ON (PARTIAL_WAKE_LOCK held while VM runs)" : "OFF (host suspend allowed)") + "\n");
        syncForegroundState();
    }

    private File getDnsFile() {
        return new File(getFilesDir(), "active-dns.txt");
    }

    private File getVmNetSockFile() {
        return new File(getFilesDir(), "vmnet.sock");
    }

    private void registerNetworkMonitor() {
        try {
            mConnMgr = getSystemService(ConnectivityManager.class);
            updateActiveNetworkDns();
            if (mConnMgr != null) {
                mNetCb = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) { updateActiveNetworkDns(); }
                    @Override
                    public void onLost(Network network) { updateActiveNetworkDns(); }
                    @Override
                    public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                        updateActiveNetworkDns();
                    }
                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                        updateActiveNetworkDns();
                    }
                };
                mConnMgr.registerDefaultNetworkCallback(mNetCb);
            }
        } catch (Throwable t) {
            Log.w(TAG, "NetworkCallback registration failed: " + t.getMessage());
        }
    }

    private void unregisterNetworkMonitor() {
        if (mConnMgr != null && mNetCb != null) {
            try { mConnMgr.unregisterNetworkCallback(mNetCb); } catch (Throwable ignored) {}
            mNetCb = null;
        }
    }

    private void updateActiveNetworkDns() {
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

        String prevMode = sNetMode;
        if (hasVpn) sNetMode = "Host VPN";
        else if (hasNet) sNetMode = "Host Net";
        else sNetMode = "Offline";
        if (!sNetMode.equals(prevMode) && "RUNNING".equals(sState)) {
            syncForegroundState();
        }

        StringBuilder sb = new StringBuilder();
        for (String s : v4) sb.append(s).append('\n');
        for (String s : v6) sb.append(s).append('\n');
        if (sb.length() == 0) sb.append("1.1.1.1\n8.8.8.8\n");

        String content = sb.toString();
        File dst = getDnsFile();
        if (!content.equals(mLastDnsContent) || !dst.isFile()) {
            File tmp = new File(getFilesDir(), "active-dns.txt.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                if (tmp.renameTo(dst)) {
                    mLastDnsContent = content;
                }
            } catch (IOException ignored) {}
        }
    }

    private void unpackSeedToDisk(File seedGz, File dstDisk) throws IOException {
        try (InputStream fis = new BufferedInputStream(new FileInputStream(seedGz), 1048576);
             GZIPInputStream gzin = new GZIPInputStream(fis, 262144)) {
            copyAndroidSparseStream(gzin, dstDisk, ((long) sDiskGb) << 30, true);
        }
    }

    private void importFromStaging() {
        synchronized (mLock) {
            File stagedCheck = mStore.findStagingImage();
            if (stagedCheck == null) {
                sState = "ERROR";
                sLastError = "No seed.simg.gz, disk.simg, or disk.img found in staging";
                appendLog("[AVF Linux] " + sLastError + "\n");
                syncForegroundState();
                return;
            }
            if (mVm != null) {
                stopVmInternal(true);
            }
            sState = "IMPORTING";
            sLastError = "";
            syncForegroundState();
            appendLog("[AVF Linux] Importing VM disk image from staging...\n");
            Log.i(TAG, "Importing VM disk image from staging...");
            try {
                File staging = mStore.getStagingDir();
                if (staging == null || !staging.isDirectory()) {
                    throw new IOException("Staging directory unavailable");
                }
                File dstDisk = mStore.getDiskFile();
                File dstSeed = mStore.getSeedFile();

                File srcSeedGz = new File(staging, "seed.simg.gz");
                File srcDiskSimg = new File(staging, "disk.simg");
                File srcDiskRaw = new File(staging, "disk.img");

                if (srcSeedGz.isFile()) {
                    appendLog("[AVF Linux] Verifying and unpacking sparse GPT disk from seed.simg.gz...\n");
                    unpackSeedToDisk(srcSeedGz, dstDisk);
                    appendLog("[AVF Linux] Saving verified golden seed image...\n");
                    copyRegular(srcSeedGz, dstSeed);
                    srcSeedGz.delete();
                } else if (srcDiskSimg.isFile()) {
                    appendLog("[AVF Linux] Importing Android sparse GPT disk disk.simg...\n");
                    try (InputStream in = new BufferedInputStream(new FileInputStream(srcDiskSimg), 1048576)) {
                        copyAndroidSparseStream(in, dstDisk, ((long) sDiskGb) << 30, false);
                    }
                    srcDiskSimg.delete();
                } else if (srcDiskRaw.isFile()) {
                    appendLog("[AVF Linux] Importing raw GPT disk disk.img sparsely...\n");
                    try (InputStream in = new BufferedInputStream(new FileInputStream(srcDiskRaw), 1048576)) {
                        copySparseStream(in, dstDisk, ((long) sDiskGb) << 30);
                    }
                    srcDiskRaw.delete();
                } else {
                    throw new IOException("No seed.simg.gz, disk.simg, or disk.img found in "
                        + staging.getAbsolutePath());
                }

                mStore.cleanupLegacyFiles();
                sState = "STOPPED";
                String doneMsg = "Import complete. Disk actual=" + (getActualDiskBytes() / (1024L * 1024L))
                    + " MiB, seed=" + (mStore.getSeedBytes() / (1024L * 1024L)) + " MiB";
                appendLog("[AVF Linux] " + doneMsg + "\n");
                Log.i(TAG, doneMsg);
            } catch (Throwable t) {
                sState = "ERROR";
                sLastError = "Import failed: " + t.getMessage();
                Log.e(TAG, sLastError, t);
                appendLog("[AVF Linux] " + sLastError + "\n");
            } finally {
                syncForegroundState();
            }
        }
    }

    private void importFromUri(Uri uri) {
        synchronized (mLock) {
            boolean isGzip = false;
            boolean isSparse = false;
            try (InputStream peek = getContentResolver().openInputStream(uri)) {
                if (peek == null) throw new IOException("Cannot open selected file");
                byte[] magic = new byte[4];
                readFully(peek, magic, 4);
                isGzip = ((magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b);
                isSparse = (readLe32(magic, 0) == 0xED26FF3A);
                if (!isGzip && !isSparse) {
                    throw new IOException("Unsupported file format (expected .simg.gz or .simg)");
                }
            } catch (Throwable t) {
                String msg = "Import rejected: " + t.getMessage();
                Log.w(TAG, msg);
                appendLog("[AVF Linux] " + msg + "\n");
                synchronized (mStateLock) {
                    if (mVm != null) {
                        sState = "RUNNING";
                    } else {
                        sState = "ERROR";
                        sLastError = msg;
                    }
                }
                syncForegroundState();
                return;
            }

            if (mVm != null) {
                stopVmInternal(true);
            }
            sState = "IMPORTING";
            sLastError = "";
            syncForegroundState();
            appendLog("[AVF Linux] Importing selected image file...\n");
            Log.i(TAG, "Importing VM image from content URI: " + uri);

            File dstDisk = mStore.getDiskFile();
            File dstSeed = mStore.getSeedFile();
            File seedTmp = new File(dstSeed.getAbsolutePath() + ".tmp");
            boolean seedSaved = false;
            try {
                if (isGzip) {
                    if (seedTmp.exists()) seedTmp.delete();
                    appendLog("[AVF Linux] Copying compressed seed image...\n");
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(seedTmp)) {
                        if (in == null) throw new IOException("Cannot open selected file");
                        byte[] buf = new byte[262144];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    appendLog("[AVF Linux] Verifying GZIP CRC and unpacking sparse GPT disk...\n");
                    unpackSeedToDisk(seedTmp, dstDisk);
                    if (!seedTmp.renameTo(dstSeed)) {
                        throw new IOException("Failed to commit golden seed image");
                    }
                    seedSaved = true;
                } else {
                    appendLog("[AVF Linux] Importing Android sparse GPT disk...\n");
                    try (InputStream raw = getContentResolver().openInputStream(uri);
                         InputStream in = new BufferedInputStream(raw, 1048576)) {
                        if (raw == null) throw new IOException("Cannot open selected file");
                        copyAndroidSparseStream(in, dstDisk, ((long) sDiskGb) << 30, false);
                    }
                }
                mStore.cleanupLegacyFiles();
                sState = "STOPPED";
                String doneMsg = "Import complete. Disk actual=" + (getActualDiskBytes() / (1024L * 1024L))
                    + " MiB, seed=" + (mStore.getSeedBytes() / (1024L * 1024L)) + " MiB";
                appendLog("[AVF Linux] " + doneMsg + "\n");
                Log.i(TAG, doneMsg);
            } catch (Throwable t) {
                sState = "ERROR";
                sLastError = "Import failed: " + t.getMessage();
                Log.e(TAG, sLastError, t);
                appendLog("[AVF Linux] " + sLastError + "\n");
            } finally {
                if (!seedSaved && seedTmp.exists()) {
                    seedTmp.delete();
                }
                syncForegroundState();
            }
        }
    }

    private void resetFromSeed() {
        synchronized (mLock) {
            if (!mStore.hasSeed()) {
                sState = "ERROR";
                sLastError = "Golden seed (seed.simg.gz) not found; cannot reset";
                appendLog("[AVF Linux] " + sLastError + "\n");
                syncForegroundState();
                return;
            }
            if (mVm != null) {
                stopVmInternal(true);
            }
            sState = "RESETTING";
            sLastError = "";
            syncForegroundState();
            appendLog("[AVF Linux] Resetting VM disk from golden seed...\n");
            Log.i(TAG, "Resetting VM disk from golden seed...");
            long t0 = System.currentTimeMillis();
            try {
                unpackSeedToDisk(mStore.getSeedFile(), mStore.getDiskFile());
                mStore.cleanupLegacyFiles();
                sState = "STOPPED";
                long dtMs = System.currentTimeMillis() - t0;
                String msg = String.format("Reset complete in %.1fs (actual=%d MiB)",
                    dtMs / 1000.0, getActualDiskBytes() / (1024L * 1024L));
                appendLog("[AVF Linux] " + msg + "\n");
                Log.i(TAG, msg);
            } catch (Throwable t) {
                sState = "ERROR";
                sLastError = "Reset failed: " + t.getMessage();
                Log.e(TAG, sLastError, t);
                appendLog("[AVF Linux] " + sLastError + "\n");
            } finally {
                syncForegroundState();
            }
        }
    }

    private static int readLe16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int readLe32(byte[] b, int off) {
        return (b[off] & 0xff)
            | ((b[off + 1] & 0xff) << 8)
            | ((b[off + 2] & 0xff) << 16)
            | ((b[off + 3] & 0xff) << 24);
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("Unexpected EOF in stream");
            off += n;
        }
    }

    private void copyAndroidSparseStream(
            InputStream in, File dst, long targetLogicalBytes, boolean drainEof) throws IOException {
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        if (tmp.exists()) tmp.delete();
        boolean ok = false;
        try {
            try (RandomAccessFile out = new RandomAccessFile(tmp, "rw")) {
                byte[] hdr = new byte[28];
                readFully(in, hdr, 28);
                int magic = readLe32(hdr, 0);
                if (magic != 0xED26FF3A) {
                    throw new IOException("Invalid Android sparse magic: 0x" + Integer.toHexString(magic));
                }
                int fileHdrSz = readLe16(hdr, 8);
                int chunkHdrSz = readLe16(hdr, 10);
                long blkSz = readLe32(hdr, 12) & 0xFFFFFFFFL;
                long totalBlks = readLe32(hdr, 16) & 0xFFFFFFFFL;
                int totalChunks = readLe32(hdr, 20);
                if (fileHdrSz > 28) {
                    readFully(in, new byte[fileHdrSz - 28], fileHdrSz - 28);
                }

                byte[] chdr = new byte[chunkHdrSz];
                byte[] buf = new byte[1048576];
                long outPos = 0L;
                long writtenBytes = 0L;
                long lastLogMb = 0L;

                for (int c = 0; c < totalChunks; c++) {
                    readFully(in, chdr, chunkHdrSz);
                    int ctype = readLe16(chdr, 0);
                    long chunkBlks = readLe32(chdr, 4) & 0xFFFFFFFFL;
                    long totalSz = readLe32(chdr, 8) & 0xFFFFFFFFL;
                    long dataSz = totalSz - chunkHdrSz;
                    long outSpan = chunkBlks * blkSz;

                    if (ctype == 0xCAC1) {
                        out.seek(outPos);
                        long rem = dataSz;
                        while (rem > 0) {
                            int want = (int) Math.min(buf.length, rem);
                            readFully(in, buf, want);
                            out.write(buf, 0, want);
                            rem -= want;
                            writtenBytes += want;
                        }
                        outPos += outSpan;
                        long mb = writtenBytes / (1024L * 1024L);
                        if (mb - lastLogMb >= 512) {
                            lastLogMb = mb;
                            appendLog("[AVF Linux] Written " + mb + " MiB...\n");
                        }
                    } else if (ctype == 0xCAC2) {
                        byte[] fill = new byte[4];
                        readFully(in, fill, 4);
                        if (fill[0] != 0 || fill[1] != 0 || fill[2] != 0 || fill[3] != 0) {
                            for (int i = 0; i < buf.length; i += 4) {
                                buf[i] = fill[0]; buf[i + 1] = fill[1];
                                buf[i + 2] = fill[2]; buf[i + 3] = fill[3];
                            }
                            out.seek(outPos);
                            long rem = outSpan;
                            while (rem > 0) {
                                int w = (int) Math.min(buf.length, rem);
                                out.write(buf, 0, w);
                                rem -= w;
                            }
                        }
                        outPos += outSpan;
                    } else if (ctype == 0xCAC3 || ctype == 0xCAC4) {
                        if (dataSz > 0) readFully(in, new byte[(int) dataSz], (int) dataSz);
                        if (ctype == 0xCAC3) outPos += outSpan;
                    } else {
                        throw new IOException("Unknown sparse chunk type: 0x" + Integer.toHexString(ctype));
                    }
                }
                if (drainEof) {
                    while (in.read(buf) >= 0) {
                        // Drain to EOF so GZIPInputStream verifies its CRC32 and ISIZE trailer
                    }
                }
                long finalLen = Math.max(Math.max(outPos, totalBlks * blkSz), targetLogicalBytes);
                out.setLength(finalLen);
            }
            if (!tmp.renameTo(dst)) throw new IOException("Failed to rename sparse disk.img");
            ok = true;
        } finally {
            if (!ok && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static void copyRegular(File src, File dst) throws IOException {
        if (!src.isFile()) throw new IOException("Missing staging file: " + src.getAbsolutePath());
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        boolean ok = false;
        try {
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[262144];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(dst)) throw new IOException("Failed to rename " + tmp);
            ok = true;
        } finally {
            if (!ok && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private static void copySparseStream(InputStream in, File dst, long targetLogicalBytes) throws IOException {
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        if (tmp.exists()) tmp.delete();
        boolean ok = false;
        try {
            try (RandomAccessFile out = new RandomAccessFile(tmp, "rw")) {
                byte[] buf = new byte[65536];
                long pos = 0;
                int filled = 0;
                while (true) {
                    int n = in.read(buf, filled, buf.length - filled);
                    if (n > 0) {
                        filled += n;
                        if (filled < buf.length) continue;
                    }
                    if (filled > 0) {
                        boolean allZero = true;
                        for (int i = 0; i < filled; i++) {
                            if (buf[i] != 0) { allZero = false; break; }
                        }
                        if (!allZero) {
                            out.seek(pos);
                            out.write(buf, 0, filled);
                        }
                        pos += filled;
                        filled = 0;
                    }
                    if (n < 0) break;
                }
                out.setLength(Math.max(pos, targetLogicalBytes));
            }
            if (!tmp.renameTo(dst)) throw new IOException("Failed to rename sparse disk.img");
            ok = true;
        } finally {
            if (!ok && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private void startVmNetDaemon(final VirtualMachine vm) {
        stopVmNetDaemon();
        updateActiveNetworkDns();
        File bin = new File(getApplicationInfo().nativeLibraryDir, "libvmnet.so");
        if (!bin.isFile()) {
            Log.e(TAG, "libvmnet.so missing at " + bin.getAbsolutePath());
            appendLog("[AVF Linux] Warning: libvmnet.so not found; guest networking disabled\n");
            return;
        }
        final File sockFile = getVmNetSockFile();
        if (sockFile.exists()) sockFile.delete();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                bin.getAbsolutePath(),
                "-sock", sockFile.getAbsolutePath(),
                "-dns-file", getDnsFile().getAbsolutePath());
            pb.redirectErrorStream(true);
            final Process proc = pb.start();
            mVmNetProc = proc;
            mVmNetStdin = proc.getOutputStream();
            mVmNetActive = true;

            Thread logT = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.i(TAG, "[vmnet] " + line);
                    }
                } catch (Throwable ignored) {}
            }, "vmnet-log");
            logT.setDaemon(true);
            logT.start();

            Thread bridgeT = new Thread(() -> {
                for (int i = 0; i < 40 && mVmNetActive && !sockFile.exists(); i++) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                }
                while (mVmNetActive && mVm == vm) {
                    ParcelFileDescriptor vsock = null;
                    LocalSocket local = null;
                    try {
                        vsock = vm.connectVsock(VMNET_VSOCK_PORT);

                        local = new LocalSocket();
                        local.connect(new LocalSocketAddress(
                            sockFile.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
                        mVmNetCtrlSock = local;

                        local.setFileDescriptorsForSend(new FileDescriptor[]{vsock.getFileDescriptor()});
                        OutputStream lsOut = local.getOutputStream();
                        lsOut.write(1);
                        lsOut.flush();
                        local.setFileDescriptorsForSend(null);

                        try { vsock.close(); } catch (IOException ignored) {}
                        vsock = null;

                        sVmNetConnected = true;
                        sGuestIp = "10.0.2.2";
                        syncForegroundState();
                        Log.i(TAG, "vmnet vsock:2223 FD handed off to libvmnet.so (SCM_RIGHTS)");

                        InputStream lsIn = local.getInputStream();
                        while (lsIn.read() >= 0) {
                            // Idle wait until libvmnet.so closes control socket on tunnel EOF
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        sVmNetConnected = false;
                        mVmNetCtrlSock = null;
                        if (local != null) { try { local.close(); } catch (IOException ignored) {} }
                        if (vsock != null) { try { vsock.close(); } catch (IOException ignored) {} }
                    }
                    if (!mVmNetActive || mVm != vm) break;
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
            }, "vmnet-ctrl");
            bridgeT.setDaemon(true);
            bridgeT.start();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start libvmnet.so", t);
            appendLog("[AVF Linux] vmnet start error: " + t.getMessage() + "\n");
        }
    }

    private void stopVmNetDaemon() {
        mVmNetActive = false;
        sVmNetConnected = false;
        LocalSocket ctrl = mVmNetCtrlSock;
        mVmNetCtrlSock = null;
        if (ctrl != null) {
            try { ctrl.close(); } catch (Throwable ignored) {}
        }
        if (mVmNetStdin != null) {
            try { mVmNetStdin.close(); } catch (Throwable ignored) {}
            mVmNetStdin = null;
        }
        if (mVmNetProc != null) {
            try { mVmNetProc.destroy(); } catch (Throwable ignored) {}
            mVmNetProc = null;
        }
        File sock = getVmNetSockFile();
        if (sock.exists()) sock.delete();
    }

    private void startVmInternal() {
        synchronized (mLock) {
            if (mVm != null && "RUNNING".equals(sState)) {
                appendLog("[AVF Linux] VM is already running.\n");
                return;
            }
            if (!mStore.hasImages()) {
                sState = "ERROR";
                sLastError = "Disk image not found at " + mStore.getDiskFile().getAbsolutePath();
                appendLog("[AVF Linux] " + sLastError + "\n");
                syncForegroundState();
                return;
            }

            sState = "STARTING";
            sGuestIp = "";
            sLastError = "";
            sVmNetConnected = false;
            clearConsole();
            syncForegroundState();

            try {
                Properties p = mStore.loadSettings();
                sMemMib = mStore.getIntProp(p, "memory_mib", VmConfigStore.DEFAULT_MEM_MIB);
                sDiskGb = mStore.getIntProp(p, "disk_gb", VmConfigStore.DEFAULT_DISK_GB);
                sSshPort = mStore.getIntProp(p, "ssh_local_port", VmConfigStore.DEFAULT_SSH_LOCAL_PORT);
                int vsockPort = mStore.getIntProp(p, "ssh_vsock_port", VmConfigStore.DEFAULT_SSH_VSOCK_PORT);
                sKeepAwake = mStore.getBoolProp(p, "keep_awake", sKeepAwake);

                File diskFile = mStore.getDiskFile();
                long wantBytes = ((long) sDiskGb) << 30;
                if (diskFile.length() < wantBytes) {
                    try (RandomAccessFile raf = new RandomAccessFile(diskFile, "rw")) {
                        raf.setLength(wantBytes);
                    }
                }

                VirtualMachineCustomImageConfig custom =
                    new VirtualMachineCustomImageConfig.Builder()
                        .setName(VmConfigStore.VM_NAME)
                        .setBootloaderPath(VmConfigStore.BOOTLOADER_PATH)
                        .addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(diskFile.getAbsolutePath()))
                        .useNetwork(false)
                        .build();

                VirtualMachineManager vmm = getSystemService(VirtualMachineManager.class);
                if (vmm == null) {
                    throw new IllegalStateException("VirtualMachineManager service is unavailable");
                }

                VirtualMachineConfig config =
                    new VirtualMachineConfig.Builder(this)
                        .setProtectedVm(false)
                        .setMemoryBytes(((long) sMemMib) * 1024L * 1024L)
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

                final long myRunId = ++mRunId;
                vm.setCallback(mCbExec, new VirtualMachineCallback() {
                    @Override
                    public void onPayloadStarted(VirtualMachine v) {}
                    @Override
                    public void onPayloadReady(VirtualMachine v) {}
                    @Override
                    public void onPayloadFinished(VirtualMachine v, int exitCode) {}

                    @Override
                    public void onError(VirtualMachine v, int errorCode, String message) {
                        sLastError = "VM error (" + errorCode + "): " + message;
                        appendLog("[AVF Linux] " + sLastError + "\n");
                        onVmStoppedCleanup(myRunId, false);
                    }

                    @Override
                    public void onStopped(VirtualMachine v, int reason) {
                        boolean guestReboot = (reason == VirtualMachineCallback.STOP_REASON_REBOOT) && !mShuttingDown;
                        appendLog("[AVF Linux] VM stopped (reason=" + reason
                            + (guestReboot ? ", rebooting..." : "") + ")\n");
                        if (onVmStoppedCleanup(myRunId, guestReboot)) {
                            mVmExec.execute(VmService.this::startVmInternal);
                        }
                    }
                });

                vm.run();
                mVm = vm;
                try {
                    mConsoleIn = vm.getConsoleInput();
                } catch (Throwable t) {
                    Log.w(TAG, "getConsoleInput failed: " + t.getMessage());
                }
                sState = "RUNNING";
                sStartedAtMs = System.currentTimeMillis();

                startConsoleReader(vm);
                startLogReader(vm);
                startSshBridge(vm, sSshPort, vsockPort);
                startVmNetDaemon(vm);
                appendLog("[AVF Linux] VM started (UEFI self-boot, match_host, "
                    + sMemMib + " MiB on-demand, WakeLock=" + (sKeepAwake ? "ON" : "OFF")
                    + ", SSH 127.0.0.1:" + sSshPort + ")\n");
            } catch (Throwable t) {
                sState = "ERROR";
                sLastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.e(TAG, "Failed to start VM", t);
                appendLog("[AVF Linux] Start failed: " + sLastError + "\n");
                stopVmNetDaemon();
            }
            syncForegroundState();
        }
    }

    private boolean onVmStoppedCleanup(long stoppedRunId, boolean restarting) {
        synchronized (mLock) {
            if (mRunId == stoppedRunId && mVm != null) {
                mVm = null;
                mConsoleIn = null;
                stopVmNetDaemon();
                closeQuietly(mSshServer);
                mSshServer = null;
                boolean keepRunning;
                synchronized (mStateLock) {
                    keepRunning = restarting && !"ERROR".equals(sState) && !isMaintenanceState();
                    if (!"ERROR".equals(sState) && !isMaintenanceState()) {
                        sState = keepRunning ? "STARTING" : "STOPPED";
                    }
                }
                sGuestIp = "";
                sStartedAtMs = 0L;
                syncForegroundState();
                return keepRunning;
            }
            return false;
        }
    }

    private void stopVmInternal(boolean preserveMaintenanceState) {
        synchronized (mLock) {
            mRunId++;
            mConsoleIn = null;
            stopVmNetDaemon();
            closeQuietly(mSshServer);
            mSshServer = null;
            if (mVm != null) {
                try { mVm.clearCallback(); } catch (Throwable ignored) {}
                try {
                    mVm.stop();
                } catch (Throwable t) {
                    Log.w(TAG, "vm.stop error", t);
                }
                mVm = null;
            }
            if (!preserveMaintenanceState) {
                sState = "STOPPED";
            }
            sGuestIp = "";
            sStartedAtMs = 0L;
            syncForegroundState();
        }
    }

    private void startConsoleReader(final VirtualMachine vm) {
        Thread t = new Thread(() -> {
            String carry = "";
            try (InputStreamReader reader = new InputStreamReader(
                    vm.getConsoleOutput(), StandardCharsets.UTF_8)) {
                char[] cbuf = new char[2048];
                int n;
                while ((n = reader.read(cbuf)) > 0) {
                    String combined = carry.isEmpty()
                        ? new String(cbuf, 0, n)
                        : carry + new String(cbuf, 0, n);
                    int split = findIncompleteAnsiTail(combined);
                    if (split >= 0) {
                        carry = combined.substring(split);
                        if (split > 0) appendLog(combined.substring(0, split));
                    } else {
                        carry = "";
                        appendLog(combined);
                    }
                }
                if (!carry.isEmpty()) {
                    appendLog(carry);
                }
            } catch (Throwable e) {
                Log.i(TAG, "Console stream ended: " + e.getMessage());
            }
        }, "vm-console");
        t.setDaemon(true);
        t.start();
    }

    private void startLogReader(final VirtualMachine vm) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(vm.getLogOutput(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    Log.i(TAG, "[vmlog] " + line);
                }
            } catch (Throwable ignored) {}
        }, "vm-log");
        t.setDaemon(true);
        t.start();
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

    private void startSshBridge(final VirtualMachine vm, final int localPort, final int vsockPort) {
        closeQuietly(mSshServer);
        try {
            final ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort), 32);
            mSshServer = server;
            Thread acceptThread = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        final Socket sock = server.accept();
                        Thread ct = new Thread(() -> relayTcpToVsock(vm, sock, vsockPort), "ssh-relay");
                        ct.setDaemon(true);
                        ct.start();
                    } catch (IOException e) {
                        break;
                    }
                }
            }, "ssh-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            appendLog("[AVF Linux] SSH bridge bind failed on 127.0.0.1:" + localPort + ": " + e.getMessage() + "\n");
        }
    }

    private static void relayTcpToVsock(VirtualMachine vm, Socket sock, int vsockPort) {
        ParcelFileDescriptor vsock = null;
        try {
            sock.setTcpNoDelay(true);
            vsock = vm.connectVsock(vsockPort);
            final ParcelFileDescriptor vfd = vsock;
            clearNonBlocking(vfd);

            final InputStream sockIn = sock.getInputStream();
            final OutputStream sockOut = sock.getOutputStream();
            final FileInputStream vsIn = new FileInputStream(vfd.getFileDescriptor());
            final FileOutputStream vsOut = new FileOutputStream(vfd.getFileDescriptor());
            final AtomicInteger rem = new AtomicInteger(2);

            Runnable done = () -> {
                if (rem.decrementAndGet() == 0) {
                    try { sock.close(); } catch (IOException ignored) {}
                    try { vfd.close(); } catch (IOException ignored) {}
                }
            };

            Thread up = new Thread(() -> {
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = sockIn.read(buf)) > 0) {
                        vsOut.write(buf, 0, n);
                        vsOut.flush();
                    }
                } catch (IOException ignored) {
                } finally {
                    try { Os.shutdown(vfd.getFileDescriptor(), OsConstants.SHUT_WR); } catch (Throwable ignored) {}
                    done.run();
                }
            }, "tcp2vsock");
            up.setDaemon(true);
            up.start();

            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = vsIn.read(buf)) > 0) {
                    sockOut.write(buf, 0, n);
                    sockOut.flush();
                }
            } catch (IOException ignored) {
            } finally {
                try { sock.shutdownOutput(); } catch (IOException ignored) {}
                done.run();
            }
        } catch (Throwable e) {
            Log.w(TAG, "SSH vsock relay error: " + e.getMessage());
            try { sock.close(); } catch (IOException ignored) {}
            if (vsock != null) {
                try { vsock.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void startControlServer() {
        try {
            final ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), sCtlPort), 16);
            mCtlServer = server;
            Thread t = new Thread(() -> {
                while (!server.isClosed() && !mShuttingDown) {
                    try {
                        Socket s = server.accept();
                        Thread ht = new Thread(() -> handleHttpControl(s), "ctl-req");
                        ht.setDaemon(true);
                        ht.start();
                    } catch (IOException e) {
                        break;
                    }
                }
            }, "ctl-server");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            Log.e(TAG, "Control HTTP server failed on 127.0.0.1:" + sCtlPort, e);
        }
    }

    private static String readHttpHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') {
                if (bos.size() >= 4096) throw new IOException("HTTP header line too long");
                bos.write(b);
            }
        }
        if (b < 0 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.US_ASCII);
    }

    private void handleHttpControl(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 4096);
            String reqLine = readHttpHeaderLine(in);
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            String authHeader = "";
            int contentLen = 0;
            String line;
            while ((line = readHttpHeaderLine(in)) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Authorization:", 0, 14)) {
                    authHeader = line.substring(14).trim();
                } else if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    try { contentLen = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                }
            }

            if (!("Bearer " + mToken).equals(authHeader)) {
                writeHttp(sock, 401, "Unauthorized", "application/json", "{\"error\":\"unauthorized\"}\n");
                return;
            }

            if ("GET".equals(method) && "/status".equals(path)) {
                writeHttp(sock, 200, "OK", "application/json", buildStatusJson());
            } else if ("GET".equals(method) && "/console".equals(path)) {
                writeHttp(sock, 200, "OK", "text/plain; charset=utf-8", getConsoleSnapshot());
            } else if ("POST".equals(method) && "/console/input".equals(path)) {
                if (contentLen > 0 && contentLen <= 4096) {
                    byte[] body = new byte[contentLen];
                    readFully(in, body, contentLen);
                    sendConsoleInput(body);
                }
                writeHttp(sock, 200, "OK", "application/json", "{\"ok\":true}\n");
            } else if ("POST".equals(method) && "/start".equals(path)) {
                if (isMaintenanceState()) {
                    writeHttp(sock, 409, "Conflict", "application/json", "{\"error\":\"busy\"}\n");
                } else {
                    mVmExec.execute(this::startVmInternal);
                    writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"start\"}\n");
                }
            } else if ("POST".equals(method) && "/stop".equals(path)) {
                mVmExec.execute(() -> stopVmInternal(false));
                writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"stop\"}\n");
            } else if ("POST".equals(method) && "/restart".equals(path)) {
                if (!enqueueRestart()) {
                    writeHttp(sock, 409, "Conflict", "application/json", "{\"error\":\"busy\"}\n");
                } else {
                    writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"restart\"}\n");
                }
            } else if ("POST".equals(method) && "/import".equals(path)) {
                int rc = enqueueImport();
                if (rc == 409) {
                    writeHttp(sock, 409, "Conflict", "application/json", "{\"error\":\"busy\"}\n");
                } else if (rc == 400) {
                    writeHttp(sock, 400, "Bad Request", "application/json",
                        "{\"error\":\"staging_empty\"}\n");
                } else {
                    writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"import\"}\n");
                }
            } else if ("POST".equals(method) && "/reset".equals(path)) {
                int rc = enqueueReset();
                if (rc == 409) {
                    writeHttp(sock, 409, "Conflict", "application/json", "{\"error\":\"busy\"}\n");
                } else if (rc == 400) {
                    writeHttp(sock, 400, "Bad Request", "application/json",
                        "{\"error\":\"seed_missing\"}\n");
                } else {
                    writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"reset\"}\n");
                }
            } else if ("POST".equals(method) && "/wakelock/on".equals(path)) {
                setKeepAwake(true);
                writeHttp(sock, 200, "OK", "application/json", buildStatusJson());
            } else if ("POST".equals(method) && "/wakelock/off".equals(path)) {
                setKeepAwake(false);
                writeHttp(sock, 200, "OK", "application/json", buildStatusJson());
            } else if ("POST".equals(method) && "/wakelock/toggle".equals(path)) {
                setKeepAwake(!sKeepAwake);
                writeHttp(sock, 200, "OK", "application/json", buildStatusJson());
            } else {
                writeHttp(sock, 404, "Not Found", "application/json", "{\"error\":\"not_found\"}\n");
            }
        } catch (Throwable ignored) {}
    }

    private String buildStatusJson() {
        long uptime = (sStartedAtMs > 0 && "RUNNING".equals(sState))
            ? (System.currentTimeMillis() - sStartedAtMs) / 1000L : 0L;
        long actualMb = getActualDiskBytes() / (1024L * 1024L);
        long seedMb = mStore.getSeedBytes() / (1024L * 1024L);
        return "{"
            + "\"running\":" + ("RUNNING".equals(sState))
            + ",\"state\":\"" + esc(sState) + "\""
            + ",\"guest_ip\":\"" + esc(sGuestIp) + "\""
            + ",\"net_mode\":\"" + esc(sNetMode) + "\""
            + ",\"vmnet_connected\":" + sVmNetConnected
            + ",\"keep_awake\":" + sKeepAwake
            + ",\"wakelock_held\":" + sWakeLockHeld
            + ",\"ssh_local\":\"127.0.0.1:" + sSshPort + "\""
            + ",\"memory_mib\":" + sMemMib
            + ",\"disk_logical_gb\":" + sDiskGb
            + ",\"disk_actual_mb\":" + actualMb
            + ",\"seed_ready\":" + mStore.hasSeed()
            + ",\"seed_mb\":" + seedMb
            + ",\"uptime_sec\":" + uptime
            + ",\"images_ready\":" + mStore.hasImages()
            + ",\"error\":\"" + esc(sLastError) + "\""
            + "}\n";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static void writeHttp(Socket s, int code, String reason, String ctype, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 " + code + " " + reason + "\r\n"
            + "Content-Type: " + ctype + "\r\n"
            + "Content-Length: " + b.length + "\r\n"
            + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
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

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        boolean running = "RUNNING".equals(sState) || "STARTING".equals(sState);
        boolean maintenance = "IMPORTING".equals(sState) || "RESETTING".equals(sState);
        boolean active = running || maintenance;
        Intent toggleIntent = new Intent(this, VmService.class);
        toggleIntent.setAction(running ? ACTION_STOP : ACTION_START);
        toggleIntent.putExtra(EXTRA_TOKEN, mToken);
        PendingIntent togglePi = PendingIntent.getService(
            this, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String sub;
        if (running) {
            sub = "SSH 127.0.0.1:" + sSshPort + " · " + sNetMode
                + (sKeepAwake ? " · WakeLock ON" : "");
        } else if ("IMPORTING".equals(sState)) {
            sub = "Importing VM disk image...";
        } else if ("RESETTING".equals(sState)) {
            sub = "Resetting VM disk from golden seed...";
        } else {
            sub = sLastError.isEmpty() ? "Stopped" : sLastError;
        }

        Notification.Builder nb = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("AVF Linux (" + sState + ")")
            .setContentText(sub)
            .setOngoing(active)
            .setContentIntent(openPi);
        if (!maintenance) {
            nb.addAction(new Notification.Action.Builder(
                null, running ? "Stop VM" : "Start VM", togglePi).build());
            if (running) {
                Intent wlIntent = new Intent(this, VmService.class);
                wlIntent.setAction(ACTION_TOGGLE_WAKELOCK);
                wlIntent.putExtra(EXTRA_TOKEN, mToken);
                PendingIntent wlPi = PendingIntent.getService(
                    this, 2, wlIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                nb.addAction(new Notification.Action.Builder(
                    null, sKeepAwake ? "WakeLock: ON" : "WakeLock: OFF", wlPi).build());
            }
        }
        return nb.build();
    }

    private synchronized void syncWakeLockState() {
        boolean shouldHold = "IMPORTING".equals(sState)
            || "RESETTING".equals(sState)
            || (!mShuttingDown && sKeepAwake && ("STARTING".equals(sState) || "RUNNING".equals(sState)));
        if (shouldHold) {
            if (mWakeLock == null) {
                PowerManager pm = getSystemService(PowerManager.class);
                if (pm != null) {
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AvfLinux:VmCpu");
                    mWakeLock.setReferenceCounted(false);
                }
            }
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire();
            }
        } else {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
        sWakeLockHeld = (mWakeLock != null && mWakeLock.isHeld());
    }

    private void syncForegroundState() {
        syncWakeLockState();
        boolean active = "RUNNING".equals(sState)
            || "STARTING".equals(sState)
            || "IMPORTING".equals(sState)
            || "RESETTING".equals(sState);
        if (active) {
            try {
                startForeground(NOTIF_ID, buildNotification());
            } catch (Throwable t) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIF_ID, buildNotification());
            }
        } else {
            try {
                stopForeground(true);
            } catch (Throwable ignored) {}
        }
        sStateSeq++;
        notifyUi();
    }

    private static void closeQuietly(ServerSocket ss) {
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }
    }
}
