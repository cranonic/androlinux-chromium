package com.alpine.chrome.engine;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lightweight rootfs manager (proot guest).
 *
 * Layout under filesDir:
 *   alpine_core/     ← rootfs (etc/alpine-release + bin/busybox mark ready)
 *   home/
 *   tmp/
 *
 * Prefer online download; optional asset fallback.
 * Extraction prefers system `tar` (preserves symlinks like /bin/sh → busybox).
 */
public class RootfsManager {

    private static final String TAG = "RootfsManager";

    /** Bump when extract layout / symlink handling changes (forces re-extract). */
    private static final int ROOTFS_VERSION = 2;

    private static final String ROOTFS_AARCH64_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz";
    private static final String ROOTFS_X86_64_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.0-x86_64.tar.gz";

    private final Context context;
    private final String filesDir;
    private final String nativeLibDir;

    public interface ProgressListener {
        void onStatus(String userMessage);
        void onProgress(int percent);
        void onError(String error);
    }

    public RootfsManager(Context context) {
        this.context = context.getApplicationContext();
        this.filesDir = context.getFilesDir().getAbsolutePath();
        this.nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
    }

    public String getFilesDir() { return filesDir; }
    public String getNativeLibDir() { return nativeLibDir; }

    public String getRootfsPath() {
        return filesDir + "/alpine_core";
    }

    public String getProotPath() {
        return nativeLibDir + "/libproot.so";
    }

    public String getProotLoaderPath() {
        return nativeLibDir + "/libproot-loader.so";
    }

    public String getTmpPath() {
        return filesDir + "/tmp";
    }

    public boolean isAlpineReady() {
        File root = new File(getRootfsPath());
        if (!new File(root, "etc/alpine-release").exists()) return false;
        if (!versionMarkerOk()) return false;
        // Must have a usable shell (busybox or real sh symlink)
        return new File(root, "bin/busybox").exists()
                || new File(root, "bin/sh").exists();
    }

    public boolean isChromiumInstalled() {
        return new File(getRootfsPath(), "root/.chromium_ready").exists()
                || new File(getRootfsPath(), "usr/bin/chromium-browser").exists()
                || new File(getRootfsPath(), "usr/bin/chromium").exists();
    }

    private boolean versionMarkerOk() {
        File marker = new File(getRootfsPath(), "etc/.ac_rootfs_ver");
        if (!marker.exists()) return false; // force re-extract when marker missing
        try (FileInputStream in = new FileInputStream(marker)) {
            byte[] b = new byte[16];
            int n = in.read(b);
            if (n <= 0) return false;
            int v = Integer.parseInt(new String(b, 0, n).trim());
            return v >= ROOTFS_VERSION;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ensures rootfs is present. Does NOT install Chromium packages.
     * Order: online download first; asset fallback.
     */
    public void ensureAlpine(ProgressListener listener) throws IOException {
        installTallocLibrary();
        ensureDirs();

        if (isAlpineReady()) {
            listener.onStatus("Environment ready");
            listener.onProgress(100);
            return;
        }

        // Wipe partial / old broken extract (empty symlink markers etc.)
        File root = new File(getRootfsPath());
        if (root.exists()) {
            Log.w(TAG, "Removing incomplete/old rootfs at " + root);
            deleteRecursive(root);
        }
        ensureDirs();

        String arch = alpineArch();
        String assetZip = "alpine-" + arch + ".zip";
        String assetTar = "alpine-" + arch + ".tar.gz";

        IOException downloadError = null;
        try {
            listener.onStatus("Downloading environment…");
            listener.onProgress(5);
            String url = "aarch64".equals(arch) ? ROOTFS_AARCH64_URL : ROOTFS_X86_64_URL;
            File tarGz = new File(filesDir, "rootfs-download.tar.gz");
            downloadFile(url, tarGz, listener);
            assertGzipMagic(tarGz);
            listener.onStatus("Extracting environment…");
            listener.onProgress(70);
            extractTarGz(getRootfsPath(), tarGz);
            //noinspection ResultOfMethodCallIgnored
            tarGz.delete();
            postExtractFixups();
            writeVersionMarker();
            if (!isAlpineReady()) {
                throw new IOException("Extract failed — shell not found in environment");
            }
            listener.onProgress(100);
            return;
        } catch (IOException e) {
            downloadError = e;
            Log.w(TAG, "Online download failed, trying asset fallback", e);
            deleteRecursive(new File(getRootfsPath()));
            ensureDirs();
        }

        if (assetExists(assetTar)) {
            listener.onStatus("Extracting environment…");
            listener.onProgress(10);
            extractFromAssetTar(assetTar);
            postExtractFixups();
            writeVersionMarker();
            if (!isAlpineReady()) {
                throw new IOException("Asset extract failed — shell not found");
            }
            listener.onProgress(100);
            return;
        }
        if (assetExists(assetZip)) {
            listener.onStatus("Extracting environment…");
            listener.onProgress(10);
            extractFromAssetZip(assetZip);
            postExtractFixups();
            writeVersionMarker();
            if (!isAlpineReady()) {
                throw new IOException("Asset extract failed — shell not found");
            }
            listener.onProgress(100);
            return;
        }

        String msg = downloadError != null
                ? downloadError.getMessage()
                : "Download failed and no offline package found";
        throw new IOException(msg != null ? msg : "Setup failed");
    }

    public void resetEnvironment() {
        deleteRecursive(new File(getRootfsPath()));
        Prefs.clearSetup();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void ensureDirs() {
        new File(getRootfsPath()).mkdirs();
        new File(getTmpPath()).mkdirs();
        new File(filesDir, "home").mkdirs();
        new File(getRootfsPath(), "tmp").mkdirs();
    }

    /**
     * Copy libtalloc into filesDir (mscode reference pattern) so
     * LD_LIBRARY_PATH=filesDir:nativeLibDir always finds it.
     */
    private void installTallocLibrary() throws IOException {
        File src = new File(nativeLibDir, "libtalloc.so.2");
        if (!src.exists()) src = new File(nativeLibDir, "libtalloc.so");
        if (!src.exists()) {
            Log.w(TAG, "libtalloc not found in nativeLibraryDir");
            return;
        }
        File dest = new File(filesDir, "libtalloc.so.2");
        if (dest.exists() && dest.length() == src.length()) return;
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        //noinspection ResultOfMethodCallIgnored
        dest.setReadable(true, false);
        Log.i(TAG, "Installed libtalloc.so.2 → " + dest);
    }

    private String alpineArch() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                if ("arm64-v8a".equals(abi)) return "aarch64";
                if ("x86_64".equals(abi)) return "x86_64";
                if ("armeabi-v7a".equals(abi)) return "armv7";
            }
        }
        return "aarch64";
    }

