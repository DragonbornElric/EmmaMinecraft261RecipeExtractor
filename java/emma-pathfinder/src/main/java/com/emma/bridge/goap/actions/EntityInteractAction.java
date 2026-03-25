package com.emma.bridge.goap.actions;

import emmatone.api.EmmatoneAPI;
import emmatone.api.pathing.goals.GoalNear;
import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.commands.HandlerUtils;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Map;

/**
 * GOAP Action: Right-click interact with entities or blocks using a held item.
 *
 * Unified handler for all "equip tool + walk to target + right-click" interactions:
 *   - Shearing sheep (shears → wool)
 *   - Milking cows (bucket → milk_bucket)
 *   - Mushroom stew from mooshrooms (bowl → mushroom_stew)
 *   - Harvesting honeycomb from beehives (shears → honeycomb)
 *
 * Claims goals with type "interact_entity" or "interact_block"
 * (created by GoalDecomposer for INTERACT items).
 *
 * Personality: resource_hoarding (passive collection, not aggressive).
 */
public class EntityInteractAction extends GoapAction {

    private static final float SEARCH_RANGE = 32.0f;
    private static final float INTERACT_REACH = 3.5f;
    private static final int NAV_TIMEOUT_TICKS = 400;  // 20 seconds

    /** Map entity class names (from JSON) to entity classes. */
    private static final Map<String, Class<? extends Entity>> ENTITY_CLASSES = Map.ofEntries(
            Map.entry("Sheep", Sheep.class),
            Map.entry("Cow", Cow.class),
            Map.entry("MushroomCow", MushroomCow.class),
            Map.entry("Goat", Goat.class),
            Map.entry("Pig", Pig.class),
            Map.entry("Chicken", Chicken.class),
            Map.entry("Horse", Horse.class),
            Map.entry("Donkey", Donkey.class),
            Map.entry("Llama", Llama.class)
    );

    /** Map tool name strings (from JSON) to Item instances. */
    private static final Map<String, Item> TOOL_ITEMS = Map.of(
            "shears", Items.SHEARS,
            "bucket", Items.BUCKET,
            "bowl", Items.BOWL
    );

    // ── State ─────────────────────────────────────────────────────

    private enum State { IDLE, EQUIP, NAVIGATE, INTERACT, DONE }

    private State state = State.IDLE;
    private Entity targetEntity = null;
    private BlockPos targetBlock = null;
    private String targetGoalId = null;
    private String goalEntityClass = null;
    private String goalBlockId = null;
    private String goalToolName = null;
    private int navTicks = 0;

    @Override
    public String getName() {
        return "EntityInteract";
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

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (goal.target == null) continue;

            float score = 0;

            if ("interact_entity".equals(goal.type)) {
                score = scoreEntityGoal(goal, player, worldState);
            } else if ("interact_block".equals(goal.type)) {
                score = scoreBlockGoal(goal, player, worldState);
            }

            if (score > bestScore) {
                bestScore = score;
                targetGoalId = goal.id;
                if ("interact_entity".equals(goal.type)) {
                    goalEntityClass = goal.target.get("interact_entity").getAsString();
                    goalBlockId = null;
                } else {
                    goalBlockId = goal.target.get("interact_block").getAsString();
                    goalEntityClass = null;
                }
                goalToolName = goal.target.has("interact_tool")
                        ? goal.target.get("interact_tool").getAsString() : null;
            }
        }

