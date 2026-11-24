package android.system.virtualmachine;

import android.os.ParcelFileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

public class VirtualMachine implements AutoCloseable {
    private VirtualMachine() {}

    public VirtualMachineConfig setConfig(VirtualMachineConfig config) throws VirtualMachineException {
        throw new RuntimeException("Stub");
    }
    public void setCallback(Executor executor, VirtualMachineCallback callback) {
        throw new RuntimeException("Stub");
    }
    public void clearCallback() {
        throw new RuntimeException("Stub");
    }
    public void run() throws VirtualMachineException {
        throw new RuntimeException("Stub");
    }
    public void stop() throws VirtualMachineException {
        throw new RuntimeException("Stub");
    }
    public void close() {
        throw new RuntimeException("Stub");
    }
    public InputStream getConsoleOutput() throws VirtualMachineException {
        throw new RuntimeException("Stub");
    }
    public OutputStream getConsoleInput() throws VirtualMachineException {
        throw new RuntimeException("Stub");
    }
    public InputStream getLogOutput() throws VirtualMachineException {
        throw new RuntimeException("Stub");
    }
    public ParcelFileDescriptor connectVsock(long port) throws VirtualMachineException {
        throw new RuntimeException("Stub");
    }
}
