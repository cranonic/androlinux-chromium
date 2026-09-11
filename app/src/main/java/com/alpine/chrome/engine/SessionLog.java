package com.alpine.chrome.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory ring buffer for session / setup process output.
 * UI (LogsActivity) and service both use this.
 */
public final class SessionLog {

    private static final int MAX_LINES = 2000;
    private static final Object LOCK = new Object();
    private static final ArrayList<String> LINES = new ArrayList<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public interface Listener {
        void onLogLine(String line);
    }

    private SessionLog() {}

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }

    public static void append(String line) {
        if (line == null) return;
        String ts = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        String full = ts + "  " + line;
        synchronized (LOCK) {
            LINES.add(full);
            while (LINES.size() > MAX_LINES) {
                LINES.remove(0);
            }
        }
        for (Listener l : LISTENERS) {
            try {
                l.onLogLine(full);
            } catch (Exception ignored) {
            }
        }
    }

    public static void i(String tag, String msg) {
        append("[" + tag + "] " + msg);
    }

    public static void e(String tag, String msg) {
        append("[" + tag + "] ERROR: " + msg);
    }

    public static List<String> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    public static String dump() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            for (String s : LINES) {
                sb.append(s).append('\n');
            }
            return sb.toString();
        }
    }

    public static void addListener(Listener l) {
        if (l != null) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }
}
