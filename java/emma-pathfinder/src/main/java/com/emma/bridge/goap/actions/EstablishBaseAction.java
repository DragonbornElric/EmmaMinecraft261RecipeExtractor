package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.goap.BaseRegistry;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * GOAP Action: Find a nearby village and register it as the initial base.
 *
 * When no base exists in the overworld, this action explores to find village
 * indicators (bells, beds, job site blocks, villagers). Once found, it maps
 * the village extent by walking scan points around the estimated center,
 * then registers the bounding box as a base via BaseRegistry.
 *
 * Spawn-setting is handled naturally by SleepAction once the base is
 * established near village beds.
 *
 * Phase machine:
 *   EXPLORE        → Walk randomly looking for village indicators
 *   SCAN_VILLAGE   → Map village extent by walking compass points
 *   REGISTER_BASE  → Compute center + radius, register base
 *   DONE           → Deactivate
 */
public class EstablishBaseAction extends GoapAction {

    // ── Command gate ────────────────────────────────────────────────
    // Only activates when triggered by @base establish command
    private static volatile boolean commandTriggered = false;

    /** Called by ChatCommandInterceptor when player types @base establish. */
    public static void triggerByCommand() {
        commandTriggered = true;
    }

    /** Whether the command has been triggered (used by GoapTicker for village scanning). */
    public static boolean isCommandTriggered() {
        return commandTriggered;
    }

    // ── Village indicator block IDs ──────────────────────────────────

    /** Block types that indicate a village. Exposed for GoapTicker scanner. */
    public static final Set<String> VILLAGE_INDICATOR_BLOCKS = Set.of(
            "minecraft:bell",
            // Beds (all 16 colors)
            "minecraft:white_bed", "minecraft:orange_bed", "minecraft:magenta_bed",
            "minecraft:light_blue_bed", "minecraft:yellow_bed", "minecraft:lime_bed",
            "minecraft:pink_bed", "minecraft:gray_bed", "minecraft:light_gray_bed",
            "minecraft:cyan_bed", "minecraft:purple_bed", "minecraft:blue_bed",
            "minecraft:brown_bed", "minecraft:green_bed", "minecraft:red_bed",
            "minecraft:black_bed",
            // Job site blocks
            "minecraft:lectern", "minecraft:grindstone", "minecraft:smithing_table",
            "minecraft:fletching_table", "minecraft:cartography_table", "minecraft:loom",
            "minecraft:barrel", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:composter", "minecraft:stonecutter", "minecraft:brewing_stand",
            // Village infrastructure
            "minecraft:hay_block"
    );

    // ── Phase machine ────────────────────────────────────────────────

    private enum Phase {
        IDLE, EXPLORE, SCAN_VILLAGE, VERIFY_EDGES, REGISTER_BASE, DONE
    }

    // ── Configuration ────────────────────────────────────────────────

    private static final int EXPLORE_TIMEOUT_TICKS = 400;   // 20 sec per waypoint
    private static final int MAP_TIMEOUT_TICKS = 1200;      // 60 sec total mapping
    private static final int SCAN_POINT_DISTANCE = 40;      // blocks from center
    private static final int MIN_BASE_RADIUS = 32;
    private static final int RADIUS_BUFFER = 10;
    private static final int EDGE_VERIFY_DISTANCE = 12;  // blocks beyond detected edge

    // ── State ────────────────────────────────────────────────────────

    private Phase phase = Phase.IDLE;
    private final Random random = new Random();

    // Explore state
    private BlockPos exploreTarget;
    private int exploreTicks;

    // Village mapping state
    private BlockPos estimatedCenter;
    private final List<BlockPos> scanPoints = new ArrayList<>();
    private int currentScanPointIndex;
    private int mapTicks;
    private int navTicks;

    // Bounding box of all village blocks found
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private boolean boundingBoxInitialized;
    private int villageBlockCount;

    // Edge verification state
    private final List<BlockPos> edgeVerifyPoints = new ArrayList<>();
    private int currentEdgePointIndex;
    private int edgeNavTicks;
    private int prevBBMinX, prevBBMaxX, prevBBMinZ, prevBBMaxZ;

