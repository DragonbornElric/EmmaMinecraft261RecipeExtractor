package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.GoalNear;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.catalogue.ItemRecipeEntry;
import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.catalogue.ObtainMethod;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.ItemClassifier;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * GOAP Action: Transform blocks in-place via tool interaction or water contact.
 *
 * Handles:
 *   - TOOL_USE: right-click with tool to transform block (strip logs with axe,
 *     create dirt paths with shovel, till farmland with hoe)
 *   - WATER_CONTACT: place concrete powder near water → auto-converts to concrete
 *     (place block, then mine the resulting concrete)
 *
 * Claims goals with type "transform_block" (created by GoalDecomposer for TRANSFORM items).
 *
 * Personality: resource_hoarding (passive resource processing).
 */
public class TransformBlockAction extends GoapAction {

    private static final float SEARCH_RANGE = 32.0f;
    private static final int NAV_TIMEOUT_TICKS = 400;  // 20 seconds

    // ── State ─────────────────────────────────────────────────────

    private enum State { IDLE, EQUIP, NAVIGATE, TRANSFORM, DONE }

    private State state = State.IDLE;
    private BlockPos targetBlockPos = null;
    private String targetGoalId = null;
    private String goalTransformInput = null;
    private String goalTransformTool = null;
    private String goalTransformType = null;
    private int navTicks = 0;

    // Unreachable block tracking — captured in computeScore(), written on nav timeout
    private Map<BlockPos, Long> unreachableBlocksRef = null;
    private long currentTick = 0;

    @Override
    public String getName() {
        return "TransformBlock";
    }

    @Override
    public String personalityCategory() {
        return "resource_hoarding";
    }

    @Override
    public boolean checkPreconditions(WorldState worldState) {
        return true;
    }

    @Override
    public float computeScore(WorldState worldState, GoalSet goals) {
        float bestScore = 0;
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) return 0;

        // Capture unreachable block refs for nav timeout marking
        unreachableBlocksRef = worldState.unreachableBlocks;
        currentTick = worldState.tick;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (!"transform_block".equals(goal.type)) continue;
            if (goal.target == null) continue;

            // Only score if this transform is a blocking path — no feasible sibling goal
            if (goal.parentGoalId != null && hasFeasibleSibling(goal, goals, worldState)) {
                continue;
            }

            String type = goal.target.has("transform_type")
                    ? goal.target.get("transform_type").getAsString() : null;
            String input = goal.target.has("transform_input")
                    ? goal.target.get("transform_input").getAsString() : null;
            String tool = goal.target.has("transform_tool")
                    ? goal.target.get("transform_tool").getAsString() : null;

            float score = 0;

            if ("TOOL_USE".equals(type)) {
                score = scoreToolUse(goal, player, worldState, input, tool);
            } else if ("WATER_CONTACT".equals(type)) {
                score = scoreWaterContact(goal, player, worldState, input);
            }

