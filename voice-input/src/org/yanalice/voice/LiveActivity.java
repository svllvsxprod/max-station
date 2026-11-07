package org.yanalice.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.RequestBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class LiveActivity extends Activity {
    private static final String TAG = "MaxStationLive";
    private static final int BLE_HEADSET = 26;
    private static final int CAPTURE_RATE = 16000;
    private static final int LIVE_RATE = 24000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false).build();

    private static final OkHttpClient BACKEND = CLIENT.newBuilder()
        .callTimeout(40, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build();
    private static final MediaType JSON = MediaType.get("application/json");

    private final Handler main = new Handler(Looper.getMainLooper());
    private LiveScreen screen;
    private Conversation conversation;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        screen = new LiveScreen(this, () -> {
            if (conversation != null) conversation.close();
            else start(false);
        }, useOverlay());
        handleLaunch(getIntent());
    }

    protected boolean useOverlay() { return false; }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleLaunch(intent);
    }

    private void handleLaunch(Intent intent) {
        if (intent.getComponent() != null && getClass().getName().equals(intent.getComponent().getClassName())) {
            if (intent.getBooleanExtra("probe", false)) start(true);
            else if (intent.getBooleanExtra("start", false) && conversation == null) {
                if (useOverlay()) playOpeningCue();
                start(false);
            }
        }
    }

    private void playOpeningCue() {
        try {
            ToneGenerator cue = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            cue.startTone(ToneGenerator.TONE_PROP_ACK, 120);
            main.postDelayed(cue::release, 180);
        } catch (RuntimeException e) {
            Log.w(TAG, "Assistant opening sound unavailable");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        screen.refreshBalance();
    }

    private void start(boolean probe) {
        if (conversation != null) return;
        if (!probe && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        Conversation next = new Conversation(probe);
        conversation = next;
        screen.render("Подключаюсь к OpenAI…", true);
        new Thread(next::connect, "live-connect").start();
        main.postDelayed(() -> {
            if (conversation == next) next.fail("session_limit");
        }, 150000);
    }

    @Override public void onStop() {
        if (conversation != null) {
            conversation.close();
            screen.render("Нажми OK и говори в пульт", false);
        }
        conversation = null;
        super.onStop();
    }

    private final class Conversation extends WebSocketListener {
        private final boolean probe;
        private final LiveTools tools = new LiveTools(LiveActivity.this);
        private final ArrayBlockingQueue<byte[]> output = new ArrayBlockingQueue<>(64);
        private final Set<String> delegations = new HashSet<>();
        private final ExecutorService backend = Executors.newSingleThreadExecutor();
        private final StringBuilder transcript = new StringBuilder();
        private String lastSpeaker = "";
        private volatile boolean closed;
        private volatile WebSocket socket;
        private volatile boolean ready;
        private long startedAt, startedElapsed, accountedMs;
        private boolean usageFinished;
        private Thread player;

        Conversation(boolean probe) { this.probe = probe; }

        void connect() {
            try {
                String key = SetupProvider.readOpenAI(LiveActivity.this);
                if (key == null) { fail("missing_key"); return; }
                if (closed) return;
                Request request = new Request.Builder().url("wss://api.openai.com/v1/live/sessions")
                    .header("Authorization", "Bearer " + key).build();
                WebSocket pending = CLIENT.newWebSocket(request, this);
                socket = pending;
                if (closed) pending.cancel();
                main.postDelayed(() -> { if (!ready && !closed) fail("startup_timeout"); }, 18000);
            } catch (Exception e) {
                fail(e.getClass().getSimpleName());
            }
        }

        @Override public void onOpen(WebSocket ws, Response response) {
            socket = ws;
            if (closed) { ws.cancel(); return; }
            try {
                send(new JSONObject().put("type", "session.start").put("session", tools.session()));
            } catch (JSONException e) {
                fail("invalid_configuration");
            }
        }

        @Override public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject event = new JSONObject(text);
                String type = event.getString("type");
                if ("session.usage.updated".equals(type) || "session.closed".equals(type)) {
                    JSONObject usage = event.optJSONObject("usage");
                    if (usage != null) accountUsage(Math.round(usage.getDouble("seconds") * 1000));
                    if ("session.closed".equals(type)) {
                        if (usage != null) Log.i(TAG, "Final billed seconds: " + usage.optDouble("seconds"));
                        synchronized (this) { usageFinished = true; }
                        close();
                        ws.close(1000, "complete");
                    }
                    return;
                }
                if (closed) return;
                switch (type) {
                    case "session.started":
                        synchronized (this) {
                            startedAt = System.currentTimeMillis();
                            startedElapsed = SystemClock.elapsedRealtime();
                        }
                        ready = true;
                        Log.i(TAG, "Session started");
                        show(probe ? "OpenAI подключён" : "Говори…");
                        if (probe) {
                            main.postDelayed(() -> {
                                if (!closed) {
                                    Log.i(TAG, "Connection probe passed");
                                    close("Прямое соединение с OpenAI проверено");
                                }
                            }, 2500);
                        } else {
                            player = new Thread(this::play, "live-speaker");
                            player.start();
                            new Thread(this::record, "live-microphone").start();
                        }
                        break;
                    case "session.output_audio.delta":
                        if (!probe && !output.offer(Base64.decode(event.getString("delta"), Base64.DEFAULT)))
                            fail("audio_buffer_full");
                        break;
                    case "session.input_transcript.delta":
                        rememberTranscript("Пользователь", event.optString("delta"));
                        break;
                    case "session.output_transcript.delta":
                        rememberTranscript("Ассистент", event.optString("delta"));
                        break;
                    case "session.delegation.created":
                        String id = event.getJSONObject("delegation").getString("id");
                        if (!probe && delegations.add(id)) backend.execute(() -> delegate(id));
                        break;
                    case "error":
                    case "session.error":
                        JSONObject error = event.optJSONObject("error");
                        if (error != null && error.optString("client_event_id").startsWith("memory-")) {
                            Log.w(TAG, "Could not refresh memory in the active conversation");
                            break;
                        }
                        if (error != null && "missing_scope".equals(error.optString("code"))
                                && error.optString("message").startsWith("History compaction is disabled")) {
                            Log.i(TAG, "History compaction unavailable for this session");
                            break;
                        }
                        if (probe && error != null) {
                            String detail = error.optString("message").replaceAll("sk-[A-Za-z0-9_-]+", "[hidden]");
                            Log.w(TAG, "Connection probe error: " + detail.substring(0, Math.min(detail.length(), 420)));
                        }
                        fail(error == null ? "openai_error" : error.optString("code", "openai_error"));
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                fail("invalid_event");
            }
        }

        @Override public void onFailure(WebSocket ws, Throwable e, Response response) {
            if (!closed) fail(response == null ? e.getClass().getSimpleName() : "http_" + response.code());
        }

        @Override public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(code, null);
            if (!closed) close("Соединение завершено. Нажми OK, чтобы начать снова.");
        }

        @Override public void onClosed(WebSocket ws, int code, String reason) {
            if (!closed) fail("closed_" + code);
        }

        private synchronized void rememberTranscript(String speaker, String text) {
            if (text.isEmpty()) return;
            if (!lastSpeaker.equals(speaker)) transcript.append('\n').append(speaker).append(": ");
            transcript.append(text);
            lastSpeaker = speaker;
            main.post(() -> { if (conversation == this && !closed) screen.caption(speaker, text); });
            if (transcript.length() > 7000) transcript.delete(0, transcript.length() - 6500);
        }

        private synchronized String snapshotTranscript() { return transcript.toString(); }

        private void delegate(String id) {
            try {
                Thread.sleep(550);
                if (closed) return;
                String context = snapshotTranscript();
                if (context.isEmpty()) {
                    commentary(id, "Я не разобрал команду. Повтори, пожалуйста.");
                    return;
                }
                JSONArray input = new JSONArray().put(new JSONObject().put("role", "user")
                    .put("content", "Стенограмма сеанса. Выполни последний запрос пользователя: " + context));
                String key = SetupProvider.readOpenAI(LiveActivity.this);
                if (key == null) { commentary(id, "Ключ OpenAI недоступен."); return; }
                Set<String> executed = new HashSet<>();
                for (int round = 0; round < 6 && !closed; round++) {
                    Request request = new Request.Builder().url("https://api.openai.com/v1/responses")
                        .header("Authorization", "Bearer " + key)
                        .post(RequestBody.create(tools.backend(input).toString(), JSON)).build();
                    JSONObject response;
                    try (Response http = BACKEND.newCall(request).execute()) {
                        if (!http.isSuccessful() || http.body() == null) {
                            Log.w(TAG, "Responses HTTP " + http.code());
                            commentary(id, "Сейчас не удалось выполнить команду.");
                            return;
                        }
                        response = new JSONObject(http.body().string());
                    }
                    ApiCredits.addResponse(LiveActivity.this, response);
                    main.post(screen::refreshBalance);
                    JSONArray items = response.getJSONArray("output");
                    JSONArray results = new JSONArray();
                    StringBuilder answer = new StringBuilder();
                    for (int i = 0; i < items.length() && !closed; i++) {
                        JSONObject item = items.getJSONObject(i);
                        input.put(item);
                        if ("function_call".equals(item.optString("type"))) {
                            String callId = item.getString("call_id");
                            if (!executed.add(callId)) continue;
                            JSONObject result;
                            String name = item.getString("name");
                            try {
                                result = tools.execute(name, new JSONObject(item.getString("arguments")));
                            } catch (Exception e) {
                                result = LiveTools.error("command_failed");
                            }
                            Log.i(TAG, "Tool " + name + " " + result.optString("status"));
                            if ("remember".equals(name) && "saved".equals(result.optString("status"))) {
                                send(new JSONObject().put("type", "session.instructions.append")
                                    .put("event_id", "memory-" + callId).put("delegation_id", id)
                                    .put("content", "Только что сохранённые данные заменяют предыдущие значения этих полей. "
                                        + LiveTools.profileInstructions(result.getJSONObject("saved"))));
                            }
                            results.put(new JSONObject().put("type", "function_call_output")
                                .put("call_id", callId).put("output", result.toString()));
                        } else if ("message".equals(item.optString("type"))) {
                            JSONArray content = item.optJSONArray("content");
                            if (content == null) continue;
                            for (int j = 0; j < content.length(); j++) {
                                JSONObject part = content.getJSONObject(j);
                                if ("output_text".equals(part.optString("type"))) answer.append(part.optString("text"));
                            }
                        }
                    }
                    if (results.length() == 0) {
                        commentary(id, answer.length() == 0 ? "Не удалось получить результат." : answer.toString());
                        return;
                    }
                    for (int j = 0; j < results.length(); j++) input.put(results.get(j));
                }
                if (!closed) commentary(id, "Команду не удалось завершить.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (!closed) {
                    Log.w(TAG, "Delegation stopped: " + e.getClass().getSimpleName());
                    try { commentary(id, "Сейчас не удалось выполнить команду."); }
                    catch (JSONException ignored) {}
                }
            }
        }

        private void commentary(String id, String result) throws JSONException {
            send(new JSONObject().put("type", "session.commentary.append").put("delegation_id", id)
                .put("content", result.substring(0, Math.min(result.length(), 1300))));
        }

        private void record() {
            AudioManager manager = getSystemService(AudioManager.class);
            boolean remote = GroqRecognitionService.useRemoteMic(new Intent());
            boolean reopening = false;
            while (!closed && capture(manager, remote, reopening)) {
                if (!remote) { fail("microphone_stalled"); return; }
                reopening = true;
                show("Переподключаю микрофон пульта…");
                Log.i(TAG, "Reopening remote microphone after input timeout");
                SystemClock.sleep(300);
            }
        }

        /** Returns true if the stream stalled and can be reopened. */
        private boolean capture(AudioManager manager, boolean remote, boolean reopening) {
            AudioRecord microphone = null;
            try {
                if (manager.isMicrophoneMute()) { fail("microphone_muted"); return false; }
                int channel = AudioFormat.CHANNEL_IN_MONO;
                int minimum = AudioRecord.getMinBufferSize(CAPTURE_RATE, channel, ENCODING);
                int source = remote ? MediaRecorder.AudioSource.VOICE_RECOGNITION : MediaRecorder.AudioSource.MIC;
                microphone = new AudioRecord(source, CAPTURE_RATE, channel, ENCODING, Math.max(minimum, 3200));
                int type = remote ? BLE_HEADSET : AudioDeviceInfo.TYPE_BUILTIN_MIC;
                boolean selected = false;
                for (AudioDeviceInfo input : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (input.getType() == type) {
                        selected = microphone.setPreferredDevice(input);
                        break;
                    }
                }
                if ((remote && !selected) || microphone.getState() != AudioRecord.STATE_INITIALIZED) {
                    fail("microphone_unavailable");
                    return false;
                }
                if (closed) return false;
                microphone.startRecording();
                if (microphone.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    fail("microphone_unavailable");
                    return false;
                }
                Log.i(TAG, "Capture started, remote=" + remote);
                short[] frame = new short[640];
                int pending = 0;
                long lastSamples = SystemClock.elapsedRealtime();
                long lastSignal = lastSamples;
                boolean awaitingSignal = reopening;
                while (!closed) {
                    if (manager.isMicrophoneMute()) { fail("microphone_muted"); return false; }
                    int count = microphone.read(frame, pending, frame.length - pending, AudioRecord.READ_NON_BLOCKING);
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) break;
                    if (count < 0) { fail("microphone_read"); return false; }
                    long now = SystemClock.elapsedRealtime();
                    if (count == 0) {
                        if (now - lastSamples >= 4000) break;
                        SystemClock.sleep(12);
                        continue;
                    }
                    lastSamples = now;
                    AudioDeviceInfo route = microphone.getRoutedDevice();
                    if (remote && route != null && route.getType() != BLE_HEADSET) {
                        fail("remote_route_changed");
                        return false;
                    }
                    if (remote && hasSignal(frame, pending, count)) {
                        lastSignal = now;
                        if (awaitingSignal) {
                            awaitingSignal = false;
                            Log.i(TAG, "Remote microphone recovered");
                            show("Говори…");
                        }
                    } else if (remote && now - lastSignal >= 5000) {
                        break;
                    }
                    pending += count;
                    if (pending == frame.length) {
                        byte[] pcm = resample(frame);
                        send(new JSONObject().put("type", "session.input_audio.append")
                            .put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP)));
                        pending = 0;
                    }
                }
                if (closed) return false;
                if (awaitingSignal) { fail("microphone_stalled"); return false; }
                return true;
            } catch (Exception e) {
                if (!closed) fail("microphone_" + e.getClass().getSimpleName());
                return false;
            } finally {
                if (microphone != null) microphone.release();
            }
        }

        private boolean hasSignal(short[] frame, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                if (frame[i] != 0) return true;
            }
            return false;
        }

        private void play() {
            AudioTrack track = null;
            try {
                int channel = AudioFormat.CHANNEL_OUT_MONO;
                int buffer = AudioTrack.getMinBufferSize(LIVE_RATE, channel, ENCODING);
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(LIVE_RATE).setEncoding(ENCODING)
                        .setChannelMask(channel).build())
                    .setBufferSizeInBytes(Math.max(buffer, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build();
                track.play();
                while (!closed) {
                    byte[] pcm = output.poll(250, TimeUnit.MILLISECONDS);
                    if (pcm == null) continue;
                    for (int offset = 0; offset < pcm.length && !closed;) {
                        int count = track.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                        if (count < 0) { fail("speaker_write"); return; }
                        if (count == 0) SystemClock.sleep(10);
                        offset += count;
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                if (!closed) fail("speaker_" + e.getClass().getSimpleName());
            } finally {
                if (track != null) track.release();
            }
        }

        private void send(JSONObject event) {
            WebSocket ws = socket;
            if (!closed && ws != null && (ws.queueSize() > 512000 || !ws.send(event.toString()))) {
                fail("connection_queue");
            }
        }

        void close() { close("Разговор завершён"); }

        synchronized void close(String message) {
            if (closed) return;
            closed = true;
            WebSocket ws = socket;
            if (ws != null) {
                if (ready && !usageFinished && ws.send("{\"type\":\"session.close\"}")) {
                    long elapsed = SystemClock.elapsedRealtime() - startedElapsed;
                    main.postDelayed(() -> finishUnconfirmedUsage(ws, elapsed), 5000);
                } else {
                    finishUnconfirmedUsage(ws, SystemClock.elapsedRealtime() - startedElapsed);
                }
            }
            if (player != null) player.interrupt();
            output.clear();
            backend.shutdownNow();
            main.post(() -> {
                if (conversation != this) return;
                conversation = null;
                screen.render(message, false);
            });
        }

        private synchronized void accountUsage(long totalMs) {
            if (usageFinished || startedAt == 0 || totalMs <= accountedMs) return;
            ApiCredits.addLive(LiveActivity.this, startedAt, accountedMs, totalMs);
            accountedMs = totalMs;
            main.post(screen::refreshBalance);
        }

        private synchronized void finishUnconfirmedUsage(WebSocket ws, long elapsed) {
            if (usageFinished) return;
            if (ready) {
                accountUsage(elapsed);
                Log.i(TAG, "Final usage estimated from session time");
            }
            usageFinished = true;
            ws.cancel();
        }

        void fail(String reason) {
            if (closed) return;
            String code = reason.replaceAll("[^A-Za-z0-9_-]", "_");
            Log.w(TAG, "Session stopped: " + code);
            if ("microphone_stalled".equals(code) || "remote_route_changed".equals(code)) {
                close("Пропал сигнал микрофона. Нажми OK, чтобы начать снова.");
            } else {
                close("Ассистент остановлен (" + code + ")");
            }
        }

        private void show(String text) {
            main.post(() -> { if (conversation == this) screen.render(text, true); });
        }
    }

    private static byte[] resample(short[] source) {
        byte[] pcm = new byte[source.length * 3];
        for (int i = 0, out = 0; i < source.length; i += 2) {
            int a = source[i];
            int b = source[i + 1];
            int c = i + 2 == source.length ? b : source[i + 2];
            out = put(pcm, out, a);
            out = put(pcm, out, (a + 2 * b) / 3);
            out = put(pcm, out, (2 * b + c) / 3);
        }
        return pcm;
    }

    private static int put(byte[] pcm, int position, int sample) {
        pcm[position] = (byte) sample;
        pcm[position + 1] = (byte) (sample >> 8);
        return position + 2;
    }
}
