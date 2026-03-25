package com.emma.bridge.goap;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.catalogue.ItemRecipeEntry;
import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.catalogue.ObtainMethod;
import com.emma.bridge.util.InventoryScanner;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;

import com.emma.bridge.BridgeServer;
import com.emma.bridge.commands.SetModeHandler;
import com.emma.bridge.websocket.JsonProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * Per-tick GOAP orchestrator. Runs in END_CLIENT_TICK callback.
 *
 * Each tick:
 *   1. Run reflexes (sub-tick reactions: shield, force field, MLG)
 *   2. Update WorldState from current game state
 *   3. Compute world state diff for debug
 *   4. Score all actions via UtilityScorer
 *   5. Execute winning action (or continue active action)
 *   6. Populate AgentDebugState as side-effect
 *
 * Zero EmmaClef dependencies — uses Minecraft directly.
 */
public class GoapTicker {

    private final WorldState worldState = new WorldState();
    private WorldState previousState = null;

    private final GoalSet goalSet;
    private final ActionRegistry registry;
    private final UtilityScorer scorer;
    private final ReflexLayer reflexLayer = new ReflexLayer();
    private final GoalDecomposer decomposer = new GoalDecomposer();
    private PortalRegistry portalRegistry;
    private BaseRegistry baseRegistry;

    private com.emma.bridge.events.ContainerTracker containerTracker = null;
    private BridgeServer bridgeServer = null;

    private GoapAction activeAction = null;
    private int activeActionTicks = 0;  // ticks since activeAction.execute()
    private boolean activeActionWasActive = false;  // true if isActive() returned true at least once this stint
    private boolean enabled = false;

    /** GOAP briefing: emitted on action switch, throttled to avoid flood. */
    private static final long BRIEFING_COOLDOWN_MS = 3000;
    private long lastBriefingMs = 0;

    /** Throttle: only score every N ticks (1 = every tick). */
    private int scoreInterval = 1;
    private int tickCounter = 0;

    /** Periodic GOAP status logging (every 40 ticks = 2 seconds). */
    private static final int LOG_INTERVAL = 40;
    private int logCounter = 0;
    private boolean loggingEnabled = true;

    /** Block scanner: runs every 20 ticks (1 second) to populate nearbyBlocks. */
    private static final int BLOCK_SCAN_INTERVAL = 20;
    private static final int BLOCK_SCAN_RADIUS = 32;
    private static final int BLOCK_SCAN_Y_RANGE = 32;
    private int blockScanCounter = 0;

    /** Goal decomposition: event-driven with debounce. */
    private static final int DECOMPOSE_DEBOUNCE_TICKS = 10;
    private boolean decomposeDirty = false;
    private int decomposeDebounceCounter = 0;
    private int lastInventoryHash = 0;

    public GoapTicker(GoalSet goalSet, ActionRegistry registry) {
        this.goalSet = goalSet;
        this.registry = registry;
        this.scorer = new UtilityScorer(registry, goalSet);
    }

