package org.yanalice.voice;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.yanalice.voice.TvUi.*;

public final class AlarmActivity extends Activity {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM, EEEE", new Locale("ru"));
    private static final AtomicReference<AlarmActivity> ringing = new AtomicReference<>();
    private final Handler handler = new Handler();
    private ToneGenerator alarm;
    private TvUi ui;
    private final Runnable ring = new Runnable() {
        @Override public void run() {
            if (alarm == null) return;
            alarm.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 850);
            handler.postDelayed(this, 1500);
        }
    };

    static boolean stopRinging() {
        AlarmActivity current = ringing.getAndSet(null);
        if (current == null) return false;
        current.handler.post(current::dismissRinging);
        return true;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new TvUi(this);
        if (StationAlarm.RING.equals(getIntent().getAction())) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            showRinging(getIntent().getLongExtra("at", System.currentTimeMillis()));
        } else showAlarms();
    }

    private void showRinging(long at) {
        LinearLayout root = ui.column();
        root.setGravity(Gravity.CENTER);
        root.setPadding(ui.dp(58), ui.dp(30), ui.dp(58), ui.dp(30));
        root.setBackgroundColor(0xff141414);
        ZonedDateTime local = localTime(at);
        LinearLayout layout = ui.row();
        LinearLayout time = ui.column();
        time.addView(ui.eyebrow("MAX STATION"));
        TextView clock = ui.text(CLOCK.format(local), 124, WHITE, true);
        clock.setIncludeFontPadding(false);
        clock.setFontFeatureSettings("tnum");
        time.addView(clock, ui.block(-2, -2, 14));
        time.addView(ui.text(DAY.format(local), 25, MUTED, false), ui.block(-2, -2, 9));
        layout.addView(time, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout controls = ui.card();
        ImageView bell = new ImageView(this);
        bell.setImageResource(R.drawable.alarm_bell);
        bell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        controls.addView(bell, ui.block(34, 34, 0));
        controls.addView(ui.text("Будильник", 36, WHITE, true), ui.block(-1, -2, 13));
        Button stop = ui.action("Остановить", true);
        stop.setTextSize(20);
        stop.setOnClickListener(view -> dismissRinging());
        controls.addView(stop, ui.block(-1, 58, 20));
        controls.addView(ui.text("Или удержи кнопку ассистента и скажи «Выключи будильник».",
            17, MUTED, false), ui.block(-1, -2, 14));
        layout.addView(controls, ui.block(350, -2, 0));
        root.addView(layout, ui.block(-1, -2, 0));
        setContentView(root);
        stop.requestFocus();
        AlarmActivity previous = ringing.getAndSet(this);
        if (previous != null && previous != this) previous.dismissRinging();
        try {
            alarm = new ToneGenerator(AudioManager.STREAM_ALARM, 85);
            handler.post(ring);
        } catch (RuntimeException ignored) {
            // Keep the alarm visible if the audio service is unavailable.
        }
        handler.postDelayed(this::dismissRinging, 120000);
    }

    private void showAlarms() {
        LinearLayout root = ui.column();
        root.setBackgroundColor(INK);
        root.setPadding(ui.dp(46), ui.dp(28), ui.dp(46), ui.dp(28));
        LinearLayout header = ui.row();
        LinearLayout heading = ui.column();
        heading.addView(ui.eyebrow("MAX STATION"));
        heading.addView(ui.text("Будильники", 38, WHITE, true));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button back = ui.action("К ассистенту", false);
        back.setOnClickListener(view -> finish());
        header.addView(back, ui.block(155, 48, 0));
        root.addView(header, ui.block(-1, -2, 0));

        LinearLayout schedule = ui.card();
        schedule.addView(ui.eyebrow("РАСПИСАНИЕ"));
        List<Long> upcoming = StationAlarm.upcoming(this);
        Button firstAlarm = null;
        if (upcoming.isEmpty()) {
            schedule.addView(ui.text("Пока нет будильников", 30, WHITE, true), ui.block(-1, -2, 19));
            schedule.addView(ui.text("Выбери время с пульта или попроси ассистента.", 20, MUTED, false),
                ui.block(-1, -2, 8));
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFocusable(false);
            LinearLayout rows = ui.column();
            for (long at : upcoming) {
                ZonedDateTime local = localTime(at);
                String time = CLOCK.format(local) + "    ·    " + DAY.format(local);
                Button row = ui.action(time, false);
                row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                row.setOnClickListener(view -> confirmRemove(at, time, row));
                rows.addView(row, ui.block(-1, 52, 9));
                if (firstAlarm == null) firstAlarm = row;
            }
            scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
            schedule.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout.LayoutParams scheduleParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scheduleParams.topMargin = ui.dp(22);
        root.addView(schedule, scheduleParams);

        LinearLayout footer = ui.row();
        Button add = ui.action("Новый будильник", true);
        add.setOnClickListener(view -> pickTime(add));
        footer.addView(add, ui.block(225, 52, 0));
        TextView hint = ui.text("OK · выбрать     Назад · к ассистенту", 15, MUTED, false);
        hint.setGravity(Gravity.END);
        footer.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(footer, ui.block(-1, 54, 18));
        add.setNextFocusUpId(firstAlarm == null ? back.getId() : firstAlarm.getId());
        back.setNextFocusDownId(firstAlarm == null ? add.getId() : firstAlarm.getId());
        setContentView(root);
        add.requestFocus();
    }

    private void pickTime(Button origin) {
        ZonedDateTime next = ZonedDateTime.now().plusMinutes(1);
        LinearLayout panel = ui.card();
        panel.addView(ui.eyebrow("НОВЫЙ БУДИЛЬНИК"));
        panel.addView(ui.text("Во сколько разбудить?", 34, WHITE, true), ui.block(-1, -2, 8));

        LinearLayout time = ui.row();
        time.setGravity(Gravity.CENTER);
        TimeField hour = new TimeField("Часы", 24, next.getHour());
        TimeField minute = new TimeField("Минуты", 60, next.getMinute());
        time.addView(hour, ui.block(168, 147, 0));
        TextView colon = ui.text(":", 40, MUTED, true);
        colon.setGravity(Gravity.CENTER);
        time.addView(colon, ui.block(40, -2, 0));
        time.addView(minute, ui.block(168, 147, 0));
        panel.addView(time, ui.block(-1, -2, 16));

        TextView date = ui.text("", 18, WHITE, true);
        date.setGravity(Gravity.CENTER);
        panel.addView(date, ui.block(-1, -2, 12));
        Runnable refreshDate = () -> date.setText(DAY.format(nextAlarm(hour.value, minute.value)));
        hour.changed = refreshDate;
        minute.changed = refreshDate;
        refreshDate.run();
        TextView help = ui.text("↑ ↓ меняют время   ·   ← → выбирают поле", 16, MUTED, false);
        help.setGravity(Gravity.CENTER);
        panel.addView(help, ui.block(-1, -2, 7));

        LinearLayout actions = ui.row();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = ui.action("Отмена", false);
        Button save = ui.action("Сохранить", true);
        actions.addView(cancel, ui.block(124, 48, 0));
        LinearLayout.LayoutParams saveButton = ui.block(149, 48, 0);
        saveButton.leftMargin = ui.dp(10);
        actions.addView(save, saveButton);
        panel.addView(actions, ui.block(-1, -2, 16));

        hour.setNextFocusLeftId(cancel.getId());
        hour.setNextFocusRightId(minute.getId());
        minute.setNextFocusLeftId(hour.getId());
        minute.setNextFocusRightId(save.getId());
        cancel.setNextFocusRightId(save.getId());
        cancel.setNextFocusUpId(hour.getId());
        save.setNextFocusUpId(minute.getId());
        save.setNextFocusLeftId(cancel.getId());
        hour.setOnClickListener(view -> minute.requestFocus());
        minute.setOnClickListener(view -> save.requestFocus());

        Dialog dialog = ui.overlay(panel, 636, hour);
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            String error = StationAlarm.scheduleAt(this, nextAlarm(hour.value, minute.value).toInstant().toEpochMilli());
            if (error != null) {
                help.setText("Не удалось сохранить. Выбери другое время.");
                return;
            }
            dialog.dismiss();
            showAlarms();
        });
        dialog.setOnDismissListener(ignored -> origin.requestFocus());
        dialog.show();
    }

    private void confirmRemove(long at, String time, Button origin) {
        LinearLayout panel = ui.card();
        panel.addView(ui.eyebrow("РАСПИСАНИЕ"));
        panel.addView(ui.text("Удалить будильник?", 32, WHITE, true), ui.block(-1, -2, 9));
        panel.addView(ui.text(time, 22, MUTED, false), ui.block(-1, -2, 11));
        LinearLayout actions = ui.row();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = ui.action("Оставить", false);
        Button remove = ui.action("Удалить", true);
        actions.addView(cancel, ui.block(128, 48, 0));
        LinearLayout.LayoutParams spacing = ui.block(128, 48, 0);
        spacing.leftMargin = ui.dp(10);
        actions.addView(remove, spacing);
        panel.addView(actions, ui.block(-1, -2, 23));
        cancel.setNextFocusRightId(remove.getId());
        remove.setNextFocusLeftId(cancel.getId());
        Dialog dialog = ui.overlay(panel, 515, cancel);
        cancel.setOnClickListener(view -> dialog.dismiss());
        remove.setOnClickListener(view -> {
            StationAlarm.cancel(this, at);
            dialog.dismiss();
            showAlarms();
        });
        dialog.setOnDismissListener(ignored -> origin.requestFocus());
        dialog.show();
    }

    private static ZonedDateTime nextAlarm(int hour, int minute) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime at = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        return at.isAfter(now.plusSeconds(1)) ? at : at.plusDays(1);
    }

    private final class TimeField extends LinearLayout {
        private final String title;
        private final int range;
        private final TextView number;
        private int value;
        private Runnable changed;

        TimeField(String title, int range, int value) {
            super(AlarmActivity.this);
            this.title = title;
            this.range = range;
            this.value = value;
            setId(View.generateViewId());
            setFocusable(true);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            StateListDrawable surface = new StateListDrawable();
            surface.addState(new int[]{android.R.attr.state_focused}, ui.shape(0xff4a4845, WHITE, 3, 16));
            surface.addState(new int[0], ui.shape(INK, BORDER, 1, 16));
            setBackground(surface);
            addView(centered(title.toUpperCase(new Locale("ru")), 14, MUTED, true));
            addView(centered("▴", 16, MUTED, true), ui.block(-1, 20, 4));
            number = ui.text("", 47, WHITE, true);
            number.setGravity(Gravity.CENTER);
            number.setIncludeFontPadding(false);
            number.setFontFeatureSettings("tnum");
            addView(number, ui.block(-1, 58, 0));
            addView(centered("▾", 16, MUTED, true), ui.block(-1, 20, 0));
            updateValue();
        }

        private TextView centered(String value, int size, int color, boolean strong) {
            TextView view = ui.text(value, size, color, strong);
            view.setGravity(Gravity.CENTER);
            return view;
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                value = Math.floorMod(value + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1), range);
                updateValue();
                if (changed != null) changed.run();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        private void updateValue() {
            String valueText = String.format(Locale.ROOT, "%02d", value);
            number.setText(valueText);
            setContentDescription(title + ", " + valueText);
        }
    }
    private static ZonedDateTime localTime(long epoch) {
        return Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault());
    }

    private void dismissRinging() {
        stopTone();
        finish();
    }

    private void stopTone() {
        ringing.compareAndSet(this, null);
        handler.removeCallbacksAndMessages(null);
        if (alarm != null) alarm.release();
        alarm = null;
    }

    @Override public void onDestroy() {
        stopTone();
        super.onDestroy();
    }
}
