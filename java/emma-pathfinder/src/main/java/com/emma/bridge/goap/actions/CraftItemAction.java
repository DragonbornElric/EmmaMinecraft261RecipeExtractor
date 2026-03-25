package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.catalogue.ItemRecipeEntry;
import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.catalogue.ObtainMethod;
import com.emma.bridge.catalogue.RecipeBookLookup;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.InventoryScanner;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * GOAP Action: Craft an item using a crafting table (3x3) or player inventory (2x2).
 *
 * Scoring: Walks the transitive dependency chain for any have_item goal.
 *   Finds the first intermediate where we have all ingredients → crafts that.
 *   Example: goal=stone_axe, have logs+cobblestone → crafts planks first.
 *
 * Execution: Tick-based state machine:
 *   FIND_TABLE → NAVIGATE → OPEN → WAIT_SCREEN → PLACE_RECIPE → WAIT_RECIPE → EXTRACT → DONE
 *
 * Uses the vanilla Recipe Book API (handlePlaceRecipe) to fill the crafting grid.
 * The server auto-fills ingredients from player inventory + EndInv.
 * Zero EmmaClef dependencies.
 */
public class CraftItemAction extends GoapAction {

    // ── State machine ────────────────────────────────────────────
    private enum Phase {
        IDLE, FIND_TABLE, EQUIP_TABLE, PLACE_TABLE, NAVIGATE, OPEN, WAIT_SCREEN,
        PLACE_RECIPE, WAIT_RECIPE, EXTRACT, BREAK_TABLE, COLLECT_TABLE, DONE
    }

    private Phase phase = Phase.IDLE;
    private boolean crafting = false;
    private String targetGoalId = null;
    private String targetItem = null;          // the item we'll actually craft (may be intermediate)
    private ItemRecipeEntry targetRecipe = null;
    private RecipeDisplayId recipeDisplayId = null; // recipe book ID for handlePlaceRecipe

    private BlockPos tablePos = null;
    private boolean usePlayerGrid = false;     // true for 2x2 recipes
    private int waitTicks = 0;
    private int extractCount = 0;              // how many times we've extracted output

    // Batch crafting: precise count control via recipe manager
    private int targetGoalCount = 1;           // how many items the goal needs
    private int craftsRemaining = 0;           // crafts left after current batch
    private int recipePlaceCount = 0;          // handlePlaceRecipe calls this batch
    private int recipePlaceTarget = 0;         // total calls needed this batch
    private String originalGoalItem = null;    // top-level goal item for chain re-check

    // Sub-craft state: when we need to craft a crafting table first
    private boolean subCraftingTable = false;
    private String savedTargetItem = null;
    private ItemRecipeEntry savedTargetRecipe = null;
    private RecipeDisplayId savedRecipeDisplayId = null;

    // Cached WorldState and GoalSet from last scoring pass
    private WorldState cachedState = null;
    private GoalSet cachedGoals = null;

    // Break/collect tracking for placed tables
    private boolean placedTable = false;
    private int breakTicks = 0;
    private int collectTicks = 0;

    // Output slot is 0 for both CraftingMenu and InventoryMenu
    private static final int OUTPUT_SLOT = 0;

    @Override
    public String getName() {
        return "CraftItem";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return true;
    }

    // ── Scoring ──────────────────────────────────────────────────

    private float lastScore = 0;

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        cachedState = state;
        cachedGoals = goals;

        // While actively crafting, maintain previous score — ingredients may be
        // in the crafting grid (not visible to inventory checks)
        if (crafting && phase != Phase.IDLE) {
            return lastScore;
        }

        float bestScore = 0;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (goal.target == null || !goal.target.has("item")) continue;

            String goalItem = goal.target.get("item").getAsString();
            int goalCount = goal.target.has("count") ? goal.target.get("count").getAsInt() : 1;

            // Skip if we already have enough (or better equipped)
            if (state.isGoalItemSatisfied(goalItem, goalCount)) continue;

            // Find the first craftable item in the dependency chain
            String craftable = findCraftableInChain(state, goalItem, goals);
            if (craftable == null) continue;

