package com.avf.linux;

import android.content.Context;
import android.system.Os;
import android.system.StructStat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * Cohesive local storage manager for VM images, configuration, auth token,
 * DNS resolver state, and persisted session logs.
 */
public final class VmConfigStore {
    public static final String VM_NAME = "debian13";
    public static final String BOOTLOADER_PATH = "/apex/com.android.virt/etc/u-boot.bin";
    public static final int DEFAULT_MEM_MIB = 4096;
    public static final int DEFAULT_DISK_GB = 64;
    public static final int DEFAULT_SSH_LOCAL_PORT = 2222;
    public static final int DEFAULT_SSH_VSOCK_PORT = 2222;
    public static final int DEFAULT_CTL_PORT = 2288;

    private static final String LAST_CONSOLE_FILE = "last-console.log";
    private static final String LAST_VMLOG_FILE = "last-vmlog.log";
    private static final String ACTIVE_DNS_FILE = "active-dns.txt";

    private final Context mContext;
    private final Object mDnsWriteLock = new Object();
    private String mLastDnsContent = "";

    public VmConfigStore(Context context) {
        mContext = context.getApplicationContext();
    }

    public File getFilesDir() {
        return mContext.getFilesDir();
    }

    public File getVmDir() {
        File dir = new File(mContext.getFilesDir(), "vm");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public File getStagingDir() {
        File ext = mContext.getExternalFilesDir("staging");
        if (ext != null && !ext.exists()) ext.mkdirs();
        return ext;
    }

    public File getDiskFile() { return new File(getVmDir(), "disk.img"); }
    public File getSeedFile() { return new File(getVmDir(), "seed.simg.gz"); }
    public File getDnsFile() { return new File(mContext.getFilesDir(), ACTIVE_DNS_FILE); }
    public File getSessionVmNetSocket(long sessionId) {
        return new File(mContext.getFilesDir(), "vmnet-" + sessionId + ".sock");
    }

    public boolean hasImages() {
        return getDiskFile().isFile();
    }

    public boolean hasSeed() {
        return getSeedFile().isFile();
    }

    public File findStagingImage() {
        File staging = getStagingDir();
        if (staging == null || !staging.isDirectory()) return null;
        String[] names = {"seed.simg.gz", "disk.simg", "disk.img"};
        for (String name : names) {
            File f = new File(staging, name);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    public void cleanupLegacyFiles() {
        File dir = getVmDir();
        String[] legacy = {"vmlinuz", "initrd.img", "root_part"};
        for (String name : legacy) {
            File f = new File(dir, name);
            if (f.exists()) {
                f.delete();
            }
        }
    }

    public void cleanupStaleVmNetSockets() {
        File dir = mContext.getFilesDir();
        File[] files = (dir != null) ? dir.listFiles() : null;
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.equals("vmnet.sock") || (name.startsWith("vmnet-") && name.endsWith(".sock"))) {
                f.delete();
            }
        }
    }

    public void writeActiveDns(String content) {
        if (content == null || content.isEmpty()) return;
        synchronized (mDnsWriteLock) {
            File dst = getDnsFile();
            if (!content.equals(mLastDnsContent) || !dst.isFile()) {
                if (writeTextAtomically(ACTIVE_DNS_FILE, content)) {
                    mLastDnsContent = content;
                }
            }
        }
    }

    public void saveSessionLogs(String consoleText, String vmLogText) {
        if (consoleText != null && !consoleText.isEmpty()) {
            writeTextAtomically(LAST_CONSOLE_FILE, consoleText);
        }
        if (vmLogText != null && !vmLogText.isEmpty()) {
            writeTextAtomically(LAST_VMLOG_FILE, vmLogText);
        }
    }

    public String readLastConsoleLog() {
        return readTextQuietly(LAST_CONSOLE_FILE);
    }

    public String readLastVmLog() {
        return readTextQuietly(LAST_VMLOG_FILE);
    }

    private boolean writeTextAtomically(String name, String content) {
        File dst = new File(mContext.getFilesDir(), name);
        File tmp = new File(mContext.getFilesDir(), name + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            return tmp.renameTo(dst);
        } catch (Throwable ignored) {
            if (tmp.exists()) tmp.delete();
            return false;
        }
    }

    private String readTextQuietly(String name) {
        File f = new File(mContext.getFilesDir(), name);
        if (!f.isFile() || f.length() <= 0) return "";
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(f.length(), 262144));
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public long getActualDiskBytes() {
        File f = getDiskFile();
        if (!f.isFile()) return 0L;
        try {
            StructStat st = Os.stat(f.getAbsolutePath());
            return st.st_blocks * 512L;
        } catch (Throwable t) {
            return f.length();
        }
    }

    public long getSeedBytes() {
        File f = getSeedFile();
        return f.isFile() ? f.length() : 0L;
    }

    public synchronized String getOrCreateToken() {
        File f = new File(mContext.getFilesDir(), "ctl-token");
        if (f.isFile() && f.length() >= 32) {
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int n = fis.read(buf);
                if (n >= 32) {
                    return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                }
            } catch (IOException ignored) {}
        }
        byte[] rnd = new byte[24];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder(48);
        for (byte b : rnd) {
            sb.append(String.format("%02x", b & 0xff));
        }
        String tok = sb.toString();
        try (FileOutputStream fos = mContext.openFileOutput("ctl-token", Context.MODE_PRIVATE)) {
            fos.write((tok + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
        return tok;
    }

    public synchronized Properties loadSettings() {
        Properties props = new Properties();
        props.setProperty("memory_mib", String.valueOf(DEFAULT_MEM_MIB));
        props.setProperty("disk_gb", String.valueOf(DEFAULT_DISK_GB));
        props.setProperty("ssh_local_port", String.valueOf(DEFAULT_SSH_LOCAL_PORT));
        props.setProperty("ssh_vsock_port", String.valueOf(DEFAULT_SSH_VSOCK_PORT));
        props.setProperty("ctl_port", String.valueOf(DEFAULT_CTL_PORT));
        props.setProperty("keep_awake", "false");
        File f = new File(mContext.getFilesDir(), "vm.properties");
        if (f.isFile()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                props.load(fis);
            } catch (IOException ignored) {}
        }
        return props;
    }

    public synchronized void saveKeepAwake(boolean enable) {
        Properties props = loadSettings();
        props.setProperty("keep_awake", Boolean.toString(enable));
        File tmp = new File(mContext.getFilesDir(), "vm.properties.tmp");
        File dst = new File(mContext.getFilesDir(), "vm.properties");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            props.store(fos, "AVF Linux settings");
            fos.flush();
            fos.getFD().sync();
            tmp.renameTo(dst);
        } catch (IOException ignored) {}
    }

    public int getIntProp(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    public boolean getBoolProp(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        v = v.trim();
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) return true;
        if ("false".equalsIgnoreCase(v) || "0".equals(v)) return false;
        return def;
    }
}