    private boolean assetExists(String name) {
        try (InputStream in = context.getAssets().open(name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void extractFromAssetTar(String assetName) throws IOException {
        File tarGz = new File(filesDir, "rootfs-asset.tar.gz");
        try (InputStream in = context.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(tarGz)) {
            copy(in, out);
        }
        assertGzipMagic(tarGz);
        extractTarGz(getRootfsPath(), tarGz);
        //noinspection ResultOfMethodCallIgnored
        tarGz.delete();
    }

    private void extractFromAssetZip(String assetName) throws IOException {
        File tmpZip = new File(filesDir, "rootfs-asset.zip");
        try (InputStream in = context.getAssets().open(assetName);
             OutputStream out = new FileOutputStream(tmpZip)) {
            copy(in, out);
        }

        File tarGz = new File(filesDir, "rootfs-from-zip.tar.gz");
        boolean found = false;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(tmpZip)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name != null && (name.endsWith(".tar.gz") || name.endsWith(".tgz"))) {
                    try (OutputStream out = new FileOutputStream(tarGz)) {
                        copy(zis, out);
                    }
                    found = true;
                    break;
                }
            }
        } catch (Exception zipEx) {
            Log.w(TAG, "Asset not a zip, trying as gzip tar", zipEx);
        }
        //noinspection ResultOfMethodCallIgnored
        tmpZip.delete();

        if (!found) {
            try (InputStream in = context.getAssets().open(assetName);
                 OutputStream out = new FileOutputStream(tarGz)) {
                copy(in, out);
            }
        }

        assertGzipMagic(tarGz);
        extractTarGz(getRootfsPath(), tarGz);
        //noinspection ResultOfMethodCallIgnored
        tarGz.delete();
    }