            float score = goal.priority * 0.8f;
            if (score > bestScore) {
                bestScore = score;
                targetGoalId = goal.id;
                targetItem = craftable;
                targetGoalCount = goalCount;
            }
        }

        lastScore = bestScore;

        return bestScore;
    }

    /**
     * Walk decomposed goals to find the first craftable item in the dependency chain.
     * Uses the GoalDecomposer's output (which already resolved tag alternatives to
     * what the player has — e.g., spruce_planks instead of generic "planks").
     *
     * Collects all craft-type derived goals descending from the parent goal, sorts
     * them by priority (lowest first = raw materials), and returns the first one
     * where we have all ingredients + recipe book entry.
     *
     * Falls back to getTransitiveDependencies only if no derived goals exist.
     */
    private String findCraftableInChain(WorldState state, String goalItem, GoalSet goals) {
        boolean hasTableAccess = hasCraftingTableAccess(state);
        GoalSet activeGoals = goals != null ? goals : cachedGoals;

        // Collect craft-type derived goals that are descendants of goalItem's parent goal
        if (activeGoals != null) {
            // Find the parent goal ID for this goalItem
            String parentId = null;
            for (GoalSet.Goal g : activeGoals.getGoals()) {
                if (g.target != null && g.target.has("item")
                        && g.target.get("item").getAsString().equals(goalItem)) {
                    parentId = g.id;
                    break;
                }
            }

            if (parentId != null) {
                // Collect all craft-method derived goals under this parent, sorted by priority (ascending)
                List<GoalSet.Goal> craftGoals = new ArrayList<>();
                for (GoalSet.Goal dg : activeGoals.getDerivedGoals()) {
                    if (!parentId.equals(dg.parentGoalId)) continue;
                    if (dg.target == null || !dg.target.has("item")) continue;
                    if (!dg.target.has("obtain_method")) continue;
                    String method = dg.target.get("obtain_method").getAsString();
                    if (!method.startsWith("CRAFT_")) continue;
                    craftGoals.add(dg);
                }
                // Sort by priority ascending (lowest = most raw, should craft first)
                craftGoals.sort(Comparator.comparingDouble(g -> g.priority));

                for (GoalSet.Goal dg : craftGoals) {
                    String dgItem = dg.target.get("item").getAsString();
                    int dgCount = dg.target.has("count") ? dg.target.get("count").getAsInt() : 1;
                    if (state.isGoalItemSatisfied(dgItem, dgCount)) continue;

                    String dgId = dgItem.contains(":") ? dgItem.split(":")[1] : dgItem;
                    if (resolveRecipe(state, dgId, hasTableAccess)) {
                        return dgItem;
                    }
                }

                // Also check the goal item itself (it might be directly craftable)
                String goalId = goalItem.contains(":") ? goalItem.split(":")[1] : goalItem;
                if (!state.hasItem(goalItem, 1) && resolveRecipe(state, goalId, hasTableAccess)) {
                    return goalItem;
                }
            }
        }

        // Fallback: use generic transitive dependencies (no decomposed goals available)
        String goalId = goalItem.contains(":") ? goalItem.split(":")[1] : goalItem;
        List<String> deps = ItemRecipeRegistry.getTransitiveDependencies(goalId);
        deps.add(goalId);

        for (String itemId : deps) {
            String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
            if (state.hasItem(fullId, 1)) continue;
            if (resolveRecipe(state, itemId, hasTableAccess)) {
                return fullId;
            }
        }
        return null;
    }

    /**
     * Find a craftable recipe for itemId, verify ingredients, and look up the recipe book ID.
     * On success: saves targetRecipe + recipeDisplayId.
     * Returns false if no recipe found or recipe not in client recipe book (no fallback).
     */
    private boolean resolveRecipe(WorldState state, String itemId, boolean hasTableAccess) {
        String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);
        if (entries.isEmpty()) return false;

        for (ItemRecipeEntry entry : entries) {
            if (!entry.getObtainMethod().isCraftType()) continue;
            if (entry.getObtainMethod() != ObtainMethod.CRAFT_SHAPED_2x2 && !hasTableAccess) continue;

            String[][] grid = entry.getCraftGrid();
            if (grid == null) continue;

            // Check if we have all ingredients (resolve tag alternatives)
            Map<String, Integer> needed = new LinkedHashMap<>();
            for (String[] slotAlts : grid) {
                if (slotAlts == null || slotAlts.length == 0) continue;
                // Pick the first alternative the player actually has; fallback to slotAlts[0]
                String best = slotAlts[0];
                for (String alt : slotAlts) {
                    String full = alt.contains(":") ? alt : "minecraft:" + alt;
                    if (state.hasItem(full, 1)) { best = alt; break; }
                }
                String fullBest = best.contains(":") ? best : "minecraft:" + best;
                needed.merge(fullBest, 1, Integer::sum);
            }

            boolean hasAll = true;
            for (var ingredientEntry : needed.entrySet()) {
                if (!state.hasItem(ingredientEntry.getKey(), ingredientEntry.getValue())) {
                    hasAll = false;
                    break;
                }
            }
            if (hasAll) {
                // Only look up recipe book AFTER confirming ingredients match
                RecipeDisplayEntry bookEntry = RecipeBookLookup.findFirstCraftingRecipe(id);
                if (bookEntry == null) return false;
                targetRecipe = entry;
                recipeDisplayId = bookEntry.id();
                return true;
            }
        }
        return false;
    }

    // ── Execution ────────────────────────────────────────────────

    @Override
    public void execute(Minecraft client) {
        if (targetItem == null) return;

        // targetRecipe + recipeDisplayId were set by resolveRecipe() during scoring.
        // Fallback: if scoring didn't resolve (shouldn't happen), try to resolve now.
        if (recipeDisplayId == null) {
            String id = targetItem.contains(":") ? targetItem.split(":")[1] : targetItem;
            RecipeDisplayEntry bookEntry = RecipeBookLookup.findFirstCraftingRecipe(id);
            if (bookEntry != null) {
                recipeDisplayId = bookEntry.id();
            }
            if (targetRecipe == null) {
                List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);
                for (ItemRecipeEntry entry : entries) {
                    if (!entry.getObtainMethod().isCraftType()) continue;
                    targetRecipe = entry;
                    break;
                }
            }
        }

        if (recipeDisplayId == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] No recipe book entry for {}", targetItem);
            return;
        }

        if (targetRecipe == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] No craft recipe found for {}", targetItem);
            return;
        }

        usePlayerGrid = (targetRecipe.getObtainMethod() == ObtainMethod.CRAFT_SHAPED_2x2);
        crafting = true;
        extractCount = 0;
        waitTicks = 0;
        placedTable = false;
        breakTicks = 0;
        collectTicks = 0;
        craftsRemaining = 0;
        recipePlaceCount = 0;
        recipePlaceTarget = 0;
        // Save original goal item for chain re-check after each sub-craft
        if (originalGoalItem == null) {
            originalGoalItem = targetItem;
        }

        if (usePlayerGrid) {
            // 2x2 recipes use player inventory — just open inventory
            phase = Phase.OPEN;
        } else {
            phase = Phase.FIND_TABLE;
        }

        EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Starting craft: {} ({})",
                targetItem, usePlayerGrid ? "2x2 player grid" : "3x3 table");
    }

    @Override
    public void tick(Minecraft client) {
        if (!crafting || phase == Phase.IDLE) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        switch (phase) {
            case FIND_TABLE -> tickFindTable(client, player);
            case EQUIP_TABLE -> tickEquipTable(client, player);
            case PLACE_TABLE -> tickPlaceTable(client, player);
            case NAVIGATE -> tickNavigate(client, player);
            case OPEN -> tickOpen(client, player);
            case WAIT_SCREEN -> tickWaitScreen(client, player);
            case PLACE_RECIPE -> tickPlaceRecipe(client, player);
            case WAIT_RECIPE -> tickWaitRecipe(client, player);
            case EXTRACT -> tickExtract(client, player);
            case BREAK_TABLE -> tickBreakTable(client, player);
            case COLLECT_TABLE -> tickCollectTable(client, player);
            case DONE -> {
                ScreenHelper.closeIfOpen(client);
                // Crafting may unlock new recipes — rebuild cache so next scoring sees them
                RecipeBookLookup.invalidate();
                if (subCraftingTable) {
                    // We just crafted a crafting_table — now place it and resume the original craft
                    subCraftingTable = false;
                    targetItem = savedTargetItem;
                    targetRecipe = savedTargetRecipe;
                    recipeDisplayId = savedRecipeDisplayId;
                    savedTargetItem = null;
                    savedTargetRecipe = null;
                    savedRecipeDisplayId = null;
                    usePlayerGrid = false;
                    extractCount = 0;
                    waitTicks = 0;
                    phase = Phase.EQUIP_TABLE;
                    EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Crafting table crafted, placing and resuming {}",
                            targetItem);
                } else if (placedTable && tablePos != null) {
                    // Check if there's more to craft in the chain before breaking the table
                    String goalForChain = originalGoalItem != null ? originalGoalItem : targetItem;
                    boolean hasTableAccess = true; // we have the table placed right here
                    String nextCraftable = findCraftableInChain(cachedState, goalForChain, null);
                    if (nextCraftable != null) {
                        String nextId = nextCraftable.contains(":") ? nextCraftable.split(":")[1] : nextCraftable;
                        ItemRecipeEntry prevRecipe = targetRecipe;
                        RecipeDisplayId prevDisplayId = recipeDisplayId;
                        boolean nextNeedsTable = resolveRecipe(cachedState, nextId, true)
                                && targetRecipe != null
                                && targetRecipe.getObtainMethod() != ObtainMethod.CRAFT_SHAPED_2x2;
                        if (!nextNeedsTable) {
                            targetRecipe = prevRecipe;
                            recipeDisplayId = prevDisplayId;
                        }
                        if (nextNeedsTable) {
                            // Reuse the table for the next recipe in the chain
                            targetItem = nextCraftable;
                            extractCount = 0;
                            waitTicks = 0;
                            recipePlaceCount = 0;
                            recipePlaceTarget = 0;
                            phase = Phase.OPEN;
                            EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Reusing table for next in chain: {}", targetItem);
                            break;
                        }
                    }
                    // No more 3x3 crafting needed — recover the table
                    breakTicks = 0;
                    phase = Phase.BREAK_TABLE;
                } else {
                    crafting = false;
                    phase = Phase.IDLE;
                }
            }
            default -> {}
        }
    }

    // ── Phase handlers ───────────────────────────────────────────

    private void tickFindTable(Minecraft client, LocalPlayer player) {
        // Scan for nearby crafting table within 32 blocks
        BlockPos playerPos = player.blockPosition();
        Level world = player.level();

        for (int r = 1; r <= 32; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // only shell
                        BlockPos check = playerPos.offset(dx, dy, dz);
                        if (world.getBlockState(check).is(Blocks.CRAFTING_TABLE)) {
                            tablePos = check;
                            phase = Phase.NAVIGATE;
                            return;
                        }
                    }
                }
            }
        }

        // No table found — check if we have one to place
        if (hasItemInInventory(player, Items.CRAFTING_TABLE)) {
            phase = Phase.EQUIP_TABLE;
        } else if (com.emma.bridge.util.EndinvBridge.extractToSlot(Items.CRAFTING_TABLE,
                player.getInventory().getSelectedSlot())) {
            // Extracted from endinv for placement
            EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Extracted crafting table from endinv");
            phase = Phase.EQUIP_TABLE;
        } else if (canCraftTable(player, cachedState)) {
            // Inline sub-craft: craft a crafting table in the 2x2 player grid first
            startSubCraftTable();
        } else {
            EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] No crafting table and can't make one");
            crafting = false;
            phase = Phase.IDLE;
        }
    }

    /**
     * Start an inline sub-craft of a crafting table in the 2x2 player grid.
     * Uses the recipe book API — the server selects which planks to use.
     */
    private void startSubCraftTable() {
        RecipeDisplayEntry entry = RecipeBookLookup.findFirstCraftingRecipe("crafting_table");
        if (entry == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] crafting_table not in recipe book");
            crafting = false;
            phase = Phase.IDLE;
            return;
        }

        // Save current target
        savedTargetItem = targetItem;
        savedTargetRecipe = targetRecipe;
        savedRecipeDisplayId = recipeDisplayId;
        subCraftingTable = true;

        targetItem = "minecraft:crafting_table";
        targetRecipe = null; // not needed — recipe book handles everything
        recipeDisplayId = entry.id();
        usePlayerGrid = true;
        extractCount = 0;
        waitTicks = 0;
        phase = Phase.OPEN;

        EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Sub-crafting crafting_table before {}", savedTargetItem);
    }

    /** Equip crafting table and find placement spot — place happens next tick. */
    private BlockPos pendingPlaceOn = null;

    private void tickEquipTable(Minecraft client, LocalPlayer player) {
        // Find a placeable position near the player that doesn't overlap the player's body
        BlockPos below = player.blockPosition().below();
        pendingPlaceOn = null;
        tablePos = null;

        // Try cardinal directions first, checking player overlap
        for (BlockPos offset : new BlockPos[]{
                player.blockPosition().north(), player.blockPosition().south(),
                player.blockPosition().east(), player.blockPosition().west()}) {
            // Check horizontal distance to avoid placing where player overlaps
            double dx = (offset.getX() + 0.5) - player.getX();
            double dz = (offset.getZ() + 0.5) - player.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDist < 1.0) continue;  // too close, player would overlap

            if (player.level().getBlockState(offset).isAir()
                    && player.level().getBlockState(offset.below()).isSolid()) {
                pendingPlaceOn = offset.below();
                tablePos = offset;
                break;
            }
        }

        // If no cardinal worked, try diagonal positions (2 blocks out)
        if (tablePos == null) {
            for (BlockPos offset : new BlockPos[]{
                    player.blockPosition().north().east(), player.blockPosition().north().west(),
                    player.blockPosition().south().east(), player.blockPosition().south().west()}) {
                double dx = (offset.getX() + 0.5) - player.getX();
                double dz = (offset.getZ() + 0.5) - player.getZ();
                double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                if (horizontalDist < 1.0) continue;

                if (player.level().getBlockState(offset).isAir()
                        && player.level().getBlockState(offset.below()).isSolid()) {
                    pendingPlaceOn = offset.below();
                    tablePos = offset;
                    break;
                }
            }
        }

        // Last resort: place on player's own block (jump first to clear space)
        if (tablePos == null) {
            if (player.level().getBlockState(player.blockPosition()).isAir()) {
                tablePos = player.blockPosition();
                pendingPlaceOn = below;
                player.jumpFromGround();
                jumpedForPlacement = true;
                EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Jumping to place table at player position");
            } else {
                EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] No space to place crafting table");
                crafting = false;
                phase = Phase.IDLE;
                return;
            }
        }

        BlockInteraction.forceEquipItem(Items.CRAFTING_TABLE);
        waitTicks = 0;
        phase = Phase.PLACE_TABLE;  // place after 2-tick delay for server sync
    }

    private boolean jumpedForPlacement = false;

    private void tickPlaceTable(Minecraft client, LocalPlayer player) {
        // Wait for equip sync (2 ticks) + extra time if we jumped to clear player body (6 ticks)
        int minWait = jumpedForPlacement ? 6 : 2;
        if (++waitTicks < minWait) return;
        jumpedForPlacement = false;

        if (pendingPlaceOn == null || tablePos == null) {
            phase = Phase.FIND_TABLE;
            return;
        }

        BlockInteraction.lookAt(pendingPlaceOn);
        BlockInteraction.rightClickBlock(pendingPlaceOn, Direction.UP);

        EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Placed crafting table at {}", tablePos);
        placedTable = true;
        pendingPlaceOn = null;
        waitTicks = 0;
        phase = Phase.NAVIGATE;
    }

    private void tickNavigate(Minecraft client, LocalPlayer player) {
        switch (GoapNavHelper.tickNavigateToBlock(player, tablePos, ++waitTicks, 200, GoapNavHelper.CONTAINER_ARRIVAL_DIST)) {
            case NO_TARGET -> phase = Phase.FIND_TABLE;
            case ARRIVED -> { phase = Phase.OPEN; waitTicks = 0; }
            case TIMEOUT -> {
                EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] Navigation timeout to {}", tablePos);
                crafting = false;
                phase = Phase.IDLE;
            }
            case PATHING -> {}
        }
    }

    private void tickOpen(Minecraft client, LocalPlayer player) {
        if (usePlayerGrid) {
            // Open player inventory
            client.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(player));
            phase = Phase.WAIT_SCREEN;
            waitTicks = 0;
            return;
        }

        if (tablePos == null) {
            phase = Phase.FIND_TABLE;
            return;
        }

        BlockInteraction.lookAt(tablePos);
        BlockInteraction.rightClickBlock(tablePos);
        phase = Phase.WAIT_SCREEN;
        waitTicks = 0;
    }

    private void tickWaitScreen(Minecraft client, LocalPlayer player) {
        boolean open;
        if (usePlayerGrid) {
            open = player.containerMenu instanceof InventoryMenu;
        } else {
            open = player.containerMenu instanceof CraftingMenu;
        }

        if (open) {
            phase = Phase.PLACE_RECIPE;
            return;
        }

        if (++waitTicks > 40) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] Screen open timeout, retrying");
            phase = Phase.OPEN;
            waitTicks = 0;
        }
    }

    /**
     * Send the recipe book packet to auto-fill the crafting grid.
     * Calls handlePlaceRecipe(false) N times to queue exactly the right number of crafts.
     * Rate-limited to 1 call per 2 ticks (~10/sec) to avoid overwhelming the server.
     * Caps at 64 input items per batch; excess is handled via craftsRemaining.
     */
    private void tickPlaceRecipe(Minecraft client, LocalPlayer player) {
        if (recipeDisplayId == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] No RecipeDisplayId for {}", targetItem);
            phase = Phase.DONE;
            return;
        }

        var handler = player.containerMenu;
        if (handler == null) {
            phase = Phase.OPEN;
            return;
        }

        // First tick in this phase: calculate how many recipe placements we need
        if (recipePlaceTarget == 0) {
            int outputPerCraft = targetRecipe != null ? Math.max(1, targetRecipe.getCraftYield()) : 1;
            int inputsPerCraft = targetRecipe != null ? countNonNullSlots(targetRecipe.getCraftGrid()) : 1;
            int already = countTargetInInventory(player, targetItem);
            int stillNeeded = Math.max(1, targetGoalCount - already);
            int craftsNeeded = (int) Math.ceil((double) stillNeeded / outputPerCraft);
            int maxPerBatch = Math.max(1, 64 / Math.max(1, inputsPerCraft));
            recipePlaceTarget = Math.min(craftsNeeded, maxPerBatch);
            craftsRemaining = craftsNeeded - recipePlaceTarget;
            recipePlaceCount = 0;
            waitTicks = 0;
            EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Batch plan: {} crafts this batch, {} remaining (yield={}, need={})",
                    recipePlaceTarget, craftsRemaining, outputPerCraft, stillNeeded);
        }

        // Rate-limit: 1 call every 2 ticks (~10/sec)
        if (++waitTicks < 2) return;
        waitTicks = 0;

        client.gameMode.handlePlaceRecipe(handler.containerId, recipeDisplayId, false);
        recipePlaceCount++;

        if (recipePlaceCount >= recipePlaceTarget) {
            recipePlaceTarget = 0;  // reset for next batch
            phase = Phase.WAIT_RECIPE;
            waitTicks = 0;
        }
    }

    /**
     * Wait for the server to process the recipe placement and populate the output slot.
     * 40-tick timeout (2 seconds) — generous for server load + EndInv mixin processing.
     */
    private void tickWaitRecipe(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (handler == null) {
            phase = Phase.OPEN;
            return;
        }

        // Check if output slot has items (server filled the grid)
        if (OUTPUT_SLOT < handler.slots.size()) {
            var outputStack = handler.slots.get(OUTPUT_SLOT).getItem();
            if (!outputStack.isEmpty()) {
                phase = Phase.EXTRACT;
                waitTicks = 0;
                return;
            }
        }

        // Wait up to 40 ticks for server to process
        if (++waitTicks > 40) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] Recipe placement timeout for {}", targetItem);
            phase = Phase.DONE;
        }
    }

    private void tickExtract(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (handler == null) {
            phase = Phase.DONE;
            return;
        }

        // Check if output slot has items
        if (OUTPUT_SLOT < handler.slots.size()) {
            var outputStack = handler.slots.get(OUTPUT_SLOT).getItem();
            if (!outputStack.isEmpty()) {
                // Shift-click output to inventory (always works — EndInv ensures space)
                client.gameMode.handleContainerInput(
                        handler.containerId, OUTPUT_SLOT, 0, ContainerInput.QUICK_MOVE, player);
                extractCount++;
                EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Extracted {} (batch {})",
                        targetItem, extractCount);
                // Wait a tick for inventory update, then check if more output appeared
                waitTicks = 0;
                return;
            }
        }

        // Output is empty — check if more batches needed
        if (++waitTicks > 5) {
            if (craftsRemaining > 0) {
                // More batches to go — loop back to PLACE_RECIPE
                phase = Phase.PLACE_RECIPE;
                waitTicks = 0;
            } else {
                phase = Phase.DONE;
            }
        }
    }

    // ── Break & collect table ─────────────────────────────────────

    private void tickBreakTable(Minecraft client, LocalPlayer player) {
        if (tablePos == null) {
            crafting = false;
            phase = Phase.IDLE;
            return;
        }

        // Block already gone → collect
        if (!client.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
            collectTicks = 0;
            phase = Phase.COLLECT_TABLE;
            return;
        }

        // First tick: close crafting screen and kick off Emmatone mine process
        if (breakTicks == 0) {
            ScreenHelper.closeIfOpen(client);
            GoapNavHelper.mineProcess().mineByName(1, "crafting_table");
            EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Mining placed table at {}", tablePos);
        }
        breakTicks++;

        // Poll: mine process finished?
        if (!GoapNavHelper.mineProcess().isActive()) {
            collectTicks = 0;
            phase = Phase.COLLECT_TABLE;
        }
    }

    private void tickCollectTable(Minecraft client, LocalPlayer player) {
        collectTicks++;

        // Check if table is back in inventory (pickup may go to endinv)
        if (hasItemInInventory(player, Items.CRAFTING_TABLE)
                || (cachedState != null && cachedState.endinvInventory
                        .getOrDefault("minecraft:crafting_table", 0) > 0)) {
            crafting = false;
            phase = Phase.IDLE;
            return;
        }

        // Walk toward the drop
        if (collectTicks == 1 && !GoapNavHelper.isPathing()) {
            GoapNavHelper.pathTo(tablePos);
        }

        // Silent fail
        if (collectTicks > 60) {
            GoapNavHelper.cancelPathing();
            crafting = false;
            phase = Phase.IDLE;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private boolean hasItemInInventory(LocalPlayer player, net.minecraft.world.item.Item item) {
        return InventoryScanner.hasItem(player.getInventory(), item);
    }

    /** Check if we can access a crafting table for 3x3 recipes (scoring). */
    private boolean hasCraftingTableAccess(WorldState state) {
        // 1. Has a crafting table in any inventory scope
        if (state.hasItem("minecraft:crafting_table", 1)) return true;
        // 2. One is placed nearby
        if (state.nearbyBlocks.containsKey("minecraft:crafting_table")) return true;
        // 3. Has 4+ planks (or log-equivalent) to craft one (2x2 recipe, no table needed)
        int planks = 0;
        for (var entry : state.playerInventory.entrySet()) {
            String key = entry.getKey();
            if (key.contains("planks")) planks += entry.getValue();
            else if (key.contains("_log") || key.contains("_wood")
                    || key.equals("minecraft:bamboo_block")) {
                planks += entry.getValue() * 4;  // each log → 4 planks via 2x2
            }
        }
        for (var entry : state.endinvInventory.entrySet()) {
            String key = entry.getKey();
            if (key.contains("planks")) planks += entry.getValue();
            else if (key.contains("_log") || key.contains("_wood")
                    || key.equals("minecraft:bamboo_block")) {
                planks += entry.getValue() * 4;
            }
        }
        return planks >= 4;
    }

    /** Count non-null slots in a craft grid (= number of input items per craft). */
    private static int countNonNullSlots(String[][] grid) {
        if (grid == null) return 1;
        int count = 0;
        for (String[] slot : grid) {
            if (slot != null && slot.length > 0) count++;
        }
        return Math.max(1, count);
    }

    /** Count how many of targetItem the player currently has in inventory + endinv. */
    private int countTargetInInventory(LocalPlayer player, String targetItem) {
        if (targetItem == null || cachedState == null) return 0;
        return cachedState.totalItemCount(targetItem);
    }

    private boolean canCraftTable(LocalPlayer player, WorldState state) {
        // Need 4 planks of any type — check player inv + endinv
        int plankCount = InventoryScanner.countItems(player.getInventory(),
                stack -> net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString().contains("planks"));
        if (state != null) {
            for (var entry : state.endinvInventory.entrySet()) {
                if (entry.getKey().contains("planks")) plankCount += entry.getValue();
            }
        }
        return plankCount >= 4;
    }

    // ── Lifecycle ────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        ScreenHelper.closeIfOpen(client);

        // Cancel mine process or navigation
        if (phase == Phase.BREAK_TABLE) {
            GoapNavHelper.mineProcess().cancel();
        }
        if (phase == Phase.NAVIGATE || phase == Phase.COLLECT_TABLE) {
            GoapNavHelper.cancelPathing();
        }

        crafting = false;
        phase = Phase.IDLE;
        targetGoalId = null;
        targetItem = null;
        targetRecipe = null;
        recipeDisplayId = null;
        cachedGoals = null;
        tablePos = null;
        usePlayerGrid = false;
        subCraftingTable = false;
        savedTargetItem = null;
        savedTargetRecipe = null;
        savedRecipeDisplayId = null;
        placedTable = false;
        jumpedForPlacement = false;
        breakTicks = 0;
        collectTicks = 0;
        targetGoalCount = 1;
        craftsRemaining = 0;
        recipePlaceCount = 0;
        recipePlaceTarget = 0;
        originalGoalItem = null;
    }

    @Override
    public boolean isActive() {
        return crafting;
    }

    @Override
    public String getPrimaryGoalId() {
        return targetGoalId;
    }

    @Override
    public float relevanceToGoal(WorldState state, GoalSet.Goal goal) {
        if (targetItem == null) return 0.0f;
        if (goal.target == null || !goal.target.has("item")) return 0.0f;

        String goalItemId = goal.target.get("item").getAsString();
        String goalId = goalItemId.contains(":") ? goalItemId.split(":")[1] : goalItemId;
        String craftId = targetItem.contains(":") ? targetItem.split(":")[1] : targetItem;

        // Only count direct craft dependencies, not transitive.
        // Prevents inflated collateral from 121 derived goals — crafting one
        // intermediate doesn't simultaneously advance ALL goals.
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(goalId);
        for (ItemRecipeEntry entry : entries) {
            if (!entry.getObtainMethod().isCraftType()) continue;
            String[][] grid = entry.getCraftGrid();
            if (grid == null) continue;
            for (String[] slotAlts : grid) {
                if (slotAlts != null) {
                    for (String alt : slotAlts) {
                        String altId = alt.contains(":") ? alt.split(":")[1] : alt;
                        if (altId.equals(craftId)) return 0.3f;
                    }
                }
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
        bd.addProperty("crafting", crafting);
        bd.addProperty("phase", phase.name());
        bd.addProperty("target_item", targetItem != null ? targetItem : "none");
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        bd.addProperty("use_player_grid", usePlayerGrid);
        if (targetRecipe != null) {
            bd.addProperty("recipe_method", targetRecipe.getObtainMethod().name());
        }
        if (recipeDisplayId != null) {
            bd.addProperty("recipe_display_id", recipeDisplayId.index());
        }
        return bd;
    }
}
