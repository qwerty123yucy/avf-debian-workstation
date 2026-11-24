package android.system.virtualmachine;

public class VirtualMachineManager {
    private VirtualMachineManager() {}

    public VirtualMachine getOrCreate(String name, VirtualMachineConfig config) throws VirtualMachineException {
        throw new RuntimeException("Stub");
    }
    public void delete(String name) throws VirtualMachineException {
        throw new RuntimeException("Stub");
    }
}
