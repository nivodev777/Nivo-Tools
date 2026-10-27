package com.nivodev.nivotools.ui.engine;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.content.Context;

import androidx.annotation.Nullable;

import com.nivodev.nivotools.util.BlurUtils;
import com.nivodev.nivotools.util.Prefs;

/**
 * Core of the UI engine: owns the liquid backdrop and hands every registered
 * GlassPanel a small, throttled, blurred snapshot of it.
 *
 * Optimised for low-end devices: ping-pong buffers mean the snapshot never
 * allocates while frames repeat, and weak devices get a cheaper blur pipeline.
 */
public final class GlassEngine {

    private static final GlassEngine INSTANCE = new GlassEngine();

    private static final float DOWNSAMPLE = 8f;
    private static final float DOWNSAMPLE_LOW = 12f;
    private static final int BLUR_RADIUS = 12;
    private static final int BLUR_RADIUS_LOW = 8;
    private static final long SNAPSHOT_EVERY_MS = 90L;
    private static final long SNAPSHOT_EVERY_MS_LOW = 180L;

    private static Boolean lowPower;

    private LiquidBackdropView backdrop;
    private final java.util.List<GlassPanel> panels = new java.util.ArrayList<>();
    /** Two rotating snapshot buffers - panels always see a ready instance. */
    private final Bitmap[] buffers = new Bitmap[2];
    private int bufferIndex = 0;
    private int bufW = -1;
    private int bufH = -1;
    private long lastSnapshotMs = 0L;

    private GlassEngine() {
    }

    public static GlassEngine get() {
        return INSTANCE;
    }

    /** True on weak devices: the whole glass pipeline runs in low-power mode. */
    public static boolean isLowPower(Context c) {
        Boolean v = lowPower;
        if (v == null) {
            boolean low = false;
            try {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        c.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    low = am.isLowRamDevice() || am.getMemoryClass() <= 128;
                }
            } catch (Throwable t) {
                low = false;
            }
            lowPower = v = low;
        }
        return v;
    }

    private boolean low(Context c) {
        return isLowPower(c);
    }

    public void onBackdropAttached(LiquidBackdropView view) {
        backdrop = view;
        refreshAll();
    }

    public void onBackdropDetached(LiquidBackdropView view) {
        if (backdrop == view) {
            backdrop = null;
            buffers[0] = null;
            buffers[1] = null;
            bufW = -1;
            bufH = -1;
        }
    }

    public void onBackdropFrame(LiquidBackdropView view) {
        if (backdrop != view) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        long every = low(backdrop.getContext()) ? SNAPSHOT_EVERY_MS_LOW : SNAPSHOT_EVERY_MS;
        if (bufW < 0 || now - lastSnapshotMs >= every) {
            Bitmap fresh = makeSnapshot();
            if (fresh != null) {
                lastSnapshotMs = now;
                for (int i = 0; i < panels.size(); i++) {
                    panels.get(i).setBlurredBackdrop(fresh);
                }
            }
        }
    }

    @Nullable
    private Bitmap makeSnapshot() {
        if (backdrop == null) {
            return null;
        }
        int w = backdrop.getWidth();
        int h = backdrop.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        if (!Prefs.isBlurEnabled(backdrop.getContext())) {
            return null;
        }
        boolean low = low(backdrop.getContext());
        int dw = Math.max(1, (int) (w / (low ? DOWNSAMPLE_LOW : DOWNSAMPLE)));
        int dh = Math.max(1, (int) (h / (low ? DOWNSAMPLE_LOW : DOWNSAMPLE)));
        if (dw != bufW || dh != bufH || buffers[0] == null || buffers[1] == null) {
            try {
                buffers[0] = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
                buffers[1] = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                buffers[0] = null;
                buffers[1] = null;
                return null;
            }
            bufW = dw;
            bufH = dh;
        }
        Bitmap target = buffers[bufferIndex];
        Canvas canvas = new Canvas(target);
        canvas.scale((float) dw / w, (float) dh / h);
        backdrop.drawScene(canvas, w, h);
        BlurUtils.boxBlur(target, low ? BLUR_RADIUS_LOW : BLUR_RADIUS);
        bufferIndex = 1 - bufferIndex;
        return target;
    }

    public void register(GlassPanel panel) {
        if (!panels.contains(panel)) {
            panels.add(panel);
        }
        refreshPanel(panel);
    }

    public void unregister(GlassPanel panel) {
        panels.remove(panel);
    }

    public void refreshAll() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            return;
        }
        for (int i = 0; i < panels.size(); i++) {
            refreshPanel(panels.get(i));
        }
    }

    private void refreshPanel(GlassPanel panel) {
        panel.updateGlass();
        Bitmap snap = buffers[1 - bufferIndex];
        if (snap != null) {
            panel.setBlurredBackdrop(snap);
        }
    }
}
