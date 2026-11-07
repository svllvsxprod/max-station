package org.yanalice.voice;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import static org.yanalice.voice.TvUi.*;

final class LiveScreen {
    private final Activity activity;
    private final TvUi ui;
    private final TextView status;
    private final Button primary;
    private final Button billing;
    private final Button alarms;
    private final Orb orb;
    private final TextView idleTranscript;
    private final LinearLayout captions;
    private final TextView lastUser;
    private final TextView lastAssistant;
    private final StringBuilder spokenUser = new StringBuilder();
    private final StringBuilder spokenAssistant = new StringBuilder();
    private String lastCaptionSpeaker = "";

    LiveScreen(Activity activity, Runnable onPrimary, boolean overlay) {
        this.activity = activity;
        ui = new TvUi(activity);
        if (overlay) {
            billing = null;
            alarms = null;
            FrameLayout stage = new FrameLayout(activity);
            LinearLayout panel = ui.card();
            LinearLayout header = ui.row();
            orb = new Orb(activity);
            header.addView(orb, ui.block(56, 56, 0));
            LinearLayout identity = ui.column();
            identity.addView(ui.eyebrow("MAX STATION"));
            identity.addView(ui.text("ChatGPT", 24, WHITE, true));
            header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            panel.addView(header);
            status = ui.text("", 17, MUTED, false);
            status.setMaxLines(2);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            panel.addView(status, ui.block(-1, -2, 8));
            idleTranscript = null;
            captions = ui.column();
            captions.setVisibility(View.GONE);
            lastUser = addTurn(captions, "ТЫ");
            lastAssistant = addTurn(captions, "CHATGPT");
            lastUser.setMaxLines(2);
            lastAssistant.setMaxLines(3);
            panel.addView(captions);
            LinearLayout actions = ui.row();
            primary = ui.action("Начать разговор", true);
            primary.setOnClickListener(view -> onPrimary.run());
            actions.addView(primary, ui.block(204, 48, 0));
            Button back = ui.action("Закрыть", false);
            back.setOnClickListener(view -> activity.finish());
            LinearLayout.LayoutParams backParams = ui.block(104, 48, 0);
            backParams.leftMargin = ui.dp(10);
            actions.addView(back, backParams);
            panel.addView(actions, ui.block(-1, -2, 18));
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(ui.dp(413), -2, Gravity.BOTTOM | Gravity.END);
            position.setMargins(ui.dp(32), ui.dp(24), ui.dp(32), ui.dp(30));
            stage.addView(panel, position);
            activity.setContentView(stage);
            render("Нажми OK и говори в пульт.", false);
            primary.requestFocus();
            return;
        }
        LinearLayout root = ui.column();
        root.setBackgroundColor(INK);
        root.setPadding(ui.dp(46), ui.dp(28), ui.dp(46), ui.dp(26));

        LinearLayout header = ui.row();
        ImageView provider = new ImageView(activity);
        provider.setImageResource(R.drawable.openai_provider);
        provider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(provider, ui.block(37, 37, 0));
        LinearLayout identity = ui.column();
        identity.setPadding(ui.dp(10), 0, 0, 0);
        identity.addView(ui.text("ChatGPT", 22, WHITE, true));
        identity.addView(ui.eyebrow("MAX STATION"));
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        alarms = ui.action("Будильники", false);
        alarms.setOnClickListener(view -> activity.startActivity(new Intent(activity, AlarmActivity.class)));
        header.addView(alarms, ui.block(136, 48, 0));
        billing = ui.action("", false);
        billing.setOnClickListener(view -> showBilling());
        LinearLayout.LayoutParams creditButton = ui.block(214, 48, 0);
        creditButton.leftMargin = ui.dp(12);
        header.addView(billing, creditButton);
        root.addView(header, ui.block(-1, 55, 0));

        LinearLayout body = ui.row();
        LinearLayout voice = ui.column();
        voice.setGravity(Gravity.CENTER_VERTICAL);
        voice.setPadding(0, 0, ui.dp(20), 0);
        orb = new Orb(activity);
        voice.addView(orb, ui.block(106, 106, 0));
        TextView title = ui.text("Чем могу помочь?", 38, WHITE, true);
        voice.addView(title, ui.block(-1, -2, 4));
        status = ui.text("", 18, MUTED, false);
        status.setMinHeight(ui.dp(46));
        status.setMaxLines(2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        voice.addView(status, ui.block(-1, -2, 7));
        primary = ui.action("Начать разговор", true);
        primary.setOnClickListener(view -> onPrimary.run());
        voice.addView(primary, ui.block(248, 55, 10));
        body.addView(voice, new LinearLayout.LayoutParams(0, -1, 1.05f));

        LinearLayout transcript = ui.card();
        transcript.addView(ui.eyebrow("РАЗГОВОР"));
        idleTranscript = ui.text("Здесь появятся твои слова и ответы ассистента.", 23, MUTED, false);
        transcript.addView(idleTranscript, ui.block(-1, -2, 18));
        captions = ui.column();
        captions.setVisibility(View.GONE);
        lastUser = addTurn(captions, "ТЫ");
        lastAssistant = addTurn(captions, "CHATGPT");
        transcript.addView(captions, ui.block(-1, -2, 0));
        body.addView(transcript, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, 0, 1);
        bodyParams.topMargin = ui.dp(22);
        bodyParams.bottomMargin = ui.dp(18);
        root.addView(body, bodyParams);

        LinearLayout footer = ui.row();
        footer.addView(ui.text("Спроси о погоде, поставь будильник или найди видео.", 16, MUTED, false),
            new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(ui.text("OK · выбрать", 15, MUTED, false));
        root.addView(footer, ui.block(-1, 30, 0));

        primary.setNextFocusUpId(alarms.getId());
        primary.setNextFocusRightId(alarms.getId());
        alarms.setNextFocusLeftId(primary.getId());
        alarms.setNextFocusDownId(primary.getId());
        alarms.setNextFocusRightId(billing.getId());
        billing.setNextFocusLeftId(alarms.getId());
        billing.setNextFocusDownId(primary.getId());
        activity.setContentView(root);
        render("Нажми OK и говори в пульт.", false);
        primary.requestFocus();
        if (ValueAnimator.areAnimatorsEnabled()) {
            orb.setAlpha(0f);
            orb.setScaleX(.94f);
            orb.setScaleY(.94f);
            orb.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start();
        }
    }

    private TextView addTurn(LinearLayout captions, String speaker) {
        LinearLayout turn = ui.column();
        turn.addView(ui.eyebrow(speaker));
        TextView line = ui.text("", 18, WHITE, false);
        line.setMaxLines(4);
        line.setEllipsize(TextUtils.TruncateAt.START);
        turn.addView(line, ui.block(-1, -2, 4));
        captions.addView(turn, ui.block(-1, -2, 13));
        return line;
    }

    void render(String message, boolean active) {
        status.setText(message);
        primary.setText(active ? "Закончить разговор" : "Начать разговор");
        if (billing != null) billing.setEnabled(!active);
        if (alarms != null) alarms.setEnabled(!active);
        refreshBalance();
        orb.setActive(active);
        if (!active) {
            spokenUser.setLength(0);
            spokenAssistant.setLength(0);
            lastCaptionSpeaker = "";
            captions.setVisibility(View.GONE);
            if (idleTranscript != null) {
                idleTranscript.setVisibility(View.VISIBLE);
                idleTranscript.setText("Здесь появятся твои слова и ответы ассистента.");
            }
        } else if (idleTranscript != null && lastCaptionSpeaker.isEmpty()) {
            idleTranscript.setText("Говори обычным голосом. Реплики появятся здесь.");
        }
    }

    void refreshBalance() {
        if (billing != null) billing.setText("API‑кредиты  ·  " + ApiCredits.remaining(activity));
    }

    void caption(String speaker, String delta) {
        boolean user = "Пользователь".equals(speaker);
        StringBuilder target = user ? spokenUser : spokenAssistant;
        if (!speaker.equals(lastCaptionSpeaker)) target.setLength(0);
        target.append(delta);
        if (target.length() > 420) target.delete(0, target.length() - 360);
        lastCaptionSpeaker = speaker;
        lastUser.setText(spokenUser);
        lastAssistant.setText(spokenAssistant);
        ((View) lastUser.getParent()).setVisibility(spokenUser.length() == 0 ? View.GONE : View.VISIBLE);
        ((View) lastAssistant.getParent()).setVisibility(spokenAssistant.length() == 0 ? View.GONE : View.VISIBLE);
        if (idleTranscript != null) idleTranscript.setVisibility(View.GONE);
        captions.setVisibility(View.VISIBLE);
    }

    private void showBilling() {
        LinearLayout panel = ui.card();
        panel.addView(ui.eyebrow("OPENAI PLATFORM"));
        panel.addView(ui.text("API‑кредиты", 35, WHITE, true), ui.block(-1, -2, 8));
        LinearLayout details = ui.row();
        LinearLayout balance = ui.column();
        TextView amount = ui.text(ApiCredits.remaining(activity), 42, WHITE, true);
        amount.setFontFeatureSettings("tnum");
        balance.addView(amount);
        balance.addView(ui.text("Расчётный остаток", 19, MUTED, false));
        balance.addView(ui.text(ApiCredits.summary(activity), 16, WHITE, false), ui.block(-1, -2, 12));
        balance.addView(ui.text("Сканируй QR, чтобы открыть точный баланс в OpenAI Platform.",
            17, MUTED, false), ui.block(-1, -2, 11));
        balance.setPadding(0, 0, ui.dp(18), 0);
        details.addView(balance, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView qr = new ImageView(activity);
        qr.setImageResource(R.drawable.api_billing_qr);
        qr.setContentDescription("QR-код страницы оплаты OpenAI Platform");
        details.addView(qr, ui.block(184, 184, 0));
        panel.addView(details, ui.block(-1, -2, 12));
        Button close = ui.action("Готово", true);
        panel.addView(close, ui.block(150, 48, 16));
        Dialog dialog = ui.overlay(panel, 652, close);
        close.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> billing.requestFocus());
        dialog.show();
    }
    private static final class Orb extends View {
        private static final float[] BAR_HEIGHTS = {.15f, .29f, .42f, .29f, .15f};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f);
        private Shader idleGlow, activeGlow, idleSurface, activeSurface;
        private float phase;
        private boolean active;

        Orb(Activity activity) {
            super(activity);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            pulse.setDuration(1300);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            });
        }

