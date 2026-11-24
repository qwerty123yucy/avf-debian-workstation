package android.system.virtualmachine;

public class VirtualMachineCustomImageConfig {
    public static final class Disk {
        private Disk() {}
        public static Disk RWDisk(String imagePath) { throw new RuntimeException("Stub"); }
        public static Disk RODisk(String imagePath) { throw new RuntimeException("Stub"); }
    }

    public static final class Builder {
        public Builder() {}
        public Builder setName(String name) { throw new RuntimeException("Stub"); }
        public Builder setBootloaderPath(String path) { throw new RuntimeException("Stub"); }
        public Builder addDisk(Disk disk) { throw new RuntimeException("Stub"); }
        public Builder useNetwork(boolean useNetwork) { throw new RuntimeException("Stub"); }
        public VirtualMachineCustomImageConfig build() { throw new RuntimeException("Stub"); }
    }
}
