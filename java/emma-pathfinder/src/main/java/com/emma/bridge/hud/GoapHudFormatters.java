package com.emma.bridge.hud;

import com.google.gson.JsonObject;

/**
 * Static helpers for GOAP HUD text formatting — mirrors the display logic
 * in tests/watch_goap.py (score coloring, name truncation, breakdown details).
 */
public final class GoapHudFormatters {

    // ARGB colors matching watch_goap.py ANSI palette
    public static final int COLOR_GREEN  = 0xFF55FF55; // winner / active
    public static final int COLOR_YELLOW = 0xFFFFFF55; // close (>70% of best)
    public static final int COLOR_RED    = 0xFFFF5555; // low
    public static final int COLOR_GRAY   = 0xFF888888; // non-viable (<=0)
    public static final int COLOR_CYAN   = 0xFF55FFFF; // personality / info
    public static final int COLOR_WHITE  = 0xFFFFFFFF; // headers
    public static final int COLOR_LABEL  = 0xFFAAAAAA; // column labels
    public static final int COLOR_BG     = 0xC0101010; // panel background

    /** Maximum display chars for action name column. */
    public static final int MAX_ACTION_NAME = 22;
    /** Maximum display chars for goal id column. */
    public static final int MAX_GOAL_NAME = 20;

    private GoapHudFormatters() {}

    /**
     * Score color based on ratio to best score — same thresholds as watch_goap.py.
     */
    public static int scoreColor(float score, float bestScore) {
        if (score <= 0) return COLOR_GRAY;
        if (bestScore > 0 && score >= bestScore) return COLOR_GREEN;
        float ratio = bestScore > 0 ? score / bestScore : 0;
        if (ratio > 0.7f) return COLOR_YELLOW;
        return COLOR_RED;
    }

    /**
     * Truncate string to maxLen, appending "…" if shortened.
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "\u2026";
    }

    /**
     * Right-align a float score into a fixed-width field.
     */
    public static String formatScore(float score, int width) {
        String s = String.format("%.1f", score);
        if (s.length() >= width) return s;
        return " ".repeat(width - s.length()) + s;
    }

    /**
     * Extract compact breakdown details from a scored-action's breakdown JSON.
     * Mirrors watch_goap.py details extraction.
     */
    public static String breakdownDetails(JsonObject breakdown) {
        if (breakdown == null) return "";
        StringBuilder sb = new StringBuilder();
        if (breakdown.has("collateral") && breakdown.get("collateral").getAsFloat() > 0) {
            sb.append("coll=").append(String.format("%.1f", breakdown.get("collateral").getAsFloat()));
        }
        if (breakdown.has("personality_mult") && breakdown.get("personality_mult").getAsFloat() != 1.0f) {
            if (!sb.isEmpty()) sb.append("  ");
            sb.append("pers=").append(String.format("%.1f", breakdown.get("personality_mult").getAsFloat()));
        }
        if (breakdown.has("success_rate") && breakdown.get("success_rate").getAsFloat() < 1.0f) {
            if (!sb.isEmpty()) sb.append("  ");
            sb.append("succ=").append(String.format("%.2f", breakdown.get("success_rate").getAsFloat()));
        }
        if (breakdown.has("primary_goal")) {
            if (!sb.isEmpty()) sb.append("  ");
            sb.append("goal=").append(truncate(breakdown.get("primary_goal").getAsString(), 14));
        }
        return sb.toString();
    }
}
