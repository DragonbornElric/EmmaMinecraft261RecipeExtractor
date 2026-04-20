package com.emma.bridge.hud;

import com.emma.bridge.goap.AgentDebugState;
import com.emma.bridge.goap.AgentDebugState.ScoredAction;
import com.emma.bridge.goap.AgentDebugState.ScoredGoal;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

import static com.emma.bridge.hud.GoapHudFormatters.*;

/**
 * Client-side GOAP HUD overlay — toggled with F7.
 * Reads AgentDebugState (populated each tick by GoapTicker on the same thread)
 * and renders a truncated version of the watch_goap.py display.
 *
 * Registered as a Fabric HudElement via {@link net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry}.
 */
public class GoapHudRenderer implements HudElement {

    public static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("emma-bridge", "goap_hud");

    /** Maximum action rows to display (keeps HUD compact). */
    private static final int MAX_ACTION_ROWS = 12;
    /** Maximum goal rows to display. */
    private static final int MAX_GOAL_ROWS = 6;
    /** Line height in pixels (font is 9px, 10px gives clean spacing). */
    private static final int LINE_H = 10;
    /** Left margin inside the panel. */
    private static final int PAD_X = 6;
    /** Top margin inside the panel. */
    private static final int PAD_Y = 4;
    /** Panel inset from screen left edge. */
    private static final int SCREEN_X = 4;
    /** Panel inset from screen top edge. */
    private static final int SCREEN_Y = 4;

    private volatile boolean enabled = false;

    /** Toggle HUD visibility (called from F7 key handler). */
    public void toggle() {
        enabled = !enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!enabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // Don't draw over screens (inventories, chat, etc.)
        if (mc.screen != null) return;

        Font font = mc.font;
        AgentDebugState debug = AgentDebugState.getInstance();

        // ── Compute content height for background panel ─────────
        int lines = 0;
        lines += 2; // header + separator
        List<ScoredAction> auction = debug.lastAuction;
        int actionRows = Math.min(auction.size(), MAX_ACTION_ROWS);
        lines += 1 + actionRows; // column header + rows
        if (auction.size() > MAX_ACTION_ROWS) lines += 1; // "+N more"

        List<ScoredGoal> goals = debug.goalScores;
        int goalRows = Math.min(goals.size(), MAX_GOAL_ROWS);
        if (!goals.isEmpty()) {
            lines += 2 + goalRows; // blank + section header + rows
            if (goals.size() > MAX_GOAL_ROWS) lines += 1;
        }

        int panelW = 320;
        int panelH = PAD_Y * 2 + lines * LINE_H;

        // ── Background ──────────────────────────────────────────
        g.fill(SCREEN_X, SCREEN_Y, SCREEN_X + panelW, SCREEN_Y + panelH, COLOR_BG);

        int x = SCREEN_X + PAD_X;
        int y = SCREEN_Y + PAD_Y;

        // ── Header ──────────────────────────────────────────────
        String header = "GOAP  |  Tick: " + debug.tick
                + "  |  Active: " + truncate(debug.activeAction, 18)
                + "  |  Hyst: +" + String.format("%.1f", debug.hysteresisBonus);
        g.text(font, Component.literal(header), x, y, COLOR_WHITE);
        y += LINE_H;

        // Separator
        g.text(font, Component.literal("\u2500".repeat(50)), x, y, COLOR_GRAY);
        y += LINE_H;

        // ── Action Auction ──────────────────────────────────────
        float bestScore = !auction.isEmpty() ? auction.getFirst().score : 0;
        String colHeader = String.format("%-" + MAX_ACTION_NAME + "s %6s %6s  %s", "Action", "Score", "Raw", "Details");
        g.text(font, Component.literal(colHeader), x, y, COLOR_LABEL);
        y += LINE_H;

        for (int i = 0; i < actionRows; i++) {
            ScoredAction sa = auction.get(i);
            int color = scoreColor(sa.score, bestScore);
            String marker = sa.isActive ? "\u25B6 " : "  "; // ▶ for active
            String name = truncate(sa.name, MAX_ACTION_NAME);
            String scoreTxt = formatScore(sa.score, 6);
            String rawTxt = formatScore(sa.rawScore, 6);
            String details = breakdownDetails(sa.breakdown);

            String line = String.format("%s%-" + MAX_ACTION_NAME + "s %s %s  %s",
                    marker, name, scoreTxt, rawTxt, truncate(details, 30));
            g.text(font, Component.literal(line), x, y, color);
            y += LINE_H;
        }
        if (auction.size() > MAX_ACTION_ROWS) {
            g.text(font, Component.literal("  +" + (auction.size() - MAX_ACTION_ROWS) + " more..."),
                    x, y, COLOR_GRAY);
            y += LINE_H;
        }

        // ── Goals ───────────────────────────────────────────────
        if (!goals.isEmpty()) {
            y += LINE_H; // blank line
            long userCount = goals.stream().filter(g2 -> !g2.isDerived).count();
            long derivedCount = goals.size() - userCount;
            g.text(font, Component.literal("GOALS (" + userCount + " user + " + derivedCount + " derived)"),
                    x, y, COLOR_WHITE);
            y += LINE_H;

            for (int i = 0; i < goalRows; i++) {
                ScoredGoal sg = goals.get(i);
                int color = sg.score > sg.priority * 0.5f ? COLOR_GREEN
                        : sg.score > 0 ? COLOR_YELLOW : COLOR_GRAY;
                String prefix = sg.isDerived ? "  \u2514 " : "  "; // └ for derived
                String gName = truncate(sg.id, MAX_GOAL_NAME);
                String gType = truncate(sg.type, 14);
                String line = String.format("%s%-" + MAX_GOAL_NAME + "s %-14s %5.1f %6.1f",
                        prefix, gName, gType, sg.priority, sg.score);
                g.text(font, Component.literal(line), x, y, color);
                y += LINE_H;
            }
            if (goals.size() > MAX_GOAL_ROWS) {
                g.text(font, Component.literal("  +" + (goals.size() - MAX_GOAL_ROWS) + " more..."),
                        x, y, COLOR_GRAY);
            }
        }
    }
}
