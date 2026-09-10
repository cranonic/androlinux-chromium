package com.alpine.chrome.engine;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.alpine.chrome.ChromeApplication;
import com.alpine.chrome.R;
import com.alpine.chrome.ui.MainActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * Short-lived foreground service while Chromium preview is active.
 * Stops itself when the user leaves the app / session ends — does NOT run all day.
 */
public class ChromeSessionService extends Service {

    private static final String TAG = "ChromeSessionService";
    public static final String ACTION_START = "com.alpine.chrome.action.START_SESSION";
    public static final String ACTION_STOP = "com.alpine.chrome.action.STOP_SESSION";
    private static final int NOTIF_ID = 42;

    private Process chromiumProcess;
    private Thread readerThread;

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
        return START_NOT_STICKY; // do not restart if killed
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

    private void startChromiumProcess() {
        if (chromiumProcess != null) return;
        try {
            RootfsManager mgr = new RootfsManager(this);
            ProotCommandBuilder proot = new ProotCommandBuilder(mgr);
            String[] cmd = proot.buildShellCommand("/usr/local/bin/ac-start-chromium");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.putAll(proot.buildEnv());
            chromiumProcess = pb.start();
            readerThread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(chromiumProcess.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        Log.d(TAG, line);
                    }
                } catch (Exception ignored) {
                }
            }, "chromium-log");
            readerThread.setDaemon(true);
            readerThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Chromium session", e);
            stopSelf();
        }
    }

    private void stopSession() {
        if (chromiumProcess != null) {
            chromiumProcess.destroy();
            try {
                chromiumProcess.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            chromiumProcess = null;
        }
        // Best-effort kill leftover guest processes via a short proot call
        try {
            RootfsManager mgr = new RootfsManager(this);
            ProotCommandBuilder proot = new ProotCommandBuilder(mgr);
            String[] cmd = proot.buildShellCommand(
                    "pkill -f chromium 2>/dev/null; pkill -f x11vnc 2>/dev/null; pkill -f 'Xvfb :1' 2>/dev/null; true"
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(proot.buildEnv());
            Process p = pb.start();
            p.waitFor();
        } catch (Exception ignored) {
        }
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
