package com.emma.bridge.goap.actions;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.control.BlockInteraction;
import com.emma.bridge.goap.GoapAction;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.StorageRequest;
import com.emma.bridge.goap.WorldState;
import com.emma.bridge.util.InventoryScanner;
import com.emma.bridge.util.ItemClassifier;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * GOAP Action: Deposit/withdraw items from containers.
 *
 * Handles two trigger sources:
 *   1. Bridge commands: StorageRequest queue (deposit/withdraw/deposit_nearby)
 *   2. Autonomous: inventory nearly full (freeSlots <= threshold)
 *
 * State machine (following SmeltItemAction pattern):
 *   IDLE → FIND_CONTAINER → NAVIGATE → OPEN → WAIT_SCREEN → TRANSFER_ITEMS → CLOSE → DONE
 *
 * Zero EmmaClef dependencies — uses Minecraft + Emmatone APIs directly.
 */
public class StoreItemsAction extends GoapAction {

    private enum Phase {
        IDLE, FIND_CONTAINER, NAVIGATE, OPEN, WAIT_SCREEN, TRANSFER_ITEMS, CLOSE, DONE
    }

    /** Trigger autonomous storage when free slots <= this. */
    private static final int FREE_SLOT_THRESHOLD = 5;
    /** Navigation timeout in ticks (200 = 10 seconds). */
    private static final int NAV_TIMEOUT_TICKS = 200;
    /** Screen open timeout in ticks (40 = 2 seconds). */
    private static final int SCREEN_TIMEOUT_TICKS = 40;
    /** Transfer timeout in ticks (200 = 10 seconds). */
    private static final int TRANSFER_TIMEOUT_TICKS = 200;
    /** Autonomous deposit keeps at least this many food stacks (slots) in inventory. */
    private static final int KEEP_FOOD_STACKS = 1;

    private Phase phase = Phase.IDLE;
    private boolean active = false;

    // Current task state
    private StorageRequest currentRequest = null;
    private BlockPos containerPos = null;
    private Map<String, Integer> transferItems = new HashMap<>();
    private Map<String, Integer> transferredCounts = new HashMap<>();
    private int waitTicks = 0;
    // Track which essentials we've already decided to keep (persists across ticks)
    private final Set<String> keptToolCategories = new HashSet<>();
    private final Set<String> keptArmorCategories = new HashSet<>();
    private boolean keptFood = false;
    private boolean keptShield = false;

    @Override
    public String getName() {
        return "StoreItems";
    }

    @Override
    public boolean checkPreconditions(WorldState state) {
        return StorageRequest.hasPending() || state.freeSlots <= FREE_SLOT_THRESHOLD || active;
    }

    // ── Scoring ──────────────────────────────────────────────────

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        // Bridge request takes high priority
        if (StorageRequest.hasPending()) {
            return 8.0f;
        }

        // Keep scoring while actively depositing so we don't abandon mid-transfer
        if (active) {
            return goals.getGoal("manage_inventory")
                    .map(g -> g.priority)
                    .orElse(4.0f);
        }

        // Autonomous: inventory nearly full
        if (state.freeSlots > FREE_SLOT_THRESHOLD) return 0;

        float urgency = 1.0f - ((float) state.freeSlots / FREE_SLOT_THRESHOLD);
        float basePriority = goals.getGoal("manage_inventory")
                .map(g -> g.priority)
                .orElse(4.0f);

