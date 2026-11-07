package org.yanalice.voice;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class TvUi {
    static final int INK = 0xff171717;
    static final int PANEL = 0xff202020;
    static final int SURFACE = 0xff303030;
    static final int BORDER = 0xff484848;
    static final int WHITE = 0xfffaf9f6;
    static final int MUTED = 0xffbbb9b5;

    private final Activity activity;

    TvUi(Activity activity) { this.activity = activity; }

    int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    TextView text(String value, float size, int color, boolean strong) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    TextView eyebrow(String value) {
        TextView view = text(value, 14, MUTED, true);
        view.setLetterSpacing(.12f);
        return view;
    }

    Button action(String value, boolean primary) {
        Button view = new Button(activity);
        view.setId(View.generateViewId());
        view.setText(value);
        view.setTextSize(16);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setAllCaps(false);
        view.setMinHeight(0);
        view.setMinWidth(0);
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setSingleLine(true);
        view.setStateListAnimator(null);
        int[][] states = {{-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {}};
        view.setTextColor(new ColorStateList(states, new int[]{0xff92918f, INK, WHITE}));
        StateListDrawable surface = new StateListDrawable();
        surface.addState(states[0], shape(PANEL, BORDER, 1, 13));
        surface.addState(states[1], shape(WHITE, WHITE, 2, 13));
        surface.addState(states[2], shape(primary ? SURFACE : PANEL, BORDER, 1, 13));
        view.setBackground(surface);
        return view;
    }

    GradientDrawable shape(int fill, int stroke, int width, int radius) {
        GradientDrawable view = new GradientDrawable();
        view.setColor(fill);
        view.setCornerRadius(dp(radius));
        if (width > 0) view.setStroke(dp(width), stroke);
        return view;
    }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(activity);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    LinearLayout.LayoutParams block(int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        params.topMargin = dp(top);
        return params;
    }

    Dialog overlay(LinearLayout panel, int width, View focus) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        FrameLayout stage = new FrameLayout(activity);
        stage.setBackgroundColor(0xaa080808);
        stage.setPadding(dp(36), dp(24), dp(36), dp(24));
        int available = activity.getResources().getDisplayMetrics().widthPixels - dp(72);
        FrameLayout.LayoutParams card = new FrameLayout.LayoutParams(
            Math.min(dp(width), available), -2, Gravity.CENTER);
        stage.addView(panel, card);
        dialog.setContentView(stage);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setLayout(-1, -1);
        dialog.setOnShowListener(ignored -> focus.requestFocus());
        return dialog;
    }

    LinearLayout card() {
        LinearLayout panel = column();
        panel.setPadding(dp(28), dp(24), dp(28), dp(24));
        panel.setBackground(shape(PANEL, BORDER, 1, 22));
        return panel;
    }
}
