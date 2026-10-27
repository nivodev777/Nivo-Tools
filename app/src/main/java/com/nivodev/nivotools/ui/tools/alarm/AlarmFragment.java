
/* Alarm screen: list of alarms, ring-time editor with day chips and
   reliability chip for the full-screen permission. */

package com.nivodev.nivotools.ui.tools.alarm;

import android.Manifest;
import android.app.AlarmManager;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.FragmentAlarmBinding;
import com.nivodev.nivotools.databinding.ItemAlarmBinding;
import com.nivodev.nivotools.databinding.SheetAlarmEditBinding;
import com.nivodev.nivotools.databinding.SheetConfirmBinding;
import com.nivodev.nivotools.databinding.SheetTimeBinding;
import com.nivodev.nivotools.databinding.SheetTuneListBinding;
import com.nivodev.nivotools.util.AnimUtils;
import com.nivodev.nivotools.util.Haptics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Alarm tool screen: add / edit alarms with TimePicker + ringtone picker
 * (12 bundled tunes plus anything the user imports), on/off switch and
 * long-press delete through a glass confirmation sheet.
 */
public class AlarmFragment extends Fragment {

    private static final int REQ_NOTIF = 4401;

    private FragmentAlarmBinding binding;
    private AlarmAdapter adapter;
    private List<JSONObject> items = new ArrayList<>();

    /** Editor state while the alarm sheet is open. */
    private SheetAlarmEditBinding editor;
    private int editingId = -1;
    private int pickHour = 7;
    private int pickMinute = 30;
    private int pickDays = 0;
    /** 0 = answer the puzzle, 1 = swipe up. */
    private int editMode = 0;
    private String pickTune = AlarmStore.builtKey(0);

    /** Tune preview (listen before choosing). */
    private MediaPlayer preview;
    private MaterialButton previewBtn;
    private String previewKey;

