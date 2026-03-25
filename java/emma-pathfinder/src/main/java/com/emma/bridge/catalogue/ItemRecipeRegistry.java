package com.emma.bridge.catalogue;

import com.emma.bridge.EmmaBridgeMod;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads and indexes the data-driven item catalogue from two sources:
 * 1. item_recipes.json — mining, mob drops, crops, shearing, custom overrides
 * 2. Minecraft's RecipeManager — all crafting, smelting, smithing, stonecutting recipes
 *
 * Provides item lookup, alias resolution, and transitive dependency walking for GOAP.
 *
 * Zero EmmaClef dependencies — uses MC APIs directly.
 */
public class ItemRecipeRegistry {

    // Primary map: itemId → list of all entries (JSON + MC recipes)
    private static final Map<String, List<ItemRecipeEntry>> entries = new LinkedHashMap<>();
    // Alias map: alias → canonical itemId
    private static final Map<String, String> aliases = new LinkedHashMap<>();

    private static boolean jsonLoaded = false;
    private static boolean mcRecipesLoaded = false;

    // Brewing container list (from BREW_CONTAINERS metadata entry)
    private static String[] brewContainers = null;

    /**
     * Fallback dimension map for mobs whose spawnDimensions is empty in the extracted JSON.
     * These mobs spawn via special mechanics not covered by biome spawners or structure spawn overrides:
     * spawner blocks, structure generation, raids, weather events, player construction, etc.
     */
    static final Map<String, String[]> FALLBACK_MOB_DIMENSIONS = Map.ofEntries(
            Map.entry("cave_spider",     new String[]{"minecraft:overworld"}),
            Map.entry("elder_guardian",   new String[]{"minecraft:overworld"}),
            Map.entry("breeze",          new String[]{"minecraft:overworld"}),
            Map.entry("silverfish",      new String[]{"minecraft:overworld"}),
            Map.entry("evoker",          new String[]{"minecraft:overworld"}),
            Map.entry("vindicator",      new String[]{"minecraft:overworld"}),
            Map.entry("ravager",         new String[]{"minecraft:overworld"}),
            Map.entry("phantom",         new String[]{"minecraft:overworld"}),
            Map.entry("iron_golem",      new String[]{"minecraft:overworld"}),
            Map.entry("snow_golem",      new String[]{"minecraft:overworld"}),
            Map.entry("copper_golem",    new String[]{"minecraft:overworld"}),
            Map.entry("skeleton_horse",  new String[]{"minecraft:overworld"}),
            Map.entry("trader_llama",    new String[]{"minecraft:overworld"}),
            Map.entry("mule",            new String[]{"minecraft:overworld"}),
            Map.entry("warden",          new String[]{"minecraft:overworld"}),
            Map.entry("shulker",         new String[]{"minecraft:the_end"}),
            Map.entry("zoglin",          new String[]{"minecraft:overworld"}),
            Map.entry("zombie_nautilus", new String[]{"minecraft:overworld"}),
            Map.entry("camel_husk",      new String[]{"minecraft:overworld"})
    );

    // ── Food knowledge cache (built from DataComponents.FOOD + recipe entries) ──
    private static Set<String> foodItems;               // bare item IDs that are food
    private static Set<String> foodSourceEntities;      // entity type paths that drop food
    private static Set<String> foodSourceBlocks;        // block IDs that yield food (MINE/CROP)
    private static Map<String, String> cropSeedMap;     // cropBlock → seedItem (for replanting)
    private static List<FoodCandidate> foodCandidatesSorted;  // ranked by acquisition efficiency
    private static boolean foodKnowledgeBuilt = false;

    /**
     * A food item candidate with acquisition cost metadata for ranking.
     */
    public record FoodCandidate(String itemId, int nutrition, int acquisitionSteps,
                                 ObtainMethod primaryMethod, boolean needsFurnace,
                                 boolean needsCraftingTable) {}

