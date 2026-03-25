package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.InventoryScanner;
import com.google.gson.JsonObject;
import emmatone.api.utils.VecUtils;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * GOAP Action: Sleep through the night in a bed.
 *
 * Ported from PlaceBedAndSetSpawnTask (Phase 57) — all the tuned logic:
 *   - Overworld only, above-ground placement via heightmap
 *   - Quick yaw-based placement (no look gate needed)
 *   - Expanding ring search: local r=2, then heightmap rings r=4,8,14,22
 *   - Player overlap avoidance (bed can't overlap player feet/head)
 *   - Reuses existing nearby beds
 *   - No look direction required for interactBlock (private server)
 *
 * Scores against the "survive" goal when it's nighttime in the overworld.
 */
public class SleepAction extends GoapAction {

    // ── State machine ────────────────────────────────────────────
    private enum Phase {
        IDLE, FIND_BED, NAVIGATE_TO_BED, EQUIP_BED, PLACE_BED, WAIT_AFTER_PLACE,
        INTERACT_BED, SLEEPING, BREAK_BED, COLLECT_BED, DONE
    }

    private Phase phase = Phase.IDLE;
    private boolean active = false;
    private String targetGoalId = null;

    // Bed tracking
    private BlockPos bedPos = null;           // existing bed to use, or placed bed
    private BlockPos spotBase = null;         // ground block to place bed on
    private Direction bedFacing = null;       // direction bed faces
    private boolean placedByUs = false;       // did we place this bed?

    // Navigation
    private BlockPos navigateTarget = null;   // heightmap spot to walk to
    private int waitTicks = 0;
    private int navTimeout = 0;

    // Retry tracking
    private int retryCooldown = 0;
    private int placementFailures = 0;

    // Break/collect tracking
    private int breakTicks = 0;
    private int collectTicks = 0;

    // Scan radii
    private static final int LOCAL_SCAN_RADIUS = 2;
    private static final int[] WIDE_RADII = {4, 8, 14, 22};

    // Bed items (all colors)
    private static final Item[] BED_ITEMS = {
            Items.WHITE_BED, Items.ORANGE_BED, Items.MAGENTA_BED, Items.LIGHT_BLUE_BED,
            Items.YELLOW_BED, Items.LIME_BED, Items.PINK_BED, Items.GRAY_BED,
            Items.LIGHT_GRAY_BED, Items.CYAN_BED, Items.PURPLE_BED, Items.BLUE_BED,
            Items.BROWN_BED, Items.GREEN_BED, Items.RED_BED, Items.BLACK_BED
    };

    @Override
    public String getName() {
        return "Sleep";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        // Only in overworld
        return state.dimension.contains("overworld");
    }

    // ── Scoring ──────────────────────────────────────────────────

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        // Only in overworld
        if (!state.dimension.contains("overworld")) return 0;

        // Skip if skyLight == 0 — deep underground / enclosed cave.
        // Houses with windows pass skylight through glass. Fully enclosed houses
        // still benefit from sleeping (sets spawn, clears mobs outside).
        // Sky light is a fixed property of the position — doesn't change with time of day.
        if (state.skyLight == 0) return 0;

        // Thunderstorm override — can sleep anytime during thunder+rain
        boolean canSleepNow = (state.isThundering && state.isRaining)
                || (state.timeOfDay >= 12542 && state.timeOfDay <= 23992);
        if (!canSleepNow) return 0;

        // Need a bed in inventory OR one nearby
        boolean hasBed = hasBedInInventory(state);
        boolean nearbyBed = hasNearbyBed(state);
        if (!hasBed && !nearbyBed) return 0;

        // Score against survive goal — sleeping prevents mob spawns
        float survivalPriority = goals.getGoalPriority("survive");
        float score = survivalPriority * 0.6f;  // moderate priority, below active threats

        // Boost if very dark (mobs spawning) — combined light for mob-danger urgency
        if (state.lightLevel < 4) score *= 1.3f;

        targetGoalId = "survive";
        return score;
    }

    // ── Execution ────────────────────────────────────────────────

    @Override
    public void execute(Minecraft client) {
        active = true;
        waitTicks = 0;
        navTimeout = 0;
        retryCooldown = 0;
        placementFailures = 0;
        navigateTarget = null;
        placedByUs = false;
        breakTicks = 0;
        collectTicks = 0;
        phase = Phase.FIND_BED;

        EmmaBridgeMod.LOGGER.info("[GOAP Sleep] Starting sleep action");
    }

    @Override
    public void tick(Minecraft client) {
        if (!active || phase == Phase.IDLE) return;
        LocalPlayer player = client.player;
        if (player == null) return;

        switch (phase) {
            case FIND_BED -> tickFindBed(client, player);
            case NAVIGATE_TO_BED -> tickNavigate(client, player);
            case EQUIP_BED -> tickEquipBed(client, player);
            case PLACE_BED -> tickPlaceBed(client, player);
            case WAIT_AFTER_PLACE -> tickWaitAfterPlace(client, player);
            case INTERACT_BED -> tickInteractBed(client, player);
            case SLEEPING -> tickSleeping(client, player);
            case BREAK_BED -> tickBreakBed(client, player);
            case COLLECT_BED -> tickCollectBed(client, player);
            case DONE -> {
                active = false;
                phase = Phase.IDLE;
            }
            default -> {}
        }
    }

    // ── Phase handlers ───────────────────────────────────────────

    private void tickFindBed(Minecraft client, LocalPlayer player) {
        // Check if already sleeping
        if (player.isSleeping() || client.screen instanceof InBedChatScreen) {
            phase = Phase.SLEEPING;
            return;
        }

        // Retry cooldown
        if (retryCooldown > 0) {
            retryCooldown--;
            return;
        }

        // Check for existing bed nearby (within 20 blocks)
        BlockPos existing = findNearbyPlacedBed(client, player, 20);
        if (existing != null) {
            bedPos = existing;
            placedByUs = false;
            double dist = VecUtils.entityDistanceToCenter(player, bedPos);
            if (dist < 4.0) {
                phase = Phase.INTERACT_BED;
            } else {
                phase = Phase.NAVIGATE_TO_BED;
                navTimeout = 0;
            }
            return;
        }

        // Need a bed in inventory — try extracting from endinv if not in player inv
        if (!hasBedInHotbarOrInventory(player)) {
            boolean extracted = false;
            for (Item bed : BED_ITEMS) {
                if (com.emma.bridge.util.EndinvBridge.extractToSlot(bed,
                        player.getInventory().getSelectedSlot())) {
                    EmmaBridgeMod.LOGGER.info("[GOAP Sleep] Extracted bed from endinv");
                    extracted = true;
                    break;
                }
            }
            if (!extracted) {
                EmmaBridgeMod.LOGGER.info("[GOAP Sleep] No bed available");
                active = false;
                phase = Phase.IDLE;
                return;
            }
        }

        // In water — can't place
        if (player.isInWater()) return;

        // Try local scan for bed placement spot
        BedPlacement spot = placementFailures < 2 ? findBedSpotLocal(client, player) : null;
        if (spot != null) {
            navigateTarget = null;
            spotBase = spot.base;
            bedFacing = spot.facing;
            phase = Phase.EQUIP_BED;
            return;
        }

        // Local scan failed — use heightmap expanding rings
        if (navigateTarget == null) {
            navigateTarget = findBedSpotWide(client, player);
        }
        if (navigateTarget != null) {
            if (player.blockPosition().closerThan(navigateTarget, 3)) {
                navigateTarget = null;
                placementFailures = 0;
                return; // retry local scan next tick
            }
            // Navigate to adjacent block so player doesn't stand on placement spot
            BlockPos standPos = pickAdjacentStandPos(player, navigateTarget);
            bedPos = standPos != null ? standPos : navigateTarget;
            phase = Phase.NAVIGATE_TO_BED;
            navTimeout = 0;
            return;
        }

        // Nothing found anywhere
        EmmaBridgeMod.LOGGER.info("[GOAP Sleep] No valid bed placement spot found");
        active = false;
        phase = Phase.IDLE;
    }

    private void tickNavigate(Minecraft client, LocalPlayer player) {
        BlockPos target = bedPos != null ? bedPos : navigateTarget;

        switch (GoapNavHelper.tickNavigateToBlock(player, target, ++navTimeout, 200, 1.5)) {
            case NO_TARGET -> phase = Phase.FIND_BED;
            case ARRIVED -> {
                if (navigateTarget != null) {
                    // Arrived at heightmap spot — go back to FIND_BED for local scan
                    navigateTarget = null;
                    placementFailures = 0;
                    phase = Phase.FIND_BED;
                } else {
                    // Arrived at existing bed
                    phase = Phase.INTERACT_BED;
                }
            }
            case TIMEOUT -> {
                EmmaBridgeMod.LOGGER.warn("[GOAP Sleep] Navigation timeout");
                active = false;
                phase = Phase.IDLE;
            }
            case PATHING -> {}
        }
    }

    /** Equip bed — place happens after 2-tick delay. */
    private void tickEquipBed(Minecraft client, LocalPlayer player) {
        if (!equipBed(player)) return;
        waitTicks = 0;
        phase = Phase.PLACE_BED;
    }

    private void tickPlaceBed(Minecraft client, LocalPlayer player) {
        // Wait 2 ticks for server to sync held item
        if (++waitTicks < 2) return;

        // Place bed: target the foot position from above, with yaw-based facing
        InteractionResult result = BlockInteraction.rightClickBlock(
                spotBase.above(), Direction.DOWN, bedFacing);

        if (result != null && result.consumesAction()) {
            bedPos = spotBase.above();
            placedByUs = true;
            waitTicks = 0;
            placementFailures = 0;
            phase = Phase.WAIT_AFTER_PLACE;
            EmmaBridgeMod.LOGGER.info("[GOAP Sleep] Bed placed at {} facing {}", bedPos, bedFacing);
        } else {
            placementFailures++;
            retryCooldown = 40;
            phase = Phase.FIND_BED;
            EmmaBridgeMod.LOGGER.info("[GOAP Sleep] Placement failed #{}", placementFailures);
        }
    }

    private void tickWaitAfterPlace(Minecraft client, LocalPlayer player) {
        if (++waitTicks >= 5) {
            phase = Phase.INTERACT_BED;
        }
    }

    private void tickInteractBed(Minecraft client, LocalPlayer player) {
        if (bedPos == null) {
            phase = Phase.FIND_BED;
            return;
        }

        // Right-click the bed to sleep — no look direction needed
        BlockHitResult hitResult = BlockInteraction.createHitResult(bedPos, Direction.UP);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);

        EmmaBridgeMod.LOGGER.info("[GOAP Sleep] Interacted with bed at {}", bedPos);
        waitTicks = 0;
        phase = Phase.SLEEPING;
    }

    private void tickSleeping(Minecraft client, LocalPlayer player) {
        if (player.isSleeping() || client.screen instanceof InBedChatScreen) {
            // Still sleeping — wait
            return;
        }

        // Woke up — check if it's day
        long time = client.level.getDefaultClockTime() % 24000;
        if (time < 13000) {
            EmmaBridgeMod.LOGGER.info("[GOAP Sleep] Woke up, it's daytime");
            if (placedByUs && bedPos != null) {
                breakTicks = 0;
                phase = Phase.BREAK_BED;
            } else {
                phase = Phase.DONE;
            }
        } else {
            // Still night — something went wrong (monster nearby?), try again
            if (++waitTicks > 40) {
                phase = Phase.FIND_BED;
                waitTicks = 0;
            }
        }
    }

    // ── Break & collect bed ─────────────────────────────────────

    private void tickBreakBed(Minecraft client, LocalPlayer player) {
        if (bedPos == null) {
            phase = Phase.DONE;
            return;
        }

        // Block already gone → collect
        BlockState state = client.level.getBlockState(bedPos);
        if (!(state.getBlock() instanceof BedBlock)) {
            collectTicks = 0;
            phase = Phase.COLLECT_BED;
            return;
        }

        // First tick: kick off Emmatone mine process for 1 bed block
        if (breakTicks == 0) {
            String bedBlockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            GoapNavHelper.mineProcess().mineByName(1, bedBlockName);
            EmmaBridgeMod.LOGGER.info("[GOAP Sleep] Mining placed bed at {}", bedPos);
        }
        breakTicks++;

        // Poll: mine process finished?
        if (!GoapNavHelper.mineProcess().isActive()) {
            collectTicks = 0;
            phase = Phase.COLLECT_BED;
        }
    }

    private void tickCollectBed(Minecraft client, LocalPlayer player) {
        collectTicks++;

        // Check if bed is back in inventory (pickup may go to endinv)
        if (hasBedInHotbarOrInventory(player) || hasBedInEndinv()) {
            phase = Phase.DONE;
            return;
        }

        // Walk toward the drop
        if (collectTicks == 1 && !GoapNavHelper.isPathing()) {
            GoapNavHelper.pathTo(bedPos);
        }

        // Silent fail — move on
        if (collectTicks > 60) {
            GoapNavHelper.cancelPathing();
            phase = Phase.DONE;
        }
    }

    // ── Bed spot finding ─────────────────────────────────────────

    private record BedPlacement(BlockPos base, Direction facing) {}

    /**
     * Find a valid bed placement spot near the player (r=2).
     * Above-ground only (heightmap check), no overlap with player position.
     */
    private BedPlacement findBedSpotLocal(Minecraft client, LocalPlayer player) {
        BlockPos playerPos = player.blockPosition();
        BlockPos below = playerPos.below();

        BedPlacement best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -LOCAL_SCAN_RADIUS; dx <= LOCAL_SCAN_RADIUS; dx++) {
            for (int dz = -LOCAL_SCAN_RADIUS; dz <= LOCAL_SCAN_RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = below.offset(dx, dy, dz);
                    BedPlacement p = validatePlacement(client, candidate, playerPos);
                    if (p == null) continue;
                    double d = candidate.distSqr(playerPos);
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Expanding-ring heightmap search for surface bed spots beyond local radius.
     * Only returns above-ground positions (heightmap ensures this).
     */
    private BlockPos findBedSpotWide(Minecraft client, LocalPlayer player) {
        if (client.level == null) return null;
        BlockPos playerPos = player.blockPosition();
        int px = playerPos.getX(), pz = playerPos.getZ();

        int prevRadius = LOCAL_SCAN_RADIUS;
        for (int radius : WIDE_RADII) {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;

            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.abs(dx) <= prevRadius && Math.abs(dz) <= prevRadius) continue;

                    int x = px + dx;
                    int z = pz + dz;
                    int y = client.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;

                    if (y < client.level.getMinY() || y < playerPos.getY() - 20) continue;

                    double dist = dx * dx + dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
            if (best != null) {
                EmmaBridgeMod.LOGGER.info("[GOAP Sleep] Heightmap found spot at {} (ring r={})", best, radius);
                return best;
            }
            prevRadius = radius;
        }
        return null;
    }

    /**
     * Validate a bed placement at pos. Checks:
     *   - Foot (pos.above()) is replaceable
     *   - Foot doesn't overlap player
     *   - Headroom above foot (player stands up from bed)
     *   - At least one cardinal direction has replaceable head block
     *   - Head doesn't overlap player
     */
    private BedPlacement validatePlacement(Minecraft client, BlockPos pos, BlockPos playerPos) {
        ClientLevel world = client.level;
        if (world == null) return null;

        BlockPos foot = pos.above();

        if (!world.getBlockState(foot).canBeReplaced()) return null;
        if (foot.equals(playerPos) || foot.equals(playerPos.above())) return null;
        if (world.getBlockState(foot.above()).isSolidRender()) return null;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos head = foot.relative(dir);
            if (head.equals(playerPos) || head.equals(playerPos.above())) continue;
            if (world.getBlockState(head).canBeReplaced()) {
                return new BedPlacement(pos, dir);
            }
        }
        return null;
    }

    /** Pick the cardinal-adjacent block closest to the player to stand on,
     *  so we don't overlap the bed placement spot. */
    private BlockPos pickAdjacentStandPos(LocalPlayer player, BlockPos target) {
        BlockPos playerPos = player.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos adj = target.relative(dir);
            double dist = adj.distSqr(playerPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = adj;
            }
        }
        return best;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private boolean hasBedInInventory(WorldState state) {
        for (Item bed : BED_ITEMS) {
            String id = BuiltInRegistries.ITEM.getKey(bed).toString();
            if (state.playerInventory.containsKey(id)) return true;
            if (state.endinvInventory.containsKey(id)) return true;
        }
        return false;
    }

    private boolean hasBedInEndinv() {
        for (Item bed : BED_ITEMS) {
            String id = BuiltInRegistries.ITEM.getKey(bed).toString();
            if (com.emma.bridge.util.EndinvBridge.getCount(id) > 0) return true;
        }
        return false;
    }

    private boolean hasNearbyBed(WorldState state) {
        // Check nearbyBlocks for any bed block
        for (String key : state.nearbyBlocks.keySet()) {
            if (key.contains("_bed")) return true;
        }
        return false;
    }

    private static boolean isBedItem(ItemStack stack) {
        for (Item bed : BED_ITEMS) {
            if (stack.is(bed)) return true;
        }
        return false;
    }

    private boolean hasBedInHotbarOrInventory(LocalPlayer player) {
        return InventoryScanner.findSlot(player.getInventory(), SleepAction::isBedItem) >= 0;
    }

    private boolean equipBed(LocalPlayer player) {
        // Check hotbar first (slots 0-8)
        for (var ss : InventoryScanner.findAll(player.getInventory(), SleepAction::isBedItem)) {
            if (ss.slot() < 9) {
                player.getInventory().setSelectedSlot(ss.slot());
                return true;
            }
        }
        // Bed in main inventory — use BlockInteraction to equip
        int slot = InventoryScanner.findSlot(player.getInventory(), SleepAction::isBedItem);
        if (slot >= 0) {
            BlockInteraction.forceEquipItem(player.getInventory().getItem(slot).getItem());
            return true;
        }
        return false;
    }

    private BlockPos findNearbyPlacedBed(Minecraft client, LocalPlayer player, int range) {
        if (client.level == null) return null;
        BlockPos center = player.blockPosition();

        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    BlockState state = client.level.getBlockState(check);
                    if (state.getBlock() instanceof BedBlock) {
                        // Prefer head part for interaction
                        if (state.hasProperty(BedBlock.PART) && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                            double dist = check.distSqr(center);
                            if (dist < closestDist) {
                                closestDist = dist;
                                closest = check;
                            }
                        }
                    }
                }
            }
        }
        return closest;
    }

    // ── Lifecycle ────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        if (phase == Phase.BREAK_BED) {
            GoapNavHelper.mineProcess().cancel();
        }
        if (phase == Phase.NAVIGATE_TO_BED || phase == Phase.COLLECT_BED) {
            GoapNavHelper.cancelPathing();
        }
        active = false;
        phase = Phase.IDLE;
        bedPos = null;
        spotBase = null;
        bedFacing = null;
        navigateTarget = null;
        placedByUs = false;
        breakTicks = 0;
        collectTicks = 0;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        return 0.0f; // sleep isn't a prerequisite for anything
    }

    @Override
    public String personalityCategory() {
        return "safety";
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("active", active);
        bd.addProperty("phase", phase.name());
        bd.addProperty("bed_pos", bedPos != null ? bedPos.toShortString() : "none");
        bd.addProperty("time_of_day", state.timeOfDay);
        bd.addProperty("sky_light", state.skyLight);
        bd.addProperty("dimension", state.dimension);
        bd.addProperty("has_bed_inventory", hasBedInInventory(state));
        bd.addProperty("has_bed_nearby", hasNearbyBed(state));
        bd.addProperty("is_night", state.timeOfDay >= 12542 && state.timeOfDay <= 23992);
        bd.addProperty("is_thunderstorm", state.isThundering && state.isRaining);
        bd.addProperty("placement_failures", placementFailures);
        return bd;
    }
}
