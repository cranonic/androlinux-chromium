package com.alpine.chrome.engine;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Lightweight prefs — onboarding done, install ready, display/touch settings.
 */
public final class Prefs {

    private static final String NAME = "alpine_chrome_prefs";
    private static SharedPreferences sp;

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

    /** Wipe setup flags after user resets environment. */
    public static void clearSetup() {
        sp.edit()
                .putBoolean("setup_done", false)
                .apply();
    }
}
