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
        File script = new File(bin, "ac-start-browser");
        File legacy = new File(bin, "ac-start-chromium");

        String body = ""
                + "#!/bin/sh\n"
                + "# Defaults; overridden by /root/.ac_display.conf written from Android prefs\n"
                + "WIDTH=1280\n"
                + "HEIGHT=720\n"
                + "QUALITY=60\n"
                + "PERF=1\n"
                + "TRY_GPU=0\n"
                + "OPEN_URL=\n"
                + "if [ -f /root/.ac_display.conf ]; then\n"
                + "  . /root/.ac_display.conf\n"
                + "fi\n"
                + "export DISPLAY=:1\n"
                + "export HOME=/root\n"
                + "export XDG_RUNTIME_DIR=/tmp\n"
                + "export TMPDIR=/tmp\n"
                + "export LIBGL_ALWAYS_SOFTWARE=1\n"
                + "export GALLIUM_DRIVER=llvmpipe\n"
                + "export MESA_GL_VERSION_OVERRIDE=3.3\n"
                + "mkdir -p /tmp /tmp/.X11-unix /root/.config /run/dbus\n"
                + "chmod 1777 /tmp /tmp/.X11-unix 2>/dev/null || true\n"
                + "\n"
                + "echo \"[ac] display ${WIDTH}x${HEIGHT} quality=$QUALITY perf=$PERF gpu=$TRY_GPU\"\n"
                + "(killall Xvfb 2>/dev/null || true)\n"
                + "(killall x11vnc 2>/dev/null || true)\n"
                + "(killall chromium-browser 2>/dev/null || true)\n"
                + "(killall chromium 2>/dev/null || true)\n"
                + "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 /tmp/x11vnc.log 2>/dev/null || true\n"
                + "\n"
                + "command -v Xvfb >/dev/null 2>&1 || { echo '[ac] ERROR: Xvfb not installed'; exit 1; }\n"
                + "command -v x11vnc >/dev/null 2>&1 || { echo '[ac] ERROR: x11vnc not installed'; exit 1; }\n"
                + "\n"
                + "echo \"[ac] starting Xvfb :1 ${WIDTH}x${HEIGHT}\"\n"
                + "Xvfb :1 -screen 0 ${WIDTH}x${HEIGHT}x24 -ac -nolisten tcp &\n"
                + "XVFB_PID=$!\n"
                + "sleep 1\n"
                + "if ! kill -0 \"$XVFB_PID\" 2>/dev/null; then\n"
                + "  echo '[ac] ERROR: Xvfb failed to start'\n"
                + "  exit 1\n"
                + "fi\n"
                + "\n"
                + "VNC_EXTRA=\"-noshm -noxdamage -xkb\"\n"
                + "if [ \"$PERF\" = \"1\" ]; then\n"
                + "  VNC_EXTRA=\"$VNC_EXTRA -ncache 0 -wait 1 -defer 1 -threads\"\n"
                + "else\n"
                + "  VNC_EXTRA=\"$VNC_EXTRA -ncache 2\"\n"
                + "fi\n"
                + "echo '[ac] starting x11vnc :5901'\n"
                + "x11vnc -display :1 -rfbport 5901 -localhost -forever -shared -nopw \\\n"
                + "  $VNC_EXTRA -quality \"$QUALITY\" \\\n"
                + "  > /tmp/x11vnc.log 2>&1 &\n"
                + "VNC_PID=$!\n"
                + "sleep 2\n"
                + "if [ -f /tmp/x11vnc.log ]; then echo '[ac] x11vnc log:'; tail -n 15 /tmp/x11vnc.log; fi\n"
                + "if ! kill -0 \"$VNC_PID\" 2>/dev/null; then\n"
                + "  echo '[ac] ERROR: x11vnc failed'\n"
                + "  exit 1\n"
                + "fi\n"
                + "\n"
                + "CHROME=$(command -v chromium-browser || command -v chromium || true)\n"
                + "if [ -z \"$CHROME\" ]; then\n"
                + "  echo '[ac] ERROR: chromium binary not found'\n"
                + "  exit 1\n"
                + "fi\n"
                + "START_URL=about:blank\n"
                + "if [ -n \"$OPEN_URL\" ]; then START_URL=\"$OPEN_URL\"; fi\n"
                + "echo \"[ac] launching $CHROME ${WIDTH}x${HEIGHT} -> $START_URL\"\n"
                + "GPU_FLAGS=\"--disable-gpu --disable-gpu-compositing --use-gl=disabled --disable-vulkan\"\n"
                + "if [ \"$TRY_GPU\" = \"1\" ]; then\n"
                + "  GPU_FLAGS=\"--ignore-gpu-blocklist --enable-features=UseSkiaRenderer\"\n"
                + "  unset LIBGL_ALWAYS_SOFTWARE\n"
                + "fi\n"
                + "exec \"$CHROME\" \\\n"
                + "  --no-sandbox \\\n"
                + "  --disable-setuid-sandbox \\\n"
                + "  --disable-dev-shm-usage \\\n"
                + "  --disable-gpu-sandbox \\\n"
                + "  --disable-seccomp-filter-sandbox \\\n"
                + "  --disable-namespace-sandbox \\\n"
                + "  $GPU_FLAGS \\\n"
                + "  --ozone-platform=x11 \\\n"
                + "  --in-process-gpu \\\n"
                + "  --single-process \\\n"
                + "  --user-data-dir=/root/.config/chromium \\\n"
                + "  --window-size=${WIDTH},${HEIGHT} \\\n"
                + "  --window-position=0,0 \\\n"
                + "  --start-maximized \\\n"
                + "  --no-first-run \\\n"
                + "  --no-default-browser-check \\\n"
                + "  --disable-dbus \\\n"
                + "  --disable-breakpad \\\n"
                + "  --disable-crash-reporter \\\n"
                + "  --disable-features=TranslateUI \\\n"
                + "  \"$START_URL\"\n";

        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(body.getBytes("UTF-8"));
        }
        //noinspection ResultOfMethodCallIgnored
        script.setExecutable(true, false);

        String wrapper = "#!/bin/sh\nexec /usr/local/bin/ac-start-browser \"$@\"\n";
        try (FileOutputStream out = new FileOutputStream(legacy)) {
            out.write(wrapper.getBytes("UTF-8"));
        }
        //noinspection ResultOfMethodCallIgnored
        legacy.setExecutable(true, false);
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
