package org.yanalice.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpotifyPlaylists {
    private static final Pattern LINK = Pattern.compile(
        "(?:https://open\\.spotify\\.com/(?:intl-[a-z]{2}/)?playlist/|spotify:playlist:)([A-Za-z0-9]{22})(?:\\?[^\\s]*)?");

    static boolean save(Context context, String name, String link) {
        String alias = key(name);
        Matcher match = LINK.matcher(link.trim());
        if (alias.isEmpty() || alias.length() > 100 || !match.matches()) return false;
        return storage(context).edit().putString(alias, match.group(1)).commit();
    }

    static String find(Context context, String name) {
        return storage(context).getString(key(name), "");
    }

    static boolean saveAccount(Context context, String account) {
        if (account == null || !account.matches("[A-Za-z0-9._-]{1,128}")) return false;
        return profile(context).edit().putString("account", account).commit();
    }

    static Uri likedSongs(Context context) {
        String account = profile(context).getString("account", "");
        if (account.isEmpty()) return null;
        return Uri.parse("spotify:user:" + account + ":collection");
    }

    private static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).trim()
            .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static SharedPreferences storage(Context context) {
        return context.getSharedPreferences("spotify-playlists", Context.MODE_PRIVATE);
    }

    private static SharedPreferences profile(Context context) {
        return context.getSharedPreferences("spotify-profile", Context.MODE_PRIVATE);
    }
}
