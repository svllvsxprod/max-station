package org.yanalice.voice;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AssistantMemory {
    private static final String[] FIELDS = {"name", "weather_city", "speaking_gender"};
    private final SharedPreferences preferences;

    AssistantMemory(Context context) {
        preferences = context.getSharedPreferences("assistant-memory", Context.MODE_PRIVATE);
    }

    JSONObject snapshot() throws JSONException {
        JSONObject profile = new JSONObject();
        for (String field : FIELDS) profile.put(field, preferences.getString(field, ""));
        return profile.put("notes", notes());
    }

    private JSONArray notes() throws JSONException {
        return new JSONArray(preferences.getString("notes", "[]"));
    }

    JSONObject update(JSONObject changes) throws JSONException {
        synchronized (AssistantMemory.class) {
            SharedPreferences.Editor edit = preferences.edit();
            JSONObject saved = new JSONObject();
            for (String field : FIELDS) {
                if (changes.isNull(field)) continue;
                String value = changes.getString(field).trim();
                if (!valid(value, 100)) return LiveTools.error("invalid_value");
                if (field.equals("speaking_gender") && !value.matches("feminine|masculine|neutral|")) {
                    return LiveTools.error("invalid_speaking_gender");
                }
                edit.putString(field, value);
                saved.put(field, value);
            }

            if (!changes.isNull("note")) {
                String note = changes.getString("note").trim();
                if (note.isEmpty() || !valid(note, 240)) return LiveTools.error("invalid_note");
                JSONArray notes = notes();
                boolean found = false;
                for (int i = 0; i < notes.length(); i++) {
                    if (note.equalsIgnoreCase(notes.getString(i))) found = true;
                }
                if (!found) {
                    if (notes.length() >= 12) return LiveTools.error("memory_full");
                    edit.putString("notes", notes.put(note).toString());
                }
                saved.put("note", note);
            }
            if (saved.length() == 0) return LiveTools.error("nothing_to_save");
            if (!edit.commit()) return LiveTools.error("save_failed");
            return new JSONObject().put("status", "saved").put("saved", saved);
        }
    }

    private static boolean valid(String value, int limit) {
        return value.length() <= limit && !value.matches("(?s).*[\\p{Cntrl}].*");
    }
}
