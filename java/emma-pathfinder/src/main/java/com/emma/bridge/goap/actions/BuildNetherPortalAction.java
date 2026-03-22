package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.PortalRegistry;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * GOAP Action: Build a nether portal from obsidian.
 *
 * Places 10 obsidian blocks in the standard 4x5 portal frame (corners omitted),
 * then ignites with flint and steel. Site selection finds flat ground nearby.
 *
 * Scores on "build_nether_portal" goal type. Returns 0 if a nether portal
 * already exists in the PortalRegistry.
 *
 * Frame layout (relative to origin = bottom-left interior block):
 *       . O O .     Y=3  (top beam)
 *       O . . O     Y=2  (columns)
 *       O . . O     Y=1
 *       O . . O     Y=0
 *       . O O .     Y=-1 (bottom beam)
 *       Z:-1 0 1 2
 *
 * 10 blocks total. Interior: Z=0..1, Y=0..2 (2 wide x 3 tall).
 */
public class BuildNetherPortalAction extends GoapAction {

    private enum Phase {
        FIND_SITE, NAVIGATE, PLACE_FRAME, IGNITE, DONE
    }

    /** Portal frame offsets relative to the "origin" (bottom-left interior block).
     *  Placed bottom-up, left-right for stable placement order. */
    private static final int[][] FRAME_OFFSETS = {
            // Bottom beam (Y=-1)
            {0, -1, 0}, {0, -1, 1},
            // Left column (Z=-1)
            {0, 0, -1}, {0, 1, -1}, {0, 2, -1},
            // Right column (Z=+2)
            {0, 0, 2}, {0, 1, 2}, {0, 2, 2},
            // Top beam (Y=3)
            {0, 3, 0}, {0, 3, 1},
    };

    private static final int OBSIDIAN_NEEDED = 10;

    private Phase phase = Phase.FIND_SITE;
    private String targetGoalId;
    private BlockPos origin;       // bottom-left interior block
    private int frameIndex;        // which frame block we're placing next
    private int navWaitTicks;
    private int placeWaitTicks;    // ticks waiting at current frame block

    private PortalRegistry portalRegistry;

    public void setPortalRegistry(PortalRegistry registry) {
        this.portalRegistry = registry;
    }

    @Override
    public String getName() {
        return "BuildNetherPortal";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return state.hasItem("minecraft:obsidian", OBSIDIAN_NEEDED)
                && state.hasItem("minecraft:flint_and_steel", 1);
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        // Don't build if we already have a nether portal
        if (portalRegistry != null && portalRegistry.hasPortalAccess(state.dimension, "nether_portal")) {
            return 0;
        }

        for (GoalSet.Goal goal : goals.getGoals()) {
            if ("build_nether_portal".equals(goal.type)) {
                targetGoalId = goal.id;
                return goal.priority * 0.9f;
            }
        }
        return 0;
    }

    @Override
    public void execute(Minecraft client) {
        phase = Phase.FIND_SITE;
        origin = null;
        frameIndex = 0;
        navWaitTicks = 0;
        placeWaitTicks = 0;
        EmmaBridgeMod.LOGGER.info("BuildNetherPortal: starting");
    }

    @Override
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;
        Level world = client.level;
        if (world == null) return;

