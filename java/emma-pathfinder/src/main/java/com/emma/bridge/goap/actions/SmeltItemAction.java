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
import com.emma.bridge.mixin.AbstractFurnaceScreenHandlerAccessor;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * GOAP Action: Smelt raw materials in a furnace.
 *
 * Scoring: Walks the transitive dependency chain for any have_item goal.
 *   Finds the first smeltable intermediate where we have raw input in inventory.
 *
 * Execution: Tick-based state machine:
 *   FIND_FURNACE → NAVIGATE → OPEN → WAIT_SCREEN → INSERT_INPUT → INSERT_FUEL → WAIT_COOK → EXTRACT → DONE
 *
 * Uses AbstractFurnaceScreenHandlerAccessor for cook progress tracking.
 * Handles furnace, blast furnace, smoker (same slot layout: 0=input, 1=fuel, 2=output).
 *
 * Zero EmmaClef dependencies.
 */
public class SmeltItemAction extends GoapAction {

    private enum Phase {
        IDLE, FIND_FURNACE, EQUIP_FURNACE, PLACE_FURNACE, NAVIGATE, OPEN, WAIT_SCREEN,
        INSERT_INPUT, INSERT_FUEL, WAIT_COOK, EXTRACT, DONE
    }

    private Phase phase = Phase.IDLE;
    private boolean smelting = false;
    private String targetGoalId = null;
    private String targetItem = null;          // the smelted output we want
    private String rawInput = null;            // the raw material to insert
    private ItemRecipeEntry targetRecipe = null;

    private BlockPos furnacePos = null;
    private int waitTicks = 0;
    private int noProgressTicks = 0;
    private float lastCookProgress = 0;

    // Furnace screen handler slots
    private static final int FURNACE_INPUT = 0;
    private static final int FURNACE_FUEL = 1;
    private static final int FURNACE_OUTPUT = 2;
    private static final int FURNACE_INV_START = 3; // player inventory starts here

    // Common fuel items
    private static final Set<String> FUEL_ITEMS = Set.of(
            "minecraft:coal", "minecraft:charcoal", "minecraft:coal_block",
            "minecraft:lava_bucket", "minecraft:blaze_rod",
            "minecraft:dried_kelp_block", "minecraft:bamboo",
            "minecraft:stick", "minecraft:wooden_pickaxe", "minecraft:wooden_axe",
            "minecraft:wooden_sword", "minecraft:wooden_shovel", "minecraft:wooden_hoe"
    );

    // Plank variants count as fuel too
    private static boolean isFuel(String itemId) {
        return FUEL_ITEMS.contains(itemId)
                || itemId.contains("planks")
                || itemId.contains("log")
                || itemId.contains("wood")
                || itemId.contains("fence")
                || itemId.contains("slab")
                || itemId.contains("stairs")
                || itemId.contains("door")
                || itemId.contains("boat")
                || itemId.contains("sign")
                || itemId.contains("banner")
                || itemId.contains("wool")
                || itemId.contains("carpet");
    }

    @Override
    public String getName() {
        return "SmeltItem";
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

            if (state.hasItem(goalItem, goalCount)) continue;

            // Find first smeltable item in dependency chain
            String[] smeltable = findSmeltableInChain(state, goalItem);
            if (smeltable == null) continue;

            // Also need fuel and furnace access
            if (!hasFuelInInventory(state)) continue;
            if (!hasFurnaceAccess(state)) continue;

            float score = goal.priority * 0.7f; // below crafting
            if (score > bestScore) {
                bestScore = score;
                targetGoalId = goal.id;
                targetItem = smeltable[0]; // output
                rawInput = smeltable[1];   // input
            }
        }

