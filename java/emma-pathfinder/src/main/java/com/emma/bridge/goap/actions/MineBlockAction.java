package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

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
    private String targetItemId = null;         // goal item ID (e.g., "minecraft:raw_iron")
    private BlockPos targetBlockPos = null;

    /** Tool requirement tracking — set in execute(), checked in tick() to detect breakage. */
    private String requiredToolSuffix = null;  // e.g., "_pickaxe"
    private int requiredToolTierIndex = -1;    // index into TIER_ORDER (-1 = hand-mineable)

    /** Unreachable block tracking — captured from WorldState each scoring tick. */
    private Map<BlockPos, Long> unreachableBlocksRef = null;
    private long currentTick = 0;

    @Override
    public String getName() {
        return "MineBlock";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
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
            int goalCount = goal.target.has("count") ? goal.target.get("count").getAsInt() : 1;

            // Skip if we already have enough
            if (state.isGoalItemSatisfied(itemId, goalCount)) continue;

            // Skip goals with an explicit non-MINE obtain method (e.g., CRAFT goals)
            if (goal.target.has("obtain_method")) {
                String method = goal.target.get("obtain_method").getAsString();
                if (!method.equals("MINE")) continue;
            }

            String[] blocks = itemToMineableBlocks(itemId, state.dimension);
            if (blocks == null) continue;

            // If item is only transitively mineable (crafted item like wooden_axe),
            // check if we already have the raw materials. Don't mine if crafting
            // should handle the rest of the chain.
            if (!goal.target.has("obtain_method") && !isDirectlyMineable(itemId, state.dimension)) {
                boolean haveRawMaterials = true;
                for (String block : blocks) {
                    String blockItem = block.contains(":") ? block : "minecraft:" + block;
                    if (!state.hasItem(blockItem, 1)) {
                        haveRawMaterials = false;
                        break;
                    }
                }
                if (haveRawMaterials) continue;
            }

            // Skip items where we don't CURRENTLY have the required tool
            if (!canMineWithCurrentTools(state, itemId)) continue;

            List<BlockPos> positions = new ArrayList<>();
            for (String block : blocks) {
                String fullBlock = block.contains(":") ? block : "minecraft:" + block;
                List<BlockPos> found = state.nearbyBlocks.get(fullBlock);
                if (found != null) positions.addAll(found);
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

            EmmaBridgeMod.LOGGER.debug("[GOAP MineBlock] Candidate goal='{}' item='{}' blocks={} nearest={} score={}",
                    goal.id, itemId, java.util.Arrays.toString(blocks),
                    nearestPos != null ? nearestPos.toShortString() : "?",
                    String.format("%.2f", score));

            if (score > bestScore) {
                bestScore = score;
                targetGoalId = goal.id;
                targetItemId = itemId;
                targetBlockPos = nearestPos;
                // Pick the nearest variant that has enough blocks to satisfy the goal
                int needed = goal.target.has("count") ? goal.target.get("count").getAsInt() : 1;
                targetBlock = pickBestVariant(state, blocks, needed);
            }
        }

        return bestScore;
    }

    @Override
    public void execute(Minecraft client) {
        if (targetBlock == null) return;

        // Record the tool requirement so tick() can detect breakage
        recordToolRequirement(targetItemId);

        // Extract the block name (e.g., "minecraft:iron_ore" -> "iron_ore")
        String blockName = targetBlock.contains(":") ? targetBlock.split(":")[1] : targetBlock;

        GoapNavHelper.mineProcess().mineByName(0, blockName);
        mining = true;
    }

    @Override
    public void tick(Minecraft client) {
        if (!mining) return;

        // Abort immediately if required tool broke
        if (requiredToolSuffix != null) {
            LocalPlayer player = client.player;
            if (player != null && !playerHasToolInInventory(player)) {
                EmmaBridgeMod.LOGGER.info("[GOAP MineBlock] Required tool broke (needed {}), aborting mine",
                        requiredToolSuffix);
                GoapNavHelper.cancelPathing();
                GoapNavHelper.mineProcess().cancel();
                mining = false;
                return;
            }
        }

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
        targetItemId = null;
        targetBlockPos = null;
        requiredToolSuffix = null;
        requiredToolTierIndex = -1;
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

    // -- Variant selection (proximity + sufficiency) ------------------------

    /**
     * Pick the best block variant to mine: prefer the nearest variant that has
     * at least {@code needed} blocks nearby. If no single variant has enough,
     * fall back to whichever variant has the closest individual block.
     */
    private static String pickBestVariant(WorldState state, String[] blocks, int needed) {
        String bestVariant = null;
        double bestAvgDist = Double.MAX_VALUE;
        // Fallback: nearest single block of any variant
        String fallbackVariant = null;
        double fallbackDist = Double.MAX_VALUE;

        for (String block : blocks) {
            String fullBlock = block.contains(":") ? block : "minecraft:" + block;
            List<String> variants = List.of(fullBlock);

            for (String variant : variants) {
                List<BlockPos> positions = state.nearbyBlocks.get(variant);
                if (positions == null || positions.isEmpty()) continue;

                // Sort positions by distance to player
                List<Double> dists = new ArrayList<>();
                for (BlockPos pos : positions) {
                    double dx = pos.getX() - state.posX;
                    double dy = pos.getY() - state.posY;
                    double dz = pos.getZ() - state.posZ;
                    dists.add(Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
                Collections.sort(dists);

                // Track fallback (nearest single block across all variants)
                if (dists.get(0) < fallbackDist) {
                    fallbackDist = dists.get(0);
                    fallbackVariant = variant;
                }

                // Only consider this variant if it has >= needed blocks
                if (positions.size() >= needed) {
                    // Average distance of the nearest `needed` blocks
                    double avg = 0;
                    for (int i = 0; i < needed; i++) avg += dists.get(i);
                    avg /= needed;

                    if (avg < bestAvgDist) {
                        bestAvgDist = avg;
                        bestVariant = variant;
                    }
                }
            }
        }

        return bestVariant != null ? bestVariant : fallbackVariant;
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
     * Check if an item has a direct MINE entry (not transitive).
     * Items like oak_log are directly mineable; wooden_axe is not (only transitively).
     */
    private static boolean isDirectlyMineable(String itemId, String currentDimension) {
        String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(id)) {
            if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
            if (!ItemRecipeRegistry.isDimensionMatch(entry.getDimension(), currentDimension)) continue;
            String[] mineBlocks = entry.getMineBlockNames();
            if (mineBlocks != null && mineBlocks.length > 0) return true;
        }
        return false;
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

    /**
     * Check if the player CURRENTLY has the tool needed to mine this item.
     * Mirrors recordToolRequirement() logic — checks per-block data first,
     * then top-level miningRequirement. Does NOT walk transitive deps.
     * This prevents scoring for cobblestone/stone when we have no pickaxe.
     */
    private static boolean canMineWithCurrentTools(WorldState state, String itemId) {
        String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;

        for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(id)) {
            if (entry.getObtainMethod() != ObtainMethod.MINE) continue;

            // Check per-block data first (preferred, has tool type info)
            ItemRecipeEntry.MineBlockInfo[] blocks = entry.getMineBlocks();
            if (blocks != null && blocks.length > 0) {
                for (ItemRecipeEntry.MineBlockInfo block : blocks) {
                    String blockReq = block.getRequirement();
                    if (blockReq == null || "HAND".equals(blockReq)) return true;
                    String toolType = block.getToolType();
                    if (toolType != null && hasToolTierOrBetter(state, toolType, blockReq)) {
                        return true;
                    }
                }
                return false; // no block mineable with current tools
            }

            // Fallback: top-level mining requirement
            String req = entry.getMiningRequirement();
            if (req == null || "HAND".equals(req)) return true;
            return hasToolTierOrBetter(state, "PICKAXE", req);
        }

        // No MINE entry for this item — it's resolved transitively by
        // itemToMineableBlocks(). Check the actual blocks that would be mined.
        String[] mineBlocks = itemToMineableBlocks(itemId, state.dimension);
        if (mineBlocks != null) {
            for (String block : mineBlocks) {
                String blockId = block.contains(":") ? block.split(":")[1] : block;
                for (ItemRecipeEntry bEntry : ItemRecipeRegistry.getEntries(blockId)) {
                    if (bEntry.getObtainMethod() != ObtainMethod.MINE) continue;
                    ItemRecipeEntry.MineBlockInfo[] bBlocks = bEntry.getMineBlocks();
                    if (bBlocks != null && bBlocks.length > 0) {
                        for (ItemRecipeEntry.MineBlockInfo bi : bBlocks) {
                            String bReq = bi.getRequirement();
                            if (bReq == null || "HAND".equals(bReq)) return true;
                            String toolType = bi.getToolType();
                            if (toolType != null && hasToolTierOrBetter(state, toolType, bReq)) {
                                return true;
                            }
                        }
                    } else {
                        String req = bEntry.getMiningRequirement();
                        if (req == null || "HAND".equals(req)) return true;
                        if (hasToolTierOrBetter(state, "PICKAXE", req)) return true;
                    }
                }
            }
            return false; // none of the target blocks are mineable
        }

        return true; // can't determine — don't block
    }

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
     * Walks the dependency chain: if the item itself isn't mined but its deps are,
     * checks tool requirements for those deps too (e.g., stone_pickaxe → cobblestone → WOOD pickaxe).
     */
    private static boolean hasRequiredTool(WorldState state, String itemId, Set<String> visited) {
        String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        if (!visited.add(id)) return true; // already checking — assume OK to break cycles

        boolean hasMineEntry = false;
        for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(id)) {
            if (entry.getObtainMethod() != ObtainMethod.MINE) continue;
            hasMineEntry = true;
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

        // No MINE entries for this item — check dependencies.
        // e.g., stone_pickaxe → cobblestone (MINE, requires WOOD pickaxe).
        // If any mineable dep requires a tool we lack, block this goal.
        if (!hasMineEntry) {
            Set<String> deps = ItemRecipeRegistry.getDependencies(id);
            for (String dep : deps) {
                if (!hasRequiredTool(state, "minecraft:" + dep, visited)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Check if the player's inventory contains a tool of the given type at or above the required tier.
     */
    private static boolean hasToolTierOrBetter(WorldState state, String toolType, String requiredTier) {
        String suffix = TOOL_SUFFIXES.get(toolType);
        if (suffix == null) return true; // unknown tool type — don't block

        int requiredIndex = TIER_ORDER.indexOf(requiredTier);
        if (requiredIndex < 0) return true; // unknown tier — don't block

        // Check both player inventory and endinv
        java.util.Set<String> allItems = new java.util.HashSet<>(state.playerInventory.keySet());
        allItems.addAll(state.endinvInventory.keySet());
        for (String invItem : allItems) {
            if (!invItem.endsWith(suffix)) continue;
            String cleanItem = invItem.contains(":") ? invItem.split(":")[1] : invItem;
            String tierPart = cleanItem.replace(suffix, "").toUpperCase();
            if (tierPart.equals("WOODEN")) tierPart = "WOOD";
            int itemIndex = TIER_ORDER.indexOf(tierPart);
            if (itemIndex >= requiredIndex) return true;
        }
        return false;
    }

    // -- Tool breakage detection -----------------------------------------------

    /**
     * Look up the tool requirement for the item we're about to mine and cache it
     * so tick() can detect when the tool breaks.
     */
    private void recordToolRequirement(String itemId) {
        requiredToolSuffix = null;
        requiredToolTierIndex = -1;

        if (itemId == null) return;
        String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;

        for (ItemRecipeEntry entry : ItemRecipeRegistry.getEntries(id)) {
            if (entry.getObtainMethod() != ObtainMethod.MINE) continue;

            // Check per-block data first
            ItemRecipeEntry.MineBlockInfo[] blocks = entry.getMineBlocks();
            if (blocks != null && blocks.length > 0) {
                ItemRecipeEntry.MineBlockInfo block = blocks[0];
                String req = block.getRequirement();
                if (req == null || req.equals("HAND")) return; // hand-mineable

                String toolType = block.getToolType();
                if (toolType == null) toolType = "PICKAXE";
                String suffix = TOOL_SUFFIXES.get(toolType);
                if (suffix == null) return;

                requiredToolSuffix = suffix;
                requiredToolTierIndex = TIER_ORDER.indexOf(req);
                return;
            }

            // Fallback: top-level mining requirement
            String req = entry.getMiningRequirement();
            if (req == null || req.equals("HAND")) return;

            String suffix = TOOL_SUFFIXES.get("PICKAXE");
            requiredToolSuffix = suffix;
            requiredToolTierIndex = TIER_ORDER.indexOf(req);
            return;
        }
    }

    /**
     * Quick check: does the player's inventory (hotbar + main) contain a tool
     * matching requiredToolSuffix at or above requiredToolTierIndex?
     */
    private boolean playerHasToolInInventory(LocalPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String itemName = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (!itemName.endsWith(requiredToolSuffix)) continue;

            // Check tier
            String tierPart = itemName.replace(requiredToolSuffix, "").toUpperCase();
            if (tierPart.equals("WOODEN")) tierPart = "WOOD";
            int tierIdx = TIER_ORDER.indexOf(tierPart);
            if (tierIdx >= requiredToolTierIndex) return true;
        }
        return false;
    }

}
