package com.alpine.chrome.engine;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lightweight rootfs manager (proot guest).
 *
 * Layout under filesDir:
 *   alpine_core/     ← rootfs (etc/alpine-release marks ready)
 *   home/            ← optional host-side home bind
 *   tmp/
 *
 * Prefer online download so APK stays small. Optional fallback asset:
 *   assets/alpine-aarch64.zip  (contains minirootfs-*.tar.gz)
 *   or assets/alpine-aarch64.tar.gz
 *
 * proot binary lives in nativeLibraryDir as libproot.so
 * (targetSdk > 28 cannot exec from writable filesDir).
 */
public class RootfsManager {

    private static final String TAG = "RootfsManager";

    /** Bump when extract layout changes. */
    private static final int ROOTFS_VERSION = 1;

    private static final String ROOTFS_AARCH64_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.1-aarch64.tar.gz";
    private static final String ROOTFS_X86_64_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.0-x86_64.tar.gz";

    private final Context context;
    private final String filesDir;
    private final String nativeLibDir;

    public interface ProgressListener {
        /** User-facing short status only (no internal logs). */
        void onStatus(String userMessage);
        /** 0..100 or -1 for indeterminate. */
        void onProgress(int percent);
        /** Real errors only — shown to user / developer. */
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
        return new File(getRootfsPath(), "etc/alpine-release").exists()
                && versionMarkerOk();
    }

    public boolean isChromiumInstalled() {
        return new File(getRootfsPath(), "root/.chromium_ready").exists()
                || new File(getRootfsPath(), "usr/bin/chromium-browser").exists()
                || new File(getRootfsPath(), "usr/bin/chromium").exists();
    }

    private boolean versionMarkerOk() {
        File marker = new File(getRootfsPath(), "etc/.ac_rootfs_ver");
        if (!marker.exists()) return true;
        try (FileInputStream in = new FileInputStream(marker)) {
            byte[] b = new byte[16];
            int n = in.read(b);
            if (n <= 0) return true;
            int v = Integer.parseInt(new String(b, 0, n).trim());
            return v >= ROOTFS_VERSION;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Ensures rootfs is present. Does NOT install Chromium packages
     * (that is ChromiumInstaller).
     *
     * Order: try online download first; if that fails, fall back to APK asset.
     */
    public void ensureAlpine(ProgressListener listener) throws IOException {
        if (isAlpineReady()) {
            listener.onStatus("Environment ready");
            listener.onProgress(100);
            return;
        }

        ensureDirs();
        String arch = alpineArch();
        String assetZip = "alpine-" + arch + ".zip";
        String assetTar = "alpine-" + arch + ".tar.gz";

        // 1) Prefer online download
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
            writeVersionMarker();
            if (!isAlpineReady()) {
                throw new IOException("Extract failed — environment incomplete");
            }
            listener.onProgress(100);
            return;
        } catch (IOException e) {
            downloadError = e;
            Log.w(TAG, "Online download failed, trying asset fallback", e);
            // clean partial extract so asset path is clean
            deleteRecursive(new File(getRootfsPath()));
            ensureDirs();
        }

        // 2) Fallback: APK asset
        if (assetExists(assetTar)) {
            listener.onStatus("Extracting environment…");
            listener.onProgress(10);
            extractFromAssetTar(assetTar);
            writeVersionMarker();
            if (!isAlpineReady()) {
                throw new IOException("Asset extract failed — environment incomplete");
            }
            listener.onProgress(100);
            return;
        }
        if (assetExists(assetZip)) {
            listener.onStatus("Extracting environment…");
            listener.onProgress(10);
            extractFromAssetZip(assetZip);
            writeVersionMarker();
            if (!isAlpineReady()) {
                throw new IOException("Asset extract failed — environment incomplete");
            }
            listener.onProgress(100);
            return;
        }

        // Both paths failed
        String msg = downloadError != null
                ? downloadError.getMessage()
                : "Download failed and no offline package found";
        throw new IOException(msg != null ? msg : "Setup failed");
    }

    /** Wipe rootfs so setup can run again. */
    public void resetEnvironment() {
        deleteRecursive(new File(getRootfsPath()));
        Prefs.clearSetup();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void ensureDirs() {
        new File(getRootfsPath()).mkdirs();
        new File(getTmpPath()).mkdirs();
        new File(filesDir, "home").mkdirs();
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

        // Zip may contain a single .tar.gz, or the asset may itself be a misnamed tar.gz
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
            // Not a valid zip — maybe the "zip" is actually the tar.gz renamed
            Log.w(TAG, "Asset not a zip, trying as gzip tar", zipEx);
        }
        //noinspection ResultOfMethodCallIgnored
        tmpZip.delete();

        if (!found) {
            // Re-copy asset and treat as direct tar.gz
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

    /** Fail fast with a clear message instead of cryptic "Not in GZIP format". */
    private void assertGzipMagic(File f) throws IOException {
        if (f == null || !f.exists() || f.length() < 2) {
            throw new IOException("Downloaded package is empty or missing");
        }
        try (InputStream in = new FileInputStream(f)) {
            int b0 = in.read();
            int b1 = in.read();
            // gzip magic: 1f 8b
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

    private void downloadFile(String urlStr, File dest, ProgressListener listener) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
        conn.connect();
        int code = conn.getResponseCode();
        // follow one more hop if needed
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
     * Extract gzip-compressed tar (minirootfs format).
     * Pure-Java ustar reader for common entries.
     */
    private void extractTarGz(String destDir, File tarGz) throws IOException {
        File dest = new File(destDir);
        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("Cannot create " + destDir);
        }
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

                File outFile = new File(dest, name);
                if (type == '5' || name.endsWith("/")) {
                    outFile.mkdirs();
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
                    long pad = (512 - (size % 512)) % 512;
                    skipFully(gin, pad);
                } else if (type == '2') {
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream out = new FileOutputStream(outFile)) {
                        // empty marker; proot resolves guest symlinks
                    }
                    long pad = (512 - (size % 512)) % 512;
                    skipFully(gin, pad);
                } else {
                    long skip = size + (512 - (size % 512)) % 512;
                    skipFully(gin, skip);
                }
            }
        } catch (java.util.zip.ZipException ze) {
            throw new IOException("Archive is not valid gzip: " + ze.getMessage(), ze);
        }
        Log.i(TAG, "Extract complete → " + destDir);
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