    // Injected dependencies
    private BaseRegistry baseRegistry;
    private WorldState worldStateRef;

    // ── Dependency injection ─────────────────────────────────────────

    public void setBaseRegistry(BaseRegistry registry) {
        this.baseRegistry = registry;
    }

    public void setWorldState(WorldState ws) {
        this.worldStateRef = ws;
    }

    // ── GoapAction interface ─────────────────────────────────────────

    @Override
    public String getName() {
        return "EstablishBase";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        if (!commandTriggered) return false;
        if (state.hasBase) return false;
        if (!state.dimension.contains("overworld")) return false;
        return true;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (!commandTriggered) return 0;
        if (state.hasBase) return 0;
        if (!state.dimension.contains("overworld")) return 0;
        float survivalPriority = goals.getGoalPriority("survive");

        // Check for village indicators from scanner
        boolean hasIndicators = state.villageIndicatorsNearby;

        // Also check for villager entities if we're actively exploring
        if (!hasIndicators && phase != Phase.IDLE) {
            hasIndicators = detectVillagers(Minecraft.getInstance());
        }

        if (hasIndicators || phase == Phase.SCAN_VILLAGE || phase == Phase.VERIFY_EDGES) {
            // Village found or actively mapping/verifying — high commitment
            return survivalPriority * 1.2f;
        }

        // No indicators — explore at moderate priority
        return survivalPriority * 0.35f;
    }

    @Override
    public void execute(Minecraft client) {
        if (phase == Phase.SCAN_VILLAGE || phase == Phase.VERIFY_EDGES || phase == Phase.REGISTER_BASE) {
            // Resuming mapping/verification — don't reset
            return;
        }
        resetState();
        phase = Phase.EXPLORE;
        startExploring(client);
        EmmaBridgeMod.LOGGER.info("[EstablishBase] Starting village search");
    }

    @Override
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        switch (phase) {
            case EXPLORE -> tickExplore(client, player);
            case SCAN_VILLAGE -> tickScanVillage(client, player);
            case VERIFY_EDGES -> tickVerifyEdges(client, player);
            case REGISTER_BASE -> tickRegisterBase();
            case DONE, IDLE -> {}
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        if (phase == Phase.EXPLORE) {
            GoapNavHelper.cancelPathing();
        }
        // Don't reset mapping state — allow resuming if we win the auction again
        if (phase != Phase.SCAN_VILLAGE && phase != Phase.VERIFY_EDGES && phase != Phase.REGISTER_BASE) {
            phase = Phase.IDLE;
            exploreTarget = null;
        }
    }

    @Override
    public boolean isActive() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    @Override
    public int getMinimumActiveTicks() {
        // Once mapping, commit for at least 5 seconds
        return (phase == Phase.SCAN_VILLAGE || phase == Phase.VERIFY_EDGES) ? 100 : 0;
    }

    @Override
    public String personalityCategory() {
        return "exploration";
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("phase", phase.name());
        bd.addProperty("village_indicators", state.villageIndicatorsNearby);
        bd.addProperty("village_blocks_found", villageBlockCount);
        if (estimatedCenter != null) {
            bd.addProperty("center", estimatedCenter.getX() + "," +
                    estimatedCenter.getY() + "," + estimatedCenter.getZ());
        }
        if (phase == Phase.SCAN_VILLAGE) {
            bd.addProperty("scan_point", currentScanPointIndex + "/" + scanPoints.size());
        }
        if (phase == Phase.VERIFY_EDGES) {
            bd.addProperty("edge_point", currentEdgePointIndex + "/" + edgeVerifyPoints.size());
        }
        return bd;
    }

    // ── Phase: EXPLORE ───────────────────────────────────────────────

