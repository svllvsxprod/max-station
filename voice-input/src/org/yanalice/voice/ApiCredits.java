package org.yanalice.voice;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Locale;

final class ApiCredits {
    private static final long NANOS_PER_DOLLAR = 1_000_000_000L;
    private static final String SPENT = "estimated_spend";
    private static final String ANCHOR = "balance_anchor";
    private static final String SPENT_AT_ANCHOR = "spend_at_balance_anchor";

    private static SharedPreferences storage(Context context) {
        return context.getSharedPreferences("api-credit-estimate", Context.MODE_PRIVATE);
    }

    static synchronized void addLive(Context context, long start, long previousMs, long totalMs) {
        add(context, liveCost(totalMs) - liveCost(previousMs));
    }

    private static long liveCost(long milliseconds) {
        return Math.round(milliseconds * (0.05 * NANOS_PER_DOLLAR / 60_000));
    }

    static synchronized void addResponse(Context context, JSONObject response) {
        JSONObject usage = response.optJSONObject("usage");
        if (usage == null) return;
        long input = usage.optLong("input_tokens");
        long output = usage.optLong("output_tokens");
        JSONObject details = usage.optJSONObject("input_tokens_details");
        long cached = details == null ? 0 : details.optLong("cached_tokens");
        long cost = (input - cached) * 100 + cached * 10 + output * 500;
        JSONArray items = response.optJSONArray("output");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && "web_search_call".equals(item.optString("type"))) cost += 10_000_000;
        }
        add(context, cost);
    }

    private static void add(Context context, long cost) {
        if (cost <= 0) return;
        SharedPreferences credits = storage(context);
        credits.edit().putLong(SPENT, credits.getLong(SPENT, 0) + cost).apply();
    }

    static synchronized boolean reconcile(Context context, String dollars) {
        if (dollars == null || !dollars.matches("[0-9]{1,6}(\\.[0-9]{1,2})?")) return false;
        long balance = new BigDecimal(dollars).movePointRight(9).longValueExact();
        SharedPreferences credits = storage(context);
        return credits.edit().putLong(ANCHOR, balance)
            .putLong(SPENT_AT_ANCHOR, credits.getLong(SPENT, 0)).commit();
    }

    static String summary(Context context) {
        SharedPreferences credits = storage(context);
        double spent = credits.getLong(SPENT, 0) / (double) NANOS_PER_DOLLAR;
        return String.format(Locale.US, "Расход с установки ≈ $%.2f", spent);
    }

    static synchronized String remaining(Context context) {
        SharedPreferences credits = storage(context);
        if (!credits.contains(ANCHOR)) return "баланс не задан";
        long since = credits.getLong(SPENT, 0) - credits.getLong(SPENT_AT_ANCHOR, 0);
        double value = Math.max(0, credits.getLong(ANCHOR, 0) - since) / (double) NANOS_PER_DOLLAR;
        return String.format(Locale.US, "≈ $%.2f", value);
    }
}
