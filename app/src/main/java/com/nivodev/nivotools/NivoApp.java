package com.nivodev.nivotools;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.nivodev.nivotools.util.Prefs;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class NivoApp extends Application {

    public static final String CRASH_FILE = "crash_report.txt";
    private static final String TAG = "NivoApp";

    /** Matching the accent overlays in values/accent_themes.xml. */
    private static final int[] ACCENT_PRIMARY = {
            0xFF6C5CE7, 0xFF3F6CE0, 0xFF14B8A6, 0xFF22B573, 0xFFE8783A,
            0xFFF06060, 0xFFE53935, 0xFFE64C8C, 0xFFEC5DA8, 0xFFB14BE0,
            0xFF8E4BE0, 0xFF5054C9, 0xFF3B9FE8, 0xFF18A8D8, 0xFF7CB342,
            0xFF34A853, 0xFFE9A319, 0xFFC79A2A, 0xFFA06A3F, 0xFF546E7A
    };

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger();
        AppCompatDelegate.setDefaultNightMode(Prefs.nightModeFor(Prefs.getThemeMode(this)));
        // fresh random accent on every cold open (20 palettes)
        try {
            int index = new java.util.Random().nextInt(ACCENT_PRIMARY.length);
            com.nivodev.nivotools.util.Accent.init(ACCENT_PRIMARY[index], index);
        } catch (Throwable t) {
            // keep the default purple if anything goes sideways
        }
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                saveCrash(thread, throwable);
            } catch (Throwable ignored) {
            }
            Log.e(TAG, "Uncaught exception on " + thread.getName(), throwable);
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private void saveCrash(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        Throwable cause = throwable.getCause();
        while (cause != null && cause != throwable) {
            sw.append("\nCAUSED BY: ");
            cause.printStackTrace(new PrintWriter(sw));
            cause = cause.getCause();
        }

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());

        File external = getExternalFilesDir(null);
        if (external != null) {
            writeCrashFile(new File(external, CRASH_FILE), thread, throwable, sw, time);
        }
        writeCrashFile(new File(getFilesDir(), CRASH_FILE), thread, throwable, sw, time);
    }

    private void writeCrashFile(File file, Thread thread, Throwable throwable,
                                StringWriter sw, String time) {
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.append("\n=== CRASH ").append(time).append(" ===\n");
            writer.append("Thread: ").append(thread.getName()).append('\n');
            writer.append("Device SDK: ").append(String.valueOf(Build.VERSION.SDK_INT)).append('\n');
            writer.append("Exception: ").append(throwable.getClass().getName()).append('\n');
            writer.append(sw.toString()).append('\n');
        } catch (Throwable ignored) {
        }
    }
}
