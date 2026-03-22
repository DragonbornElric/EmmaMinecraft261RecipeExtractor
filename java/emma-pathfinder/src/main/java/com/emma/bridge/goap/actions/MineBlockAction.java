package com.emma.bridge.goap.actions;

import com.emma.bridge.catalogue.ItemRecipeEntry;
import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.catalogue.ObtainMethod;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * GOAP Action: Mine nearby resource blocks using Emmatone.
 *
 * Preconditions: a dynamic goal requests an item obtainable by mining, AND
 *                the relevant ore/block type is in nearbyBlocks
 * Score: goal priority x proximity to nearest block of that type
 *
 * Delegates to Emmatone's mine process (#mine).
 * Score spikes when walking past ore you need (opportunistic pickup).
 */
public class MineBlockAction extends GoapAction {

    private boolean mining = false;
    private String targetGoalId = null;
    private String targetBlock = null;
    private BlockPos targetBlockPos = null;

    /** Unreachable block tracking — captured from WorldState each scoring tick. */
    private Map<BlockPos, Long> unreachableBlocksRef = null;
    private long currentTick = 0;

    @Override
    public String getName() {
        return "MineBlock";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        // Need nearby blocks to mine -- populated by external block scanner
        return !state.nearbyBlocks.isEmpty();
    }

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        // Capture unreachable block refs for nav failure marking
        unreachableBlocksRef = state.unreachableBlocks;
        currentTick = state.tick;

        // Find a goal that wants a mineable resource
        float bestScore = 0;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (goal.target == null || !goal.target.has("item")) continue;

            String itemId = goal.target.get("item").getAsString();
            String[] blocks = itemToMineableBlocks(itemId, state.dimension);
            if (blocks == null) continue;

            // Skip items where we don't have the required tool tier
            if (!hasRequiredTool(state, itemId)) continue;

            // Collect nearby positions from ALL mineable block types + tag equivalents
            List<BlockPos> positions = new ArrayList<>();
            for (String block : blocks) {
                positions.addAll(TagGroups.findNearbyWithTagEquivalents(state, block));
            }
            if (positions.isEmpty()) continue;

