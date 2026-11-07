package org.yanalice.voice;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class StationAlarm extends BroadcastReceiver {
    private static final String FIRE = "org.yanalice.voice.ALARM";
    static final String RING = "org.yanalice.voice.RING";

    static JSONObject schedule(Context context, JSONObject args) throws JSONException {
        long now = System.currentTimeMillis();
        long at;
        try {
            boolean relative = !args.isNull("delay_seconds");
            boolean absolute = !args.isNull("local_time");
            if (relative == absolute) return LiveTools.error("provide_one_time");
            if (relative) at = Math.addExact(now, Math.multiplyExact(args.getLong("delay_seconds"), 1000L));
            else {
                LocalDateTime requested = LocalDateTime.parse(args.getString("local_time"));
                ZoneId zone = ZoneId.systemDefault();
                if (zone.getRules().getValidOffsets(requested).size() != 1)
                    return LiveTools.error("ambiguous_local_time");
                at = requested.atZone(zone).toInstant().toEpochMilli();
            }
        } catch (RuntimeException e) {
            return LiveTools.error("invalid_time");
        }
        String error = scheduleAt(context, at);
        if (error != null) return LiveTools.error(error);
        return new JSONObject().put("status", "scheduled")
            .put("local_time", Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toString());
    }

    static synchronized String scheduleAt(Context context, long at) {
        long now = System.currentTimeMillis();
        if (at < now + 1000 || at > now + 366L * 24 * 3600 * 1000) return "invalid_time";
        SharedPreferences state = alarms(context);
        String id = Long.toString(at);
        boolean existed = state.contains(id);
        if (state.getAll().size() >= 20 && !existed) return "too_many_alarms";
        if (!state.edit().putLong(id, at).commit()) return "save_failed";
        try {
            register(context, at);
        } catch (RuntimeException e) {
            if (!existed) state.edit().remove(id).commit();
            return "schedule_failed";
        }
        return null;
    }

    static List<Long> upcoming(Context context) {
        long now = System.currentTimeMillis();
        List<Long> result = new ArrayList<>();
        for (Object value : alarms(context).getAll().values()) {
            if (value instanceof Long && (Long) value > now) result.add((Long) value);
        }
        Collections.sort(result);
        return result;
    }

    static synchronized boolean cancel(Context context, long at) {
        if (!remove(context, at)) return false;
        PendingIntent action = PendingIntent.getBroadcast(context, 0, fire(context, at),
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (action != null) {
            context.getSystemService(AlarmManager.class).cancel(action);
            action.cancel();
        }
        return true;
    }

    private static synchronized boolean remove(Context context, long at) {
        SharedPreferences state = alarms(context);
        String id = Long.toString(at);
        return state.contains(id) && state.edit().remove(id).commit();
    }

    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences state = alarms(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            for (Map.Entry<String, ?> saved : state.getAll().entrySet()) {
                if (!(saved.getValue() instanceof Long)) continue;
                long at = (Long) saved.getValue();
                if (at > System.currentTimeMillis()) register(context, at);
                else state.edit().remove(saved.getKey()).apply();
            }
        } else if (FIRE.equals(intent.getAction())) {
            long at = intent.getLongExtra("at", -1);
            if (!remove(context, at)) return;
            Intent ring = new Intent(context, AlarmActivity.class).setAction(RING)
                .putExtra("at", at).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(ring);
        }
    }

    private static void register(Context context, long at) {
        Uri id = Uri.parse("maxstation-alarm:" + at);
        Intent details = new Intent(context, AlarmActivity.class).setData(id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent action = PendingIntent.getBroadcast(context, 0, fire(context, at), flags);
        PendingIntent show = PendingIntent.getActivity(context, 0, details, flags);
        context.getSystemService(AlarmManager.class).setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), action);
    }

    private static Intent fire(Context context, long at) {
        return new Intent(context, StationAlarm.class).setAction(FIRE)
            .setData(Uri.parse("maxstation-alarm:" + at)).putExtra("at", at);
    }

    private static SharedPreferences alarms(Context context) {
        return context.getSharedPreferences("assistant-alarms", Context.MODE_PRIVATE);
    }
}