        return bestScore;
    }

    private float scoreEntityGoal(GoalSet.Goal goal, LocalPlayer player, WorldState worldState) {
        if (!goal.target.has("interact_entity")) return 0;
        String entityClassName = goal.target.get("interact_entity").getAsString();
        String toolName = goal.target.has("interact_tool")
                ? goal.target.get("interact_tool").getAsString() : null;

        // Must have tool in inventory
        if (toolName != null && !worldState.hasItem("minecraft:" + toolName, 1)) return 0;

        Class<? extends Entity> entityClass = ENTITY_CLASSES.get(entityClassName);
        if (entityClass == null) return 0;

        Entity nearest = HandlerUtils.findNearestEntity(player, (int) SEARCH_RANGE,
                e -> e.isAlive() && entityClass.isInstance(e));
        if (nearest == null) return 0;

        float distance = player.distanceTo(nearest);
        float proximityFactor = 1.0f / (1.0f + distance / 10.0f);
        return goal.priority * proximityFactor;
    }

    private float scoreBlockGoal(GoalSet.Goal goal, LocalPlayer player, WorldState worldState) {
        if (!goal.target.has("interact_block")) return 0;
        String blockId = goal.target.get("interact_block").getAsString();
        String toolName = goal.target.has("interact_tool")
                ? goal.target.get("interact_tool").getAsString() : null;

        // Must have tool in inventory
        if (toolName != null && !worldState.hasItem("minecraft:" + toolName, 1)) return 0;

        // Search for block nearby
        BlockPos blockPos = findNearbyBlock(player, blockId);
        if (blockPos == null) return 0;

        // Beehive precondition: must have campfire below
        if (blockId.equals("beehive") || blockId.equals("bee_nest")) {
            if (!hasCampfireBelow(player.level(), blockPos)) return 0;
        }

        double distance = Math.sqrt(player.blockPosition().distSqr(blockPos));
        float proximityFactor = 1.0f / (1.0f + (float) distance / 10.0f);
        return goal.priority * proximityFactor;
    }

    @Override
    public void execute(Minecraft client) {
        state = State.EQUIP;
        navTicks = 0;
        targetEntity = null;
        targetBlock = null;

        EmmaBridgeMod.LOGGER.info("[GOAP EntityInteract] Starting: entity={}, block={}, tool={}, goal={}",
                goalEntityClass, goalBlockId, goalToolName, targetGoalId);
    }

    @Override
    public void tick(Minecraft client) {
        if (state == State.IDLE || state == State.DONE) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        switch (state) {
            case EQUIP -> tickEquip(player);
            case NAVIGATE -> tickNavigate(player);
            case INTERACT -> tickInteract(client, player);
            default -> {}
        }
    }

    private void tickEquip(LocalPlayer player) {
        if (goalToolName != null) {
            Item toolItem = TOOL_ITEMS.get(goalToolName);
            if (toolItem == null) {
                // Try registry lookup for unknown tools
                toolItem = BuiltInRegistries.ITEM
                        .getValue(Identifier.withDefaultNamespace(goalToolName));
            }
            if (toolItem == null || toolItem == Items.AIR) {
                EmmaBridgeMod.LOGGER.warn("[GOAP EntityInteract] Unknown tool: {}", goalToolName);
                state = State.DONE;
                return;
            }
            if (!BlockInteraction.forceEquipItem(toolItem)) {
                EmmaBridgeMod.LOGGER.warn("[GOAP EntityInteract] Tool not in inventory: {}", goalToolName);
                state = State.DONE;
                return;
            }
        }
        state = State.NAVIGATE;
        navTicks = 0;
    }

    private void tickNavigate(LocalPlayer player) {
        // Find/refresh target
        if (goalEntityClass != null) {
            if (targetEntity == null || targetEntity.isRemoved() || !targetEntity.isAlive()) {
                Class<? extends Entity> entityClass = ENTITY_CLASSES.get(goalEntityClass);
                if (entityClass != null) {
                    targetEntity = HandlerUtils.findNearestEntity(player, (int) SEARCH_RANGE,
                            e -> e.isAlive() && entityClass.isInstance(e));
                }
                if (targetEntity == null) {
                    navTicks++;
                    if (navTicks > NAV_TIMEOUT_TICKS) {
                        EmmaBridgeMod.LOGGER.info("[GOAP EntityInteract] No {} found, giving up", goalEntityClass);
                        state = State.DONE;
                    }
                    return;
                }
            }

            float distance = player.distanceTo(targetEntity);
            if (distance <= INTERACT_REACH) {
                EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
                state = State.INTERACT;
                return;
            }

            // Path to entity
            if (!EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().isPathing()) {
                EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                        .setGoalAndPath(new GoalNear(targetEntity.blockPosition(), 2));
            }
        } else if (goalBlockId != null) {
            if (targetBlock == null) {
                targetBlock = findNearbyBlock(player, goalBlockId);
                if (targetBlock == null) {
                    navTicks++;
                    if (navTicks > NAV_TIMEOUT_TICKS) {
                        EmmaBridgeMod.LOGGER.info("[GOAP EntityInteract] No {} block found, giving up", goalBlockId);
                        state = State.DONE;
                    }
                    return;
                }
            }

            if (BlockInteraction.isInReach(targetBlock)) {
                EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().cancelEverything();
                state = State.INTERACT;
                return;
            }

            // Path to block
            if (!EmmatoneAPI.getProvider().getPrimaryEmmatone().getPathingBehavior().isPathing()) {
                EmmatoneAPI.getProvider().getPrimaryEmmatone().getCustomGoalProcess()
                        .setGoalAndPath(new GoalNear(targetBlock, 2));
            }
        }

        navTicks++;
        if (navTicks > NAV_TIMEOUT_TICKS) {
            EmmaBridgeMod.LOGGER.info("[GOAP EntityInteract] Navigation timeout");
            state = State.DONE;
        }
    }

    private void tickInteract(Minecraft client, LocalPlayer player) {
        if (goalEntityClass != null && targetEntity != null) {
            if (!targetEntity.isAlive() || targetEntity.isRemoved()) {
                state = State.NAVIGATE;
                targetEntity = null;
                return;
            }

            BlockInteraction.lookAt(targetEntity.getEyePosition());
            client.gameMode.interact(player, targetEntity,
                    new EntityHitResult(targetEntity), InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);

            EmmaBridgeMod.LOGGER.info("[GOAP EntityInteract] Interacted with {}", goalEntityClass);
        } else if (goalBlockId != null && targetBlock != null) {
            BlockInteraction.rightClickBlock(targetBlock);

            EmmaBridgeMod.LOGGER.info("[GOAP EntityInteract] Interacted with block {} at {}",
                    goalBlockId, targetBlock);
        }

        state = State.DONE;
    }

    @Override
    public void onDeactivated(Minecraft client) {
        state = State.IDLE;
        targetEntity = null;
        targetBlock = null;
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
        bd.addProperty("target_entity_class", goalEntityClass != null ? goalEntityClass : "none");
        bd.addProperty("target_block", goalBlockId != null ? goalBlockId : "none");
        bd.addProperty("tool", goalToolName != null ? goalToolName : "none");
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        bd.addProperty("state", state.name());
        return bd;
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Search for a block by registry ID within SEARCH_RANGE.
     * Brute-force scan of nearby blocks — suitable for rare targets (beehives).
     */
    private BlockPos findNearbyBlock(LocalPlayer player, String blockId) {
        String fullId = blockId.contains(":") ? blockId : "minecraft:" + blockId;
        Level world = player.level();
        BlockPos center = player.blockPosition();
        int range = (int) SEARCH_RANGE;

        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -range; x <= range; x++) {
            for (int y = -8; y <= 8; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState bs = world.getBlockState(pos);
                    String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
                    if (id.equals(fullId)) {
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

    /**
     * Check if there's a campfire (regular or soul) within 5 blocks below a beehive.
     */
    private boolean hasCampfireBelow(Level world, BlockPos hivePos) {
        for (int y = 1; y <= 5; y++) {
            BlockPos below = hivePos.below(y);
            BlockState bs = world.getBlockState(below);
            String id = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
            if (id.equals("minecraft:campfire") || id.equals("minecraft:soul_campfire")) {
                return true;
            }
        }
        return false;
    }
}