            if (score > bestScore) {
                bestScore = score;
                targetGoalId = goal.id;
                goalTransformInput = input;
                goalTransformTool = tool;
                goalTransformType = type;
            }
        }

        return bestScore;
    }

    private float scoreToolUse(GoalSet.Goal goal, LocalPlayer player,
                                WorldState worldState, String input, String tool) {
        // Must have tool of the right category in inventory
        if (tool != null && !hasToolCategory(player, tool)) return 0;

        // Must find the input block nearby
        if (input == null) return 0;

        // Check nearbyBlocks from WorldState first, fall back to direct scan
        BlockPos blockPos = findInputBlock(player, worldState, input);
        if (blockPos == null) return 0;

        double distance = Math.sqrt(player.blockPosition().distSqr(blockPos));
        float proximityFactor = 1.0f / (1.0f + (float) distance / 10.0f);
        return goal.priority * proximityFactor;
    }

    private float scoreWaterContact(GoalSet.Goal goal, LocalPlayer player,
                                     WorldState worldState, String input) {
        // Must have input item (concrete powder) in inventory
        if (input != null && !worldState.hasItem("minecraft:" + input, 1)) return 0;

        // Must find water nearby
        if (!hasWaterNearby(player)) return 0;

        return goal.priority * 0.8f;
    }

    @Override
    public void execute(Minecraft client) {
        state = State.EQUIP;
        navTicks = 0;
        targetBlockPos = null;

        EmmaBridgeMod.LOGGER.info("[GOAP TransformBlock] Starting: type={}, input={}, tool={}, goal={}",
                goalTransformType, goalTransformInput, goalTransformTool, targetGoalId);
    }

    @Override
    public void tick(Minecraft client) {
        if (state == State.IDLE || state == State.DONE) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        switch (state) {
            case EQUIP -> tickEquip(player);
            case NAVIGATE -> tickNavigate(player);
            case TRANSFORM -> tickTransform(player);
            default -> {}
        }
    }

    private void tickEquip(LocalPlayer player) {
        if ("TOOL_USE".equals(goalTransformType) && goalTransformTool != null) {
            if (!ItemClassifier.equipBestOfCategory(player, goalTransformTool)) {
                EmmaBridgeMod.LOGGER.warn("[GOAP TransformBlock] No {} in inventory", goalTransformTool);
                state = State.DONE;
                return;
            }
        }
        state = State.NAVIGATE;
        navTicks = 0;
    }

    private void tickNavigate(LocalPlayer player) {
        if ("TOOL_USE".equals(goalTransformType)) {
            navigateToInputBlock(player);
        } else if ("WATER_CONTACT".equals(goalTransformType)) {
            navigateToWater(player);
        } else {
            state = State.DONE;
        }
    }

    private void navigateToInputBlock(LocalPlayer player) {
        // Find/refresh target block
        if (targetBlockPos == null || !isStillInputBlock(player.level(), targetBlockPos)) {
            WorldState worldState = new WorldState(); // lightweight — just need nearbyBlocks
            targetBlockPos = findInputBlock(player, null, goalTransformInput);
            if (targetBlockPos == null) {
                navTicks++;
                if (navTicks > NAV_TIMEOUT_TICKS) {
                    EmmaBridgeMod.LOGGER.info("[GOAP TransformBlock] No {} block found, giving up",
                            goalTransformInput);
                    state = State.DONE;
                }
                return;
            }
        }

        if (BlockInteraction.isInReach(targetBlockPos)) {
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
            state = State.TRANSFORM;
            return;
        }

        // Path to block
        if (!EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().isPathing()) {
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                    .setGoalAndPath(new GoalNear(targetBlockPos, 1));
        }

        navTicks++;
        if (navTicks > NAV_TIMEOUT_TICKS) {
            EmmaBridgeMod.LOGGER.info("[GOAP TransformBlock] Navigation timeout");
            if (targetBlockPos != null && unreachableBlocksRef != null) {
                unreachableBlocksRef.put(targetBlockPos, currentTick);
            }
            state = State.DONE;
        }
    }

    private void navigateToWater(LocalPlayer player) {
        // For WATER_CONTACT: find water, navigate to adjacent block, place powder
        // This is more complex — place powder adjacent to water, it auto-converts, then mine it.
        // For now, find water and navigate to it.
        if (targetBlockPos == null) {
            targetBlockPos = findNearbyWater(player);
            if (targetBlockPos == null) {
                navTicks++;
                if (navTicks > NAV_TIMEOUT_TICKS) {
                    EmmaBridgeMod.LOGGER.info("[GOAP TransformBlock] No water found, giving up");
                    state = State.DONE;
                }
                return;
            }
        }

        // Navigate to adjacent to water (not into water)
        if (BlockInteraction.isInReach(targetBlockPos)) {
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
            state = State.TRANSFORM;
            return;
        }

        if (!EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().isPathing()) {
            EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                    .setGoalAndPath(new GoalNear(targetBlockPos, 2));
        }

        navTicks++;
        if (navTicks > NAV_TIMEOUT_TICKS) {
            EmmaBridgeMod.LOGGER.info("[GOAP TransformBlock] Navigation timeout to water");
            if (targetBlockPos != null && unreachableBlocksRef != null) {
                unreachableBlocksRef.put(targetBlockPos, currentTick);
            }
            state = State.DONE;
        }
    }

    private void tickTransform(LocalPlayer player) {
        if ("TOOL_USE".equals(goalTransformType)) {
            // Re-equip tool (may have been switched)
            if (goalTransformTool != null) {
                ItemClassifier.equipBestOfCategory(player, goalTransformTool);
            }

            if (targetBlockPos != null && BlockInteraction.isInReach(targetBlockPos)) {
                BlockInteraction.lookAt(targetBlockPos);
                BlockInteraction.rightClickBlock(targetBlockPos);
                EmmaBridgeMod.LOGGER.info("[GOAP TransformBlock] Right-clicked {} at {} with {}",
                        goalTransformInput, targetBlockPos, goalTransformTool);
            }
        } else if ("WATER_CONTACT".equals(goalTransformType)) {
            // Place concrete powder adjacent to water
            if (targetBlockPos != null && goalTransformInput != null) {
                // Equip concrete powder
                net.minecraft.world.item.Item inputItem = BuiltInRegistries.ITEM
                        .getValue(net.minecraft.resources.Identifier
                                .withDefaultNamespace(goalTransformInput));
                if (inputItem != null && inputItem != net.minecraft.world.item.Items.AIR) {
                    BlockInteraction.forceEquipItem(inputItem);
                    // Place on top of the water-adjacent block (or next to water)
                    BlockInteraction.rightClickBlock(targetBlockPos);
                    EmmaBridgeMod.LOGGER.info("[GOAP TransformBlock] Placed {} near water at {}",
                            goalTransformInput, targetBlockPos);
                }
            }
        }

        state = State.DONE;
    }

    @Override
    public void onDeactivated(Minecraft client) {
        state = State.IDLE;
        targetBlockPos = null;
        navTicks = 0;
        EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
    }

    @Override
    public boolean isActive() {
        return state != State.IDLE && state != State.DONE;
    }

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public float relevanceToGoal(WorldState worldState, GoalSet.Goal goal) {
        return 0.0f;
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState worldState, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(worldState, goals);
        bd.addProperty("transform_type", goalTransformType != null ? goalTransformType : "none");
        bd.addProperty("transform_input", goalTransformInput != null ? goalTransformInput : "none");
        bd.addProperty("transform_tool", goalTransformTool != null ? goalTransformTool : "none");
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        bd.addProperty("state", state.name());
        return bd;
    }

    // ── Blocking path check ────────────────────────────────────

    /**
     * Check if any sibling goal (same parent, non-transform) has mineable
     * resources nearby. If yes, the parent need can be satisfied without
     * this transform — it's not a blocking path.
     */
    private boolean hasFeasibleSibling(GoalSet.Goal transformGoal, GoalSet goals,
                                        WorldState state) {
        for (GoalSet.Goal sibling : goals.getGoals()) {
            if (sibling == transformGoal) continue;
            if (sibling.parentGoalId == null) continue;
            if (!sibling.parentGoalId.equals(transformGoal.parentGoalId)) continue;
            if ("transform_block".equals(sibling.type)) continue;

            // Check if sibling's item has mineable blocks nearby
            if (sibling.target != null && sibling.target.has("item")) {
                String itemId = sibling.target.get("item").getAsString();
                String cleanId = itemId.contains(":") ? itemId.split(":")[1] : itemId;
                for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(cleanId)) {
                    if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
                    if (entry.getMineBlockNames() == null) continue;
                    for (String block : entry.getMineBlockNames()) {
                        String fullBlock = block.contains(":") ? block : "minecraft:" + block;
                        List<BlockPos> found = state.nearbyBlocks.get(fullBlock);
                        if (found != null && !found.isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Check if player has any tool of the given category (axe, shovel, hoe) in inventory. */
    private boolean hasToolCategory(LocalPlayer player, String category) {
        for (int i = 0; i < 36; i++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String cat = ItemClassifier.getToolCategory(ItemClassifier.itemId(stack));
            if (category.equals(cat)) return true;
        }
        return false;
    }

    /** Find the input block nearby — checks WorldState nearbyBlocks then does direct scan. */
    private BlockPos findInputBlock(LocalPlayer player, WorldState worldState, String input) {
        String fullId = input.contains(":") ? input : "minecraft:" + input;

        // Check WorldState nearby blocks first (populated by GoapTicker block scanner)
        if (worldState != null) {
            List<BlockPos> positions = worldState.nearbyBlocks.get(fullId);
            if (positions == null) {
                // Try without namespace
                positions = worldState.nearbyBlocks.get(input);
            }
            if (positions != null && !positions.isEmpty()) {
                // Return nearest
                BlockPos nearest = null;
                double nearestDist = Double.MAX_VALUE;
                for (BlockPos pos : positions) {
                    double dist = player.blockPosition().distSqr(pos);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = pos;
                    }
                }
                return nearest;
            }
        }

        // Fall back to direct scan (smaller range for performance)
        return directBlockScan(player, fullId, 16);
    }

    /** Direct brute-force block scan within range. */
    private BlockPos directBlockScan(LocalPlayer player, String blockId, int range) {
        Level world = player.level();
        BlockPos center = player.blockPosition();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -range; x <= range; x++) {
            for (int y = -8; y <= 8; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState bs = world.getBlockState(pos);
                    String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
                    if (id.equals(blockId)) {
                        double dist = center.distSqr(pos);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    /** Check if the block at pos is still the expected input block. */
    private boolean isStillInputBlock(Level world, BlockPos pos) {
        if (goalTransformInput == null) return false;
        String fullId = goalTransformInput.contains(":") ? goalTransformInput : "minecraft:" + goalTransformInput;
        BlockState bs = world.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
        return id.equals(fullId);
    }

    /** Check if there's water within SEARCH_RANGE. */
    private boolean hasWaterNearby(LocalPlayer player) {
        return findNearbyWater(player) != null;
    }

    /** Find the nearest water source block. */
    private BlockPos findNearbyWater(LocalPlayer player) {
        Level world = player.level();
        BlockPos center = player.blockPosition();
        int range = 16;  // smaller range for water search
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -range; x <= range; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (world.getFluidState(pos).isSource()) {
                        String fluidId = BuiltInRegistries.FLUID.getKey(
                                world.getFluidState(pos).getType()).toString();
                        if (fluidId.equals("minecraft:water")) {
                            double dist = center.distSqr(pos);
                            if (dist < nearestDist) {
                                nearestDist = dist;
                                nearest = pos;
                            }
                        }
                    }
                }
            }
        }
        return nearest;
    }
}