    /**
     * Load entries from classpath JSON resource. Safe to call multiple times (idempotent).
     * JSON entries have priority — they are loaded first and take precedence.
     */
    public static void load() {
        if (jsonLoaded) return;

        try (InputStream is = ItemRecipeRegistry.class.getResourceAsStream("/data/emma/item_recipes.json")) {
            if (is == null) {
                EmmaBridgeMod.LOGGER.warn("ItemRecipeRegistry: item_recipes.json not found on classpath");
                jsonLoaded = true;
                return;
            }
            Gson gson = new Gson();
            Type listType = new TypeToken<List<ItemRecipeEntry>>() {}.getType();

            // Handle both wrapper format { "entries": [...] } and bare array [...]
            JsonElement root = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            List<ItemRecipeEntry> list;
            if (root.isJsonObject() && root.getAsJsonObject().has("entries")) {
                list = gson.fromJson(root.getAsJsonObject().get("entries"), listType);
            } else {
                list = gson.fromJson(root, listType);
            }

            int count = 0;
            for (ItemRecipeEntry entry : list) {
                // BREW_CONTAINERS is a metadata-only entry with no itemId
                if (entry.getObtainMethod() == ObtainMethod.BREW_CONTAINERS) {
                    brewContainers = entry.getContainers();
                    count++;
                    continue;
                }

                if (entry.getItemId() == null) {
                    EmmaBridgeMod.LOGGER.warn("ItemRecipeRegistry: skipping entry with null itemId");
                    continue;
                }
                addEntry(entry);
                count++;

                // Register aliases
                if (entry.getAliases() != null) {
                    for (String alias : entry.getAliases()) {
                        aliases.putIfAbsent(alias, entry.getItemId());
                    }
                }
            }

            // Post-process MOB_DROP entries: derive 'dimension' from spawnDimensions + fallback map
            int dimFixed = 0;
            for (List<ItemRecipeEntry> entryList : entries.values()) {
                for (ItemRecipeEntry entry : entryList) {
                    if (entry.getObtainMethod() == ObtainMethod.MOB_DROP) {
                        entry.deriveDimensionFromSpawnDimensions();
                        if (entry.getDimension() != null) dimFixed++;
                    }
                }
            }

            EmmaBridgeMod.LOGGER.info("ItemRecipeRegistry: loaded {} entries from JSON ({} aliases, {} mob dimension tags, {} brew containers)",
                    count, aliases.size(), dimFixed, brewContainers != null ? brewContainers.length : 0);
        } catch (Exception e) {
            EmmaBridgeMod.LOGGER.warn("ItemRecipeRegistry: failed to load item_recipes.json: {}", e.getMessage());
        }

        generateDerivedEntries();

        jsonLoaded = true;
    }

    /**
     * Generate TRANSFORM and INTERACT entries by pattern-matching existing data.
     * Called after JSON loading — avoids hand-coding color variants.
     */
    private static void generateDerivedEntries() {
        List<ItemRecipeEntry> snapshot = new ArrayList<>(allEntries());
        int count = 0;

        // 1. Wool shearing: every *_wool MOB_DROP from sheep → INTERACT (shear)
        for (ItemRecipeEntry entry : snapshot) {
            if (entry.getObtainMethod() == ObtainMethod.MOB_DROP
                    && "sheep".equals(entry.getMobClass())
                    && entry.getItemId().endsWith("_wool")) {
                addEntry(ItemRecipeEntry.ofInteract(entry.getItemId(), "Sheep", "shears"));
                count++;
            }
        }

        // 2. Concrete: every *_concrete_powder → *_concrete via WATER_CONTACT
        for (String itemId : new ArrayList<>(entries.keySet())) {
            if (itemId.endsWith("_concrete_powder")) {
                String concreteId = itemId.replace("_concrete_powder", "_concrete");
                addEntry(ItemRecipeEntry.ofTransform(concreteId, itemId, null, "WATER_CONTACT"));
                count++;
            }
        }

        // 3. Stripped wood: for every MINE entry, check if stripped_ variant exists
        //    Catches _log, _wood, _stem, _hyphae, bamboo_block — no suffix list needed.
        //    isValidItem() filters out non-existent combos (stripped_stone, etc.)
        for (ItemRecipeEntry entry : snapshot) {
            if (entry.getObtainMethod() == ObtainMethod.MINE) {
                String stripped = "stripped_" + entry.getItemId();
                if (isValidItem(stripped)) {
                    addEntry(ItemRecipeEntry.ofTransform(stripped, entry.getItemId(), "axe", "TOOL_USE"));
                    count++;
                }
            }
        }

        // 4. One-off entity interactions (not pattern-derivable)
        addEntry(ItemRecipeEntry.ofInteract("milk_bucket", "Cow", "bucket"));
        addEntry(ItemRecipeEntry.ofInteract("mushroom_stew", "MushroomCow", "bowl"));
        addEntry(ItemRecipeEntry.ofInteractBlock("honeycomb", "beehive", "shears"));
        count += 3;

        EmmaBridgeMod.LOGGER.info("ItemRecipeRegistry: generated {} derived TRANSFORM/INTERACT entries", count);
    }

