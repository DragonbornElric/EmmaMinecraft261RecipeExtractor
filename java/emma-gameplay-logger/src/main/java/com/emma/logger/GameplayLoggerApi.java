package com.emma.logger;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

/**
 * Public API for external mods (e.g., the bridge mod's GOAP planner)
 * to emit decision events into the gameplay logger.
 *
 * Usage from bridge mod:
 * <pre>
 * if (FabricLoader.getInstance().isModLoaded("emma-gameplay-logger")) {
 *     GameplayLoggerApi.logGoapEvent(player, "goap_plan", planData);
 * }
 * </pre>
 *
 * If the logger mod is not installed, calls are no-ops.
 * If the player is not being logged, calls are no-ops.
 */
public final class GameplayLoggerApi {

    private GameplayLoggerApi() {}

    /**
     * Log a GOAP decision event for a player.
     *
     * @param player    the server player entity
     * @param eventType one of: goap_plan, goap_replan, goap_action_start,
     *                  goap_action_result, goap_goal_change
     * @param data      event-specific JSON data (merged into the event object)
     */
    public static void logGoapEvent(ServerPlayer player, String eventType, JsonObject data) {
        PlayerLogger logger = GameplayLoggerMod.getLogger(player);
        if (logger == null) return;

        long tick = player.level().getServer().getTickCount();
        logger.logGoapEvent(tick, eventType, data);
    }

    /**
     * Check if a player is currently being logged.
     */
    public static boolean isLogging(ServerPlayer player) {
        return GameplayLoggerMod.getLogger(player) != null;
    }
}
