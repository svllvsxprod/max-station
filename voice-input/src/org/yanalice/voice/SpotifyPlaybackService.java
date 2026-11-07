package org.yanalice.voice;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public final class SpotifyPlaybackService extends AccessibilityService {
    private static final String TAG = "MaxSpotifyPlayback";
    private static final String SPOTIFY = "com.spotify.tv.android";
    private static volatile SpotifyPlaybackService connected;

    private final Handler main = new Handler(Looper.getMainLooper());
    private long until;
    private int furthestStage;
    private final Runnable attempt = this::tryStart;

    static boolean requestLikedPlayback() {
        SpotifyPlaybackService service = connected;
        if (service == null) return false;
        service.main.post(() -> {
            service.until = SystemClock.uptimeMillis() + 15000;
            service.furthestStage = 0;
            service.main.removeCallbacks(service.attempt);
            service.main.postDelayed(service.attempt, 350);
        });
        return true;
    }

    @Override protected void onServiceConnected() {
        connected = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence source = event.getPackageName();
        if (until != 0 && source != null && SPOTIFY.contentEquals(source)) {
            main.removeCallbacks(attempt);
            main.post(attempt);
        }
    }

    @Override public void onInterrupt() {
        until = 0;
        main.removeCallbacks(attempt);
    }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        onInterrupt();
        super.onDestroy();
    }

    private void tryStart() {
        if (until == 0) return;
        if (SystemClock.uptimeMillis() >= until) {
            Log.w(TAG, "Personal collection did not start, stage=" + furthestStage);
            until = 0;
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try {
                if (root.getPackageName() != null && SPOTIFY.contentEquals(root.getPackageName())
                        && clickFirstLikedTrack(root)) {
                    until = 0;
                    Log.i(TAG, "Selected first track in personal collection");
                    return;
                }
            } finally {
                root.recycle();
            }
        }
        main.postDelayed(attempt, 300);
    }

    private boolean clickFirstLikedTrack(AccessibilityNodeInfo root) {
        furthestStage = Math.max(furthestStage, 1);
        return visitForTitle(root, 0);
    }

    private boolean visitForTitle(AccessibilityNodeInfo node, int depth) {
        if (depth >= 18) return false;
        CharSequence text = node.getText();
        if (text != null && ("Любимые треки".contentEquals(text) || "Liked Songs".contentEquals(text))) {
            furthestStage = Math.max(furthestStage, 2);
            AccessibilityNodeInfo page = node.getParent();
            if (page != null) {
                try {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (clickTrackList(page, bounds.right, 0)) return true;
                } finally {
                    page.recycle();
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (visitForTitle(child, depth + 1)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private boolean clickTrackList(AccessibilityNodeInfo page, int titleRight, int depth) {
        if (depth >= 5) return false;
        Rect bounds = new Rect();
        for (int i = 0; i < page.getChildCount(); i++) {
            AccessibilityNodeInfo child = page.getChild(i);
            if (child == null) continue;
            try {
                child.getBoundsInScreen(bounds);
                if (bounds.left >= titleRight && child.isClickable() && hasTrackMarker(child)) {
                    furthestStage = Math.max(furthestStage, 3);
                    return child.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                if (clickTrackList(child, titleRight, depth + 1)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private static boolean hasTrackMarker(AccessibilityNodeInfo track) {
        for (int i = 0; i < track.getChildCount(); i++) {
            AccessibilityNodeInfo item = track.getChild(i);
            if (item == null) continue;
            try {
                CharSequence label = item.getContentDescription();
                if (label != null && (label.toString().startsWith("Кто добавил:")
                        || label.toString().startsWith("Added by:"))) return true;
            } finally {
                item.recycle();
            }
        }
        return false;
    }
}
