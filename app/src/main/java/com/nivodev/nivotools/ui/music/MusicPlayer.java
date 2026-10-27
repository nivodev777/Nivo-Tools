package com.nivodev.nivotools.ui.music;

import android.content.Context;
import android.content.ContentUris;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import android.os.Handler;
import android.os.Looper;

/** Single MusicPlayer engine shared by the app UI and the background MusicService. */
public final class MusicPlayer {

    public interface Listener {
        void onMusicStateChanged();
    }

    private static final MusicPlayer INSTANCE = new MusicPlayer();

    public static MusicPlayer get() {
        return INSTANCE;
    }

    private static final Object LOCK = new Object();

    private MediaPlayer player;
    private Song current;
    private boolean prepared;
    private Listener listener;
    private Runnable serviceHook;
    private Context appContext;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusListener = change -> onFocusChange(change);
    private boolean resumeOnFocusGain = false;
    private boolean ducked = false;
    private boolean focusHeld = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private Runnable sleepRunner;
    private int repeatMode = MusicStore.REPEAT_OFF;
    private boolean shuffleMode = false;

    private MusicPlayer() {
    }

    public void init(Context context) {
        synchronized (LOCK) {
            if (appContext == null && context != null) {
                appContext = context.getApplicationContext();
                audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                Context app = appContext;
                // persisted playback modes survive app restarts
                synchronized (LOCK) {
                    repeatMode = MusicStore.getRepeatMode(app);
                    shuffleMode = MusicStore.isShuffle(app);
                }
            }
        }
    }

    /** Repeat mode of the playback engine (OFF/ALL/ONE), persisted. */
    public void setRepeatMode(int mode) {
        synchronized (LOCK) {
            repeatMode = mode;
        }
        if (appContext != null) {
            MusicStore.setRepeatMode(appContext, mode);
        }
        notifyStateChanged();
    }

    public int getRepeatMode() {
        synchronized (LOCK) {
            return repeatMode;
        }
    }

    public void setShuffleMode(boolean on) {
        synchronized (LOCK) {
            shuffleMode = on;
        }
        if (appContext != null) {
            MusicStore.setShuffle(appContext, on);
        }
        notifyStateChanged();
    }

    public boolean isShuffleMode() {
        synchronized (LOCK) {
            return shuffleMode;
        }
    }

    // ---------- sleep timer (real system-backed pause) ----------