    private void tickExplore(Minecraft client, LocalPlayer player) {
        exploreTicks++;

        // Check if village indicators appeared in scanner
        if (worldStateRef != null && worldStateRef.villageIndicatorsNearby) {
            EmmaBridgeMod.LOGGER.info("[EstablishBase] Village indicators detected, switching to mapping");
            GoapNavHelper.cancelPathing();
            transitionToScanVillage(player);
            return;
        }

        // Check for villager entities
        if (detectVillagers(client)) {
            EmmaBridgeMod.LOGGER.info("[EstablishBase] Villagers detected, switching to mapping");
            GoapNavHelper.cancelPathing();
            // Use player position as initial center estimate
            estimatedCenter = player.blockPosition();
            buildScanPoints();
            phase = Phase.SCAN_VILLAGE;
            mapTicks = 0;
            navTicks = 0;
            return;
        }

        // Navigate toward explore target
        switch (GoapNavHelper.tickNavigateToBlock(player, exploreTarget, exploreTicks)) {
            case ARRIVED, TIMEOUT -> {
                // Pick new direction
                exploreTarget = generateRandomTarget(player);
                GoapNavHelper.pathTo(exploreTarget);
                exploreTicks = 0;
            }
            case PATHING -> {} // still moving
            case NO_TARGET -> startExploring(client);
        }
    }

    private void startExploring(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;
        exploreTarget = generateRandomTarget(player);
        GoapNavHelper.pathTo(exploreTarget);
        exploreTicks = 0;
    }