        return basePriority * urgency;
    }

    // ── Execution ────────────────────────────────────────────────

    @Override
    public void execute(Minecraft client) {
        if (active) return;

        active = true;
        transferredCounts.clear();

        // Consume pending bridge request if available
        currentRequest = StorageRequest.poll();

        if (currentRequest != null) {
            containerPos = currentRequest.pos; // null for deposit_nearby
            transferItems = new HashMap<>(currentRequest.items);
            EmmaBridgeMod.LOGGER.info("[GOAP StoreItems] Starting {} — {} item types, task={}",
                    currentRequest.type, transferItems.size(), currentRequest.taskId);
        } else {
            // Autonomous mode — deposit all items to nearest container
            containerPos = null;
            transferItems = new HashMap<>();
            EmmaBridgeMod.LOGGER.info("[GOAP StoreItems] Starting autonomous deposit (inventory full)");
        }

        phase = Phase.FIND_CONTAINER;
        waitTicks = 0;
    }

    @Override
    public void tick(Minecraft client) {
        if (!active || phase == Phase.IDLE) return;

        LocalPlayer player = client.player;
        if (player == null) return;

        switch (phase) {
            case FIND_CONTAINER -> tickFindContainer(client, player);
            case NAVIGATE -> tickNavigate(client, player);
            case OPEN -> tickOpen(client, player);
            case WAIT_SCREEN -> tickWaitScreen(client, player);
            case TRANSFER_ITEMS -> tickTransferItems(client, player);
            case CLOSE -> tickClose(client, player);
            case DONE -> {
                logCompletion(true, null);
                ScreenHelper.closeIfOpen(client);
                resetState();
            }
            default -> {}
        }
    }

    // ── Phase: FIND_CONTAINER ────────────────────────────────────

    private void tickFindContainer(Minecraft client, LocalPlayer player) {
        // If request specified a position, use it directly
        if (containerPos != null) {
            phase = Phase.NAVIGATE;
            waitTicks = 0;
            return;
        }

        // Autonomous: build transfer list (all inventory items)
        if (transferItems.isEmpty() && currentRequest == null) {
            for (var ss : InventoryScanner.findAll(player.getInventory(), stack -> true)) {
                String id = ItemClassifier.itemId(ss.stack());
                transferItems.merge(id, ss.stack().getCount(), Integer::sum);
            }
        }

        // Search for nearest container, preferring same-level or above
        Level world = player.level();
        BlockPos playerPos = player.blockPosition();
        BlockPos bestPos = null;
        double bestDist = Double.MAX_VALUE;

        for (int r = 1; r <= 32; r++) {
            for (int dx = -r; dx <= r; dx++) {
                // Autonomous: only search at player Y level ±2 (avoid underground chests)
                int yMin = currentRequest != null ? -4 : -2;
                int yMax = currentRequest != null ? 4 : 2;
                for (int dy = yMin; dy <= yMax; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                        BlockPos check = playerPos.offset(dx, dy, dz);
                        if (isContainerBlock(world, check)) {
                            double dist = playerPos.distSqr(check);
                            if (dist < bestDist) {
                                bestDist = dist;
                                bestPos = check;
                            }
                        }
                    }
                }
            }
            if (bestPos != null) break;
        }

        if (bestPos == null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP StoreItems] No container found within 32 blocks");
            logCompletion(false, "no_container_found");
            resetState();
            return;
        }

        containerPos = bestPos;
        phase = Phase.NAVIGATE;
        waitTicks = 0;
        EmmaBridgeMod.LOGGER.info("[GOAP StoreItems] Found container at [{},{},{}] (player at [{},{},{}], dist={})",
                containerPos.getX(), containerPos.getY(), containerPos.getZ(),
                playerPos.getX(), playerPos.getY(), playerPos.getZ(),
                String.format("%.1f", Math.sqrt(bestDist)));
    }

    // ── Phase: NAVIGATE ──────────────────────────────────────────

    private void tickNavigate(Minecraft client, LocalPlayer player) {
        switch (GoapNavHelper.tickNavigateToBlock(player, containerPos, ++waitTicks, NAV_TIMEOUT_TICKS, GoapNavHelper.CONTAINER_ARRIVAL_DIST)) {
            case NO_TARGET -> phase = Phase.FIND_CONTAINER;
            case ARRIVED -> { phase = Phase.OPEN; waitTicks = 0; }
            case TIMEOUT -> {
                EmmaBridgeMod.LOGGER.warn("[GOAP StoreItems] Navigation timeout");
                logCompletion(false, "navigation_timeout");
                resetState();
            }
            case PATHING -> {}
        }
    }

    // ── Phase: OPEN ──────────────────────────────────────────────

    private void tickOpen(Minecraft client, LocalPlayer player) {
        if (containerPos == null) {
            phase = Phase.FIND_CONTAINER;
            return;
        }

        BlockInteraction.lookAt(containerPos);
        BlockInteraction.rightClickBlock(containerPos);
        phase = Phase.WAIT_SCREEN;
        waitTicks = 0;
    }

    // ── Phase: WAIT_SCREEN ───────────────────────────────────────

    private void tickWaitScreen(Minecraft client, LocalPlayer player) {
        if (!(player.containerMenu instanceof InventoryMenu)) {
            phase = Phase.TRANSFER_ITEMS;
            waitTicks = 0;
            return;
        }

        if (++waitTicks > SCREEN_TIMEOUT_TICKS) {
            EmmaBridgeMod.LOGGER.warn("[GOAP StoreItems] Screen open timeout, retrying");
            phase = Phase.OPEN;
            waitTicks = 0;
        }
    }

    // ── Phase: TRANSFER_ITEMS ────────────────────────────────────

    private void tickTransferItems(Minecraft client, LocalPlayer player) {
        var handler = player.containerMenu;
        if (handler instanceof InventoryMenu) {
            EmmaBridgeMod.LOGGER.warn("[GOAP StoreItems] Screen closed during transfer");
            logCompletion(false, "screen_closed");
            resetState();
            return;
        }

        int containerSlotCount = handler.slots.size() - 36;
        if (containerSlotCount <= 0) {
            phase = Phase.CLOSE;
            return;
        }

        boolean isDeposit = currentRequest == null || currentRequest.type == StorageRequest.Type.DEPOSIT;

        if (isDeposit) {
            tickDeposit(client, player, handler, containerSlotCount);
        } else {
            tickWithdraw(client, player, handler, containerSlotCount);
        }

        if (++waitTicks > TRANSFER_TIMEOUT_TICKS) {
            EmmaBridgeMod.LOGGER.warn("[GOAP StoreItems] Transfer timeout after {} ticks", waitTicks);
            phase = Phase.CLOSE;
        }
    }

    private void tickDeposit(Minecraft client, LocalPlayer player,
                             net.minecraft.world.inventory.AbstractContainerMenu handler,
                             int containerSlotCount) {
        int playerInvStart = containerSlotCount;
        int playerInvEnd = handler.slots.size();

        for (int i = playerInvStart; i < playerInvEnd; i++) {
            ItemStack stack = handler.slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

            boolean shouldTransfer;
            if (currentRequest == null) {
                // Autonomous: keep 1 food stack + 1 of each tool category + 1 shield
                String toolCat = ItemClassifier.getToolCategory(itemId);
                if (ItemClassifier.isFood(itemId) && !keptFood) {
                    keptFood = true;
                    shouldTransfer = false;
                } else if (toolCat != null && !keptToolCategories.contains(toolCat)) {
                    keptToolCategories.add(toolCat);
                    shouldTransfer = false;
                } else if (itemId.contains("shield") && !keptShield) {
                    keptShield = true;
                    shouldTransfer = false;
                } else if (isArmorUpgrade(player, itemId)) {
                    shouldTransfer = false;
                } else {
                    shouldTransfer = true;
                }
            } else {
                shouldTransfer = transferItems.containsKey(itemId) && transferItems.get(itemId) > 0;
            }

            if (shouldTransfer) {
                int countBefore = stack.getCount();
                client.gameMode.handleContainerInput(
                        handler.containerId, i, 0, ContainerInput.QUICK_MOVE, player);

                if (currentRequest != null) {
                    transferItems.merge(itemId, -countBefore, Integer::sum);
                    if (transferItems.getOrDefault(itemId, 0) <= 0) {
                        transferItems.remove(itemId);
                    }
                }
                transferredCounts.merge(itemId, countBefore, Integer::sum);
                return; // one click per tick
            }
        }

        // No more items to transfer
        phase = Phase.CLOSE;
    }

    private void tickWithdraw(Minecraft client, LocalPlayer player,
                              net.minecraft.world.inventory.AbstractContainerMenu handler,
                              int containerSlotCount) {
        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack stack = handler.slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

            if (transferItems.containsKey(itemId) && transferItems.get(itemId) > 0) {
                int countBefore = stack.getCount();
                client.gameMode.handleContainerInput(
                        handler.containerId, i, 0, ContainerInput.QUICK_MOVE, player);

                transferItems.merge(itemId, -countBefore, Integer::sum);
                if (transferItems.getOrDefault(itemId, 0) <= 0) {
                    transferItems.remove(itemId);
                }
                transferredCounts.merge(itemId, countBefore, Integer::sum);
                return; // one click per tick
            }
        }

        // No more matching items in container
        phase = Phase.CLOSE;
    }

    // ── Phase: CLOSE ─────────────────────────────────────────────

    private void tickClose(Minecraft client, LocalPlayer player) {
        ScreenHelper.closeIfOpen(client);
        phase = Phase.DONE;
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Maps armor category string to EquipmentSlot for reading what's currently worn. */
    private static EquipmentSlot armorEquipmentSlot(String category) {
        return switch (category) {
            case "helmet" -> EquipmentSlot.HEAD;
            case "chestplate" -> EquipmentSlot.CHEST;
            case "leggings" -> EquipmentSlot.LEGS;
            case "boots" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    /**
     * Returns true if this armor piece is a higher tier than what's currently equipped
     * in that slot, and we haven't already kept one for this slot.
     */
    private boolean isArmorUpgrade(LocalPlayer player, String itemId) {
        String cat = ItemClassifier.getArmorCategory(itemId);
        if (cat == null) return false;
        if (keptArmorCategories.contains(cat)) return false;

        int spareTier = ItemClassifier.getArmorTierRank(itemId);
        EquipmentSlot slot = armorEquipmentSlot(cat);
        if (slot == null) return false;

        ItemStack equipped = player.getItemBySlot(slot);
        int equippedTier;
        if (equipped.isEmpty()) {
            equippedTier = Integer.MAX_VALUE; // nothing worn = anything is an upgrade
        } else {
            String equippedId = BuiltInRegistries.ITEM.getKey(equipped.getItem()).toString();
            equippedTier = ItemClassifier.getArmorTierRank(equippedId);
        }

        if (spareTier < equippedTier) {
            keptArmorCategories.add(cat);
            return true;
        }
        return false;
    }

    private static boolean isContainerBlock(Level world, BlockPos pos) {
        var block = world.getBlockState(pos).getBlock();
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock;
    }

    private void logCompletion(boolean success, String failReason) {
        if (currentRequest == null) return;

        if (success) {
            EmmaBridgeMod.LOGGER.info("[GOAP StoreItems] Completed {} task={}, transferred {} item types",
                    currentRequest.type, currentRequest.taskId, transferredCounts.size());
        } else {
            EmmaBridgeMod.LOGGER.warn("[GOAP StoreItems] Failed {} task={}: {}",
                    currentRequest.type, currentRequest.taskId, failReason);
        }
    }

    private void resetState() {
        active = false;
        phase = Phase.IDLE;
        currentRequest = null;
        containerPos = null;
        transferItems.clear();
        transferredCounts.clear();
        waitTicks = 0;
        keptToolCategories.clear();
        keptArmorCategories.clear();
        keptFood = false;
        keptShield = false;
    }

    // ── Lifecycle ────────────────────────────────────────────────

    @Override
    public void onDeactivated(Minecraft client) {
        ScreenHelper.closeIfOpen(client);

        if (phase == Phase.NAVIGATE) {
            GoapNavHelper.cancelPathing();
        }

        if (currentRequest != null) {
            EmmaBridgeMod.LOGGER.warn("[GOAP StoreItems] Deactivated with pending task={}, {} items transferred",
                    currentRequest.taskId, transferredCounts.size());
        }

        resetState();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String getPrimaryGoalId() {
        return "manage_inventory";
    }

    @Override
    public String personalityCategory() {
        return "resource_hoarding";
    }

    @Override
    public JsonObject getScoreBreakdown(WorldState state, GoalSet goals) {
        JsonObject bd = super.getScoreBreakdown(state, goals);
        bd.addProperty("active", active);
        bd.addProperty("phase", phase.name());
        bd.addProperty("pending_request", StorageRequest.hasPending());
        bd.addProperty("free_slots", state.freeSlots);

        if (currentRequest != null) {
            bd.addProperty("task_id", currentRequest.taskId);
            bd.addProperty("request_type", currentRequest.type.name());
        }
        if (containerPos != null) {
            bd.addProperty("container_pos",
                    containerPos.getX() + "," + containerPos.getY() + "," + containerPos.getZ());
        }
        bd.addProperty("items_transferred", transferredCounts.size());
        return bd;
    }
}
