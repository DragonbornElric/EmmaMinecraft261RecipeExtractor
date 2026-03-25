package com.emma.endinv.options.config.json;

import com.google.gson.*;
import com.emma.endinv.options.config.ComplexConfigEntryImpl;
import com.emma.endinv.options.config.ConfigEntryImpl;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-backed config initializer for common entries.
 * - Lazily reads/writes values with simple caching per entry.
 * - Tolerates missing/incomplete files by falling back to defaults and
 *   populating in-memory JSON for later save.
 * - Short-term: used by fabric module.
 */
public class JsonConfigurationHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path path;
    private final List<ConfigEntryImpl<?>> configEntries;

    private JsonObject root = new JsonObject();
    private boolean dirty = false;

    // Cache per entry to avoid repeated JSON parsing and boxing
    private final Map<ConfigEntryImpl<?>, Object> cache = new IdentityHashMap<>();

    public JsonConfigurationHandler(Path path, List<ConfigEntryImpl<?>> configEntries)
    {
        this.path = path;
        this.configEntries = configEntries;
    }

    public void load()
    {
        root = new JsonObject();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element != null && element.isJsonObject()) {
                    root = element.getAsJsonObject();
                } else {
                    LOGGER.warn("Config file {} is not a JSON object; using defaults in-memory.", path);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed reading config {}. Using defaults.", path, e);
            }
        } else {
            // No file yet; force creation with defaults after binding
            LOGGER.info("Config file {} not found. Creating with defaults.", path);
            dirty = true;
        }

        for (ConfigEntryImpl<?> entry : configEntries) {
            recursiveBind(root, entry);
        }

        // Force-read all entries so defaults populate the JSON object
        for (ConfigEntryImpl<?> entry : configEntries) {
            try { entry.get(); } catch (Exception ignored) {}
        }

        // Persist defaults on first load or when new keys are introduced
        if (dirty) {
            save();
        }
    }

    private void recursiveBind(JsonObject json, ConfigEntryImpl<?> entry)
    {
        switch (entry) {
            case ConfigEntryImpl.BooleanEntry booleanEntry -> bindBoolean(json, booleanEntry);
            case ConfigEntryImpl.IntEntry intEntry -> bindInt(json, intEntry);
            case ConfigEntryImpl.LongEntry longEntry -> bindLong(json, longEntry);
            case ConfigEntryImpl.FloatEntry floatEntry -> bindFloat(json, floatEntry);
            case ConfigEntryImpl.DoubleEntry doubleEntry -> bindDouble(json, doubleEntry);
            case ConfigEntryImpl.StringEntry stringEntry -> bindString(json, stringEntry);
            case ConfigEntryImpl.EnumEntry<?> enumEntry -> bindEnum(json, enumEntry);
            case ConfigEntryImpl.ListEntry<?> listEntry -> bindList(json, (ConfigEntryImpl.ListEntry<?>) listEntry);
            case ComplexConfigEntryImpl<?> complexEntry -> {
                JsonObject section = getOrCreateObject(json, complexEntry.key());
                for (ConfigEntryImpl<?> field : complexEntry.fields()) {
                    recursiveBind(section, field);
                }
                complexEntry.setInitialized();
                complexEntry.setSaver(this::save);
            }
            default -> bindGeneric(json, entry);
        }
    }

    @SuppressWarnings("unchecked")
    private <E> void bindList(JsonObject json, ConfigEntryImpl.ListEntry<E> entry)
    {
        final String key = entry.key();
        final List<E> def = entry.defaultValue();

        entry.initialize(() -> {
            List<E> cached = (List<E>) cache.get(entry);
            if (cached != null) return cached;

            List<E> value = readList(json, key, def);
            if (!json.has(key)) { writeList(json, key, value); dirty = true; }
            cache.put(entry, value);
            return value;
        }, v -> {
            List<E> copy = new ArrayList<>(v);
            cache.put(entry, copy);
            writeList(json, key, copy);
            dirty = true;
        });
        entry.setSaver(this::save);
    }

    private void bindBoolean(JsonObject json, ConfigEntryImpl.BooleanEntry entry)
    {
        final String key = entry.key();
        entry.initialize(() -> {
            Boolean cached = (Boolean) cache.get(entry);
            if (cached != null) return cached;
            boolean value = getBoolean(json, key, entry.defaultValue());
            if (!json.has(key)) { json.addProperty(key, value); dirty = true; }
            cache.put(entry, value);
            return value;
        }, v -> {
            cache.put(entry, v);
            json.addProperty(key, v);
            dirty = true;
        });
        entry.setSaver(this::save);
    }

    private void bindInt(JsonObject json, ConfigEntryImpl.IntEntry entry)
    {
        final String key = entry.key();
        entry.initialize(() -> {
            Integer cached = (Integer) cache.get(entry);
            if (cached != null) return cached;
            int value = getInt(json, key, entry.defaultValue());
            value = clamp(value, entry.getMin(), entry.getMax());
            if (!json.has(key)) { json.addProperty(key, value); dirty = true; }
            cache.put(entry, value);
            return value;
        }, v -> {
            int nv = clamp(v, entry.getMin(), entry.getMax());
            cache.put(entry, nv);
            json.addProperty(key, nv);
            dirty = true;
        });
        entry.setSaver(this::save);
    }

    private void bindLong(JsonObject json, ConfigEntryImpl.LongEntry entry)
    {
        final String key = entry.key();
        entry.initialize(() -> {
            Long cached = (Long) cache.get(entry);
            if (cached != null) return cached;
            long value = getLong(json, key, entry.defaultValue());
            long min = entry.getMin();
            long max = entry.getMax();
            if (value < min) value = min; else if (value > max) value = max;
            if (!json.has(key)) { json.addProperty(key, value); dirty = true; }
            cache.put(entry, value);
            return value;
        }, v -> {
            long nv = v;
            long min = entry.getMin();
            long max = entry.getMax();
            if (nv < min) nv = min; else if (nv > max) nv = max;
            cache.put(entry, nv);
            json.addProperty(key, nv);
            dirty = true;
        });
        entry.setSaver(this::save);
    }

    private void bindFloat(JsonObject json, ConfigEntryImpl.FloatEntry entry)
    {
        final String key = entry.key();
        entry.initialize(() -> {
            Float cached = (Float) cache.get(entry);
            if (cached != null) return cached;
            float value = getFloat(json, key, entry.defaultValue());
            float min = entry.getMin();
            float max = entry.getMax();
            if (value < min) value = min; else if (value > max) value = max;
            if (!json.has(key)) { json.addProperty(key, value); dirty = true; }
            cache.put(entry, value);
            return value;
        }, v -> {
            float nv = v;
            float min = entry.getMin();
            float max = entry.getMax();
            if (nv < min) nv = min; else if (nv > max) nv = max;
            cache.put(entry, nv);
            json.addProperty(key, nv);
            dirty = true;
        });
        entry.setSaver(this::save);
    }

    private void bindDouble(JsonObject json, ConfigEntryImpl.DoubleEntry entry)
    {
        final String key = entry.key();
        entry.initialize(() -> {
            Double cached = (Double) cache.get(entry);
            if (cached != null) return cached;
            double value = getDouble(json, key, entry.defaultValue());
            double min = entry.getMin();
            double max = entry.getMax();
            if (value < min) value = min; else if (value > max) value = max;
            if (!json.has(key)) { json.addProperty(key, value); dirty = true; }
            cache.put(entry, value);
            return value;
        }, v -> {
            double nv = v;
            double min = entry.getMin();
            double max = entry.getMax();
            if (nv < min) nv = min; else if (nv > max) nv = max;
            cache.put(entry, nv);
            json.addProperty(key, nv);
            dirty = true;
        });
        entry.setSaver(this::save);
    }

    private void bindString(JsonObject json, ConfigEntryImpl.StringEntry entry)
    {
        final String key = entry.key();
        entry.initialize(() -> {
            String cached = (String) cache.get(entry);
            if (cached != null) return cached;
            String value = getString(json, key, entry.defaultValue());
            if (!json.has(key)) { json.addProperty(key, value); dirty = true; }
            cache.put(entry, value);
            return value;
        }, v -> {
            cache.put(entry, v);
            json.addProperty(key, v);
            dirty = true;
        });
        entry.setSaver(this::save);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void bindEnum(JsonObject json, ConfigEntryImpl.EnumEntry<?> entry)
    {
        final String key = entry.key();
        final Enum<?> def = (Enum<?>) entry.defaultValue();
        final Class enumClass = def.getDeclaringClass();

        entry.initialize((java.util.function.Supplier) () -> {
            Enum<?> cached = (Enum<?>) cache.get(entry);
            if (cached != null) return cached;
            String name = getString(json, key, def.name());
            Enum<?> value = parseEnum(enumClass, name, def);
            if (!json.has(key)) { json.addProperty(key, value.name()); dirty = true; }
            cache.put(entry, value);
            return value;
        }, (java.util.function.Consumer) (v -> {
            Enum<?> ev = (Enum<?>) v;
            cache.put(entry, ev);
            json.addProperty(key, ev.name());
            dirty = true;
        }));
        entry.setSaver(this::save);
    }
    @SuppressWarnings("unchecked")
    private void bindGeneric(JsonObject json, ConfigEntryImpl<?> entry)
    {
        final String key = entry.key();
        entry.initialize((java.util.function.Supplier) () -> {
            Object cached = cache.get(entry);
            if (cached != null) return cached;
            // Fallback: use default and populate JSON
            Object value = entry.defaultValue();
            putGeneric(json, key, value);
            cache.put(entry, value);
            dirty = true;
            return value;
        }, (java.util.function.Consumer) (v -> {
            cache.put(entry, v);
            putGeneric(json, key, v);
            dirty = true;
        }));
        entry.setSaver(this::save);
    }

    public void save()
    {
        if (!dirty) return;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            dirty = false;
        } catch (IOException e) {
            LOGGER.error("Failed writing config {}", path, e);
        }
    }

    // ---------- JSON helpers ----------

    private static JsonObject getOrCreateObject(JsonObject parent, String key)
    {
        JsonElement el = parent.get(key);
        if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        JsonObject obj = new JsonObject();
        parent.add(key, obj);
        return obj;
    }

    private static boolean getBoolean(JsonObject obj, String key, boolean def)
    {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean();
        return def;
    }

    private static int getInt(JsonObject obj, String key, int def)
    {
        JsonElement el = obj.get(key);
        try {
            if (el != null && el.isJsonPrimitive()) return el.getAsInt();
        } catch (Exception ignored) {}
        return def;
    }

    private static long getLong(JsonObject obj, String key, long def)
    {
        JsonElement el = obj.get(key);
        try {
            if (el != null && el.isJsonPrimitive()) return el.getAsLong();
        } catch (Exception ignored) {}
        return def;
    }

    private static float getFloat(JsonObject obj, String key, float def)
    {
        JsonElement el = obj.get(key);
        try {
            if (el != null && el.isJsonPrimitive()) return el.getAsFloat();
        } catch (Exception ignored) {}
        return def;
    }

    private static double getDouble(JsonObject obj, String key, double def)
    {
        JsonElement el = obj.get(key);
        try {
            if (el != null && el.isJsonPrimitive()) return el.getAsDouble();
        } catch (Exception ignored) {}
        return def;
    }

    private static String getString(JsonObject obj, String key, String def)
    {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            try { return el.getAsString(); } catch (Exception ignored) {}
        }
        return def;
    }

    private static void putGeneric(JsonObject obj, String key, Object v)
    {
        if (v == null) { obj.add(key, null); return; }
        if (v instanceof Boolean b) {
            obj.addProperty(key, b);
        } else if (v instanceof Number n) {
            obj.addProperty(key, n);
        } else if (v instanceof String s) {
            obj.addProperty(key, s);
        } else if (v instanceof Enum<?> e) {
            obj.addProperty(key, e.name());
        } else if (v instanceof List<?> list) {
            JsonArray arr = new JsonArray();
            for (Object o : list) {
                if (o instanceof String s) arr.add(s);
                else if (o instanceof Number n) arr.add(n);
                else if (o instanceof Boolean b) arr.add(b);
                else if (o instanceof Enum<?> e2) arr.add(e2.name());
                else arr.add(String.valueOf(o));
            }
            obj.add(key, arr);
        } else {
            // Fallback to string representation
            obj.addProperty(key, String.valueOf(v));
        }
    }

    @SuppressWarnings("unchecked")
    private static <E> List<E> readList(JsonObject obj, String key, List<E> def)
    {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonArray()) return new ArrayList<>(def);

        JsonArray arr = el.getAsJsonArray();
        List<E> out = new ArrayList<>(arr.size());
        Class<?> elemClass = null;
        if (!def.isEmpty() && def.get(0) != null) elemClass = def.get(0).getClass();

        for (JsonElement e : arr) {
            try {
                if (elemClass == null || elemClass == String.class) {
                    out.add((E) (e.isJsonNull() ? null : e.getAsString()));
                } else if (elemClass == Integer.class) {
                    out.add((E) Integer.valueOf(e.getAsInt()));
                } else if (elemClass == Long.class) {
                    out.add((E) Long.valueOf(e.getAsLong()));
                } else if (elemClass == Float.class) {
                    out.add((E) Float.valueOf(e.getAsFloat()));
                } else if (elemClass == Double.class) {
                    out.add((E) Double.valueOf(e.getAsDouble()));
                } else if (elemClass == Boolean.class) {
                    out.add((E) Boolean.valueOf(e.getAsBoolean()));
                } else {
                    // Unknown element type; fall back to string
                    out.add((E) (e.isJsonNull() ? null : e.getAsString()));
                }
            } catch (Exception ignore) {
                // Skip malformed element
            }
        }
        return out;
    }

    private static <E> void writeList(JsonObject obj, String key, List<E> list)
    {
        JsonArray arr = new JsonArray();
        for (Object o : list) {
            if (o == null) { arr.add((String) null); continue; }
            if (o instanceof String s) arr.add(s);
            else if (o instanceof Number n) arr.add(n);
            else if (o instanceof Boolean b) arr.add(b);
            else if (o instanceof Enum<?> e) arr.add(e.name());
            else arr.add(String.valueOf(o));
        }
        obj.add(key, arr);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> parseEnum(Class enumClass, String name, Enum<?> def)
    {
        if (name == null) return def;

        // 1) Fast paths: exact match and simple case-only mismatch
        try { return Enum.valueOf(enumClass, name); } catch (Exception ignored) {}
        try { return Enum.valueOf(enumClass, name.trim()); } catch (Exception ignored) {}
        try { return Enum.valueOf(enumClass, name.toUpperCase()); } catch (Exception ignored) {}

        // 2) Normalize common user/input variants: trim, replace spaces/hyphens with underscores,
        //    collapse multiple underscores, and try UPPER_SNAKE_CASE.
        String normalized = name.trim()
                .replace(' ', '_')
                .replace('-', '_')
                .replace('\t', '_');
        while (normalized.contains("__")) normalized = normalized.replace("__", "_");
        String upperSnake = normalized.toUpperCase();
        try { return Enum.valueOf(enumClass, upperSnake); } catch (Exception ignored) {}

        // 3) Try converting camel/pascal case into snake and match
        String camelToSnake = name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
        try { return Enum.valueOf(enumClass, camelToSnake); } catch (Exception ignored) {}

        // 4) Iterate constants for tolerant comparisons (ignore underscores and case)
        Object[] constants = enumClass.getEnumConstants();
        if (constants != null) {
            String compact = upperSnake.replace("_", "");
            for (Object c : constants) {
                if (c instanceof Enum<?> e) {
                    String en = e.name();
                    if (en.equalsIgnoreCase(name) || en.equalsIgnoreCase(upperSnake)) return e;
                    if (en.replace("_", "").equalsIgnoreCase(compact)) return e;
                }
            }

            // 5) Numeric ordinal support: allow users to specify index as string
            try {
                int idx = Integer.parseInt(name.trim());
                if (idx >= 0 && idx < constants.length && constants[idx] instanceof Enum<?> e) return e;
            } catch (Exception ignored) {}
        }

        // 6) Fallback to default
        return def;
    }

    private static int clamp(int v, int min, int max)
    {
        return v < min ? min : Math.min(v, max);
    }
}
