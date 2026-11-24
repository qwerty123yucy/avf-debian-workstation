package android.system.virtualmachine;

import android.content.Context;

public final class VirtualMachineConfig {
    public static final int CPU_TOPOLOGY_ONE_CPU = 0;
    public static final int CPU_TOPOLOGY_MATCH_HOST = 1;
    public static final int DEBUG_LEVEL_NONE = 0;
    public static final int DEBUG_LEVEL_FULL = 1;

    private VirtualMachineConfig() {}

    public static final class Builder {
        public Builder(Context context) {}
        public Builder setProtectedVm(boolean protectedVm) { throw new RuntimeException("Stub"); }
        public Builder setMemoryBytes(long memoryBytes) { throw new RuntimeException("Stub"); }
        public Builder setCpuTopology(int cpuTopology) { throw new RuntimeException("Stub"); }
        public Builder setDebugLevel(int debugLevel) { throw new RuntimeException("Stub"); }
        public Builder setConsoleInputDevice(String device) { throw new RuntimeException("Stub"); }
        public Builder setVmOutputCaptured(boolean captured) { throw new RuntimeException("Stub"); }
        public Builder setVmConsoleInputSupported(boolean supported) { throw new RuntimeException("Stub"); }
        public Builder setCustomImageConfig(VirtualMachineCustomImageConfig config) { throw new RuntimeException("Stub"); }
        public VirtualMachineConfig build() { throw new RuntimeException("Stub"); }
    }
}
