package com.avf.linux;

import java.util.Properties;

/**
 * Immutable, atomic snapshot of VM service state shared lock-free with UI,
 * notifications, and the localhost HTTP control server.
 */
public final class VmStatus {
    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        IMPORTING,
        RESETTING,
        ERROR;

        public boolean isMaintenance() {
            return this == IMPORTING || this == RESETTING;
        }

        public boolean isBusy() {
            return this == STARTING || this == STOPPING || this == IMPORTING || this == RESETTING;
        }

        public boolean isActive() {
            return this == RUNNING || isBusy();
        }

        public boolean showsConsoleCursor() {
            return this == RUNNING || this == STARTING || this == STOPPING;
        }
    }

    public final long seq;
    public final State state;
    public final String guestIp;
    public final String netMode;
    public final boolean vmNetConnected;
    public final boolean keepAwake;
    public final boolean wakeLockHeld;
    public final boolean showOverlay;
    public final int memMib;
    public final int diskGb;
    public final int sshPort;
    public final int ctlPort;
    public final long startedAtMs;
    public final String lastError;

    public VmStatus(
            long seq,
            State state,
            String guestIp,
            String netMode,
            boolean vmNetConnected,
            boolean keepAwake,
            boolean wakeLockHeld,
            boolean showOverlay,
            int memMib,
            int diskGb,
            int sshPort,
            int ctlPort,
            long startedAtMs,
            String lastError) {
        this.seq = seq;
        this.state = (state != null) ? state : State.STOPPED;
        this.guestIp = (guestIp != null) ? guestIp : "";
        this.netMode = (netMode != null) ? netMode : "Offline";
        this.vmNetConnected = vmNetConnected;
        this.keepAwake = keepAwake;
        this.wakeLockHeld = wakeLockHeld;
        this.showOverlay = showOverlay;
        this.memMib = memMib;
        this.diskGb = diskGb;
        this.sshPort = sshPort;
        this.ctlPort = ctlPort;
        this.startedAtMs = startedAtMs;
        this.lastError = (lastError != null) ? lastError : "";
    }

    public static VmStatus initialDefaults() {
        return new VmStatus(
            0L,
            State.STOPPED,
            "",
            "Host Net",
            false,
            false,
            false,
            true,
            VmConfigStore.DEFAULT_MEM_MIB,
            VmConfigStore.DEFAULT_DISK_GB,
            VmConfigStore.DEFAULT_SSH_LOCAL_PORT,
            VmConfigStore.DEFAULT_CTL_PORT,
            0L,
            ""
        );
    }

    public static VmStatus fromStore(long seq, VmConfigStore store) {
        Properties p = store.loadSettings();
        return new VmStatus(
            seq,
            State.STOPPED,
            "",
            "Host Net",
            false,
            store.getBoolProp(p, "keep_awake", false),
            false,
            store.getBoolProp(p, "show_overlay", true),
            store.getIntProp(p, "memory_mib", VmConfigStore.DEFAULT_MEM_MIB),
            store.getIntProp(p, "disk_gb", VmConfigStore.DEFAULT_DISK_GB),
            store.getIntProp(p, "ssh_local_port", VmConfigStore.DEFAULT_SSH_LOCAL_PORT),
            store.getIntProp(p, "ctl_port", VmConfigStore.DEFAULT_CTL_PORT),
            0L,
            ""
        );
    }

    public VmStatus withKeepAwake(long nextSeq, boolean nextKeepAwake, boolean nextWakeLockHeld) {
        return new VmStatus(
            nextSeq,
            state,
            guestIp,
            netMode,
            vmNetConnected,
            nextKeepAwake,
            nextWakeLockHeld,
            showOverlay,
            memMib,
            diskGb,
            sshPort,
            ctlPort,
            startedAtMs,
            lastError
        );
    }

    public VmStatus withShowOverlay(long nextSeq, boolean nextShowOverlay) {
        return new VmStatus(
            nextSeq,
            state,
            guestIp,
            netMode,
            vmNetConnected,
            keepAwake,
            wakeLockHeld,
            nextShowOverlay,
            memMib,
            diskGb,
            sshPort,
            ctlPort,
            startedAtMs,
            lastError
        );
    }

    public static final class RuntimeDiag {
        public final boolean wakeLockHeld;
        public final boolean wifiLockHeld;
        public final boolean batteryUnrestricted;
        public final boolean foregroundActive;
        public final boolean overlayShown;
        public final int freezeCount;
        public final long lastFreezeMs;
        public final long lastFreezeAgoSec;
        public final boolean netBlocked;
        public final int netBlockedCount;

        public RuntimeDiag(
                boolean wakeLockHeld,
                boolean wifiLockHeld,
                boolean batteryUnrestricted,
                boolean foregroundActive,
                boolean overlayShown,
                int freezeCount,
                long lastFreezeMs,
                long lastFreezeAgoSec,
                boolean netBlocked,
                int netBlockedCount) {
            this.wakeLockHeld = wakeLockHeld;
            this.wifiLockHeld = wifiLockHeld;
            this.batteryUnrestricted = batteryUnrestricted;
            this.foregroundActive = foregroundActive;
            this.overlayShown = overlayShown;
            this.freezeCount = freezeCount;
            this.lastFreezeMs = lastFreezeMs;
            this.lastFreezeAgoSec = lastFreezeAgoSec;
            this.netBlocked = netBlocked;
            this.netBlockedCount = netBlockedCount;
        }
    }

    public String toJson(VmConfigStore store, RuntimeDiag diag) {
        long uptime = (startedAtMs > 0 && state == State.RUNNING)
            ? Math.max(0L, (System.currentTimeMillis() - startedAtMs) / 1000L)
            : 0L;
        long actualMb = (store != null) ? (store.getActualDiskBytes() / (1024L * 1024L)) : 0L;
        long seedMb = (store != null) ? (store.getSeedBytes() / (1024L * 1024L)) : 0L;
        boolean seedReady = (store != null) && store.hasSeed();
        boolean imagesReady = (store != null) && store.hasImages();
        return "{"
            + "\"running\":" + (state == State.RUNNING)
            + ",\"state\":\"" + state.name() + "\""
            + ",\"guest_ip\":\"" + esc(guestIp) + "\""
            + ",\"net_mode\":\"" + esc(netMode) + "\""
            + ",\"vmnet_connected\":" + vmNetConnected
            + ",\"keep_awake\":" + keepAwake
            + ",\"wakelock_held\":" + diag.wakeLockHeld
            + ",\"wifilock_held\":" + diag.wifiLockHeld
            + ",\"show_overlay\":" + showOverlay
            + ",\"overlay_shown\":" + diag.overlayShown
            + ",\"battery_unrestricted\":" + diag.batteryUnrestricted
            + ",\"foreground_service\":" + diag.foregroundActive
            + ",\"freeze_count\":" + diag.freezeCount
            + ",\"last_freeze_ms\":" + diag.lastFreezeMs
            + ",\"last_freeze_ago_sec\":" + diag.lastFreezeAgoSec
            + ",\"net_blocked\":" + diag.netBlocked
            + ",\"net_blocked_count\":" + diag.netBlockedCount
            + ",\"ssh_local\":\"127.0.0.1:" + sshPort + "\""
            + ",\"memory_mib\":" + memMib
            + ",\"disk_logical_gb\":" + diskGb
            + ",\"disk_actual_mb\":" + actualMb
            + ",\"seed_ready\":" + seedReady
            + ",\"seed_mb\":" + seedMb
            + ",\"uptime_sec\":" + uptime
            + ",\"images_ready\":" + imagesReady
            + ",\"error\":\"" + esc(lastError) + "\""
            + "}\n";
    }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
