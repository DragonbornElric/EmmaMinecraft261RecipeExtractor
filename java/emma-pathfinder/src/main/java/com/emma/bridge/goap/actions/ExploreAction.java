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
 * GOAP Action: Explore to find resources when needed blocks are not in scanner range.
 *
 * Preconditions: always true (scoring handles all filtering)
 * Score: highest unmet mining goal priority × 0.3, but ONLY when the specific blocks
 *        needed for that goal are NOT visible in the block scanner. This means crafting
 *        stations being present won't block exploration — only the actual ore/resource
 *        blocks matter.
 *
 * When seeking underground ores from the surface, navigates to an appropriate Y level
 * (Y=16-48 for iron/gold/diamond). Otherwise explores horizontally.
 *
 * The block scanner runs every 20 ticks during movement, so if the bot walks past
 * resources, MineBlock outscores ExploreAction naturally and takes over.
 */
public class ExploreAction extends GoapAction {

    private static final Random random = new Random();
    private static final int EXPLORE_TIMEOUT_TICKS = 400; // 20 seconds

    private boolean exploring = false;
    private String targetGoalId = null;
    private BlockPos targetPos = null;
    private int exploreTicks = 0;
    private boolean needsUndergroundOre = false;

    @Override
    public String getName() {
        return "Explore";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        // Let computeScore handle all filtering — precondition is always true
        // so we can activate even when crafting stations are in nearbyBlocks
        return true;
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        float bestScore = 0;
        needsUndergroundOre = false;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (goal.target == null || !goal.target.has("item")) continue;

            String goalItem = goal.target.get("item").getAsString();
            int goalCount = goal.target.has("count") ? goal.target.get("count").getAsInt() : 1;

            // Skip already satisfied goals
            if (state.isGoalItemSatisfied(goalItem, goalCount)) continue;

            String goalId = goalItem.contains(":") ? goalItem.split(":")[1] : goalItem;

            // Check if this goal needs items obtainable by mining AND those blocks
            // are NOT currently visible in the scanner
            if (needsExploration(goalId, state)) {
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
        needsUndergroundOre = false;
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
        bd.addProperty("needs_underground", needsUndergroundOre);
        if (targetPos != null) {
            bd.addProperty("target_pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ());
        }
        return bd;
    }

    // -- Helpers --------------------------------------------------------

    /**
     * Check if an item has any mineable transitive dependency whose blocks
     * are NOT currently visible in the block scanner. If the blocks ARE visible,
     * MineBlock can handle it — no exploration needed.
     */
    private boolean needsExploration(String itemId, WorldState state) {
        List<String> deps = ItemRecipeRegistry.getTransitiveDependencies(itemId);
        deps.add(itemId);

        boolean hasAnyMineGoal = false;
        boolean allBlocksVisible = true;

        for (String dep : deps) {
            for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(dep)) {
                if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
                hasAnyMineGoal = true;

                String[] mineBlocks = entry.getMineBlockNames();
                if (mineBlocks == null) continue;

                boolean anyVisible = false;
                for (String mb : mineBlocks) {
                    String fullId = mb.contains(":") ? mb : "minecraft:" + mb;
                    List<BlockPos> found = state.nearbyBlocks.get(fullId);
                    List<BlockPos> positions = found != null ? found : List.of();
                    if (!positions.isEmpty()) {
                        anyVisible = true;
                        break;
                    }
                }

                if (!anyVisible) {
                    allBlocksVisible = false;
                    // Check if this is an underground ore (iron, gold, diamond, etc.)
                    if (mineBlocks.length > 0) {
                        String blockName = mineBlocks[0].toLowerCase();
                        if (blockName.contains("iron_ore") || blockName.contains("gold_ore")
                                || blockName.contains("diamond_ore") || blockName.contains("copper_ore")
                                || blockName.contains("lapis_ore") || blockName.contains("redstone_ore")
                                || blockName.contains("emerald_ore") || blockName.contains("coal_ore")
                                || blockName.contains("deepslate")) {
                            needsUndergroundOre = true;
                        }
                    }
                }
            }
        }

        // Only need exploration if there are mining goals with invisible blocks
        return hasAnyMineGoal && !allBlocksVisible;
    }

    /**
     * Generate a random BlockPos 50-80 blocks away from the player.
     * When seeking underground ores from the surface, targets Y=16-48.
     */
    private BlockPos generateRandomTarget(LocalPlayer player) {
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = 50.0 + random.nextDouble() * 30.0;
        int targetX = (int) (player.getX() + distance * Math.cos(angle));
        int targetZ = (int) (player.getZ() + distance * Math.sin(angle));

        int targetY;
        if (needsUndergroundOre && player.getY() > 50) {
            // Navigate underground to where ores generate (Y=16-48)
            targetY = 16 + random.nextInt(32);
        } else {
            targetY = (int) player.getY();
        }
        return new BlockPos(targetX, targetY, targetZ);
    }
}
