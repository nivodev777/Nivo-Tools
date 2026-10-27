package com.nivodev.nivotools.ui.music;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small SharedPreferences store for music preferences and the last
 * played state: favorites, repeat/shuffle modes, sort mode and the
 * lastplayed track + position so playback can be restored after an app relaunch.
 */
public final class MusicStore {

    private static final String PREFS = "music_store";

    private static final String KEY_FAVS = "favs";
    private static final String KEY_REPEAT = "repeat";
    private static final String KEY_SHUFFLE = "shuffle";
    private static final String KEY_SORT = "sort_mode";
    private static final String KEY_LAST_ID = "last_id";
    private static final String KEY_LAST_TITLE = "last_title";
    private static final String KEY_LAST_ARTIST = "last_artist";
    private static final String KEY_LAST_DUR = "last_dur";
    private static final String KEY_LAST_PATH = "last_path";
    private static final String KEY_LAST_POS = "last_pos";

    /** Repeat modes. */
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    /** Sort modes matching MusicLibrary.fetch queries. */
    public static final int SORT_TITLE = 0;
    public static final int SORT_ARTIST = 1;
    public static final int SORT_DURATION = 2;
    public static final int SORT_DATE_ADDED = 3;
    public static final int SORT_FILE = 4;

    private MusicStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- favorites ----------

    public static boolean isFavorite(Context c, long songId) {
        return prefs(c).getStringSet(KEY_FAVS, null) != null
                && prefs(c).getStringSet(KEY_FAVS, null)
                .contains(String.valueOf(songId));
    }

    public static void setFavorite(Context c, long songId, boolean fav) {
        SharedPreferences p = prefs(c);
        java.util.Set<String> set =
                new java.util.HashSet<>(p.getStringSet(KEY_FAVS,
                        new java.util.HashSet<>()));
        String key = String.valueOf(songId);
        if (fav) {
            set.add(key);
        } else {
            set.remove(key);
        }
        p.edit().putStringSet(KEY_FAVS, set).apply();
    }

    // ---------- repeat / shuffle ----------

    public static int getRepeatMode(Context c) {
        return prefs(c).getInt(KEY_REPEAT, REPEAT_OFF);
    }

    public static void setRepeatMode(Context c, int mode) {
        prefs(c).edit().putInt(KEY_REPEAT, mode).apply();
    }

    public static boolean isShuffle(Context c) {
        return prefs(c).getBoolean(KEY_SHUFFLE, false);
    }

    public static void setShuffle(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_SHUFFLE, on).apply();
    }

    // ---------- sort ----------

    public static int getSortMode(Context c) {
        return prefs(c).getInt(KEY_SORT, SORT_TITLE);
    }

    public static void setSortMode(Context c, int mode) {
        prefs(c).edit().putInt(KEY_SORT, mode).apply();
    }

    // ---------- last played ----------

    /** Save the currently playing song and position (called on pause). */
    public static void saveLast(Context c, Song song, long position) {
        if (song == null) {
            return;
        }
        prefs(c).edit()
                .putLong(KEY_LAST_ID, song.id)
                .putString(KEY_LAST_TITLE, song.title)
                .putString(KEY_LAST_ARTIST, song.artist)
                .putLong(KEY_LAST_DUR, song.duration)
                .putString(KEY_LAST_PATH, song.path)
                .putLong(KEY_LAST_POS, Math.max(0, position))
                .apply();
    }

    /**
     * Restore the last played song; writes the position into pos[0].
     * Returns null when nothing valid was saved.
     */
    public static Song readLast(Context c, long[] pos) {
        SharedPreferences p = prefs(c);
        long id = p.getLong(KEY_LAST_ID, -1);
        if (id <= 0) {
            return null;
        }
        if (pos != null && pos.length > 0) {
            pos[0] = Math.max(0, p.getLong(KEY_LAST_POS, 0));
        }
        return new Song(id,
                p.getString(KEY_LAST_TITLE, "Unknown"),
                p.getString(KEY_LAST_ARTIST, ""),
                p.getLong(KEY_LAST_DUR, 0),
                null, 0,
                p.getString(KEY_LAST_PATH, null));
    }
}
