package com.nivodev.nivotools.ui.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.util.Prefs;

/**
 * Reusable liquid-glass surface every panel of the app is built on.
 * Layers (bottom to top): blurred backdrop sample (optional), sheen highlight,
 * then the XML children. Fill + hairline stroke + rounded corners are drawn
 * on the background with clipToOutline.
 *
 * android:tag encodes config: "blur,24" (real blur + radius dp), "plain,28",
 * or empty for the default plain panel at 24dp.
 */
public class GlassPanel extends FrameLayout {

    private final ImageView blurLayer;
    private final ImageView sheenLayer;
    private final GradientDrawable background = new GradientDrawable();

    private boolean blurMode = false;
    private float radiusDp = 24f;
    private Bitmap lastBitmap;

    public GlassPanel(Context context) {
        this(context, null);
    }

    public GlassPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassPanel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(true);

        float density = context.getResources().getDisplayMetrics().density;
        blurLayer = new ImageView(context);
        blurLayer.setScaleType(ImageView.ScaleType.FIT_XY);
        blurLayer.setVisibility(View.GONE);
        sheenLayer = new ImageView(context);
        sheenLayer.setScaleType(ImageView.ScaleType.FIT_XY);
        sheenLayer.setImageResource(R.drawable.glass_highlight);

        parseTag();
        int radiusPx = (int) (radiusDp * density);

        background.setShape(GradientDrawable.RECTANGLE);
        applyGlassColors(radiusPx);

        addView(blurLayer, 0, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(sheenLayer, 1, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        setClipToOutline(true);
        invalidateOutline();
    }

    private void parseTag() {
        String tag = String.valueOf(getTag());
        if (tag != null && !tag.isEmpty() && !"null".equals(tag)) {
            String[] parts = tag.split(",");
            if ("blur".equals(parts[0].trim())) {
                blurMode = true;
            }
            if (parts.length > 1) {
                try {
                    radiusDp = Float.parseFloat(parts[1].trim());
                } catch (NumberFormatException ignored) {
                    radiusDp = 24f;
                }
            }
        }
    }

    private void applyGlassColors(int radiusPx) {
        int fill = ContextCompat.getColor(getContext(), R.color.glass_fill_light);
        int soft = ContextCompat.getColor(getContext(), R.color.glass_fill_soft_light);
        background.setColors(new int[]{fill, soft});
        background.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        background.setShape(GradientDrawable.RECTANGLE);
        background.setStroke(
                Math.max(1, (int) (1f * getContext().getResources().getDisplayMetrics().density)),
                ContextCompat.getColor(getContext(), R.color.glass_stroke_light));
        background.setCornerRadius(radiusPx);
        setBackground(background);
        sheenLayer.setImageAlpha(210);
    }

    public void updateGlass() {
        boolean useBlur = blurMode && Prefs.isBlurEnabled(getContext());
        blurLayer.setVisibility(useBlur && lastBitmap != null ? View.VISIBLE : View.GONE);
        int radiusPx = (int) (radiusDp * getContext().getResources().getDisplayMetrics().density);
        applyGlassColors(radiusPx);
        invalidate();
    }

    public void setBlurredBackdrop(Bitmap bitmap) {
        if (bitmap == lastBitmap) {
            return;
        }
        lastBitmap = bitmap;
        boolean useBlur = blurMode && Prefs.isBlurEnabled(getContext());
        if (useBlur) {
            blurLayer.setImageBitmap(bitmap);
            blurLayer.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (blurMode) {
            GlassEngine.get().register(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (blurMode) {
            GlassEngine.get().unregister(this);
        }
        super.onDetachedFromWindow();
    }

    public boolean isBlurMode() {
        return blurMode;
    }

    public float getRadiusDp() {
        return radiusDp;
    }

    public static int resolveGlassColor(View view, int resId) {
        return ContextCompat.getColor(view.getContext(), resId);
    }
}
