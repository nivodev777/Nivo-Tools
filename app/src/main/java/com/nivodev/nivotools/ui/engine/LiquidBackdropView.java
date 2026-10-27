package com.nivodev.nivotools.ui.engine;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.util.Prefs;

/**
 * Full-screen animated liquid backdrop: a slow drifting field of soft radial
 * color blobs. Glass panels blur this through GlassEngine, so the frosted
 * surfaces keep a living, colorful background instead of a flat one.
 */
public class LiquidBackdropView extends View {

    private static final long ANIMATOR_MS = 17000L;
    private static final long NOTIFY_EVERY_MS = 120L;

    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] phases = {0.00f, 0.25f, 0.50f, 0.75f};
    private final int[] blobColors = new int[4];

    /**
     * Full sine revolutions each blob completes per loop. Integers are what makes
     * the wrap frame zero identical to frame one - the drift never "restarts",
     * it just keeps flowing. Different counts per blob keep the motion organic.
     */
    private static final int[] REV_X = {3, 4, 2, 3};
    private static final int[] REV_Y = {4, 3, 4, 2};
    private float flow;

    private boolean sizeReady = false;
    private ValueAnimator animator;
    private long lastNotifyMs = 0L;
    private long lastDrawMs = 0L;
    private boolean registerWithEngine = true;

    /** Cached radial gradients - rebuilt only when the view size changes. */
    private final RadialGradient[] gradients = new RadialGradient[4];
    private int gradW = -1;
    private int gradH = -1;

    /** Set false for decorative instances (drawer) that must not hijack the engine source. */
    public void setRegisterWithEngine(boolean register) {
        registerWithEngine = register;
    }

    public LiquidBackdropView(Context context) {
        this(context, null);
    }

    public LiquidBackdropView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LiquidBackdropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        blobColors[0] = safeColor(R.color.blob_violet_light);
        blobColors[1] = safeColor(R.color.blob_cyan_light);
        blobColors[2] = safeColor(R.color.blob_pink_light);
        blobColors[3] = safeColor(R.color.blob_amber_light);
    }

    private int safeColor(int resId) {
        try {
            return ContextCompat.getColor(getContext(), resId);
        } catch (Throwable t) {
            return Color.TRANSPARENT;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        sizeReady = w > 0 && h > 0;
    }

    /** Draws the liquid scene. Used both for onDraw and for engine snapshots. */
    public void drawScene(Canvas canvas, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        // anchors spread over the whole screen so glass near the toolbar,
        // the lists and the bottom edge all have colour behind them
        float[] anchorsX = {0.24f, 0.78f, 0.32f, 0.70f};
        float[] anchorsY = {0.14f, 0.16f, 0.84f, 0.80f};
        float[] wanderX = {0.26f, 0.20f, 0.18f, 0.24f};
        float[] wanderY = {0.14f, 0.16f, 0.12f, 0.18f};
        float[] radii = {0.58f, 0.52f, 0.46f, 0.42f};
        float rStep = minSize(w, h);

        if (w != gradW || h != gradH) {
            gradW = w;
            gradH = h;
            for (int i = 0; i < 4; i++) {
                float r = radii[i] * rStep;
                int color = blobColors[i];
                int mid = (color & 0x00FFFFFF) | (((color >>> 24) / 2) << 24);
                gradients[i] = new RadialGradient(
                        0f, 0f, r,
                        new int[]{color, mid, Color.TRANSPARENT},
                        new float[]{0f, 0.55f, 1f},
                        Shader.TileMode.CLAMP);
            }
        }

        for (int i = 0; i < 4; i++) {
            // continuous flow: full revolutions + static offset = seamless loop
            float tx = flow * REV_X[i] + phases[i];
            float ty = flow * REV_Y[i] + phases[i] * 0.5f;
            float cx = w * (anchorsX[i] + wanderX[i] * (float) Math.sin(tx * Math.PI * 2.0));
            float cy = h * (anchorsY[i]
                    + wanderY[i] * (float) Math.cos(ty * Math.PI * 2.0));

            blobPaint.setShader(gradients[i]);
            int save = canvas.save();
            canvas.translate(cx, cy);
            canvas.drawCircle(0f, 0f, radii[i] * rStep, blobPaint);
            canvas.restoreToCount(save);
        }
    }

    private float minSize(int w, int h) {
        return w < h ? w : h;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!sizeReady) {
            return;
        }
        drawScene(canvas, getWidth(), getHeight());
    }

    /** Pushes throttled frame notifications into the blur engine. */
    public void notifyFrame(long nowMs) {
        if (nowMs - lastNotifyMs >= NOTIFY_EVERY_MS) {
            lastNotifyMs = nowMs;
            GlassEngine.get().onBackdropFrame(this);
        }
    }

    private void syncAnimator() {
        boolean shouldRun = sizeReady && Prefs.areAnimationsEnabled(getContext());
        if (shouldRun) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(ANIMATOR_MS);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.addUpdateListener(animation -> {
                    flow = (float) animation.getAnimatedValue();
                    notifyFrame(SystemClock.uptimeMillis());
                    if (GlassEngine.isLowPower(getContext())) {
                        // weak devices: keep the liquid alive at ~16fps instead of 60
                        long now = SystemClock.uptimeMillis();
                        if (now - lastDrawMs >= 60L) {
                            lastDrawMs = now;
                            postInvalidateOnAnimation();
                        }
                    } else {
                        postInvalidateOnAnimation();
                    }
                });
                animator.start();
            }
        } else if (animator != null && animator.isRunning()) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (registerWithEngine) {
            GlassEngine.get().onBackdropAttached(this);
        }
        syncAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
            animator = null;
        }
        if (registerWithEngine) {
            GlassEngine.get().onBackdropDetached(this);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            syncAnimator();
        } else if (animator != null && animator.isRunning()) {
            animator.cancel();
            animator = null;
        }
    }
}
