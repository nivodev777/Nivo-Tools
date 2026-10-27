package com.nivodev.nivotools.ui.music;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads the device audio list on a worker thread and keeps a cache for the player service. */
public final class MusicLibrary {

    public interface Callback {
        void onLoaded(List<Song> songs);
    }

    public interface ArtCallback {
        void onArt(Bitmap bitmap);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final List<Song> CACHE = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MusicLibrary() {
    }

    /** Snapshot for MusicService actions that may fire without a fragment. */
    public static List<Song> cachedSongs() {
        synchronized (CACHE) {
            return new ArrayList<>(CACHE);
        }
    }

    public static void load(final Context context, final Callback callback) {
        EXEC.execute(() -> {
            final List<Song> result = fetch(context.getApplicationContext());
            synchronized (CACHE) {
                CACHE.clear();
                CACHE.addAll(result);
            }
            MAIN.post(() -> callback.onLoaded(result));
        });
    }

    /** Embedded/thumbnail album art when the song carries one, else null. */
    public static void loadArtwork(final Context context, final Song song, final ArtCallback callback) {
        EXEC.execute(() -> {
            Bitmap art = null;
            try {
                Uri audio = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
                if (Build.VERSION.SDK_INT >= 29) {
                    art = context.getContentResolver().loadThumbnail(
                            audio, new android.util.Size(512, 512), null);
                } else {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    try {
                        mmr.setDataSource(context.getApplicationContext(), audio);
                        byte[] data = mmr.getEmbeddedPicture();
                        if (data != null) {
                            art = BitmapFactory.decodeByteArray(data, 0, data.length);
                        }
                    } finally {
                        mmr.release();
                    }
                }
            } catch (Throwable ignored) {
            }
            final Bitmap result = sortOut(art);
            MAIN.post(() -> callback.onArt(result));
        });
    }

    private static Bitmap sortOut(Bitmap art) {
        return art == null || art.isRecycled() ? null : art;
    }

    private static List<Song> fetch(Context context) {
        List<Song> out = new ArrayList<>();
        try {
            ContentResolver cr = context.getContentResolver();
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATA
            };
            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            int order = MusicStore.getSortMode(context);
            String sort;
            switch (order) {
                case MusicStore.SORT_ARTIST:
                    sort = MediaStore.Audio.Media.ARTIST + " COLLATE NOCASE ASC";
                    break;
                case MusicStore.SORT_DURATION:
                    sort = MediaStore.Audio.Media.DURATION + " DESC";
                    break;
                case MusicStore.SORT_DATE_ADDED:
                    sort = MediaStore.Audio.Media.DATE_ADDED + " DESC";
                    break;
                case MusicStore.SORT_FILE:
                    sort = MediaStore.Audio.Media.DATA + " COLLATE NOCASE ASC";
                    break;
                case MusicStore.SORT_TITLE:
                default:
                    sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";
                    break;
            }
            Cursor c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, selection, null,
                    sort);
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String title = c.getString(1);
                    String artist = c.getString(2);
                    long duration = c.getLong(3);
                    if (title == null || title.trim().isEmpty()) {
                        title = "Unknown";
                    }
                    String path = c.getString(6);
                    // MediaStore keeps stale rows for a while after a file
                    // is deleted externally - skip anything that no longer
                    // exists on disk so the list never shows ghost songs
                    if (path != null && !path.isEmpty()
                            && !new java.io.File(path).exists()) {
                        continue;
                    }
                    // hidden files/folders (dot-prefixed) are private junk -
                    // only normal device files belong in the music list
                    if (inHiddenPath(path)) {
                        continue;
                    }
                    // MediaStore TITLE comes from embedded ID3 tags, so a
                    // file rename never changes it - show the real on-disk
                    // file name instead so renames land on the next scan
                    title = fromFileName(path, title);
                    Song song = new Song(id, title, artist, duration,
                            c.getString(4), c.getLong(5), path);
                    boolean dup = false;
                    for (int i = 0; i < out.size(); i++) {
                        if (out.get(i).id == song.id) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        out.add(song);
                    }
                }
                c.close();
            }
        } catch (Throwable ignored) {
        }

        return out;
    }

    /** Renames a track (MediaStore title). Returns true when the DB update succeeded. */
    public static boolean rename(Context context, Song song, String newTitle) {
        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.TITLE, newTitle);
            return context.getContentResolver().update(uri, values, null, null) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Deletes the audio row; returns true when the provider confirmed removal. */
    public static void deleteDirect(Context context, Song song) {
        try {
            Uri uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
            context.getContentResolver().delete(uri, null, null);
        } catch (Throwable ignored) {
        }
    }


    /** true when any folder/file segment of the path is hidden (.prefix) */
    private static boolean inHiddenPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String seg : path.split("/")) {
            if (!seg.isEmpty() && seg.charAt(0) == '.') {
                return true;
            }
        }
        return false;
    }

    /**
     * Display title = actual file name (without extension). Falls back to
     * the MediaStore title when the path is unavailable or yields nothing.
     */
    private static String fromFileName(String path, String mediaTitle) {
        if (path == null || path.isEmpty()) {
            return mediaTitle == null || mediaTitle.trim().isEmpty()
                    ? "Unknown" : mediaTitle;
        }
        String name = new java.io.File(path).getName();
        int dot = name.lastIndexOf('.');
        String clean = dot > 0 ? name.substring(0, dot) : name;
        clean = clean == null ? null : clean.trim();
        if (clean == null || clean.isEmpty()) {
            return mediaTitle == null || mediaTitle.trim().isEmpty()
                    ? "Unknown" : mediaTitle;
        }
        return clean;
    }
}
