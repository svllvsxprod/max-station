package org.yanalice.voice;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.net.ssl.HttpsURLConnection;

public final class SetupProvider extends ContentProvider {
    private static final String ALIAS = "max-station-voice";
    private KeyPair pending;
    private String pendingProvider;

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingPermission("android.permission.DUMP", "Voice setup");
        Bundle result = new Bundle();
        try {
            switch (method) {
                case "prepare":
                case "prepare-openai":
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                    generator.initialize(2048);
                    pending = generator.generateKeyPair();
                    pendingProvider = method.equals("prepare") ? "groq" : "openai";
                    result.putString("publicKey", encode(pending.getPublic().getEncoded()));
                    break;
                case "store":
                case "store-openai":
                    String provider = method.equals("store") ? "groq" : "openai";
                    if (!provider.equals(pendingProvider)) break;
                    KeyPair pair = pending;
                    pending = null;
                    pendingProvider = null;
                    if (pair == null) break;
                    Cipher envelope = Cipher.getInstance("RSA/ECB/OAEPPadding");
                    envelope.init(Cipher.DECRYPT_MODE, pair.getPrivate(), new OAEPParameterSpec(
                        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
                    byte[] secret = envelope.doFinal(Base64.decode(arg, Base64.DEFAULT));
                    try {
                        String value = new String(secret, StandardCharsets.US_ASCII);
                        String pattern = provider.equals("groq") ? "gsk_[A-Za-z0-9_-]{30,180}"
                            : "sk-[A-Za-z0-9_-]{32,350}";
                        if (value.matches(pattern)) {
                            result.putBoolean("saved", save(getContext(), provider, secret));
                        }
                    } finally {
                        Arrays.fill(secret, (byte) 0);
                    }
                    break;
                case "status":
                    result.putBoolean("configured", read(getContext()) != null);
                    result.putBoolean("openaiConfigured", readOpenAI(getContext()) != null);
                    break;
                case "check":
                    result.putInt("http", check(getContext()));
                    break;
                case "check-openai-responses":
                    result.putInt("http", checkOpenAIResponses(getContext()));
                    break;
                case "reconcile-credits":
                    result.putBoolean("saved", ApiCredits.reconcile(getContext(), arg));
                    result.putString("estimate", ApiCredits.remaining(getContext()));
                    break;
                case "memory-status":
                    JSONObject profile = new AssistantMemory(getContext()).snapshot();
                    result.putBoolean("nameSaved", !profile.optString("name").isEmpty());
                    result.putBoolean("weatherCitySaved", !profile.optString("weather_city").isEmpty());
                    result.putString("speakingGender", profile.optString("speaking_gender"));
                    break;
                case "update-memory":
                    JSONObject saved = new AssistantMemory(getContext()).update(new JSONObject(arg));
                    result.putString("status", saved.optString("status"));
                    break;
                case "save-spotify-playlist":
                    JSONObject playlist = new JSONObject(arg);
                    result.putBoolean("saved", SpotifyPlaylists.save(getContext(),
                        playlist.getString("name"), playlist.getString("link")));
                    break;
                case "set-spotify-account":
                    result.putBoolean("saved", SpotifyPlaylists.saveAccount(getContext(), arg));
                    break;
                case "abort":
                    pending = null;
                    pendingProvider = null;
                    break;
                default:
                    throw new UnsupportedOperationException();
            }
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException | JSONException e) {
            result.putString("failure", e.getClass().getSimpleName());
            if (e instanceof javax.net.ssl.SSLException) result.putString("tls", e.getMessage());
            Throwable cause = e.getCause();
            if (cause != null) result.putString("cause", cause.getClass().getSimpleName());
            if (cause instanceof java.security.cert.CertPathValidatorException) {
                result.putString("reason",
                    ((java.security.cert.CertPathValidatorException) cause).getReason().toString());
            }
        }
        return result;
    }

    private static int check(Context context) throws GeneralSecurityException, java.io.IOException {
        String key = read(context);
        if (key == null) return 0;
        URL endpoint = new URL("https://api.groq.com/openai/v1/models/whisper-large-v3-turbo");
        HttpsURLConnection connection = (HttpsURLConnection) endpoint.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static int checkOpenAIResponses(Context context)
            throws GeneralSecurityException, java.io.IOException {
        String key = readOpenAI(context);
        if (key == null) return 0;
        URL endpoint = new URL("https://api.openai.com/v1/responses");
        HttpsURLConnection connection = (HttpsURLConnection) endpoint.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] body = "{\"model\":\"gpt-6-luna\",\"input\":\"Reply OK\",\"max_output_tokens\":16,\"store\":false}"
                .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static boolean save(Context context, String provider, byte[] secret)
            throws GeneralSecurityException, java.io.IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        return preferences(context, provider).edit().putString("nonce", encode(cipher.getIV()))
            .putString("sealed", encode(cipher.doFinal(secret))).commit();
    }

    static String read(Context context) throws GeneralSecurityException, java.io.IOException {
        return read(context, "groq");
    }

    static String readOpenAI(Context context) throws GeneralSecurityException, java.io.IOException {
        return read(context, "openai");
    }

    private static String read(Context context, String provider)
            throws GeneralSecurityException, java.io.IOException {
        SharedPreferences preferences = preferences(context, provider);
        String sealed = preferences.getString("sealed", null);
        if (sealed == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(
            128, Base64.decode(preferences.getString("nonce", ""), Base64.DEFAULT)));
        byte[] plain = cipher.doFinal(Base64.decode(sealed, Base64.DEFAULT));
        try {
            return new String(plain, StandardCharsets.US_ASCII);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static SecretKey key(boolean create)
            throws GeneralSecurityException, java.io.IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(ALIAS, null);
        if (key != null || !create) return key;
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }

    private static SharedPreferences preferences(Context context, String provider) {
        return context.getSharedPreferences(provider.equals("groq") ? "setup" : "setup-openai",
            Context.MODE_PRIVATE);
    }

    private static String encode(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP); }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
