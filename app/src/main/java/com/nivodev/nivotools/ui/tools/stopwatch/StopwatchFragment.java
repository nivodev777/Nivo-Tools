package com.nivodev.nivotools.ui.tools.stopwatch;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.FragmentStopwatchBinding;
import com.nivodev.nivotools.databinding.ItemLapBinding;
import com.nivodev.nivotools.util.AnimUtils;
import com.nivodev.nivotools.util.Haptics;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stopwatch + Timer mini-app (Google Clock style).
 *
 * LAYOUT idea (user rule): stopwatch jahan pehle tha wahi hai; TIMER us ke
 * NEECHE same glass card style me - dono ek sath screen bharte hain takay
 * screen khali na lage, NestedScrollView scroll par sab dikhta hai.
 *
 * NOTE (app-structure rule): poora code is package me - dusre app se koi
 * sharing nahi (common engines AnimUtils/Haptics sab ke liye exception).
 *
 * 1) STOPWATCH card:
 *    - SystemClock.elapsedRealtime() clock: deep sleep samet chalta hai.
 *    - bank  = pehle ke bane hue total ms (Pause freeze), started = resume.
 *    - Lap list: newest top, split + total.
 *
 * 2) TIMER card:
 *    - frozen = baqi ms (paused), endAt = elapsedRealtime zero-point.
 *    - Quick presets (1/3/5/10/30 min), "+1 min" chip = baad me waqt jora.
 *    - Zero par 3x beep + sharp haptic + "Time over" note.
 *
 * Autosave: dono cards ki state onPause par prefs me, onResume par wapas.
 */
public class StopwatchFragment extends androidx.fragment.app.Fragment {

    /** prefs file - sirf isi mini-app ke liye */
    private static final String PREFS = "sw_store";

    /** quick preset chips (minutes) */
    private static final int[] PRESETS = {1, 3, 5, 10, 30};

    private FragmentStopwatchBinding binding;

    /** main-thread handler: 33ms par display refresh */
    private final Handler tick = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------- stopwatch state

    /** ms jo pehli runs me ban chuka */
    private long bank = 0L;

    /** last resume ka elapsedRealtime() snapshot (0 = resume nahi) */
    private long started = 0L;

    /** watch chal rahi hai? */
    private boolean running = false;

    /** entry = lap press waqt ka TOTAL elapsed ms (chronological) */
    private final List<Long> laps = new ArrayList<>();

    private LapAdapter lapsAdapter;

    // ------------------------------------------------------- timer state

    /** baqi countdown ms (paused me) */
    private long timerFrozen = 0L;

    /** elapsedRealtime() jab countdown zero hoga */
    private long timerEndAt = 0L;

    /** countdown chal raha hai? */
    private boolean timerRunning = false;

    /** finish beeps ka sound engine (waqt par banti hai) */
    private ToneGenerator beeper;

    // ------------------------------------------------------------ setup

