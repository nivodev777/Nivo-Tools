package com.nivodev.nivotools.ui.tools.notes;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Notes persist in their own prefs file (one file per tool keeps things isolated). */
public final class NotesStore {

    private NotesStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("notes_store", Context.MODE_PRIVATE);
    }

    /** @return every note (fields: id, title, body, updated) newest first. */
    public static JSONArray load(Context c) {
        try {
            return new JSONArray(prefs(c).getString("list", "[]"));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    public static void save(Context c, JSONArray arr) {
        try {
            ArrayList<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getJSONObject(i));
            }
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(b.optLong("updated", 0L),
                            a.optLong("updated", 0L));
                }
            });
            JSONArray out = new JSONArray();
            for (JSONObject o : list) {
                out.put(o);
            }
            arr = out;
        } catch (Throwable t) {
        }
        prefs(c).edit().putString("list", arr.toString()).apply();
    }

    public static int nextId(Context c) {
        SharedPreferences p = prefs(c);
        int n = p.getInt("next_id", 1);
        p.edit().putInt("next_id", n + 1).apply();
        return n;
    }
}
