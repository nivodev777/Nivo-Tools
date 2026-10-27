package com.nivodev.nivotools.ui.tools.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;

/**
 * Alarm storage + scheduling. Each future tool keeps its own store file, same pattern
 * used by the other tools (song library, notes...).
 */
public final class AlarmStore {

    /** Built-in ringtunes bundled in res/raw (tune_1 .. tune_12). */
    public static final String[] TUNE_NAMES = {
            "Acoustic Guitar", "Brazil Funk", "Breeze", "Chiptune", "Dripping Drops",
            "Future Bounce", "Hacker Alarm", "Helicopter", "Hip Hop", "Jingle Bells",
            "Police Trap", "Sprints Alarm"
    };
    public static final int[] TUNE_IDS = {
            com.nivodev.nivotools.R.raw.tune_1,
            com.nivodev.nivotools.R.raw.tune_2,
            com.nivodev.nivotools.R.raw.tune_3,
            com.nivodev.nivotools.R.raw.tune_4,
            com.nivodev.nivotools.R.raw.tune_5,
            com.nivodev.nivotools.R.raw.tune_6,
            com.nivodev.nivotools.R.raw.tune_7,
            com.nivodev.nivotools.R.raw.tune_8,
            com.nivodev.nivotools.R.raw.tune_9,
            com.nivodev.nivotools.R.raw.tune_10,
            com.nivodev.nivotools.R.raw.tune_11,
            com.nivodev.nivotools.R.raw.tune_12
    };

    /** Bit i = weekday i is selected; bit 0 = Sunday ... bit 6 = Saturday. */
    public static final String[] DAY_NAMES = {
            "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"
    };
    /** Full weekday display names (bit order same as DAY_NAMES). */
    public static final String[] DAY_NAMES_FULL = {
            "Sunday", "Monday", "Tuesday", "Wednesday",
            "Thursday", "Friday", "Saturday"
    };
    public static final int DAYS_ALL = 0x7F;

