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
import net.minecraft.core.registries.BuiltInRegistries;
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

    // Manual crafting fallback: when recipe book lookup fails, fill grid slots directly
    private boolean useManualCraft = false;
    private List<ManualSlotOp> manualOps = null;
    private int manualOpIndex = 0;
    private int manualExtractWaitTicks = 0;

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

    /** Immutable scoring result for one craftable target candidate. */
    private record RecipeResolution(
            String itemId,
            ItemRecipeEntry recipe,
            RecipeDisplayId recipeDisplayId,
            boolean useManualCraft
    ) {}

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        cachedState = state;
        cachedGoals = goals;

        // While actively crafting, maintain previous score — ingredients may be
        // in the crafting grid (not visible to inventory checks)
        if (crafting && phase != Phase.IDLE) {
            return lastScore;
        }

        clearSelectedCraft();

        float bestScore = 0;
        RecipeResolution bestResolution = null;
        String bestGoalId = null;
        int bestGoalCount = 1;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (goal.target == null || !goal.target.has("item")) continue;

            String goalItem = goal.target.get("item").getAsString();
            int goalCount = goal.target.has("count") ? goal.target.get("count").getAsInt() : 1;

            // Skip if we already have enough (or better equipped)
            if (state.isGoalItemSatisfied(goalItem, goalCount)) continue;

            // Find the first craftable item in the dependency chain
            RecipeResolution resolution = findCraftableInChain(state, goalItem, goals);
            if (resolution == null) continue;

            float score = goal.priority * 0.8f;
            if (score > bestScore) {
                bestScore = score;
                bestResolution = resolution;
                bestGoalId = goal.id;
                bestGoalCount = goalCount;
            }
        }

        if (bestResolution != null) {
            applySelectedCraft(bestGoalId, bestGoalCount, bestResolution);
        }

        lastScore = bestScore;

        return bestScore;
    }

    private void clearSelectedCraft() {
        targetGoalId = null;
        targetItem = null;
        targetRecipe = null;
        recipeDisplayId = null;
        useManualCraft = false;
        targetGoalCount = 1;
    }

    private void applySelectedCraft(String goalId, int goalCount, RecipeResolution resolution) {
        targetGoalId = goalId;
        targetGoalCount = goalCount;
        targetItem = resolution.itemId();
        targetRecipe = resolution.recipe();
        recipeDisplayId = resolution.recipeDisplayId();
        useManualCraft = resolution.useManualCraft();
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
    private RecipeResolution findCraftableInChain(WorldState state, String goalItem, GoalSet goals) {
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

                    RecipeResolution resolution = resolveRecipe(state, dgItem, hasTableAccess);
                    if (resolution != null) {
                        return resolution;
                    }
                }

                // Also check the goal item itself (it might be directly craftable)
                if (!state.isGoalItemSatisfied(goalItem, 1)) {
                    RecipeResolution resolution = resolveRecipe(state, goalItem, hasTableAccess);
                    if (resolution != null) {
                        return resolution;
                    }
                }
            }
        }

        // Fallback: use generic transitive dependencies (no decomposed goals available)
        String goalId = goalItem.contains(":") ? goalItem.split(":")[1] : goalItem;
        List<String> deps = ItemRecipeRegistry.getTransitiveDependencies(goalId);
        deps.add(goalId);

        for (String itemId : deps) {
            String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
            if (state.isGoalItemSatisfied(fullId, 1)) continue;
            RecipeResolution resolution = resolveRecipe(state, itemId, hasTableAccess);
            if (resolution != null) {
                return resolution;
            }
        }
        return null;
    }

    /**
     * Find a craftable recipe for itemId, verify ingredients, and return an isolated
     * recipe resolution for scoring. This must not mutate the action's selected
     * target while candidate goals are still competing.
     */
    private RecipeResolution resolveRecipe(WorldState state, String itemId, boolean hasTableAccess) {
        String fullItemId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);
        if (entries.isEmpty()) return null;

        for (ItemRecipeEntry entry : entries) {
            if (!entry.getObtainMethod().isCraftType()) continue;
            if (recipeRequiresCraftingTable(entry) && !hasTableAccess) continue;

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
                // Try recipe book first (server-side auto-fill, handles EndInv)
                RecipeDisplayEntry bookEntry = RecipeBookLookup.findFirstCraftingRecipe(id);
                if (bookEntry != null) {
                    return new RecipeResolution(fullItemId, entry, bookEntry.id(), false);
                }
                // Fallback: manual grid filling — we have ingredients, just no recipe book entry
                EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Recipe book missing for '{}', will use manual craft", id);
                return new RecipeResolution(fullItemId, entry, null, true);
            }
        }
        return null;
    }

    // ── Execution ────────────────────────────────────────────────

    @Override
    public void execute(Minecraft client) {
        if (targetItem == null) return;

        String targetId = targetItem.contains(":") ? targetItem.split(":")[1] : targetItem;
        if (targetRecipe != null && !targetId.equals(targetRecipe.getItemId())) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] Clearing stale recipe state: target={} recipe={}",
                    targetItem, targetRecipe.getItemId());
            targetRecipe = null;
            recipeDisplayId = null;
            useManualCraft = false;
        }

        // targetRecipe + recipeDisplayId were set by resolveRecipe() during scoring.
        // Fallback: if scoring didn't resolve (shouldn't happen), try to resolve now.
        if (targetRecipe == null) {
            List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(targetId);
            for (ItemRecipeEntry entry : entries) {
                if (!entry.getObtainMethod().isCraftType()) continue;
                targetRecipe = entry;
                break;
            }
        }
        if (recipeDisplayId == null && !useManualCraft) {
            RecipeDisplayEntry bookEntry = RecipeBookLookup.findFirstCraftingRecipe(targetId);
            if (bookEntry != null) {
                recipeDisplayId = bookEntry.id();
            } else {
                // Fall back to manual craft
                useManualCraft = true;
                EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] No recipe book entry for {}, using manual craft", targetItem);
            }
        }

        if (targetRecipe == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] No craft recipe found for {}", targetItem);
            return;
        }

        usePlayerGrid = recipeUsesPlayerGrid(targetRecipe);
        crafting = true;
        extractCount = 0;
        waitTicks = 0;
        manualExtractWaitTicks = 0;
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
                    RecipeResolution nextResolution = findCraftableInChain(cachedState, goalForChain, null);
                    if (nextResolution != null
                            && nextResolution.recipe() != null
                            && recipeRequiresCraftingTable(nextResolution.recipe())) {
                        // Reuse the table for the next 3x3 recipe in the chain.
                        targetItem = nextResolution.itemId();
                        targetRecipe = nextResolution.recipe();
                        recipeDisplayId = nextResolution.recipeDisplayId();
                        useManualCraft = nextResolution.useManualCraft();
                        extractCount = 0;
                        waitTicks = 0;
                        recipePlaceCount = 0;
                        recipePlaceTarget = 0;
                        phase = Phase.OPEN;
                        EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Reusing table for next in chain: {}", targetItem);
                        break;
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
     * Uses the recipe book API if available, otherwise falls back to manual craft.
     */
    private void startSubCraftTable() {
        RecipeDisplayEntry entry = RecipeBookLookup.findFirstCraftingRecipe("crafting_table");

        // Save current target
        savedTargetItem = targetItem;
        savedTargetRecipe = targetRecipe;
        savedRecipeDisplayId = recipeDisplayId;
        subCraftingTable = true;

        targetItem = "minecraft:crafting_table";
        usePlayerGrid = true;
        extractCount = 0;
        waitTicks = 0;

        if (entry != null) {
            targetRecipe = null; // not needed — recipe book handles everything
            recipeDisplayId = entry.id();
            useManualCraft = false;
        } else {
            // Manual fallback for crafting table
            EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] crafting_table not in recipe book, using manual craft");
            String id = "crafting_table";
            List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);
            targetRecipe = null;
            for (ItemRecipeEntry e : entries) {
                if (e.getObtainMethod().isCraftType()) {
                    targetRecipe = e;
                    break;
                }
            }
            recipeDisplayId = null;
            useManualCraft = true;
        }

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
     * Send the recipe book packet to auto-fill the crafting grid, or manually fill
     * slots if the recipe book is unavailable.
     *
     * Recipe book path: Calls handlePlaceRecipe(false) N times.
     * Manual path: Picks up items from inventory and places them into grid slots.
     */
    private void tickPlaceRecipe(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (handler == null) {
            phase = Phase.OPEN;
            return;
        }

        if (useManualCraft) {
            tickManualPlaceRecipe(client, player);
            return;
        }

        if (recipeDisplayId == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] No RecipeDisplayId for {}", targetItem);
            phase = Phase.DONE;
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
     * Manual grid filling: place items from inventory into crafting grid slots one by one.
     * Uses PICKUP clicks to move items precisely into the correct grid positions.
     * Processes one click operation per tick to avoid server desync.
     */
    private void tickManualPlaceRecipe(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (handler == null) {
            phase = Phase.OPEN;
            return;
        }

        if (manualExtractWaitTicks > 0) {
            manualExtractWaitTicks--;
            return;
        }

        // Build operation queue on first tick
        if (manualOps == null) {
            if (!ensureManualIngredientsInInventory(player)) {
                return;
            }
            manualOps = buildManualOps(handler, player);
            manualOpIndex = 0;
            if (manualOps.isEmpty()) {
                EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] Manual craft: no slot operations generated for {}", targetItem);
                phase = Phase.DONE;
                return;
            }
            EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Manual craft: {} slot operations for {}", manualOps.size(), targetItem);
        }

        // Process one operation per tick
        if (manualOpIndex < manualOps.size()) {
            ManualSlotOp op = manualOps.get(manualOpIndex);
            client.gameMode.handleContainerInput(
                    handler.containerId, op.slot, op.button, ContainerInput.PICKUP, player);
            manualOpIndex++;
        }

        // All operations done — advance to wait for output
        if (manualOpIndex >= manualOps.size()) {
            manualOps = null;
            manualOpIndex = 0;
            phase = Phase.WAIT_RECIPE;
            waitTicks = 0;
        }
    }

    /**
     * Build the list of click operations to fill the crafting grid manually.
     * For each grid slot with an ingredient:
     *   1. Left-click source inventory slot (pick up stack)
     *   2. Right-click target grid slot (place exactly 1)
     *   3. Left-click source slot again (put rest of stack back)
     *
     * Same source slot can be reused for multiple grid slots (e.g., 3 planks
     * from one stack) — the stack is returned after each placement.
     */
    private List<ManualSlotOp> buildManualOps(net.minecraft.world.inventory.AbstractContainerMenu handler, LocalPlayer player) {
        List<ManualSlotOp> ops = new ArrayList<>();
        if (targetRecipe == null) return ops;

        String[][] grid = targetRecipe.getCraftGrid();
        if (grid == null) return ops;

        // Determine grid slot offset in the container
        // CraftingMenu: grid slots 1-9, inventory 10-45
        // InventoryMenu: grid slots 1-4, inventory 9-44
        int gridSlotOffset = 1; // both start at slot 1

        // Determine inventory slot range
        int invStart, invEnd;
        if (handler instanceof CraftingMenu) {
            invStart = 10;
            invEnd = 45;
        } else {
            // InventoryMenu — slots 9-44 are main inventory + hotbar
            invStart = 9;
            invEnd = 44;
        }

        // Track how many items we've "consumed" from each slot (for count validation)
        Map<Integer, Integer> slotUsage = new HashMap<>();

        for (int i = 0; i < grid.length; i++) {
            String[] slotAlts = grid[i];
            if (slotAlts == null || slotAlts.length == 0) continue;

            int gridSlot = gridSlotOffset + i;

            // Find the ingredient in inventory slots, accounting for prior usage
            int sourceSlot = findIngredientSlot(handler, slotAlts, invStart, invEnd, slotUsage);
            if (sourceSlot == -1) {
                EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] Manual craft: can't find {} in inventory for grid slot {}",
                        Arrays.toString(slotAlts), i);
                continue;
            }
            slotUsage.merge(sourceSlot, 1, Integer::sum);

            // 3-click sequence: pick up → place 1 → put back
            ops.add(new ManualSlotOp(sourceSlot, 0));  // left-click: pick up stack
            ops.add(new ManualSlotOp(gridSlot, 1));     // right-click: place 1 item
            ops.add(new ManualSlotOp(sourceSlot, 0));   // left-click: put rest back
        }

        return ops;
    }

    /**
     * Find an inventory slot containing one of the alternative items, with enough
     * remaining count after prior usage from the same slot.
     */
    private int findIngredientSlot(net.minecraft.world.inventory.AbstractContainerMenu handler,
                                    String[] alternatives, int invStart, int invEnd,
                                    Map<Integer, Integer> slotUsage) {
        for (int slot = invStart; slot <= invEnd && slot < handler.slots.size(); slot++) {
            var stack = handler.slots.get(slot).getItem();
            if (stack.isEmpty()) continue;
            // Check if this slot has enough items left after prior allocations
            int used = slotUsage.getOrDefault(slot, 0);
            if (stack.getCount() <= used) continue;

            String stackId = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            for (String alt : alternatives) {
                String altId = alt.contains(":") ? alt.split(":")[1] : alt;
                if (stackId.equals(altId)) return slot;
            }
        }
        return -1;
    }

    private boolean ensureManualIngredientsInInventory(LocalPlayer player) {
        Map<String, Integer> needed = computeManualIngredientNeeds(player);
        if (needed.isEmpty()) return true;

        for (var entry : needed.entrySet()) {
            String itemId = entry.getKey();
            int required = entry.getValue();
            int inInventory = countInventoryItem(player, itemId);
            if (inInventory >= required) continue;

            if (com.emma.bridge.util.EndinvBridge.getCount(itemId) <= 0) continue;

            int hotbarSlot = findEmptyHotbarSlot(player);
            if (hotbarSlot < 0) {
                EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] Manual craft: no empty hotbar slot to extract {}", itemId);
                return true;
            }

            if (com.emma.bridge.util.EndinvBridge.extractToSlot(itemId, hotbarSlot)) {
                manualExtractWaitTicks = 2;
                EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Manual craft: extracting {} from endinv", itemId);
                return false;
            }
        }

        return true;
    }

    private Map<String, Integer> computeManualIngredientNeeds(LocalPlayer player) {
        Map<String, Integer> needed = new LinkedHashMap<>();
        if (targetRecipe == null) return needed;

        String[][] grid = targetRecipe.getCraftGrid();
        if (grid == null) return needed;

        for (String[] slotAlts : grid) {
            if (slotAlts == null || slotAlts.length == 0) continue;
            String best = chooseManualIngredient(slotAlts, player, needed);
            needed.merge(best, 1, Integer::sum);
        }

        return needed;
    }

    private String chooseManualIngredient(String[] slotAlts, LocalPlayer player, Map<String, Integer> alreadyNeeded) {
        String fallback = slotAlts[0].contains(":") ? slotAlts[0] : "minecraft:" + slotAlts[0];

        for (String alt : slotAlts) {
            String full = alt.contains(":") ? alt : "minecraft:" + alt;
            int reserved = alreadyNeeded.getOrDefault(full, 0);
            if (countInventoryItem(player, full) > reserved) {
                return full;
            }
        }

        for (String alt : slotAlts) {
            String full = alt.contains(":") ? alt : "minecraft:" + alt;
            int reserved = alreadyNeeded.getOrDefault(full, 0);
            if (countInventoryItem(player, full)
                    + com.emma.bridge.util.EndinvBridge.getCount(full) > reserved) {
                return full;
            }
        }

        return fallback;
    }

    private int countInventoryItem(LocalPlayer player, String itemId) {
        return InventoryScanner.countItems(player.getInventory(),
                stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId));
    }

    private int findEmptyHotbarSlot(LocalPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /** A single click operation for manual crafting: slot index + mouse button. */
    private record ManualSlotOp(int slot, int button) {}

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
        // Need 4 planks of any type. Logs and wood count too because they can be
        // converted into planks in the 2x2 player grid before crafting the table.
        int plankCount = 0;
        for (var slotStack : InventoryScanner.findAll(player.getInventory(), stack -> true)) {
            plankCount += plankEquivalentCount(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(slotStack.stack().getItem()).toString(),
                    slotStack.stack().getCount());
        }
        if (state != null) {
            for (var entry : state.endinvInventory.entrySet()) {
                plankCount += plankEquivalentCount(entry.getKey(), entry.getValue());
            }
        }
        return plankCount >= 4;
    }

    private boolean recipeUsesPlayerGrid(ItemRecipeEntry recipe) {
        return !recipeRequiresCraftingTable(recipe);
    }

    private boolean recipeRequiresCraftingTable(ItemRecipeEntry recipe) {
        if (recipe == null) return true;

        ObtainMethod method = recipe.getObtainMethod();
        if (method == ObtainMethod.CRAFT_SHAPED_2x2) return false;
        if (method == ObtainMethod.CRAFT_SHAPED_3x3) return true;
        if (method == ObtainMethod.CRAFT_SHAPELESS) {
            return countNonNullSlots(recipe.getCraftGrid()) > 4;
        }
        return true;
    }

    private static int plankEquivalentCount(String itemId, int count) {
        if (itemId == null || count <= 0) return 0;
        if (itemId.contains("planks")) return count;
        if (itemId.contains("_log") || itemId.contains("_wood")
                || itemId.equals("minecraft:bamboo_block")) {
            return count * 4;
        }
        return 0;
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
        useManualCraft = false;
        manualOps = null;
        manualOpIndex = 0;
        manualExtractWaitTicks = 0;
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
        bd.addProperty("manual_craft", useManualCraft);
        return bd;
    }
}