    /**
     * Load recipes from Minecraft's RecipeManager at world join.
     * Extracts all crafting, smelting, smithing, and stonecutting recipes.
     * JSON entries take priority — MC recipes are added as alternatives.
     *
     * @param recipeManager the server's RecipeManager (cast from integrated server)
     */
    public static void loadFromMinecraftRecipes(net.minecraft.world.item.crafting.RecipeManager recipeManager) {
        if (mcRecipesLoaded) return;

        Level world = Minecraft.getInstance().level;
        if (world == null) {
            EmmaBridgeMod.LOGGER.warn("ItemRecipeRegistry: world is null, cannot load MC recipes");
            return;
        }

        // Only RecipeManager has values() — cast when on integrated server
        if (!(recipeManager instanceof net.minecraft.world.item.crafting.RecipeManager serverRM)) {
            EmmaBridgeMod.LOGGER.warn("ItemRecipeRegistry: RecipeManager is not RecipeManager, cannot load MC recipes");
            return;
        }

        int craftCount = 0, smeltCount = 0, smithCount = 0, trimCount = 0, stonecutCount = 0;

        for (var recipeEntry : serverRM.getRecipes()) {
            Recipe<?> recipe = recipeEntry.value();

            ItemStack result = getRecipeOutput(recipe, world);
            if (result.isEmpty()) continue;

            String itemId = BuiltInRegistries.ITEM.getKey(result.getItem()).getPath(); // strip "minecraft:"

            if (recipe instanceof CraftingRecipe craftingRecipe) {
                if (craftingRecipe instanceof CustomRecipe) continue;

                ItemRecipeEntry entry = extractCraftingEntry(itemId, craftingRecipe, result.getCount());
                if (entry != null) {
                    addEntry(entry);
                    craftCount++;
                }
            } else if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
                ItemRecipeEntry entry = extractSmeltingEntry(itemId, cookingRecipe);
                if (entry != null) {
                    addEntry(entry);
                    smeltCount++;
                }
            } else if (recipe instanceof SmithingTrimRecipe trimRecipe) {
                ItemRecipeEntry entry = extractSmithingTrimEntry(trimRecipe);
                if (entry != null) {
                    addEntry(entry);
                    trimCount++;
                }
            } else if (recipe instanceof SmithingTransformRecipe smithingRecipe) {
                ItemRecipeEntry entry = extractSmithingEntry(itemId, smithingRecipe);
                if (entry != null) {
                    addEntry(entry);
                    smithCount++;
                }
            } else if (recipe instanceof StonecutterRecipe stonecuttingRecipe) {
                ItemRecipeEntry entry = extractStonecuttingEntry(itemId, stonecuttingRecipe, result.getCount());
                if (entry != null) {
                    addEntry(entry);
                    stonecutCount++;
                }
            }
        }

        EmmaBridgeMod.LOGGER.info("ItemRecipeRegistry: loaded from Minecraft — {} crafting, {} smelting, {} smithing, {} trim, {} stonecutting",
                craftCount, smeltCount, smithCount, trimCount, stonecutCount);

        mcRecipesLoaded = true;