    private AlarmStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("alarm_store", Context.MODE_PRIVATE);
    }

    /** @return every persisted alarm (fields: id, hour, minute, label, tune, enabled). */
    public static JSONArray load(Context c) {
        try {
            return new JSONArray(prefs(c).getString("list", "[]"));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    public static void save(Context c, JSONArray arr) {
        prefs(c).edit().putString("list", arr.toString()).apply();
    }

    public static JSONObject byId(JSONArray arr, int id) {
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                if (o.optInt("id", -1) == id) {
                    return o;
                }
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    public static int nextId(Context c) {
        SharedPreferences p = prefs(c);
        int n = p.getInt("next_id", 1);
        p.edit().putInt("next_id", n + 1).apply();
        return n;
    }

    /** Built-in tune list, values look like "raw:tune_3". */
    public static String builtKey(int index) {
        return "raw:" + "tune_" + (index + 1);
    }

    /** Display name for any stored tune key ("raw:tune_3" / "file:tunes_custom_1.audio"). */
    public static String tuneTitle(Context c, String key) {
        if (key == null || key.isEmpty()) {
            return TUNE_NAMES[0];
        }
        if (key.startsWith("raw:")) {
            String name = key.substring(4);
            try {
                int idx = Integer.parseInt(name.substring(name.indexOf('_') + 1)) - 1;
                if (idx >= 0 && idx < TUNE_NAMES.length) {
                    return TUNE_NAMES[idx];
                }
            } catch (Throwable t) {
                return name;
            }
            return name;
        }
        if (key.startsWith("file:")) {
            String file = key.substring(5);
            try {
                JSONArray customs = new JSONArray(prefs(c).getString("customs", "[]"));
                for (int i = 0; i < customs.length(); i++) {
                    JSONObject o = customs.getJSONObject(i);
                    if (file.equals(o.optString("file"))) {
                        return o.optString("name", file);
                    }
                }
            } catch (Throwable t) {
                return file;
            }
            return file;
        }
        return TUNE_NAMES[0];
    }

    /** @return handle of a raw resource for the given tune key, 0 when not found. */
    public static int rawRes(Context c, String key) {
        if (key != null && key.startsWith("raw:")) {
            String name = key.substring(4);
            int res = c.getResources().getIdentifier(name, "raw", c.getPackageName());
            if (res != 0) {
                return res;
            }
        }
        return TUNE_IDS[0];
    }

    /** 12-hour clock text, e.g. "07:05 AM". */
    public static String formatTime(int hour, int minute) {
        int h = hour % 12;
        if (h == 0) {
            h = 12;
        }
        boolean pm = hour % 24 >= 12;
        return String.format(Locale.US, "%02d:%02d %s", h, minute % 60, pm ? "PM" : "AM");
    }

    /** Human summary of the weekday mask: one time / every day / day list. */
    public static String daySummary(Context c, int mask) {
        if (mask <= 0) {
            return c.getString(com.nivodev.nivotools.R.string.alarm_repeat_once);
        }
        if ((mask & DAYS_ALL) == DAYS_ALL) {
            return c.getString(com.nivodev.nivotools.R.string.alarm_repeat_everyday);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (((mask >> i) & 1) != 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(DAY_NAMES[i]);
            }
        }
        return sb.toString();
    }

    /** Audio file in internal storage for the given custom tune key, or null. */
    public static File file(Context c, String key) {
        if (key != null && key.startsWith("file:")) {
            return new File(c.getFilesDir(), key.substring(5));
        }
        return null;
    }

    /** Stores an imported tune (copied into internal storage) and returns its key. */
    public static String addCustom(Context c, String displayName) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray customs = new JSONArray(p.getString("customs", "[]"));
            int n = p.getInt("custom_n", 0) + 1;
            p.edit().putInt("custom_n", n).apply();
            String file = "tunes_custom_" + n + ".audio";
            String name = displayName == null || displayName.trim().isEmpty()
                    ? ("Custom " + n) : displayName.trim();
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("file", file);
            customs.put(o);
            p.edit().putString("customs", customs.toString()).apply();
            return "file:" + file;
        } catch (Throwable t) {
            return "raw:tune_1";
        }
    }

    /** Schedules one alarm with clock-exact priority; falls back to window alarms. */
    public static void schedule(Context c, int id, int hour, int minute, int days) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Calendar now = Calendar.getInstance();
            if (now.get(Calendar.SECOND) > 55
                    || (now.get(Calendar.SECOND) == 55 && now.get(Calendar.MILLISECOND) > 0)) {
                now.add(Calendar.MINUTE, 1);
            }
            long best = -1L;
            for (int add = 0; add < 8 && best < 0L; add++) {
                Calendar cal = (Calendar) now.clone();
                cal.add(Calendar.DAY_OF_YEAR, add);
                int bit = cal.get(Calendar.DAY_OF_WEEK) - 1; // SUNDAY=1 -> bit 0
                boolean dayOk = days <= 0 || ((days >> bit) & 1) != 0;
                if (!dayOk) {
                    continue;
                }
                cal.set(Calendar.HOUR_OF_DAY, hour % 24);
                cal.set(Calendar.MINUTE, minute % 60);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() > now.getTimeInMillis()) {
                    best = cal.getTimeInMillis();
                }
            }
            if (best < 0L) {
                return;
            }
            PendingIntent same = pi(c, id);
            AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(best, show(c));
            try {
                am.setAlarmClock(info, same);
                return;
            } catch (Throwable t) {
                // exact alarm permission missing - degraded but still working
            }
            if (Build.VERSION.SDK_INT >= 23) {
                am.setWindow(AlarmManager.RTC_WAKEUP, best, 30000L, same);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, best, same);
            }
        } catch (Throwable t) {
            // never crash on scheduling
        }
    }

    /** Overload kept for single-call sites that have no day mask (never repeats). */
    public static void schedule(Context c, int id, int hour, int minute) {
        schedule(c, id, hour, minute, 0);
    }

    public static void cancel(Context c, int id) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pi(c, id));
                am.cancel(piSnooze(c, id));
            }
        } catch (Throwable t) {
        }
    }

    /** Pause offset so snooze PendingIntents never clash with the real alarm slot. */
    public static final int SNOOZE_REQ_OFFSET = 20000;

    /** Schedules a one-off re-ring (the 15-minute Pause from the ring screen). */
    public static void scheduleSnooze(Context c, int id, int minutes) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            long at = System.currentTimeMillis() + Math.max(1, minutes) * 60000L;
            PendingIntent p = piSnooze(c, id);
            AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(at, show(c));
            try {
                am.setAlarmClock(info, p);
                return;
            } catch (Throwable t) {
            }
            if (Build.VERSION.SDK_INT >= 23) {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 30000L, p);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, p);
            }
        } catch (Throwable t) {
        }
    }

    private static PendingIntent pi(Context c, int id) {
        Intent in = new Intent(c, AlarmReceiver.class).putExtra("id", id);
        return PendingIntent.getBroadcast(c, id, in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent piSnooze(Context c, int id) {
        Intent in = new Intent(c, AlarmReceiver.class)
                .putExtra("id", id)
                .putExtra("snooze", true);
        return PendingIntent.getBroadcast(c, id + SNOOZE_REQ_OFFSET, in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent show(Context c) {
        Intent in = new Intent(c, com.nivodev.nivotools.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, id_show(c), in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int id_show(Context c) {
        SharedPreferences p = prefs(c);
        int n = p.getInt("show_id", 8001);
        p.edit().putInt("show_id", n + 1).apply();
        return n;
    }
}
