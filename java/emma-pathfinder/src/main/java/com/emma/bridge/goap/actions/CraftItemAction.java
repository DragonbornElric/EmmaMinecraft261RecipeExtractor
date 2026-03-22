package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.catalogue.ItemRecipeEntry;
import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.catalogue.ObtainMethod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.InventoryScanner;
import com.emma.bridge.util.ItemClassifier;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ContainerInput;
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
 *   FIND_TABLE → NAVIGATE → OPEN → WAIT_SCREEN → FILL_GRID → EXTRACT → DONE
 *
 * Uses direct MC API calls (interactionManager.clickSlot, rightClickBlock).
 * Zero EmmaClef dependencies.
 */
public class CraftItemAction extends GoapAction {

    // ── State machine ────────────────────────────────────────────
    private enum Phase {
        IDLE, FIND_TABLE, EQUIP_TABLE, PLACE_TABLE, NAVIGATE, OPEN, WAIT_SCREEN,
        FILL_GRID, EXTRACT, BREAK_TABLE, COLLECT_TABLE, DONE
    }

    private Phase phase = Phase.IDLE;
    private boolean crafting = false;
    private String targetGoalId = null;
    private String targetItem = null;          // the item we'll actually craft (may be intermediate)
    private ItemRecipeEntry targetRecipe = null;

    private BlockPos tablePos = null;
    private boolean usePlayerGrid = false;     // true for 2x2 recipes
    private int fillSlotIndex = 0;             // tracks which grid slot we're filling
    private int waitTicks = 0;
    private int extractCount = 0;              // how many times we've extracted output

    // Sub-craft state: when we need to craft a crafting table first
    private boolean subCraftingTable = false;
    private String savedTargetItem = null;
    private ItemRecipeEntry savedTargetRecipe = null;

    // Break/collect tracking for placed tables
    private boolean placedTable = false;
    private int breakTicks = 0;
    private int collectTicks = 0;

    // Slot mapping:
    //   Crafting table: grid=1-9, output=0, player inv starts at 10
    //   Player inventory: grid=1-4, output=0, main inv starts at 5? Actually InventoryMenu:
    //     0=output, 1-4=crafting grid, 5-8=armor, 9-44=main+hotbar (9-35=main, 36-44=hotbar)
    private static final int TABLE_OUTPUT = 0;
    private static final int TABLE_GRID_START = 1;  // 1-9
    private static final int TABLE_INV_START = 10;  // 10-45

    private static final int PLAYER_OUTPUT = 0;
    private static final int PLAYER_GRID_START = 1; // 1-4
    private static final int PLAYER_INV_START = 9;  // 9-44 (main+hotbar in InventoryMenu)

    @Override
    public String getName() {
        return "CraftItem";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return true; // scoring determines viability
    }

    // ── Scoring ──────────────────────────────────────────────────

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        float bestScore = 0;

        for (GoalSet.Goal goal : goals.getGoals()) {
            if (goal.target == null || !goal.target.has("item")) continue;

            String goalItem = goal.target.get("item").getAsString();
            int goalCount = goal.target.has("count") ? goal.target.get("count").getAsInt() : 1;

            // Skip if we already have enough
            if (state.hasItem(goalItem, goalCount)) continue;

            // Find the first craftable item in the dependency chain
            String craftable = findCraftableInChain(state, goalItem);
            if (craftable == null) continue;

            float score = goal.priority * 0.8f;
            if (score > bestScore) {
                bestScore = score;
                targetGoalId = goal.id;
                targetItem = craftable;
            }
        }