    private void assertGzipMagic(File f) throws IOException {
        if (f == null || !f.exists() || f.length() < 2) {
            throw new IOException("Downloaded package is empty or missing");
        }
        try (InputStream in = new FileInputStream(f)) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 != 0x1f || b1 != 0x8b) {
                throw new IOException(
                        "Package is not a valid gzip archive (got "
                                + String.format("%02x %02x", b0 & 0xff, b1 & 0xff)
                                + "). Check network or try again.");
            }
        }
    }

    private void writeVersionMarker() throws IOException {
        File marker = new File(getRootfsPath(), "etc/.ac_rootfs_ver");
        File parent = marker.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(String.valueOf(ROOTFS_VERSION).getBytes("UTF-8"));
        }
    }

    /**
     * After extract: ensure /bin/sh works and binaries are executable.
     * Alpine minirootfs ships busybox + many symlinks; broken extracts
     * leave empty files instead of links.
     */
    private void postExtractFixups() throws IOException {
        File root = new File(getRootfsPath());
        File busybox = new File(root, "bin/busybox");
        File sh = new File(root, "bin/sh");

        if (busybox.exists()) {
            //noinspection ResultOfMethodCallIgnored
            busybox.setExecutable(true, false);
        }

        // If /bin/sh is missing or is a zero-byte stub, recreate as symlink to busybox
        if (busybox.exists() && (!sh.exists() || (sh.isFile() && sh.length() == 0 && !Files.isSymbolicLink(sh.toPath())))) {
            //noinspection ResultOfMethodCallIgnored
            sh.delete();
            try {
                Files.createSymbolicLink(sh.toPath(), busybox.toPath().getFileName());
                Log.i(TAG, "Created /bin/sh → busybox symlink");
            } catch (Exception e) {
                // Fallback: copy busybox as sh
                try (InputStream in = new FileInputStream(busybox);
                     OutputStream out = new FileOutputStream(sh)) {
                    copy(in, out);
                }
                //noinspection ResultOfMethodCallIgnored
                sh.setExecutable(true, false);
                Log.w(TAG, "Symlink failed, copied busybox as sh: " + e.getMessage());
            }
        }

        // Common busybox applets Alpine expects under /bin
        if (busybox.exists()) {
            String[] applets = {
                    "sh", "ash", "ls", "cp", "mv", "rm", "mkdir", "cat", "echo",
                    "ln", "chmod", "chown", "grep", "sed", "awk", "ps", "kill",
                    "mount", "umount", "wget", "tar", "gzip", "gunzip"
            };
            File bin = new File(root, "bin");
            for (String name : applets) {
                File link = new File(bin, name);
                if (link.exists() && !(link.isFile() && link.length() == 0 && !Files.isSymbolicLink(link.toPath()))) {
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                link.delete();
                try {
                    Files.createSymbolicLink(link.toPath(), busybox.toPath().getFileName());
                } catch (Exception ignored) {
                    // non-fatal
                }
            }
        }

        // Make sure key dirs exist for apk
        new File(root, "tmp").mkdirs();
        new File(root, "var/cache/apk").mkdirs();
        new File(root, "etc/apk").mkdirs();

        Log.i(TAG, "postExtractFixups done; sh exists=" + sh.exists()
                + " busybox=" + busybox.exists());
    }

    private void downloadFile(String urlStr, File dest, ProgressListener listener) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
        conn.connect();
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == 307 || code == 308) {
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            if (loc == null || loc.isEmpty()) {
                throw new IOException("Redirect without Location");
            }
            conn = (HttpURLConnection) new URL(loc).openConnection();
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
            conn.connect();
            code = conn.getResponseCode();
        }
        if (code != 200) {
            conn.disconnect();
            throw new IOException("Download failed (HTTP " + code + ")");
        }
        long total = conn.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0 && listener != null) {
                    int pct = (int) Math.min(65, 5 + (read * 60 / total));
                    listener.onProgress(pct);
                }
            }
        } finally {
            conn.disconnect();
        }
        if (dest.length() < 1024) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw new IOException("Downloaded file too small — check connection");
        }
    }

    /**
     * Extract gzip tar. Prefer system `tar` (preserves symlinks).
     * Fall back to pure-Java ustar with real NIO symlinks.
     */
    private void extractTarGz(String destDir, File tarGz) throws IOException {
        File dest = new File(destDir);
        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("Cannot create " + destDir);
        }

        // 1) Prefer system tar (same approach as mscode reference)
        if (trySystemTar(tarGz, dest)) {
            Log.i(TAG, "Extract via system tar → " + destDir);
            return;
        }

        // 2) Pure-Java fallback with real symlink support
        Log.w(TAG, "system tar unavailable — using Java extractor");
        extractTarGzJava(destDir, tarGz);
        Log.i(TAG, "Java extract complete → " + destDir);
    }

    private boolean trySystemTar(File tarGz, File dest) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "tar", "-xzf", tarGz.getAbsolutePath(), "-C", dest.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            if (code == 0) {
                // quick sanity: alpine-release or busybox should exist
                if (new File(dest, "etc/alpine-release").exists()
                        || new File(dest, "bin/busybox").exists()) {
                    return true;
                }
                Log.w(TAG, "tar exited 0 but rootfs looks empty");
            } else {
                Log.w(TAG, "tar exit " + code + ": " + out);
            }
        } catch (Exception e) {
            Log.w(TAG, "system tar failed: " + e.getMessage());
        }
        return false;
    }

    private void extractTarGzJava(String destDir, File tarGz) throws IOException {
        File dest = new File(destDir);
        List<String[]> pendingSymlinks = new ArrayList<>();

        try (InputStream fin = new FileInputStream(tarGz);
             InputStream gin = new GZIPInputStream(new BufferedInputStream(fin))) {
            byte[] header = new byte[512];
            while (true) {
                int got = readFully(gin, header);
                if (got < 512) break;
                if (isZeroBlock(header)) break;

                String name = tarString(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                int type = header[156] & 0xFF;
                String prefix = tarString(header, 345, 155);
                if (prefix != null && !prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                if (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty() || name.contains("..")) {
                    long skip = size + (512 - (size % 512)) % 512;
                    skipFully(gin, skip);
                    continue;
                }

                File outFile = new File(dest, name);
                if (type == '5' || name.endsWith("/")) {
                    outFile.mkdirs();
                } else if (type == '2') {
                    // symlink — defer so targets exist
                    String target = tarString(header, 157, 100);
                    pendingSymlinks.add(new String[]{name, target});
                    long pad = (512 - (size % 512)) % 512;
                    skipFully(gin, pad);
                } else if (type == '0' || type == 0 || type == '7') {
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream out = new FileOutputStream(outFile)) {
                        long remaining = size;
                        byte[] buf = new byte[64 * 1024];
                        while (remaining > 0) {
                            int n = gin.read(buf, 0, (int) Math.min(buf.length, remaining));
                            if (n < 0) throw new IOException("Unexpected EOF in archive");
                            out.write(buf, 0, n);
                            remaining -= n;
                        }
                    }
                    // Make bin/* and *.so executable
                    if (name.startsWith("bin/") || name.startsWith("sbin/")
                            || name.startsWith("usr/bin/") || name.startsWith("usr/sbin/")
                            || name.endsWith(".so") || name.contains("/lib/")) {
                        //noinspection ResultOfMethodCallIgnored
                        outFile.setExecutable(true, false);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    outFile.setReadable(true, false);
                    long pad = (512 - (size % 512)) % 512;
                    skipFully(gin, pad);
                } else {
                    long skip = size + (512 - (size % 512)) % 512;
                    skipFully(gin, skip);
                }
            }
        }

        // Create real symlinks (NIO works in app-private storage on Android)
        int links = 0;
        for (String[] pair : pendingSymlinks) {
            String linkName = pair[0];
            String target = pair[1];
            File linkFile = new File(dest, linkName);
            File parent = linkFile.getParentFile();
            if (parent != null) parent.mkdirs();
            if (linkFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                linkFile.delete();
            }
            try {
                Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get(target));
                links++;
            } catch (Exception e) {
                // Fallback: if target is a relative file that exists, copy it
                File targetFile = new File(parent != null ? parent : dest, target);
                if (!targetFile.isFile()) {
                    targetFile = new File(dest, target);
                }
                if (targetFile.isFile()) {
                    try (InputStream in = new FileInputStream(targetFile);
                         OutputStream out = new FileOutputStream(linkFile)) {
                        copy(in, out);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    linkFile.setExecutable(true, false);
                    links++;
                    Log.w(TAG, "Symlink fallback copy: " + linkName + " → " + target);
                } else {
                    Log.w(TAG, "Failed symlink " + linkName + " → " + target + ": " + e.getMessage());
                }
            }
        }
        Log.i(TAG, "Java extract: " + links + " symlinks created");
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

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) return;
                n--;
            } else {
                n -= s;
            }
        }
    }

    private static boolean isZeroBlock(byte[] h) {
        for (byte b : h) if (b != 0) return false;
        return true;
    }

    private static String tarString(byte[] h, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && h[end] != 0) end++;
        return new String(h, off, end - off);
    }

    private static long parseOctal(byte[] h, int off, int len) {
        long v = 0;
        int end = off + len;
        int i = off;
        while (i < end && (h[i] == ' ' || h[i] == 0)) i++;
        while (i < end) {
            byte c = h[i];
            if (c == 0 || c == ' ') break;
            v = (v << 3) + (c - '0');
            i++;
        }
        return v;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
