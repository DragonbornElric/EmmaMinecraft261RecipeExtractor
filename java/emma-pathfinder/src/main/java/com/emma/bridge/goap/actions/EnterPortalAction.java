package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.control.DirectInput;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.PortalRegistry;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * GOAP Action: Navigate to a portal and enter it to change dimensions.
 *
 * Scores on "enter_dimension" goal type. Uses PortalRegistry to find the
 * nearest portal matching the target dimension. Navigates to it via Emmatone,
 * then walks forward into the portal block.
 *
 * Dimension routing:
 *   overworld → nether: nether_portal
 *   nether → overworld: nether_portal
 *   any → overworld from end: navigate to (0, ~60, 0) exit portal
 *   cross-dimension (nether → end): GoalDecomposer creates intermediate overworld goal
 */
public class EnterPortalAction extends GoapAction {

    private enum Phase {
        LOOKUP, NAVIGATE, ENTER, WAIT_TRANSITION, DONE
    }

    private Phase phase = Phase.LOOKUP;
    private String targetGoalId;
    private String targetDimension;
    private BlockPos portalPos;
    private int navWaitTicks;
    private int enterWaitTicks;
    private String startDimension;  // dimension when we started, to detect transition

    private PortalRegistry portalRegistry;

    public void setPortalRegistry(PortalRegistry registry) {
        this.portalRegistry = registry;
    }

    @Override
    public String getName() {
        return "EnterPortal";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return portalRegistry != null;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        for (GoalSet.Goal goal : goals.getGoals()) {
            if (!"enter_dimension".equals(goal.type)) continue;
            if (goal.target == null || !goal.target.has("dimension")) continue;

            String wantDim = goal.target.get("dimension").getAsString();

            // Already in target dimension
            if (state.dimension.contains(dimensionKey(wantDim))) return 0;

            // Check if a suitable portal exists
            String portalType = portalTypeFor(state.dimension, wantDim);
            if (portalType == null) return 0;

            if ("exit_portal".equals(portalType)) {
                // Exit portal is always at 0,0 in the End
                targetGoalId = goal.id;
                targetDimension = wantDim;
                return goal.priority * 0.85f;
            }

            if (!portalRegistry.hasPortalAccess(state.dimension, portalType)) return 0;

            targetGoalId = goal.id;
            targetDimension = wantDim;
            return goal.priority * 0.85f;
        }
        return 0;
    }

    @Override
    public void execute(Minecraft client) {
        phase = Phase.LOOKUP;
        portalPos = null;
        navWaitTicks = 0;
        enterWaitTicks = 0;
        startDimension = client.player != null ? client.player.level().dimension().identifier().toString() : "";
        EmmaBridgeMod.LOGGER.info("EnterPortal: starting, target dimension: {}", targetDimension);
    }

