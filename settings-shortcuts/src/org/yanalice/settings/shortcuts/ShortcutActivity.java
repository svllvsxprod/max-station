package org.yanalice.settings.shortcuts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;

/** Launcher tiles that open existing settings through ordinary Android intents. */
public final class ShortcutActivity extends Activity {
    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final String VPN_APP = "com.celzero.bravedns";
    private static final String SMARTTUBE_APP = "app.smarttube.fdroid";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ComponentName component = getIntent().getComponent();
        String name = component == null ? "" : component.getClassName();
        Intent destination;
        String title;
        String missing;
        if (name.equals(getPackageName() + ".AndroidSettings")) {
            destination = new Intent(Settings.ACTION_SETTINGS).setPackage(TV_SETTINGS);
            title = "Настройки Android";
            missing = "Настройки Android сейчас недоступны.";
        } else if (name.equals(getPackageName() + ".NetworkSettings")) {
            destination = new Intent(Settings.ACTION_WIFI_SETTINGS).setPackage(TV_SETTINGS);
            title = "Сеть и Wi-Fi";
            missing = "Настройки сети сейчас недоступны.";
        } else if (name.equals(getPackageName() + ".VpnSettings")) {
            PackageManager manager = getPackageManager();
            destination = manager.getLeanbackLaunchIntentForPackage(VPN_APP);
            if (destination == null) destination = manager.getLaunchIntentForPackage(VPN_APP);
            title = "VPN и интернет";
            missing = "Приложение для защиты интернета не установлено или отключено.";
        } else if (name.equals(getPackageName() + ".SmartTube")) {
            PackageManager manager = getPackageManager();
            destination = manager.getLeanbackLaunchIntentForPackage(SMARTTUBE_APP);
            if (destination == null) destination = manager.getLaunchIntentForPackage(SMARTTUBE_APP);
            title = "YouTube";
            missing = "SmartTube не установлен или отключён.";
        } else {
            finish();
            return;
        }
        if (destination != null) {
            destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                startActivity(destination);
                finish();
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // An optional target may be absent or disabled; keep a remote-friendly exit.
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(missing)
            .setPositiveButton("Понятно", (dialog, which) -> finish())
            .setOnCancelListener(dialog -> finish())
            .setOnDismissListener(dialog -> finish())
            .show();
    }
}
