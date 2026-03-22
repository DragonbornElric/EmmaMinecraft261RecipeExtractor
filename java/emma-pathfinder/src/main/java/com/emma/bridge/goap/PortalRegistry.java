package com.emma.bridge.goap;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.*;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistent registry of portal locations across dimensions.
 *
 * Stores portal positions in {@code config/emma_portals.json} so the GOAP
 * system can navigate between dimensions across sessions. Auto-detected by
 * the block scanner in GoapTicker, and manually saveable via WebSocket
 * commands (for Python/Emma).
 *
 * Thread-safe: all mutations synchronize on the instance.
 */
public class PortalRegistry {

    private static final String PORTALS_FILE = "emma_portals.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path filePath;
    private final List<Portal> portals = new ArrayList<>();

    public PortalRegistry() {
        Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        this.filePath = configDir.resolve(PORTALS_FILE);
        load();
    }

    // ── Data class ──────────────────────────────────────────────────

    public record Portal(String name, String type, String dimension,
                         int x, int y, int z, long createdAt) {

        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("name", name);
            j.addProperty("type", type);
            j.addProperty("dimension", dimension);
            j.addProperty("x", x);
            j.addProperty("y", y);
            j.addProperty("z", z);
            j.addProperty("created_at", createdAt);
            return j;
        }

        public static Portal fromJson(JsonObject j) {
            return new Portal(
                    j.get("name").getAsString(),
                    j.get("type").getAsString(),
                    j.get("dimension").getAsString(),
                    j.get("x").getAsInt(),
                    j.get("y").getAsInt(),
                    j.get("z").getAsInt(),
                    j.has("created_at") ? j.get("created_at").getAsLong() : System.currentTimeMillis()
            );
        }
    }

    // ── Queries ─────────────────────────────────────────────────────

    /** Get all portals, optionally filtered by dimension. */
    public synchronized List<Portal> getPortals(String dimension) {
        if (dimension == null || dimension.isEmpty()) {
            return List.copyOf(portals);
        }
        return portals.stream().filter(p -> p.dimension().equals(dimension)).toList();
    }

    /** Check if any portal of the given type exists in the given dimension. */
    public synchronized boolean hasPortalAccess(String dimension, String type) {
        return portals.stream().anyMatch(
                p -> p.dimension().equals(dimension) && p.type().equals(type));
    }

    /** Find the nearest portal of a given type in a dimension. */
    public synchronized Optional<Portal> getNearestPortal(double x, double z,
                                                           String dimension, String type) {
        return portals.stream()
                .filter(p -> p.dimension().equals(dimension) && p.type().equals(type))
                .min((a, b) -> Double.compare(
                        horizontalDistSq(x, z, a.x(), a.z()),
                        horizontalDistSq(x, z, b.x(), b.z())));
    }

    // ── Mutations ───────────────────────────────────────────────────

    /**
     * Add or update a portal. Upserts by (name, dimension) — if a portal with
     * the same name and dimension exists, its position and type are updated.
     *
     * @return The saved portal record
     */
    public synchronized Portal addPortal(String name, String type, String dimension,
                                          int x, int y, int z) {
        // Remove existing with same name+dimension
        portals.removeIf(p -> p.name().equals(name) && p.dimension().equals(dimension));

        Portal portal = new Portal(name, type, dimension, x, y, z, System.currentTimeMillis());
        portals.add(portal);
        save();

        EmmaBridgeMod.LOGGER.info("[PortalRegistry] Saved {} '{}' at ({}, {}, {}) in {}",
                type, name, x, y, z, dimension);
        return portal;
    }

    /** Remove a portal by name and dimension. Returns true if removed. */
    public synchronized boolean removePortal(String name, String dimension) {
        boolean removed = portals.removeIf(
                p -> p.name().equals(name) && p.dimension().equals(dimension));
        if (removed) save();
        return removed;
    }

    // ── Persistence ─────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(filePath)) {
            EmmaBridgeMod.LOGGER.info("[PortalRegistry] No portals file, starting empty");
            return;
        }
        try {
            String json = Files.readString(filePath);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) {
                portals.add(Portal.fromJson(el.getAsJsonObject()));
            }
            EmmaBridgeMod.LOGGER.info("[PortalRegistry] Loaded {} portals from {}",
                    portals.size(), filePath);
        } catch (Exception e) {
            EmmaBridgeMod.LOGGER.error("[PortalRegistry] Failed to load portals", e);
        }
    }

    private void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Portal p : portals) {
                arr.add(p.toJson());
            }
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, GSON.toJson(arr));
        } catch (IOException e) {
            EmmaBridgeMod.LOGGER.error("[PortalRegistry] Failed to save portals", e);
        }
    }

    // ── Utility ─────────────────────────────────────────────────────

    private static double horizontalDistSq(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return dx * dx + dz * dz;
    }
}
