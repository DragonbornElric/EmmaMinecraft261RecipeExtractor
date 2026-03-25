package com.emma.bridge.goap.actions;

import com.emma.bridge.BridgeServer;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.goap.*;
import com.emma.bridge.schematic.GuideSchematic;
import com.emma.bridge.schematic.GuideSchematicAdapter;
import com.emma.bridge.websocket.JsonProtocol;
import emmatone.api.EmmatoneAPI;
import emmatone.api.process.IBuilderProcess;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;

/**
 * GOAP action: build a structure from a stored build plan.
 *
 * Scores > 0 only when ALL materials for the build are in inventory.
 * While materials are being gathered (by MineBlock, CraftItem, etc.),
 * this action scores 0 and stays out of the way.
 *
 * Once materials are ready, starts Emmatone's BuilderProcess.
 * Can be preempted by survival actions (eat, fight, flee) — the builder
 * pauses and resumes when this action is re-selected.
 *
 * On completion, removes the build goal and broadcasts a
 * build_phase_complete event so Python can send the next phase.
 */
public class BuildStructureAction extends GoapAction {

    private final BuildPlanRegistry buildPlanRegistry;
    private final GoalSet goalSet;
    private BridgeServer bridgeServer;

    private boolean building = false;
    private boolean paused = false;
    private String activeBuildId = null;
    private String primaryGoalId = null;

    // Cooldown: after builder reports inactive, wait a few ticks to confirm
    private int completionCooldown = 0;
    private static final int COMPLETION_COOLDOWN_TICKS = 10;

    public BuildStructureAction(BuildPlanRegistry buildPlanRegistry, GoalSet goalSet) {
        this.buildPlanRegistry = buildPlanRegistry;
        this.goalSet = goalSet;
    }

    public void setBridgeServer(BridgeServer bridgeServer) {
        this.bridgeServer = bridgeServer;
    }

    @Override
    public String getName() {
        return "BuildStructure";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return buildPlanRegistry.hasActivePlan();
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        BuildPlanRegistry.BuildPlan plan = buildPlanRegistry.getActivePlan();
        if (plan == null) return 0;

        // Find the build_structure goal
        for (GoalSet.Goal goal : goals.getGoals()) {
            if ("build_structure".equals(goal.type)) {
                // If already building (or paused), keep scoring to resume
                if (building || paused) {
                    primaryGoalId = goal.id;
                    return goal.priority;
                }

                // Only start building when ALL materials are in inventory
                if (allMaterialsReady(state, plan)) {
                    primaryGoalId = goal.id;
                    return goal.priority;
                }

                // Materials not ready — let gathering actions run
                return 0;
            }
        }
        return 0;
    }

    @Override
    public void execute(Minecraft client) {
        BuildPlanRegistry.BuildPlan plan = buildPlanRegistry.getActivePlan();
        if (plan == null) return;

        IBuilderProcess builder = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone().getBuilderProcess();

        if (paused) {
            // Resume existing build
            builder.resume();
            building = true;
            paused = false;
            EmmaBridgeMod.LOGGER.info("[BuildStructure] Resumed build '{}'", plan.name);
        } else if (!building) {
            // Start new build
            GuideSchematicAdapter adapter = new GuideSchematicAdapter();
            GuideSchematic schematic = (GuideSchematic) adapter.fromGuideBlocks(plan.blocks);

            var settings = EmmatoneAPI.getSettings();
            settings.buildInLayers.value = true;
            settings.layerHeight.value = 2;
            settings.buildIgnoreDirection.value = true;

            builder.build(plan.name, schematic, new Vec3i(plan.originX, plan.originY, plan.originZ));
            building = true;
            activeBuildId = plan.buildId;
            completionCooldown = 0;

            EmmaBridgeMod.LOGGER.info("[BuildStructure] Started build '{}' ({} blocks) at ({},{},{})",
                    plan.name, plan.blocks.size(), plan.originX, plan.originY, plan.originZ);
        }
    }

    @Override
    public void tick(Minecraft client) {
        if (!building) return;

        IBuilderProcess builder = EmmatoneAPI.getProvider()
                .getPrimaryEmmatone().getBuilderProcess();

        if (!builder.isActive()) {
            // Builder stopped — wait for cooldown to confirm completion
            completionCooldown++;
            if (completionCooldown >= COMPLETION_COOLDOWN_TICKS) {
                onBuildComplete();
            }
        } else {
            completionCooldown = 0;
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        if (building) {
            IBuilderProcess builder = EmmatoneAPI.getProvider()
                    .getPrimaryEmmatone().getBuilderProcess();
            builder.pause();
            building = false;
            paused = true;
            EmmaBridgeMod.LOGGER.info("[BuildStructure] Paused build (preempted by survival action)");
        }
    }

    @Override
    public boolean isActive() {
        return building;
    }

    @Override
    public int getMinimumActiveTicks() {
        return 20;  // 1 second — don't interrupt mid-block-place
    }

    @Override
    public String personalityCategory() {
        return "resource_hoarding";
    }

    @Override
    public String getPrimaryGoalId() {
        return primaryGoalId;
    }

    // ── Internal ────────────────────────────────────────────────────

    private void onBuildComplete() {
        String buildId = activeBuildId;
        building = false;
        paused = false;
        activeBuildId = null;
        completionCooldown = 0;

        // Remove the build goal
        String goalId = "build_" + buildId;
        goalSet.removeDynamicGoal(goalId);
        buildPlanRegistry.clearActivePlan();

        EmmaBridgeMod.LOGGER.info("[BuildStructure] Build '{}' complete, goal removed", buildId);

        // Broadcast event so Python knows to send the next phase
        if (bridgeServer != null) {
            JsonObject data = new JsonObject();
            data.addProperty("build_id", buildId);
            bridgeServer.broadcastEvent(JsonProtocol.event("build_phase_complete", data));
        }
    }

    private boolean allMaterialsReady(WorldState state, BuildPlanRegistry.BuildPlan plan) {
        for (var entry : plan.materials.entrySet()) {
            if (!state.hasItem(entry.getKey(), entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