        // Build food knowledge now that both JSON and MC recipes are loaded
        buildFoodKnowledge();
    }

    // ── Recipe output helper (replaces RecipeVer) ────────────

    /**
     * Get the output ItemStack from a recipe.
     * In 1.21.8, Recipe no longer has getResult(DynamicRegistryManager).
     * Uses getDisplays() to obtain the result via RecipeDisplay.
     */
    private static ItemStack getRecipeOutput(Recipe<?> recipe, Level world) {
        List<net.minecraft.world.item.crafting.display.RecipeDisplay> displays = recipe.display();
        if (displays.isEmpty()) {
            return ItemStack.EMPTY;
        }
        net.minecraft.util.context.ContextMap params = SlotDisplayContext.fromLevel(world);
        return displays.getFirst().result().resolveForFirstStack(params);
    }

    // ── Recipe extraction helpers ─────────────────────────────

    private static ItemRecipeEntry extractCraftingEntry(String itemId, CraftingRecipe recipe, int yield) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.isEmpty()) return null;

        String[][] grid = new String[ingredients.size()][];
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing.isEmpty()) {
                grid[i] = null;
            } else {
                grid[i] = ing.items()
                        .map(Holder::value)
                        .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
                        .toArray(String[]::new);
                if (grid[i].length == 0) grid[i] = null;
            }
        }

        ObtainMethod method;
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            method = (w <= 2 && h <= 2) ? ObtainMethod.CRAFT_SHAPED_2x2 : ObtainMethod.CRAFT_SHAPED_3x3;
        } else {
            method = ObtainMethod.CRAFT_SHAPELESS;
        }

        return ItemRecipeEntry.ofCraft(itemId, method, grid, yield);
    }

    private static ItemRecipeEntry extractSmeltingEntry(String itemId, AbstractCookingRecipe recipe) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.isEmpty()) return null;

        Ingredient input = ingredients.getFirst();
        if (input.isEmpty()) return null;

        String[] inputs = input.items()
                .map(Holder::value)
                .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
                .toArray(String[]::new);
        if (inputs.length == 0) return null;

        ObtainMethod method;
        if (recipe instanceof SmeltingRecipe) method = ObtainMethod.SMELT_FURNACE;
        else if (recipe instanceof BlastingRecipe) method = ObtainMethod.SMELT_BLAST;
        else if (recipe instanceof SmokingRecipe) method = ObtainMethod.SMELT_SMOKER;
        else if (recipe instanceof CampfireCookingRecipe) method = ObtainMethod.SMELT_CAMPFIRE;
        else method = ObtainMethod.SMELT;

        return ItemRecipeEntry.ofSmelt(itemId, method, inputs, recipe.experience(), recipe.cookingTime());
    }

    private static ItemRecipeEntry extractSmithingEntry(String itemId, SmithingTransformRecipe recipe) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.size() < 3) return null;

        String[] templates = ingredients.get(0).items()
                .map(Holder::value)
                .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
                .toArray(String[]::new);
        String[] bases = ingredients.get(1).items()
                .map(Holder::value)
                .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
                .toArray(String[]::new);
        String[] materials = ingredients.get(2).items()
                .map(Holder::value)
                .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
                .toArray(String[]::new);

        if (bases.length == 0 || materials.length == 0) return null;

        return ItemRecipeEntry.ofSmith(itemId, templates, bases, materials);
    }

    private static ItemRecipeEntry extractSmithingTrimEntry(SmithingTrimRecipe recipe) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.size() < 3) return null;

        String[] templates = ingredients.get(0).items()
                .map(Holder::value)
                .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
                .toArray(String[]::new);
        String[] bases = ingredients.get(1).items()
                .map(Holder::value)
                .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
                .toArray(String[]::new);
        String[] materials = ingredients.get(2).items()
                .map(Holder::value)
                .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
                .toArray(String[]::new);

        if (bases.length == 0 || materials.length == 0) return null;

        // Use first base item as itemId (trims modify existing items, not producing new ones)
        String itemId = bases[0];
        return ItemRecipeEntry.ofSmithTrim(itemId, templates, bases, materials, null);
    }

    private static ItemRecipeEntry extractStonecuttingEntry(String itemId, StonecutterRecipe recipe, int yield) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.isEmpty()) return null;

        String[] inputs = ingredients.getFirst().items()
                .map(Holder::value)
                .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
                .toArray(String[]::new);
        if (inputs.length == 0) return null;

        return ItemRecipeEntry.ofStonecutter(itemId, inputs, yield);
    }

    // ── Entry management ──────────────────────────────────────

    private static void addEntry(ItemRecipeEntry entry) {
        entries.computeIfAbsent(entry.getItemId(), k -> new ArrayList<>()).add(entry);
    }

    // ── Public query API ──────────────────────────────────────

    /**
     * Get the primary entry by itemId or alias. Returns null if not found.
     * JSON entries are returned first (loaded before MC recipes).
     */
    public static ItemRecipeEntry getEntry(String itemId) {
        List<ItemRecipeEntry> list = getEntries(itemId);
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * Get all entries for an item (may have multiple: e.g. iron_ingot has SMELT + CRAFT).
     * Resolves aliases. Returns empty list if not found.
     */
    public static List<ItemRecipeEntry> getEntries(String itemId) {
        List<ItemRecipeEntry> list = entries.get(itemId);
        if (list != null) return list;

        String canonical = aliases.get(itemId);
        if (canonical != null) {
            list = entries.get(canonical);
            if (list != null) return list;
        }

        return Collections.emptyList();
    }

    /**
     * Get the first entry matching a specific ObtainMethod. Returns null if not found.
     */
    public static ItemRecipeEntry getEntryByMethod(String itemId, ObtainMethod method) {
        for (ItemRecipeEntry e : getEntries(itemId)) {
            if (e.getObtainMethod() == method) return e;
        }
        return null;
    }

    /**
     * Check if an entry exists for the given itemId or alias.
     * Use for DECOMPOSITION — does this item have a known recipe?
     */
    public static boolean hasEntry(String itemId) {
        return !getEntries(itemId).isEmpty();
    }

    /**
     * Check if an itemId is a valid Minecraft item (raw drops, recipe outputs, anything).
     * Use for VALIDATION — is this item real?
     * Unlike hasEntry(), this accepts items that have no recipe (e.g. raw_iron, diamond, coal).
     */
    public static boolean isValidItem(String itemId) {
        Identifier rl = Identifier.withDefaultNamespace(itemId);
        return BuiltInRegistries.ITEM.containsKey(rl)
                && BuiltInRegistries.ITEM.getValue(rl) != Items.AIR;
    }

    /**
     * Get all unique entries across all items.
     */
    public static Collection<ItemRecipeEntry> allEntries() {
        Set<ItemRecipeEntry> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<ItemRecipeEntry> list : entries.values()) {
            unique.addAll(list);
        }
        return unique;
    }

    /**
     * Get direct dependencies for an item. Auto-derives from craftGrid/smeltFrom
     * when the entry has no explicit dependencies. Unions across all entries.
     */
    public static Set<String> getDependencies(String itemId) {
        List<ItemRecipeEntry> entryList = getEntries(itemId);
        if (entryList.isEmpty()) return Collections.emptySet();

        Set<String> deps = new LinkedHashSet<>();
        for (ItemRecipeEntry entry : entryList) {
            deps.addAll(getDependenciesForEntry(entry));
        }
        return deps;
    }

    private static Set<String> getDependenciesForEntry(ItemRecipeEntry entry) {
        if (entry.getDependencies() != null && entry.getDependencies().length > 0) {
            return new LinkedHashSet<>(Arrays.asList(entry.getDependencies()));
        }

        Set<String> deps = new LinkedHashSet<>();

        if (entry.getCraftGrid() != null) {
            for (String[] slotAlts : entry.getCraftGrid()) {
                if (slotAlts != null) {
                    for (String alt : slotAlts) {
                        if (alt != null) deps.add(alt);
                    }
                }
            }
        }

        // Only add smeltFrom for actual SMELT entries.
        // STONECUTTER entries reuse smeltFrom for their input, but that's an
        // alternative production path, not a required prerequisite.
        // Skip inputs that have ITEM_PROPERTIES (tools/weapons/armor) — those are
        // recycling recipes (smelt iron_helmet → iron_nugget), not raw material paths.
        if (entry.getSmeltFrom() != null && entry.getObtainMethod().isSmeltType()) {
            for (String input : entry.getSmeltFrom()) {
                if (!hasItemProperties(input)) {
                    deps.add(input);
                }
            }
        }

        if (entry.getSmithTemplate() != null) {
            for (String tmpl : entry.getSmithTemplate()) deps.add(tmpl);
        }
        if (entry.getSmithBase() != null) {
            for (String base : entry.getSmithBase()) deps.add(base);
        }
        if (entry.getSmithMaterial() != null) {
            for (String mat : entry.getSmithMaterial()) deps.add(mat);
        }

        // Stonecutter proper field
        if (entry.getStonecutterFrom() != null && entry.getObtainMethod() == ObtainMethod.STONECUTTER) {
            for (String input : entry.getStonecutterFrom()) deps.add(input);
        }

        if (entry.getTransformInput() != null) {
            deps.add(entry.getTransformInput());
        }
        // transformTool is a category ("axe"), not a specific item — not a dep
        // interactTool IS a specific item (shears, bucket) — IS a dep
        if (entry.getInteractTool() != null) {
            deps.add(entry.getInteractTool());
        }

        // Brewing ingredient is a specific item — IS a dep
        if (entry.getBrewIngredient() != null && entry.getObtainMethod().isBrewType()) {
            Collections.addAll(deps, entry.getBrewIngredient());
        }

        return deps;
    }

    /**
     * Get transitive dependencies in topological order (build order).
     * Starts with raw materials, ends with immediate prerequisites.
     * The itemId itself is NOT included.
     */
    public static List<String> getTransitiveDependencies(String itemId) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        walkDependencies(itemId, visited, result);
        return result;
    }

    private static void walkDependencies(String itemId, Set<String> visited, List<String> result) {
        if (visited.contains(itemId)) return;
        visited.add(itemId);

        for (String dep : getDependencies(itemId)) {
            walkDependencies(dep, visited, result);
            if (!result.contains(dep)) {
                result.add(dep);
            }
        }
    }

    /**
     * Get transitive dependencies resolved against current inventory state.
     * For each craft grid slot with alternatives (e.g., [oak_planks, spruce_planks, ...]):
     *   - If any alternative is already owned → collapse to just that one (prune subtree)
     *   - If none owned → keep ALL alternatives (let action scoring pick nearest at runtime)
     * This prunes the tree naturally — having oak_wood means we don't expand all 12 log types.
     *
     * @param itemId   Item to resolve deps for
     * @param hasItem  Predicate: does the player have this item? (full ID, e.g. "minecraft:oak_log")
     * @return Topological-ordered list of needed deps (raw materials first)
     */
    public static List<String> getResolvedDependencies(String itemId,
                                                        java.util.function.Predicate<String> hasItem) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        walkResolvedDeps(itemId, visited, result, hasItem);
        return result;
    }

    private static void walkResolvedDeps(String itemId, Set<String> visited,
                                          List<String> result,
                                          java.util.function.Predicate<String> hasItem) {
        if (visited.contains(itemId)) return;
        visited.add(itemId);

        for (String dep : getResolvedDirectDeps(itemId, hasItem)) {
            walkResolvedDeps(dep, visited, result, hasItem);
            if (!result.contains(dep)) {
                result.add(dep);
            }
        }
    }

    /**
     * Get direct dependencies for an item, resolving slot alternatives.
     * For craft grid slots with multiple alternatives, picks the best one.
     */
    private static Set<String> getResolvedDirectDeps(String itemId,
                                                      java.util.function.Predicate<String> hasItem) {
        List<ItemRecipeEntry> entryList = getEntries(itemId);
        if (entryList.isEmpty()) return Collections.emptySet();

        Set<String> deps = new LinkedHashSet<>();
        for (ItemRecipeEntry entry : entryList) {
            deps.addAll(getResolvedDepsForEntry(entry, hasItem));
        }
        return deps;
    }

    private static Set<String> getResolvedDepsForEntry(ItemRecipeEntry entry,
                                                        java.util.function.Predicate<String> hasItem) {
        if (entry.getDependencies() != null && entry.getDependencies().length > 0) {
            return new LinkedHashSet<>(Arrays.asList(entry.getDependencies()));
        }

        Set<String> deps = new LinkedHashSet<>();

        if (entry.getCraftGrid() != null) {
            for (String[] slotAlts : entry.getCraftGrid()) {
                if (slotAlts != null && slotAlts.length > 0) {
                    // If we already have any alternative for this slot, collapse to just that one.
                    // Otherwise keep ALL alternatives so action scoring can pick the nearest.
                    String owned = null;
                    for (String alt : slotAlts) {
                        String full = alt.contains(":") ? alt : "minecraft:" + alt;
                        if (hasItem.test(full)) {
                            owned = alt;
                            break;
                        }
                    }
                    if (owned != null) {
                        deps.add(owned);
                    } else {
                        Collections.addAll(deps, slotAlts);
                    }
                }
            }
        }

        // Non-craft deps have no slot alternatives — same as getDependenciesForEntry
        // Skip inputs that have ITEM_PROPERTIES (recycling recipes, not raw material paths)
        if (entry.getSmeltFrom() != null && entry.getObtainMethod().isSmeltType()) {
            for (String input : entry.getSmeltFrom()) {
                if (!hasItemProperties(input)) {
                    deps.add(input);
                }
            }
        }
        if (entry.getSmithTemplate() != null) Collections.addAll(deps, entry.getSmithTemplate());
        if (entry.getSmithBase() != null) Collections.addAll(deps, entry.getSmithBase());
        if (entry.getSmithMaterial() != null) Collections.addAll(deps, entry.getSmithMaterial());
        if (entry.getStonecutterFrom() != null && entry.getObtainMethod() == ObtainMethod.STONECUTTER) {
            Collections.addAll(deps, entry.getStonecutterFrom());
        }
        if (entry.getTransformInput() != null) deps.add(entry.getTransformInput());
        if (entry.getInteractTool() != null) deps.add(entry.getInteractTool());
        if (entry.getBrewIngredient() != null && entry.getObtainMethod().isBrewType()) {
            Collections.addAll(deps, entry.getBrewIngredient());
        }

        return deps;
    }

    /**
     * Get the total number of unique items registered.
     */
    public static int size() {
        return entries.size();
    }

    // ── Food knowledge ───────────────────────────────────────────

    /**
     * Build the food knowledge cache by scanning all MC items for DataComponents.FOOD
     * and cross-referencing with recipe entries for source blocks/entities.
     * Called at end of loadFromMinecraftRecipes() and lazily on first access.
     */
    public static void buildFoodKnowledge() {
        if (foodKnowledgeBuilt) return;

        foodItems = new LinkedHashSet<>();
        foodSourceEntities = new LinkedHashSet<>();
        foodSourceBlocks = new LinkedHashSet<>();
        cropSeedMap = new LinkedHashMap<>();
        Map<String, Integer> nutritionMap = new LinkedHashMap<>();

        // Phase 1: Scan all MC items for FOOD component
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            ItemStack stack = new ItemStack(item, 1);
            if (stack.has(DataComponents.FOOD)) {
                String id = BuiltInRegistries.ITEM.getKey(item).getPath();
                foodItems.add(id);
                var food = stack.get(DataComponents.FOOD);
                if (food != null) {
                    nutritionMap.put(id, food.nutrition());
                }
            }
        }

        // Phase 2: For each food item, find source blocks and entities from recipe entries
        for (String foodId : foodItems) {
            for (ItemRecipeEntry entry : getEntries(foodId)) {
                ObtainMethod method = entry.getObtainMethod();

                if (method == ObtainMethod.MOB_DROP && entry.getMobClass() != null) {
                    foodSourceEntities.add(entry.getMobClass().toLowerCase());
                }

                if (method == ObtainMethod.MINE && entry.getMineBlockNames() != null) {
                    Collections.addAll(foodSourceBlocks, entry.getMineBlockNames());
                }

                if (method == ObtainMethod.CROP) {
                    if (entry.getCropBlocks() != null) {
                        Collections.addAll(foodSourceBlocks, entry.getCropBlocks());
                        // Build crop seed map for replanting
                        if (entry.getCropSeeds() != null && entry.getCropSeeds().length > 0) {
                            for (String cropBlock : entry.getCropBlocks()) {
                                cropSeedMap.put(cropBlock, entry.getCropSeeds()[0]);
                            }
                        }
                    }
                }
            }
        }

        // Phase 3: Build ranked FoodCandidate list
        List<FoodCandidate> candidates = new ArrayList<>();
        for (String foodId : foodItems) {
            int nutrition = nutritionMap.getOrDefault(foodId, 0);
            int steps = getTransitiveDependencies(foodId).size();

            // Find primary obtain method (prefer simplest)
            ObtainMethod primaryMethod = null;
            boolean needsFurnace = false;
            boolean needsCraftingTable = false;

            for (ItemRecipeEntry entry : getEntries(foodId)) {
                ObtainMethod m = entry.getObtainMethod();
                if (primaryMethod == null) primaryMethod = m;
                // Prefer direct methods over multi-step
                if (m == ObtainMethod.MINE || m == ObtainMethod.CROP || m == ObtainMethod.MOB_DROP) {
                    primaryMethod = m;
                }
                if (m.isSmeltType()) needsFurnace = true;
                if (m == ObtainMethod.CRAFT_SHAPED_3x3) needsCraftingTable = true;
            }

            if (primaryMethod == null) continue;

            candidates.add(new FoodCandidate(foodId, nutrition, steps,
                    primaryMethod, needsFurnace, needsCraftingTable));
        }

        // Sort: fewest steps first, then highest nutrition
        candidates.sort(Comparator.comparingInt(FoodCandidate::acquisitionSteps)
                .thenComparing(Comparator.comparingInt(FoodCandidate::nutrition).reversed()));

        foodCandidatesSorted = Collections.unmodifiableList(candidates);
        foodKnowledgeBuilt = true;

        EmmaBridgeMod.LOGGER.info("ItemRecipeRegistry: food knowledge built — {} food items, {} source entities, {} source blocks, {} crop seed mappings",
                foodItems.size(), foodSourceEntities.size(), foodSourceBlocks.size(), cropSeedMap.size());
    }

    /**
     * Check if an item is food. Strips namespace. Returns false if cache not yet built.
     */
    public static boolean isFoodItem(String itemId) {
        if (!foodKnowledgeBuilt) buildFoodKnowledge();
        if (foodItems == null) return false;
        String bare = itemId;
        int colon = itemId.indexOf(':');
        if (colon >= 0) bare = itemId.substring(colon + 1);
        return foodItems.contains(bare);
    }

    /** All food item IDs (bare, no namespace). */
    public static Set<String> getFoodItems() {
        if (!foodKnowledgeBuilt) buildFoodKnowledge();
        return foodItems != null ? foodItems : Collections.emptySet();
    }

    /** Entity type paths that drop food (lowercase, e.g. "cow", "pig"). */
    public static Set<String> getFoodSourceEntities() {
        if (!foodKnowledgeBuilt) buildFoodKnowledge();
        return foodSourceEntities != null ? foodSourceEntities : Collections.emptySet();
    }

    /** Block IDs that yield food directly (MINE/CROP entries for food items). */
    public static Set<String> getFoodSourceBlocks() {
        if (!foodKnowledgeBuilt) buildFoodKnowledge();
        return foodSourceBlocks != null ? foodSourceBlocks : Collections.emptySet();
    }

    /** Crop block → seed item mapping for replanting after harvest. */
    public static Map<String, String> getCropSeedMap() {
        if (!foodKnowledgeBuilt) buildFoodKnowledge();
        return cropSeedMap != null ? cropSeedMap : Collections.emptyMap();
    }

    /** Food candidates sorted by acquisition efficiency (fewest steps first, then nutrition). */
    public static List<FoodCandidate> getFoodCandidatesByEfficiency() {
        if (!foodKnowledgeBuilt) buildFoodKnowledge();
        return foodCandidatesSorted != null ? foodCandidatesSorted : Collections.emptyList();
    }

    // ── Brewing helpers ─────────────────────────────────────────

    /** Get the list of valid brewing container items (potion, splash_potion, lingering_potion). */
    public static String[] getBrewContainers() {
        return brewContainers;
    }

    // ── Item property helpers ─────────────────────────────────────

    /**
     * Check if an item has an ITEM_PROPERTIES entry (tools, weapons, armor).
     * Used to filter finished products from smelting dependency graphs —
     * recycling recipes (smelt iron_helmet → iron_nugget) should not create
     * acquisition subgoals for those items.
     */
    public static boolean hasItemProperties(String itemId) {
        for (ItemRecipeEntry entry : getEntries(itemId)) {
            if (entry.getObtainMethod() == ObtainMethod.ITEM_PROPERTIES) return true;
        }
        return false;
    }

    // ── Dimension helpers ─────────────────────────────────────────

    /**
     * Check if an entry's dimension requirement matches the current WorldState dimension.
     * Returns true if the entry has no dimension requirement (null = any dimension)
     * or if the requirement matches the current dimension.
     *
     * @param entryDimension  "NETHER", "END", or null (from ItemRecipeEntry.getDimension())
     * @param worldDimension  e.g. "minecraft:overworld", "minecraft:the_nether" (from WorldState.dimension)
     */
    public static boolean isDimensionMatch(String entryDimension, String worldDimension) {
        if (entryDimension == null) return true;
        return switch (entryDimension) {
            case "NETHER" -> worldDimension.contains("the_nether");
            case "END"    -> worldDimension.contains("the_end");
            default       -> true;
        };
    }

    /**
     * Convert an ItemRecipeEntry dimension tag to a full WorldState dimension string.
     */
    public static String entryDimensionToWorldDimension(String entryDimension) {
        if (entryDimension == null) return "minecraft:overworld";
        return switch (entryDimension) {
            case "NETHER" -> "minecraft:the_nether";
            case "END"    -> "minecraft:the_end";
            default       -> "minecraft:overworld";
        };
    }

    /**
     * Get entries for an item filtered to only those obtainable in the given dimension.
     * Entries with null dimension (any) are always included.
     */
    public static List<ItemRecipeEntry> getEntriesForDimension(String itemId, String worldDimension) {
        return getEntries(itemId).stream()
                .filter(e -> isDimensionMatch(e.getDimension(), worldDimension))
                .toList();
    }

    /**
     * Reset the registry (for testing or world change).
     */
    public static void reset() {
        entries.clear();
        aliases.clear();
        brewContainers = null;
        jsonLoaded = false;
        mcRecipesLoaded = false;
        foodItems = null;
        foodSourceEntities = null;
        foodSourceBlocks = null;
        cropSeedMap = null;
        foodCandidatesSorted = null;
        foodKnowledgeBuilt = false;
        RecipeBookLookup.invalidate();
    }

}
