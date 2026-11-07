package org.yanalice.voice;

import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.InputDevice;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

public final class GroqRecognitionService extends RecognitionService {
    static final String EXTRA_REMOTE_MIC = "org.yanalice.voice.extra.REMOTE_MIC";
    // This Android 9 firmware exposes its BLE-In port as TYPE_BLE_HEADSET (26).
    private static final int BLE_HEADSET = 26;
    private static final int RATE = 16000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Session active;

    private static final class Session {
        final Callback callback;
        final boolean remote;
        volatile boolean stop;
        volatile boolean cancel;

        Session(Callback callback, boolean remote) {
            this.callback = callback;
            this.remote = remote;
        }
    }

    private static final class RecognitionError extends Exception {
        final int code;

        RecognitionError(int code) { this.code = code; }
    }

    @Override protected void onStartListening(Intent intent, Callback callback) {
        Session session = new Session(callback, useRemoteMic(intent));
        active = session;
        String requested = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        String language = Locale.forLanguageTag(requested == null
            ? Locale.getDefault().toLanguageTag() : requested).getLanguage();
        worker.execute(() -> recognize(session, language));
    }

    static boolean useRemoteMic(Intent intent) {
        if (intent.hasExtra(EXTRA_REMOTE_MIC)) {
            return intent.getBooleanExtra(EXTRA_REMOTE_MIC, false);
        }
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            // Bluetooth HID identifier of the paired first-generation Max remote.
            if (device != null && device.getVendorId() == 0x057a && device.getProductId() == 0x0058) {
                return true;
            }
        }
        return false;
    }

    @Override protected void onStopListening(Callback callback) {
        if (active != null) active.stop = true;
    }

    @Override protected void onCancel(Callback callback) {
        if (active != null) active.cancel = true;
        active = null;
    }

    @Override public void onDestroy() {
        onCancel(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void recognize(Session session, String language) {
        try {
            if (session.cancel) return;
            String key = SetupProvider.read(this);
            if (key == null) throw new RecognitionError(SpeechRecognizer.ERROR_CLIENT);
            byte[] wav = capture(session);
            if (session.cancel) return;
            session.callback.endOfSpeech();
            String transcript = transcribe(key, language, wav);
            if (transcript.isEmpty()) throw new RecognitionError(SpeechRecognizer.ERROR_NO_MATCH);
            ArrayList<String> results = new ArrayList<>();
            results.add(transcript);
            Bundle bundle = new Bundle();
            bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, results);
            finish(session, bundle, 0);
        } catch (RecognitionError e) {
            finish(session, null, e.code);
        } catch (SocketTimeoutException e) {
            finish(session, null, SpeechRecognizer.ERROR_NETWORK_TIMEOUT);
        } catch (IOException e) {
            finish(session, null, SpeechRecognizer.ERROR_NETWORK);
        } catch (SecurityException e) {
            finish(session, null, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
        } catch (Exception e) {
            finish(session, null, SpeechRecognizer.ERROR_CLIENT);
        }
    }

    private void finish(Session session, Bundle result, int error) {
        main.post(() -> {
            if (active != session || session.cancel) return;
            active = null;
            try {
                if (result == null) session.callback.error(error);
                else session.callback.results(result);
            } catch (RemoteException ignored) {
                // The calling app closed its recognition session.
            }
        });
    }

    private byte[] capture(Session session) throws RecognitionError, RemoteException {
        int channel = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int bufferSize = AudioRecord.getMinBufferSize(RATE, channel, encoding);
        if (bufferSize <= 0) throw new RecognitionError(SpeechRecognizer.ERROR_AUDIO);
        int source = session.remote ? MediaRecorder.AudioSource.VOICE_RECOGNITION
            : MediaRecorder.AudioSource.MIC;
        AudioRecord microphone = new AudioRecord(source,
            RATE, channel, encoding, Math.max(bufferSize, RATE / 5));
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] frame = new byte[640];
        boolean speech = false;
        long lastSpeech = 0;
        try {
            if (microphone.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RecognitionError(SpeechRecognizer.ERROR_AUDIO);
            }
            AudioManager manager = getSystemService(AudioManager.class);
            int desiredType = session.remote ? BLE_HEADSET : AudioDeviceInfo.TYPE_BUILTIN_MIC;
            boolean selected = false;
            for (AudioDeviceInfo input : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (input.getType() == desiredType) {
                    selected = microphone.setPreferredDevice(input);
                    break;
                }
            }
            if (session.remote && !selected) throw new RecognitionError(SpeechRecognizer.ERROR_AUDIO);
            microphone.startRecording();
            if (microphone.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new RecognitionError(SpeechRecognizer.ERROR_AUDIO);
            }
            session.callback.readyForSpeech(new Bundle());
            long start = SystemClock.elapsedRealtime();
            while (!session.cancel && !session.stop && pcm.size() < RATE * 2 * 12) {
                int count = microphone.read(frame, 0, frame.length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new RecognitionError(SpeechRecognizer.ERROR_AUDIO);
                long now = SystemClock.elapsedRealtime();
                if (now - start >= 12000 || (!speech && now - start >= 8000)) break;
                if (session.remote) {
                    AudioDeviceInfo route = microphone.getRoutedDevice();
                    // This firmware reports no route here even when AudioPolicy has
                    // opened BLE-In. Reject a conflicting route when one is reported.
                    if (route != null && route.getType() != BLE_HEADSET) {
                        throw new RecognitionError(SpeechRecognizer.ERROR_AUDIO);
                    }
                }
                if (count == 0) {
                    SystemClock.sleep(15);
                    continue;
                }
                pcm.write(frame, 0, count);
                int rms = rms(frame, count);
                if (rms >= 45) {
                    lastSpeech = now;
                    if (!speech) {
                        speech = true;
                        session.callback.beginningOfSpeech();
                    }
                }
                if (speech && now - lastSpeech >= 1050) break;
            }
        } finally {
            microphone.release();
        }
        if (!speech && !session.stop) throw new RecognitionError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
        if (pcm.size() < RATE / 4) throw new RecognitionError(SpeechRecognizer.ERROR_NO_MATCH);
        return wave(pcm.toByteArray());
    }

    private static int rms(byte[] frame, int count) {
        long energy = 0;
        for (int i = 0; i + 1 < count; i += 2) {
            int sample = (short) ((frame[i] & 255) | (frame[i + 1] << 8));
            energy += (long) sample * sample;
        }
        return count < 2 ? 0 : (int) Math.sqrt(energy / (count / 2));
    }

    private static byte[] wave(byte[] pcm) {
        ByteBuffer buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46464952).putInt(36 + pcm.length).putInt(0x45564157);
        buffer.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1);
        buffer.putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        buffer.putInt(0x61746164).putInt(pcm.length).put(pcm);
        return buffer.array();
    }

    private static String transcribe(String key, String language, byte[] wav) throws Exception {
        URL url = new URL("https://api.groq.com/openai/v1/audio/transcriptions");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        String boundary = UUID.randomUUID().toString();
        try {
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(8192);
            try (OutputStream out = connection.getOutputStream()) {
                field(out, boundary, "model", "whisper-large-v3-turbo");
                if (language.matches("[a-z]{2}")) field(out, boundary, "language", language);
                write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                    + "filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
                out.write(wav);
                write(out, "\r\n--" + boundary + "--\r\n");
            }
            int code = connection.getResponseCode();
            if (code == 429) throw new RecognitionError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            if (code != 200) throw new RecognitionError(SpeechRecognizer.ERROR_SERVER);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (response.size() + n > 65536) throw new IOException("Response too large");
                    response.write(buffer, 0, n);
                }
                return new JSONObject(response.toString("UTF-8")).getString("text").trim();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void field(OutputStream out, String boundary, String name, String value)
            throws IOException {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
            + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
    }
}
