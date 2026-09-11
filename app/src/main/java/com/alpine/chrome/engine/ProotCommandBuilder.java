package com.alpine.chrome.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds proot argv + env for guest commands.
 * proot binary MUST come from nativeLibraryDir (libproot.so).
 *
 * Flags aligned with the working mscode android reference.
 */
public class ProotCommandBuilder {

    private final String prootPath;
    private final String rootfsPath;
    private final String nativeLibDir;
    private final String filesDir;
    private final String tmpPath;

    public ProotCommandBuilder(RootfsManager mgr) {
        this.prootPath = mgr.getProotPath();
        this.rootfsPath = mgr.getRootfsPath();
        this.nativeLibDir = mgr.getNativeLibDir();
        this.filesDir = mgr.getFilesDir();
        File guestTmp = new File(this.rootfsPath, "tmp");
        this.tmpPath = guestTmp.exists() ? guestTmp.getAbsolutePath() : mgr.getTmpPath();
    }

    /** One-shot: proot … /bin/sh -c "command" */
    public String[] buildShellCommand(String shellCommand) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootPath);
        addCommonFlags(cmd);
        // Prefer absolute path so a broken relative lookup still works
        File sh = new File(rootfsPath, "bin/sh");
        if (sh.exists()) {
            cmd.add("/bin/sh");
        } else {
            File busy = new File(rootfsPath, "bin/busybox");
            if (busy.exists()) {
                cmd.add("/bin/busybox");
                cmd.add("sh");
            } else {
                cmd.add("sh");
            }
        }
        cmd.add("-c");
        cmd.add(shellCommand);
        return cmd.toArray(new String[0]);
    }

    public Map<String, String> buildEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        // filesDir first (libtalloc.so.2 copy) then nativeLibraryDir
        env.put("LD_LIBRARY_PATH", filesDir + ":" + nativeLibDir);
        env.put("PROOT_TMP_DIR", tmpPath);
        env.put("PROOT_LOADER", nativeLibDir + "/libproot-loader.so");
        File l32 = new File(nativeLibDir, "libproot-loader32.so");
        if (l32.exists()) {
            // Reference uses PROOT_LOADER32 (no underscore before 32)
            env.put("PROOT_LOADER32", l32.getAbsolutePath());
            env.put("PROOT_LOADER_32", l32.getAbsolutePath());
        }
        env.put("HOME", "/root");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("TMPDIR", "/tmp");
        return env;
    }

    private void addCommonFlags(List<String> cmd) {
        // Critical for apk / busybox symlink world inside minirootfs
        cmd.add("--link2symlink");
        cmd.add("--sysvipc");
        cmd.add("-L");              // ignore non-fatal mount errors
        cmd.add("--kill-on-exit");
        cmd.add("-0");              // fake root
        cmd.add("-r");
        cmd.add(rootfsPath);
        cmd.add("-w");
        cmd.add("/");

        // Android system partitions — needed for libproot-loader ELF resolution
        for (String mnt : new String[]{
                "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
                "/linkerconfig/ld.config.txt",
                "/linkerconfig/com.android.art/ld.config.txt",
                "/plat_property_contexts", "/property_contexts",
                "/storage"}) {
            if (new File(mnt).exists()) {
                cmd.add("-b");
                cmd.add(mnt);
            }
        }

        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add("/data");
        cmd.add("-b");
        cmd.add(filesDir + "/home:/root");
        cmd.add("-b");
        cmd.add("/dev/urandom:/dev/random");
        cmd.add("-b");
        cmd.add(tmpPath + ":/dev/shm");

        File resolv = new File("/etc/resolv.conf");
        if (resolv.exists()) {
            cmd.add("-b");
            cmd.add("/etc/resolv.conf:/etc/resolv.conf");
        }
        File sd = new File("/sdcard");
        if (sd.exists()) {
            cmd.add("-b");
            cmd.add("/sdcard");
        }
        if (new File("/storage").exists()) {
            cmd.add("-b");
            cmd.add("/storage");
        }
    }
}