    private final ActivityResultLauncher<String> notifLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
            });

    /** Picks any audio file from the phone and converts it into a custom ringtone. */
    private final ActivityResultLauncher<String[]> tunePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                if (editingId >= 0) {
                    importCustomTune(uri);
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentAlarmBinding.inflate(inflater, container, false);
        adapter = new AlarmAdapter();
        binding.alarmRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.alarmRecycler.setAdapter(adapter);
        binding.alarmRecycler.setHasFixedSize(true);
        AnimUtils.gatePressFeedback(binding.alarmRecycler);

        binding.btnAlarmAdd.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_PRESS);
            openEditor(null);
        });

        load();
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    // ------------------------------------------------------------ list

    private void load() {
        JSONArray arr = AlarmStore.load(requireContext());
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                rows.add(arr.getJSONObject(i));
            } catch (Throwable t) {
            }
        }
        Collections.sort(rows, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int ma = a.optInt("hour", 0) * 60 + a.optInt("minute", 0);
                int mb = b.optInt("hour", 0) * 60 + b.optInt("minute", 0);
                return ma - mb;
            }
        });
        items = rows;
        paint();
    }

    private void paint() {
        if (binding == null) {
            return;
        }
        boolean has = !items.isEmpty();
        binding.alarmEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
        binding.alarmRecycler.setVisibility(has ? View.VISIBLE : View.GONE);
        // visible whenever the ring pop-up is not yet permitted so the
        // full-screen alarm screen can open by itself when it fires
        boolean noPop = AlarmReliability.needsSetup(requireContext());
        binding.reliChip.setVisibility(noPop ? View.VISIBLE : View.GONE);
        binding.reliChip.setOnClickListener(noPop ? v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_CONFIRM);
            AlarmReliability.openSettings(requireActivity());
        } : null);
        adapter.notifyDataSetChanged();
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        for (JSONObject o : items) {
            arr.put(o);
        }
        AlarmStore.save(requireContext(), arr);
    }

    // ------------------------------------------------------------ editor sheet

    private void openEditor(JSONObject existing) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        editor = SheetAlarmEditBinding.inflate(getLayoutInflater());
        sheet.setContentView(editor.getRoot());
        AnimUtils.slideFadeIn(editor.getRoot(), 0);
        editor.sheetTitle.setText(existing != null
                ? R.string.alarm_edit_title : R.string.alarm_add);

        editingId = existing != null ? existing.optInt("id", -1) : -1;
        pickHour = existing != null ? existing.optInt("hour", 7) : 7;
        pickMinute = existing != null ? existing.optInt("minute", 30) : 30;
        pickDays = existing != null ? existing.optInt("days", 0) : 0;
        editMode = existing != null ? existing.optInt("mode", 0) : 0;
        pickTune = existing != null ? existing.optString("tune", AlarmStore.builtKey(0))
                : AlarmStore.builtKey(0);

        editor.sheetAlarmLabel.setText(existing != null ? existing.optString("label", "") : "");
        updateTime();
        updateTuneText();
        fillDaysRow();
        fillModeRow();

        hideSheetKeyboardLater(editor.sheetAlarmLabel, 80L);

        // whole time card opens the clock sheet
        editor.sheetTimeCard.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            showTimeSheet();
        });

        editor.sheetAlarmTune.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            showTuneSheet();
        });

        editor.sheetCancel.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            sheet.dismiss();
            editor = null;
        });

        editor.sheetSave.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_CONFIRM);
            saveAlarm(existing);
            sheet.dismiss();
            editor = null;
        });

        sheet.show();
    }

    private void updateTime() {
        if (editor == null) {
            return;
        }
        editor.sheetAlarmTime.setText(AlarmStore.formatTime(pickHour % 24, pickMinute % 60));
    }

    /** Builds the repeat toggles: full day names, plain bulletproof TextView chips. */
    private void fillDaysRow() {
        if (editor == null) {
            return;
        }
        editor.sheetDaysRow.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        final int fillSel = com.nivodev.nivotools.util.Accent.of(requireContext());
        final int fillIdle = ContextCompat.getColor(requireContext(), R.color.chip_idle);
        final int textSel = ContextCompat.getColor(requireContext(), R.color.light_on_primary);
        final int textIdle = ContextCompat.getColor(requireContext(), R.color.light_on_surface);
        String[][] rows = {
                {"Sunday", "Monday", "Tuesday", "Wednesday"},
                {"Thursday", "Friday", "Saturday"}
        };
        for (String[] row : rows) {
            android.widget.LinearLayout line = new android.widget.LinearLayout(requireContext());
            line.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            for (final String name : row) {
                // Calendar.DAY_OF_WEEK: SUNDAY=1 -> bit 0
                final int bit = java.util.Arrays.asList(AlarmStore.DAY_NAMES_FULL)
                        .indexOf(name);
                final TextView chip = new TextView(requireContext());
                chip.setText(name);
                chip.setTextSize(12f);
                chip.setGravity(Gravity.CENTER);
                chip.setMaxLines(1);
                android.widget.LinearLayout.LayoutParams p =
                        new android.widget.LinearLayout.LayoutParams(0,
                                (int) (38f * d), 1f);
                p.setMargins((int) (3f * d), 0, (int) (3f * d), 0);
                chip.setLayoutParams(p);
                final Runnable style = () -> {
                    boolean sel = ((pickDays >> bit) & 1) != 0;
                    android.graphics.drawable.GradientDrawable g =
                            new android.graphics.drawable.GradientDrawable();
                    g.setCornerRadius(19f * d);
                    g.setColor(sel ? fillSel : fillIdle);
                    chip.setBackground(g);
                    chip.setTextColor(sel ? textSel : textIdle);
                };
                style.run();
                chip.setOnClickListener(v -> {
                    AnimUtils.pressScale(v);
                    Haptics.action(v, Haptics.TYPE_TICK);
                    if (((pickDays >> bit) & 1) != 0) {
                        pickDays &= ~(1 << bit);
                    } else {
                        pickDays |= (1 << bit);
                    }
                    style.run();
                });
                line.addView(chip);
            }
            editor.sheetDaysRow.addView(line);
        }
    }

    /** In-app glass clock: hour grid 1-12, type-in minutes, AM/PM toggle. */
    private void showTimeSheet() {
        final BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        final SheetTimeBinding tp = SheetTimeBinding.inflate(getLayoutInflater());
        sheet.setContentView(tp.getRoot());
        AnimUtils.slideFadeIn(tp.getRoot(), 0);

        // tmp[0] = hour 1..12, tmp[1] = minute, tmp[2] = pm flag
        final int[] tmp = new int[3];
        tmp[0] = pickHour % 12 == 0 ? 12 : pickHour % 12;
        tmp[1] = pickMinute % 60;
        tmp[2] = pickHour >= 12 ? 1 : 0;
        tp.tpAm.setMinimumWidth(0);
        tp.tpPm.setMinimumWidth(0);

        float d = getResources().getDisplayMetrics().density;
        final int fillSel = com.nivodev.nivotools.util.Accent.of(requireContext());
        final int fillIdle = ContextCompat.getColor(requireContext(), R.color.chip_idle);
        final int textSel = ContextCompat.getColor(requireContext(), R.color.light_on_primary);
        final int textIdle = ContextCompat.getColor(requireContext(), R.color.light_on_surface);

        final TextView[] hourBtns = new TextView[13];

        final Runnable stylePmButtons = () -> {
            boolean pm = tmp[2] == 1;
            tp.tpAm.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    pm ? fillIdle : fillSel));
            tp.tpAm.setTextColor(pm ? textIdle : textSel);
            tp.tpPm.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    pm ? fillSel : fillIdle));
            tp.tpPm.setTextColor(pm ? textSel : textIdle);
        };

        final Runnable repaint = () -> {
            boolean pm = tmp[2] == 1;
            tp.tpPreview.setText(AlarmStore.formatTime(
                    (tmp[0] % 12) + (pm ? 12 : 0), tmp[1]));
            tp.tpMinPreview.setText(String.format(Locale.US, "%02d", tmp[1]));
            for (int n = 1; n <= 12; n++) {
                TextView hb = hourBtns[n];
                if (hb == null) {
                    continue;
                }
                boolean sel = tmp[0] == n;
                android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                g.setColor(sel ? fillSel : fillIdle);
                hb.setBackground(g);
                hb.setTextColor(sel ? textSel : textIdle);
            }
            stylePmButtons.run();
        };

        android.view.View.OnClickListener pmClick = v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TICK);
            tmp[2] = tmp[2] == 1 ? 0 : 1;
            repaint.run();
        };
        tp.tpAm.setOnClickListener(pmClick);
        tp.tpPm.setOnClickListener(pmClick);

        for (int n = 1; n <= 12; n++) {
            final int hour = n;
            TextView hb = new TextView(requireContext());
            hb.setText(String.valueOf(n));
            hb.setTextSize(15f);
            hb.setGravity(Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams p =
                    new android.widget.LinearLayout.LayoutParams(
                            (int) (48f * d), (int) (48f * d));
            p.setMargins((int) (4f * d), 0, (int) (4f * d), 0);
            hb.setLayoutParams(p);
            hb.setOnClickListener(v -> {
                AnimUtils.pressScale(v);
                Haptics.action(v, Haptics.TYPE_TICK);
                tmp[0] = hour;
                tmp[1] = 0;
                repaint.run();
            });
            hourBtns[n] = hb;
            tp.tpHourTop.addView(hb);
        }

        String[][] pads = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"DEL", "0", "00"}
        };
        for (int r = 0; r < pads.length; r++) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            for (int cIdx = 0; cIdx < pads[r].length; cIdx++) {
                final String label = pads[r][cIdx];
                TextView kb = new TextView(requireContext());
                kb.setText("DEL".equals(label) ? "\u232B" : label);
                kb.setTextSize(17f);
                kb.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(23f * d);
                bg.setColor(fillIdle);
                kb.setBackground(bg);
                kb.setTextColor(textIdle);
                android.widget.LinearLayout.LayoutParams p =
                        new android.widget.LinearLayout.LayoutParams(
                                (int) (62f * d), (int) (46f * d));
                p.setMargins((int) (3f * d), (int) (3f * d), (int) (3f * d), 0);
                kb.setLayoutParams(p);
                kb.setOnClickListener(v -> {
                    AnimUtils.pressScale(v);
                    try {
                        if ("DEL".equals(label)) {
                            tmp[1] = tmp[1] / 10;
                            Haptics.action(v, Haptics.TYPE_TICK);
                        } else {
                            int digit = Integer.parseInt(label);
                            int next = tmp[1] * 10 + digit;
                            tmp[1] = next > 59 ? digit : next;
                            Haptics.action(v, Haptics.TYPE_TAP);
                        }
                    } catch (Throwable t) {
                        Haptics.action(requireContext(), Haptics.TYPE_ERROR);
                    }
                    repaint.run();
                });
                row.addView(kb);
            }
            tp.tpKeys.addView(row);
        }

        tp.tpCancel.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            sheet.dismiss();
        });
        tp.tpSave.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(requireContext(), Haptics.TYPE_CONFIRM);
            pickHour = (tmp[0] % 12) + (tmp[2] == 1 ? 12 : 0);
            pickMinute = tmp[1] % 60;
            updateTime();
            sheet.dismiss();
        });

        repaint.run();
        sheet.show();
    }

    /** Builds the stop-method choice: Answer / Swipe. Exactly one is active. */
    private void fillModeRow() {
        if (editor == null) {
            return;
        }
        editor.sheetModeRow.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        final int fillSel = com.nivodev.nivotools.util.Accent.of(requireContext());
        final int fillIdle = ContextCompat.getColor(requireContext(), R.color.chip_idle);
        final int textSel = ContextCompat.getColor(requireContext(), R.color.light_on_primary);
        final int textIdle = ContextCompat.getColor(requireContext(), R.color.light_on_surface);
        String[] names = {getString(R.string.stop_by_answer),
                getString(R.string.stop_by_swipe)};
        final TextView[] chips = new TextView[2];
        final Runnable[] styles = new Runnable[2];
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            final TextView chip = new TextView(requireContext());
            chip.setText(names[i]);
            chip.setTextSize(14f);
            chipGravity(chip);
            android.widget.LinearLayout.LayoutParams p =
                    new android.widget.LinearLayout.LayoutParams(0,
                            (int) (44f * d), 1f);
            p.setMargins((int) (3f * d), 0, (int) (3f * d), 0);
            chip.setLayoutParams(p);
            chips[i] = chip;
            final int index = i;
            styles[index] = () -> {
                boolean sel = editMode == index;
                android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(20f * d);
                g.setColor(sel ? fillSel : fillIdle);
                chip.setBackground(g);
                chip.setTextColor(sel ? textSel : textIdle);
            };
            chip.setOnClickListener(v -> {
                AnimUtils.pressScale(v);
                Haptics.action(v, Haptics.TYPE_TICK);
                editMode = mode;
                // refresh BOTH chips so only one looks active at a time
                styles[0].run();
                styles[1].run();
            });
            editor.sheetModeRow.addView(chip);
        }
        styles[0].run();
        styles[1].run();
    }

    private void chipGravity(TextView t) {
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
    }

    private void updateTuneText() {
        if (editor == null) {
            return;
        }
        editor.sheetAlarmTune.setText(getString(R.string.alarm_tune_row) + ": "
                + AlarmStore.tuneTitle(requireContext(), pickTune));
    }

    private void saveAlarm(JSONObject existing) {
        JSONObject a;
        if (existing != null) {
            a = existing;
        } else {
            a = new JSONObject();
            try {
                a.put("id", AlarmStore.nextId(requireContext()));
                a.put("enabled", true);
            } catch (Throwable t) {
                return;
            }
            items.add(a);
        }
        try {
            a.put("hour", pickHour % 24);
            a.put("minute", pickMinute % 60);
            a.put("days", Math.max(0, pickDays) & AlarmStore.DAYS_ALL);
            a.put("mode", editMode == 1 ? 1 : 0);
            a.put("label", editor != null
                    ? editor.sheetAlarmLabel.getText().toString().trim() : "");
            a.put("tune", pickTune);
        } catch (Throwable t) {
            return;
        }
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                return (x.optInt("hour", 0) * 60 + x.optInt("minute", 0))
                        - (y.optInt("hour", 0) * 60 + y.optInt("minute", 0));
            }
        });
        persist();
        applyAlarmState(a);
        try {
            if (a.optBoolean("enabled", true)) {
                AlarmStore.schedule(requireContext(), a.optInt("id", -1),
                        a.optInt("hour", 0), a.optInt("minute", 0), a.optInt("days", 0));
            } else {
                AlarmStore.cancel(requireContext(), a.optInt("id", -1));
            }
        } catch (Throwable t) {
        }
        load();
    }

    /** Requests notification permission and opens exact-alarm settings once. */
    private void applyAlarmState(JSONObject a) {
        if (Build.VERSION.SDK_INT >= 33) {
            String perm = Manifest.permission.POST_NOTIFICATIONS;
            if (ContextCompat.checkSelfPermission(requireContext(), perm)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifLauncher.launch(perm);
            }
        }
        AlarmManager am = (AlarmManager)
                requireContext().getSystemService(Context.ALARM_SERVICE);
        if (am != null && Build.VERSION.SDK_INT >= 31
                && !am.canScheduleExactAlarms()) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + requireActivity().getPackageName())));
            } catch (Throwable t) {
            }
        }
        // guide the user to auto-start / full-screen intent ONCE so the ring
        // screen pops on the full display without being tapped
        try {
            android.content.SharedPreferences p = requireContext()
                    .getSharedPreferences("alarm_store", Context.MODE_PRIVATE);
            boolean showGuide = !p.getBoolean("reli_shown", false)
                    || AlarmReliability.needsSetup(requireContext());
            if (showGuide) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder dlg =
                        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                requireActivity(), R.style.ThemeOverlay_Nivo_AlertDialog);
                dlg.setTitle(R.string.alarm_reli_title);
                dlg.setMessage(R.string.alarm_reli_text);
                dlg.setPositiveButton(R.string.alarm_reli_open,
                        (d, w) -> {
                            p.edit().putBoolean("reli_shown", true).apply();
                            AlarmReliability.openSettings(requireActivity());
                        });
                dlg.setNegativeButton(android.R.string.cancel,
                        (d, w) -> p.edit().putBoolean("reli_shown", true).apply());
                dlg.setOnCancelListener(
                        d -> p.edit().putBoolean("reli_shown", true).apply());
                dlg.show();
            }
        } catch (Throwable t) {
        }
    }

    private void hideSheetKeyboardLater(final EditText target, long delay) {
        target.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
            }
        }, delay);
    }

    // ------------------------------------------------------------ ringtone picker

    private void showTuneSheet() {
        final BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        final SheetTuneListBinding tb = SheetTuneListBinding.inflate(getLayoutInflater());
        sheet.setContentView(tb.getRoot());
        AnimUtils.slideFadeIn(tb.getRoot(), 0);
        sheet.setOnDismissListener(dlg -> stopPreview());
        fillTuneList(sheet, tb);
        sheet.show();
    }

    private void fillTuneList(final BottomSheetDialog sheet, final SheetTuneListBinding tb) {
        tb.tuneList.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < AlarmStore.TUNE_NAMES.length; i++) {
            final String key = AlarmStore.builtKey(i);
            tb.tuneList.addView(tuneRow(sheet, AlarmStore.TUNE_NAMES[i], key));
        }
        try {
            JSONArray customs = new JSONArray(requireContext()
                    .getSharedPreferences("alarm_store", Context.MODE_PRIVATE)
                    .getString("customs", "[]"));
            for (int i = 0; i < customs.length(); i++) {
                JSONObject o = customs.getJSONObject(i);
                final String key = "file:" + o.optString("file");
                tb.tuneList.addView(tuneRow(sheet, o.optString("name", "Custom"), key));
            }
        } catch (Throwable t) {
        }
        MaterialButton add = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        add.setText(getString(R.string.custom_tune_add));
        add.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_add));
        add.setTextColor(com.nivodev.nivotools.util.Accent.of(requireContext()));
        add.setCornerRadius((int) (20f * d));
        add.setInsetTop(0);
        add.setInsetBottom(0);
        add.setMinimumHeight((int) (52f * d));
        android.widget.LinearLayout.LayoutParams p =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins((int) (4f * d), (int) (4f * d), (int) (4f * d), (int) (4f * d));
        add.setLayoutParams(p);
        add.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            try {
                sheet.dismiss();
            } catch (Throwable t) {
            }
            try {
                tunePicker.launch(new String[]{"audio/*"});
            } catch (Throwable t) {
                Haptics.action(requireContext(), Haptics.TYPE_ERROR);
            }
        });
        tb.tuneList.addView(add);
    }

    /** Name button + play preview button side by side. */
    private View tuneRow(final BottomSheetDialog sheet, String name, final String key) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        MaterialButton label = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        label.setText(name);
        label.setIcon(ContextCompat.getDrawable(requireContext(),
                key.equals(pickTune) ? R.drawable.ic_clock : R.drawable.ic_music));
        label.setTextColor(ContextCompat.getColor(requireContext(), R.color.light_on_surface));
        label.setCornerRadius((int) (20f * d));
        label.setInsetTop(0);
        label.setInsetBottom(0);
        label.setMinimumHeight((int) (52f * d));
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins((int) (4f * d), (int) (4f * d), 0, (int) (4f * d));
        label.setLayoutParams(lp);
        label.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            pickTune = key;
            updateTuneText();
            try {
                sheet.dismiss();
            } catch (Throwable t) {
            }
        });
        row.addView(label);

        final MaterialButton play = new MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        play.setIcon(ContextCompat.getDrawable(requireContext(), R.drawable.ic_play));
        play.setIconTint(android.content.res.ColorStateList.valueOf(
                com.nivodev.nivotools.util.Accent.of(requireContext())));
        play.setCornerRadius((int) (20f * d));
        play.setInsetTop(0);
        play.setInsetBottom(0);
        play.setMinimumHeight((int) (52f * d));
        play.setMinimumWidth((int) (64f * d));
        android.widget.LinearLayout.LayoutParams pp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        (int) (52f * d));
        pp.setMargins((int) (4f * d), (int) (4f * d), (int) (4f * d), (int) (4f * d));
        play.setLayoutParams(pp);
        play.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            if (key.equals(previewKey) && preview != null) {
                stopPreview();
                return;
            }
            if (startPreview(key)) {
                stopPreviewIconOnly();
                previewBtn = play;
                previewBtn.setIcon(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_pause));
                Haptics.action(v, Haptics.TYPE_TAP);
            } else {
                Haptics.action(requireContext(), Haptics.TYPE_ERROR);
            }
        });
        row.addView(play);
        return row;
    }

    /** Starts the preview player; false when the tune cannot be opened. */
    private boolean startPreview(String key) {
        stopPreview();
        try {
            MediaPlayer mp;
            File f = AlarmStore.file(requireContext(), key);
            if (f != null && f.isFile() && f.canRead()) {
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mp.setDataSource(f.getAbsolutePath());
                mp.prepare();
            } else {
                mp = MediaPlayer.create(requireContext(),
                        AlarmStore.rawRes(requireContext(), key));
            }
            if (mp == null) {
                return false;
            }
            mp.setOnCompletionListener(m -> stopPreview());
            mp.start();
            preview = mp;
            previewKey = key;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Releases the player and restores icons. */
    private void stopPreview() {
        if (preview != null) {
            try {
                preview.stop();
            } catch (Throwable t) {
            }
            preview = null;
        }
        previewKey = null;
        stopPreviewIconOnly();
    }

    private void stopPreviewIconOnly() {
        if (previewBtn != null) {
            previewBtn.setIcon(ContextCompat.getDrawable(requireContext(),
                    R.drawable.ic_play));
            previewBtn = null;
        }
    }

    /** Copies the picked audio into internal storage and registers it. */
    private void importCustomTune(Uri uri) {
        try {
            File tmp = new File(requireContext().getFilesDir(), "incoming.audio");
            InputStream in = requireContext().getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IllegalStateException();
            }
            OutputStream out = requireContext().openFileOutput("incoming.audio",
                    Context.MODE_PRIVATE);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            out.close();
            in.close();
            if (total == 0) {
                throw new IllegalStateException();
            }
            String key = AlarmStore.addCustom(requireContext(), queryName(uri));
            File target = AlarmStore.file(requireContext(), key);
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(target);
            pickTune = key;
            updateTuneText();
            Haptics.action(requireContext(), Haptics.TYPE_CONFIRM);
        } catch (Throwable t) {
            Haptics.action(requireContext(), Haptics.TYPE_ERROR);
        }
    }

    private String queryName(Uri uri) {
        try {
            android.database.Cursor c = requireContext().getContentResolver()
                    .query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                            null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String name = c.getString(0);
                        if (name != null) {
                            int cut = name.lastIndexOf('.');
                            return cut > 0 ? name.substring(0, cut) : name;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
        }
        return null;
    }

    // ------------------------------------------------------------ delete sheet

    private void confirmDelete(final JSONObject a) {
        final BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        final SheetConfirmBinding cb = SheetConfirmBinding.inflate(getLayoutInflater());
        sheet.setContentView(cb.getRoot());
        AnimUtils.slideFadeIn(cb.getRoot(), 0);
        cb.sheetTitle.setText(R.string.alarm_delete_title);
        cb.sheetBody.setText(R.string.alarm_delete_body);
        cb.sheetCancel.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            sheet.dismiss();
        });
        cb.sheetDanger.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_ERROR);
            items.remove(a);
            AlarmStore.cancel(requireContext(), a.optInt("id", -1));
            persist();
            load();
            sheet.dismiss();
        });
        sheet.show();
    }

    // ------------------------------------------------------------ adapter

    private class AlarmAdapter extends RecyclerView.Adapter<AlarmVH> {

        @NonNull
        @Override
        public AlarmVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new AlarmVH(ItemAlarmBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull AlarmVH h, int position) {
            final JSONObject a = items.get(position);
            int hour = a.optInt("hour", 0);
            int minute = a.optInt("minute", 0);
            h.b.alarmTime.setText(AlarmStore.formatTime(hour, minute));
            String label = a.optString("label", "");
            h.b.alarmLabel.setText(label.isEmpty() ? getString(R.string.menu_alarm) : label);
            h.b.alarmDays.setText(AlarmStore.daySummary(requireContext(),
                    a.optInt("days", 0)));
            h.b.alarmTune.setText(AlarmStore.tuneTitle(requireContext(),
                    a.optString("tune", "raw:tune_1")));
            h.b.alarmSwitch.setOnCheckedChangeListener(null);
            boolean enabled = a.optBoolean("enabled", false);
            h.b.alarmSwitch.setChecked(enabled);
            float dim = enabled ? 1f : 0.55f;
            h.b.alarmTime.setAlpha(dim);
            h.b.alarmLabel.setAlpha(dim);
            h.b.alarmDays.setAlpha(dim);
            h.b.alarmTune.setAlpha(enabled ? 1f : 0.55f);
            h.b.alarmSwitch.setOnCheckedChangeListener((btn, on) -> {
                Haptics.action(btn, on ? Haptics.TYPE_CONFIRM : Haptics.TYPE_TICK);
                if (on) {
                    applyAlarmState(a);
                }
                try {
                    a.put("enabled", on);
                } catch (Throwable t) {
                }
                if (on) {
                    AlarmStore.schedule(requireContext(), a.optInt("id", -1), hour, minute,
                            a.optInt("days", 0));
                } else {
                    AlarmStore.cancel(requireContext(), a.optInt("id", -1));
                }
                persist();
                load();
            });
            AnimUtils.pressScale(h.b.getRoot());
            h.b.getRoot().setOnClickListener(v ->
                    openEditor(a));
            h.b.getRoot().setOnLongClickListener(v -> {
                confirmDelete(a);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class AlarmVH extends RecyclerView.ViewHolder {

        final ItemAlarmBinding b;

        AlarmVH(ItemAlarmBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}
