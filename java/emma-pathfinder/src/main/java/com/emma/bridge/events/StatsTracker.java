package com.emma.bridge.events;

import com.emma.bridge.BridgeServer;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads Mojang's built-in player statistics (mobs killed, blocks mined,
 * distance walked, etc.) and broadcasts them via WebSocket for the OBS
 * overlay.  Follows the HealthTracker pattern: tick-based with dedup.
 *
 * <p>Flow:
 * <ol>
 *   <li>tick() sends REQUEST_STATS every 5 s</li>
 *   <li>Server responds with ClientboundAwardStatsPacket</li>
 *   <li>StatisticsListenerMixin captures the response → onStatsReceived()</li>
 *   <li>We extract tracked stats, compute session deltas, broadcast JSON</li>
 * </ol>
 */
public class StatsTracker {

    private static final StatsTracker INSTANCE = new StatsTracker();
    public static StatsTracker getInstance() { return INSTANCE; }

    private static final long REQUEST_INTERVAL_MS = 5_000;

    // ── Stat definitions (friendly key → Stat<?>) ──────────────────
    // Built lazily once registries are available.
    private Map<String, Stat<?>> statMap;

    // ── State ──────────────────────────────────────────────────────
    private final Map<String, Integer> currentValues  = new LinkedHashMap<>();
    private Map<String, Integer> sessionBaseline      = null;
    private final Map<String, Integer> lastBroadcast  = new LinkedHashMap<>();

    private long lastRequestTime = 0;
    private BridgeServer ws;

    private StatsTracker() {}

    // ── Stat map construction ──────────────────────────────────────

    private void ensureStatMap() {
        if (statMap != null) return;
        statMap = new LinkedHashMap<>();

        // Combat — killed entities
        statMap.put("zombies_killed",    Stats.ENTITY_KILLED.get(EntityType.ZOMBIE));
        statMap.put("skeletons_killed",  Stats.ENTITY_KILLED.get(EntityType.SKELETON));
        statMap.put("creepers_killed",   Stats.ENTITY_KILLED.get(EntityType.CREEPER));
        statMap.put("spiders_killed",    Stats.ENTITY_KILLED.get(EntityType.SPIDER));
        statMap.put("endermen_killed",   Stats.ENTITY_KILLED.get(EntityType.ENDERMAN));

        // Combat — custom
        statMap.put("player_deaths",     Stats.CUSTOM.get(Stats.DEATHS));
        statMap.put("damage_dealt",      Stats.CUSTOM.get(Stats.DAMAGE_DEALT));
        statMap.put("damage_taken",      Stats.CUSTOM.get(Stats.DAMAGE_TAKEN));

        // Mining — specific ores (surface + deepslate variants)
        statMap.put("diamonds_mined",    null); // aggregate
        statMap.put("iron_mined",        null); // aggregate
        statMap.put("gold_mined",        null); // aggregate
        statMap.put("stone_mined",       Stats.BLOCK_MINED.get(Blocks.STONE));

        // Aggregates computed from full map
        statMap.put("blocks_mined",      null);

        // Movement (Mojang stores in cm)
        statMap.put("walk_distance",     Stats.CUSTOM.get(Stats.WALK_ONE_CM));
        statMap.put("sprint_distance",   Stats.CUSTOM.get(Stats.SPRINT_ONE_CM));
        statMap.put("fly_distance",      Stats.CUSTOM.get(Stats.FLY_ONE_CM));
        statMap.put("swim_distance",     Stats.CUSTOM.get(Stats.SWIM_ONE_CM));
        statMap.put("jump_count",        Stats.CUSTOM.get(Stats.JUMP));

        // Misc
        statMap.put("animals_bred",      Stats.CUSTOM.get(Stats.ANIMALS_BRED));
        statMap.put("fish_caught",       Stats.CUSTOM.get(Stats.FISH_CAUGHT));
        statMap.put("play_time",         Stats.CUSTOM.get(Stats.PLAY_TIME));
    }

    // ── Tick (called from EventReporter) ───────────────────────────

    public void tick(BridgeServer ws) {
        this.ws = ws;
        long now = System.currentTimeMillis();
        if (now - lastRequestTime < REQUEST_INTERVAL_MS) return;
        lastRequestTime = now;

        Minecraft client = Minecraft.getInstance();
        ClientPacketListener handler = client.getConnection();
        if (handler != null) {
            handler.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
        }
    }

    // ── Mixin callback ─────────────────────────────────────────────

    /**
     * Called by StatisticsListenerMixin when the server responds with stats.
     */
    public void onStatsReceived(Object2IntMap<Stat<?>> stats) {
        ensureStatMap();

        boolean changed = false;

        // 1. Extract direct-mapped stats
        for (Map.Entry<String, Stat<?>> entry : statMap.entrySet()) {
            Stat<?> stat = entry.getValue();
            if (stat == null) continue; // aggregate — handled below
            int value = stats.getOrDefault(stat, 0);
            Integer prev = currentValues.get(entry.getKey());
            if (prev == null || prev != value) {
                currentValues.put(entry.getKey(), value);
                changed = true;
            }
        }

        // 2. Compute aggregates from full stat map
        int totalMined = 0;
        int diamondsMined = 0;
        int ironMined = 0;
        int goldMined = 0;

        for (Object2IntMap.Entry<Stat<?>> e : stats.object2IntEntrySet()) {
            Stat<?> s = e.getKey();
            int v = e.getIntValue();

            if (s.getType() == Stats.BLOCK_MINED) {
                totalMined += v;
                // Check specific ore blocks
                Object value = s.getValue();
                if (value == Blocks.DIAMOND_ORE || value == Blocks.DEEPSLATE_DIAMOND_ORE) {
                    diamondsMined += v;
                } else if (value == Blocks.IRON_ORE || value == Blocks.DEEPSLATE_IRON_ORE) {
                    ironMined += v;
                } else if (value == Blocks.GOLD_ORE || value == Blocks.DEEPSLATE_GOLD_ORE) {
                    goldMined += v;
                }
            }
        }

        changed |= putIfChanged("blocks_mined", totalMined);
        changed |= putIfChanged("diamonds_mined", diamondsMined);
        changed |= putIfChanged("iron_mined", ironMined);
        changed |= putIfChanged("gold_mined", goldMined);

        // 3. Capture session baseline on first receive
        if (sessionBaseline == null) {
            sessionBaseline = new LinkedHashMap<>(currentValues);
        }

        if (changed) {
            broadcast();
        }
    }

    private boolean putIfChanged(String key, int value) {
        Integer prev = currentValues.get(key);
        if (prev == null || prev != value) {
            currentValues.put(key, value);
            return true;
        }
        return false;
    }

    // ── Broadcast ──────────────────────────────────────────────────

    private void broadcast() {
        if (ws == null || !ws.hasConnections()) return;
        if (lastBroadcast.equals(currentValues)) return;

        lastBroadcast.clear();
        lastBroadcast.putAll(currentValues);

        JsonObject data = new JsonObject();
        for (Map.Entry<String, Integer> entry : currentValues.entrySet()) {
            data.addProperty(entry.getKey(), entry.getValue());
            int baseline = sessionBaseline != null
                    ? sessionBaseline.getOrDefault(entry.getKey(), 0) : 0;
            data.addProperty("delta_" + entry.getKey(),
                    entry.getValue() - baseline);
        }

        ws.broadcastEvent(JsonProtocol.event("player_stats", data));
    }
}
