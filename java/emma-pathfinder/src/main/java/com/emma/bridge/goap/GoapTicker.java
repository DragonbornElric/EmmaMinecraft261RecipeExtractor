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

    private com.emma.bridge.events.ContainerTracker containerTracker = null;

    private GoapAction activeAction = null;
    private int activeActionTicks = 0;  // ticks since activeAction.execute()
    private boolean enabled = false;

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
    private static final int BLOCK_SCAN_Y_RANGE = 8;
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
                try {
                    activeAction.execute(client);
                } catch (Exception e) {
                    EmmaBridgeMod.LOGGER.error("[GOAP] Error executing {}", activeAction.getName(), e);
                }

                // Log action switch
                if (loggingEnabled) {
                    float winnerScore = getActionScore(winner.getName());
                    EmmaBridgeMod.LOGGER.info("[GOAP] Switch: {} -> {} ({}) | HP: {} | Food: {} | Threats: {}",
                            oldName, winner.getName(), String.format("%.1f", winnerScore),
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

    public GoapAction getActiveAction() {
        return activeAction;
    }

    public ReflexLayer getReflexLayer() {
        return reflexLayer;
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

        if (targetBlocks.isEmpty()) {
            worldState.setNearbyBlocks(Map.of());
            return;
        }

        // Build a set of Block objects for fast matching
        Map<Block, String> blockToId = new HashMap<>();
        for (String blockId : targetBlocks) {
            var id = blockId.contains(":") ?
                    net.minecraft.resources.Identifier.parse(blockId) :
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", blockId);
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                blockToId.put(block, blockId);
            }
        }

        // Scan in radius around player
        Map<String, List<BlockPos>> result = new HashMap<>();
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

        // Auto-save detected portals to PortalRegistry
        if (portalRegistry != null) {
            autoSavePortals(result);
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

        // Expand tag equivalents: if any log variant is needed, add ALL log variants
        expandTagEquivalents(blocks);

        return blocks;
    }

    /**
     * MC recipes resolve tags (#logs, #planks, #stone_tool_materials) to one
     * specific variant, but any member of the tag works. If any variant of a
     * tag group appears in the block set, add all variants.
     */
    private static void expandTagEquivalents(Set<String> blocks) {
        // Log variants — all interchangeable for planks recipes
        List<String> allLogs = List.of(
                "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
                "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
                "minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:pale_oak_log",
                "minecraft:crimson_stem", "minecraft:warped_stem"
        );

        // Stone variants — cobblestone, blackstone, cobbled_deepslate all work for stone tools
        List<String> stoneMaterials = List.of(
                "minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate",
                "minecraft:cobbled_deepslate", "minecraft:blackstone"
        );

        expandGroup(blocks, allLogs);
        expandGroup(blocks, stoneMaterials);
    }

    private static void expandGroup(Set<String> blocks, List<String> group) {
        for (String member : group) {
            if (blocks.contains(member)) {
                blocks.addAll(group);
                return;
            }
        }
    }
}
