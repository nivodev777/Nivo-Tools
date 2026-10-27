package com.nivodev.nivotools.ui.glass;

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

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.util.BlurUtils;
import com.nivodev.nivotools.util.Prefs;

/**
 * Liquid-glass panel: blurred snapshot of the configured source view is drawn
 * behind the panel content, plus a soft highlight gradient and a hairline stroke.
 */
public class GlassBlurView extends FrameLayout {

    private static final float DEFAULT_DOWNSAMPLE = 7f;
    private static final int DEFAULT_RADIUS_DP = 22;

    private final ImageView blurImage;
    private final ImageView highlightImage;
    private final GradientDrawable strokeDrawable;

    private View blurSource;
    private float downsample = DEFAULT_DOWNSAMPLE;
    private float blurRadiusPx;
    private float cornerRadiusPx;

    public GlassBlurView(Context context) {
        this(context, null);
    }

    public GlassBlurView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GlassBlurView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipChildren(true);

        float density = context.getResources().getDisplayMetrics().density;
        blurRadiusPx = 14f * density;
        cornerRadiusPx = 24f * density;

        blurImage = new ImageView(context);
        blurImage.setScaleType(ImageView.ScaleType.FIT_XY);
        addView(blurImage, 0, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        highlightImage = new ImageView(context);
        highlightImage.setScaleType(ImageView.ScaleType.FIT_XY);
        highlightImage.setImageResource(R.drawable.glass_highlight);
        addView(highlightImage, 1, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        strokeDrawable = new GradientDrawable();
        strokeDrawable.setColor(0x00000000);
        strokeDrawable.setStroke(Math.max(1, (int) density), 0x55FFFFFF);
        strokeDrawable.setCornerRadius(cornerRadiusPx);
        setBackground(strokeDrawable);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadiusPx);
            }
        });
        setClipToOutline(true);
    }

    public void setBlurSource(View source) {
        this.blurSource = source;
    }

    public void setCornerRadiusDp(float dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        cornerRadiusPx = dp * density;
        strokeDrawable.setCornerRadius(cornerRadiusPx);
        invalidateOutline();
    }

    public void setBlurRadiusDp(float dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        blurRadiusPx = dp * density;
    }

    public void setDownsample(float downsample) {
        this.downsample = Math.max(1f, downsample);
    }

    /** Re-captures and re-blurs the source. Call when the backdrop may have changed. */
    public void updateBlur() {
        if (blurSource == null) {
            return;
        }
        if (!Prefs.isBlurEnabled(getContext())) {
            blurImage.setImageDrawable(null);
            return;
        }
        Bitmap captured = BlurUtils.captureView(blurSource, downsample);
        if (captured == null) {
            return;
        }
        int radius = Math.max(1, (int) (blurRadiusPx / downsample));
        Bitmap blurred = BlurUtils.boxBlur(captured, radius);
        blurImage.setImageBitmap(blurred);
    }
}
