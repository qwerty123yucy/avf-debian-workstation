package com.avf.linux;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Localhost-only (127.0.0.1) authenticated HTTP control server for CLI tooling (avf-vm).
 *
 * Uses a bounded daemon worker pool and constant-time bearer token verification,
 * delegating state transitions cleanly to VmService's state machine.
 */
final class VmControlServer {
    private static final String TAG = "AvfLinux";

    interface Controller {
        String getStatusJson();
        String getLiveConsoleText();
        String getLastConsoleText();
        String getLastVmLogText();
        void sendConsoleInput(byte[] data);
        int requestStart();
        int requestStop();
        boolean requestRestart();
        int requestImportStaging();
        int requestReset();
        void setKeepAwake(boolean enable);
        void toggleKeepAwake();
        void setShowOverlay(boolean enable);
        void toggleShowOverlay();
    }

    private final int mPort;
    private final byte[] mExpectedAuthBytes;
    private final Controller mController;
    private final AtomicBoolean mClosed = new AtomicBoolean(false);
    private final ExecutorService mWorkerPool;
    private ServerSocket mServer;

    VmControlServer(int port, String token, Controller controller) {
        mPort = port;
        mExpectedAuthBytes = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        mController = controller;
        mWorkerPool = new ThreadPoolExecutor(
            1, 4, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32),
            r -> {
                Thread t = new Thread(r, "ctl-req");
                t.setDaemon(true);
                return t;
            }
        );
    }

    void start() {
        try {
            final ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), mPort), 16);
            mServer = server;
            Thread acceptThread = new Thread(() -> {
                while (!mClosed.get() && !server.isClosed()) {
                    try {
                        final Socket s = server.accept();
                        try {
                            mWorkerPool.execute(() -> handleClient(s));
                        } catch (RejectedExecutionException rex) {
                            try { s.close(); } catch (IOException ignored) {}
                        }
                    } catch (IOException e) {
                        break;
                    }
                }
            }, "ctl-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            Log.e(TAG, "Control HTTP server failed on 127.0.0.1:" + mPort, e);
        }
    }

    void close() {
        if (!mClosed.compareAndSet(false, true)) return;
        ServerSocket s = mServer;
        mServer = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        mWorkerPool.shutdownNow();
    }

    private void handleClient(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 4096);
            String reqLine = readHttpHeaderLine(in);
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String rawPath = parts.length > 1 ? parts[1] : "/";
            int qIdx = rawPath.indexOf('?');
            String path = (qIdx >= 0) ? rawPath.substring(0, qIdx) : rawPath;

            String authHeader = "";
            int contentLen = 0;
            String line;
            while ((line = readHttpHeaderLine(in)) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Authorization:", 0, 14)) {
                    authHeader = line.substring(14).trim();
                } else if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    try {
                        contentLen = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException nfe) {
                        contentLen = -1;
                    }
                }
            }

            byte[] gotAuth = authHeader.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(mExpectedAuthBytes, gotAuth)) {
                writeHttp(sock, 401, "Unauthorized", "application/json", "{\"error\":\"unauthorized\"}\n");
                return;
            }

            if ("GET".equals(method) && "/status".equals(path)) {
                writeHttp(sock, 200, "OK", "application/json", mController.getStatusJson());
            } else if ("GET".equals(method) && "/console".equals(path)) {
                writeHttp(sock, 200, "OK", "text/plain; charset=utf-8", mController.getLiveConsoleText());
            } else if ("GET".equals(method) && "/console/last".equals(path)) {
                writeHttp(sock, 200, "OK", "text/plain; charset=utf-8", mController.getLastConsoleText());
            } else if ("GET".equals(method) && "/vmlog/last".equals(path)) {
                writeHttp(sock, 200, "OK", "text/plain; charset=utf-8", mController.getLastVmLogText());
            } else if ("POST".equals(method) && "/console/input".equals(path)) {
                if (contentLen < 0 || contentLen > 4096) {
                    writeHttp(sock, 400, "Bad Request", "application/json", "{\"error\":\"payload_size\"}\n");
                    return;
                }
                if (contentLen > 0) {
                    byte[] body = new byte[contentLen];
                    readFully(in, body, contentLen);
                    mController.sendConsoleInput(body);
                }
                writeHttp(sock, 200, "OK", "application/json", "{\"ok\":true}\n");
            } else if ("POST".equals(method) && "/start".equals(path)) {
                int rc = mController.requestStart();
                if (rc == 409) {
                    writeHttp(sock, 409, "Conflict", "application/json", "{\"error\":\"busy\"}\n");
                } else if (rc == 400) {
                    writeHttp(sock, 400, "Bad Request", "application/json", "{\"error\":\"disk_missing\"}\n");
                } else if (rc == 200) {
                    writeHttp(sock, 200, "OK", "application/json", "{\"ok\":true,\"action\":\"already_running\"}\n");
                } else {
                    writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"start\"}\n");
                }
            } else if ("POST".equals(method) && "/stop".equals(path)) {
                int rc = mController.requestStop();
                if (rc == 409) {
                    writeHttp(sock, 409, "Conflict", "application/json", "{\"error\":\"busy\"}\n");
                } else if (rc == 200) {
                    writeHttp(sock, 200, "OK", "application/json", "{\"ok\":true,\"action\":\"already_stopped\"}\n");
                } else {
                    writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"stop\"}\n");
                }
            } else if ("POST".equals(method) && "/restart".equals(path)) {
                if (!mController.requestRestart()) {
                    writeHttp(sock, 409, "Conflict", "application/json", "{\"error\":\"busy\"}\n");
                } else {
                    writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"restart\"}\n");
                }
            } else if ("POST".equals(method) && "/import".equals(path)) {
                int rc = mController.requestImportStaging();
                if (rc == 409) {
                    writeHttp(sock, 409, "Conflict", "application/json", "{\"error\":\"busy\"}\n");
                } else if (rc == 400) {
                    writeHttp(sock, 400, "Bad Request", "application/json", "{\"error\":\"staging_empty\"}\n");
                } else {
                    writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"import\"}\n");
                }
            } else if ("POST".equals(method) && "/reset".equals(path)) {
                int rc = mController.requestReset();
                if (rc == 409) {
                    writeHttp(sock, 409, "Conflict", "application/json", "{\"error\":\"busy\"}\n");
                } else if (rc == 400) {
                    writeHttp(sock, 400, "Bad Request", "application/json", "{\"error\":\"seed_missing\"}\n");
                } else {
                    writeHttp(sock, 202, "Accepted", "application/json", "{\"ok\":true,\"action\":\"reset\"}\n");
                }
            } else if ("POST".equals(method) && "/wakelock/on".equals(path)) {
                mController.setKeepAwake(true);
                writeHttp(sock, 200, "OK", "application/json", mController.getStatusJson());
            } else if ("POST".equals(method) && "/wakelock/off".equals(path)) {
                mController.setKeepAwake(false);
                writeHttp(sock, 200, "OK", "application/json", mController.getStatusJson());
            } else if ("POST".equals(method) && "/wakelock/toggle".equals(path)) {
                mController.toggleKeepAwake();
                writeHttp(sock, 200, "OK", "application/json", mController.getStatusJson());
            } else if ("POST".equals(method) && "/overlay/on".equals(path)) {
                mController.setShowOverlay(true);
                writeHttp(sock, 200, "OK", "application/json", mController.getStatusJson());
            } else if ("POST".equals(method) && "/overlay/off".equals(path)) {
                mController.setShowOverlay(false);
                writeHttp(sock, 200, "OK", "application/json", mController.getStatusJson());
            } else if ("POST".equals(method) && "/overlay/toggle".equals(path)) {
                mController.toggleShowOverlay();
                writeHttp(sock, 200, "OK", "application/json", mController.getStatusJson());
            } else {
                writeHttp(sock, 404, "Not Found", "application/json", "{\"error\":\"not_found\"}\n");
            }
        } catch (Throwable ignored) {}
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

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("Unexpected EOF");
            off += n;
        }
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
}
