package com.emma.logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for the gameplay logger.
 * Reads from config/emma_logger.json on the server.
 */
public class LogConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("EmmaLogger");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Ticks between state stream snapshots (default 20 = 1/second). */
    public static int snapshotIntervalTicks = 20;

    /** Entity scan radius in blocks (default 32). */
    public static int entityScanRadius = 32;

    /** Max entities to include per snapshot (default 20). */
    public static int maxEntitiesPerSnapshot = 20;

    /** Whether to log all players on join automatically. */
    public static boolean autoLogOnJoin = true;

    /** Directory for log output (relative to server root). */
    public static String logDirectory = "logs/gameplay";

    /** Inventory change debounce in ticks (default 10 = 500ms). */
    public static int inventoryDebounceTicks = 10;

    /** Flush JSONL writers every N events (default 50). */
    public static int flushInterval = 50;

    public static void load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFile = configDir.resolve("emma_logger.json");

        if (!Files.exists(configFile)) {
            save(configFile);
            LOGGER.info("[EmmaLogger] Created default config at {}", configFile);
            return;
        }

        try {
            String json = Files.readString(configFile);
            JsonObject obj = GSON.fromJson(json, JsonObject.class);

            if (obj.has("snapshot_interval_ticks"))
                snapshotIntervalTicks = obj.get("snapshot_interval_ticks").getAsInt();
            if (obj.has("entity_scan_radius"))
                entityScanRadius = obj.get("entity_scan_radius").getAsInt();
            if (obj.has("max_entities_per_snapshot"))
                maxEntitiesPerSnapshot = obj.get("max_entities_per_snapshot").getAsInt();
            if (obj.has("auto_log_on_join"))
                autoLogOnJoin = obj.get("auto_log_on_join").getAsBoolean();
            if (obj.has("log_directory"))
                logDirectory = obj.get("log_directory").getAsString();
            if (obj.has("inventory_debounce_ticks"))
                inventoryDebounceTicks = obj.get("inventory_debounce_ticks").getAsInt();
            if (obj.has("flush_interval"))
                flushInterval = obj.get("flush_interval").getAsInt();

            LOGGER.info("[EmmaLogger] Config loaded: snapshot={}t, entities={}r, autoLog={}",
                    snapshotIntervalTicks, entityScanRadius, autoLogOnJoin);
        } catch (Exception e) {
            LOGGER.warn("[EmmaLogger] Failed to load config, using defaults: {}", e.getMessage());
        }
    }

    private static void save(Path configFile) {
        JsonObject obj = new JsonObject();
        obj.addProperty("snapshot_interval_ticks", snapshotIntervalTicks);
        obj.addProperty("entity_scan_radius", entityScanRadius);
        obj.addProperty("max_entities_per_snapshot", maxEntitiesPerSnapshot);
        obj.addProperty("auto_log_on_join", autoLogOnJoin);
        obj.addProperty("log_directory", logDirectory);
        obj.addProperty("inventory_debounce_ticks", inventoryDebounceTicks);
        obj.addProperty("flush_interval", flushInterval);

        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, GSON.toJson(obj));
        } catch (IOException e) {
            LOGGER.error("[EmmaLogger] Failed to save config: {}", e.getMessage());
        }
    }
}
