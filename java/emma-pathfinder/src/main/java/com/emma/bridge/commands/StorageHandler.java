package com.emma.bridge.commands;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.events.ContainerTracker;
import com.emma.bridge.goap.StorageRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles storage-related bridge commands.
 *
 * Scan detects container blocks and enriches results with ContainerTracker cache.
 * Total counts items across inventory + overflow + cached containers.
 * Deposit/withdraw queue StorageRequests for the GOAP StoreItemsAction to execute.
 */
public class StorageHandler implements ICommandHandler {

    private ContainerTracker containerTracker;

    /** Set the ContainerTracker after construction (wired in EmmaBridgeClient). */
    public void setContainerTracker(ContainerTracker tracker) {
        this.containerTracker = tracker;
    }

    @Override
    public String getCommand() {
        return "storage";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        String action = params.has("action") ? params.get("action").getAsString() : "";

        return switch (action) {
            case "scan" -> handleScan(params);
            case "deposit" -> handleDeposit(params);
            case "withdraw" -> handleWithdraw(params);
            case "deposit_nearby" -> handleDepositNearby(params);
            case "total" -> handleTotal(params);
            case "base_inventory" -> handleStubbed("base_inventory");
            case "save_cache" -> handleSaveCache();
            default -> throw new IllegalArgumentException(
                    "Unknown storage action: '" + action + "'. " +
                    "Valid actions: scan, deposit, withdraw, deposit_nearby, total, base_inventory, save_cache");
        };
    }

    /**
     * Stubbed handler for actions not yet implemented.
     */
    private JsonObject handleStubbed(String action) {
        JsonObject result = new JsonObject();
        result.addProperty("error", "storage " + action + " not yet implemented");
        EmmaBridgeMod.LOGGER.warn("[Emma Bridge] storage {} not yet implemented", action);
        return result;
    }

    // ── storage_scan — detect container blocks in radius ─────────────

