package com.nivodev.nivotools.util;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;

/**
 * App-wide haptics engine (Nivo Haptic v1).
 *
 * <p>Contract:</p>
 * <ul>
 *     <li>{@code tap(View)} - tiny UI click, wired automatically by AnimUtils.pressScale.</li>
 *     <li>{@code action(Context, int)} - typed feedback (TICK/PRESS/CONFIRM/ERROR) for buttons,
 *     dialogs, toggles, tools. Every future tool should prefer this.</li>
     * <li>{@code pattern(Context, ...)} - custom one-shot/rhythm feedback, still respecting the user
 *     vibration preference.</li>
 *     <li>{@code *Direct(...)} variants - bypass the preference; reserved for alarms and other
 *     system-critical features that must always vibrate.</li>
 * </ul>
 *
 * <p>Safe on every device: no-op when haptics are disabled in settings, when the device has no
 * vibrator, or below API 26 for waveform amplitudes (falls back to plain duration patterns).</p>
 */
public final class Haptics {

    /**
     * Same-touch dedupe: pressScale already buzzes on ACTION_DOWN, so a
     * lighter follow-up buzz from the click handler a moment later reads
     * as a "double tap". Distinct reinforcements (CONFIRM/ERROR) stay
     * audible; lighter repeats inside the window are swallowed. Direct
     * alarm feedback never goes through this gate.
     */
    private static final long MERGE_WINDOW_MS = 350L;
    private static long lastBuzzAt = 0L;
    private static int lastBuzzWeight = -1;

    private static boolean allow(int type) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean distinct = type == TYPE_CONFIRM || type == TYPE_ERROR;
        if (now - lastBuzzAt < MERGE_WINDOW_MS && !distinct) {
            return false;
        }
        lastBuzzAt = now;
        lastBuzzWeight = type;
        return true;
    }


    /** Plain short click - default for row taps. */
    public static final int TYPE_TAP = 0;
    /** Lighter tick - subtle feedback (selects, chips). */
    public static final int TYPE_TICK = 1;
    /** Heavier press - primary actions (save, play, send). */
    public static final int TYPE_PRESS = 2;
    /** Positive double-pulse - success/confirm. */
    public static final int TYPE_CONFIRM = 3;
    /** Sharp triple-pulse - error/invalid input. */
    public static final int TYPE_ERROR = 4;

    private Haptics() {
    }

    /** Tiny click bound to a view (uses KEYBOARD_TAP feedback constant). */
    public static void tap(View view) {
        if (view == null || !Prefs.isHapticsEnabled(view.getContext())) {
            return;
        }
        if (!allow(TYPE_TAP)) {
            return;
        }
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /** Soft pulse (14ms) - building block kept for callers. */
    public static void buzz(Context context) {
        if (!contextHapticOk(context)) {
            return;
        }
        if (!allow(TYPE_TAP)) {
            return;
        }
        oneShot(context, 14L, 60);
    }

    /**
     * Typed feedback - the preferred engine entry point for buttons and cards.
     * Respects the vibration preference.
     */
    public static void action(Context context, int type) {
        if (!contextHapticOk(context)) {
            return;
        }
        if (!allow(type)) {
            return;
        }
        switch (type) {
            case TYPE_TICK:
                oneShot(context, 8L, 45);
                break;
            case TYPE_PRESS:
                oneShot(context, 16L, 90);
                break;
            case TYPE_CONFIRM:
                pattern(context, new long[]{0L, 16L, 60L, 16L},
                        new int[]{90, 0, 90, 0}, -1);
                break;
            case TYPE_ERROR:
                pattern(context, new long[]{0L, 18L, 50L, 18L, 50L, 18L},
                        new int[]{90, 0, 90, 0, 90, 0}, -1);
                break;
            case TYPE_TAP:
            default:
                oneShot(context, 12L, 64);
                break;
        }
    }

    /** Convenience view variant of {@link #action(Context, int)}. */
    public static void action(View view, int type) {
        if (view != null) {
            action(view.getContext(), type);
        }
    }

    /** Custom length/amplitude pulse honoring the user preference. */
    public static void oneShot(Context context, long ms, int amplitude) {
        if (!contextHapticOk(context)) {
            return;
        }
        if (!allow(TYPE_TAP)) {
            return;
        }
        Vibrator v = vibrator(context);
        if (v == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(
                    ms > 0 ? ms : 1, clamp(amplitude)));
        } else {
            v.vibrate(ms > 0 ? ms : 1);
        }
    }

    /** Custom repeating timeline honoring the user preference.
     *  repeat = -1 plays once, otherwise the index the cycle restarts from. */
    public static void pattern(Context context, long[] timings, int[] amplitudes, int repeat) {
        if (!contextHapticOk(context)) {
            return;
        }
        if (!allow(TYPE_TAP)) {
            return;
        }
        Vibrator v = vibrator(context);
        if (v == null || timings == null || timings.length == 0) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(timings, clampArray(amplitudes, timings), repeat));
        } else {
            v.vibrate(timings, repeat);
        }
    }

    /** Always-vibrates pulse for system-critical features (alarm ringing etc). */
    public static void oneShotDirect(Context context, long ms, int amplitude) {
        if (context == null) {
            return;
        }
        Vibrator v = vibrator(context);
        if (v == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(ms > 0 ? ms : 1, clamp(amplitude)));
        } else {
            v.vibrate(ms > 0 ? ms : 1);
        }
    }

    /** Always-vibrates pattern for alarms; repeated until dismissed. */
    public static void patternDirect(Context context, long[] timings, int[] amplitudes, int repeat) {
        if (context == null) {
            return;
        }
        Vibrator v = vibrator(context);
        if (v == null || timings == null || timings.length == 0) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(timings, clampArray(amplitudes, timings), repeat));
        } else {
            v.vibrate(timings, repeat);
        }
    }

    /** Cancels any running vibration (used by the alarm dismissal). */
    public static void cancelAll(Context context) {
        if (context == null) {
            return;
        }
        Vibrator v = vibrator(context);
        if (v != null) {
            try {
                v.cancel();
            } catch (Throwable t) {
            }
        }
    }

    /** Null-safe preference/service check shared by guarded entry points. */
    private static boolean contextHapticOk(Context context) {
        return context != null && Prefs.isHapticsEnabled(context);
    }

    private static Vibrator vibrator(Context context) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                return null;
            }
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int clamp(int amplitude) {
        if (amplitude < 1) {
            return 1;
        }
        return amplitude > 255 ? 255 : amplitude;
    }

    private static int[] clampArray(int[] in, long[] timings) {
        int[] out = in != null && in.length == timings.length ? in
                : new int[timings.length];
        for (int i = 0; i < timings.length; i++) {
            out[i] = timings[i] == 0 ? 0 : clamp(in != null && in.length == timings.length
                    ? in[i] : 110);
        }
        return out;
    }
}