        return bestScore;
    }

    /**
     * Walk dependency chain and find the first smeltable item where we have the raw input.
     * Returns [outputItem, inputItem] or null.
     */
    private String[] findSmeltableInChain(WorldState state, String goalItem) {
        String goalId = goalItem.contains(":") ? goalItem.split(":")[1] : goalItem;

        List<String> deps = ItemRecipeRegistry.getTransitiveDependencies(goalId);
        deps.add(goalId);

        for (String itemId : deps) {
            String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
            if (state.hasItemInInventory(fullId, 1)) continue;

            List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(itemId);
            for (ItemRecipeEntry entry : entries) {
                if (entry.getObtainMethod() != ObtainMethod.SMELT) continue;
                String[] smeltFrom = entry.getSmeltFrom();
                if (smeltFrom == null) continue;

                for (String input : smeltFrom) {
                    String inputId = input.contains(":") ? input : "minecraft:" + input;
                    if (state.hasItemInInventory(inputId, 1)) {
                        return new String[]{fullId, inputId};
                    }
                }
            }
        }
        return null;
    }

    private boolean hasFurnaceAccess(WorldState state) {
        for (String type : List.of("minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker")) {
            List<BlockPos> positions = state.nearbyBlocks.get(type);
            if (positions != null && !positions.isEmpty()) return true;
            if (state.hasItemInInventory(type, 1)) return true;
        }
        return false;
    }

    private boolean hasFuelInInventory(WorldState state) {
        for (var entry : state.playerInventory.entrySet()) {
            if (isFuel(entry.getKey()) && entry.getValue() > 0) return true;
        }
        return false;
    }

    // ── Execution ────────────────────────────────────────────────

    @Override
    public void execute(Minecraft client) {
        if (targetItem == null || rawInput == null) return;

        // Find recipe
        String id = targetItem.contains(":") ? targetItem.split(":")[1] : targetItem;
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);
        for (ItemRecipeEntry entry : entries) {
            if (entry.getObtainMethod() == ObtainMethod.SMELT) {
                targetRecipe = entry;
                break;
            }
        }

        if (targetRecipe == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP SmeltItem] No smelt recipe for {}", targetItem);
            return;
        }

        smelting = true;
        waitTicks = 0;
        noProgressTicks = 0;
        lastCookProgress = 0;
        phase = Phase.FIND_FURNACE;

        EmmaBridgeMod.LOGGER.info("[GOAP SmeltItem] Starting smelt: {} from {}", targetItem, rawInput);
    }

    @Override
    public void tick(Minecraft client) {
        if (!smelting || phase == Phase.IDLE) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        switch (phase) {
            case FIND_FURNACE -> tickFindFurnace(client, player);
            case EQUIP_FURNACE -> tickEquipFurnace(client, player);
            case PLACE_FURNACE -> tickPlaceFurnace(client, player);
            case NAVIGATE -> tickNavigate(client, player);
            case OPEN -> tickOpen(client, player);
            case WAIT_SCREEN -> tickWaitScreen(client, player);
            case INSERT_INPUT -> tickInsertInput(client, player);
            case INSERT_FUEL -> tickInsertFuel(client, player);
            case WAIT_COOK -> tickWaitCook(client, player);
            case EXTRACT -> tickExtract(client, player);
            case DONE -> {
                ScreenHelper.closeIfOpen(client);
                smelting = false;
                phase = Phase.IDLE;
            }
            default -> {}
        }
    }

    // ── Phase handlers ───────────────────────────────────────────

    private void tickFindFurnace(Minecraft client, LocalPlayer player) {
        BlockPos playerPos = player.blockPosition();
        Level world = player.level();

        Block[] furnaceTypes = {Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER};

        for (int r = 1; r <= 32; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                        BlockPos check = playerPos.offset(dx, dy, dz);
                        var blockState = world.getBlockState(check);
                        for (Block type : furnaceTypes) {
                            if (blockState.is(type)) {
                                furnacePos = check;
                                phase = Phase.NAVIGATE;
                                return;
                            }
                        }
                    }
                }
            }
        }

        // No furnace found — place one if we have it
        if (hasItemInInventory(player, Items.FURNACE)) {
            phase = Phase.EQUIP_FURNACE;
        } else {
            EmmaBridgeMod.LOGGER.info("[GOAP SmeltItem] No furnace available");
            smelting = false;
            phase = Phase.IDLE;
        }
    }

    private BlockPos pendingFurnacePlaceOn = null;

    /** Equip furnace and find spot — actual placement next tick. */
    private void tickEquipFurnace(Minecraft client, LocalPlayer player) {
        for (BlockPos offset : new BlockPos[]{
                player.blockPosition().north(), player.blockPosition().south(),
                player.blockPosition().east(), player.blockPosition().west()}) {
            if (player.level().getBlockState(offset).isAir()
                    && player.level().getBlockState(offset.below()).isSolid()) {
                furnacePos = offset;
                pendingFurnacePlaceOn = offset.below();
                BlockInteraction.forceEquipItem(Items.FURNACE);
                waitTicks = 0;
                phase = Phase.PLACE_FURNACE;  // place next tick
                return;
            }
        }

        EmmaBridgeMod.LOGGER.warn("[GOAP SmeltItem] No space to place furnace");
        smelting = false;
        phase = Phase.IDLE;
    }

    /** Place furnace — one tick after equip for server sync. */
    private void tickPlaceFurnace(Minecraft client, LocalPlayer player) {
        // Wait 2 extra ticks for server sync (3 total including equip tick)
        if (++waitTicks < 2) return;

        if (pendingFurnacePlaceOn == null || furnacePos == null) {
            phase = Phase.FIND_FURNACE;
            return;
        }

        BlockInteraction.lookAt(pendingFurnacePlaceOn);
        BlockInteraction.rightClickBlock(pendingFurnacePlaceOn, Direction.UP);
        EmmaBridgeMod.LOGGER.info("[GOAP SmeltItem] Placed furnace at {}", furnacePos);
        pendingFurnacePlaceOn = null;
        waitTicks = 0;
        phase = Phase.NAVIGATE;
    }

    private void tickNavigate(Minecraft client, LocalPlayer player) {
        switch (GoapNavHelper.tickNavigateToBlock(player, furnacePos, ++waitTicks)) {
            case NO_TARGET -> phase = Phase.FIND_FURNACE;
            case ARRIVED -> { phase = Phase.OPEN; waitTicks = 0; }
            case TIMEOUT -> {
                EmmaBridgeMod.LOGGER.warn("[GOAP SmeltItem] Navigation timeout");
                smelting = false;
                phase = Phase.IDLE;
            }
            case PATHING -> {}
        }
    }

    private void tickOpen(Minecraft client, LocalPlayer player) {
        if (furnacePos == null) {
            phase = Phase.FIND_FURNACE;
            return;
        }

        BlockInteraction.lookAt(furnacePos);
        BlockInteraction.rightClickBlock(furnacePos);
        phase = Phase.WAIT_SCREEN;
        waitTicks = 0;
    }

    private void tickWaitScreen(Minecraft client, LocalPlayer player) {
        if (player.containerMenu instanceof AbstractFurnaceMenu) {
            phase = Phase.INSERT_INPUT;
            return;
        }

        if (++waitTicks > 40) {
            EmmaBridgeMod.LOGGER.warn("[GOAP SmeltItem] Screen open timeout, retrying");
            phase = Phase.OPEN;
            waitTicks = 0;
        }
    }

    private void tickInsertInput(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (!(handler instanceof AbstractFurnaceMenu)) {
            phase = Phase.OPEN;
            return;
        }

        // Check if input slot already has material
        var inputStack = handler.slots.get(FURNACE_INPUT).getItem();
        if (!inputStack.isEmpty()) {
            String inputId = BuiltInRegistries.ITEM.getKey(inputStack.getItem()).toString();
            if (inputId.equals(rawInput)) {
                // Already has our material — go to fuel
                phase = Phase.INSERT_FUEL;
                return;
            }
        }

        // Find raw input in player inventory area
        int sourceSlot = ScreenHelper.findItem(handler, FURNACE_INV_START, rawInput);
        if (sourceSlot < 0) {
            EmmaBridgeMod.LOGGER.warn("[GOAP SmeltItem] Raw input {} not found in inventory", rawInput);
            phase = Phase.DONE;
            return;
        }

        // Shift-click raw material to input slot
        client.gameMode.handleContainerInput(
                handler.containerId, sourceSlot, 0, ContainerInput.QUICK_MOVE, player);

        phase = Phase.INSERT_FUEL;
    }

    private void tickInsertFuel(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (!(handler instanceof AbstractFurnaceMenu furnace)) {
            phase = Phase.OPEN;
            return;
        }

        // Check if furnace is already burning
        ContainerData pd = ((AbstractFurnaceScreenHandlerAccessor) furnace).getPropertyDelegate();
        int burnTimeRemaining = pd.get(0);
        if (burnTimeRemaining > 0) {
            // Already burning — go to wait
            phase = Phase.WAIT_COOK;
            noProgressTicks = 0;
            lastCookProgress = 0;
            return;
        }

        // Check if fuel slot already has fuel
        var fuelStack = handler.slots.get(FURNACE_FUEL).getItem();
        if (!fuelStack.isEmpty()) {
            String fuelId = BuiltInRegistries.ITEM.getKey(fuelStack.getItem()).toString();
            if (isFuel(fuelId)) {
                // Has fuel — the furnace should start burning
                phase = Phase.WAIT_COOK;
                noProgressTicks = 0;
                lastCookProgress = 0;
                return;
            }
        }

        // Find fuel in player inventory area
        int fuelSlot = -1;
        for (int i = FURNACE_INV_START; i < handler.slots.size(); i++) {
            var stack = handler.slots.get(i).getItem();
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (isFuel(id)) {
                    fuelSlot = i;
                    break;
                }
            }
        }

        if (fuelSlot < 0) {
            EmmaBridgeMod.LOGGER.warn("[GOAP SmeltItem] No fuel available in inventory");
            phase = Phase.DONE;
            return;
        }

        // Shift-click fuel to fuel slot
        client.gameMode.handleContainerInput(
                handler.containerId, fuelSlot, 0, ContainerInput.QUICK_MOVE, player);

        phase = Phase.WAIT_COOK;
        noProgressTicks = 0;
        lastCookProgress = 0;
    }

    private void tickWaitCook(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (!(handler instanceof AbstractFurnaceMenu furnace)) {
            // Screen closed (maybe preempted) — will re-open when action wins again
            smelting = false;
            phase = Phase.IDLE;
            return;
        }

        // Check output slot
        var outputStack = handler.slots.get(FURNACE_OUTPUT).getItem();
        if (!outputStack.isEmpty()) {
            phase = Phase.EXTRACT;
            return;
        }

        // Track cook progress
        ContainerData pd = ((AbstractFurnaceScreenHandlerAccessor) furnace).getPropertyDelegate();
        int cookProgress = pd.get(2);
        int totalCookTime = pd.get(3);
        float progress = (totalCookTime > 0) ? (float) cookProgress / totalCookTime : 0;

        if (progress > lastCookProgress + 0.001f) {
            // Making progress
            lastCookProgress = progress;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        // Timeout: no progress for 200 ticks (10 seconds)
        if (noProgressTicks > 200) {
            EmmaBridgeMod.LOGGER.warn("[GOAP SmeltItem] No smelting progress for 200 ticks, aborting");
            phase = Phase.DONE;
        }
    }

    private void tickExtract(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (handler == null) {
            phase = Phase.DONE;
            return;
        }

        var outputStack = handler.slots.get(FURNACE_OUTPUT).getItem();
        if (!outputStack.isEmpty()) {
            client.gameMode.handleContainerInput(
                    handler.containerId, FURNACE_OUTPUT, 0, ContainerInput.QUICK_MOVE, player);
            EmmaBridgeMod.LOGGER.info("[GOAP SmeltItem] Extracted smelted output");
        }

        // Check if more raw material remains in input
        var inputStack = handler.slots.get(FURNACE_INPUT).getItem();
        if (!inputStack.isEmpty()) {
            // More to smelt — wait for next batch
            phase = Phase.WAIT_COOK;
            noProgressTicks = 0;
            lastCookProgress = 0;
            return;
        }

        phase = Phase.DONE;
    }

    // ── Helpers ──────────────────────────────────────────────────

    private boolean hasItemInInventory(LocalPlayer player, net.minecraft.world.item.Item item) {
        return InventoryScanner.hasItem(player.getInventory(), item);
    }

    // ── Lifecycle ────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        // Close screen but leave items in furnace — it keeps smelting!
        ScreenHelper.closeIfOpen(client);

        if (phase == Phase.NAVIGATE) {
            GoapNavHelper.cancelPathing();
        }

        smelting = false;
        phase = Phase.IDLE;
        targetGoalId = null;
        targetItem = null;
        rawInput = null;
        targetRecipe = null;
        furnacePos = null;
    }

    @Override
    public boolean isActive() {
        return smelting;
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
        String smeltId = targetItem.contains(":") ? targetItem.split(":")[1] : targetItem;

        List<String> deps = ItemRecipeRegistry.getTransitiveDependencies(goalId);
        if (deps.contains(smeltId)) {
            return 0.7f;
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
        bd.addProperty("smelting", smelting);
        bd.addProperty("phase", phase.name());
        bd.addProperty("target_item", targetItem != null ? targetItem : "none");
        bd.addProperty("raw_input", rawInput != null ? rawInput : "none");
        bd.addProperty("target_goal", targetGoalId != null ? targetGoalId : "none");
        return bd;
    }
}