        return bestScore;
    }

    /**
     * Walk transitive dependency chain for goalItem.
     * Returns the first item (starting from raw deps, ending with goalItem itself)
     * where a CRAFT recipe exists AND we have all ingredients in inventory.
     * Returns null if nothing is ready to craft.
     */
    private String findCraftableInChain(WorldState state, String goalItem) {
        String goalId = goalItem.contains(":") ? goalItem.split(":")[1] : goalItem;

        // Get all dependencies in topological order (raw materials first)
        List<String> deps = ItemRecipeRegistry.getTransitiveDependencies(goalId);
        // Add the goal item itself at the end
        deps.add(goalId);

        for (String itemId : deps) {
            // Skip if we already have this item
            String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
            if (state.hasItemInInventory(fullId, 1)) continue;

            // Check if this item has a craft recipe where we have ingredients
            if (hasIngredients(state, itemId)) {
                return fullId;
            }
        }
        return null;
    }

    /**
     * Check if we have ingredients for any CRAFT recipe of this item.
     * Uses tag-aware matching: if recipe needs oak_planks, any planks type works.
     */
    private boolean hasIngredients(WorldState state, String itemId) {
        String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);
        if (entries.isEmpty()) return false;

        for (ItemRecipeEntry entry : entries) {
            if (!entry.getObtainMethod().isCraftType()) continue;

            String[][] grid = entry.getCraftGrid();
            if (grid == null) continue;

            Map<String, Integer> needed = new LinkedHashMap<>();
            for (String[] slotAlts : grid) {
                if (slotAlts != null && slotAlts.length > 0) {
                    String fullSlot = slotAlts[0].contains(":") ? slotAlts[0] : "minecraft:" + slotAlts[0];
                    needed.merge(fullSlot, 1, Integer::sum);
                }
            }

            boolean hasAll = true;
            for (var ingredientEntry : needed.entrySet()) {
                if (!hasItemOrTagEquivalent(state, ingredientEntry.getKey(), ingredientEntry.getValue())) {
                    hasAll = false;
                    break;
                }
            }
            if (hasAll) return true;
        }
        return false;
    }

    // ── Execution ────────────────────────────────────────────────

    @Override
    public void execute(Minecraft client) {
        if (targetItem == null) return;

        // Find the recipe to use
        String id = targetItem.contains(":") ? targetItem.split(":")[1] : targetItem;
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);
        for (ItemRecipeEntry entry : entries) {
            if (!entry.getObtainMethod().isCraftType()) continue;
            targetRecipe = entry;
            break;
        }

        if (targetRecipe == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] No craft recipe found for {}", targetItem);
            return;
        }

        usePlayerGrid = (targetRecipe.getObtainMethod() == ObtainMethod.CRAFT_SHAPED_2x2);
        crafting = true;
        fillSlotIndex = 0;
        extractCount = 0;
        waitTicks = 0;
        placedTable = false;
        breakTicks = 0;
        collectTicks = 0;

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
            case FILL_GRID -> tickFillGrid(client, player);
            case EXTRACT -> tickExtract(client, player);
            case BREAK_TABLE -> tickBreakTable(client, player);
            case COLLECT_TABLE -> tickCollectTable(client, player);
            case DONE -> {
                ScreenHelper.closeIfOpen(client);
                if (subCraftingTable) {
                    // We just crafted a crafting_table — now place it and resume the original craft
                    subCraftingTable = false;
                    targetItem = savedTargetItem;
                    targetRecipe = savedTargetRecipe;
                    savedTargetItem = null;
                    savedTargetRecipe = null;
                    usePlayerGrid = false;
                    fillSlotIndex = 0;
                    extractCount = 0;
                    waitTicks = 0;
                    phase = Phase.EQUIP_TABLE;
                    EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Crafting table crafted, placing and resuming {}",
                            targetItem);
                } else if (placedTable && tablePos != null) {
                    // Recover the table we placed
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
        } else if (canCraftTable(player)) {
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
     * Saves the original target so we can resume after placing the table.
     *
     * Builds a synthetic recipe using whatever planks the player actually has,
     * since the registry resolves the #planks tag to one specific type
     * (e.g., oak_planks) which may not match what's in inventory.
     */
    private void startSubCraftTable() {
        // Find what planks the player actually has
        String plankType = findAnyPlankInInventory();
        if (plankType == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] canCraftTable was true but no planks found?");
            crafting = false;
            phase = Phase.IDLE;
            return;
        }

        // Save current target
        savedTargetItem = targetItem;
        savedTargetRecipe = targetRecipe;
        subCraftingTable = true;

        // Build a synthetic 2x2 recipe: 4 of whatever planks we have
        String plankId = plankType.contains(":") ? plankType.split(":")[1] : plankType;
        String[][] grid = new String[][]{{plankId}, {plankId}, {plankId}, {plankId}};
        targetRecipe = ItemRecipeEntry.ofCraft("crafting_table", ObtainMethod.CRAFT_SHAPED_2x2, grid, 1);

        targetItem = "minecraft:crafting_table";
        usePlayerGrid = true;
        fillSlotIndex = 0;
        extractCount = 0;
        waitTicks = 0;
        phase = Phase.OPEN;

        EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Sub-crafting crafting_table from {} in 2x2 grid before {}",
                plankType, savedTargetItem);
    }

    /**
     * Find any planks item in player inventory. Returns full ID (e.g., "minecraft:spruce_planks").
     */
    private String findAnyPlankInInventory() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return null;
        var planks = InventoryScanner.findAll(client.player.getInventory(),
                stack -> ItemClassifier.itemId(stack).contains("planks"));
        // Fast path: single stack with 4+
        for (var ss : planks) {
            if (ss.stack().getCount() >= 4) return ItemClassifier.itemId(ss.stack());
        }
        // Slow path: sum across stacks per plank type
        Map<String, Integer> plankCounts = new HashMap<>();
        for (var ss : planks) {
            String id = ItemClassifier.itemId(ss.stack());
            plankCounts.merge(id, ss.stack().getCount(), Integer::sum);
        }
        for (var entry : plankCounts.entrySet()) {
            if (entry.getValue() >= 4) return entry.getKey();
        }
        return null;
    }

    /** Equip crafting table and find placement spot — place happens next tick. */
    private BlockPos pendingPlaceOn = null;

    private void tickEquipTable(Minecraft client, LocalPlayer player) {
        // Find a placeable position near the player
        BlockPos below = player.blockPosition().below();
        pendingPlaceOn = below;

        // Try positions around the player
        for (BlockPos offset : new BlockPos[]{
                player.blockPosition().north(), player.blockPosition().south(),
                player.blockPosition().east(), player.blockPosition().west()}) {
            if (player.level().getBlockState(offset).isAir()
                    && player.level().getBlockState(offset.below()).isSolid()) {
                pendingPlaceOn = offset.below();
                tablePos = offset;
                break;
            }
        }

        if (tablePos == null) {
            // Place on top of block below player
            if (player.level().getBlockState(player.blockPosition()).isAir()) {
                tablePos = player.blockPosition();
                pendingPlaceOn = below;
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

    private void tickPlaceTable(Minecraft client, LocalPlayer player) {
        // Wait 2 ticks after equip for server to sync held item
        if (++waitTicks < 2) return;

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
        switch (GoapNavHelper.tickNavigateToBlock(player, tablePos, ++waitTicks)) {
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
            fillSlotIndex = 0;
            phase = Phase.FILL_GRID;
            return;
        }

        if (++waitTicks > 40) {
            EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] Screen open timeout, retrying");
            phase = Phase.OPEN;
            waitTicks = 0;
        }
    }

    private void tickFillGrid(Minecraft client, LocalPlayer player) {
        if (targetRecipe == null) {
            phase = Phase.DONE;
            return;
        }

        String[][] grid = targetRecipe.getCraftGrid();
        if (grid == null) {
            phase = Phase.DONE;
            return;
        }

        int gridSize = usePlayerGrid ? 4 : 9;
        int gridStart = usePlayerGrid ? PLAYER_GRID_START : TABLE_GRID_START;
        int invStart = usePlayerGrid ? PLAYER_INV_START : TABLE_INV_START;

        // Process one grid slot per tick
        while (fillSlotIndex < gridSize) {
            int i = fillSlotIndex;
            fillSlotIndex++;

            String[] slotAlts = (i < grid.length) ? grid[i] : null;
            if (slotAlts == null || slotAlts.length == 0) continue; // empty slot
            String ingredient = slotAlts[0]; // representative — tag matching finds actual item

            String fullIngredient = ingredient.contains(":") ? ingredient : "minecraft:" + ingredient;
            int gridSlot = gridStart + i;

            // Check if slot already has the right item
            var handler = player.containerMenu;
            if (handler == null) {
                phase = Phase.OPEN;
                return;
            }

            if (gridSlot < handler.slots.size()) {
                var currentStack = handler.slots.get(gridSlot).getItem();
                if (!currentStack.isEmpty()) {
                    String currentId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(currentStack.getItem()).toString();
                    if (currentId.equals(fullIngredient)) {
                        continue; // already filled
                    }
                }
            }

            // Find this ingredient in player inventory area
            int syncId = handler.containerId;
            int sourceSlot = ScreenHelper.findItemOrTagEquivalent(handler, invStart, fullIngredient);

            if (sourceSlot < 0) {
                EmmaBridgeMod.LOGGER.warn("[GOAP CraftItem] Can't find {} in inventory", fullIngredient);
                phase = Phase.DONE; // abort
                return;
            }

            // Pick up from inventory, right-click to place 1 in grid, put remainder back
            client.gameMode.handleContainerInput(syncId, sourceSlot, 0, ContainerInput.PICKUP, player);
            client.gameMode.handleContainerInput(syncId, gridSlot, 1, ContainerInput.PICKUP, player);
            client.gameMode.handleContainerInput(syncId, sourceSlot, 0, ContainerInput.PICKUP, player);

            return; // one slot per tick
        }

        // All slots filled — go to extract
        phase = Phase.EXTRACT;
        waitTicks = 0;
    }

    private void tickExtract(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (handler == null) {
            phase = Phase.DONE;
            return;
        }

        int outputSlot = usePlayerGrid ? PLAYER_OUTPUT : TABLE_OUTPUT;

        // Check if output slot has items
        if (outputSlot < handler.slots.size()) {
            var outputStack = handler.slots.get(outputSlot).getItem();
            if (!outputStack.isEmpty()) {
                // Shift-click output to inventory
                client.gameMode.handleContainerInput(
                        handler.containerId, outputSlot, 0, ContainerInput.QUICK_MOVE, player);
                extractCount++;
                EmmaBridgeMod.LOGGER.info("[GOAP CraftItem] Extracted {} (attempt {})",
                        targetItem, extractCount);
                // Wait a tick for inventory update, then check if we need more
                waitTicks = 0;
                return;
            }
        }

        // Output is empty — we're done (or something went wrong)
        if (++waitTicks > 5) {
            phase = Phase.DONE;
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

        // Check if table is back in inventory
        if (hasItemInInventory(player, Items.CRAFTING_TABLE)) {
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

    /**
     * Check if the player has enough of an item OR any tag-equivalent item.
     * E.g., hasItemOrTagEquivalent(state, "minecraft:oak_planks", 4) returns true
     * if player has 4 of ANY planks type.
     */
    private static boolean hasItemOrTagEquivalent(WorldState state, String itemId, int count) {
        // Direct check first
        if (state.hasItemInInventory(itemId, count)) return true;

        // Check tag equivalents
        String tagGroup = TagGroups.getItemTagGroup(itemId);
        if (tagGroup == null) return false;

        int total = 0;
        for (String member : TagGroups.getGroupMembers(tagGroup)) {
            String fullId = "minecraft:" + member;
            total += state.playerInventory.getOrDefault(fullId, 0);
            if (total >= count) return true;
        }
        return false;
    }

    private boolean hasItemInInventory(LocalPlayer player, net.minecraft.world.item.Item item) {
        return InventoryScanner.hasItem(player.getInventory(), item);
    }

    private boolean canCraftTable(LocalPlayer player) {
        // Need 4 planks of any type
        int plankCount = InventoryScanner.countItems(player.getInventory(),
                stack -> net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString().contains("planks"));
        return plankCount >= 4;
    }

    // ── Lifecycle ────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        ScreenHelper.closeIfOpen(client);

        // Clean up cursor if we got interrupted mid-click
        if (client.player != null) {
            var handler = client.player.containerMenu;
            if (handler != null && !client.player.containerMenu.getCarried().isEmpty()) {
                // Try to put cursor item back into inventory
                for (int i = 0; i < handler.slots.size(); i++) {
                    if (handler.slots.get(i).getItem().isEmpty()) {
                        client.gameMode.handleContainerInput(
                                handler.containerId, i, 0, ContainerInput.PICKUP, client.player);
                        break;
                    }
                }
            }
        }

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
        tablePos = null;
        usePlayerGrid = false;
        subCraftingTable = false;
        savedTargetItem = null;
        savedTargetRecipe = null;
        placedTable = false;
        breakTicks = 0;
        collectTicks = 0;
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
        return bd;
    }
}