    private JsonObject handleScan(JsonObject params) {
        LocalPlayer player = Minecraft.getInstance().player;
        Level level = Minecraft.getInstance().level;
        if (player == null || level == null) throw new RuntimeException("No player/world");

        int radius = params.has("radius") ? params.get("radius").getAsInt() : 32;
        BlockPos playerPos = player.blockPosition();

        JsonArray containers = new JsonArray();
        int minX = playerPos.getX() - radius, maxX = playerPos.getX() + radius;
        int minY = Math.max(playerPos.getY() - 8, level.getMinY());
        int maxY = Math.min(playerPos.getY() + 8, level.getMaxY());
        int minZ = playerPos.getZ() - radius, maxZ = playerPos.getZ() + radius;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    Block block = state.getBlock();

                    String containerType = null;
                    if (block instanceof ChestBlock) containerType = "chest";
                    else if (block instanceof BarrelBlock) containerType = "barrel";
                    else if (block instanceof ShulkerBoxBlock) containerType = "shulker_box";

                    if (containerType != null) {
                        double dist = Math.sqrt(playerPos.distSqr(pos));
                        JsonObject entry = new JsonObject();
                        entry.addProperty("type", containerType);
                        entry.addProperty("x", x);
                        entry.addProperty("y", y);
                        entry.addProperty("z", z);
                        entry.addProperty("distance", Math.round(dist * 10.0) / 10.0);

                        // Enrich with cached container contents
                        if (containerTracker != null && containerTracker.hasData(pos)) {
                            ContainerTracker.ContainerSnapshot snapshot = containerTracker.getSnapshot(pos);
                            entry.add("items", snapshot.slotDetails.deepCopy());
                            entry.addProperty("items_known", true);
                            entry.addProperty("empty_slots", snapshot.emptySlots);
                            entry.addProperty("total_slots", snapshot.totalSlots);
                        } else {
                            entry.add("items", new JsonArray());
                            entry.addProperty("items_known", false);
                        }

                        containers.add(entry);
                    }
                }
            }
        }

        JsonObject result = new JsonObject();
        result.add("containers", containers);
        result.addProperty("count", containers.size());
        result.addProperty("radius", radius);
        return result;
    }

    // ── storage_deposit — queue deposit request for GOAP ─────────────

    private JsonObject handleDeposit(JsonObject params) {
        Map<String, Integer> items = parseItemList(params);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("No items specified for deposit");
        }

        BlockPos pos = parsePos(params);
        if (pos == null) {
            throw new IllegalArgumentException("No position specified for deposit (use deposit_nearby for auto-find)");
        }

        StorageRequest request = new StorageRequest(StorageRequest.Type.DEPOSIT, pos, items);
        StorageRequest.submit(request);

        JsonObject result = new JsonObject();
        result.addProperty("status", "queued");
        result.addProperty("task_id", request.taskId);
        result.addProperty("type", "deposit");
        result.addProperty("item_count", items.size());
        EmmaBridgeMod.LOGGER.info("[Storage] Deposit queued: {} item types at [{},{},{}] task={}",
                items.size(), pos.getX(), pos.getY(), pos.getZ(), request.taskId);
        return result;
    }

    // ── storage_withdraw — queue withdraw request for GOAP ───────────

    private JsonObject handleWithdraw(JsonObject params) {
        Map<String, Integer> items = parseItemList(params);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("No items specified for withdraw");
        }

        BlockPos pos = parsePos(params);
        if (pos == null) {
            throw new IllegalArgumentException("No position specified for withdraw");
        }

        StorageRequest request = new StorageRequest(StorageRequest.Type.WITHDRAW, pos, items);
        StorageRequest.submit(request);

        JsonObject result = new JsonObject();
        result.addProperty("status", "queued");
        result.addProperty("task_id", request.taskId);
        result.addProperty("type", "withdraw");
        result.addProperty("item_count", items.size());
        EmmaBridgeMod.LOGGER.info("[Storage] Withdraw queued: {} item types from [{},{},{}] task={}",
                items.size(), pos.getX(), pos.getY(), pos.getZ(), request.taskId);
        return result;
    }

    // ── storage_deposit_nearby — queue deposit to nearest container ───

    private JsonObject handleDepositNearby(JsonObject params) {
        Map<String, Integer> items = parseItemList(params);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("No items specified for deposit_nearby");
        }

        // pos=null signals StoreItemsAction to find nearest container
        StorageRequest request = new StorageRequest(StorageRequest.Type.DEPOSIT, null, items);
        StorageRequest.submit(request);

        JsonObject result = new JsonObject();
        result.addProperty("status", "queued");
        result.addProperty("task_id", request.taskId);
        result.addProperty("type", "deposit_nearby");
        result.addProperty("item_count", items.size());
        EmmaBridgeMod.LOGGER.info("[Storage] Deposit nearby queued: {} item types task={}",
                items.size(), request.taskId);
        return result;
    }

    // ── storage_total — inventory + overflow + cached containers ─────

    private JsonObject handleTotal(JsonObject params) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new RuntimeException("No player entity");

        JsonArray itemNames = params.has("items") ? params.getAsJsonArray("items") : new JsonArray();
        JsonObject totals = new JsonObject();

        // Get aggregated container counts from cache
        Map<String, Integer> containerCounts = containerTracker != null
                ? containerTracker.getAggregatedItemCounts()
                : Map.of();

        for (JsonElement elem : itemNames) {
            String itemName = elem.getAsString();
            String fullName = itemName.contains(":") ? itemName : "minecraft:" + itemName;

            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(fullName));
            if (item == null || item == Items.AIR) {
                EmmaBridgeMod.LOGGER.warn("[Storage] Unknown item: {}", fullName);
                continue;
            }

            // Count from player inventory
            int invCount = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                var stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    invCount += stack.getCount();
                }
            }

            // Overflow count (virtual storage mod)
            int overflowCount = 0;
            try {
                if (com.emma.overflow.OverflowClientMod.OverflowClientApi.isAvailable()) {
                    JsonObject cached = com.emma.overflow.OverflowClientMod.OverflowClientApi.getCachedStatus();
                    if (cached != null && cached.has("items")) {
                        for (JsonElement oElem : cached.getAsJsonArray("items")) {
                            JsonObject oObj = oElem.getAsJsonObject();
                            if (oObj.get("item").getAsString().equals(fullName)) {
                                overflowCount = oObj.get("count").getAsInt();
                            }
                        }
                    }
                }
            } catch (NoClassDefFoundError | Exception ignored) {}

            // Container count from cache
            int containerCount = containerCounts.getOrDefault(fullName, 0);

            JsonObject itemTotals = new JsonObject();
            itemTotals.addProperty("inventory", invCount);
            itemTotals.addProperty("containers", containerCount);
            itemTotals.addProperty("overflow", overflowCount);
            itemTotals.addProperty("total", invCount + overflowCount + containerCount);
            totals.add(itemName, itemTotals);
        }

        JsonObject result = new JsonObject();
        result.add("totals", totals);
        return result;
    }

    // ── storage_save_cache — no-op, persistence is via WebSocket events ─

    private JsonObject handleSaveCache() {
        // Container cache is automatically broadcast via container_contents events.
        // Python side persists to SQLite on each event.
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        result.addProperty("message", "Container cache is auto-persisted via container_contents events");
        if (containerTracker != null) {
            result.addProperty("cached_containers", containerTracker.getAllSnapshots().size());
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Parse items array from params: [{item, count}, ...] or {items: [...]} */
    private Map<String, Integer> parseItemList(JsonObject params) {
        Map<String, Integer> items = new HashMap<>();
        if (!params.has("items")) return items;

        JsonArray itemArr = params.getAsJsonArray("items");
        for (JsonElement elem : itemArr) {
            JsonObject obj = elem.getAsJsonObject();
            String itemName = obj.get("item").getAsString();
            String fullName = itemName.contains(":") ? itemName : "minecraft:" + itemName;
            int count = obj.has("count") ? obj.get("count").getAsInt() : 1;
            items.merge(fullName, count, Integer::sum);
        }
        return items;
    }

    /** Parse pos array from params: [x, y, z] */
    private BlockPos parsePos(JsonObject params) {
        if (!params.has("pos")) return null;
        JsonArray posArr = params.getAsJsonArray("pos");
        if (posArr.size() < 3) return null;
        return new BlockPos(
                posArr.get(0).getAsInt(),
                posArr.get(1).getAsInt(),
                posArr.get(2).getAsInt()
        );
    }
}
