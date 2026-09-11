package com.alpine.chrome.engine;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.alpine.chrome.ChromeApplication;
import com.alpine.chrome.R;
import com.alpine.chrome.ui.MainActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

/**
 * Short-lived foreground service while Chromium preview is active.
 * Starts guest Xvfb/x11vnc/Chromium + local WebSocket→VNC bridge for WebView.
 */
public class ChromeSessionService extends Service {

    private static final String TAG = "ChromeSessionService";
    public static final String ACTION_START = "com.alpine.chrome.action.START_SESSION";
    public static final String ACTION_STOP = "com.alpine.chrome.action.STOP_SESSION";
    public static final String ACTION_SESSION_READY = "com.alpine.chrome.action.SESSION_READY";
    public static final String ACTION_SESSION_STATUS = "com.alpine.chrome.action.SESSION_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_WS_PORT = "ws_port";

    public static final int VNC_PORT = 5901;
    public static final int WS_PORT = 6080;

    private static final int NOTIF_ID = 42;

    private Process chromiumProcess;
    private Thread readerThread;
    private WsTcpBridge bridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean stopping;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification());
        startChromiumProcess();
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Builder(this, ChromeApplication.CHANNEL_SESSION)
                .setContentTitle(getString(R.string.notif_session_title))
                .setContentText(getString(R.string.notif_session_text))
                .setSmallIcon(R.drawable.ic_chromium_logo)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void broadcastStatus(String status) {
        SessionLog.i(TAG, status);
        SessionEvents.fireStatus(status);
    }

    private void startChromiumProcess() {
        if (chromiumProcess != null) return;
        stopping = false;
        broadcastStatus("Starting Chromium session…");

        new Thread(() -> {
            try {
                // Bridge first so it is ready when VNC comes up
                if (bridge == null) {
                    bridge = new WsTcpBridge(WS_PORT, VNC_PORT);
                    bridge.start();
                }

                RootfsManager mgr = new RootfsManager(this);
                // Refresh launch script (fixes Xvfb backgrounding etc. after updates)
                try {
                    new ChromiumInstaller(mgr).ensureLaunchHelper();
                } catch (Exception e) {
                    SessionLog.e(TAG, "ensureLaunchHelper: " + e.getMessage());
                }
                ProotCommandBuilder proot = new ProotCommandBuilder(mgr);

                // Preflight: log what exists inside the guest
                try {
                    String[] checkCmd = proot.buildShellCommand(
                            "echo PREFLIGHT; "
                                    + "command -v Xvfb; command -v x11vnc; "
                                    + "command -v chromium-browser || command -v chromium; "
                                    + "ls -la /usr/local/bin/ac-start-browser 2>/dev/null; "
                                    + "test -f /etc/alpine-release && cat /etc/alpine-release; "
                                    + "true"
                    );
                    ProcessBuilder cpb = new ProcessBuilder(checkCmd);
                    cpb.redirectErrorStream(true);
                    cpb.environment().putAll(proot.buildEnv());
                    Process cp = cpb.start();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(cp.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            SessionLog.append("[preflight] " + line);
                        }
                    }
                    cp.waitFor();
                } catch (Exception e) {
                    SessionLog.e(TAG, "preflight: " + e.getMessage());
                }

                String[] cmd = proot.buildShellCommand("/usr/local/bin/ac-start-browser");
                SessionLog.i(TAG, "exec: " + String.join(" ", cmd));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.putAll(proot.buildEnv());
                chromiumProcess = pb.start();
                broadcastStatus("Guest process started, waiting for display…");

                readerThread = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(chromiumProcess.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            Log.d(TAG, line);
                            SessionLog.append(line);
                        }
                    } catch (Exception e) {
                        SessionLog.e(TAG, "reader: " + e.getMessage());
                    }
                    try {
                        int code = chromiumProcess.waitFor();
                        SessionLog.i(TAG, "guest process exited code=" + code);
                    } catch (Exception e) {
                        SessionLog.e(TAG, "waitFor: " + e.getMessage());
                    }
                }, "chromium-log");
                readerThread.setDaemon(true);
                readerThread.start();

                // Poll VNC port
                boolean ready = false;
                for (int i = 0; i < 60 && !stopping; i++) {
                    if (isPortOpen("127.0.0.1", VNC_PORT, 300)) {
                        ready = true;
                        break;
                    }
                    if (i % 5 == 0) {
                        broadcastStatus("Waiting for display bridge… (" + (i + 1) + "s)");
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                if (ready) {
                    broadcastStatus("Display ready — connecting preview");
                    SessionEvents.fireReady(WS_PORT);
                } else {
                    broadcastStatus("Display not ready — check Logs for details");
                    SessionLog.e(TAG, "VNC port " + VNC_PORT + " never opened");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start Chromium session", e);
                SessionLog.e(TAG, "start failed: " + e.getMessage());
                broadcastStatus("Session failed: " + e.getMessage());
                stopSelf();
            }
        }, "session-start").start();
    }

    private static boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void stopSession() {
        stopping = true;
        broadcastStatus("Stopping session…");
        if (bridge != null) {
            bridge.stop();
            bridge = null;
        }
        if (chromiumProcess != null) {
            chromiumProcess.destroy();
            try {
                chromiumProcess.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            chromiumProcess = null;
        }
        try {
            RootfsManager mgr = new RootfsManager(this);
            ProotCommandBuilder proot = new ProotCommandBuilder(mgr);
            String[] cmd = proot.buildShellCommand(
                    "killall -q chromium-browser 2>/dev/null; "
                            + "killall -q chromium 2>/dev/null; "
                            + "killall -q x11vnc 2>/dev/null; "
                            + "killall -q Xvfb 2>/dev/null; true"
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(proot.buildEnv());
            Process p = pb.start();
            p.waitFor();
        } catch (Exception ignored) {
        }
        SessionLog.i(TAG, "Session stopped");
    }

    @Override
    public void onDestroy() {
        stopSession();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
