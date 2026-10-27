package com.nivodev.nivotools.ui.tools.alarm;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.ActivityRingBinding;
import com.nivodev.nivotools.util.AnimUtils;
import com.nivodev.nivotools.util.Haptics;

import java.util.Random;

/**
 * Full-screen alarm stopper: ringing never gives up until the user either
 * answers the math question (mode 0) or drags the handle far up (mode 1).
 */
public class RingActivity extends AppCompatActivity {

    private ActivityRingBinding binding;
    private int answer;
    private int mode;
    private int id;
    private ObjectAnimator bellAnim;
    private ObjectAnimator wiggle;
    private ValueAnimator pulse1;
    private ValueAnimator pulse2;
    private final StringBuilder typed = new StringBuilder();
    private final Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.nivodev.nivotools.util.Accent.apply(this);
        lockscreenFlags();
        binding = ActivityRingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String label = getIntent() != null ? getIntent().getStringExtra("label") : null;
        mode = getIntent() != null ? getIntent().getIntExtra("mode", -1) : -1;
        id = getIntent() != null ? getIntent().getIntExtra("id", -1) : -1;
        // notification launches may drop some extras - fall back to the store
        if (mode < 0 || label == null || id >= 0) {
            try {
                org.json.JSONObject a = AlarmStore.byId(AlarmStore.load(this), id);
                if (a != null) {
                    if (mode < 0) {
                        mode = a.optInt("mode", 0);
                    }
                    if (label == null || label.isEmpty()) {
                        label = a.optString("label", "");
                    }
                }
            } catch (Throwable t) {
            }
        }
        if (mode < 0) {
            mode = 0;
        }
        binding.textRingLabel.setText(label != null && !label.isEmpty()
                ? label : getString(R.string.alarm_fired_title));

        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            binding.textRingTime.setText(AlarmStore.formatTime(
                    c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE)));
        } catch (Throwable t) {
        }

        binding.ringPulseRoot.setVisibility(View.VISIBLE);
        if (mode == 1) {
            binding.ringQuestionCard.setVisibility(View.GONE);
            binding.containerRingKeys.setVisibility(View.GONE);
            binding.ringButtonsHolder.setVisibility(View.VISIBLE);
            setupButtons();
        } else {
            binding.ringQuestionCard.setVisibility(View.VISIBLE);
            binding.ringButtonsHolder.setVisibility(View.GONE);
            buildKeypad();
            newQuestion();
            resetInput();
        }
        setupPulse();
        setupEntrance();
    }

    @SuppressWarnings("deprecation")
    private void lockscreenFlags() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** No-frills question: only + and -, easy numbers, answer always fits 2 digits. */
    private void newQuestion() {
        int op = random.nextInt(2);
        int a;
        int b;
        switch (op) {
            case 0: // + (15..93, never 3 digits)
                a = random.nextInt(35) + 10;
                b = random.nextInt(45) + 5;
                break;
            default: // - (5..88, clean subtraction)
                a = random.nextInt(65) + 25;
                b = random.nextInt(20) + 1;
                break;
        }
        answer = compute(op, a, b);
        String ops = op == 0 ? "+" : "\u2212";
        binding.textRingQuestion.setText(a + " " + ops + " " + b + " = ?");
        binding.textRingWrong.setVisibility(View.GONE);
    }

    private static int compute(int op, int a, int b) {
        if (op == 0) {
            return a + b;
        }
        if (op == 1) {
            return a - b;
        }
        if (op == 2) {
            return a * b;
        }
        return a / b;
    }

    private void buildKeypad() {
        final int[][] rows = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}, {-1, 0, -2}};
        float d = getResources().getDisplayMetrics().density;
        int idle = ContextCompat.getColor(this, R.color.chip_idle);
        int onSurface = ContextCompat.getColor(this, R.color.light_on_surface);
        int primary = com.nivodev.nivotools.util.Accent.of(this);
        int onPrimary = ContextCompat.getColor(this, R.color.light_on_primary);
        int muted = ContextCompat.getColor(this, R.color.light_on_surface_variant);
        int margins = (int) (5f * d);
        for (int[] row : rows) {
            android.widget.LinearLayout line = new android.widget.LinearLayout(this);
            line.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            line.setWeightSum(3f);
            for (int key : row) {
                final boolean check = key == -2;
                final boolean back = key == -1;
                TextView btn = new TextView(this);
                btn.setText(key >= 0 ? String.valueOf(key) : (back ? "\u232B" : "\u2713"));
                btn.setTextSize(check ? 24f : 19f);
                btn.setGravity(android.view.Gravity.CENTER);
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setShape(back ? android.graphics.drawable.GradientDrawable.OVAL
                        : android.graphics.drawable.GradientDrawable.RECTANGLE);
                if (!back) {
                    bg.setCornerRadius(29f * d);
                }
                bg.setColor(check ? primary : idle);
                btn.setBackground(bg);
                btn.setTextColor(check ? onPrimary : (back ? muted : onSurface));
                android.widget.LinearLayout.LayoutParams lp =
                        new android.widget.LinearLayout.LayoutParams(0,
                                (int) (54f * d), 1f);
                lp.setMargins(margins, margins, margins, margins);
                btn.setLayoutParams(lp);
                btn.setOnClickListener(v -> {
                    AnimUtils.pressScale(v);
                    Haptics.action((Context) this, Haptics.TYPE_TAP);
                    onKey(key);
                });
                line.addView(btn);
            }
            binding.containerRingKeys.addView(line);
        }
    }

    private void onKey(int key) {
        if (key == -2) { // check
            int guess;
            try {
                guess = Integer.parseInt(typed.length() == 0 ? "0"
                        : typed.toString());
            } catch (NumberFormatException e) {
                guess = Integer.MIN_VALUE;
            }
            if (guess == answer) {
                Haptics.action((Context) this, Haptics.TYPE_CONFIRM);
                stopAlarm();
                return;
            }
            Haptics.action((Context) this, Haptics.TYPE_ERROR);
            binding.textRingWrong.setVisibility(View.VISIBLE);
            shakeWrong();
            typed.setLength(0);
            resetInput();
            return;
        }
        if (key == -1) { // backspace
            if (typed.length() > 0) {
                typed.deleteCharAt(typed.length() - 1);
            }
        } else if (typed.length() < 2) {
            typed.append((char) ('0' + key));
        }
        binding.textRingWrong.setVisibility(View.GONE);
        resetInput();
    }

    private void resetInput() {
        String s = typed.toString();
        // fill left to right: first digit in box A, second in box B
        binding.ringDigitA.setText(s.length() >= 1 ? s.substring(0, 1) : "");
        binding.ringDigitB.setText(s.length() >= 2 ? s.substring(1) : "");
    }

    /** Horizontal shake + red flash on the two digit boxes. */
    private void shakeWrong() {
        float off = 14f * getResources().getDisplayMetrics().density;
        int err = ContextCompat.getColor(this, R.color.light_error);
        TextView[] boxes = {binding.ringDigitA, binding.ringDigitB};
        for (TextView box : boxes) {
            ObjectAnimator.ofFloat(box, View.TRANSLATION_X, -off, off, -off / 2f, 0f)
                    .setDuration(320L).start();
            try {
                GradientDrawable flash = new GradientDrawable();
                flash.setCornerRadius(12f * getResources().getDisplayMetrics().density);
                flash.setColor(Color.argb(70, Color.red(err), Color.green(err),
                        Color.blue(err)));
                box.setBackground(flash);
                box.setTextColor(err);
                box.postDelayed(() -> {
                    box.setBackground(ContextCompat.getDrawable(this,
                            R.drawable.glass_panel_small));
                    box.setTextColor(ContextCompat.getColor(this,
                            R.color.light_on_surface));
                }, 420L);
            } catch (Throwable t) {
            }
        }
    }

    /** Same alarm refires while the ring screen is already on top. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) {
            return;
        }
        int nid = intent.getIntExtra("id", id);
        int nmode = intent.getIntExtra("mode", mode);
        if (nid != id || nmode != mode) {
            // different alarm: rebuild the whole screen against the new extras
            recreate();
            return;
        }
        String label = intent.getStringExtra("label");
        if (binding != null && label != null && !label.isEmpty()) {
            binding.textRingLabel.setText(label);
        }
    }

    /** Off: one-shot alarms die completely; repeating ones ring again on
     *  their next selected day (the receiver already re-registered it). */
    private void setupButtons() {
        binding.ringBtnOff.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(this, Haptics.TYPE_CONFIRM);
            try {
                org.json.JSONArray arr = AlarmStore.load(this);
                org.json.JSONObject a = AlarmStore.byId(arr, id);
                int days = a != null ? a.optInt("days", 0) : 0;
                if (days <= 0) {
                    // single-day alarm: it will never fire again - disable it
                    if (a != null) {
                        a.put("enabled", false);
                        AlarmStore.save(this, arr);
                    }
                    AlarmStore.cancel(this, id);
                }
                // repeating alarm: keep it enabled and keep tomorrow's slot
            } catch (Throwable t) {
            }
            stopAlarm();
        });
        binding.ringBtnPause.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(this, Haptics.TYPE_TICK);
            // one-shot alarms were marked done when they fired, but a paused
            // alarm must still ring at the snooze time, so re-enable it
            try {
                org.json.JSONArray arr = AlarmStore.load(this);
                org.json.JSONObject a = AlarmStore.byId(arr, id);
                if (a != null) {
                    a.put("enabled", true);
                    AlarmStore.save(this, arr);
                }
            } catch (Throwable t) {
            }
            AlarmStore.scheduleSnooze(this, id, 15);
            stopAlarm();
        });
    }

    /** Expanding accent rings + wiggling, breathing alarm bell. */
    private void setupPulse() {
        View[] rings = {binding.ringPulse1, binding.ringPulse2};
        for (int i = 0; i < rings.length; i++) {
            final View r = rings[i];
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(2000L);
            va.setRepeatCount(ValueAnimator.INFINITE);
            va.setStartDelay(850L * i);
            va.addUpdateListener(anim -> {
                float p = (float) anim.getAnimatedValue();
                r.setScaleX(1f + p * 1.6f);
                r.setScaleY(1f + p * 1.6f);
                r.setAlpha(0.6f - 0.6f * p);
            });
            va.start();
            if (i == 0) {
                pulse1 = va;
            } else {
                pulse2 = va;
            }
        }
        bellAnim = ObjectAnimator.ofPropertyValuesHolder(binding.ringBell,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.1f, 1f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.1f, 1f));
        bellAnim.setDuration(1000L);
        bellAnim.setRepeatCount(ValueAnimator.INFINITE);
        bellAnim.start();
        wiggle = ObjectAnimator.ofFloat(binding.ringBell, "rotation", -12f, 12f);
        wiggle.setDuration(420L);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.start();
    }

    /** Surgical entrance: cards rise + fade, the ringing visual pops in. */
    private void setupEntrance() {
        float d = getResources().getDisplayMetrics().density;
        View top = binding.ringTopCard;
        top.setAlpha(0f);
        top.setTranslationY(-22f * d);
        top.animate().alpha(1f).translationY(0f).setDuration(420L).start();
        final View pulse = binding.ringPulseRoot;
        pulse.setScaleX(0.6f);
        pulse.setScaleY(0.6f);
        pulse.setAlpha(0f);
        pulse.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(520L)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                .start();
        View bottom = mode == 1 ? binding.ringButtonsHolder : binding.ringQuestionCard;
        bottom.setAlpha(0f);
        bottom.setTranslationY(26f * d);
        bottom.animate().alpha(1f).translationY(0f).setDuration(420L)
                .setStartDelay(90L).start();
    }

    @Override
    protected void onDestroy() {
        if (pulse1 != null) {
            pulse1.cancel();
        }
        if (pulse2 != null) {
            pulse2.cancel();
        }
        if (bellAnim != null) {
            bellAnim.cancel();
        }
        if (wiggle != null) {
            wiggle.cancel();
        }
        binding = null;
        super.onDestroy();
    }

    private void stopAlarm() {
        try {
            Intent stop = new Intent(this, AlarmService.class)
                    .setAction(AlarmService.ACTION_STOP);
            androidx.core.content.ContextCompat.startForegroundService(this, stop);
        } catch (Throwable t) {
            stopService(new Intent(this, AlarmService.class));
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        // blocked: only the correct answer dismisses the alarm
    }
}
