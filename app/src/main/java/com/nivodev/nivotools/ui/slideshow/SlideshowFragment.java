package com.nivodev.nivotools.ui.slideshow;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.FragmentSettingsBinding;
import com.nivodev.nivotools.util.AnimUtils;
import com.nivodev.nivotools.util.Prefs;

import java.io.File;
import java.util.Locale;

public class SlideshowFragment extends Fragment {

    /* Iterative dir walk: no recursion depth limit. Failures are skipped,
     * never thrown. */
    private static long dirSize(File dir) {
        long total = 0L;
        if (dir == null || !dir.isDirectory() || !dir.canRead()) {
            return 0L;
        }
        File[] stack = new File[64];
        int top = 0;
        stack[top++] = dir;
        while (top > 0) {
            File cur = stack[--top];
            File[] kids = cur.listFiles();
            if (kids == null) {
                continue;
            }
            for (File k : kids) {
                if (k.isDirectory()) {
                    if (top < stack.length - 1) {
                        stack[top++] = k;
                    }
                } else {
                    total += k.length();
                }
            }
        }
        return total;
    }

    /* Delete the contents of dir (not dir itself), depth-first. A locked
     * file or failure marks ok=false but never aborts the whole cleanup. */
    private static void wipeDir(File dir, boolean[] ok) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            try {
                if (k.isDirectory()) {
                    wipeDir(k, ok);
                }
                ok[0] &= k.delete();
            } catch (Throwable t) {
                ok[0] = false;
            }
        }
    }

    private FragmentSettingsBinding binding;
    private boolean clearing;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        binding.rowThemeLight.setOnClickListener(v -> applyTheme(v, Prefs.THEME_LIGHT));
        binding.rowThemeDark.setOnClickListener(v -> applyTheme(v, Prefs.THEME_DARK));
        binding.rowThemeSystem.setOnClickListener(v -> applyTheme(v, Prefs.THEME_SYSTEM));

        binding.switchVibration.setChecked(Prefs.isHapticsEnabled(requireContext()));
        binding.switchVibration.setOnCheckedChangeListener((buttonView, isChecked) ->
                Prefs.setHapticsEnabled(requireContext(), isChecked));
        binding.rowVibration.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            binding.switchVibration.setChecked(!binding.switchVibration.isChecked());
        });

        syncThemeRadios();

        binding.btnClearCache.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            if (clearing) {
                return;
            }
            confirmClearCache();
        });
        refreshCacheSize();
    }

    @Override
    public void onResume() {
        super.onResume();
        syncThemeRadios();
        refreshCacheSize();
    }

    /* Async cache size: cache walk on a background thread, result posted
     * back only if the view is still alive. Never blocks the main thread. */
    private void refreshCacheSize() {
        if (binding == null || clearing) {
            return;
        }
        binding.textStorageValue.setText(R.string.storage_cache_unset);
        final File[] caches = new File[]{
                requireContext().getCacheDir(), requireContext().getExternalCacheDir()};
        new Thread(() -> {
            long total = 0L;
            for (File c : caches) {
                total += dirSize(c);
            }
            final long n = Math.max(0L, total);
            if (getActivity() == null || binding == null) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                binding.textStorageValue.setText(humanSize(n));
            });
        }, "nivo-cache-size").start();
    }

    /** Compact "human" byte count for labels. */
    private static String humanSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double v = bytes;
        String[] units = {"KB", "MB", "GB"};
        for (String u : units) {
            v /= 1024.0;
            if (v < 1024.0) {
                return String.format(Locale.US, "%.1f %s", v, u);
            }
        }
        return String.format(Locale.US, "%.1f TB", v / 1024.0);
    }

    /* Synchronous cache collect + wipe off-thread; the dialog only needs
     * the size hint, computed on the same worker to avoid a stale value. */
    private void confirmClearCache() {
        if (binding == null || !isAdded()) {
            return;
        }
        binding.textStorageValue.setText(R.string.storage_cache_unset);
        final File[] caches = new File[]{
                requireContext().getCacheDir(), requireContext().getExternalCacheDir()};
        new Thread(() -> {
            long total = 0L;
            for (File c : caches) {
                total += dirSize(c);
            }
            final long n = Math.max(0L, total);
            if (getActivity() == null || !isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (binding == null || !isAdded()) {
                    return;
                }
                String hint = humanSize(n);
                new MaterialAlertDialogBuilder(requireActivity(),
                        R.style.ThemeOverlay_Nivo_AlertDialog)
                        .setTitle(R.string.storage_clear_title)
                        .setMessage(getString(R.string.storage_clear_msg, hint))
                        .setPositiveButton(R.string.storage_clear_yes,
                                (d, w) -> runClear(caches))
                        .setNegativeButton(R.string.storage_clear_no, null)
                        .show();
            });
        }, "nivo-cache-size").start();
    }

    /* Wipes cache dir contents on a worker thread. Reader temp copies live
     * here too - deleting them mid-open is safe (the open file handle keeps
     * working; the reader re-copies on the next document). User data lives
     * in SharedPreferences, which this never touches. */
    private void runClear(File[] caches) {
        if (binding == null) {
            return;
        }
        clearing = true;
        binding.btnClearCache.setEnabled(false);
        binding.btnClearCache.setText(R.string.storage_clearing);
        new Thread(() -> {
            boolean[] ok = {true};
            for (File c : caches) {
                wipeDir(c, ok);
            }
            final boolean success = ok[0];
            if (getActivity() == null || !isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                clearing = false;
                if (binding == null) {
                    return;
                }
                binding.btnClearCache.setEnabled(true);
                binding.btnClearCache.setText(R.string.storage_clear);
                binding.textStorageValue.setText(success
                        ? humanSize(0L) : getString(R.string.storage_failed));
                if (success) {
                    android.widget.Toast.makeText(requireActivity(),
                            R.string.storage_cleared,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                refreshCacheSize();
            });
        }, "nivo-cache-clear").start();
    }

    private void applyTheme(View row, int mode) {
        AnimUtils.pressScale(row);
        Prefs.setThemeMode(requireContext(), mode);
        syncThemeRadios();
        AppCompatDelegate.setDefaultNightMode(Prefs.nightModeFor(mode));
    }

    private void syncThemeRadios() {
        if (binding == null) {
            return;
        }
        int mode = Prefs.getThemeMode(requireContext());
        binding.radioLight.setChecked(mode == Prefs.THEME_LIGHT);
        binding.radioDark.setChecked(mode == Prefs.THEME_DARK);
        binding.radioSystem.setChecked(mode == Prefs.THEME_SYSTEM);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