    @Override
    public View onCreateView(@NonNull LayoutInflater in, ViewGroup c, Bundle s) {
        binding = FragmentStopwatchBinding.inflate(in, c, false);

        lapsAdapter = new LapAdapter();
        AnimUtils.gatePressFeedback(binding.swLaps);
        binding.swLaps.setLayoutManager(new LinearLayoutManager(
                requireContext(), RecyclerView.VERTICAL, false));
        binding.swLaps.setAdapter(lapsAdapter);

        // ---- stopwatch card buttons
        binding.swAction.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            if (running) {
                pauseWatch();
            } else {
                startWatch();
            }
        });
        binding.swLap.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TICK);
            if (running) {
                lapWatch();
            } else if (bank > 0 || !laps.isEmpty()) {
                resetWatch();
            }
        });

        // ---- timer card buttons
        binding.tBtnStart.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            if (timerRunning) {
                pauseTimer();
            } else {
                startTimer();
            }
        });
        binding.tBtnMore.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TICK);
            if (timerRunning) {
                addMinute();         // chalte me +1 min (baad ke waqt)
            } else if (timerFrozen > 0) {
                resetTimer();
            }
        });

        buildPresets();
        restoreState();
        syncWatchUI();
        syncTimerUI();
        return binding.getRoot();
    }

    // ------------------------------------------------------ watch logic

    /** resume clock: started snapshot + tick loop */
    private void startWatch() {
        if (running) {
            return;
        }
        started = SystemClock.elapsedRealtime();
        running = true;
        syncWatchUI();
        runTick();
    }

    /** freeze: chalta run bank me shift */
    private void pauseWatch() {
        if (!running) {
            return;
        }
        bank += SystemClock.elapsedRealtime() - started;
        started = 0L;
        running = false;
        syncWatchUI();
        renderWatch();
    }

    /** lap: total snapshot list me jor + newest top */
    private void lapWatch() {
        laps.add(elapsed());
        saveState();
        lapsAdapter.notifyItemInserted(0);
        binding.swLaps.scrollToPosition(0);
    }

    /** watch clean reset */
    private void resetWatch() {
        running = false;
        bank = 0L;
        started = 0L;
        int count = laps.size();
        laps.clear();
        saveState();
        if (count > 0) {
            lapsAdapter.notifyItemRangeRemoved(0, count);
        }
        syncWatchUI();
        renderWatch();
    }

    /** banked + current run = total ms */
    private long elapsed() {
        return bank + (running ? SystemClock.elapsedRealtime() - started : 0L);
    }

    // ------------------------------------------------------ timer logic

    /** countdown arm chalu: endAt snapshot + tick */
    private void startTimer() {
        if (timerRunning || timerFrozen <= 0) {
            return;
        }
        timerEndAt = SystemClock.elapsedRealtime() + timerFrozen;
        timerRunning = true;
        syncTimerUI();
        runTick();
    }

    /** countdown freeze: baqi frozen me */
    private void pauseTimer() {
        if (!timerRunning) {
            return;
        }
        timerFrozen = Math.max(0L, timerEndAt - SystemClock.elapsedRealtime());
        timerRunning = false;
        syncTimerUI();
        renderTimer();
    }

    /** "+1 min": running me endAt barho warna frozen me jor */
    private void addMinute() {
        if (timerRunning) {
            timerEndAt += 60_000L;
            timerFrozen = Math.max(0L,
                    timerEndAt - SystemClock.elapsedRealtime());
        } else {
            timerFrozen += 60_000L;
        }
        renderTimer();
        saveState();
    }

    /** timer clean */
    private void resetTimer() {
        timerRunning = false;
        timerFrozen = 0L;
        timerEndAt = 0L;
        stopBeeps();
        syncTimerUI();
        renderTimer();
        saveState();
    }

    /** preset chip par aram ho jai */
    private void armPreset(int minutes) {
        if (timerRunning) {
            return;   // chalte me preset nahi - pehle Reset
        }
        timerFrozen = minutes * 60_000L;
        timerEndAt = 0L;
        saveState();
        renderTimer();
        Haptics.action(requireActivity(), Haptics.TYPE_TAP);
    }

    /** zero par 3x beep + sharp haptic + note */
    private void fireTimerDone() {
        timerRunning = false;
        timerFrozen = 0L;
        timerEndAt = 0L;
        syncTimerUI();
        try {
            if (beeper == null) {
                beeper = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            }
            tick.removeCallbacks(beep1);
            tick.post(beep1);
            tick.postDelayed(beep2, 350);
            tick.postDelayed(beep3, 700);
        } catch (Throwable ignored) {
        }
        try {
            Haptics.action(requireActivity(), Haptics.TYPE_ERROR);
        } catch (Throwable ignored) {
        }
    }

    private final Runnable beep1 = () -> buzz(ToneGenerator.TONE_PROP_BEEP);
    private final Runnable beep2 = () -> buzz(ToneGenerator.TONE_PROP_BEEP2);
    private final Runnable beep3 = () -> buzz(ToneGenerator.TONE_PROP_BEEP);

    private void buzz(int tone) {
        try {
            if (beeper != null) {
                beeper.startTone(tone, 120);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopBeeps() {
        tick.removeCallbacks(beep1);
        tick.removeCallbacks(beep2);
        tick.removeCallbacks(beep3);
        try {
            if (beeper != null) {
                beeper.release();
            }
        } catch (Throwable ignored) {
        }
        beeper = null;
    }

    // ---------------------------------------------------------- presets

    /** 1/3/5/10/30 min chips - timer card ke andar */
    private void buildPresets() {
        float d = getResources().getDisplayMetrics().density;
        binding.tPresets.removeAllViews();
        for (int minutes : PRESETS) {
            android.widget.TextView chip = new android.widget.TextView(requireContext());
            chip.setText(getString(R.string.sw_min_val, minutes));
            chip.setTextSize(12f);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setTextColor(ContextCompat.getColor(requireContext(),
                    R.color.light_on_surface));
            chip.setBackgroundResource(R.drawable.bg_solid_round);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding((int) (14 * d), 0, (int) (14 * d), 0);

            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            1f);   // barabar chips: row full bharto hai
            if (minutes != PRESETS[0]) {
                lp.setMarginStart((int) (8 * d));
            }
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                AnimUtils.pressScale(v);
                armPreset(minutes);
            });
            binding.tPresets.addView(chip);
        }
    }

    // ------------------------------------------------------------- tick

    /** display driver: jab tak koi engine running ho, repeat */
    private final Runnable runner = new Runnable() {
        @Override
        public void run() {
            renderWatch();
            renderTimer();
            if (binding != null && (running || timerRunning)) {
                tick.postDelayed(this, 33);
            }
        }
    };

    private void runTick() {
        tick.removeCallbacks(runner);
        renderWatch();
        renderTimer();
        tick.postDelayed(runner, 33);
    }

    /** "mm:ss.cc" (1h+ -> "h:mm:ss.cc") */
    private String format(long ms) {
        long total = Math.max(0L, ms);
        long cs = (total / 10) % 100;
        long sec = (total / 1000) % 60;
        long min = ((total / 60000) % 60);
        long hour = total / 3600000;
        if (hour > 0) {
            return String.format(Locale.US, "%d:%02d:%02d.%02d",
                    hour, min, sec, cs);
        }
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, cs);
    }

    /** timer mode ke liye "mm:ss"/"h:mm:ss" (centi na - clean) */
    private String formatTimer(long ms) {
        long total = Math.max(0L, ms);
        long sec = (total / 1000) % 60;
        long min = (total / 60000) % 60;
        long hour = total / 3600000;
        if (hour > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hour, min, sec);
        }
        return String.format(Locale.US, "%02d:%02d", min, sec);
    }

    /** stopwatch card render */
    /** setText only when the string actually changed (idle ticks no-op). */
    private void setTextIfChanged(TextView view, String text) {
        if (view.getText() == null
                || !view.getText().toString().equals(text)) {
            view.setText(text);
        }
    }

    private void renderWatch() {
        if (binding == null) {
            return;
        }
        setTextIfChanged(binding.swTime, format(elapsed()));
    }

    /** timer card render (+ finish detect) */
    private void renderTimer() {
        if (binding == null) {
            return;
        }
        long remain = timerRunning
                ? Math.max(0L, timerEndAt - SystemClock.elapsedRealtime())
                : timerFrozen;
        if (timerRunning && remain == 0L) {
            binding.tTime.setText(formatTimer(0L));
            fireTimerDone();
            return;
        }
        setTextIfChanged(binding.tTime, formatTimer(remain));
    }

    // ------------------------------------------------------- visuals

    /** stopwatch card buttons/notes sync */
    private void syncWatchUI() {
        if (binding == null) {
            return;
        }
        boolean hasState = running || bank > 0 || !laps.isEmpty();
        binding.swAction.setText(getString(running
                ? R.string.sw_pause
                : hasState ? R.string.sw_resume : R.string.sw_start));
        binding.swLap.setText(getString(running
                ? R.string.sw_lap
                : hasState ? R.string.sw_reset : R.string.sw_lap));
        binding.swLap.setAlpha(running ? 1f : 0.8f);
        binding.swNote.setText(getString(running
                ? R.string.sw_note_run
                : hasState ? R.string.sw_note_pause : R.string.sw_note_idle));
    }

    /** timer card buttons/notes sync */
    private void syncTimerUI() {
        if (binding == null) {
            return;
        }
        if (timerRunning) {
            binding.tBtnStart.setText(getString(R.string.sw_pause));
            binding.tBtnMore.setText(getString(R.string.sw_add_min));
            binding.tBtnMore.setAlpha(1f);
            binding.tNote.setText(getString(R.string.sw_t_run));
        } else if (timerFrozen > 0) {
            binding.tBtnStart.setText(getString(R.string.sw_resume));
            binding.tBtnMore.setText(getString(R.string.sw_reset));
            binding.tBtnMore.setAlpha(1f);
            binding.tNote.setText(getString(R.string.sw_t_pause));
        } else {
            binding.tBtnStart.setText(getString(R.string.sw_start));
            binding.tBtnMore.setText(getString(R.string.sw_add_min));
            binding.tBtnMore.setAlpha(0.8f);
            binding.tNote.setText(getString(R.string.sw_t_ready));
        }
    }

    // ------------------------------------------------------------ laps

    /** newest-first lap cards: split + total dono */
    private class LapAdapter extends RecyclerView.Adapter<LapAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final ItemLapBinding b;

            Holder(ItemLapBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemLapBinding.inflate(
                    getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            // lap list chronological - screen ulti (newest top)
            int index = laps.size() - 1 - position;
            long cur = laps.get(index);
            long prev = index == 0 ? 0L : laps.get(index - 1);
            h.b.lapNo.setText(getString(R.string.sw_lap_no, index + 1));
            h.b.lapSplit.setText(format(cur - prev));
            h.b.lapTotal.setText(format(cur));
        }

        @Override
        public int getItemCount() {
            return laps.size();
        }
    }

    // ------------------------------------------------------- autosave

    /** dono cards ki poori state ek prefs file me (same app) */
    private void saveState() {
        try {
            JSONArray arr = new JSONArray();
            for (long l : laps) {
                arr.put(l);
            }
            requireContext().getSharedPreferences(PREFS, 0).edit()
                    .putLong("bank", bank)
                    .putBoolean("running", running)
                    .putLong("started", started)
                    .putString("laps", arr.toString())
                    .putLong("timer_f", timerFrozen)
                    .putBoolean("timer_r", timerRunning)
                    .putLong("timer_end", timerEndAt)
                    .apply();
        } catch (Throwable ignored) {
            // storage fail: sirf live session me data, crash nahi
        }
    }

    /** dono cards ki state wapas + render */
    private void restoreState() {
        try {
            android.content.SharedPreferences p =
                    requireContext().getSharedPreferences(PREFS, 0);
            JSONArray arr = new JSONArray(p.getString("laps", "[]"));
            laps.clear();
            for (int i = 0; i < arr.length(); i++) {
                laps.add(arr.optLong(i, 0L));
            }
            bank = Math.max(0L, p.getLong("bank", 0L));
            started = p.getLong("started", 0L);
            running = p.getBoolean("running", false);
            timerFrozen = Math.max(0L, p.getLong("timer_f", 0L));
            timerRunning = p.getBoolean("timer_r", false);
            timerEndAt = p.getLong("timer_end", 0L);

            // app close ke dauran countdown khatam ho gaya? graceful
            if (timerRunning && timerEndAt <= SystemClock.elapsedRealtime()) {
                timerRunning = false;
                timerFrozen = 0L;
                timerEndAt = 0L;
            } else if (timerRunning) {
                // wake: endAt absolute - baqi frozen me le lo (paused wapas)
                timerRunning = false;
                timerFrozen = Math.max(0L, timerEndAt
                        - SystemClock.elapsedRealtime());
            }
            if (running) {
                runTick();
            } else if (timerRunning) {
                runTick();
            }
        } catch (Throwable ignored) {
        }
        lapsAdapter.notifyDataSetChanged();
        if (binding != null && !running && bank == 0 && laps.isEmpty()) {
            binding.swTime.setText(format(0L));
        }
    }

    // ------------------------------------------------------- lifecycle

    @Override
    public void onPause() {
        saveState();
        tick.removeCallbacks(runner);
        stopBeeps();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        restoreState();
        syncWatchUI();
        syncTimerUI();
    }

    @Override
    public void onDestroyView() {
        tick.removeCallbacks(runner);
        stopBeeps();
        binding = null;
        super.onDestroyView();
    }
}
