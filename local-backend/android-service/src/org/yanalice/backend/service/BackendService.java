package org.yanalice.backend.service;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import java.io.File;
import org.yanalice.backend.LocalBackend;

/** Explicit, bounded diagnostic host. No boot receiver, root call or outbound client. */
public final class BackendService extends Service {
    public static final String START = "org.yanalice.backend.service.START";
    public static final String STOP = "org.yanalice.backend.service.STOP";
    private static final String TAG = "YanAliceBackend";
    private static final String CHANNEL = "local_backend";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Локальная служба", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Временная проверка сервера внутри колонки");
        manager.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // A foreground-service start must be acknowledged promptly, even if invalid.
        startForeground(1, notification());
        if (intent == null || STOP.equals(intent.getAction())) {
            if (worker != null) worker.interrupt();
            else stopSelf();
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction())) {
            Log.w(TAG, "Unknown action rejected");
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null) {
            Log.i(TAG, "Duplicate start ignored; original duration retained");
            return START_NOT_STICKY;
        }
        final int seconds;
        final File config;
        try {
            seconds = DurationPolicy.requireSeconds(intent.getIntExtra("seconds", 300));
            config = new File(getFilesDir().getCanonicalFile(), "config");
            validateConfig(config);
        } catch (Exception invalid) {
            // Do not print file contents, credential paths, or arbitrary exception messages.
            Log.e(TAG, "Start rejected: duration or private configuration invalid");
            stopSelf();
            return START_NOT_STICKY;
        }
        worker = new Thread(() -> {
            try {
                Log.i(TAG, "Bounded backend starting; seconds=" + seconds);
                LocalBackend.main(new String[]{config.getAbsolutePath(), Integer.toString(seconds)});
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                Log.i(TAG, "Backend stopped explicitly");
            } catch (Exception failure) {
                Log.e(TAG, "Backend failed: " + failure.getClass().getSimpleName());
            } finally {
                final Thread completed = Thread.currentThread();
                main.post(() -> {
                    if (worker == completed) {
                        worker = null;
                        stopForeground(true);
                        stopSelf();
                    }
                });
            }
        }, "station-local-backend");
        worker.start();
        return START_NOT_STICKY;
    }

    private static void validateConfig(File directory) throws Exception {
        if (!directory.isDirectory() || !directory.equals(directory.getCanonicalFile()))
            throw new IllegalArgumentException("Private configuration required");
        String[] names = {"sync.json", "password", "server.p12"};
        long[] limits = {1024 * 1024, 4096, 1024 * 1024};
        for (int i = 0; i < names.length; i++) {
            File file = new File(directory, names[i]);
            if (!file.equals(file.getCanonicalFile()) || !file.isFile() || !file.canRead()
                    || file.length() < 1 || file.length() > limits[i])
                throw new IllegalArgumentException("Private configuration invalid");
        }
    }

    private Notification notification() {
        Intent stop = new Intent(this, BackendService.class).setAction(STOP);
        PendingIntent operation = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Локальный сервер колонки")
                .setContentText("Временная проверка. Остановится автоматически.")
                .setOngoing(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Остановить", operation).build())
                .build();
    }

    @Override public void onDestroy() {
        if (worker != null) worker.interrupt();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