        switch (phase) {
            case FIND_SITE -> tickFindSite(player, world);
            case NAVIGATE -> tickNavigate(player);
            case PLACE_FRAME -> tickPlaceFrame(player, world);
            case IGNITE -> tickIgnite(player, world);
            case DONE -> {} // score drops, another action takes over
        }
    }

    // ── Phase: Find a suitable build site ─────────────────────────

    private void tickFindSite(LocalPlayer player, Level world) {
        BlockPos playerPos = player.blockPosition();

        // Search outward for a flat 4-wide, 5-tall clear area on solid ground
        for (int r = 2; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx += r * 2) {
                for (int dz = -r; dz <= r; dz += r * 2) {
                    BlockPos candidate = playerPos.offset(dx, 0, dz);
                    // Find ground level
                    candidate = findGround(world, candidate);
                    if (candidate == null) continue;

                    if (isValidSite(world, candidate)) {
                        origin = candidate;
                        phase = Phase.NAVIGATE;
                        navWaitTicks = 0;
                        EmmaBridgeMod.LOGGER.info("BuildNetherPortal: site found at {}", origin);
                        return;
                    }
                }
            }
        }

        // Fallback: build right where we are
        origin = findGround(world, playerPos);
        if (origin == null) {
            origin = playerPos;
        }
        phase = Phase.NAVIGATE;
        navWaitTicks = 0;
    }

    private BlockPos findGround(Level world, BlockPos pos) {
        // Search up/down for solid ground with air above
        for (int dy = -5; dy <= 5; dy++) {
            BlockPos check = pos.offset(0, dy, 0);
            BlockState below = world.getBlockState(check.below());
            BlockState at = world.getBlockState(check);
            if (below.isSolid() && at.isAir()) {
                return check;
            }
        }
        return null;
    }

    private boolean isValidSite(Level world, BlockPos ground) {
        // Check a 4-wide (Z: -1 to +2) x 5-tall (Y: -1 to +3) region
        // Ground block is at Y=0 in frame coordinates
        for (int[] offset : FRAME_OFFSETS) {
            BlockPos framePos = ground.offset(offset[0], offset[1], offset[2]);
            // Frame blocks need to be placeable (air, water, or replaceable)
            BlockState state = world.getBlockState(framePos);
            if (state.isSolid() && !state.is(Blocks.OBSIDIAN)) return false;
        }
        // Interior must be clearable
        for (int y = 0; y <= 2; y++) {
            for (int z = 0; z <= 1; z++) {
                BlockPos interior = ground.offset(0, y, z);
                BlockState state = world.getBlockState(interior);
                if (state.isSolid()) return false;
            }
        }
        // Need solid ground below the bottom beam
        BlockState belowLeft = world.getBlockState(ground.offset(0, -2, 0));
        BlockState belowRight = world.getBlockState(ground.offset(0, -2, 1));
        return belowLeft.isSolid() || belowRight.isSolid();
    }

    // ── Phase: Navigate to build site ─────────────────────────────

    private void tickNavigate(LocalPlayer player) {
        navWaitTicks++;
        GoapNavHelper.NavResult result = GoapNavHelper.tickNavigateToBlock(
                player, origin, navWaitTicks, 300, 4.0);

        switch (result) {
            case ARRIVED -> {
                phase = Phase.PLACE_FRAME;
                frameIndex = 0;
                placeWaitTicks = 0;
                EmmaBridgeMod.LOGGER.info("BuildNetherPortal: arrived at site, placing frame");
            }
            case TIMEOUT -> {
                // Try building here anyway
                phase = Phase.PLACE_FRAME;
                frameIndex = 0;
                placeWaitTicks = 0;
            }
            case NO_TARGET -> phase = Phase.FIND_SITE;
            case PATHING -> {} // wait
        }
    }

    // ── Phase: Place obsidian frame blocks ────────────────────────

    private void tickPlaceFrame(LocalPlayer player, Level world) {
        if (frameIndex >= FRAME_OFFSETS.length) {
            phase = Phase.IGNITE;
            placeWaitTicks = 0;
            EmmaBridgeMod.LOGGER.info("BuildNetherPortal: frame complete, igniting");
            return;
        }

        int[] offset = FRAME_OFFSETS[frameIndex];
        BlockPos framePos = origin.offset(offset[0], offset[1], offset[2]);

        // Already obsidian? Skip.
        if (world.getBlockState(framePos).is(Blocks.OBSIDIAN)) {
            frameIndex++;
            placeWaitTicks = 0;
            return;
        }

        placeWaitTicks++;

        // Navigate within reach
        if (!BlockInteraction.isInReach(framePos)) {
            GoapNavHelper.tickNavigateToBlock(player, framePos, placeWaitTicks, 100, 3.0);
            return;
        }

        // Cancel any pathing — we're close enough
        if (GoapNavHelper.isPathing()) {
            GoapNavHelper.cancelPathing();
        }

        // Equip obsidian and place directly at target position
        if (!BlockInteraction.forceEquipItem(Items.OBSIDIAN)) return;

        BlockInteraction.rightClickBlock(framePos, Direction.UP);

        // Wait a tick then check if placement succeeded
        if (placeWaitTicks > 3 && world.getBlockState(framePos).is(Blocks.OBSIDIAN)) {
            frameIndex++;
            placeWaitTicks = 0;
        } else if (placeWaitTicks > 20) {
            // Try next block — this one might be obstructed
            frameIndex++;
            placeWaitTicks = 0;
        }
    }

    // ── Phase: Ignite the portal ──────────────────────────────────

    private void tickIgnite(LocalPlayer player, Level world) {
        placeWaitTicks++;

        // Ignite on the bottom interior block (origin at Y=0, Z=0)
        BlockPos ignitePos = origin.offset(0, 0, 0);

        if (!BlockInteraction.isInReach(ignitePos)) {
            GoapNavHelper.tickNavigateToBlock(player, ignitePos, placeWaitTicks, 100, 3.0);
            return;
        }

        if (GoapNavHelper.isPathing()) {
            GoapNavHelper.cancelPathing();
        }

        // Check if portal is already lit
        if (world.getBlockState(ignitePos).is(Blocks.NETHER_PORTAL)) {
            phase = Phase.DONE;
            EmmaBridgeMod.LOGGER.info("BuildNetherPortal: portal already lit!");
            return;
        }

        if (!BlockInteraction.forceEquipItem(Items.FLINT_AND_STEEL)) return;

        // Right-click the bottom frame block to ignite
        BlockPos bottomFrame = origin.offset(0, -1, 0);
        BlockInteraction.rightClickBlock(bottomFrame, Direction.UP);

        if (placeWaitTicks > 5 && world.getBlockState(ignitePos).is(Blocks.NETHER_PORTAL)) {
            phase = Phase.DONE;
            EmmaBridgeMod.LOGGER.info("BuildNetherPortal: portal ignited successfully!");
        } else if (placeWaitTicks > 40) {
            // Failed to ignite — frame may be incomplete, retry placement
            phase = Phase.PLACE_FRAME;
            frameIndex = 0;
            placeWaitTicks = 0;
            EmmaBridgeMod.LOGGER.warn("BuildNetherPortal: ignition failed, retrying frame");
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        if (phase != Phase.DONE) {
            GoapNavHelper.cancelPathing();
        }
        phase = Phase.FIND_SITE;
        targetGoalId = null;
    }

    @Override
    public boolean isActive() {
        return phase != Phase.DONE && phase != Phase.FIND_SITE;
    }

    @Override
    public int getMinimumActiveTicks() {
        return 20; // Don't interrupt mid-placement
    }

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public String personalityCategory() {
        return "exploration";
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("phase", phase.name());
        bd.addProperty("frame_index", frameIndex);
        if (origin != null) {
            bd.addProperty("origin", origin.toShortString());
        }
        return bd;
    }
}
