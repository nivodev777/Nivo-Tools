package com.nivodev.nivotools.ui.tools.compass;

import android.content.Context;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.nivodev.nivotools.R;

/**
 * Custom compass dial view - LIQUID GLASS polish.
 *
 * Design: ROTATING CARD + FIXED NEEDLE (classic magnetic compass):
 *   - The needle NEVER rotates - it stays pinned, tip up, on screen.
 *   - The CARD (tick ring + letters around it) rotates WITH the device:
 *     it turns by -heading, so the letter of the direction you face is
 *     always at the TOP (front). Face east -> the card spins (eastwards
 *     counterclockwise) until E sits on top; N lands on the left side.
 *   - The accent letter = the card letter currently sitting at the top
 *     (your heading) - it matches the degree/octant readout text.
 *   - The rim keeps an accent SweepGradient (liquid glass shine) plus a
 *     slow rotating glass sweep highlight - decoration only.
 *
 * setHeading(deg): heading in degrees clockwise, 0 = north. The fragment
 * does the smoothing; this view only draws.
 */
public class CompassDialView extends View {

    /** classic compass labels - one every 45 degrees */
    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMinor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMajor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterMain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint northFace = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint southFace = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint northFaceShadow = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** resolved colors (set in onSizeChanged, also needed in onDraw) */
    private int accent = Color.WHITE;
    private int ink = Color.WHITE;
    private int dim = Color.GRAY;

    /** reusable highlight paths - no per-frame allocation */
    private final Path up = new Path();
    private final Path down = new Path();

    /** current heading (clockwise, 0 = north) - drives the accent letter */
    private float heading = 0f;

    /** liquid sweep animation angle (self-driven, time based) */
    private long born;

    /** cached in onSizeChanged */
    private float cx = 0f;
    private float cy = 0f;
    private float radius = 0f;
    private boolean setupShine = false;

    public CompassDialView(Context c, android.util.AttributeSet attrs) {
        super(c, attrs);
        init(c);
    }

    public CompassDialView(Context c, android.util.AttributeSet attrs, int style) {
        super(c, attrs, style);
        init(c);
    }

    private void init(Context c) {
        born = System.currentTimeMillis();
        northFaceShadow.setShadowLayer(10f, 0f, 4f, 0x66000000);
    }

    /** called by the fragment: smoothed heading (0..360) */
    public void setHeading(float deg) {
        this.heading = ((deg % 360f) + 360f) % 360f;
        invalidate();
    }

    /** one long nudge swing of the needle - settle-animation pass */
    public void poke() {
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float d = getResources().getDisplayMetrics().density;
        cx = w / 2f;
        cy = h / 2f;
        // small margin from the edge so the ring is never cut off
        radius = Math.min(w, h) / 2f - 12f * d;

        // resolve colors from the theme - fall back to white on failure
        android.content.Context ctx = getContext();
        accent = Color.WHITE;
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(
                androidx.appcompat.R.attr.colorPrimary, tv, true)) {
            accent = tv.data;
        }
        ink = ContextCompat.getColor(ctx, R.color.light_on_surface);
        dim = ContextCompat.getColor(ctx, R.color.light_on_surface_variant);

