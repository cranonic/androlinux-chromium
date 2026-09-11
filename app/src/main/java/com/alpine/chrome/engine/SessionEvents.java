package com.alpine.chrome.engine;

import java.util.concurrent.CopyOnWriteArrayList;

/** Simple process-local events between ChromeSessionService and MainActivity. */
public final class SessionEvents {

    public interface Listener {
        void onStatus(String status);
        void onReady(int wsPort);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private SessionEvents() {}

    public static void addListener(Listener l) {
        if (l != null) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static void fireStatus(String status) {
        for (Listener l : LISTENERS) {
            try {
                l.onStatus(status);
            } catch (Exception ignored) {
            }
        }
    }

    public static void fireReady(int wsPort) {
        for (Listener l : LISTENERS) {
            try {
                l.onReady(wsPort);
            } catch (Exception ignored) {
            }
        }
    }
}
