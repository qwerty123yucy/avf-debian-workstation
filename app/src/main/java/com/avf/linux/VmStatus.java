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
            memMib,
            diskGb,
            sshPort,
            ctlPort,
            startedAtMs,
            lastError
        );
    }

    public String toJson(VmConfigStore store, boolean wakeLockCurrentlyHeld) {
        long uptime = (startedAtMs > 0 && state == State.RUNNING)
            ? Math.max(0L, (System.currentTimeMillis() - startedAtMs) / 1000L)
            : 0L;
        long actualMb = store.getActualDiskBytes() / (1024L * 1024L);
        long seedMb = store.getSeedBytes() / (1024L * 1024L);
        return "{"
            + "\"running\":" + (state == State.RUNNING)
            + ",\"state\":\"" + state.name() + "\""
            + ",\"guest_ip\":\"" + esc(guestIp) + "\""
            + ",\"net_mode\":\"" + esc(netMode) + "\""
            + ",\"vmnet_connected\":" + vmNetConnected
            + ",\"keep_awake\":" + keepAwake
            + ",\"wakelock_held\":" + wakeLockCurrentlyHeld
            + ",\"ssh_local\":\"127.0.0.1:" + sshPort + "\""
            + ",\"memory_mib\":" + memMib
            + ",\"disk_logical_gb\":" + diskGb
            + ",\"disk_actual_mb\":" + actualMb
            + ",\"seed_ready\":" + store.hasSeed()
            + ",\"seed_mb\":" + seedMb
            + ",\"uptime_sec\":" + uptime
            + ",\"images_ready\":" + store.hasImages()
            + ",\"error\":\"" + esc(lastError) + "\""
            + "}\n";
    }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
