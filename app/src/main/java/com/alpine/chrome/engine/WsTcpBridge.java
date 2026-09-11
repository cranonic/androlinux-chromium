package com.alpine.chrome.engine;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal WebSocket (RFC6455) → TCP bridge for noVNC → x11vnc.
 * Listens on 127.0.0.1:wsPort, forwards binary frames to 127.0.0.1:tcpPort.
 */
public class WsTcpBridge {

    private static final String TAG = "WsTcpBridge";

    private final int wsPort;
    private final int tcpPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread acceptThread;

    public WsTcpBridge(int wsPort, int tcpPort) {
        this.wsPort = wsPort;
        this.tcpPort = tcpPort;
    }

    public void start() throws IOException {
        if (running.getAndSet(true)) return;
        server = new ServerSocket(wsPort, 8, InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(this::acceptLoop, "ws-tcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        SessionLog.i(TAG, "WebSocket bridge listening on 127.0.0.1:" + wsPort + " → :" + tcpPort);
    }

    public void stop() {
        running.set(false);
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = server.accept();
                Thread t = new Thread(() -> handleClient(client), "ws-tcp-client");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running.get()) {
                    SessionLog.e(TAG, "accept: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handleClient(Socket wsSock) {
        Socket tcpSock = null;
        try {
            wsSock.setTcpNoDelay(true);
            InputStream wsIn = wsSock.getInputStream();
            OutputStream wsOut = wsSock.getOutputStream();

            if (!doHandshake(wsIn, wsOut)) {
                SessionLog.e(TAG, "WebSocket handshake failed");
                return;
            }

            // Wait briefly for VNC to be up
            for (int i = 0; i < 30; i++) {
                try {
                    tcpSock = new Socket();
                    tcpSock.connect(new java.net.InetSocketAddress("127.0.0.1", tcpPort), 500);
                    break;
                } catch (Exception e) {
                    tcpSock = null;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (tcpSock == null) {
                SessionLog.e(TAG, "Cannot connect to VNC on :" + tcpPort);
                return;
            }
            tcpSock.setTcpNoDelay(true);
            SessionLog.i(TAG, "Client connected, bridging to VNC :" + tcpPort);

            InputStream tcpIn = tcpSock.getInputStream();
            OutputStream tcpOut = tcpSock.getOutputStream();

            Socket finalTcp = tcpSock;
            Thread up = new Thread(() -> {
                try {
                    pipeWsToTcp(wsIn, tcpOut);
                } catch (Exception ignored) {
                } finally {
                    quietClose(finalTcp);
                    quietClose(wsSock);
                }
            }, "ws-to-tcp");
            up.setDaemon(true);
            up.start();

            pipeTcpToWs(tcpIn, wsOut);
        } catch (Exception e) {
            SessionLog.e(TAG, "bridge client: " + e.getMessage());
        } finally {
            quietClose(tcpSock);
            quietClose(wsSock);
        }
    }

    private boolean doHandshake(InputStream in, OutputStream out) throws Exception {
        // Read HTTP headers
        StringBuilder req = new StringBuilder();
        int state = 0; // look for \r\n\r\n
        while (state < 4) {
            int b = in.read();
            if (b < 0) return false;
            req.append((char) b);
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (req.length() > 8192) return false;
        }
        String headers = req.toString();
        String key = null;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.US).startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(':') + 1).trim();
                break;
            }
        }
        if (key == null) return false;

        String accept = wsAccept(key);
        String resp =
                "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n"
                        + "\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }

    private static String wsAccept(String key) throws Exception {
        String magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] dig = md.digest(magic.getBytes(StandardCharsets.UTF_8));
        return android.util.Base64.encodeToString(dig, android.util.Base64.NO_WRAP);
    }

    /** Client → server frames (masked) → raw TCP */
    private void pipeWsToTcp(InputStream wsIn, OutputStream tcpOut) throws IOException {
        while (running.get()) {
            int b0 = wsIn.read();
            if (b0 < 0) break;
            int b1 = wsIn.read();
            if (b1 < 0) break;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = ((wsIn.read() & 0xff) << 8) | (wsIn.read() & 0xff);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | (wsIn.read() & 0xff);
                }
            }
            byte[] mask = new byte[4];
            if (masked) {
                if (readFully(wsIn, mask) < 4) break;
            }
            if (len > 8 * 1024 * 1024) break; // safety
            byte[] payload = new byte[(int) len];
            if (len > 0 && readFully(wsIn, payload) < len) break;
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            if (opcode == 0x8) break; // close
            if (opcode == 0x9) {
                // ping → pong not strictly needed for binary RFB
                continue;
            }
            if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) {
                if (payload.length > 0) {
                    tcpOut.write(payload);
                    tcpOut.flush();
                }
            }
        }
    }

    /** Raw TCP → server frames (unmasked) → client */
    private void pipeTcpToWs(InputStream tcpIn, OutputStream wsOut) throws IOException {
        byte[] buf = new byte[32 * 1024];
        while (running.get()) {
            int n = tcpIn.read(buf);
            if (n < 0) break;
            if (n == 0) continue;
            writeWsFrame(wsOut, buf, n);
        }
    }

    private static void writeWsFrame(OutputStream out, byte[] data, int len) throws IOException {
        // Binary frame, FIN, unmasked (server→client)
        out.write(0x82);
        if (len < 126) {
            out.write(len);
        } else if (len < 65536) {
            out.write(126);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((len >> (8 * i)) & 0xff);
            }
        }
        out.write(data, 0, len);
        out.flush();
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return off;
            off += n;
        }
        return off;
    }

    private static void quietClose(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
