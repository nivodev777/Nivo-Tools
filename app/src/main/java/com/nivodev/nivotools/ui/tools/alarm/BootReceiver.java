package com.nivodev.nivotools.ui.tools.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Re-registers every enabled alarm after a reboot. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null) {
                return;
            }
            String act = intent.getAction();
            boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(act);
            // clock or timezone edits shift every wall-clock anchor
            boolean clock = Intent.ACTION_TIME_CHANGED.equals(act)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(act);
            if (!boot && !clock) {
                return;
            }
            JSONArray arr = AlarmStore.load(context);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                if (a.optBoolean("enabled", false)) {
                    AlarmStore.schedule(context, a.optInt("id", -1),
                            a.optInt("hour", 8), a.optInt("minute", 0),
                            a.optInt("days", 0));
                }
            }
        } catch (Throwable t) {
        }
    }
}
