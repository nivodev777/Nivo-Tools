
/* Music library screen: scan, sort, search, sleep timer and the
   now-playing sheet. Talks to MusicService through static helpers. */

package com.nivodev.nivotools.ui.music;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.FragmentMusicBinding;
import com.nivodev.nivotools.util.AnimUtils;
import com.nivodev.nivotools.util.Haptics;

import java.util.List;

public class MusicFragment extends Fragment implements MusicPlayer.Listener {

    private static final String PERM_MEDIA = "android.permission.READ_MEDIA_AUDIO";
    private static final String PERM_LEGACY = Manifest.permission.READ_EXTERNAL_STORAGE;
    private static final String PERM_NOTIF = "android.permission.POST_NOTIFICATIONS";

    private FragmentMusicBinding binding;
    private MusicViewModel model;
    private MusicAdapter adapter;
    private boolean loading;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (binding == null) {
                return;
            }
            boolean live = MusicPlayer.get().hasCurrent()
                    && MusicPlayer.get().isPlaying();
            if (!live) {
                // nothing playing: slow the poll instead of busy 250ms work
                handler.postDelayed(this, 900);
                return;
            }
            long dur = MusicPlayer.get().duration();
            if (dur > 0) {
                binding.musicSeek.setMax((int) dur);
                binding.musicSeek.setProgress((int) MusicPlayer.get().position());
            }
            handler.postDelayed(this, 250);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                if (hasAudioPermission(requireContext())) {
                    loadSongs();
                } else {
                    refreshUi();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMusicBinding.inflate(inflater, container, false);
        model = new ViewModelProvider(this).get(MusicViewModel.class);

        adapter = new MusicAdapter(song -> {
            androidx.navigation.NavController nc =
                    androidx.navigation.Navigation.findNavController(binding.getRoot());
            if (nc.getCurrentDestination() == null
                    || nc.getCurrentDestination().getId() != R.id.nav_now_playing) {
                Bundle args = new Bundle();
                args.putParcelable("song", song);
                nc.navigate(R.id.nav_now_playing, args);
            }
            MusicService.play(requireContext(), song);
        });
        adapter.longPress = song -> showSongMenu(song);
        // deadscroll flicks should not buzz list rows
        AnimUtils.gatePressFeedback(binding.musicRecycler);
        binding.musicRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.musicRecycler.setAdapter(adapter);
        binding.musicRecycler.setHasFixedSize(true);
        binding.musicRecycler.setItemViewCacheSize(24);

        setupSearch();

        binding.musicPlayer.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicPlayer player = MusicPlayer.get();
            if (!player.hasCurrent()) {
                return;
            }
            List<Song> list = MusicLibrary.cachedSongs();
            Song target = null;
            long curId = player.current().id;
            for (Song s : list) {
                if (s.id == curId) {
                    target = s;
                    break;
                }
            }
            androidx.navigation.NavController nc =
                    androidx.navigation.Navigation.findNavController(binding.getRoot());
            if (nc.getCurrentDestination() == null
                    || nc.getCurrentDestination().getId() != R.id.nav_now_playing) {
                Bundle args = new Bundle();
                if (target != null) {
                    args.putParcelable("song", target);
                }
                nc.navigate(R.id.nav_now_playing, args);
            }
        });

        model.getSongs().observe(getViewLifecycleOwner(), new Observer<List<Song>>() {
            @Override
            public void onChanged(List<Song> songs) {
                // fresh scan se list aayi - CURRENT search text ke sath
                // resubmit karo (null full-list resubmit stale dikha sakti hai)
                CharSequence q = binding.inputSearch.getText();
                applyFilter(q == null ? null : q.toString());
                refreshUi();
            }
        });

        binding.btnGrant.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            requestPermissions();
        });

        binding.btnToggle.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicPlayer player = MusicPlayer.get();
            if (player.hasCurrent()) {
                MusicService.play(requireContext(), player.current());
            } else {
                List<Song> list = model.getSongs().getValue();
                if (list != null && !list.isEmpty()) {
                    MusicService.play(requireContext(), list.get(0));
                }
            }
        });

        binding.btnNext.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicService.step(requireContext(), 1);
        });

        binding.btnPrev.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicService.step(requireContext(), -1);
        });

        binding.musicSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                MusicPlayer.get().seekTo(seekBar.getProgress());
            }
        });

                // live MediaStore sync: songs deleted/imported by any other app
                // refresh the list right away (no app restart) - listener
                // lives as long as the fragment view does
                storeObserver = new android.database.ContentObserver(
                        new android.os.Handler(android.os.Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange, android.net.Uri uri) {
                        if (binding != null && isAdded()
                                && hasAudioPermission(requireContext())) {
                            loadSongs();
                        }
                    }
                };
                try {
                    requireContext().getContentResolver().registerContentObserver(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            true, storeObserver);
                } catch (Throwable t) {
                }

                return binding.getRoot();
            }

    @Override
    public void onResume() {
        super.onResume();
        if (hasAudioPermission(requireContext())) {
            // rescan on EVERY return to this screen: deleted songs drop,
            // newly copied files appear - no full app restart needed
            loadSongs();
        }
        refreshUi();
    }

    // ------------------------------------------------------------ search

    /** Opens the toolbar-triggered search bar and focuses the input. */
    public void openSearch() {
        if (binding == null) {
            return;
        }
        binding.searchBar.setVisibility(View.VISIBLE);
        binding.searchBar.setAlpha(0f);
        binding.searchBar.setTranslationY(-12f);
        binding.searchBar.animate().alpha(1f).translationY(0f).setDuration(170).start();
        float d = getResources().getDisplayMetrics().density;
        binding.musicRecycler.animate().translationY(62f * d).setDuration(200).start();
        binding.inputSearch.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(binding.inputSearch, 0);
        }
    }

    private void closeSearch() {
        if (binding == null) {
            return;
        }
        boolean hadText = binding.inputSearch.getText() != null
                && binding.inputSearch.getText().length() > 0;
        float d = getResources().getDisplayMetrics().density;
        binding.musicRecycler.animate().translationY(0f).setDuration(200).start();
        binding.searchBar.animate().alpha(0f).translationY(-12f).setDuration(150)
                .withEndAction(() -> {
                    if (binding != null) {
                        binding.searchBar.setVisibility(View.GONE);
                    }
                })
                .start();
        binding.inputSearch.clearFocus();
        if (hadText) {
            binding.inputSearch.setText("");
        } else {
            applyFilter("");
        }
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.inputSearch.getWindowToken(), 0);
        }
    }

    /** Filters the visible list by title or artist. */
    private void applyFilter(String raw) {
        if (model == null) {
            return;
        }
        List<Song> all = model.getSongs().getValue();
        if (all == null) {
            return;
        }
        String q = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.US);
        if (q.isEmpty()) {
            adapter.submitList(all);
            return;
        }
        List<Song> out = new java.util.ArrayList<>();
        for (Song s : all) {
            boolean hit = (s.title != null && s.title.toLowerCase(java.util.Locale.US).contains(q))
                    || (s.artist != null && s.artist.toLowerCase(java.util.Locale.US).contains(q));
            if (hit) {
                out.add(s);
            }
        }
        adapter.submitList(out);
    }

    private void setupSearch() {
        binding.btnSearchClose.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Haptics.action(v, Haptics.TYPE_TAP);
            closeSearch();
        });
        binding.inputSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(@androidx.annotation.NonNull android.text.Editable s) {
                applyFilter(s.toString());
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        MusicPlayer.get().setListener(this);
        handler.post(tick);
    }

    @Override
    public void onStop() {
        MusicPlayer.get().setListener(null);
        handler.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    public void onPause() {
        super.onPause();
        // remember current track + position for next launch
        if (MusicPlayer.get().hasCurrent()) {
            MusicStore.saveLast(requireContext(),
                    MusicPlayer.get().current(), MusicPlayer.get().position());
        }
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(tick);
        try {
            if (storeObserver != null) {
                requireContext().getContentResolver()
                        .unregisterContentObserver(storeObserver);
                storeObserver = null;
            }
        } catch (Throwable t) {
        }
        binding = null;
        super.onDestroyView();
    }

    /** MediaStore change watcher: files added/deleted by other apps fire here */
    private android.database.ContentObserver storeObserver;

    /** fresh device scan - also used by the toolbar rescan action */
    /** transient overlay chip that animates while a rescan is running */
    private android.widget.TextView rescanChip;
    /** true while the user drags the mini-player seek thumb. */
    private boolean userSeeking;

    /** fresh device scan (toolbar refresh + permission grant + resume) */
    public void loadSongs() {
        if (loading) {
            return;
        }
        loading = true;
        animateRescanStart();
        MusicLibrary.load(requireContext(), songs -> {
            loading = false;
            if (model != null) {
                model.setSongs(songs);
            }
            if (binding != null) {
                animateRescanEnd();
            }
            if (isAdded()) {
                maybeRestoreLast(songs);
            }
            if (binding != null) {
                playFirstStagger();
            }
            refreshUi();
        });
        refreshUi();
    }

    private boolean lastTriedOnce;

    /** Restore the persisted last track once per session, paused. */
    private boolean firstStaggerDone;

    /** replay the list stagger once per session (not on every refresh) */
    private void playFirstStagger() {
        if (firstStaggerDone || binding == null) {
            return;
        }
        firstStaggerDone = true;
        binding.musicRecycler.post(() -> {
            if (binding != null) {
                binding.musicRecycler.scheduleLayoutAnimation();
            }
        });
    }

    private void maybeRestoreLast(java.util.List<Song> songs) {
        if (lastTriedOnce || MusicPlayer.get().hasCurrent()
                || songs == null || songs.isEmpty()) {
            return;
        }
        lastTriedOnce = true;
        long[] pos = new long[1];
        Song last = MusicStore.readLast(requireContext(), pos);
        if (last == null || pos[0] <= 0) {
            return;
        }
        for (Song sng : songs) {
            if (sng.id == last.id) {
                MusicPlayer.get().restoreLast(requireContext(), sng, pos[0]);
                return;
            }
        }
    }

    /**
     * Visible refresh motion: a small "scanning..." chip fades in at the
     * top and the list crossfades + replays the stagger animation, so the
     * toolbar refresh press always feels alive.
     */
    private void animateRescanStart() {
        if (binding == null || rescanChip != null) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        android.view.LayoutInflater li =
                android.view.LayoutInflater.from(requireContext());
        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp =
                new androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
        lp.leftToLeft = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
        lp.rightToRight = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
        lp.topMargin = (int) (8f * d);
        android.widget.TextView chip = (android.widget.TextView) li.inflate(
                R.layout.item_rescan_chip, null, false);
        binding.getRoot().addView(chip, lp);
        chip.setAlpha(0f);
        chip.setTranslationY(-8f * d);
        chip.animate().alpha(1f).translationY(0f).setDuration(140).start();
        rescanChip = chip;

        // list pulse: dim + replay stagger for feedback
        binding.musicRecycler.animate()
                .alpha(0.35f).setDuration(120).start();
    }

    private void animateRescanEnd() {
        float d = getResources().getDisplayMetrics().density;
        if (rescanChip != null) {
            android.view.View chip = rescanChip;
            chip.animate().alpha(0f).translationY(-8f * d).setDuration(160)
                    .withEndAction(() -> {
                        if (chip.getParent() instanceof
                                android.view.ViewGroup) {
                            ((android.view.ViewGroup) chip.getParent())
                                    .removeView(chip);
                        }
                    }).start();
            rescanChip = null;
        }
        if (binding != null) {
            // release the dim and replay the list animation
            binding.musicRecycler.animate().alpha(1f).setDuration(160).start();
            binding.musicRecycler.scheduleLayoutAnimation();
        }
    }

    private void requestPermissions() {
        String[] request = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? new String[]{PERM_MEDIA, PERM_NOTIF}
                : new String[]{PERM_LEGACY};
        permissionLauncher.launch(request);
    }

    private static boolean hasAudioPermission(Context context) {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? PERM_MEDIA : PERM_LEGACY;
        return ContextCompat.checkSelfPermission(context, perm)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshUi() {
        if (binding == null) {
            return;
        }
        boolean granted = hasAudioPermission(requireContext());
        List<Song> songs = model.getSongs().getValue();

        if (!granted) {
            binding.musicPermission.setVisibility(View.VISIBLE);
            binding.musicEmpty.setVisibility(View.GONE);
            binding.musicRecycler.setVisibility(View.GONE);
            binding.musicPlayer.setVisibility(View.GONE);
            return;
        }

        binding.musicPermission.setVisibility(View.GONE);
        if (songs == null || songs.isEmpty()) {
            binding.musicEmpty.setVisibility(View.VISIBLE);
            binding.musicRecycler.setVisibility(View.GONE);
            binding.musicPlayer.setVisibility(View.GONE);
        } else {
            binding.musicEmpty.setVisibility(View.GONE);
            binding.musicRecycler.setVisibility(View.VISIBLE);
            syncPlayer();
        }
    }

    /** Paints the mini player from the shared MusicPlayer state. */
    private void syncPlayer() {
        if (binding == null) {
            return;
        }
        MusicPlayer player = MusicPlayer.get();
        Song current = player.current();
        boolean has = current != null;
        if (userSeeking) {
            // the user is dragging the bar - do not snap it back live
            return;
        }
        binding.musicPlayer.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) {
            return;
        }
        boolean playing = player.isPlaying();
        binding.textNowTitle.setText(current.title);
        binding.textNowArtist.setText(current.artist);
        binding.btnToggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        long dur = player.duration();
        if (dur > 0) {
            binding.musicSeek.setMax((int) dur);
            binding.musicSeek.setProgress((int) player.position());
        }
        adapter.refreshPlayingState();
    }

    @Override
    public void onMusicStateChanged() {
        if (binding != null) {
            syncPlayer();
        }
    }

    private static final int REQ_DELETE = 7411;

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE && binding != null) {
            loadSongs();
        }
    }

    /** Programmatic sleep timer chooser: off / 15 / 30 / 60 / end of track. */
    public void showSleepSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.glass_panel);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, (int) (26 * getResources().getDisplayMetrics().density));

        TextView title = new TextView(requireContext());
        title.setText(R.string.action_sleep);
        title.setTextColor(getResources().getColor(
                com.nivodev.nivotools.R.color.glass_on_light, null));
        title.setTextSize(17f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (52
                * getResources().getDisplayMetrics().density));
        rowLp.topMargin = (int) (6 * getResources().getDisplayMetrics().density);

        int[] labels = {R.string.sleep_off, R.string.sleep_15, R.string.sleep_30,
                R.string.sleep_60, R.string.sleep_track};
        final long[] minutes = {0, 15, 30, 60, -1};
        for (int i = 0; i < labels.length; i++) {
            TextView row = new TextView(requireContext());
            row.setText(labels[i]);
            row.setTextColor(getResources().getColor(
                    com.nivodev.nivotools.R.color.glass_on_light, null));
            row.setTextSize(15f);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.glass_panel_small);
            row.setPadding(pad, 0, pad, 0);
            final long mins = minutes[i];
            row.setOnClickListener(v -> {
                AnimUtils.pressScale(v);
                MusicPlayer p = MusicPlayer.get();
                p.init(requireContext());
                if (mins < 0) {
                    // sleep after the CURRENT track finishes: arm a timer
                    // covering the remaining time if one is playing
                    long left = Math.max(1, (p.duration() - p.position()) / 60_000L);
                    p.setSleepTimer(Math.max(1, left));
                } else if (mins == 0) {
                    p.cancelSleepTimer();
                    row.setTextColor(getResources().getColor(
                            com.nivodev.nivotools.R.color.glass_on_light, null));
                } else {
                    p.setSleepTimer(mins);
                }
                boolean armed = p.isSleepPending();
                showToastish(armed ? R.string.sleep_applied : R.string.sleep_cleared);
                sheet.dismiss();
            });
            content.addView(row, rowLp);
        }
        sheet.setContentView(content);
        AnimUtils.staggerChildren((android.view.ViewGroup) content, 45L);
        sheet.show();
    }

    private void showToastish(int resId) {
        try {
            android.widget.Toast.makeText(requireContext(), resId,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void showSongMenu(Song song) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(R.layout.sheet_song_options, null);
        sheet.setContentView(content);
        AnimUtils.staggerChildren((android.view.ViewGroup) content, 45L);
        com.google.android.material.button.MaterialButton favBtn =
                content.findViewById(R.id.sheet_fav);
        boolean fav = MusicStore.isFavorite(requireContext(), song.id);
        favBtn.setText(fav
                ? R.string.song_option_fav_on : R.string.song_option_fav);
        favBtn.setIconResource(fav
                ? R.drawable.ic_heart_on : R.drawable.ic_heart_off);
        favBtn.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicStore.setFavorite(requireContext(), song.id, !fav);
            sheet.dismiss();
        });
        content.findViewById(R.id.sheet_playnext).setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
            MusicPlayer p = MusicPlayer.get();
            p.init(requireContext());
            p.setNextUp(song);
        });
        content.findViewById(R.id.sheet_info).setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
            showSongInfo(song);
        });
        content.findViewById(R.id.sheet_rename).setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
            promptRename(song);
        });
        content.findViewById(R.id.sheet_delete).setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
            confirmDelete(song);
        });
        sheet.show();
    }

    private void showSongInfo(Song song) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(R.layout.sheet_song_info, null, false);
        sheet.setContentView(content);
        AnimUtils.staggerChildren((android.view.ViewGroup) content, 45L);
        TextView body = content.findViewById(R.id.song_info_body);
        content.<TextView>findViewById(R.id.song_info_name).setText(
                song.name != null ? song.name : song.title);
        body.setText(getString(R.string.info_name_label) + " " + (song.name != null ? song.name : song.title)
                + "\n" + getString(R.string.info_artist_label) + " " + song.artist
                + "\n" + getString(R.string.info_duration_label) + " "
                + MusicAdapter.format(song.duration)
                + "\n" + getString(R.string.info_size_label) + " "
                + String.format(java.util.Locale.US, "%.1f MB", song.size / 1048576f)
                + "\n" + getString(R.string.info_path_label) + " " + (song.path != null ? song.path : "-"));
        content.findViewById(R.id.sheet_close).setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
        });
        sheet.show();
    }

    private void promptRename(Song song) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(R.layout.sheet_rename, null, false);
        sheet.setContentView(content);
        AnimUtils.staggerChildren((android.view.ViewGroup) content, 45L);
        EditText input = content.findViewById(R.id.song_rename_input);
        input.setText(song.title);
        input.setSelection(input.getText().length());
        input.requestFocus();
        content.findViewById(R.id.sheet_cancel).setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
        });
        content.findViewById(R.id.sheet_save).setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            String newName = input.getText().toString().trim();
            sheet.dismiss();
            if (!newName.isEmpty() && MusicLibrary.rename(requireContext(), song, newName)) {
                loadSongs();
            }
        });
        sheet.show();
    }

    private void confirmDelete(Song song) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        View content = getLayoutInflater().inflate(R.layout.sheet_delete, null, false);
        sheet.setContentView(content);
        AnimUtils.staggerChildren((android.view.ViewGroup) content, 45L);
        TextView body = content.findViewById(R.id.song_delete_body);
        body.setText(getString(R.string.delete_song_body, song.title));
        content.findViewById(R.id.sheet_cancel).setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
        });
        content.findViewById(R.id.sheet_delete_confirm).setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            sheet.dismiss();
            deleteSong(song);
        });
        sheet.show();
    }

    private void deleteSong(Song song) {
        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
            if (Build.VERSION.SDK_INT >= 29) {
                PendingIntent pi = MediaStore.createDeleteRequest(
                        requireActivity().getContentResolver(),
                        java.util.Collections.singletonList(uri));
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0, null);
            } else {
                MusicLibrary.deleteDirect(requireContext(), song);
                loadSongs();
            }
        } catch (Throwable t) {
            loadSongs();
        }
    }
}
