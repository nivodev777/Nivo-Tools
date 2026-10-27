package com.nivodev.nivotools.ui.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.nivodev.nivotools.MainActivity;
import com.nivodev.nivotools.R;

/**
 * Foreground service so music keeps playing while Nivo Tools is in the
 * background. Playback decisions live in MusicPlayer; this service forwards
 * commands, owns the system MediaSession (headset buttons, transport
 * integrations) and publishes the media-style state notification.
 */
public class MusicService extends Service {

    public static final String ACTION_PLAY = "com.nivodev.nivotools.music.PLAY";
    public static final String ACTION_TOGGLE = "com.nivodev.nivotools.music.TOGGLE";
    public static final String ACTION_NEXT = "com.nivodev.nivotools.music.NEXT";
    public static final String ACTION_PREV = "com.nivodev.nivotools.music.PREV";
    public static final String ACTION_STOP = "com.nivodev.nivotools.music.STOP";

    private static final String EXTRA_SONG = "extra_song";
    private static final String CHANNEL_ID = "nivo_music_channel";
    private static final int NOTIF_ID = 4217;

    private MediaSession session;
    private BroadcastReceiver noisyReceiver;
    private boolean noisyRegistered;
    private boolean stopping;
    /** True while startForeground was denied (background-start rule);
     *  retried lazily as soon as an active session exists again. */
    private boolean fgBlocked;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        setupSession();
        setupNoisyReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MusicPlayer.get().init(this);
        if (intent == null) {
            // sticky restart after a system kill: never toggle blindly
            promoteForeground();
            updateSession();
            return START_NOT_STICKY;
        }
        String action = intent.getAction() != null ? intent.getAction() : ACTION_TOGGLE;
        if (ACTION_PLAY.equals(action)) {
            Song song = null;
            if (intent != null) {
                song = (Song) intent.getParcelableExtra(EXTRA_SONG);
            }
            MusicPlayer.get().toggle(this, song);
        } else if (ACTION_NEXT.equals(action)) {
            MusicPlayer.get().playNext(1);
        } else if (ACTION_PREV.equals(action)) {
            MusicPlayer.get().playNext(-1);
        } else if (ACTION_STOP.equals(action)) {
            // Notification-delete during the completion/next-track gap is a
            // system dismissal, not a user stop: ignore it while playing.
            if (!MusicPlayer.get().isPlaying()) {
                stopping = true;
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            updateSession();
            return START_STICKY;
        } else {
            MusicPlayer.get().toggleCurrent();
        }
        promoteForeground();
        updateSession();
        return START_STICKY;
    }

    /** Publish the FGS notification; never crashes when the system denies
     *  the start (background start rules). Falls back to a plain visible
     *  notification and retries on the next player state change. */
    private void promoteForeground() {
        try {
            startForeground(NOTIF_ID, buildNotification());
            fgBlocked = false;
        } catch (Throwable t) {
            fgBlocked = true;
            try {
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.notify(NOTIF_ID, buildNotification());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void setupSession() {
        try {
            session = new MediaSession(this, "NivoToolsMusic");
            session.setSessionActivity(pendingOpen());
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    MusicPlayer.get().resume();
                }

                @Override
                public void onPause() {
                    MusicPlayer.get().pause();
                }

                @Override
                public void onSkipToNext() {
                    MusicPlayer.get().playNext(1);
                }

                @Override
                public void onSkipToPrevious() {
                    MusicPlayer.get().playNext(-1);
                }

                @Override
                public void onSeekTo(long pos) {
                    MusicPlayer.get().seekTo(pos);
                }

                @Override
                public void onStop() {
                    stopping = true;
                    ServiceCompat.stopForeground(MusicService.this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            });
            // Player state changes drive both the notification and the session.
            MusicPlayer.get().setServiceHook(this::updateUiState);
        } catch (Throwable ignored) {
            session = null;
        }
    }

    private void setupNoisyReceiver() {
        try {
            noisyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // Headphones unplugged: pause politely (system convention).
                    MusicPlayer.get().pauseForSystem();
                }
            };
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            ContextCompat.registerReceiver(this, noisyReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            noisyRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private PendingIntent pendingOpen() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Called on every player state change (via service hook) and commands. */
    private void updateUiState() {
        if (fgBlocked) {
            // the state change implies a session update; try promoting
            promoteForeground();
        }
        updateSession();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification());
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateSession() {
        if (session == null) {
            return;
        }
        try {
            MusicPlayer p = MusicPlayer.get();
            int state = p.hasCurrent()
                    ? (p.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED)
                    : PlaybackState.STATE_STOPPED;
            PlaybackState.Builder psb = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_SEEK_TO
                            | PlaybackState.ACTION_STOP)
                    .setState(state, p.position(), p.isPlaying() ? 1.0f : 0.0f);
            session.setPlaybackState(psb.build());

            Song song = p.current();
            if (song != null) {
                android.media.MediaMetadata.Builder mb = new android.media.MediaMetadata.Builder();
                mb.putString(android.media.MediaMetadata.METADATA_KEY_TITLE, song.title);
                mb.putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, song.artist);
                mb.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, song.duration);
                session.setMetadata(mb.build());
            }
            // active while a track is LOADED, not only while it plays:
            // keeps FGS/media legality across the completion-to-next gap
            session.setActive(p.hasCurrent());
        } catch (Throwable ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.notif_channel_music),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(getString(R.string.notif_channel_music_desc));
                channel.setShowBadge(false);
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.createNotificationChannel(channel);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private Notification buildNotification() {
        MusicPlayer p = MusicPlayer.get();
        Song song = p.current();
        boolean playing = p.isPlaying();

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_music)
                .setContentIntent(pendingOpen())
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_TRANSPORT);