        // liquid glass rim: accent glow ring + theme refraction look
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.4f * d);
        rim.setColor(accent);
        rim.setAlpha(90);

        rimGlow.setStyle(Paint.Style.STROKE);
        rimGlow.setStrokeWidth(1.2f * d);
        rimGlow.setColor(accent);
        rimGlow.setAlpha(34);

        // sweep gradient set up only once (rim highlight)
        if (!setupShine) {
            setupShine = true;
            int[] coloursSh = {0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF};
            sweep.setShader(new SweepGradient(cx, cy, coloursSh,
                    new float[]{0.0f, 0.12f, 0.42f}));
        }
        sweep.setStyle(Paint.Style.STROKE);
        sweep.setStrokeWidth(radius * 0.72f);
        sweep.setAlpha(16);

        // inner glass fill: a faint blot (depth)
        innerFill.setStyle(Paint.Style.FILL);
        innerFill.setColor(accent);
        innerFill.setAlpha(15);

        tickMinor.setStyle(Paint.Style.STROKE);
        tickMinor.setStrokeWidth(2f * d);
        tickMinor.setColor(ink);
        tickMinor.setAlpha(70);
        tickMinor.setStrokeCap(Paint.Cap.ROUND);

        tickMajor.setStyle(Paint.Style.STROKE);
        tickMajor.setStrokeWidth(2.6f * d);
        tickMajor.setColor(ink);
        tickMajor.setAlpha(170);
        tickMajor.setStrokeCap(Paint.Cap.ROUND);

        letterMain.setColor(accent);   // the front/heading letter wears the accent
        letterMain.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        letterMain.setTextSize(14f * d);
        letterMain.setTextAlign(Paint.Align.CENTER);

        letterDim.setColor(dim);
        letterDim.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        letterDim.setTextSize(10f * d);
        letterDim.setTextAlign(Paint.Align.CENTER);

        northFace.setColor(accent);
        southFace.setColor(dim);
        southFace.setAlpha(120);
        centerDot.setColor(accent);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float time = (System.currentTimeMillis() - born) / 1000f;

        // 1) liquid glass core (outer soft fill)
        canvas.drawCircle(cx, cy, radius - 6f * d, innerFill);

        // 2) rim + glow
        canvas.drawCircle(cx, cy, radius, rim);
        canvas.drawCircle(cx, cy, radius + 4f * d, rimGlow);

        // 3) slow rotating glass sweep (liquid feel - twist)
        canvas.save();
        canvas.rotate(time * 24f % 360f, cx, cy);
        canvas.drawCircle(cx, cy, radius - 3f * d, sweep);
        canvas.restore();

        // 4) ROTATING CARD - the tick ring + letters turn by -heading so
        //    the letter of the faced direction always ends up on TOP
        //    (in front). Face east -> the card rotates until E is up.
        //    baseline: letters drawn at (deg - 90) = N at top when
        //    heading = 0.
        int topDeg = (Math.round(heading / 45f) % 8) * 45;
        canvas.save();
        canvas.rotate(-heading, cx, cy);
        for (int deg = 0; deg < 360; deg += 15) {
            boolean major = deg % 45 == 0;
            Paint p = major ? tickMajor : tickMinor;
            float len = major ? 12f * d : 6f * d;
            double a = Math.toRadians(deg - 90);   // offset that puts E on top
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            canvas.drawLine(
                    cx + radius * cos, cy + radius * sin,
                    cx + (radius - len) * cos, cy + (radius - len) * sin, p);

            if (major) {
                // letter in its card slot (baseline offset applied)
                float lr = radius - 27f * d;
                float lx = cx + lr * cos;
                float ly = cy + lr * sin;
                // accent letter = the one that arrives at the TOP
                // (the faced direction) - matches the readout text
                Paint lp = deg == topDeg ? letterMain : letterDim;
                Paint.FontMetrics fm = lp.getFontMetrics();
                float mid = (fm.ascent + fm.descent) / 2f;
                canvas.drawText(POINTS[deg / 45], lx, ly - mid, lp);
            }
        }

        canvas.restore();   // card rotation group ends here

        // 5) NEEDLE: always UP, fixed - never rotates even when
        //    the device moves - the card spins around it
        float hw = 8f * d;
        float hlen = radius - 22f * d;
        float tail = hw * 1.5f;

        canvas.save();
        // no rotation - the needle is completely fixed
        northFaceShadow.setColor(accent);
        up.reset();
        up.moveTo(cx, cy - hlen);
        up.lineTo(cx - hw, cy + tail);
        up.lineTo(cx + hw, cy + tail);
        up.close();
        canvas.drawPath(up, northFaceShadow);   // soft shadow coat
        canvas.drawPath(up, northFace);

        down.reset();
        down.moveTo(cx, cy + hlen);
        down.lineTo(cx - hw, cy - tail);
        down.lineTo(cx + hw, cy - tail);
        down.close();
        canvas.drawPath(down, southFace);
        canvas.restore();

        // 6) center pin: accent ring + inner dot - depth bump
        canvas.drawCircle(cx, cy, 5f * d, northFace);
        canvas.drawCircle(cx, cy, 2.2f * d, centerDot);
    }
}
