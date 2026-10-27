package com.nivodev.nivotools.ui.music;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.databinding.FragmentNowPlayingBinding;
import com.nivodev.nivotools.util.AnimUtils;

/** Full-screen glass music player with artwork, live seek and system-synced controls. */
public class NowPlayingFragment extends Fragment implements MusicPlayer.Listener {

    private FragmentNowPlayingBinding binding;
    private Song screenSong;
    private boolean userSeeking;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (binding == null || userSeeking) {
                handler.postDelayed(this, 250);
                return;
            }
            if (!MusicPlayer.get().hasCurrent()
                    || !MusicPlayer.get().isPlaying()) {
                // paused/idle state: gentle polling only
                handler.postDelayed(this, 900);
                return;
            }
            long dur = MusicPlayer.get().duration();
            if (dur > 0) {
                binding.npSeek.setMax((int) dur);
                binding.npSeek.setProgress((int) MusicPlayer.get().position());
                binding.npTimeElapsed.setText(formatTime(MusicPlayer.get().position()));
                binding.npTimeTotal.setText(formatTime(dur));
            }
            handler.postDelayed(this, 250);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentNowPlayingBinding.inflate(inflater, container, false);


        if (getArguments() != null) {
            Song arg = (Song) getArguments().getParcelable("song");
            if (arg != null) {
                screenSong = arg;
            }
        }
        if (screenSong == null) {
            screenSong = MusicPlayer.get().current();
        }

        binding.npSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
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

        binding.btnNpToggle.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicPlayer.get().toggleCurrent();
        });
        binding.btnNpNext.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicService.step(requireContext(), 1);
        });
        binding.btnNpPrev.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicService.step(requireContext(), -1);
        });

        binding.btnNpRepeat.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicPlayer p = MusicPlayer.get();
            int next = (p.getRepeatMode() + 1) % 3;
            p.setRepeatMode(next);
            syncState();
        });
        binding.btnNpShuffle.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            MusicPlayer.get().setShuffleMode(!MusicPlayer.get().isShuffleMode());
            syncState();
        });
        binding.btnNpFav.setOnClickListener(v -> {
            AnimUtils.pressScale(v);
            Song shown = screenSong;
            if (shown == null) {
                return;
            }
            boolean on = MusicStore.isFavorite(requireContext(), shown.id);
            MusicStore.setFavorite(requireContext(), shown.id, !on);
            syncState();
        });

        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderSong(MusicPlayer.get().current());
        syncState();
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
    public void onDestroyView() {
        handler.removeCallbacks(tick);
        binding = null;
        super.onDestroyView();
    }

    private void renderSong(Song song) {
        if (binding == null) {
            return;
        }
        Song toShow = song != null ? song : screenSong;
        if (toShow == null) {
            return;
        }
        screenSong = toShow;
        binding.npTitle.setText(toShow.title);
        binding.npArtist.setText(toShow.artist);
        binding.npArtwork.setImageResource(R.drawable.cover_fallback);
        binding.npNoCover.setVisibility(View.VISIBLE);
        binding.npArtwork.setVisibility(View.GONE);
        MusicLibrary.loadArtwork(requireContext(), toShow, bitmap -> {
            if (binding == null || screenSong == null || toShow.id != screenSong.id) {
                return;
            }
            if (bitmap != null && binding != null) {
                // artwork swap crossfaeds in over the fallback
                binding.npArtwork.setAlpha(0f);
                binding.npArtwork.setImageBitmap(bitmap);
                binding.npArtwork.setVisibility(View.VISIBLE);
                binding.npNoCover.setVisibility(View.GONE);
                if (com.nivodev.nivotools.util.AnimUtils.enabled(binding.npArtwork)) {
                    binding.npArtwork.animate().alpha(1f)
                            .setDuration(140).start();
                } else {
                    binding.npArtwork.setAlpha(1f);
                }
            }
        });
    }

    private void syncState() {
        if (binding == null) {
            return;
        }
        boolean playing = MusicPlayer.get().isPlaying();
        binding.btnNpToggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        MusicPlayer p = MusicPlayer.get();
        binding.btnNpRepeat.setImageResource(
                p.getRepeatMode() == MusicStore.REPEAT_ONE
                        ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        binding.btnNpRepeat.setAlpha(
                p.getRepeatMode() == MusicStore.REPEAT_OFF ? 0.55f : 1f);
        binding.btnNpRepeat.setContentDescription(getString(
                p.getRepeatMode() == MusicStore.REPEAT_ONE
                        ? R.string.mode_repeat_one : R.string.mode_repeat));
        binding.btnNpShuffle.setAlpha(p.isShuffleMode() ? 1f : 0.55f);
        Song shown = screenSong;
        boolean fav = shown != null
                && MusicStore.isFavorite(requireContext(), shown.id);
        binding.btnNpFav.setImageResource(fav
                ? R.drawable.ic_heart_on : R.drawable.ic_heart_off);
        long dur = MusicPlayer.get().duration();
        if (dur > 0) {
            binding.npSeek.setMax((int) dur);
            binding.npSeek.setProgress((int) MusicPlayer.get().position());
        }
    }

    private static String formatTime(long ms) {
        long total = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60);
    }

    @Override
    public void onMusicStateChanged() {
        Song live = MusicPlayer.get().current();
        if (live != null && (screenSong == null || live.id != screenSong.id)) {
            renderSong(live);
        }
        syncState();
    }
}
