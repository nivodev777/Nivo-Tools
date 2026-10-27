package com.nivodev.nivotools.ui.tools.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.nivodev.nivotools.R;
import com.nivodev.nivotools.util.Haptics;

import java.io.File;

/**
 * Rings until the user answers the security question: looping tune, alarm stream at
 * full volume, repeating vibration and a wake lock.
 */
public class AlarmService extends Service {

    public static final String ACTION_STOP = "com.nivodev.nivotools.alarm.STOP";
    public static final String ACTION_SNOOZE = "com.nivodev.nivotools.alarm.SNOOZE";
    public static final String CHANNEL_ID = "nivo_alarm_ring";
    public static final int NOTIF_ID = 4312;

    private MediaPlayer player;
    private PowerManager.WakeLock wake;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SNOOZE.equals(intent.getAction())) {
            int sid = intent.getIntExtra("id", -1);
            try {
                AlarmStore.scheduleSnooze(this, sid, 15);
            } catch (Throwable t) {
            }
            shutdown();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        String label = intent != null ? intent.getStringExtra("label") : null;
        String tune = intent != null ? intent.getStringExtra("tune") : null;
        int id = intent != null ? intent.getIntExtra("id", -1) : -1;
        int mode = intent != null ? intent.getIntExtra("mode", 0) : 0;
        ring(label, tune, id, mode);
        return START_STICKY;
    }

    /** Builds the head-up / full-screen alarm notification shown while ringing. */
    public static Notification ringNotification(Context c, String label, int id, int mode) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    c.getString(R.string.menu_alarm), NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(c.getString(R.string.alarm_fired_body));
            ch.setSound(null, null);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0L, 600L, 400L});
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(c, 4411,
                new Intent(c, RingActivity.class)
                        .putExtra("id", id)
                        .putExtra("label", label)
                        .putExtra("mode", mode)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean fsOn = true;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                NotificationManager nmx = (NotificationManager)
                        c.getSystemService(Context.NOTIFICATION_SERVICE);
                fsOn = nmx == null || nmx.canUseFullScreenIntent();
            }
        } catch (Throwable t) {
        }
        PendingIntent stop = PendingIntent.getService(c, 4412,
                new Intent(c, AlarmService.class).setAction(ACTION_STOP)
                        .putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(c, 4413,
                new Intent(c, AlarmService.class).setAction(ACTION_SNOOZE)
                        .putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(label != null && !label.isEmpty()
                        ? label : c.getString(R.string.alarm_fired_title))
                .setContentText(c.getString(mode == 1
                        ? R.string.ring_swipe_hint : R.string.ring_solve)
                        + (fsOn ? "" : "  -  "
                        + c.getString(R.string.ring_notif_fs_hint)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ALARM)
                .setDefaults(0)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .addAction(0, c.getString(R.string.ring_notif_stop), stop)
                .addAction(0, c.getString(R.string.ring_notif_pause), pause);
        return b.build();
    }

    private void ring(String label, String tune, int id, int mode) {
        // re-fire while already ringing: stop the old audio, vibration and
        // wake lock first so sessions never overlap, then rebuild on top
        if (player != null || wake != null) {
            shutdownAudio();
            if (wake != null) {
                try {
                    wake.release();
                } catch (Throwable t) {
                }
                wake = null;
            }
        }
        Notification notif = ringNotification(this, label, id, mode);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Throwable t) {
            startForeground(NOTIF_ID, notif);
        }

        // some OEMs block the receiver's startActivity call - this is a second
        // chance now that the app is a foreground service
        try {
            Intent ui = new Intent(this, RingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("id", id)
                    .putExtra("label", label)
                    .putExtra("mode", mode);
            startActivity(ui);
        } catch (Throwable t) {
            // fall back to the full-screen intent notification
        }

        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
        } catch (Throwable t) {
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NivoAlarm:ring");
            wake.acquire(60L * 60L * 1000L);
        } catch (Throwable t) {
        }

        try {
            player = buildPlayer(tune);
            player.setVolume(1f, 1f);
            player.setOnErrorListener((mp, what, extra) -> {
                shutdownAudio();
                return true;
            });
            player.start();
        } catch (Throwable t) {
            shutdownAudio();
        }

        try {
            // alarm ring must always vibrate, independent of the UI haptics switch
            Haptics.patternDirect(this, new long[]{0L, 600L, 400L},
                    new int[]{0, 255, 0}, 0);
        } catch (Throwable t) {
        }
    }

    private MediaPlayer buildPlayer(String tune) throws Exception {
        File custom = AlarmStore.file(this, tune);
        MediaPlayer mp = new MediaPlayer();
        AudioAttributes att = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        mp.setAudioAttributes(att);
        if (custom != null && custom.exists() && custom.length() > 0) {
            mp.setDataSource(custom.getAbsolutePath());
        } else {
            int res = AlarmStore.rawRes(this, tune);
            if (res != 0) {
                mp.setDataSource(this, android.net.Uri.parse("android.resource://"
                        + getPackageName() + "/" + res));
            } else {
                mp.setDataSource(this, Settings.System.DEFAULT_ALARM_ALERT_URI);
            }
        }
        mp.setLooping(true);
        mp.prepare();
        return mp;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        shutdownAudio();
        try {
            NotificationManagerCompat.from(this).cancel(NOTIF_ID);
        } catch (Throwable t) {
        }
        if (wake != null) {
            try {
                wake.release();
            } catch (Throwable t) {
            }
            wake = null;
        }
        stopVibrator();
    }

    private void shutdownAudio() {
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (Throwable t) {
            }
            try {
                player.release();
            } catch (Throwable t) {
            }
            player = null;
        }
        stopVibrator();
    }

    private void stopVibrator() {
        try {
            Haptics.cancelAll(this);
        } catch (Throwable t) {
        }
    }
}