    /**
     * Called every END_CLIENT_TICK. Does nothing if GOAP is disabled.
     */
    public void tick(Minecraft client) {
        if (!enabled) return;

        LocalPlayer player = client.player;
        if (player == null) return;
        ClientLevel world = client.level;
        if (world == null) return;

        // Update world state snapshot
        worldState.update(player, world);

        // Populate portal access flags from PortalRegistry
        if (portalRegistry != null) {
            worldState.hasNetherPortal = portalRegistry.hasPortalAccess(worldState.dimension, "nether_portal");
            worldState.hasEndPortal = portalRegistry.hasPortalAccess(worldState.dimension, "end_portal");
        }

        // Populate base location from BaseRegistry
        if (baseRegistry != null) {
            var nearestBase = baseRegistry.getNearestBase(worldState.posX, worldState.posZ, worldState.dimension);
            if (nearestBase.isPresent()) {
                var base = nearestBase.get();
                worldState.hasBase = true;
                worldState.baseX = base.x();
                worldState.baseY = base.y();
                worldState.baseZ = base.z();
                worldState.baseName = base.name();
            } else {
                worldState.hasBase = false;
                worldState.baseName = null;
            }
        }

        // Update End-dimension state (dragon, crystals)
        if (worldState.dimension.contains("the_end")) {
            worldState.updateEndState(player, world);
        }

        // Populate knownStorage from ContainerTracker cache
        if (containerTracker != null) {
            worldState.setKnownStorage(containerTracker.getAggregatedItemCounts());
        }

        // Inventory change detection → trigger goal decomposition
        int currentInvHash = worldState.playerInventory.hashCode();
        if (currentInvHash != lastInventoryHash) {
            lastInventoryHash = currentInvHash;
            decomposeDirty = true;
            decomposeDebounceCounter = 0;
        }

        // Debounced decomposition (10 ticks after last inventory change)
        if (decomposeDirty) {
            decomposeDebounceCounter++;
            if (decomposeDebounceCounter >= DECOMPOSE_DEBOUNCE_TICKS) {
                runDecomposition();
                decomposeDirty = false;
            }
        }

        // Block scanner: populate nearbyBlocks every N ticks
        if (++blockScanCounter >= BLOCK_SCAN_INTERVAL) {
            blockScanCounter = 0;
            scanNearbyBlocks(player, world);
        }

        // Compute diff for debug visibility
        AgentDebugState debug = AgentDebugState.getInstance();
        if (previousState != null) {
            debug.worldStateDiff = worldState.diff(previousState);
        }
        debug.lastWorldState = worldState;

        // Snapshot active reflexes before tick for change detection
        var reflexesBefore = loggingEnabled ? new java.util.HashSet<>(
                reflexLayer.getActiveReflexes().stream().map(GoapReflex::getName).toList()) : null;

        // Tick reflexes before scoring (sub-tick reactions)
        reflexLayer.tick(worldState, client);

        // Log reflex changes
        if (loggingEnabled && reflexesBefore != null) {
            for (GoapReflex reflex : reflexLayer.getActiveReflexes()) {
                if (!reflexesBefore.contains(reflex.getName())) {
                    EmmaBridgeMod.LOGGER.info("[GOAP] Reflex: {} FIRED", reflex.getName());
                }
            }
            for (String name : reflexesBefore) {
                boolean stillActive = reflexLayer.getActiveReflexes().stream()
                        .anyMatch(r -> r.getName().equals(name));
                if (!stillActive) {
                    EmmaBridgeMod.LOGGER.info("[GOAP] Reflex: {} RELEASED", name);
                }
            }
        }

        // Throttle scoring (default: every tick)
        tickCounter++;
        if (tickCounter < scoreInterval) return;
        tickCounter = 0;

        // Skip scoring if a reflex suppresses it (e.g., shield blocking, MLG)
        if (reflexLayer.isScoringSupressed()) return;

        // Score and select best action
        GoapAction winner = scorer.scoreAndSelect(worldState);

        // Commitment lock: don't switch away from an action that needs minimum ticks
        // (e.g., eating = 32 ticks, attack cooldown = 13 ticks)
        // Reflexes already ran above and can still preempt via isScoringSupressed()
        if (activeAction != null && winner != activeAction) {
            int minTicks = activeAction.getMinimumActiveTicks();
            if (minTicks > 0 && activeActionTicks < minTicks) {
                // Stay with current action — commitment not yet fulfilled
                winner = activeAction;
            }
        }

        // No winner — deactivate current action if one is running
        if (winner == null && activeAction != null) {
            try {
                activeAction.onDeactivated(client);
            } catch (Exception e) {
                EmmaBridgeMod.LOGGER.warn("[GOAP] Error deactivating {}", activeAction.getName(), e);
            }
            if (loggingEnabled) {
                EmmaBridgeMod.LOGGER.info("[GOAP] Deactivated {} (no viable actions)", activeAction.getName());
            }
            activeAction = null;
            activeActionTicks = 0;
        }

        // Execute or switch action
        if (winner != null) {
            if (winner != activeAction) {
                String oldName = activeAction != null ? activeAction.getName() : "none";

                // Deactivate old action
                if (activeAction != null) {
                    try {
                        activeAction.onDeactivated(client);
                    } catch (Exception e) {
                        EmmaBridgeMod.LOGGER.warn("[GOAP] Error deactivating {}", activeAction.getName(), e);
                    }
                }

                // Activate new action
                activeAction = winner;
                activeActionTicks = 0;
                activeActionWasActive = false;
                try {
                    activeAction.execute(client);
                } catch (Exception e) {
                    EmmaBridgeMod.LOGGER.error("[GOAP] Error executing {}", activeAction.getName(), e);
                }

                // Log action switch
                float winnerScore = getActionScore(winner.getName());
                if (loggingEnabled) {
                    EmmaBridgeMod.LOGGER.info("[GOAP] Switch: {} -> {} ({}) | HP: {} | Food: {} | Threats: {}",
                            oldName, winner.getName(), String.format("%.1f", winnerScore),
                            String.format("%.0f", worldState.health),
                            worldState.foodItemCount, worldState.threats.size());
                }

                // Emit goap_briefing event to Emma (throttled)
                emitGoapBriefing(oldName, winner.getName(), winnerScore, client);
            }

            // If the action was active and then deactivated itself (e.g., CraftItem
            // finished one recipe) but still scores positively (next recipe in chain
            // ready), re-execute. The activeActionWasActive guard prevents firing on
            // actions that never report isActive()=true (e.g., NavigateTo).
            if (activeActionWasActive && !activeAction.isActive()) {
                activeActionWasActive = false;  // reset for next stint
                try {
                    activeAction.execute(client);
                    activeActionTicks = 0;
                    scorer.resetStallTracking(worldState);
                } catch (Exception e) {
                    EmmaBridgeMod.LOGGER.error("[GOAP] Error re-executing {}", activeAction.getName(), e);
                }
                if (loggingEnabled) {
                    float reScore = getActionScore(activeAction.getName());
                    EmmaBridgeMod.LOGGER.info("[GOAP] Re-execute: {} ({}) | HP: {} | Food: {} | Threats: {}",
                            activeAction.getName(), String.format("%.1f", reScore),
                            String.format("%.0f", worldState.health),
                            worldState.foodItemCount, worldState.threats.size());
                }
            }

            // Tick the active action (ongoing behavior)
            activeActionTicks++;
            try {
                activeAction.tick(client);
            } catch (Exception e) {
                EmmaBridgeMod.LOGGER.warn("[GOAP] Error ticking {}", activeAction.getName(), e);
            }

            // Track if the action became active (so we can detect self-deactivation later)
            if (activeAction.isActive()) {
                activeActionWasActive = true;
            }
        }

        // Periodic status log
        if (loggingEnabled && ++logCounter >= LOG_INTERVAL) {
            logCounter = 0;
            logStatus();
        }

        // Save for next diff
        previousState = worldState.snapshot();
    }

