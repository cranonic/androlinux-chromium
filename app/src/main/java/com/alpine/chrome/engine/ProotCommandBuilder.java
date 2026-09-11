package com.alpine.chrome.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds proot argv + env for Alpine guest commands.
 * proot binary MUST come from nativeLibraryDir (libproot.so).
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
        File alpineTmp = new File(this.rootfsPath, "tmp");
        this.tmpPath = alpineTmp.exists() ? alpineTmp.getAbsolutePath() : mgr.getTmpPath();
    }

    /** One-shot: proot … sh -c "command" */
    public String[] buildShellCommand(String shellCommand) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootPath);
        addCommonFlags(cmd);
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(shellCommand);
        return cmd.toArray(new String[0]);
    }

    public Map<String, String> buildEnv() {
        Map<String, String> env = new HashMap<>();
        // Required so the dynamic linker finds libtalloc.so next to libproot.so
        // (both live under nativeLibraryDir from jniLibs).
        String existingLd = System.getenv("LD_LIBRARY_PATH");
        if (existingLd != null && !existingLd.isEmpty()) {
            env.put("LD_LIBRARY_PATH", nativeLibDir + ":" + existingLd);
        } else {
            env.put("LD_LIBRARY_PATH", nativeLibDir);
        }
        env.put("PROOT_TMP_DIR", tmpPath);
        env.put("PROOT_LOADER", nativeLibDir + "/libproot-loader.so");
        File l32 = new File(nativeLibDir, "libproot-loader32.so");
        if (l32.exists()) {
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
        cmd.add("--kill-on-exit");
        cmd.add("-0"); // fake root
        cmd.add("-r");
        cmd.add(rootfsPath);
        // Essential Android binds for linker / DNS / time
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(filesDir + "/home:/root");
        // DNS
        File resolv = new File("/etc/resolv.conf");
        if (resolv.exists()) {
            cmd.add("-b");
            cmd.add("/etc/resolv.conf:/etc/resolv.conf");
        }
        // Optional SD card (when permission granted)
        File sd = new File("/sdcard");
        if (sd.exists()) {
            cmd.add("-b");
            cmd.add("/sdcard:/sdcard");
        }
    }
}