            // Find nearest block
            double nearestDist = Double.MAX_VALUE;
            BlockPos nearestPos = null;
            for (BlockPos pos : positions) {
                double dx = pos.getX() - state.posX;
                double dy = pos.getY() - state.posY;
                double dz = pos.getZ() - state.posZ;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestPos = pos;
                }
            }

            float proximityFactor = 1.0f / (1.0f + (float) nearestDist / 16.0f);
            float score = goal.priority * proximityFactor;

            if (score > bestScore) {
                bestScore = score;
                targetGoalId = goal.id;
                targetBlockPos = nearestPos;
                // Pick the most abundant nearby variant across all mineable block types
                String bestBlock = null;
                int bestCount = 0;
                for (String block : blocks) {
                    String variant = TagGroups.findBestNearbyVariant(state, block);
                    if (variant != null) {
                        List<BlockPos> varPositions = TagGroups.findNearbyWithTagEquivalents(state, variant);
                        if (varPositions.size() > bestCount) {
                            bestCount = varPositions.size();
                            bestBlock = variant;
                        }
                    }
                }
                targetBlock = bestBlock;
            }
        }

        return bestScore;
    }

    @Override
    public void execute(Minecraft client) {
        if (targetBlock == null) return;

        // Extract the block name (e.g., "minecraft:iron_ore" -> "iron_ore")
        String blockName = targetBlock.contains(":") ? targetBlock.split(":")[1] : targetBlock;

        GoapNavHelper.mineProcess().mineByName(0, blockName);
        mining = true;
    }

    @Override
    public void tick(Minecraft client) {
        if (!mining) return;

        // Check if Emmatone mine process is still active
        boolean active = GoapNavHelper.mineProcess().isActive();
        if (!active) {
            // Mine process ended — if the block still exists, it's unreachable
            if (targetBlockPos != null && unreachableBlocksRef != null && client.level != null) {
                var blockState = client.level.getBlockState(targetBlockPos);
                if (!blockState.isAir()) {
                    unreachableBlocksRef.put(targetBlockPos, currentTick);
                }
            }
            mining = false;
        }
    }

    @Override
    public void onDeactivated(Minecraft client) {
        if (mining) {
            GoapNavHelper.cancelPathing();
            mining = false;
        }
        targetGoalId = null;
        targetBlock = null;
        targetBlockPos = null;
    }

    @Override
    public boolean isActive() {
        return mining;
    }

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        // Mining iron is a prerequisite for many goals (tools, armor)
        if (goal.target != null && goal.target.has("item")) {
            String itemId = goal.target.get("item").getAsString();
            if (targetBlock != null && isPrerequisiteFor(targetBlock, itemId)) {
                return 0.5f;  // indirect prerequisite
            }
        }
        return 0.0f;
    }

    @Override
    public String personalityCategory() {
        return "resource_hoarding";
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("mining", mining);
        bd.addProperty("target_block", targetBlock != null ? targetBlock : "none");
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        bd.addProperty("nearby_block_types", state.nearbyBlocks.size());
        return bd;
    }

    // -- Resource mappings (data-driven via ItemRecipeRegistry) ------------

    /**
     * Map an item ID to ALL block types that drop it when mined.
     * Queries ItemRecipeRegistry for MINE entries and returns the full mineBlocks array.
     */
    private static String[] itemToMineableBlocks(String itemId, String currentDimension) {
        String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);

        for (ItemRecipeEntry entry : entries) {
            if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
            // Skip entries that require a different dimension
            if (!ItemRecipeRegistry.isDimensionMatch(entry.getDimension(), currentDimension)) continue;
            String[] mineBlocks = entry.getMineBlockNames();
            if (mineBlocks != null && mineBlocks.length > 0) {
                return mineBlocks;
            }
        }

        // Also check if item is a transitive dependency of something mineable
        // e.g., iron_ingot -> raw_iron (MINE) -> iron_ore
        Set<String> deps = ItemRecipeRegistry.getDependencies(id);
        for (String dep : deps) {
            String[] blocks = itemToMineableBlocks("minecraft:" + dep, currentDimension);
            if (blocks != null) return blocks;
        }

        return null;
    }

    /**
     * Check if mining blockType is a prerequisite for crafting targetItem.
     * Uses ItemRecipeRegistry's transitive dependency walker.
     */
    private static boolean isPrerequisiteFor(String blockType, String targetItem) {
        String targetId = targetItem.contains(":") ? targetItem.split(":")[1] : targetItem;
        List<String> transDeps = ItemRecipeRegistry.getTransitiveDependencies(targetId);

        // Check if any transitive dependency is obtained by mining blockType
        for (String dep : transDeps) {
            List<ItemRecipeEntry> depEntries = ItemRecipeRegistry.getEntries(dep);
            for (ItemRecipeEntry entry : depEntries) {
                if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
                String[] mineBlocks = entry.getMineBlockNames();
                if (mineBlocks == null) continue;
                for (String block : mineBlocks) {
                    if (block.equals(blockType)) return true;
                }
            }
        }
        return false;
    }

    // -- Tool requirement checking -----------------------------------------

    /** Tool tier hierarchy — higher index = better tier. */
    private static final List<String> TIER_ORDER = List.of("WOOD", "STONE", "IRON", "DIAMOND", "NETHERITE");

    /** Tool type prefixes — maps toolType to inventory item name patterns. */
    private static final Map<String, String> TOOL_SUFFIXES = Map.of(
            "PICKAXE", "_pickaxe",
            "AXE", "_axe",
            "SHOVEL", "_shovel",
            "HOE", "_hoe"
    );

    /**
     * Check if the player has a tool that meets the mining requirement for this item.
     * Returns true if no requirement (hand-mineable) or player has sufficient tool.
     */
    private static boolean hasRequiredTool(WorldState state, String itemId) {
        String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;

        for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(id)) {
            if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
            String req = entry.getMiningRequirement();
            if (req == null) return true; // hand-mineable

            // Check per-block data for the best (easiest) tool type + requirement
            ItemRecipeEntry.MineBlockInfo[] blocks = entry.getMineBlocks();
            if (blocks != null) {
                for (ItemRecipeEntry.MineBlockInfo block : blocks) {
                    String blockReq = block.getRequirement();
                    if (blockReq == null) return true; // at least one block is hand-mineable
                    String toolType = block.getToolType();
                    if (toolType != null && hasToolTierOrBetter(state, toolType, blockReq)) {
                        return true;
                    }
                }
                return false; // no block is mineable with current tools
            }

            // Fallback: use overall miningRequirement (old data format)
            return hasToolTierOrBetter(state, "PICKAXE", req);
        }

        return true; // no MINE entry found — don't block
    }

    /**
     * Check if the player's inventory contains a tool of the given type at or above the required tier.
     */
    private static boolean hasToolTierOrBetter(WorldState state, String toolType, String requiredTier) {
        String suffix = TOOL_SUFFIXES.get(toolType);
        if (suffix == null) return true; // unknown tool type — don't block

        int requiredIndex = TIER_ORDER.indexOf(requiredTier);
        if (requiredIndex < 0) return true; // unknown tier — don't block

        for (String invItem : state.playerInventory.keySet()) {
            if (!invItem.endsWith(suffix)) continue;
            // Extract tier from item name: "minecraft:iron_pickaxe" → "IRON"
            String cleanItem = invItem.contains(":") ? invItem.split(":")[1] : invItem;
            String tierPart = cleanItem.replace(suffix, "").toUpperCase();
            // wooden_pickaxe → "WOODEN" but our tier is "WOOD"
            if (tierPart.equals("WOODEN")) tierPart = "WOOD";
            int itemIndex = TIER_ORDER.indexOf(tierPart);
            if (itemIndex >= requiredIndex) return true;
        }
        return false;
    }

}