    // ── Control ───────────────────────────────────────────────────

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            if (activeAction != null) {
                try {
                    activeAction.onDeactivated(Minecraft.getInstance());
                } catch (Exception e) {
                    EmmaBridgeMod.LOGGER.warn("[GOAP] Error deactivating on disable", e);
                }
                activeAction = null;
            }
            scorer.reset();
        }
        EmmaBridgeMod.LOGGER.info("[GOAP] {} (registry has {} actions)",
                enabled ? "Enabled" : "Disabled", registry.size());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setScoreInterval(int ticks) {
        this.scoreInterval = Math.max(1, ticks);
    }

    public void setLoggingEnabled(boolean enabled) {
        this.loggingEnabled = enabled;
    }

    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }

    // ── Goal decomposition ──────────────────────────────────────

    /**
     * Trigger immediate goal decomposition. Called from SetGoalsHandler
     * when goals change (no debounce needed — goals just changed).
     */
    public void triggerDecomposition() {
        runDecomposition();
        decomposeDirty = false;
    }

    /**
     * Run goal decomposition and update derived goals.
     */
    private void runDecomposition() {
        var derived = decomposer.decompose(goalSet, worldState);
        goalSet.setDerivedGoals(derived);
    }

    // ── Accessors for wiring ──────────────────────────────────────

    public GoalSet getGoalSet() {
        return goalSet;
    }

    public ActionRegistry getRegistry() {
        return registry;
    }

    public UtilityScorer getScorer() {
        return scorer;
    }

    public WorldState getWorldState() {
        return worldState;
    }

    /** Set the ContainerTracker to populate knownStorage each tick. */
    public void setContainerTracker(com.emma.bridge.events.ContainerTracker tracker) {
        this.containerTracker = tracker;
    }

    /** Set the PortalRegistry for portal block auto-detection. */
    public void setPortalRegistry(PortalRegistry registry) {
        this.portalRegistry = registry;
    }

    public PortalRegistry getPortalRegistry() {
        return portalRegistry;
    }

    /** Set the BaseRegistry for persistent base location tracking. */
    public void setBaseRegistry(BaseRegistry registry) {
        this.baseRegistry = registry;
    }

    public BaseRegistry getBaseRegistry() {
        return baseRegistry;
    }

    public GoapAction getActiveAction() {
        return activeAction;
    }

    public ReflexLayer getReflexLayer() {
        return reflexLayer;
    }

    /** Set the BridgeServer for emitting goap_briefing events. */
    public void setBridgeServer(BridgeServer server) {
        this.bridgeServer = server;
    }

    // ── GOAP Briefing ─────────────────────────────────────────────

    /**
     * Emit a goap_briefing event when the active action switches.
     * Includes all goals, vitals, nearby threats, mode, and a compact heightmap.
     * Throttled to one briefing per BRIEFING_COOLDOWN_MS.
     */
    private void emitGoapBriefing(String previousAction, String currentAction,
                                   float currentScore, Minecraft client) {
        if (bridgeServer == null || !bridgeServer.hasConnections()) return;

        long now = System.currentTimeMillis();
        if (now - lastBriefingMs < BRIEFING_COOLDOWN_MS) return;
        lastBriefingMs = now;

        JsonObject payload = new JsonObject();
        payload.addProperty("previous_action", previousAction);
        payload.addProperty("current_action", currentAction);
        payload.addProperty("current_score", currentScore);
        payload.addProperty("mode", SetModeHandler.getCurrentMode().name().toLowerCase());

        // All goals with priorities
        payload.add("goals", goalSet.toJson());

        // Vitals
        JsonObject vitals = new JsonObject();
        vitals.addProperty("health", worldState.health);
        vitals.addProperty("max_health", worldState.maxHealth);
        vitals.addProperty("hunger", worldState.hunger);
        vitals.addProperty("armor", worldState.armorValue);
        vitals.addProperty("dimension", worldState.dimension);
        vitals.addProperty("threat_count", worldState.threats.size());
        vitals.addProperty("food_items", worldState.foodItemCount);
        vitals.addProperty("light_level", worldState.lightLevel);
        payload.add("vitals", vitals);

        // Nearby threats (up to 5)
        JsonArray threats = new JsonArray();
        int threatLimit = Math.min(5, worldState.threats.size());
        for (int i = 0; i < threatLimit; i++) {
            var threat = worldState.threats.get(i);
            JsonObject t = new JsonObject();
            t.addProperty("type", threat.type);
            t.addProperty("distance", Math.round(threat.distance * 10.0) / 10.0);
            t.addProperty("health", threat.health);
            threats.add(t);
        }
        payload.add("nearby_threats", threats);

        // Compact heightmap (11x11 grid, step=2, radius=10 blocks)
        if (client.level != null && client.player != null) {
            payload.add("heightmap", buildCompactHeightmap(client));
        }

        bridgeServer.broadcastEvent(JsonProtocol.event("goap_briefing", payload));
    }

    /**
     * Build a compact 11x11 heightmap centered on the player.
     * Step=2, radius=10 → covers 21x21 block area downsampled to 11x11.
     */
    private JsonObject buildCompactHeightmap(Minecraft client) {
        int cx = client.player.blockPosition().getX();
        int cz = client.player.blockPosition().getZ();
        int radius = 10;
        int step = 2;

        JsonArray rows = new JsonArray();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        for (int z = cz - radius; z <= cz + radius; z += step) {
            JsonArray row = new JsonArray();
            for (int x = cx - radius; x <= cx + radius; x += step) {
                int y = client.level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                row.add(y);
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
            rows.add(row);
        }

        JsonObject hm = new JsonObject();
        hm.add("heights", rows);
        hm.addProperty("center_x", cx);
        hm.addProperty("center_z", cz);
        hm.addProperty("min_y", minY);
        hm.addProperty("max_y", maxY);
        return hm;
    }

    // ── Logging ───────────────────────────────────────────────────

    /** Periodic status log: active action, runner-up, key vitals. */
    private void logStatus() {
        AgentDebugState debug = AgentDebugState.getInstance();
        String activeName = activeAction != null ? activeAction.getName() : "none";
        float activeScore = getActionScore(activeName);

        // Find runner-up (second highest score)
        String runnerUp = "none";
        float runnerUpScore = 0;
        for (AgentDebugState.ScoredAction sa : debug.lastAuction) {
            if (!sa.name.equals(activeName) && sa.score > runnerUpScore) {
                runnerUpScore = sa.score;
                runnerUp = sa.name;
            }
        }

        // Count free inventory slots
        int freeSlots = 0;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            freeSlots = InventoryScanner.emptySlots(client.player.getInventory());
        }

        EmmaBridgeMod.LOGGER.info("[GOAP] Active: {} ({}) | Runner-up: {} ({}) | HP: {} | Food: {}/{} | Light: {} | Threats: {} | Inv: {}/36",
                activeName, String.format("%.1f", activeScore),
                runnerUp, String.format("%.1f", runnerUpScore),
                String.format("%.0f", worldState.health),
                worldState.foodItemCount, 5,  // threshold
                worldState.lightLevel,
                worldState.threats.size(),
                36 - freeSlots);
    }

    /** Look up an action's score from the last auction. */
    private float getActionScore(String actionName) {
        for (AgentDebugState.ScoredAction sa : AgentDebugState.getInstance().lastAuction) {
            if (sa.name.equals(actionName)) return sa.score;
        }
        return 0;
    }

    // ── Block Scanner ─────────────────────────────────────────────

    /**
     * Scan nearby blocks for types relevant to active goals.
     * Populates worldState.nearbyBlocks with block ID → positions.
     *
     * Scans for:
     *   1. Blocks that drop items needed by active goals (via ItemRecipeRegistry)
     *   2. Crafting tables and furnaces (needed by CraftItem/SmeltItem actions)
     */
    private void scanNearbyBlocks(LocalPlayer player, ClientLevel world) {
        // Determine what block types we care about
        Set<String> targetBlocks = computeGoalRelevantBlocks();
        // Always scan for crafting stations
        targetBlocks.add("minecraft:crafting_table");
        targetBlocks.add("minecraft:furnace");
        targetBlocks.add("minecraft:blast_furnace");
        targetBlocks.add("minecraft:smoker");
        // Always scan for portal blocks (auto-detect and persist)
        targetBlocks.add("minecraft:nether_portal");
        targetBlocks.add("minecraft:end_portal_frame");

        // When no base exists in overworld, also scan for village indicators
        boolean scanVillage = !worldState.hasBase
                && worldState.dimension.contains("overworld");
        Set<String> villageTargets = scanVillage
                ? com.emma.bridge.goap.actions.EstablishBaseAction.VILLAGE_INDICATOR_BLOCKS
                : Set.of();

        // Build a set of Block objects for fast matching
        Map<Block, String> blockToId = new HashMap<>();
        for (String blockId : targetBlocks) {
            resolveBlock(blockId, blockToId);
        }
        // Village indicators go into a separate lookup for routing
        Map<Block, String> villageBlockToId = new HashMap<>();
        for (String blockId : villageTargets) {
            if (!blockToId.containsValue(blockId)) {  // avoid duplicate scanning
                resolveBlock(blockId, villageBlockToId);
            }
        }

        if (blockToId.isEmpty() && villageBlockToId.isEmpty()) {
            worldState.setNearbyBlocks(Map.of());
            worldState.setVillageBlocks(Map.of());
            return;
        }

        // Scan in radius around player
        Map<String, List<BlockPos>> result = new HashMap<>();
        Map<String, List<BlockPos>> villageResult = new HashMap<>();
        BlockPos center = player.blockPosition();

        for (int dx = -BLOCK_SCAN_RADIUS; dx <= BLOCK_SCAN_RADIUS; dx++) {
            for (int dz = -BLOCK_SCAN_RADIUS; dz <= BLOCK_SCAN_RADIUS; dz++) {
                for (int dy = -BLOCK_SCAN_Y_RANGE; dy <= BLOCK_SCAN_Y_RANGE; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

                    String id = blockToId.get(block);
                    if (id != null) {
                        result.computeIfAbsent(id, k -> new ArrayList<>()).add(pos);
                    }
                    // Village indicators route to separate map
                    String vid = villageBlockToId.get(block);
                    if (vid != null) {
                        villageResult.computeIfAbsent(vid, k -> new ArrayList<>()).add(pos);
                    }
                }
            }
        }

        // Filter out unreachable positions — all actions naturally skip them
        Map<BlockPos, Long> unreachable = scorer.getGlobalKnowledge().unreachableBlocks;
        if (!unreachable.isEmpty()) {
            for (List<BlockPos> positions : result.values()) {
                positions.removeIf(unreachable::containsKey);
            }
            result.values().removeIf(List::isEmpty);
        }

        worldState.setNearbyBlocks(result);
        worldState.setVillageBlocks(villageResult);

        // Auto-save detected portals to PortalRegistry
        if (portalRegistry != null) {
            autoSavePortals(result);
        }
    }

    /** Resolve a block ID string to a Block object and add to the lookup map. */
    private void resolveBlock(String blockId, Map<Block, String> map) {
        var id = blockId.contains(":")
                ? net.minecraft.resources.Identifier.parse(blockId)
                : net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", blockId);
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
            map.put(block, blockId);
        }
    }

    /**
     * Auto-save portal blocks found by the scanner to PortalRegistry.
     * Uses the first detected block position for each portal type.
     */
    private void autoSavePortals(Map<String, List<BlockPos>> scanResult) {
        List<BlockPos> netherPortals = scanResult.get("minecraft:nether_portal");
        if (netherPortals != null && !netherPortals.isEmpty()) {
            BlockPos pos = netherPortals.get(0);
            portalRegistry.addPortal("nether_portal_auto", "nether_portal",
                    worldState.dimension, pos.getX(), pos.getY(), pos.getZ());
        }
        List<BlockPos> endPortals = scanResult.get("minecraft:end_portal_frame");
        if (endPortals != null && !endPortals.isEmpty()) {
            BlockPos pos = endPortals.get(0);
            portalRegistry.addPortal("end_portal_auto", "end_portal",
                    worldState.dimension, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /**
     * Determine which block types are relevant to active goals.
     * Walks transitive dependencies of each have_item goal and finds
     * blocks that mine those items.
     *
     * Handles tag equivalents: if any log variant is needed, scans for ALL
     * log variants (MC recipes resolve #logs/#planks tags to one specific type,
     * but any variant works).
     */
    private Set<String> computeGoalRelevantBlocks() {
        Set<String> blocks = new HashSet<>();

        // When food is low, include all food source blocks + raw material blocks
        if (worldState.foodItemCount < com.emma.bridge.goap.actions.CollectFoodAction.LOW_FOOD_THRESHOLD) {
            Set<String> foodBlocks = ItemRecipeRegistry.getFoodSourceBlocks();
            for (String block : foodBlocks) {
                blocks.add(block.contains(":") ? block : "minecraft:" + block);
            }
            // Also add raw material blocks for indirect food (e.g., wheat for bread)
            for (var candidate : ItemRecipeRegistry.getFoodCandidatesByEfficiency()) {
                for (String dep : ItemRecipeRegistry.getTransitiveDependencies(candidate.itemId())) {
                    for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(dep)) {
                        if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
                        if (entry.getMineBlockNames() != null) {
                            for (String block : entry.getMineBlockNames()) {
                                blocks.add(block.contains(":") ? block : "minecraft:" + block);
                            }
                        }
                    }
                }
            }
        }

        for (GoalSet.Goal goal : goalSet.getGoals()) {
            if (goal.target == null || !goal.target.has("item")) continue;

            String goalItem = goal.target.get("item").getAsString();
            String goalId = goalItem.contains(":") ? goalItem.split(":")[1] : goalItem;

            // Get all transitive deps including the goal item itself
            List<String> deps = ItemRecipeRegistry.getTransitiveDependencies(goalId);
            deps.add(goalId);

            for (String dep : deps) {
                // Check if this dep has a MINE method
                List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(dep);
                for (ItemRecipeEntry entry : entries) {
                    if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
                    String[] mineBlocks = entry.getMineBlockNames();
                    if (mineBlocks != null) {
                        for (String mb : mineBlocks) {
                            String fullId = mb.contains(":") ? mb : "minecraft:" + mb;
                            blocks.add(fullId);
                        }
                    }
                }
            }
        }

        // Also include mine_block targets from derived goals (e.g., tool prereq
        // chains that aren't in getTransitiveDependencies — cobblestone for
        // stone_pickaxe when the goal is iron_pickaxe)
        for (GoalSet.Goal dg : goalSet.getDerivedGoals()) {
            if (dg.target == null) continue;
            if (dg.target.has("mine_block")) {
                String mb = dg.target.get("mine_block").getAsString();
                blocks.add(mb.contains(":") ? mb : "minecraft:" + mb);
            }
            // Also check the item directly for MINE entries
            if (dg.target.has("item") && dg.target.has("obtain_method")) {
                String method = dg.target.get("obtain_method").getAsString();
                if ("MINE".equals(method)) {
                    String itemId = dg.target.get("item").getAsString();
                    String cleanId = itemId.contains(":") ? itemId.split(":")[1] : itemId;
                    for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(cleanId)) {
                        if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
                        String[] mineBlocks = entry.getMineBlockNames();
                        if (mineBlocks != null) {
                            for (String block : mineBlocks) {
                                blocks.add(block.contains(":") ? block : "minecraft:" + block);
                            }
                        }
                    }
                }
            }
        }

        return blocks;
    }
}
