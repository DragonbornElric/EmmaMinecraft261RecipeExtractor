package com.emma.overflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * Server-side entrypoint for the overflow system.
 *
 * Maintains a {@link SimpleContainer} per connected player. Handles C2S
 * packets to deposit, withdraw, trash, clear junk, and free inventory slots.
 * Sends S2C responses back with results.
 */
public class OverflowServerMod implements DedicatedServerModInitializer {

    /** Overflow size — 2048 slots (large virtual storage). */
    private static final int OVERFLOW_SIZE = 2048;

    /** Per-player overflow inventories, keyed by UUID. */
    private static final Map<UUID, SimpleContainer> overflows = new HashMap<>();

    /** Saved overflow data for persistence across sessions. */
    private static final Map<UUID, ListTag> savedOverflows = new HashMap<>();

    private static MinecraftServer server;

    @Override
    public void onInitializeServer() {
        // Player join: create or restore overflow
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            UUID uuid = handler.getPlayer().getUUID();
            SimpleContainer overflow = new SimpleContainer(OVERFLOW_SIZE);

            // Restore from saved data if available
            ListTag saved = savedOverflows.remove(uuid);
            if (saved != null) {
                HolderLookup.Provider lookup = srv.registryAccess();
                readNbtIntoInventory(overflow, saved, lookup);
                OverflowMod.LOGGER.info("[EmmaOverflow] Restored {} overflow items for {}",
                        countNonEmpty(overflow), handler.getPlayer().getName().getString());
            }

            overflows.put(uuid, overflow);
        });

        // Player disconnect: save overflow for persistence
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            UUID uuid = handler.getPlayer().getUUID();
            SimpleContainer overflow = overflows.remove(uuid);
            if (overflow != null && countNonEmpty(overflow) > 0) {
                HolderLookup.Provider lookup = srv.registryAccess();
                savedOverflows.put(uuid, writeInventoryToNbt(overflow, lookup));
                OverflowMod.LOGGER.info("[EmmaOverflow] Saved overflow for {} ({} items)",
                        handler.getPlayer().getName().getString(), countNonEmpty(overflow));
                // Persist to disk immediately for crash safety
                saveAllToDisk(srv);
            }
        });

        // Server starting: capture reference
        ServerLifecycleEvents.SERVER_STARTING.register(srv -> server = srv);

        // Server started: load overflow data from disk (world save path ready)
        ServerLifecycleEvents.SERVER_STARTED.register(OverflowServerMod::loadAllFromDisk);

        // Server stopping: persist all overflow data to disk
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            // Serialize online players' overflows into savedOverflows first
            HolderLookup.Provider lookup = srv.registryAccess();
            for (Map.Entry<UUID, SimpleContainer> entry : overflows.entrySet()) {
                if (countNonEmpty(entry.getValue()) > 0) {
                    savedOverflows.put(entry.getKey(), writeInventoryToNbt(entry.getValue(), lookup));
                }
            }
            saveAllToDisk(srv);
        });

        // Register the C2S packet handler
        ServerPlayNetworking.registerGlobalReceiver(
                OverflowPayloads.Request.ID,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    String requestId = payload.requestId();
                    String jsonStr = payload.jsonPayload();

                    // Process on server thread (already there in Fabric handler)
                    String result;
                    try {
                        JsonObject request = JsonParser.parseString(jsonStr).getAsJsonObject();
                        result = handleRequest(player, request).toString();
                    } catch (Exception e) {
                        JsonObject err = new JsonObject();
                        err.addProperty("success", false);
                        err.addProperty("error", e.getMessage());
                        result = err.toString();
                        OverflowMod.LOGGER.warn("[EmmaOverflow] Error handling request: {}", e.getMessage());
                    }

                    // Send response back to client
                    ServerPlayNetworking.send(player,
                            new OverflowPayloads.Response(requestId, result));
                }
        );

        // Register /emma_extract command — one-time extraction of all recipe + block + mob data
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("emma_extract")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER)) // op only
                    .executes(context -> {
                        MinecraftServer srv = context.getSource().getServer();
                        try {
                            String json = RecipeExtractor.extractAll(srv);
                            int count = RecipeExtractor.countEntries(json);
                            Path output = srv.getServerDirectory().resolve("emma_extracted_recipes.json");
                            Files.writeString(output, json);
                            context.getSource().sendSuccess(
                                    () -> Component.literal("[EmmaExtract] Wrote " + count
                                            + " entries to " + output.getFileName()),
                                    true);
                        } catch (Exception e) {
                            OverflowMod.LOGGER.error("[EmmaExtract] Extraction failed", e);
                            context.getSource().sendFailure(
                                    Component.literal("[EmmaExtract] Failed: " + e.getMessage()));
                        }
                        return 1;
                    }));
        });

        OverflowMod.LOGGER.info("[EmmaOverflow] Server-side initialized");
    }

    // ── Request dispatcher ─────────────────────────────────────────────

    private JsonObject handleRequest(ServerPlayer player, JsonObject request) {
        String action = request.has("action") ? request.get("action").getAsString() : "";

        return switch (action) {
            case "trash" -> handleTrash(player, request);
            case "deposit" -> handleDeposit(player, request);
            case "withdraw" -> handleWithdraw(player, request);
            case "status" -> handleStatus(player);
            case "clear_junk" -> handleClearJunk(player, request);
            case "free_slots" -> handleFreeSlots(player, request);
            default -> {
                JsonObject err = new JsonObject();
                err.addProperty("success", false);
                err.addProperty("error", "Unknown action: " + action);
                yield err;
            }
        };
    }

    // ── TRASH: destroy items from player inventory ─────────────────────

    private JsonObject handleTrash(ServerPlayer player, JsonObject request) {
        Inventory inv = player.getInventory();
        JsonArray items = request.getAsJsonArray("items");
        JsonArray trashed = new JsonArray();
        int totalTrashed = 0;

        for (JsonElement elem : items) {
            JsonObject itemObj = elem.getAsJsonObject();
            String itemId = itemObj.get("item").getAsString();
            int count = itemObj.has("count") ? itemObj.get("count").getAsInt() : 64;

            Item item = resolveItem(itemId);
            if (item == null) continue;

            int removed = removeFromInventory(inv, item, count);
            if (removed > 0) {
                JsonObject entry = new JsonObject();
                entry.addProperty("item", itemId);
                entry.addProperty("count", removed);
                trashed.add(entry);
                totalTrashed += removed;
            }
        }

        player.getInventory().setChanged();
        player.inventoryMenu.sendAllDataToRemote();

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.add("trashed", trashed);
        result.addProperty("total_trashed", totalTrashed);
        result.addProperty("inventory_free", countFreeSlots(inv));
        return result;
    }

    // ── DEPOSIT: move items from inventory → overflow ──────────────────

    private JsonObject handleDeposit(ServerPlayer player, JsonObject request) {
        Inventory inv = player.getInventory();
        SimpleContainer overflow = getOverflow(player);
        JsonArray items = request.getAsJsonArray("items");
        JsonArray deposited = new JsonArray();

        for (JsonElement elem : items) {
            JsonObject itemObj = elem.getAsJsonObject();
            String itemId = itemObj.get("item").getAsString();
            int count = itemObj.has("count") ? itemObj.get("count").getAsInt() : 64;

            Item item = resolveItem(itemId);
            if (item == null) continue;

            // Safety: only overflow stackable, non-enchanted items
            ItemStack testStack = new ItemStack(item);
            if (!canOverflow(testStack)) {
                OverflowMod.LOGGER.debug("[EmmaOverflow] Skipping non-overflowable: {}", itemId);
                continue;
            }

            // Remove from player inventory
            int removed = removeFromInventory(inv, item, count);
            if (removed > 0) {
                // Add to overflow (SimpleContainer handles stacking)
                ItemStack toStore = new ItemStack(item, removed);
                ItemStack leftover = overflow.addItem(toStore);
                int actuallyStored = removed - leftover.getCount();

                if (leftover.getCount() > 0) {
                    // Overflow is full — put the remainder back in player inventory
                    inv.add(leftover);
                }

                if (actuallyStored > 0) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("item", itemId);
                    entry.addProperty("count", actuallyStored);
                    deposited.add(entry);
                }
            }
        }

        player.getInventory().setChanged();
        player.inventoryMenu.sendAllDataToRemote();

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.add("deposited", deposited);
        mergeOverflowSummary(result, overflow);
        return result;
    }

    // ── WITHDRAW: move items from overflow → inventory ─────────────────

    private JsonObject handleWithdraw(ServerPlayer player, JsonObject request) {
        Inventory inv = player.getInventory();
        SimpleContainer overflow = getOverflow(player);
        JsonArray items = request.getAsJsonArray("items");
        JsonArray withdrawn = new JsonArray();

        for (JsonElement elem : items) {
            JsonObject itemObj = elem.getAsJsonObject();
            String itemId = itemObj.get("item").getAsString();
            int count = itemObj.has("count") ? itemObj.get("count").getAsInt() : 64;

            Item item = resolveItem(itemId);
            if (item == null) continue;

            // Remove from overflow
            int removed = removeFromSimpleInventory(overflow, item, count);
            if (removed > 0) {
                // Insert into player inventory — add modifies stack in-place,
                // reducing count by amount inserted, returns true if fully inserted
                ItemStack toInsert = new ItemStack(item, removed);
                int beforeCount = toInsert.getCount();
                inv.add(toInsert);
                int leftover = toInsert.getCount(); // remaining after insertion

                if (leftover > 0) {
                    // Player inventory full — put remainder back in overflow
                    overflow.addItem(toInsert);
                }

                int actuallyWithdrawn = beforeCount - leftover;
                if (actuallyWithdrawn > 0) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("item", itemId);
                    entry.addProperty("count", actuallyWithdrawn);
                    withdrawn.add(entry);
                }
            }
        }

        player.getInventory().setChanged();
        player.inventoryMenu.sendAllDataToRemote();

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.add("withdrawn", withdrawn);
        result.addProperty("inventory_free", countFreeSlots(inv));
        mergeOverflowSummary(result, overflow);
        return result;
    }

    // ── STATUS: report overflow contents ───────────────────────────────

    private JsonObject handleStatus(ServerPlayer player) {
        SimpleContainer overflow = getOverflow(player);
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        mergeOverflowSummary(result, overflow);
        return result;
    }

    // ── CLEAR_JUNK: client sends junk list, server removes matches ─────

    private JsonObject handleClearJunk(ServerPlayer player, JsonObject request) {
        Inventory inv = player.getInventory();
        Set<Item> junkItems = parseJunkList(request);

        JsonArray cleared = new JsonArray();
        Map<String, Integer> clearCounts = new LinkedHashMap<>();

        // Scan main inventory (slots 0-35) + offhand
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && junkItems.contains(stack.getItem())) {
                // Don't trash enchanted or custom-named items
                if (stack.isEnchanted()) continue;
                if (stack.has(DataComponents.CUSTOM_NAME)) continue;

                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                clearCounts.merge(id, stack.getCount(), Integer::sum);
                inv.setItem(i, ItemStack.EMPTY);
            }
        }

        for (Map.Entry<String, Integer> entry : clearCounts.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("item", entry.getKey());
            obj.addProperty("count", entry.getValue());
            cleared.add(obj);
        }

        player.getInventory().setChanged();
        player.inventoryMenu.sendAllDataToRemote();

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.add("cleared", cleared);
        result.addProperty("total_cleared", clearCounts.values().stream().mapToInt(i -> i).sum());
        result.addProperty("inventory_free", countFreeSlots(inv));
        return result;
    }

    // ── FREE_SLOTS: proactively free N slots ───────────────────────────

    private JsonObject handleFreeSlots(ServerPlayer player, JsonObject request) {
        int target = request.has("target") ? request.get("target").getAsInt() : 5;
        int foodThreshold = request.has("food_threshold") ? request.get("food_threshold").getAsInt() : 0;
        Inventory inv = player.getInventory();
        SimpleContainer overflow = getOverflow(player);
        Set<Item> junkItems = parseJunkList(request);
        Set<Item> protectedItems = parseProtectedList(request);

        int initialFree = countFreeSlots(inv);
        if (initialFree >= target) {
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("slots_freed", 0);
            result.addProperty("inventory_free", initialFree);
            result.addProperty("method", "none_needed");
            mergeOverflowSummary(result, overflow);
            return result;
        }

        int needed = target - initialFree;
        String method = "none";

        // Calculate current food score in inventory (for threshold-aware food overflow)
        int currentFoodScore = 0;
        if (foodThreshold > 0) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && s.has(DataComponents.FOOD)) {
                    var food = s.get(DataComponents.FOOD);
                    if (food != null) {
                        currentFoodScore += food.nutrition() * s.getCount();
                    }
                }
            }
        }

        // Phase 1: Deposit overflowable items (largest stacks first)
        // Overflow is always preferred over trashing — items can be retrieved later.
        if (needed > 0) {
            // Build list of overflowable slots sorted by stack size (largest first)
            List<int[]> candidates = new ArrayList<>(); // [slot, count]
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && canOverflow(stack)
                        && !protectedItems.contains(stack.getItem())) {
                    candidates.add(new int[]{i, stack.getCount()});
                }
            }
            candidates.sort((a, b) -> Integer.compare(b[1], a[1])); // descending by count

            for (int[] candidate : candidates) {
                if (needed <= 0) break;
                int slot = candidate[0];
                ItemStack stack = inv.getItem(slot);
                if (stack.isEmpty()) continue;

                // Food threshold check: don't deposit food if it would drop below threshold
                if (foodThreshold > 0 && stack.has(DataComponents.FOOD)) {
                    var food = stack.get(DataComponents.FOOD);
                    int stackFoodValue = (food != null) ? food.nutrition() * stack.getCount() : 0;
                    if (currentFoodScore - stackFoodValue < foodThreshold) {
                        continue; // Skip — would drop food below threshold
                    }
                }

                ItemStack leftover = overflow.addItem(stack.copy());
                if (leftover.getCount() < stack.getCount()) {
                    // At least some items went into overflow
                    if (leftover.isEmpty()) {
                        inv.setItem(slot, ItemStack.EMPTY);
                        needed--;
                    } else {
                        inv.setItem(slot, leftover);
                    }
                    method = "overflow";

                    // Update running food score if we deposited food
                    if (stack.has(DataComponents.FOOD)) {
                        var food = stack.get(DataComponents.FOOD);
                        if (food != null) {
                            int depositedCount = stack.getCount() - leftover.getCount();
                            currentFoodScore -= food.nutrition() * depositedCount;
                        }
                    }
                }

                if (countFreeSlots(overflow) == 0) break; // Overflow full
            }
        }

        // Phase 2: Trash junk items only if overflow didn't free enough slots
        if (needed > 0 && !junkItems.isEmpty()) {
            for (int i = 0; i < inv.getContainerSize() && needed > 0; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && junkItems.contains(stack.getItem())) {
                    if (stack.isEnchanted()) continue;
                    if (stack.has(DataComponents.CUSTOM_NAME)) continue;

                    inv.setItem(i, ItemStack.EMPTY);
                    needed--;
                    method = method.equals("overflow") ? "both" : "trash";
                }
            }
        }

        player.getInventory().setChanged();
        player.inventoryMenu.sendAllDataToRemote();

        int finalFree = countFreeSlots(inv);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("slots_freed", finalFree - initialFree);
        result.addProperty("inventory_free", finalFree);
        result.addProperty("method", method);
        mergeOverflowSummary(result, overflow);
        return result;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private SimpleContainer getOverflow(ServerPlayer player) {
        return overflows.computeIfAbsent(player.getUUID(), k -> new SimpleContainer(OVERFLOW_SIZE));
    }

    /**
     * Returns true if this item can safely be stored in overflow.
     * Only generic stackable items — no tools, armor, enchanted, or critical items.
     */
    static boolean canOverflow(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getMaxStackSize() <= 1) return false; // tools, armor, non-stackable
        if (stack.isEnchanted()) return false;
        if (stack.has(DataComponents.CUSTOM_NAME)) return false;

        // Food CAN go to overflow — handleFreeSlots() uses food_threshold to keep
        // enough food in inventory. FoodChain withdraws from overflow when hungry.

        // Critical items that must never leave the real inventory
        Item item = stack.getItem();
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        return !CRITICAL_ITEMS.contains(id);
    }

    /** Items that must never go into overflow, even if stackable. */
    private static final Set<String> CRITICAL_ITEMS = Set.of(
            "minecraft:ender_eye",
            "minecraft:ender_pearl",
            "minecraft:blaze_rod",
            "minecraft:blaze_powder",
            "minecraft:diamond",
            "minecraft:netherite_ingot",
            "minecraft:totem_of_undying",
            "minecraft:enchanted_golden_apple",
            "minecraft:nether_star",
            "minecraft:elytra",
            "minecraft:shulker_box",
            "minecraft:obsidian",
            "minecraft:crying_obsidian",
            "minecraft:torch",
            "minecraft:soul_torch",
            "minecraft:netherite_scrap",
            "minecraft:emerald",
            "minecraft:lapis_lazuli"
    );

    private static Item resolveItem(String itemId) {
        String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        Identifier id = Identifier.parse(fullId);
        Item item = BuiltInRegistries.ITEM.getValue(id);
        // BuiltInRegistries.ITEM.getValue returns Items.AIR for unknown IDs
        if (BuiltInRegistries.ITEM.getKey(item).toString().equals("minecraft:air") && !fullId.equals("minecraft:air")) {
            OverflowMod.LOGGER.warn("[EmmaOverflow] Unknown item: {}", fullId);
            return null;
        }
        return item;
    }

    /**
     * Remove up to {@code count} of {@code item} from an Inventory.
     * Scans all main inventory slots (0 through size-1).
     */
    private static int removeFromInventory(Inventory inv, Item item, int count) {
        int remaining = count;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                remaining -= take;
            }
        }
        return count - remaining;
    }

    /**
     * Remove up to {@code count} of {@code item} from a SimpleContainer.
     */
    private static int removeFromSimpleInventory(SimpleContainer inv, Item item, int count) {
        int remaining = count;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
                remaining -= take;
            }
        }
        return count - remaining;
    }

    /** Count empty slots in an Inventory (main inventory only, slots 0-35). */
    private static int countFreeSlots(Inventory inv) {
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) free++;
        }
        return free;
    }

    /** Count empty slots in a SimpleContainer. */
    private static int countFreeSlots(SimpleContainer inv) {
        int free = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) free++;
        }
        return free;
    }

    /** Count non-empty slots. */
    private static int countNonEmpty(SimpleContainer inv) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) count++;
        }
        return count;
    }

    /**
     * Build a standard overflow summary: items array, used_slots, free_slots, capacity.
     * Including "capacity" triggers cache update on the client (OverflowClientApi).
     */
    private JsonObject buildOverflowSummary(SimpleContainer overflow) {
        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        for (int i = 0; i < overflow.getContainerSize(); i++) {
            ItemStack stack = overflow.getItem(i);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                itemCounts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        JsonArray itemsArray = new JsonArray();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("item", entry.getKey());
            obj.addProperty("count", entry.getValue());
            itemsArray.add(obj);
        }
        JsonObject summary = new JsonObject();
        summary.add("items", itemsArray);
        summary.addProperty("used_slots", OVERFLOW_SIZE - countFreeSlots(overflow));
        summary.addProperty("free_slots", countFreeSlots(overflow));
        summary.addProperty("capacity", OVERFLOW_SIZE);
        return summary;
    }

    /** Merge overflow summary fields into an existing result object. */
    private void mergeOverflowSummary(JsonObject result, SimpleContainer overflow) {
        JsonObject summary = buildOverflowSummary(overflow);
        for (String key : summary.keySet()) {
            result.add(key, summary.get(key));
        }
    }

    /** Parse the protected_items array from the request JSON. */
    private static Set<Item> parseProtectedList(JsonObject request) {
        Set<Item> items = new HashSet<>();
        if (request.has("protected_items")) {
            for (JsonElement elem : request.getAsJsonArray("protected_items")) {
                Item item = resolveItem(elem.getAsString());
                if (item != null) items.add(item);
            }
        }
        return items;
    }

    /** Parse the junk_items array from the request JSON. */
    private static Set<Item> parseJunkList(JsonObject request) {
        Set<Item> junkItems = new HashSet<>();
        if (request.has("junk_items")) {
            for (JsonElement elem : request.getAsJsonArray("junk_items")) {
                Item item = resolveItem(elem.getAsString());
                if (item != null) junkItems.add(item);
            }
        }
        return junkItems;
    }

    // ── Serialization ─────────────────────────────────────────────────
    // Overflow only stores generic stackable items (no enchantments, no NBT),
    // so we just persist item ID + count. No need for full ItemStack codec.

    /** Serialize a SimpleContainer to a ListTag of {Slot, Id, Count} compounds. */
    private static ListTag writeInventoryToNbt(SimpleContainer inv, HolderLookup.Provider lookup) {
        ListTag list = new ListTag();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag tag = new CompoundTag();
                tag.putShort("Slot", (short) i);
                tag.putString("Id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                tag.putInt("Count", stack.getCount());
                list.add(tag);
            }
        }
        return list;
    }

    /** Restore a SimpleContainer from a ListTag written by writeInventoryToNbt. */
    private static void readNbtIntoInventory(SimpleContainer inv, ListTag list, HolderLookup.Provider lookup) {
        inv.clearContent();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = (CompoundTag) list.get(i);
            int slot = tag.getShortOr("Slot", (short) 0) & 0xFFFF;
            String id = tag.getStringOr("Id", "");
            int count = tag.getIntOr("Count", 0);
            if (slot < inv.getContainerSize() && !id.isEmpty() && count > 0) {
                Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
                if (item != null) {
                    inv.setItem(slot, new ItemStack(item, count));
                }
            }
        }
    }

    // ── Disk persistence ───────────────────────────────────────────────

    /** Save all overflow data (online + disconnected players) to world/emma_overflow.nbt. */
    private static void saveAllToDisk(MinecraftServer srv) {
        Path savePath = srv.getWorldPath(LevelResource.ROOT).resolve("emma_overflow.nbt");
        HolderLookup.Provider lookup = srv.registryAccess();

        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();

        // Save currently online player overflows
        for (Map.Entry<UUID, SimpleContainer> entry : overflows.entrySet()) {
            if (countNonEmpty(entry.getValue()) > 0) {
                players.put(entry.getKey().toString(), writeInventoryToNbt(entry.getValue(), lookup));
            }
        }

        // Save disconnected player overflows (don't overwrite online data)
        for (Map.Entry<UUID, ListTag> entry : savedOverflows.entrySet()) {
            if (!players.contains(entry.getKey().toString())) {
                players.put(entry.getKey().toString(), entry.getValue());
            }
        }

        root.put("Players", players);

        try {
            NbtIo.writeCompressed(root, savePath);
            OverflowMod.LOGGER.info("[EmmaOverflow] Saved {} player overflow(s) to disk", players.size());
        } catch (IOException e) {
            OverflowMod.LOGGER.error("[EmmaOverflow] Failed to save overflow data to disk", e);
        }
    }

    /** Load overflow data from world/emma_overflow.nbt into savedOverflows map. */
    private static void loadAllFromDisk(MinecraftServer srv) {
        Path savePath = srv.getWorldPath(LevelResource.ROOT).resolve("emma_overflow.nbt");

        if (!Files.exists(savePath)) {
            OverflowMod.LOGGER.info("[EmmaOverflow] No saved overflow data found (first run)");
            return;
        }

        try {
            CompoundTag root = NbtIo.readCompressed(savePath, NbtAccounter.unlimitedHeap());
            CompoundTag players = root.getCompoundOrEmpty("Players");

            int count = 0;
            for (String uuidStr : players.keySet()) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    ListTag items = players.getListOrEmpty(uuidStr);
                    if (!items.isEmpty()) {
                        savedOverflows.put(uuid, items);
                        count++;
                    }
                } catch (IllegalArgumentException e) {
                    OverflowMod.LOGGER.warn("[EmmaOverflow] Skipping invalid UUID key: {}", uuidStr);
                }
            }

            OverflowMod.LOGGER.info("[EmmaOverflow] Loaded {} player overflow(s) from disk", count);
        } catch (IOException e) {
            OverflowMod.LOGGER.error("[EmmaOverflow] Failed to load overflow data from disk", e);
        }
    }

    // ── Public API for death handling ──────────────────────────────────

    /**
     * Clear a player's overflow on death. Called from a death event listener
     * if you want overflow to be lost on death (matching normal inventory behavior).
     */
    public static void clearOnDeath(UUID playerUuid) {
        SimpleContainer overflow = overflows.get(playerUuid);
        if (overflow != null) {
            overflow.clearContent();
            savedOverflows.remove(playerUuid);
            if (server != null) {
                saveAllToDisk(server);
            }
            OverflowMod.LOGGER.info("[EmmaOverflow] Cleared overflow on death for {}", playerUuid);
        }
    }
}