        void setActive(boolean active) {
            if (this.active == active) return;
            this.active = active;
            if (active && ValueAnimator.areAnimatorsEnabled()) pulse.start();
            else {
                pulse.cancel();
                phase = 0f;
            }
            invalidate();
        }

        @Override protected void onDetachedFromWindow() {
            pulse.cancel();
            super.onDetachedFromWindow();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            float x = width * .5f, y = height * .5f;
            float r = Math.min(x, y) * .83f;
            if (r <= 0) return;
            idleGlow = new RadialGradient(x, y, r, 0xff444444, 0x00505050, Shader.TileMode.CLAMP);
            activeGlow = new RadialGradient(x, y, r, 0xff696969, 0x00505050, Shader.TileMode.CLAMP);
            idleSurface = new RadialGradient(x-r*.2f, y-r*.35f, r * 1.25f,
                0xffe6e6e6, 0xff8c8c8c, Shader.TileMode.CLAMP);
            activeSurface = new RadialGradient(x-r*.2f, y-r*.35f, r * 1.25f,
                0xfff8f8f8, 0xff8c8c8c, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth() * .5f, y = getHeight() * .5f;
            float r = Math.min(x, y) * .83f;
            canvas.save();
            float scale = 1f + .065f * phase;
            canvas.scale(scale, scale, x, y);
            paint.setShader(active ? activeGlow : idleGlow);
            canvas.drawCircle(x, y, r * 1.2f, paint);
            paint.setShader(active ? activeSurface : idleSurface);
            canvas.drawCircle(x, y, r*.76f, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, r * .015f));
            paint.setColor(active ? WHITE : 0xffb1b1b1);
            canvas.drawCircle(x, y, r * .76f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(INK);
            for (int i = 0; i < BAR_HEIGHTS.length; i++) {
                float bar = x + (i - 2) * r * .18f;
                float wave = active ? .085f * (float) Math.sin(phase * Math.PI * 2 + i * .92) : 0f;
                float w = r * .043f, h = r * (BAR_HEIGHTS[i] + wave);
                canvas.drawRoundRect(bar-w, y-h, bar+w, y+h, w, w, paint);
            }
            canvas.restore();
        }
    }
}