    @Override
    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        switch (phase) {
            case LOOKUP -> tickLookup(player);
            case NAVIGATE -> tickNavigate(player);
            case ENTER -> tickEnter(player);
            case WAIT_TRANSITION -> tickWaitTransition(player);
            case DONE -> {}
        }
    }

    // ── Phase: Find portal position ───────────────────────────────

    private void tickLookup(LocalPlayer player) {
        String currentDim = player.level().dimension().identifier().toString();
        String portalType = portalTypeFor(currentDim, targetDimension);

        if ("exit_portal".equals(portalType)) {
            // End exit portal is always at approximately (0, 62, 0)
            portalPos = new BlockPos(0, 62, 0);
            phase = Phase.NAVIGATE;
            navWaitTicks = 0;
            return;
        }

        if (portalRegistry == null || portalType == null) {
            phase = Phase.DONE;
            return;
        }

        Optional<PortalRegistry.Portal> nearest = portalRegistry.getNearestPortal(
                player.getX(), player.getZ(), currentDim, portalType);

        if (nearest.isPresent()) {
            PortalRegistry.Portal p = nearest.get();
            portalPos = new BlockPos(p.x(), p.y(), p.z());
            phase = Phase.NAVIGATE;
            navWaitTicks = 0;
            EmmaBridgeMod.LOGGER.info("EnterPortal: found portal at {}", portalPos);
        } else {
            EmmaBridgeMod.LOGGER.warn("EnterPortal: no portal found for type {}", portalType);
            phase = Phase.DONE;
        }
    }

    // ── Phase: Navigate to portal ─────────────────────────────────

    private void tickNavigate(LocalPlayer player) {
        navWaitTicks++;
        GoapNavHelper.NavResult result = GoapNavHelper.tickNavigateToBlock(
                player, portalPos, navWaitTicks, 600, 3.0);

        switch (result) {
            case ARRIVED -> {
                phase = Phase.ENTER;
                enterWaitTicks = 0;
                EmmaBridgeMod.LOGGER.info("EnterPortal: arrived at portal, entering");
            }
            case TIMEOUT -> {
                EmmaBridgeMod.LOGGER.warn("EnterPortal: navigation timeout");
                phase = Phase.DONE;
            }
            case NO_TARGET -> phase = Phase.LOOKUP;
            case PATHING -> {}
        }
    }

    // ── Phase: Walk into portal ───────────────────────────────────

    private void tickEnter(LocalPlayer player) {
        enterWaitTicks++;

        // Cancel pathfinding — we're walking in manually
        if (GoapNavHelper.isPathing()) {
            GoapNavHelper.cancelPathing();
        }

        // Look at portal center and walk forward
        BlockInteraction.lookAt(portalPos);
        DirectInput.setForward(true);

        // Check if dimension changed
        String currentDim = player.level().dimension().identifier().toString();
        if (!currentDim.equals(startDimension)) {
            DirectInput.setForward(false);
            phase = Phase.DONE;
            EmmaBridgeMod.LOGGER.info("EnterPortal: dimension changed to {}", currentDim);
            return;
        }

        if (enterWaitTicks > 200) {
            // Portal loading takes up to ~4 seconds (80 ticks) — give extra time
            DirectInput.setForward(false);
            EmmaBridgeMod.LOGGER.warn("EnterPortal: timeout waiting for portal transition");
            phase = Phase.DONE;
        }
    }

    // ── Phase: Wait for world transition ──────────────────────────

    private void tickWaitTransition(LocalPlayer player) {
        enterWaitTicks++;
        String currentDim = player.level().dimension().identifier().toString();
        if (!currentDim.equals(startDimension)) {
            phase = Phase.DONE;
            EmmaBridgeMod.LOGGER.info("EnterPortal: arrived in {}", currentDim);
        } else if (enterWaitTicks > 100) {
            phase = Phase.DONE;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        DirectInput.setForward(false);
        if (phase == Phase.NAVIGATE) {
            GoapNavHelper.cancelPathing();
        }
        phase = Phase.LOOKUP;
        targetGoalId = null;
    }

    @Override
    public boolean isActive() {
        return phase != Phase.DONE && phase != Phase.LOOKUP;
    }

    @Override
    public int getMinimumActiveTicks() {
        return 40; // Don't interrupt while entering portal
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
        bd.addProperty("target_dimension", targetDimension != null ? targetDimension : "none");
        if (portalPos != null) {
            bd.addProperty("portal_pos", portalPos.toShortString());
        }
        return bd;
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Extract the unique portion of a dimension ID for contains() matching. */
    private static String dimensionKey(String dimension) {
        if (dimension.contains("the_nether")) return "the_nether";
        if (dimension.contains("the_end")) return "the_end";
        return "overworld";
    }

    /** Determine which portal type to use for a given dimension transition. */
    private static String portalTypeFor(String fromDim, String toDim) {
        boolean fromEnd = fromDim.contains("the_end");
        boolean toNether = toDim.contains("the_nether");
        boolean toOverworld = toDim.contains("overworld");

        if (fromEnd && toOverworld) return "exit_portal";
        if (toNether || (fromDim.contains("the_nether") && toOverworld)) return "nether_portal";
        return null; // cross-dimension hops not directly supported
    }
}
