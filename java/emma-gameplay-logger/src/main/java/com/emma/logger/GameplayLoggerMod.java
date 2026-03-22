package com.emma.logger;

import com.emma.logger.events.BlockEventLogger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side gameplay logger mod.
 * Tracks player actions and world state for GOAP training data and debugging.
 */
public class GameplayLoggerMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("EmmaLogger");

    /** Active loggers, keyed by player UUID. */
    private static final Map<UUID, PlayerLogger> activeLoggers = new ConcurrentHashMap<>();

    /** Screen open tick tracking for container duration. */
    private static final Map<UUID, Long> screenOpenTicks = new ConcurrentHashMap<>();

    /** Pre-eat state for food consumption logging. */
    private static final Map<UUID, float[]> preEatStates = new ConcurrentHashMap<>();
    private static final Map<UUID, String> preEatItems = new ConcurrentHashMap<>();

    private static Path logRoot;
    private static MinecraftServer server;

    private final BlockEventLogger blockEventLogger = new BlockEventLogger();

    @Override
    public void onInitialize() {
        LogConfig.load();
        LOGGER.info("[EmmaLogger] Gameplay Logger initializing (server-side)");

        // Register block events
        blockEventLogger.register();

        // Player join/leave for session management
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            server = srv;
            logRoot = Paths.get(LogConfig.logDirectory);

            if (LogConfig.autoLogOnJoin) {
                startLogging(handler.getPlayer());
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            stopLogging(handler.getPlayer());
        });

        // Server tick — drive state snapshots + pending block placements
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            server = srv;
            blockEventLogger.tick();

            for (var entry : activeLoggers.entrySet()) {
                ServerPlayer player = srv.getPlayerList().getPlayer(entry.getKey());
                if (player != null && !player.hasDisconnected()) {
                    entry.getValue().tick(player);
                }
            }
        });

        // Register admin commands
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> {
                    dispatcher.register(Commands.literal("logger")
                            .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                            .then(Commands.literal("start")
                                    .then(Commands.argument("player",
                                                    net.minecraft.commands.arguments.EntityArgument.player())
                                            .executes(ctx -> {
                                                ServerPlayer target =
                                                        net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
                                                startLogging(target);
                                                ctx.getSource().sendSuccess(
                                                        () -> Component.literal("[Logger] Started logging " + target.getName().getString()),
                                                        false);
                                                return 1;
                                            })))
                            .then(Commands.literal("stop")
                                    .then(Commands.argument("player",
                                                    net.minecraft.commands.arguments.EntityArgument.player())
                                            .executes(ctx -> {
                                                ServerPlayer target =
                                                        net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
                                                stopLogging(target);
                                                ctx.getSource().sendSuccess(
                                                        () -> Component.literal("[Logger] Stopped logging " + target.getName().getString()),
                                                        false);
                                                return 1;
                                            })))
                            .then(Commands.literal("startall")
                                    .executes(ctx -> {
                                        int count = 0;
                                        for (ServerPlayer p : ctx.getSource().getServer()
                                                .getPlayerList().getPlayers()) {
                                            if (!activeLoggers.containsKey(p.getUUID())) {
                                                startLogging(p);
                                                count++;
                                            }
                                        }
                                        int finalCount = count;
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[Logger] Started logging " + finalCount + " players"),
                                                false);
                                        return count;
                                    }))
                            .then(Commands.literal("status")
                                    .executes(ctx -> {
                                        if (activeLoggers.isEmpty()) {
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("[Logger] No active loggers"),
                                                    false);
                                            return 0;
                                        }
                                        for (PlayerLogger logger : activeLoggers.values()) {
                                            long minutes = (System.currentTimeMillis() - logger.getStartTimeMs()) / 60000;
                                            String msg = String.format("  %s: session %s, %d events, %dm",
                                                    logger.getPlayerName(), logger.getSessionId(),
                                                    logger.getTotalEvents(), minutes);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal(msg), false);
                                        }
                                        return activeLoggers.size();
                                    }))
                    );
                });

        LOGGER.info("[EmmaLogger] Gameplay Logger initialized");
    }

    // ---- Logger management ----

    public static void startLogging(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (activeLoggers.containsKey(uuid)) {
            LOGGER.info("[EmmaLogger] Already logging {}", player.getName().getString());
            return;
        }

        Path root = logRoot != null ? logRoot : Paths.get(LogConfig.logDirectory);
        PlayerLogger logger = new PlayerLogger(player, root);
        activeLoggers.put(uuid, logger);
    }

    public static void stopLogging(ServerPlayer player) {
        PlayerLogger logger = activeLoggers.remove(player.getUUID());
        if (logger != null) {
            long tick = player.level().getServer().getTickCount();
            logger.close(tick);
        }
        screenOpenTicks.remove(player.getUUID());
        preEatStates.remove(player.getUUID());
        preEatItems.remove(player.getUUID());
    }

    /**
     * Get the active logger for a player, or null if not being logged.
     */
    public static PlayerLogger getLogger(ServerPlayer player) {
        return activeLoggers.get(player.getUUID());
    }

    // ---- Screen tracking helpers (for container duration) ----

    public static void setScreenOpenTick(ServerPlayer player, long tick) {
        screenOpenTicks.put(player.getUUID(), tick);
    }

    public static long getScreenOpenTick(ServerPlayer player) {
        return screenOpenTicks.getOrDefault(player.getUUID(), 0L);
    }

    public static void clearScreenOpenTick(ServerPlayer player) {
        screenOpenTicks.remove(player.getUUID());
    }

    // ---- Pre-eat state helpers (for food consumption logging) ----

    public static void storePreEatState(ServerPlayer player, float health, int hunger, String item) {
        preEatStates.put(player.getUUID(), new float[]{health, hunger});
        preEatItems.put(player.getUUID(), item);
    }

    public static float[] getPreEatState(ServerPlayer player) {
        return preEatStates.get(player.getUUID());
    }

    public static String getPreEatItem(ServerPlayer player) {
        return preEatItems.get(player.getUUID());
    }

    public static void clearPreEatState(ServerPlayer player) {
        preEatStates.remove(player.getUUID());
        preEatItems.remove(player.getUUID());
    }
}
