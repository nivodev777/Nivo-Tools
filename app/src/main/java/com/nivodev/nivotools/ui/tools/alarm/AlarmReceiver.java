package com.nivodev.nivotools.ui.tools.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fires the stored alarm: marks it done, starts the ring service + ring screen. */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            int id = intent != null ? intent.getIntExtra("id", -1) : -1;
            if (id < 0) {
                return;
            }
            boolean snooze = intent.getBooleanExtra("snooze", false);
            JSONArray arr = AlarmStore.load(context);
            JSONObject a = AlarmStore.byId(arr, id);
            if (a == null || !a.optBoolean("enabled", false)) {
                return;
            }
            int days = a.optInt("days", 0);
            if (snooze) {
                // Pause re-ring: just sound again, never touch stored state
            } else if (days <= 0) {
                // one-shot: it just fired - keep the list visible state consistent
                a.put("enabled", false);
            } else {
                // repeating: register the next occurrence right away
                AlarmStore.schedule(context, id, a.optInt("hour", 8),
                        a.optInt("minute", 0), days);
            }
            AlarmStore.save(context, arr);

            String label = a.optString("label", "");
            String tune = a.optString("tune", "raw:tune_1");
            int mode = a.optInt("mode", 0);

            // ring screen FIRST so it pops on the full display at once
            Intent ui = new Intent(context, RingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("id", id)
                    .putExtra("label", label)
                    .putExtra("mode", mode);
            try {
                context.startActivity(ui);
            } catch (Throwable t) {
                // some OEMs block background activity launches; the full-screen
                // intent notification still brings the ring screen up
            }

            Intent ring = new Intent(context, AlarmService.class)
                    .setAction("com.nivodev.nivotools.alarm.FIRE")
                    .putExtra("id", id)
                    .putExtra("label", label)
                    .putExtra("tune", tune)
                    .putExtra("mode", mode);
            androidx.core.content.ContextCompat.startForegroundService(context, ring);
        } catch (Throwable t) {
            // alarms must never crash the system slot
        }
    }
}