        if (song != null) {
            builder.setContentTitle(song.title);
            builder.setContentText(song.artist);
        } else {
            builder.setContentTitle(getString(R.string.music_title));
            builder.setContentText("");
        }

        builder.addAction(actionOf(R.drawable.ic_prev, getString(R.string.player_previous),
                serviceIntent(ACTION_PREV), 1));
        builder.addAction(actionOf(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                playing ? getString(R.string.player_pause) : getString(R.string.player_play),
                serviceIntent(ACTION_TOGGLE), 2));
        builder.addAction(actionOf(R.drawable.ic_next, getString(R.string.player_next),
                serviceIntent(ACTION_NEXT), 3));

        Intent stop = serviceIntent(ACTION_STOP);
        builder.setDeleteIntent(servicePendingIntent(stop, 4));

        Notification.MediaStyle style = new Notification.MediaStyle();
        if (session != null) {
            style.setMediaSession(session.getSessionToken());
        }
        style.setShowActionsInCompactView(0, 1, 2);
        builder.setStyle(style);

        return builder.build();
    }

    private Intent serviceIntent(String action) {
        return new Intent(this, MusicService.class).setAction(action);
    }

    private Notification.Action actionOf(int icon, String title, Intent intent, int req) {
        PendingIntent pi = servicePendingIntent(intent, req);
        if (Build.VERSION.SDK_INT >= 23) {
            return new Notification.Action.Builder(icon, title, pi).build();
        }
        return new Notification.Action(icon, title, pi);
    }

    private PendingIntent servicePendingIntent(Intent intent, int req) {
        if (Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(
                    this, req, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return PendingIntent.getService(
                this, req, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // --- static controls used by the UI ---

    public static void play(Context context, Song song) {
        Intent i = new Intent(context, MusicService.class)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_SONG, song);
        ContextCompat.startForegroundService(context, i);
    }

    public static void step(Context context, int delta) {
        Intent i = new Intent(context, MusicService.class)
                .setAction(delta >= 0 ? ACTION_NEXT : ACTION_PREV);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, MusicService.class).setAction(ACTION_STOP);
        ContextCompat.startForegroundService(context, i);
    }

    @Override
    public void onDestroy() {
        if (noisyRegistered && noisyReceiver != null) {
            try {
                getApplicationContext().unregisterReceiver(noisyReceiver);
            } catch (Throwable ignored) {
            }
            noisyRegistered = false;
        }
        MusicPlayer.get().setServiceHook(null);
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Throwable ignored) {
            }
            session = null;
        }
        if (stopping) {
            MusicPlayer.get().teardown();
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
