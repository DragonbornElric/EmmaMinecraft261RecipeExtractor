package com.emma.bridge.catalogue;

import com.emma.bridge.EmmaBridgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;

import java.util.*;

/**
 * Looks up RecipeDisplayId entries from the client-side recipe book.
 * Lazy-cached maps from bare item ID → matching RecipeDisplayEntry list.
 * Separate caches for crafting (Shaped/Shapeless) and smelting (Furnace).
 * Cache is invalidated on world join / disconnect via ItemRecipeRegistry.reset().
 */
public final class RecipeBookLookup {

    private static Map<String, List<RecipeDisplayEntry>> craftCache;
    private static Map<String, List<RecipeDisplayEntry>> smeltCache;
    private static int cachedRecipeCount;

    /**
     * Find all crafting recipe entries that produce the given item.
     * @param itemId bare item ID (e.g. "iron_sword") or full ID ("minecraft:iron_sword")
     * @return list of matching entries, empty if none
     */
    public static List<RecipeDisplayEntry> findCraftingRecipes(String itemId) {
        ensureCache();
        String bare = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        return craftCache.getOrDefault(bare, List.of());
    }

    /**
     * Find the first crafting recipe entry for an item.
     * @param itemId bare item ID or full ID
     * @return first matching entry, or null if none
     */
    public static RecipeDisplayEntry findFirstCraftingRecipe(String itemId) {
        List<RecipeDisplayEntry> entries = findCraftingRecipes(itemId);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    /**
     * Find all smelting/furnace recipe entries that produce the given item.
     * Covers furnace, blast furnace, and smoker (all use FurnaceRecipeDisplay).
     * @param itemId bare item ID or full ID
     * @return list of matching entries, empty if none
     */
    public static List<RecipeDisplayEntry> findSmeltingRecipes(String itemId) {
        ensureCache();
        String bare = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        return smeltCache.getOrDefault(bare, List.of());
    }

    /**
     * Find the first smelting recipe entry for an item.
     * @param itemId bare item ID or full ID
     * @return first matching entry, or null if none
     */
    public static RecipeDisplayEntry findFirstSmeltingRecipe(String itemId) {
        List<RecipeDisplayEntry> entries = findSmeltingRecipes(itemId);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    /** Clear the cache. Called on world join / disconnect. */
    public static void invalidate() {
        craftCache = null;
        smeltCache = null;
    }

    private static void ensureCache() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        ClientRecipeBook book = client.player.getRecipeBook();

        // Check if the recipe book has grown since last cache build
        List<RecipeCollection> collections = book.getCollections();
        int totalRecipes = 0;
        for (RecipeCollection collection : collections) {
            totalRecipes += collection.getRecipes().size();
        }
        // Don't cache empty results — recipes may not have synced yet
        if (totalRecipes == 0) {
            EmmaBridgeMod.LOGGER.debug("RecipeBookLookup: recipe book empty ({} collections), skipping cache",
                    collections.size());
            return;
        }
        if (craftCache != null && smeltCache != null && totalRecipes == cachedRecipeCount) return;

        craftCache = new HashMap<>();
        smeltCache = new HashMap<>();
        cachedRecipeCount = totalRecipes;
        ContextMap ctx = SlotDisplayContext.fromLevel(client.level);

        for (RecipeCollection collection : book.getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                RecipeDisplay display = entry.display();

                Map<String, List<RecipeDisplayEntry>> targetCache;
                if (display instanceof ShapedCraftingRecipeDisplay
                        || display instanceof ShapelessCraftingRecipeDisplay) {
                    targetCache = craftCache;
                } else if (display instanceof FurnaceRecipeDisplay) {
                    targetCache = smeltCache;
                } else {
                    continue;
                }

                List<ItemStack> results = entry.resultItems(ctx);
                for (ItemStack result : results) {
                    if (result.isEmpty()) continue;
                    String bareId = BuiltInRegistries.ITEM.getKey(result.getItem()).getPath();
                    targetCache.computeIfAbsent(bareId, k -> new ArrayList<>()).add(entry);
                }
            }
        }

        EmmaBridgeMod.LOGGER.info("RecipeBookLookup: cached {} craft + {} smelt item IDs from recipe book (total recipes: {})",
                craftCache.size(), smeltCache.size(), cachedRecipeCount);
    }

    private RecipeBookLookup() {}
}
