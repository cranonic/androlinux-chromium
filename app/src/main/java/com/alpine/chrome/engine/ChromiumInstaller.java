package com.alpine.chrome.engine;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Chromium install inside Alpine via apk.
 *
 * Packages kept intentionally small:
 *   chromium          — browser
 *   mesa-egl mesa-gl  — software GL (needed on many devices)
 *   font-dejavu       — readable fonts
 *   ttf-freefont      — fallback
 *   dbus              — chromium often expects a session bus
 *   xvfb              — virtual display for headless preview pipeline
 *   x11vnc            — VNC server for Android WebView/noVNC client
 *
 * No desktop meta-packages, no office, no extra browsers.
 */
public class ChromiumInstaller {

    private static final String TAG = "ChromiumInstaller";

    private final RootfsManager rootfs;
    private final ProotCommandBuilder proot;

    public ChromiumInstaller(RootfsManager rootfs) {
        this.rootfs = rootfs;
        this.proot = new ProotCommandBuilder(rootfs);
    }

    public void install(RootfsManager.ProgressListener listener) throws IOException, InterruptedException {
        if (rootfs.isChromiumInstalled()) {
            listener.onStatus("Chromium already installed");
            listener.onProgress(100);
            return;
        }
        if (!rootfs.isAlpineReady()) {
            throw new IOException("Environment not ready");
        }

        ensureDns();
        listener.onStatus("Updating package index…");
        listener.onProgress(10);
        runChecked("apk update -q", 180);

        listener.onStatus("Installing Chromium (minimal)…");
        listener.onProgress(30);
        // Single apk add keeps dependency resolution consistent
        runChecked(
                "apk add -q --no-cache "
                        + "chromium "
                        + "mesa-egl mesa-gl "
                        + "font-dejavu ttf-freefont "
                        + "dbus "
                        + "xvfb x11vnc "
                        + "|| apk add -q --no-cache chromium dbus xvfb x11vnc",
                600
        );

        listener.onProgress(85);
        listener.onStatus("Finalizing…");
        writeReadyMarker();
        writeLaunchHelper();
        listener.onProgress(100);
        listener.onStatus("Ready — launching preview");
    }

    private void ensureDns() throws IOException {
        File resolv = new File(rootfs.getRootfsPath(), "etc/resolv.conf");
        if (!resolv.exists() || resolv.length() == 0) {
            File parent = resolv.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream out = new FileOutputStream(resolv)) {
                out.write("nameserver 8.8.8.8\nnameserver 1.1.1.1\n".getBytes("UTF-8"));
            }
        }
    }

    private void writeReadyMarker() throws IOException {
        File marker = new File(rootfs.getRootfsPath(), "root/.chromium_ready");
        File parent = marker.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write("ok\n".getBytes("UTF-8"));
        }
    }

    /**
     * Guest-side helper: starts Xvfb + x11vnc + chromium with touch-friendly flags.
     * Display :1, VNC on 5901. Android side connects via noVNC/WebView.
     */
    /** Public so session start can refresh the helper after code updates. */
    public void ensureLaunchHelper() throws IOException {
        writeLaunchHelper();
    }

    private void writeLaunchHelper() throws IOException {
        File bin = new File(rootfs.getRootfsPath(), "usr/local/bin");
        bin.mkdirs();
        File script = new File(bin, "ac-start-chromium");
        String body =
                "#!/bin/sh\n"
                        + "export DISPLAY=:1\n"
                        + "export HOME=/root\n"
                        + "export XDG_RUNTIME_DIR=/tmp\n"
                        + "pkill -f 'Xvfb :1' 2>/dev/null || true\n"
                        + "pkill -f 'x11vnc' 2>/dev/null || true\n"
                        + "pkill -f chromium 2>/dev/null || true\n"
                        + "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true\n"
                        + "echo '[ac] starting Xvfb'\n"
                        + "Xvfb :1 -screen 0 1280x720x24 -ac &\n"
                        + "sleep 1\n"
                        + "echo '[ac] starting x11vnc on 5901'\n"
                        + "x11vnc -display :1 -rfbport 5901 -localhost -forever -shared -nopw -xkb -ncache 0 &\n"
                        + "sleep 1\n"
                        + "CHROME=$(command -v chromium-browser || command -v chromium)\n"
                        + "if [ -z \"$CHROME\" ]; then echo '[ac] chromium not found'; exit 1; fi\n"
                        + "echo \"[ac] launching $CHROME\"\n"
                        + "exec \"$CHROME\" \\\n"
                        + "  --no-sandbox \\\n"
                        + "  --disable-dev-shm-usage \\\n"
                        + "  --disable-gpu \\\n"
                        + "  --use-gl=swiftshader \\\n"
                        + "  --user-data-dir=/root/.config/chromium \\\n"
                        + "  --window-size=1280,720 \\\n"
                        + "  --start-maximized \\\n"
                        + "  --disable-features=TranslateUI \\\n"
                        + "  --no-first-run \\\n"
                        + "  about:blank\n";
        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(body.getBytes("UTF-8"));
        }
        //noinspection ResultOfMethodCallIgnored
        script.setExecutable(true, false);
    }

    private void runChecked(String shellCommand, int timeoutSec)
            throws IOException, InterruptedException {
        String[] cmd = proot.buildShellCommand(shellCommand);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.putAll(proot.buildEnv());
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
                // Do not stream internal apk noise to UI
                Log.d(TAG, line);
            }
        }
        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Command timed out: " + shellCommand);
        }
        int code = p.exitValue();
        if (code != 0) {
            String snippet = out.length() > 400 ? out.substring(out.length() - 400) : out.toString();
            throw new IOException("apk/command failed (exit " + code + "): " + snippet.trim());
        }
    }
}
