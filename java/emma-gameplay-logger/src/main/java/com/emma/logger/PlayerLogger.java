package com.emma.logger;

import com.emma.logger.events.*;
import com.emma.logger.state.EnvironmentCapture;
import com.emma.logger.state.EquipmentStateCapture;
import com.emma.logger.state.PlayerStateCapture;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Per-player logger instance. Manages state snapshots, event logging,
 * and session lifecycle for a single player.
 */
public class PlayerLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("EmmaLogger");
    private static final DateTimeFormatter DIR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").withZone(ZoneId.systemDefault());

    private final String playerName;
    private final UUID playerUuid;
    private final String sessionId;
    private final LogWriter writer;
    private final long startTick;
    private final long startTimeMs;

    // State capture
    private final PlayerStateCapture playerState = new PlayerStateCapture();
    private final EquipmentStateCapture equipmentState = new EquipmentStateCapture();
    private final EnvironmentCapture environmentCapture = new EnvironmentCapture();

    // Event loggers
    private final EquipmentChangeLogger equipmentChangeLogger = new EquipmentChangeLogger();

    // Tick tracking
    private int ticksSinceSnapshot = 0;
    private int totalBlocksPlaced = 0;
    private int totalBlocksBroken = 0;
    private int totalDeaths = 0;

    // Active annotation
    private String activeAnnotation = null;
    private long annotationStartTick = 0;

    public PlayerLogger(ServerPlayer player, Path logRoot) {
        this.playerName = player.getName().getString();
        this.playerUuid = player.getUUID();
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.startTick = player.level().getServer().getTickCount();
        this.startTimeMs = System.currentTimeMillis();

        String dirName = playerName + "_" + DIR_FORMAT.format(Instant.now()) + "_" + sessionId;
        Path sessionDir = logRoot.resolve(dirName);
        this.writer = new LogWriter(sessionDir, sessionId);

        try {
            writer.open();
            writeSessionStart(player);
            LOGGER.info("[EmmaLogger] Started logging player {} (session {})", playerName, sessionId);
        } catch (IOException e) {
            LOGGER.error("[EmmaLogger] Failed to start session for {}: {}", playerName, e.getMessage());
        }
    }

    /**
     * Called every server tick. Handles state snapshots and equipment change detection.
     */
    public void tick(ServerPlayer player) {
        ServerLevel world = (ServerLevel) player.level();
        long currentTick = world.getServer().getTickCount();

        // Equipment change detection (every tick — lightweight)
        equipmentChangeLogger.tick(player, currentTick, this);

        // State snapshot at configured interval
        ticksSinceSnapshot++;
        if (ticksSinceSnapshot >= LogConfig.snapshotIntervalTicks) {
            ticksSinceSnapshot = 0;
            captureStateSnapshot(player, world, currentTick);
        }
    }

    /**
     * Capture and write a full state snapshot.
     */
    private void captureStateSnapshot(ServerPlayer player, ServerLevel world, long tick) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("tick", tick);
        snapshot.add("player", playerState.capture(player));
        snapshot.add("equipment", equipmentState.capture(player));
        snapshot.add("environment", environmentCapture.capture(player, world));
        writer.writeState(snapshot);
    }

    /**
     * Force a state snapshot (e.g., alongside an annotation).
     */
    public void forceSnapshot(ServerPlayer player) {
        ServerLevel world = (ServerLevel) player.level();
        long tick = world.getServer().getTickCount();
        captureStateSnapshot(player, world, tick);
    }

    // ---- Event logging methods (called by event loggers / mixins) ----

    public void logEvent(JsonObject event) {
        writer.writeEvent(event);
    }

    public void onBlockPlaced(long tick, String block, int x, int y, int z, String face) {
        totalBlocksPlaced++;
        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", "block_placed");
        event.addProperty("block", block);
        event.addProperty("x", x);
        event.addProperty("y", y);
        event.addProperty("z", z);
        if (face != null) event.addProperty("face", face);
        writer.writeEvent(event);
    }

    public void onBlockBroken(long tick, String block, int x, int y, int z, String tool) {
        totalBlocksBroken++;
        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", "block_broken");
        event.addProperty("block", block);
        event.addProperty("x", x);
        event.addProperty("y", y);
        event.addProperty("z", z);
        if (tool != null) event.addProperty("tool", tool);
        writer.writeEvent(event);
    }

    public void onDeath(long tick, String message) {
        totalDeaths++;
        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", "death");
        event.addProperty("message", message);
        writer.writeEvent(event);
    }

    public void onDamageTaken(long tick, float amount, float healthAfter, String sourceType,
                              String attacker, float attackerDist) {
        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", "damage_taken");
        event.addProperty("amount", amount);
        event.addProperty("health_after", healthAfter);
        event.addProperty("source_type", sourceType);
        if (attacker != null) event.addProperty("attacker", attacker);
        if (attackerDist >= 0) event.addProperty("attacker_dist", attackerDist);
        writer.writeEvent(event);
    }

    public void onFoodEaten(long tick, String item, float healthBefore, int hungerBefore,
                            float healthAfter, int hungerAfter) {
        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", "eat_food");
        event.addProperty("item", item);
        event.addProperty("health_before", healthBefore);
        event.addProperty("hunger_before", hungerBefore);
        event.addProperty("health_after", healthAfter);
        event.addProperty("hunger_after", hungerAfter);
        writer.writeEvent(event);
    }

    public void onContainerOpened(long tick, String containerType, String title,
                                  int x, int y, int z) {
        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", "screen_open");
        event.addProperty("container_type", containerType);
        if (title != null) event.addProperty("title", title);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", x);
        pos.addProperty("y", y);
        pos.addProperty("z", z);
        event.add("pos", pos);
        writer.writeEvent(event);
    }

    public void onContainerClosed(long tick, String containerType, long durationTicks) {
        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", "screen_close");
        event.addProperty("container_type", containerType);
        event.addProperty("duration_ticks", durationTicks);
        writer.writeEvent(event);
    }

    public void onAnnotation(long tick, String text, ServerPlayer player) {
        // End previous annotation if active
        if (activeAnnotation != null) {
            endAnnotation(tick);
        }

        if (text.equalsIgnoreCase("end")) {
            return; // just end, don't start a new one
        }

        activeAnnotation = text;
        annotationStartTick = tick;

        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", "annotation");
        event.addProperty("text", text);
        writer.writeEvent(event);

        // Force a context snapshot alongside the annotation
        forceSnapshot(player);
    }

    private void endAnnotation(long tick) {
        if (activeAnnotation == null) return;

        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", "annotation_end");
        event.addProperty("text", activeAnnotation);
        event.addProperty("duration_ticks", tick - annotationStartTick);
        writer.writeEvent(event);

        activeAnnotation = null;
    }

    /**
     * Log a GOAP decision event (called via GameplayLoggerApi).
     */
    public void logGoapEvent(long tick, String eventType, JsonObject data) {
        JsonObject event = new JsonObject();
        event.addProperty("tick", tick);
        event.addProperty("type", eventType);
        // Merge data fields into the event
        for (var entry : data.entrySet()) {
            event.add(entry.getKey(), entry.getValue());
        }
        writer.writeEvent(event);
    }

    // ---- Session lifecycle ----

    private void writeSessionStart(ServerPlayer player) {
        JsonObject event = new JsonObject();
        event.addProperty("tick", startTick);
        event.addProperty("type", "session_start");
        event.addProperty("session_id", sessionId);
        event.addProperty("player_name", playerName);
        event.addProperty("player_uuid", playerUuid.toString());
        event.addProperty("mc_version", "1.21.8");
        event.addProperty("mod_version", "0.1.0");
        event.addProperty("server_address", player.level().getServer().getLocalIp());
        writer.writeEvent(event);
    }

    /**
     * End the session, write metadata, close files.
     */
    public void close(long endTick) {
        // End any active annotation
        if (activeAnnotation != null) {
            endAnnotation(endTick);
        }

        // Write session_end event
        JsonObject endEvent = new JsonObject();
        endEvent.addProperty("tick", endTick);
        endEvent.addProperty("type", "session_end");
        endEvent.addProperty("duration_ticks", endTick - startTick);
        endEvent.addProperty("events_logged", writer.getTotalEvents());
        writer.writeEvent(endEvent);

        // Write metadata
        JsonObject meta = new JsonObject();
        meta.addProperty("session_id", sessionId);
        meta.addProperty("player_name", playerName);
        meta.addProperty("player_uuid", playerUuid.toString());
        meta.addProperty("start_tick", startTick);
        meta.addProperty("end_tick", endTick);
        meta.addProperty("duration_ticks", endTick - startTick);
        meta.addProperty("duration_minutes", (System.currentTimeMillis() - startTimeMs) / 60000.0);
        meta.addProperty("total_events", writer.getTotalEvents());
        meta.addProperty("blocks_placed", totalBlocksPlaced);
        meta.addProperty("blocks_broken", totalBlocksBroken);
        meta.addProperty("deaths", totalDeaths);
        writer.writeMetadata(meta);

        writer.close();
    }

    // ---- Getters ----

    public String getPlayerName() { return playerName; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getSessionId() { return sessionId; }
    public int getTotalEvents() { return writer.getTotalEvents(); }
    public long getStartTimeMs() { return startTimeMs; }
}
