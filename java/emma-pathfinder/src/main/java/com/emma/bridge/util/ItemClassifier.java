package com.emma.bridge.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Shared item classification helpers — single source of truth for material tiers,
 * tool/armor categories, item IDs, and inventory slot math.
 *
 * Previously duplicated across EquipBestArmorAction, ArmorEquipReflex, and StoreItemsAction.
 */
public final class ItemClassifier {

    private ItemClassifier() {}

    // ── Material tiers (higher = better) ─────────────────────────

    public static final int TIER_UNKNOWN   = -100;
    public static final int TIER_WOODEN    = -2;
    public static final int TIER_STONE     = -1;
    public static final int TIER_LEATHER   = 0;
    public static final int TIER_GOLD      = 1;
    public static final int TIER_CHAINMAIL = 2;
    public static final int TIER_IRON      = 3;
    public static final int TIER_DIAMOND   = 4;
    public static final int TIER_NETHERITE = 5;

    /** Tool tiers, best first (lower index = better tier). Used by {@link #getToolTierRank}. */
    private static final String[] TOOL_TIER_ORDER = {
        "netherite", "diamond", "iron", "stone", "wooden", "golden"
    };

    /** Armor tiers, best first (lower index = better tier). Used by {@link #getArmorTierRank}. */
    private static final String[] ARMOR_TIER_ORDER = {
        "netherite", "diamond", "iron", "chainmail", "golden", "leather"
    };

    /**
     * Returns a material tier score where higher = better.
     * -2=wooden, -1=stone, 0=leather, 1=gold, 2=chainmail, 3=iron, 4=diamond, 5=netherite.
     * Returns {@link Integer#MIN_VALUE} for unknown materials.
     */
    public static int getMaterialTier(String item) {
        if (item == null) return TIER_UNKNOWN;
        if (item.contains("netherite")) return TIER_NETHERITE;
        if (item.contains("diamond"))   return TIER_DIAMOND;
        if (item.contains("iron"))      return TIER_IRON;
        if (item.contains("chainmail")) return TIER_CHAINMAIL;
        if (item.contains("golden") || item.contains("gold")) return TIER_GOLD;
        if (item.contains("leather"))   return TIER_LEATHER;
        if (item.contains("stone"))     return TIER_STONE;
        if (item.contains("wooden"))    return TIER_WOODEN;
        return TIER_UNKNOWN;
    }

