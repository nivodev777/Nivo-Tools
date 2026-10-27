package com.nivodev.nivotools.util;

import android.app.Activity;
import android.content.Context;

/**
 * Runtime accent. NivoApp picks a random palette index on every cold start;
 * every Activity must call {@link #apply(Activity)} BEFORE setContentView so
 * the whole app (music, calculator, notes, alarm...) paints with the same
 * random accent instead of the fixed purple.
 */
public final class Accent {

    private static int accent = 0xFF6C5CE7;
    private static int index = 0;

    private Accent() {
    }

    /** Called once per process from NivoApp.onCreate. */
    public static void init(int color, int styleIndex) {
        accent = color;
        index = styleIndex;
    }

    /** Current random primary as a plain ARGB int. */
    public static int of(Context context) {
        return accent;
    }

    /** Applies the chosen accent overlay to an Activity theme. Idempotent. */
    public static void apply(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier(
                    "ThemeAccent" + index, "style", activity.getPackageName());
            if (id != 0) {
                activity.getTheme().applyStyle(id, true);
            }
        } catch (Throwable ignored) {
        }
    }
}