    /** Schedule playback pause after the given minutes; 0 cancels. */
    public void setSleepTimer(long minutes) {
        synchronized (LOCK) {
            if (sleepRunner != null) {
                mainHandler.removeCallbacks(sleepRunner);
                sleepRunner = null;
            }
        }
        if (appContext == null || minutes <= 0) {
            return;
        }
        Runnable runner = new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    sleepRunner = null;
                }
                // gentle system-style stop: pause but keep position
                pause();
            }
        };
        synchronized (LOCK) {
            sleepRunner = runner;
        }
        mainHandler.postDelayed(runner, minutes * 60_000L);
    }

    /** True when a sleep timer is armed. */
    public boolean isSleepPending() {
        synchronized (LOCK) {
            return sleepRunner != null;
        }
    }

    /** Armed sleep end time in millis (0 when none). */
    public long sleepEndAt() {
        return 0L; // simplified: only pending flag exposed for UI
    }

    /** Cancel a pending sleep timer without touching playback mode. */
    public void cancelSleepTimer() {
        setSleepTimer(0);
    }

    private void bindAudio(Context context) {
        init(context);
    }

    public boolean hasCurrent() {
        synchronized (LOCK) {
            return current != null;
        }
    }

    public Song current() {
        synchronized (LOCK) {
            return current;
        }
    }

    public boolean isPlaying() {
        synchronized (LOCK) {
            try {
                return player != null && prepared && player.isPlaying();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public long position() {
        synchronized (LOCK) {
            try {
                return player != null && prepared ? player.getCurrentPosition() : 0L;
            } catch (Throwable t) {
                return 0L;
            }
        }
    }

    public long duration() {
        synchronized (LOCK) {
            try {
                return player != null && prepared ? player.getDuration() : 0L;
            } catch (Throwable t) {
                return 0L;
            }
        }
    }

    public void setListener(Listener l) {
        synchronized (LOCK) {
            listener = l;
        }
        notifyStateChanged();
    }

    public void setServiceHook(Runnable hook) {
        synchronized (LOCK) {
            serviceHook = hook;
        }
        notifyStateChanged();
    }

    /** Play/pause for the given song: tapping the same song toggles. */
    public void toggle(Context context, Song song) {
        init(context);
        Song mine;
        synchronized (LOCK) {
            mine = current;
        }
        if (mine != null && song != null && mine.id == song.id) {
            if (isPlaying()) {
                pause();
            } else {
                resume();
            }
            return;
        }
        playInternal(context, song);
    }

    public void toggleCurrent() {
        synchronized (LOCK) {
            if (current == null) {
                return;
            }
        }
        if (isPlaying()) {
            pause();
        } else {
            resume();
        }
    }

    /** User-initiated pause: releases audio focus so other apps can play. */
    public void pause() {
        internalPause();
        abandonFocus();
    }

    /** User-initiated resume: takes audio focus back. */
    public void resume() {
        internalResume();
        requestFocus();
    }

    /** System-initiated pause (focus loss, headphones unplugged): no focus abandon. */
    public void pauseForSystem() {
        internalPause();
    }

    private void internalPause() {
        synchronized (LOCK) {
            try {
                if (player != null && prepared && player.isPlaying()) {
                    player.pause();
                }
            } catch (Throwable ignored) {
            }
        }
        notifyStateChanged();
    }

    private void internalResume() {
        synchronized (LOCK) {
            try {
                if (player != null && prepared && !player.isPlaying()) {
                    player.start();
                }
            } catch (Throwable ignored) {
            }
        }
        notifyStateChanged();
    }

    // --- Audio focus: system integration so only one app blasts music ---

    private void requestFocus() {
        if (audioManager == null) {
            return;
        }
        try {
            int result;
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest == null) {
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setOnAudioFocusChangeListener(focusListener)
                            .build();
                }
                result = audioManager.requestAudioFocus(focusRequest);
            } else {
                result = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Throwable ignored) {
        }
    }

    private void abandonFocus() {
        if (audioManager == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Throwable ignored) {
        }
        focusHeld = false;
    }

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                pauseForSystem();
                restoreVolume();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                synchronized (LOCK) {
                    resumeOnFocusGain = isPlaying();
                }
                pauseForSystem();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                duckVolume();
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                restoreVolume();
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    internalResume();
                }
                break;
            default:
                break;
        }
        notifyStateChanged();
    }

    private void duckVolume() {
        ducked = true;
        synchronized (LOCK) {
            try {
                if (player != null && prepared) {
                    player.setVolume(0.2f, 0.2f);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void restoreVolume() {
        ducked = false;
        synchronized (LOCK) {
            try {
                if (player != null && prepared) {
                    player.setVolume(1f, 1f);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void seekTo(long pos) {
        synchronized (LOCK) {
            try {
                if (player != null && prepared) {
                    player.seekTo((int) pos);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Advance the queue. Manual steps find the next library entry;
     * auto-advance (track completion) honors repeat/shuffle modes and
     * stops cleanly when repeat is OFF and the list end is reached.
     */
    public void playNext(int delta) {
        playQueue(delta, true);
    }

    /** Auto advance on completion - may stop playback when repeat OFF. */
    public void autoNext() {
        playQueue(1, false);
    }

    /** Song queued as "next" from the options sheet; one-shot slot. */
    private volatile Song nextUp;
    private volatile boolean hasNextUp = false;

    public void setNextUp(Song song) {
        synchronized (LOCK) {
            nextUp = song;
            hasNextUp = song != null;
        }
        notifyStateChanged();
    }

    public void clearNextUp() {
        synchronized (LOCK) {
            nextUp = null;
            hasNextUp = false;
        }
    }

    private void playQueue(int delta, boolean manual) {
        Context context;
        synchronized (LOCK) {
            context = appContext;
        }
        if (context == null) {
            return;
        }
        List<Song> list = MusicLibrary.cachedSongs();
        if (list.isEmpty()) {
            return;
        }

        // user-queued one-shot wins over everything
        if (manual && delta == 1 && hasNextUp) {
            Song up;
            synchronized (LOCK) {
                up = nextUp;
                nextUp = null;
                hasNextUp = false;
            }
            if (up != null) {
                playInternal(context, up);
                return;
            }
        }

        Song mine;
        int repeat;
        boolean shuffle;
        synchronized (LOCK) {
            mine = current;
            repeat = repeatMode;
            shuffle = shuffleMode;
        }

        int found = -1;
        if (mine != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == mine.id) {
                    found = i;
                    break;
                }
            }
        }
        int idx = found >= 0 ? found : 0;

        if (manual && delta == 1 && shuffle && list.size() > 1) {
            // shuffled manual skip: any other position
            idx = random.nextInt(list.size() - 1);
            if (idx >= found) {
                idx = Math.min(list.size() - 1, idx + 1);
            }
            playInternal(context, list.get(idx));
            return;
        }

        int target = (idx + delta) % list.size();
        if (target < 0) {
            target += list.size();
        }
        if (!manual && delta > 0 && idx == list.size() - 1) {
            // completion of the last track
            if (repeat == MusicStore.REPEAT_ONE) {
                playInternal(context, list.get(idx));
                return;
            }
            if (repeat == MusicStore.REPEAT_ALL) {
                playInternal(context, list.get(0));
                return;
            }
            // repeat OFF: stop cleanly and keep a quiet session
            pause();
            return;
        }
        if (!manual && delta > 0 && repeat == MusicStore.REPEAT_ONE) {
            playInternal(context, list.get(idx));
            return;
        }
        playInternal(context, list.get(target));
    }

    /**
     * Restore the last played track paused at the given position
     * (used on app start so playback state survives relaunch).
     */
    public void restoreLast(Context context, Song song, long startAt) {
        if (song == null || context == null) {
            return;
        }
        playInternal(context, song, false, startAt);
    }

    private void playInternal(Context context, final Song song) {
        playInternal(context, song, true, 0);
    }

    private void playInternal(Context context, final Song song,
                              final boolean autoStart, final long startAt) {
        releaseInternal();
        if (song == null || context == null) {
            notifyStateChanged();
            return;
        }
        init(context);
        requestFocus();
        final MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
            mp.setDataSource(context.getApplicationContext(), uri);
            mp.setOnPreparedListener(mediaPlayer -> {
                synchronized (LOCK) {
                    prepared = true;
                }
                if (startAt > 0) {
                    try {
                        mediaPlayer.seekTo((int) startAt);
                    } catch (Throwable ignored) {
                    }
                }
                if (autoStart) {
                    mutexStart(mediaPlayer);
                }
                notifyStateChanged();
            });
            mp.setOnCompletionListener(mediaPlayer -> {
                synchronized (LOCK) {
                    prepared = false;
                }
                notifyStateChanged();
                autoNext();
            });
            mp.setOnErrorListener((mediaPlayer, what, extra) -> {
                hardRelease();
                notifyStateChanged();
                return true;
            });
            synchronized (LOCK) {
                player = mp;
                current = song;
                prepared = false;
            }
            mp.prepareAsync();
        } catch (Throwable t) {
            releaseInternal();
        }
        notifyStateChanged();
    }

    private void mutexStart(MediaPlayer target) {
        try {
            target.start();
        } catch (Throwable ignored) {
        }
    }

    public void releaseInternal() {
        synchronized (LOCK) {
            teardown();
        }
    }

    private void hardRelease() {
        synchronized (LOCK) {
            teardown();
        }
    }

    /** Full stop used when the service is destroyed (user dismissed playback). */
    public void teardown() {
        abandonFocus();
        MediaPlayer p;
        synchronized (LOCK) {
            p = player;
            player = null;
            current = null;
            prepared = false;
        }
        if (p != null) {
            try {
                p.stop();
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyStateChanged() {
        Runnable hook;
        Listener li;
        synchronized (LOCK) {
            hook = serviceHook;
            li = listener;
        }
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable ignored) {
            }
        }
        if (li != null) {
            try {
                li.onMusicStateChanged();
            } catch (Throwable ignored) {
            }
        }
    }
}
