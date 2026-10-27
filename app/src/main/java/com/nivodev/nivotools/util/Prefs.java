package com.nivodev.nivotools.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.nivodev.nivotools.R;

public final class Prefs {

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private static final String FILE = "nivo_prefs";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_HAPTICS = "haptics_enabled";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int getThemeMode(Context context) {
        // brand default: follow the device (system) theme
        return prefs(context).getInt(KEY_THEME, THEME_SYSTEM);
    }

    public static void setThemeMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_THEME, mode).apply();
    }

    /** Glass blur is always on (no toggle). */
    public static boolean isBlurEnabled(Context context) {
        return true;
    }

    /** Animations respect the setting; zero system animator scale = off. */
    public static boolean areAnimationsEnabled(Context context) {
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            float scale = android.provider.Settings.Global.getFloat(
                    cr, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (scale == 0f) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Vibration feedback preference. */
    public static boolean isHapticsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HAPTICS, true);
    }

    public static void setHapticsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_HAPTICS, enabled).apply();
    }

    public static int nightModeFor(int themeMode) {
        if (themeMode == THEME_LIGHT) {
            return androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;
        }
        if (themeMode == THEME_DARK) {
            return androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;
        }
        return androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }
}
