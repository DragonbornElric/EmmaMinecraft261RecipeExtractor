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
 * Persistent registry of named base locations.
 *
 * Stores base positions in {@code config/emma_bases.json} so the GOAP
 * system can navigate to a known base when food is unavailable underground.
 * Set via WebSocket commands (for Python/Emma), queried each tick by
 * GoapTicker to populate WorldState.
 *
 * Thread-safe: all mutations synchronize on the instance.
 */
public class BaseRegistry {

    private static final String BASES_FILE = "emma_bases.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path filePath;
    private final List<Base> bases = new ArrayList<>();

    public BaseRegistry() {
        Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        this.filePath = configDir.resolve(BASES_FILE);
        load();
    }

    // ── Data class ──────────────────────────────────────────────────

    public record Base(String name, String dimension,
                       int x, int y, int z, int radius, long createdAt) {

        public JsonObject toJson() {
            JsonObject j = new JsonObject();
            j.addProperty("name", name);
            j.addProperty("dimension", dimension);
            j.addProperty("x", x);
            j.addProperty("y", y);
            j.addProperty("z", z);
            j.addProperty("radius", radius);
            j.addProperty("created_at", createdAt);
            return j;
        }

        public static Base fromJson(JsonObject j) {
            return new Base(
                    j.get("name").getAsString(),
                    j.get("dimension").getAsString(),
                    j.get("x").getAsInt(),
                    j.get("y").getAsInt(),
                    j.get("z").getAsInt(),
                    j.has("radius") ? j.get("radius").getAsInt() : 32,
                    j.has("created_at") ? j.get("created_at").getAsLong() : System.currentTimeMillis()
            );
        }
    }

    // ── Queries ─────────────────────────────────────────────────────

    /** Get all bases, optionally filtered by dimension. */
    public synchronized List<Base> getBases(String dimension) {
        if (dimension == null || dimension.isEmpty()) {
            return List.copyOf(bases);
        }
        return bases.stream().filter(b -> b.dimension().equals(dimension)).toList();
    }

    /** Check if any base exists in the given dimension. */
    public synchronized boolean hasBase(String dimension) {
        return bases.stream().anyMatch(b -> b.dimension().equals(dimension));
    }

    /** Find the nearest base in a dimension. */
    public synchronized Optional<Base> getNearestBase(double x, double z, String dimension) {
        return bases.stream()
                .filter(b -> b.dimension().equals(dimension))
                .min((a, b) -> Double.compare(
                        horizontalDistSq(x, z, a.x(), a.z()),
                        horizontalDistSq(x, z, b.x(), b.z())));
    }

    /** Find a base by name and dimension. */
    public synchronized Optional<Base> getBase(String name, String dimension) {
        return bases.stream()
                .filter(b -> b.name().equals(name) && b.dimension().equals(dimension))
                .findFirst();
    }

    // ── Mutations ───────────────────────────────────────────────────

    /**
     * Add or update a base. Upserts by (name, dimension) — if a base with
     * the same name and dimension exists, its position and radius are updated.
     *
     * @return The saved base record
     */
    public synchronized Base addBase(String name, String dimension,
                                      int x, int y, int z, int radius) {
        // Remove existing with same name+dimension
        bases.removeIf(b -> b.name().equals(name) && b.dimension().equals(dimension));

        Base base = new Base(name, dimension, x, y, z, radius, System.currentTimeMillis());
        bases.add(base);
        save();

        EmmaBridgeMod.LOGGER.info("[BaseRegistry] Saved base '{}' at ({}, {}, {}) radius={} in {}",
                name, x, y, z, radius, dimension);
        return base;
    }

    /** Remove a base by name and dimension. Returns true if removed. */
    public synchronized boolean removeBase(String name, String dimension) {
        boolean removed = bases.removeIf(
                b -> b.name().equals(name) && b.dimension().equals(dimension));
        if (removed) {
            save();
            EmmaBridgeMod.LOGGER.info("[BaseRegistry] Removed base '{}' in {}", name, dimension);
        }
        return removed;
    }

    // ── Persistence ─────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(filePath)) {
            EmmaBridgeMod.LOGGER.info("[BaseRegistry] No bases file, starting empty");
            return;
        }
        try {
            String json = Files.readString(filePath);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) {
                bases.add(Base.fromJson(el.getAsJsonObject()));
            }
            EmmaBridgeMod.LOGGER.info("[BaseRegistry] Loaded {} bases from {}",
                    bases.size(), filePath);
        } catch (Exception e) {
            EmmaBridgeMod.LOGGER.error("[BaseRegistry] Failed to load bases", e);
        }
    }

    private void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Base b : bases) {
                arr.add(b.toJson());
            }
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, GSON.toJson(arr));
        } catch (IOException e) {
            EmmaBridgeMod.LOGGER.error("[BaseRegistry] Failed to save bases", e);
        }
    }

    // ── Utility ─────────────────────────────────────────────────────

    private static double horizontalDistSq(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return dx * dx + dz * dz;
    }
}
