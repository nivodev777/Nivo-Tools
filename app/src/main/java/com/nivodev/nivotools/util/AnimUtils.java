package com.nivodev.nivotools.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Locale;

public final class AnimUtils {

    private AnimUtils() {
    }

    /** Global gate set by busy RecyclerViews: suppresses DOWN-tap buzz/scale
     *  while the user is dragging a scroll, so list flicks stay silent. */
    private static volatile boolean scrolling;

    /** Returns true while any gated RecyclerView is actively dragging. */
    public static boolean isScrolling() {
        return scrolling;
    }

    /** Gate a list's press feedback against scroll gestures (call once). */
    public static void gatePressFeedback(RecyclerView list) {
        if (list == null) {
            return;
        }
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                scrolling = newState == RecyclerView.SCROLL_STATE_DRAGGING;
            }
        });
    }

    /** Stagger the direct children of a sheet/screen container. */
    public static void staggerChildren(ViewGroup container, long stepMs) {
        if (container == null
                || !Prefs.areAnimationsEnabled(container.getContext())) {
            return;
        }
        long delay = 0L;
        for (int i = 0; i < container.getChildCount(); i++) {
            slideFadeIn(container.getChildAt(i), delay);
            delay += Math.max(30L, stepMs);
        }
    }

    public static boolean enabled(View view) {
        return view != null && Prefs.areAnimationsEnabled(view.getContext());
    }

    /** Quick press feedback. Deliberately NEVER uses view.animate() or
     *  .cancel(): row views share one ViewPropertyAnimator with RecyclerView
     *  item animations, and cancelling it mid-layout can recycle a detached
     *  holder and crash. Direct scale sets never touch that animator. */
    public static void pressScale(final View view) {
        if (view == null) {
            return;
        }
        view.setOnTouchListener(null);
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    Haptics.tap(v);
                    v.setScaleX(0.96f);
                    v.setScaleY(0.96f);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Fade + slide-up entrance. */
    public static void slideFadeIn(View view, long delayMs) {
        if (view == null) {
            return;
        }
        if (!enabled(view)) {
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        view.setAlpha(0f);
        view.setTranslationY(28f);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(340)
                .setInterpolator(new LinearOutSlowInInterpolator())
                .start();
    }

    /** Subtle looping pulse for the FAB. */
    public static void pulse(final View view) {
        if (view == null || !enabled(view)) {
            return;
        }
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        ValueAnimator pulse = ValueAnimator.ofFloat(1f, 1.06f, 1f);
        pulse.setDuration(1600);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(animation -> {
            float v = (float) animation.getAnimatedValue();
            view.setScaleX(v);
            view.setScaleY(v);
        });
        pulse.start();
        view.setTag(com.nivodev.nivotools.R.id.anim_pulse_tag, pulse);
    }

    public static void stopPulse(View view) {
        if (view == null) {
            return;
        }
        Object tag = view.getTag(com.nivodev.nivotools.R.id.anim_pulse_tag);
        if (tag instanceof ValueAnimator) {
            ((ValueAnimator) tag).cancel();
        }
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    /** Bounce-in for the splash logo. */
    public static void bounceIn(View view, long delayMs) {
        if (view == null) {
            return;
        }
        if (!enabled(view)) {
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0f);
        view.setScaleX(0.4f);
        view.setScaleY(0.4f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.4f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.4f, 1f));
        set.setStartDelay(delayMs);
        set.setDuration(480);
        set.setInterpolator(new OvershootInterpolator(1.25f));
        set.start();
    }

    /** Grow a horizontal progress bar from its current width to a fraction of max. */
    public static void animateWidth(final View fillView, final float fraction, long durationMs) {
        if (fillView == null) {
            return;
        }
        final int parentWidth = ((View) fillView.getParent()).getWidth();
        final int target = (int) (parentWidth * Math.max(0f, Math.min(1f, fraction)));
        if (!enabled(fillView)) {
            ViewGroup.LayoutParams lp = fillView.getLayoutParams();
            lp.width = target;
            fillView.setLayoutParams(lp);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(fillView.getWidth(), target);
        animator.setDuration(durationMs);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams lp = fillView.getLayoutParams();
            lp.width = (int) animation.getAnimatedValue();
            fillView.setLayoutParams(lp);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ViewGroup.LayoutParams lp = fillView.getLayoutParams();
                lp.width = target;
                fillView.setLayoutParams(lp);
            }
        });
        animator.start();
    }

    public static String formatPercent(float fraction) {
        return String.format(Locale.US, "%d%%", Math.round(fraction * 100f));
    }
}