    private BlockPos generateRandomTarget(LocalPlayer player) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = 50.0 + random.nextDouble() * 30.0;
        int targetX = (int) (player.getX() + distance * Math.cos(angle));
        int targetZ = (int) (player.getZ() + distance * Math.sin(angle));
        int targetY = (int) player.getY();
        return new BlockPos(targetX, targetY, targetZ);
    }

    // ── Phase: SCAN_VILLAGE ──────────────────────────────────────────

    private void transitionToScanVillage(LocalPlayer player) {
        // Estimate village center from detected indicators
        estimatedCenter = estimateVillageCenter(player);
        buildScanPoints();
        phase = Phase.SCAN_VILLAGE;
        mapTicks = 0;
        navTicks = 0;
        boundingBoxInitialized = false;
        villageBlockCount = 0;
        EmmaBridgeMod.LOGGER.info("[EstablishBase] Mapping village around ({}, {}, {})",
                estimatedCenter.getX(), estimatedCenter.getY(), estimatedCenter.getZ());
    }

    private void tickScanVillage(Minecraft client, LocalPlayer player) {
        mapTicks++;

        // Collect village blocks from current scanner results
        collectVillageBlocks();

        // Navigate through scan points
        if (currentScanPointIndex < scanPoints.size()) {
            BlockPos scanPoint = scanPoints.get(currentScanPointIndex);
            navTicks++;
            switch (GoapNavHelper.tickNavigateToBlock(player, scanPoint, navTicks, 300, 6.0)) {
                case ARRIVED -> {
                    currentScanPointIndex++;
                    navTicks = 0;
                    if (currentScanPointIndex < scanPoints.size()) {
                        GoapNavHelper.pathTo(scanPoints.get(currentScanPointIndex));
                    }
                }
                case TIMEOUT -> {
                    // Skip this scan point
                    EmmaBridgeMod.LOGGER.info("[EstablishBase] Scan point {} timed out, skipping",
                            currentScanPointIndex);
                    currentScanPointIndex++;
                    navTicks = 0;
                    if (currentScanPointIndex < scanPoints.size()) {
                        GoapNavHelper.pathTo(scanPoints.get(currentScanPointIndex));
                    }
                }
                case PATHING -> {} // still moving
                case NO_TARGET -> {
                    currentScanPointIndex++;
                    navTicks = 0;
                }
            }
        }

        // All scan points visited or timeout — verify edges before registering
        if (currentScanPointIndex >= scanPoints.size() || mapTicks > MAP_TIMEOUT_TICKS) {
            GoapNavHelper.cancelPathing();
            buildEdgeVerifyPoints();
            phase = Phase.VERIFY_EDGES;
            if (!edgeVerifyPoints.isEmpty()) {
                GoapNavHelper.pathTo(edgeVerifyPoints.get(0));
            }
        }
    }

    private void collectVillageBlocks() {
        if (worldStateRef == null) return;
        for (var entry : worldStateRef.villageBlocks.entrySet()) {
            for (BlockPos pos : entry.getValue()) {
                updateBoundingBox(pos);
                villageBlockCount++;
            }
        }
    }

    private void updateBoundingBox(BlockPos pos) {
        if (!boundingBoxInitialized) {
            minX = maxX = pos.getX();
            minY = maxY = pos.getY();
            minZ = maxZ = pos.getZ();
            boundingBoxInitialized = true;
        } else {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
    }

    // ── Phase: VERIFY_EDGES ─────────────────────────────────────

    /** Build 4 points just beyond each bounding box edge to check for missed blocks. */
    private void buildEdgeVerifyPoints() {
        edgeVerifyPoints.clear();
        currentEdgePointIndex = 0;
        edgeNavTicks = 0;

        if (!boundingBoxInitialized) return;

        // Snapshot current bounding box for comparison after verify
        prevBBMinX = minX;
        prevBBMaxX = maxX;
        prevBBMinZ = minZ;
        prevBBMaxZ = maxZ;

        int midX = (minX + maxX) / 2;
        int midZ = (minZ + maxZ) / 2;
        int y = (minY + maxY) / 2;

        // Walk beyond each edge to check for missed blocks
        edgeVerifyPoints.add(new BlockPos(maxX + EDGE_VERIFY_DISTANCE, y, midZ)); // East
        edgeVerifyPoints.add(new BlockPos(minX - EDGE_VERIFY_DISTANCE, y, midZ)); // West
        edgeVerifyPoints.add(new BlockPos(midX, y, maxZ + EDGE_VERIFY_DISTANCE)); // South
        edgeVerifyPoints.add(new BlockPos(midX, y, minZ - EDGE_VERIFY_DISTANCE)); // North

        EmmaBridgeMod.LOGGER.info("[EstablishBase] Verifying edges: BB ({},{}) to ({},{}), checking {} blocks beyond",
                minX, minZ, maxX, maxZ, EDGE_VERIFY_DISTANCE);
    }

    private void tickVerifyEdges(Minecraft client, LocalPlayer player) {
        collectVillageBlocks();

        if (currentEdgePointIndex < edgeVerifyPoints.size()) {
            BlockPos edgePoint = edgeVerifyPoints.get(currentEdgePointIndex);
            edgeNavTicks++;
            switch (GoapNavHelper.tickNavigateToBlock(player, edgePoint, edgeNavTicks, 200, 6.0)) {
                case ARRIVED, TIMEOUT -> {
                    currentEdgePointIndex++;
                    edgeNavTicks = 0;
                    if (currentEdgePointIndex < edgeVerifyPoints.size()) {
                        GoapNavHelper.pathTo(edgeVerifyPoints.get(currentEdgePointIndex));
                    }
                }
                case PATHING -> {}
                case NO_TARGET -> {
                    currentEdgePointIndex++;
                    edgeNavTicks = 0;
                }
            }
        }

        if (currentEdgePointIndex >= edgeVerifyPoints.size()) {
            GoapNavHelper.cancelPathing();
            boolean expanded = minX < prevBBMinX || maxX > prevBBMaxX
                            || minZ < prevBBMinZ || maxZ > prevBBMaxZ;
            if (expanded) {
                EmmaBridgeMod.LOGGER.info(
                        "[EstablishBase] BB expanded during verify: ({},{}) to ({},{}), running another pass",
                        minX, minZ, maxX, maxZ);
                buildEdgeVerifyPoints();
                if (!edgeVerifyPoints.isEmpty()) {
                    GoapNavHelper.pathTo(edgeVerifyPoints.get(0));
                }
            } else {
                EmmaBridgeMod.LOGGER.info("[EstablishBase] Edges confirmed, registering base");
                phase = Phase.REGISTER_BASE;
            }
        }
    }

    // ── Phase: REGISTER_BASE ─────────────────────────────────────────

    private void tickRegisterBase() {
        if (baseRegistry == null) {
            EmmaBridgeMod.LOGGER.warn("[EstablishBase] No BaseRegistry — cannot register base");
            phase = Phase.DONE;
            return;
        }

        int centerX, centerY, centerZ, radius;

        if (boundingBoxInitialized) {
            centerX = (minX + maxX) / 2;
            centerY = (minY + maxY) / 2;
            centerZ = (minZ + maxZ) / 2;
            int extentX = (maxX - minX) / 2;
            int extentZ = (maxZ - minZ) / 2;
            radius = Math.max(MIN_BASE_RADIUS,
                    (int) Math.sqrt(extentX * extentX + extentZ * extentZ) + RADIUS_BUFFER);
        } else if (estimatedCenter != null) {
            // No bounding box data — use estimated center with default radius
            centerX = estimatedCenter.getX();
            centerY = estimatedCenter.getY();
            centerZ = estimatedCenter.getZ();
            radius = MIN_BASE_RADIUS;
        } else {
            EmmaBridgeMod.LOGGER.warn("[EstablishBase] No village data to register");
            phase = Phase.DONE;
            return;
        }

        baseRegistry.addBase("village", "minecraft:overworld",
                centerX, centerY, centerZ, radius);

        EmmaBridgeMod.LOGGER.info(
                "[EstablishBase] Registered village base at ({},{},{}) radius={} ({} blocks mapped)",
                centerX, centerY, centerZ, radius, villageBlockCount);

        commandTriggered = false;  // reset — requires @base establish again
        phase = Phase.DONE;
    }

    // ── Village detection helpers ────────────────────────────────────

    /**
     * Estimate village center from detected indicator blocks.
     * Prefers bell position; falls back to centroid of beds/job sites.
     */
    private BlockPos estimateVillageCenter(LocalPlayer player) {
        if (worldStateRef == null) return player.blockPosition();

        // Prefer bell as center
        List<BlockPos> bells = worldStateRef.villageBlocks.get("minecraft:bell");
        if (bells != null && !bells.isEmpty()) {
            return bells.get(0);
        }

        // Centroid of all village indicator blocks
        int count = 0;
        long sumX = 0, sumY = 0, sumZ = 0;
        for (var entry : worldStateRef.villageBlocks.entrySet()) {
            for (BlockPos pos : entry.getValue()) {
                sumX += pos.getX();
                sumY += pos.getY();
                sumZ += pos.getZ();
                count++;
            }
        }

        if (count > 0) {
            return new BlockPos((int) (sumX / count), (int) (sumY / count), (int) (sumZ / count));
        }

        return player.blockPosition();
    }

    /** Build 4 compass scan points around the estimated village center. */
    private void buildScanPoints() {
        scanPoints.clear();
        currentScanPointIndex = 0;
        if (estimatedCenter == null) return;

        int cx = estimatedCenter.getX();
        int cy = estimatedCenter.getY();
        int cz = estimatedCenter.getZ();

        // Visit center first, then N/E/S/W at SCAN_POINT_DISTANCE
        scanPoints.add(estimatedCenter);
        scanPoints.add(new BlockPos(cx, cy, cz - SCAN_POINT_DISTANCE)); // North
        scanPoints.add(new BlockPos(cx + SCAN_POINT_DISTANCE, cy, cz)); // East
        scanPoints.add(new BlockPos(cx, cy, cz + SCAN_POINT_DISTANCE)); // South
        scanPoints.add(new BlockPos(cx - SCAN_POINT_DISTANCE, cy, cz)); // West
    }

    /**
     * Detect villager entities within 48 blocks of the player.
     * Villagers wander and may be found before block scanning detects beds/bells.
     */
    private boolean detectVillagers(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return false;

        AABB scanBox = player.getBoundingBox().inflate(48);
        for (Entity entity : player.level().getEntities(player, scanBox)) {
            if (entity instanceof Villager) {
                return true;
            }
        }
        return false;
    }

    // ── State management ─────────────────────────────────────────────

    private void resetState() {
        exploreTarget = null;
        exploreTicks = 0;
        estimatedCenter = null;
        scanPoints.clear();
        currentScanPointIndex = 0;
        mapTicks = 0;
        navTicks = 0;
        minX = minY = minZ = maxX = maxY = maxZ = 0;
        boundingBoxInitialized = false;
        villageBlockCount = 0;
        edgeVerifyPoints.clear();
        currentEdgePointIndex = 0;
        edgeNavTicks = 0;
    }
}
