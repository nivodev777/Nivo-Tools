package com.nivodev.nivotools.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Date helpers built on Calendar so everything works down to minSdk 21. */
public final class TimeUtils {

    private TimeUtils() {
    }

    public static long startOfDay(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public static boolean isSameDay(long a, long b) {
        return startOfDay(a) == startOfDay(b);
    }

    public static boolean isToday(long millis) {
        return millis > 0 && isSameDay(millis, System.currentTimeMillis());
    }

    public static boolean isPast(long millis) {
        return millis > 0 && millis < System.currentTimeMillis();
    }

    public static String formatTime(long millis) {
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(millis));
    }

    public static String formatDue(long millis) {
        if (millis <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (isToday(millis)) {
            return "Today, " + formatTime(millis);
        }
        long tomorrow = startOfDay(now) + 24L * 60L * 60L * 1000L;
        if (isSameDay(millis, tomorrow)) {
            return "Tomorrow, " + formatTime(millis);
        }
        if (millis > now && millis < now + 7L * 24L * 60L * 60L * 1000L) {
            String day = new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date(millis));
            return day + ", " + formatTime(millis);
        }
        String date = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(new Date(millis));
        return date + ", " + formatTime(millis);
    }

    public static String formatHeaderDate(long millis) {
        return new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date(millis));
    }

    public static int greetingRes(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (hour < 12) {
            return com.nivodev.nivotools.R.string.greeting_morning;
        }
        if (hour < 18) {
            return com.nivodev.nivotools.R.string.greeting_afternoon;
        }
        return com.nivodev.nivotools.R.string.greeting_evening;
    }

    public static Calendar calendarFor(long millis) {
        Calendar cal = Calendar.getInstance();
        if (millis > 0) {
            cal.setTimeInMillis(millis);
        }
        return cal;
    }

    public static String formatButtonDate(long millis) {
        if (millis <= 0) {
            return null;
        }
        return new SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(new Date(millis));
    }

    public static String formatButtonTime(long millis) {
        if (millis <= 0) {
            return null;
        }
        return formatTime(millis);
    }
}
