package com.emma.bridge.goap.actions;

import com.emma.bridge.catalogue.ItemRecipeEntry;
import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.catalogue.ObtainMethod;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Random;

/**
 * GOAP Action: Explore to find resources when nothing useful is in scanner range.
 *
 * Preconditions: nearbyBlocks is empty (scanner found nothing goal-relevant)
 * Score: highest unmet mining goal priority × 0.3 (low enough that mining/crafting
 *        always win when resources ARE nearby, but non-zero to beat the 0-score deadlock)
 *
 * Navigates to a random position 50-80 blocks away. The block scanner runs every
 * 20 ticks during movement, so if the bot walks past resources, MineBlock outscores
 * ExploreAction naturally and takes over.
 */
public class ExploreAction extends GoapAction {

    private static final Random random = new Random();
    private static final int EXPLORE_TIMEOUT_TICKS = 400; // 20 seconds

    private boolean exploring = false;
    private String targetGoalId = null;
    private BlockPos targetPos = null;
    private int exploreTicks = 0;

    @Override
    public String getName() {
        return "Explore";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return state.nearbyBlocks.isEmpty();
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        float bestScore = 0;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (goal.target == null || !goal.target.has("item")) continue;

            String goalItem = goal.target.get("item").getAsString();
            String goalId = goalItem.contains(":") ? goalItem.split(":")[1] : goalItem;

            // Check if this goal needs items obtainable by mining
            if (hasMineableDependency(goalId)) {
                float score = goal.priority * 0.3f;
                if (score > bestScore) {
                    bestScore = score;
                    targetGoalId = goal.id;
                }
            }
        }

        return bestScore;
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;

        targetPos = generateRandomTarget(player);
        GoapNavHelper.pathTo(targetPos);
        exploring = true;
        exploreTicks = 0;
    }

    @Override
    public void tick(Minecraft client) {
        if (!exploring) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        exploreTicks++;

        switch (GoapNavHelper.tickNavigateToBlock(player, targetPos, exploreTicks)) {
            case ARRIVED -> {
                // Arrived at target — pick a new random direction and keep exploring
                targetPos = generateRandomTarget(player);
                GoapNavHelper.pathTo(targetPos);
                exploreTicks = 0;
            }
            case TIMEOUT -> {
                // Stuck — pick a different direction
                targetPos = generateRandomTarget(player);
                GoapNavHelper.pathTo(targetPos);
                exploreTicks = 0;
            }
            case PATHING -> {} // still moving
            case NO_TARGET -> {
                exploring = false;
            }
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        if (exploring) {
            GoapNavHelper.cancelPathing();
            exploring = false;
        }
        targetGoalId = null;
        targetPos = null;
    }

    @Override
    public boolean isActive() {
        return exploring;
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
        bd.addProperty("exploring", exploring);
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        if (targetPos != null) {
            bd.addProperty("target_pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ());
        }
        return bd;
    }

    // -- Helpers --------------------------------------------------------

    /**
     * Check if an item has any mineable transitive dependency.
     */
    private static boolean hasMineableDependency(String itemId) {
        List<String> deps = ItemRecipeRegistry.getTransitiveDependencies(itemId);
        deps.add(itemId);
        for (String dep : deps) {
            for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(dep)) {
                if (entry.getObtainMethod() == ObtainMethod.MINE) return true;
            }
        }
        return false;
    }

    /**
     * Generate a random BlockPos 50-80 blocks away from the player at the same Y level.
     */
    private static BlockPos generateRandomTarget(LocalPlayer player) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = 50.0 + random.nextDouble() * 30.0;
        int targetX = (int) (player.getX() + distance * Math.cos(angle));
        int targetZ = (int) (player.getZ() + distance * Math.sin(angle));
        int targetY = (int) player.getY();
        return new BlockPos(targetX, targetY, targetZ);
    }
}