    /**
     * Returns tool tier rank where lower = better (0=netherite, 5=golden).
     * Returns {@link Integer#MAX_VALUE} if unknown.
     * Used by StoreItemsAction for "keep best tool" comparisons.
     */
    public static int getToolTierRank(String itemId) {
        String id = stripNamespace(itemId);
        for (int i = 0; i < TOOL_TIER_ORDER.length; i++) {
            if (id.startsWith(TOOL_TIER_ORDER[i] + "_")) return i;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Returns armor tier rank where lower = better (0=netherite, 5=leather).
     * Returns {@link Integer#MAX_VALUE} if unknown.
     * Used by StoreItemsAction for "keep best armor" comparisons.
     */
    public static int getArmorTierRank(String itemId) {
        String id = stripNamespace(itemId);
        for (int i = 0; i < ARMOR_TIER_ORDER.length; i++) {
            if (id.startsWith(ARMOR_TIER_ORDER[i] + "_")) return i;
        }
        return Integer.MAX_VALUE;
    }

    // ── Category detection ───────────────────────────────────────

    /**
     * Returns the tool/weapon category ("pickaxe", "axe", "shovel", "sword", "hoe") or null.
     */
    public static String getToolCategory(String itemId) {
        String id = stripNamespace(itemId);
        if (id.endsWith("_pickaxe")) return "pickaxe";
        if (id.endsWith("_axe"))     return "axe";
        if (id.endsWith("_shovel"))  return "shovel";
        if (id.endsWith("_sword"))   return "sword";
        if (id.endsWith("_hoe"))     return "hoe";
        return null;
    }

    /**
     * Returns the armor slot for an item, or null if not armor.
     * Handles both standard names (_helmet) and leather variants (_cap, _tunic, _pants).
     */
    public static EquipmentSlot getArmorSlot(String item) {
        if (item == null) return null;
        if (item.contains("helmet") || item.contains("cap")) return EquipmentSlot.HEAD;
        if (item.contains("chestplate") || item.contains("tunic")) return EquipmentSlot.CHEST;
        if (item.contains("leggings") || item.contains("pants")) return EquipmentSlot.LEGS;
        if (item.contains("boots")) return EquipmentSlot.FEET;
        return null;
    }

    /**
     * Returns the armor category string ("helmet", "chestplate", "leggings", "boots") or null.
     */
    public static String getArmorCategory(String itemId) {
        String id = stripNamespace(itemId);
        if (id.endsWith("_helmet") || id.endsWith("_cap")) return "helmet";
        if (id.endsWith("_chestplate") || id.endsWith("_tunic")) return "chestplate";
        if (id.endsWith("_leggings") || id.endsWith("_pants")) return "leggings";
        if (id.endsWith("_boots")) return "boots";
        return null;
    }

    // ── Item identity helpers ────────────────────────────────────

    public static boolean isGoldItem(String item) {
        return item != null && (item.contains("golden") || item.contains("gold"));
    }

    public static boolean isFood(String itemId) {
        return com.emma.bridge.catalogue.ItemRecipeRegistry.isFoodItem(itemId);
    }

    /** Returns the full registry ID for an ItemStack (e.g. "minecraft:diamond_sword"). */
    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // ── Dimension helper ─────────────────────────────────────────

    /** @return 0=overworld, 1=nether, 2=end */
    public static int dimensionIndex(String dimension) {
        if (dimension.contains("the_nether")) return 1;
        if (dimension.contains("the_end")) return 2;
        return 0;
    }

    // ── Tool equip by category ─────────────────────────────────

    /**
     * Find the best tool of a given category (e.g. "axe", "pickaxe") in the player's
     * inventory and equip it to the selected hotbar slot.
     * "Best" = lowest tier rank (netherite < diamond < iron < stone < wooden < golden).
     *
     * @param player   the player
     * @param category tool category ("pickaxe", "axe", "shovel", "sword", "hoe")
     * @return true if a tool of that category was found and selected, false if none found
     */
    public static boolean equipBestOfCategory(net.minecraft.client.player.LocalPlayer player, String category) {
        if (player == null || category == null) return false;

        int bestSlot = -1;
        int bestRank = Integer.MAX_VALUE;

        for (int i = 0; i < 36; i++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = itemId(stack);
            String cat = getToolCategory(id);
            if (!category.equals(cat)) continue;
            int rank = getToolTierRank(id);
            if (rank < bestRank) {
                bestRank = rank;
                bestSlot = i;
            }
        }

        if (bestSlot < 0) {
            // Fallback: extract best tool from Endless Inventory
            if (EndinvBridge.isAvailable()) {
                String bestToolId = null;
                int bestEndinvRank = Integer.MAX_VALUE;
                for (var entry : EndinvBridge.getAllItems().entrySet()) {
                    String cat = getToolCategory(entry.getKey());
                    if (!category.equals(cat)) continue;
                    int rank = getToolTierRank(entry.getKey());
                    if (rank < bestEndinvRank) {
                        bestEndinvRank = rank;
                        bestToolId = entry.getKey();
                    }
                }
                if (bestToolId != null) {
                    return EndinvBridge.extractToSlot(bestToolId,
                            player.getInventory().getSelectedSlot());
                }
            }
            return false;
        }

        if (bestSlot < 9) {
            // Already in hotbar — just select it
            player.getInventory().setSelectedSlot(bestSlot);
        } else {
            // In main inventory — swap to current hotbar slot
            int hotbarSlot = player.getInventory().getSelectedSlot();
            int screenSlot = bestSlot;  // main inv slots 9-35 map to screen slots 9-35
            int hotbarScreenSlot = hotbarSlot + 36;
            int syncId = player.inventoryMenu.containerId;
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            // Pick up from source
            client.gameMode.handleContainerInput(syncId, screenSlot, 0,
                    net.minecraft.world.inventory.ContainerInput.PICKUP, player);
            // Place into hotbar
            client.gameMode.handleContainerInput(syncId, hotbarScreenSlot, 0,
                    net.minecraft.world.inventory.ContainerInput.PICKUP, player);
            // If there was something in the hotbar slot, put it back
            if (!player.inventoryMenu.getCarried().isEmpty()) {
                client.gameMode.handleContainerInput(syncId, screenSlot, 0,
                        net.minecraft.world.inventory.ContainerInput.PICKUP, player);
            }
        }
        return true;
    }

    // ── Inventory interaction ────────────────────────────────────

    /**
     * Shift-click (quick move) an inventory slot in the player's current container screen.
     * Converts player inventory slot (0-35) to screen slot index.
     */
    public static void shiftClick(Minecraft client, LocalPlayer player, int inventorySlot) {
        int syncId = player.containerMenu.containerId;
        // Hotbar 0-8 -> screen slots 36-44, main 9-35 -> screen slots 9-35
        int screenSlot = (inventorySlot < 9) ? inventorySlot + 36 : inventorySlot;
        client.gameMode.handleContainerInput(
                syncId, screenSlot, 0, ContainerInput.QUICK_MOVE, player);
    }

    // ── Internal ─────────────────────────────────────────────────

    /** Strips "minecraft:" or any namespace prefix from an item ID. */
    public static String stripNamespace(String itemId) {
        if (itemId == null) return "";
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(colon + 1) : itemId;
    }
}
