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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * GOAP Action: Activate the end portal by placing eyes of ender in frames.
 *
 * Navigates to the stronghold, finds end_portal_frame blocks, and places
 * eyes of ender in each empty frame. The portal activates when all 12 frames
 * have eyes.
 *
 * Scores on "activate_end_portal" goal type. Returns 0 if end portal already active.
 */
public class ActivateEndPortalAction extends GoapAction {

    private enum Phase {
        NAVIGATE_TO_STRONGHOLD, FIND_PORTAL_FRAMES, PLACE_EYES, VERIFY, DONE
    }

    private Phase phase = Phase.DONE;
    private String targetGoalId;
    private int waitTicks;

    // Portal frame data
    private final List<BlockPos> emptyFrames = new ArrayList<>();
    private int frameIndex;
    private BlockPos portalCenter;

    private PortalRegistry portalRegistry;
    private WorldState worldStateRef;

    public void setPortalRegistry(PortalRegistry registry) {
        this.portalRegistry = registry;
    }

    public void setWorldState(WorldState state) {
        this.worldStateRef = state;
    }

    @Override
    public String getName() {
        return "ActivateEndPortal";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return state.strongholdKnown
                && state.hasItemInInventory("minecraft:ender_eye", 1)
                && state.dimension.contains("overworld");
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        if (state.hasEndPortal) return 0;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if ("activate_end_portal".equals(goal.type)) {
                targetGoalId = goal.id;
                return goal.priority * 0.9f;
            }
        }
        return 0;
    }

    @Override
    public void execute(Minecraft client) {
        phase = Phase.NAVIGATE_TO_STRONGHOLD;
        emptyFrames.clear();
        frameIndex = 0;
        waitTicks = 0;
        portalCenter = null;
        EmmaBridgeMod.LOGGER.info("ActivateEndPortal: starting");
    }

    @Override
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (player == null || world == null) return;
        waitTicks++;

        switch (phase) {
            case NAVIGATE_TO_STRONGHOLD -> tickNavigate(player);
            case FIND_PORTAL_FRAMES -> tickFindFrames(player, world);
            case PLACE_EYES -> tickPlaceEyes(player, world);
            case VERIFY -> tickVerify(player, world);
            case DONE -> {}
        }
    }

    // ── Navigate to stronghold coordinates ────────────────────────

    private void tickNavigate(LocalPlayer player) {
        if (worldStateRef == null || !worldStateRef.strongholdKnown) {
            phase = Phase.DONE;
            return;
        }

        BlockPos target = new BlockPos(worldStateRef.strongholdX, (int) player.getY(), worldStateRef.strongholdZ);
        GoapNavHelper.NavResult result = GoapNavHelper.tickNavigateToBlock(
                player, target, waitTicks, 1200, 10.0);

        switch (result) {
            case ARRIVED -> {
                phase = Phase.FIND_PORTAL_FRAMES;
                waitTicks = 0;
                EmmaBridgeMod.LOGGER.info("ActivateEndPortal: near stronghold, searching for portal frames");
            }
            case TIMEOUT -> {
                // Try searching from here anyway
                phase = Phase.FIND_PORTAL_FRAMES;
                waitTicks = 0;
            }
            case NO_TARGET -> phase = Phase.DONE;
            case PATHING -> {}
        }
    }

    // ── Find end portal frame blocks ──────────────────────────────

    private void tickFindFrames(LocalPlayer player, ClientLevel world) {
        emptyFrames.clear();

        // Scan nearby blocks for end_portal_frame
        List<BlockPos> frames = worldStateRef != null
                ? worldStateRef.nearbyBlocks.getOrDefault("minecraft:end_portal_frame", List.of())
                : List.of();

        if (!frames.isEmpty()) {
            // Found frames in scanner results
            for (BlockPos pos : frames) {
                BlockState state = world.getBlockState(pos);
                if (state.is(Blocks.END_PORTAL_FRAME)) {
                    boolean hasEye = state.getValue(BlockStateProperties.EYE);
                    if (!hasEye) {
                        emptyFrames.add(pos);
                    }
                    if (portalCenter == null) {
                        portalCenter = pos;
                    }
                }
            }

            if (emptyFrames.isEmpty()) {
                // All frames have eyes — portal should be active
                phase = Phase.VERIFY;
                waitTicks = 0;
                return;
            }

            phase = Phase.PLACE_EYES;
            frameIndex = 0;
            waitTicks = 0;
            EmmaBridgeMod.LOGGER.info("ActivateEndPortal: found {} empty frames", emptyFrames.size());
            return;
        }

        // No frames found in scanner — spiral search around stronghold
        if (waitTicks > 200) {
            EmmaBridgeMod.LOGGER.warn("ActivateEndPortal: no portal frames found, aborting");
            phase = Phase.DONE;
            return;
        }

        // Navigate around stronghold to trigger block scanner
        if (waitTicks % 40 == 0 && worldStateRef != null) {
            int angle = (waitTicks / 40) * 45;
            double rad = Math.toRadians(angle);
            int searchX = worldStateRef.strongholdX + (int) (20 * Math.cos(rad));
            int searchZ = worldStateRef.strongholdZ + (int) (20 * Math.sin(rad));
            GoapNavHelper.pathTo(new BlockPos(searchX, (int) player.getY(), searchZ));
        }
    }

    // ── Place eyes in empty frames ────────────────────────────────

    private void tickPlaceEyes(LocalPlayer player, ClientLevel world) {
        if (frameIndex >= emptyFrames.size()) {
            phase = Phase.VERIFY;
            waitTicks = 0;
            EmmaBridgeMod.LOGGER.info("ActivateEndPortal: all eyes placed, verifying");
            return;
        }

        BlockPos framePos = emptyFrames.get(frameIndex);

        // Check if already filled (by another agent or previous tick)
        BlockState state = world.getBlockState(framePos);
        if (!state.is(Blocks.END_PORTAL_FRAME) || state.getValue(BlockStateProperties.EYE)) {
            frameIndex++;
            waitTicks = 0;
            return;
        }

        // Navigate within reach
        if (!BlockInteraction.isInReach(framePos)) {
            GoapNavHelper.tickNavigateToBlock(player, framePos, waitTicks, 200, 3.5);
            return;
        }

        if (GoapNavHelper.isPathing()) {
            GoapNavHelper.cancelPathing();
        }

        // Equip ender eye and right-click the frame
        if (!BlockInteraction.forceEquipItem(Items.ENDER_EYE)) return;

        BlockInteraction.rightClickBlock(framePos, Direction.UP);

        // Check if eye was placed
        if (waitTicks > 3) {
            BlockState after = world.getBlockState(framePos);
            if (after.is(Blocks.END_PORTAL_FRAME) && after.getValue(BlockStateProperties.EYE)) {
                frameIndex++;
                waitTicks = 0;
                EmmaBridgeMod.LOGGER.info("ActivateEndPortal: placed eye {}/{}", frameIndex, emptyFrames.size());
            } else if (waitTicks > 20) {
                // Move on — might be obstructed
                frameIndex++;
                waitTicks = 0;
            }
        }
    }

    // ── Verify portal is active ───────────────────────────────────

    private void tickVerify(LocalPlayer player, ClientLevel world) {
        // Check for end_portal block (appears in the center when all 12 eyes are placed)
        if (portalCenter != null) {
            // Search the 3x3 interior of the portal
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos check = portalCenter.offset(dx, 0, dz);
                    if (world.getBlockState(check).is(Blocks.END_PORTAL)) {
                        EmmaBridgeMod.LOGGER.info("ActivateEndPortal: end portal is active!");
                        // Save to PortalRegistry
                        if (portalRegistry != null) {
                            portalRegistry.addPortal("end_portal_stronghold", "end_portal",
                                    "minecraft:overworld", check.getX(), check.getY(), check.getZ());
                        }
                        phase = Phase.DONE;
                        return;
                    }
                }
            }
        }

        if (waitTicks > 40) {
            // Portal didn't activate — might be missing eyes, rescan
            phase = Phase.FIND_PORTAL_FRAMES;
            waitTicks = 0;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        if (phase != Phase.DONE) {
            GoapNavHelper.cancelPathing();
        }
        phase = Phase.DONE;
        targetGoalId = null;
    }

    @Override
    public boolean isActive() {
        return phase != Phase.DONE;
    }

    @Override
    public int getMinimumActiveTicks() {
        return 20;
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
        bd.addProperty("empty_frames", emptyFrames.size());
        bd.addProperty("frame_index", frameIndex);
        return bd;
    }
}
