package org.yanalice.voice;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

public final class VoiceActivity extends Activity implements RecognitionListener {
    private TextView message;
    private Button listen;
    private SpeechRecognizer recognizer;
    private boolean remote;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        remote = GroqRecognitionService.useRemoteMic(getIntent());
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);
        message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setTextSize(22);
        message.setText((remote ? "Микрофон пульта" : "Голосовой ввод Max Station")
            + "\nПосле нажатия звук отправляется в Groq.");
        listen = new Button(this);
        listen.setText("Начать распознавание");
        listen.setOnClickListener(view -> start());
        layout.addView(message);
        layout.addView(listen);
        setContentView(layout);
        listen.requestFocus();
    }

    private void start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this,
                new ComponentName(this, GroqRecognitionService.class));
            recognizer.setRecognitionListener(this);
        }
        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String language = getIntent().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language == null ? "ru-RU" : language);
        request.putExtra(GroqRecognitionService.EXTRA_REMOTE_MIC, remote);
        listen.setEnabled(false);
        message.setText("Включаю микрофон…");
        recognizer.startListening(request);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            message.setText("Микрофон разрешён. Нажми кнопку, чтобы начать.");
        }
    }

    @Override public void onStop() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        listen.setEnabled(true);
        super.onStop();
    }

    @Override public void onReadyForSpeech(Bundle params) { message.setText("Говори…"); }
    @Override public void onEndOfSpeech() { message.setText("Распознаю…"); }
    @Override public void onError(int error) {
        message.setText("Ошибка распознавания (" + error + ").");
        listen.setEnabled(true);
        listen.requestFocus();
    }
    @Override public void onResults(Bundle result) {
        ArrayList<String> matches = result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(getIntent().getAction())) {
            setResult(RESULT_OK, new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, matches));
            finish();
        } else {
            message.setText(matches == null || matches.isEmpty() ? "Нет результата." : matches.get(0));
            listen.setEnabled(true);
            listen.requestFocus();
        }
    }
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rms) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onPartialResults(Bundle partial) {}
    @Override public void onEvent(int type, Bundle params) {}
}
