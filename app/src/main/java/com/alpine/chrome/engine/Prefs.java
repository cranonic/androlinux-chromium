package com.alpine.chrome.engine;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App preferences — setup flags, display, input, VNC, performance.
 */
public final class Prefs {

    private static final String NAME = "alpine_chrome_prefs";
    private static SharedPreferences sp;

    /** fit | fill | remote */
    public static final String VIEW_FIT = "fit";
    public static final String VIEW_FILL = "fill";
    public static final String VIEW_REMOTE = "remote";

    public static void init(Context ctx) {
        if (sp == null) {
            sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    public static boolean isOnboardingDone() {
        return sp.getBoolean("onboarding_done", false);
    }

    public static void setOnboardingDone(boolean v) {
        sp.edit().putBoolean("onboarding_done", v).apply();
    }

    public static boolean isSetupDone() {
        return sp.getBoolean("setup_done", false);
    }

    public static void setSetupDone(boolean v) {
        sp.edit().putBoolean("setup_done", v).apply();
    }

    public static float getDisplayScale() {
        return sp.getFloat("display_scale", 1.0f);
    }

    public static void setDisplayScale(float v) {
        sp.edit().putFloat("display_scale", v).apply();
    }

    public static boolean isTouchMode() {
        return sp.getBoolean("touch_mode", true);
    }

    public static void setTouchMode(boolean v) {
        sp.edit().putBoolean("touch_mode", v).apply();
    }

    public static boolean isMouseSupport() {
        return sp.getBoolean("mouse_support", false);
    }

    public static void setMouseSupport(boolean v) {
        sp.edit().putBoolean("mouse_support", v).apply();
    }

    public static boolean isLogsFabEnabled() {
        return sp.getBoolean("logs_fab", true);
    }

    public static void setLogsFabEnabled(boolean v) {
        sp.edit().putBoolean("logs_fab", v).apply();
    }

    /** Show remote mouse pointer / dot cursor in VNC view. */
    public static boolean isShowPointer() {
        return sp.getBoolean("show_pointer", true);
    }

    public static void setShowPointer(boolean v) {
        sp.edit().putBoolean("show_pointer", v).apply();
    }

    /** Hide Android status bar (immersive) while browsing. */
    public static boolean isHideStatusBar() {
        return sp.getBoolean("hide_status_bar", true);
    }

    public static void setHideStatusBar(boolean v) {
        sp.edit().putBoolean("hide_status_bar", v).apply();
    }

    /** View mode: fit (letterbox), fill (crop), remote (resize guest to phone). */
    public static String getViewMode() {
        return sp.getString("view_mode", VIEW_FIT);
    }

    public static void setViewMode(String v) {
        sp.edit().putString("view_mode", v).apply();
    }

    /** Two-finger tap toggles soft keyboard. */
    public static boolean isTwoFingerKeyboard() {
        return sp.getBoolean("two_finger_kb", true);
    }

    public static void setTwoFingerKeyboard(boolean v) {
        sp.edit().putBoolean("two_finger_kb", v).apply();
    }

    /** Allow two-finger scroll on the VNC surface. */
    public static boolean isTwoFingerScroll() {
        return sp.getBoolean("two_finger_scroll", true);
    }

    public static void setTwoFingerScroll(boolean v) {
        sp.edit().putBoolean("two_finger_scroll", v).apply();
    }

    /** Virtual display width (Xvfb / Chromium window). */
    public static int getVncWidth() {
        return sp.getInt("vnc_width", 1280);
    }

    public static void setVncWidth(int v) {
        sp.edit().putInt("vnc_width", Math.max(320, Math.min(v, 2560))).apply();
    }

    public static int getVncHeight() {
        return sp.getInt("vnc_height", 720);
    }

    public static void setVncHeight(int v) {
        sp.edit().putInt("vnc_height", Math.max(320, Math.min(v, 2560))).apply();
    }

    /** JPEG quality for x11vnc when using tight (10–100). */
    public static int getVncQuality() {
        return sp.getInt("vnc_quality", 60);
    }

    public static void setVncQuality(int v) {
        sp.edit().putInt("vnc_quality", Math.max(10, Math.min(v, 100))).apply();
    }

    /** Prefer lower latency encodings / fewer caches. */
    public static boolean isPerformanceMode() {
        return sp.getBoolean("perf_mode", true);
    }

    public static void setPerformanceMode(boolean v) {
        sp.edit().putBoolean("perf_mode", v).apply();
    }

    /** Try hardware-ish GL path (may be unstable under proot). */
    public static boolean isTryGpu() {
        return sp.getBoolean("try_gpu", false);
    }

    public static void setTryGpu(boolean v) {
        sp.edit().putBoolean("try_gpu", v).apply();
    }

    /** SD card bind already on; remember last open-files request. */
    public static boolean isSdCardEnabled() {
        return sp.getBoolean("sdcard_enabled", true);
    }

    public static void setSdCardEnabled(boolean v) {
        sp.edit().putBoolean("sdcard_enabled", v).apply();
    }

    /** Pending URL to open in Chromium on next session start (file:///sdcard etc). */
    public static String getPendingOpenUrl() {
        return sp.getString("pending_open_url", "");
    }

    public static void setPendingOpenUrl(String url) {
        sp.edit().putString("pending_open_url", url != null ? url : "").apply();
    }

    public static void clearSetup() {
        sp.edit().putBoolean("setup_done", false).apply();
    }

    /** Write guest-side config read by ac-start-browser. */
    public static String buildDisplayConf() {
        return "WIDTH=" + getVncWidth() + "\n"
                + "HEIGHT=" + getVncHeight() + "\n"
                + "QUALITY=" + getVncQuality() + "\n"
                + "PERF=" + (isPerformanceMode() ? "1" : "0") + "\n"
                + "TRY_GPU=" + (isTryGpu() ? "1" : "0") + "\n"
                + "OPEN_URL=" + getPendingOpenUrl().replace("\n", "") + "\n";
    }
}
