package com.emma.recipeextractor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Either;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.core.Registry;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.enchantment.Enchantable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Repairable;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.*;
import net.minecraft.world.level.storage.loot.functions.*;
import net.minecraft.world.level.storage.loot.predicates.*;
import net.minecraft.world.level.storage.loot.providers.number.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

/**
 * Comprehensive extraction tool: pulls ALL available recipe, block drop, and mob drop data
 * from the server's registries and outputs a JSON object with metadata + entries array.
 *
 * Design principle: capture EVERY field MC 26.1 exposes. Unknown types get "unknown" fallback
 * with class name — no data is silently dropped.
 *
 * Run via /emma_extract — output is a static JSON file, re-run on MC version upgrades.
 */
public class RecipeExtractor {

    // ── Reflection for loot table walking ──────────────────────────────
    // All private fields accessed via reflection. MC 26.1 is unobfuscated so field names are real.

    private static Field LOOT_TABLE_POOLS;
    private static Field LOOT_POOL_ENTRIES;
    private static Field LOOT_POOL_CONDITIONS;
    private static Field LOOT_POOL_FUNCTIONS;
    private static Field LOOT_POOL_ROLLS;
    private static Field LOOT_POOL_BONUS_ROLLS;
    private static Field LOOT_ITEM_HOLDER;
    private static Field NESTED_TABLE_CONTENTS;
    private static Field COMPOSITE_CHILDREN;
    private static Field SINGLETON_WEIGHT;
    private static Field SINGLETON_QUALITY;
    private static Field SINGLETON_FUNCTIONS;
    private static Field ENTRY_CONDITIONS;

    // PotionBrewing reflection
    private static Field POTION_BREWING_POTION_MIXES;
    private static Field POTION_BREWING_CONTAINER_MIXES;
    private static Field POTION_BREWING_CONTAINERS;

    static {
        try {
            LOOT_TABLE_POOLS = LootTable.class.getDeclaredField("pools");
            LOOT_TABLE_POOLS.setAccessible(true);

            LOOT_POOL_ENTRIES = LootPool.class.getDeclaredField("entries");
            LOOT_POOL_ENTRIES.setAccessible(true);
            LOOT_POOL_CONDITIONS = LootPool.class.getDeclaredField("conditions");
            LOOT_POOL_CONDITIONS.setAccessible(true);
            LOOT_POOL_FUNCTIONS = LootPool.class.getDeclaredField("functions");
            LOOT_POOL_FUNCTIONS.setAccessible(true);
            LOOT_POOL_ROLLS = LootPool.class.getDeclaredField("rolls");
            LOOT_POOL_ROLLS.setAccessible(true);
            LOOT_POOL_BONUS_ROLLS = LootPool.class.getDeclaredField("bonusRolls");
            LOOT_POOL_BONUS_ROLLS.setAccessible(true);

            LOOT_ITEM_HOLDER = LootItem.class.getDeclaredField("item");
            LOOT_ITEM_HOLDER.setAccessible(true);

            NESTED_TABLE_CONTENTS = NestedLootTable.class.getDeclaredField("contents");
            NESTED_TABLE_CONTENTS.setAccessible(true);

            COMPOSITE_CHILDREN = CompositeEntryBase.class.getDeclaredField("children");
            COMPOSITE_CHILDREN.setAccessible(true);

            SINGLETON_WEIGHT = LootPoolSingletonContainer.class.getDeclaredField("weight");
            SINGLETON_WEIGHT.setAccessible(true);
            SINGLETON_QUALITY = LootPoolSingletonContainer.class.getDeclaredField("quality");
            SINGLETON_QUALITY.setAccessible(true);
            SINGLETON_FUNCTIONS = LootPoolSingletonContainer.class.getDeclaredField("functions");
            SINGLETON_FUNCTIONS.setAccessible(true);

            ENTRY_CONDITIONS = LootPoolEntryContainer.class.getDeclaredField("conditions");
            ENTRY_CONDITIONS.setAccessible(true);

            POTION_BREWING_POTION_MIXES = PotionBrewing.class.getDeclaredField("potionMixes");
            POTION_BREWING_POTION_MIXES.setAccessible(true);
            POTION_BREWING_CONTAINER_MIXES = PotionBrewing.class.getDeclaredField("containerMixes");
            POTION_BREWING_CONTAINER_MIXES.setAccessible(true);
            POTION_BREWING_CONTAINERS = PotionBrewing.class.getDeclaredField("containers");
            POTION_BREWING_CONTAINERS.setAccessible(true);
        } catch (NoSuchFieldException e) {
            RecipeExtractorMod.LOGGER.error("[RecipeExtractor] Failed to init reflection", e);
        }
    }

    // ── Dimension block sets ──────────────────────────────────────────

    private static final Set<String> NETHER_BLOCKS = Set.of(
            "netherrack", "nether_gold_ore", "ancient_debris", "nether_quartz_ore",
            "soul_sand", "soul_soil", "basalt", "smooth_basalt", "blackstone",
            "polished_blackstone", "gilded_blackstone", "glowstone", "magma_block",
            "crimson_stem", "warped_stem", "crimson_nylium", "warped_nylium",
            "crimson_planks", "warped_planks", "nether_bricks", "red_nether_bricks",
            "nether_wart_block", "warped_wart_block", "shroomlight",
            "crying_obsidian", "respawn_anchor"
    );

    private static final Set<String> END_BLOCKS = Set.of(
            "end_stone", "end_stone_bricks", "purpur_block", "purpur_pillar",
            "purpur_stairs", "purpur_slab", "chorus_plant", "chorus_flower",
            "end_rod", "dragon_egg", "shulker_box"
    );

    // ── Main extraction ───────────────────────────────────────────────

    /**
     * Extract all item acquisition data from the server.
     * Returns a JSON string with wrapper object: { extractionMeta, entries[] }.
     */
    public static String extractAll(MinecraftServer server) {
        JsonArray allEntries = new JsonArray();
        Map<String, Integer> counts = new LinkedHashMap<>();

        int craftCount = extractRecipes(server, allEntries, counts);
        int blockCount = extractBlockDrops(server, allEntries);
        int mobCount = extractMobDrops(server, allEntries);
        int brewingCount = extractBrewing(server, allEntries);
        int enchantCount = extractEnchantments(server, allEntries);
        int bannerCount = extractBannerPatterns(server, allEntries);
        int itemPropCount = extractItemProperties(allEntries);

        counts.put("blockDrops", blockCount);
        counts.put("mobDrops", mobCount);
        counts.put("brewing", brewingCount);
        counts.put("enchantments", enchantCount);
        counts.put("bannerPatterns", bannerCount);
        counts.put("itemProperties", itemPropCount);
        counts.put("total", allEntries.size());

        // Build wrapper with metadata
        JsonObject wrapper = new JsonObject();

        JsonObject meta = new JsonObject();
        meta.addProperty("mcVersion", SharedConstants.getCurrentVersion().id());
        meta.addProperty("timestamp", Instant.now().toString());
        meta.addProperty("extractorVersion", 2);
        JsonObject countsObj = new JsonObject();
        for (var e : counts.entrySet()) countsObj.addProperty(e.getKey(), e.getValue());
        meta.add("counts", countsObj);
        wrapper.add("extractionMeta", meta);

        wrapper.add("entries", allEntries);

        RecipeExtractorMod.LOGGER.info("[RecipeExtractor] Extracted {} entries (crafting={}, blocks={}, mobs={}, brewing={}, enchants={}, banners={}, items={})",
                allEntries.size(), craftCount, blockCount, mobCount, brewingCount, enchantCount, bannerCount, itemPropCount);

        return new GsonBuilder().setPrettyPrinting().create().toJson(wrapper);
    }

    /**
     * Return the total entry count from extraction JSON (handles both wrapper and bare array).
     */
    public static int countEntries(String json) {
        var parsed = com.google.gson.JsonParser.parseString(json);
        if (parsed.isJsonObject() && parsed.getAsJsonObject().has("entries")) {
            return parsed.getAsJsonObject().getAsJsonArray("entries").size();
        }
        return parsed.getAsJsonArray().size();
    }

    // ── Global helpers ────────────────────────────────────────────────

    /** Serialize an Ingredient to a JsonArray of item path strings. */
    private static JsonArray serializeIngredient(Ingredient ing) {
        JsonArray arr = new JsonArray();
        if (ing == null || ing.isEmpty()) return arr;
        ing.items()
                .map(Holder::value)
                .forEach(item -> arr.add(itemPath(item)));
        return arr;
    }

    /** Add a string property only if non-null and non-empty. */
    private static void addIfNotEmpty(JsonObject obj, String key, String value) {
        if (value != null && !value.isEmpty()) {
            obj.addProperty(key, value);
        }
    }

    /** Get the full recipe ID string from a RecipeHolder. */
    private static String recipeId(RecipeHolder<?> holder) {
        return holder.id().identifier().toString();
    }

    /** Get the path portion of an item's registry key (e.g., "iron_ingot"). */
    private static String itemPath(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    /** Get the path portion of a block's registry key (e.g., "oak_log"). */
    private static String blockPath(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    /** Build the standard itemMatches array: ["minecraft:<itemId>"]. */
    private static JsonArray itemMatchesArray(String itemId) {
        JsonArray arr = new JsonArray();
        arr.add("minecraft:" + itemId);
        return arr;
    }

    // ── Part A: Crafting / Smelting / Smithing / Stonecutting ──────────

    private static int extractRecipes(MinecraftServer server, JsonArray entries, Map<String, Integer> counts) {
        RecipeManager rm = server.getRecipeManager();
        ServerLevel world = server.overworld();
        net.minecraft.util.context.ContextMap displayCtx = SlotDisplayContext.fromLevel(world);

        int craftCount = 0, smeltCount = 0, smithTransformCount = 0, smithTrimCount = 0, stonecutCount = 0;

        for (RecipeHolder<?> holder : rm.getRecipes()) {
            Recipe<?> recipe = holder.value();

            // Get output item
            ItemStack result = getRecipeOutput(recipe, displayCtx);
            if (result.isEmpty()) continue;

            String itemId = itemPath(result.getItem());
            JsonObject entry = null;

            if (recipe instanceof CraftingRecipe craftingRecipe) {
                if (craftingRecipe instanceof CustomRecipe) continue;
                entry = extractCraftingEntry(holder, itemId, craftingRecipe, result.getCount());
                if (entry != null) craftCount++;
            } else if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
                entry = extractSmeltingEntry(holder, itemId, cookingRecipe);
                if (entry != null) smeltCount++;
            } else if (recipe instanceof SmithingTrimRecipe trimRecipe) {
                // Must check SmithingTrimRecipe BEFORE SmithingTransformRecipe
                entry = extractSmithingTrimEntry(holder, trimRecipe);
                if (entry != null) smithTrimCount++;
            } else if (recipe instanceof SmithingTransformRecipe smithingRecipe) {
                entry = extractSmithingEntry(holder, itemId, smithingRecipe);
                if (entry != null) smithTransformCount++;
            } else if (recipe instanceof StonecutterRecipe stonecuttingRecipe) {
                entry = extractStonecuttingEntry(holder, itemId, stonecuttingRecipe, result.getCount());
                if (entry != null) stonecutCount++;
            }

            if (entry != null) {
                entries.add(entry);
            }
        }

        counts.put("crafting", craftCount);
        counts.put("smelting", smeltCount);
        counts.put("smithingTransform", smithTransformCount);
        counts.put("smithingTrim", smithTrimCount);
        counts.put("stonecutting", stonecutCount);

        return craftCount + smeltCount + smithTransformCount + smithTrimCount + stonecutCount;
    }

    private static ItemStack getRecipeOutput(Recipe<?> recipe, net.minecraft.util.context.ContextMap ctx) {
        List<RecipeDisplay> displays = recipe.display();
        if (displays.isEmpty()) return ItemStack.EMPTY;
        return displays.getFirst().result().resolveForFirstStack(ctx);
    }

    private static JsonObject extractCraftingEntry(RecipeHolder<?> holder, String itemId,
                                                    CraftingRecipe recipe, int yield) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.isEmpty()) return null;

        // Build grid with tag expansion
        JsonArray grid = new JsonArray();
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) {
                grid.add((String) null);
            } else {
                JsonArray slotAlts = serializeIngredient(ing);
                if (slotAlts.isEmpty()) {
                    grid.add((String) null);
                } else {
                    grid.add(slotAlts);
                }
            }
        }

        // Determine method using instanceof (not heuristic)
        String method;
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            method = (w <= 2 && h <= 2) ? "CRAFT_SHAPED_2x2" : "CRAFT_SHAPED_3x3";
        } else {
            // ShapelessRecipe or any other CraftingRecipe subclass
            method = "CRAFT_SHAPELESS";
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("itemId", itemId);
        entry.addProperty("recipeId", recipeId(holder));
        entry.addProperty("obtainMethod", method);
        entry.add("craftGrid", grid);
        entry.addProperty("craftYield", yield);
        addIfNotEmpty(entry, "group", recipe.group());
        entry.addProperty("craftingCategory", recipe.category().name());

        if (recipe instanceof ShapedRecipe shaped) {
            entry.addProperty("shapedWidth", shaped.getWidth());
            entry.addProperty("shapedHeight", shaped.getHeight());
        }

        entry.add("itemMatches", itemMatchesArray(itemId));
        return entry;
    }

    private static JsonObject extractSmeltingEntry(RecipeHolder<?> holder, String itemId,
                                                    AbstractCookingRecipe recipe) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.isEmpty()) return null;

        Ingredient input = ingredients.getFirst();
        if (input.isEmpty()) return null;

        JsonArray inputs = serializeIngredient(input);
        if (inputs.isEmpty()) return null;

        // Differentiate smelting subtypes
        String method;
        if (recipe instanceof SmeltingRecipe) {
            method = "SMELT_FURNACE";
        } else if (recipe instanceof BlastingRecipe) {
            method = "SMELT_BLAST";
        } else if (recipe instanceof SmokingRecipe) {
            method = "SMELT_SMOKER";
        } else if (recipe instanceof CampfireCookingRecipe) {
            method = "SMELT_CAMPFIRE";
        } else {
            method = "SMELT"; // unknown cooking subtype — shouldn't happen
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("itemId", itemId);
        entry.addProperty("recipeId", recipeId(holder));
        entry.addProperty("obtainMethod", method);
        entry.add("smeltFrom", inputs);
        addIfNotEmpty(entry, "group", recipe.group());
        entry.addProperty("cookingCategory", recipe.category().name());
        entry.addProperty("experience", recipe.experience());
        entry.addProperty("cookingTime", recipe.cookingTime());
        entry.add("itemMatches", itemMatchesArray(itemId));
        return entry;
    }

    private static JsonObject extractSmithingEntry(RecipeHolder<?> holder, String itemId,
                                                    SmithingTransformRecipe recipe) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.size() < 3) return null;

        // Index 0 = template, 1 = base, 2 = material
        JsonArray templates = serializeIngredient(ingredients.get(0));
        JsonArray bases = serializeIngredient(ingredients.get(1));
        JsonArray materials = serializeIngredient(ingredients.get(2));

        if (bases.isEmpty() || materials.isEmpty()) return null;

        JsonObject entry = new JsonObject();
        entry.addProperty("itemId", itemId);
        entry.addProperty("recipeId", recipeId(holder));
        entry.addProperty("obtainMethod", "SMITH");
        if (!templates.isEmpty()) entry.add("smithTemplate", templates);
        entry.add("smithBase", bases);
        entry.add("smithMaterial", materials);
        addIfNotEmpty(entry, "group", recipe.group());
        entry.add("itemMatches", itemMatchesArray(itemId));
        return entry;
    }

    private static JsonObject extractSmithingTrimEntry(RecipeHolder<?> holder,
                                                        SmithingTrimRecipe recipe) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.size() < 3) return null;

        JsonArray templates = serializeIngredient(ingredients.get(0));
        JsonArray bases = serializeIngredient(ingredients.get(1));
        JsonArray materials = serializeIngredient(ingredients.get(2));

        if (bases.isEmpty() || materials.isEmpty()) return null;

        // Use the first base item as the itemId (trims don't produce a new item)
        String itemId = bases.get(0).getAsString();

        JsonObject entry = new JsonObject();
        entry.addProperty("itemId", itemId);
        entry.addProperty("recipeId", recipeId(holder));
        entry.addProperty("obtainMethod", "SMITH_TRIM");
        if (!templates.isEmpty()) entry.add("smithTemplate", templates);
        entry.add("smithBase", bases);
        entry.add("smithMaterial", materials);
        addIfNotEmpty(entry, "group", recipe.group());
        entry.add("itemMatches", itemMatchesArray(itemId));
        return entry;
    }

    private static JsonObject extractStonecuttingEntry(RecipeHolder<?> holder, String itemId,
                                                        StonecutterRecipe recipe, int yield) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.isEmpty()) return null;

        JsonArray inputs = serializeIngredient(ingredients.getFirst());
        if (inputs.isEmpty()) return null;

        JsonObject entry = new JsonObject();
        entry.addProperty("itemId", itemId);
        entry.addProperty("recipeId", recipeId(holder));
        entry.addProperty("obtainMethod", "STONECUTTER");
        entry.add("stonecutterFrom", inputs);
        entry.add("smeltFrom", inputs); // backward compat
        entry.addProperty("stonecutterYield", yield);
        entry.addProperty("craftYield", yield); // backward compat
        addIfNotEmpty(entry, "group", recipe.group());
        entry.add("itemMatches", itemMatchesArray(itemId));
        return entry;
    }

    // ── Part B: Block drops via loot table walking ────────────────────

    /** Per-drop structured record carrying loot metadata. */
    private static class LootDropRecord {
        final String itemId;
        final JsonArray conditions;
        final JsonArray functions;
        final int weight;
        final int quality;
        final JsonObject rolls;
        final JsonObject bonusRolls;

        LootDropRecord(String itemId, JsonArray conditions, JsonArray functions,
                       int weight, int quality, JsonObject rolls, JsonObject bonusRolls) {
            this.itemId = itemId;
            this.conditions = conditions;
            this.functions = functions;
            this.weight = weight;
            this.quality = quality;
            this.rolls = rolls;
            this.bonusRolls = bonusRolls;
        }
    }

    /** Per-block metadata including loot data per dropped item. */
    private record BlockMeta(String blockId, String toolType, String requirement,
                              List<LootDropRecord> drops) {}

    /** Aggregated info for a single dropped item across all blocks. */
    private static class BlockDropInfo {
        final List<BlockMeta> blocks = new ArrayList<>();
        String bestMiningRequirement = null;
        String dimension = null;
        boolean dimensionConflict = false;

        private static final Map<String, Integer> TIER_ORDER = Map.of(
                "DIAMOND", 4, "IRON", 3, "STONE", 2, "WOOD", 1
        );

        void addBlock(BlockMeta meta, String dim) {
            blocks.add(meta);

            String miningReq = meta.requirement();
            if (miningReq != null) {
                if (bestMiningRequirement == null) {
                    bestMiningRequirement = miningReq;
                } else {
                    int existing = TIER_ORDER.getOrDefault(bestMiningRequirement, 0);
                    int incoming = TIER_ORDER.getOrDefault(miningReq, 0);
                    if (incoming < existing) {
                        bestMiningRequirement = miningReq;
                    }
                }
            }

            if (!dimensionConflict) {
                if (dimension == null && blocks.size() == 1) {
                    dimension = dim;
                } else if (dim == null || !Objects.equals(dimension, dim)) {
                    dimension = null;
                    dimensionConflict = true;
                }
            }
        }
    }

    private static int extractBlockDrops(MinecraftServer server, JsonArray entries) {
        Map<String, BlockDropInfo> dropMap = new LinkedHashMap<>();

        BuiltInRegistries.BLOCK.forEach(block -> {
            String blockId = blockPath(block);
            BlockState state = block.defaultBlockState();

            Optional<ResourceKey<LootTable>> lootKey = block.getLootTable();
            if (lootKey.isEmpty()) return;

            LootTable table = server.reloadableRegistries().getLootTable(lootKey.get());
            if (table == LootTable.EMPTY) return;

            // Walk loot table with full metadata
            List<LootDropRecord> drops = new ArrayList<>();
            walkLootTable(table, drops, server, new HashSet<>());

            if (drops.isEmpty()) return;

            String toolType = getToolType(state);
            String miningReq = getMiningRequirement(state);
            String dim = getDimension(blockId);

            // Group drops by item, attach per-block metadata
            Map<String, List<LootDropRecord>> dropsByItem = new LinkedHashMap<>();
            for (LootDropRecord drop : drops) {
                dropsByItem.computeIfAbsent(drop.itemId, k -> new ArrayList<>()).add(drop);
            }

            for (var e : dropsByItem.entrySet()) {
                BlockMeta meta = new BlockMeta(blockId, toolType, miningReq, e.getValue());
                dropMap.computeIfAbsent(e.getKey(), k -> new BlockDropInfo())
                        .addBlock(meta, dim);
            }
        });

        int count = 0;
        for (Map.Entry<String, BlockDropInfo> e : dropMap.entrySet()) {
            String itemId = e.getKey();
            BlockDropInfo info = e.getValue();

            JsonObject entry = new JsonObject();
            entry.addProperty("itemId", itemId);
            entry.addProperty("obtainMethod", "MINE");

            JsonArray mineBlocks = new JsonArray();
            for (BlockMeta b : info.blocks) {
                JsonObject blockObj = new JsonObject();
                blockObj.addProperty("block", b.blockId());
                blockObj.addProperty("toolType", b.toolType());
                if (b.requirement() != null) {
                    blockObj.addProperty("requirement", b.requirement());
                }

                // Attach loot metadata from the first drop record for this item from this block
                if (b.drops() != null && !b.drops().isEmpty()) {
                    LootDropRecord drop = b.drops().getFirst();
                    if (drop.conditions != null && !drop.conditions.isEmpty()) {
                        blockObj.add("conditions", drop.conditions);
                    }
                    if (drop.functions != null && !drop.functions.isEmpty()) {
                        blockObj.add("functions", drop.functions);
                    }
                    blockObj.addProperty("weight", drop.weight);
                    blockObj.addProperty("quality", drop.quality);
                    if (drop.rolls != null) blockObj.add("rolls", drop.rolls);
                    if (drop.bonusRolls != null) blockObj.add("bonusRolls", drop.bonusRolls);
                }

                mineBlocks.add(blockObj);
            }
            entry.add("mineBlocks", mineBlocks);

            if (info.bestMiningRequirement != null) {
                entry.addProperty("miningRequirement", info.bestMiningRequirement);
            }
            if (info.dimension != null) {
                entry.addProperty("dimension", info.dimension);
            }

            entry.add("itemMatches", itemMatchesArray(itemId));
            entries.add(entry);
            count++;
        }

        return count;
    }

    private static String getToolType(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return "PICKAXE";
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return "AXE";
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return "SHOVEL";
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return "HOE";
        return "HAND";
    }

    private static String getMiningRequirement(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return null;
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) return "DIAMOND";
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) return "IRON";
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) return "STONE";
        return "WOOD";
    }

    private static String getDimension(String blockId) {
        if (NETHER_BLOCKS.contains(blockId)) return "NETHER";
        if (END_BLOCKS.contains(blockId)) return "END";
        return null;
    }

    // ── Part C: Mob drops via entity loot tables ──────────────────────

    /** Per-entity-item pair carrying loot metadata. */
    private static class MobDropRecord {
        final String itemId;
        final JsonArray conditions;
        final JsonArray functions;

        MobDropRecord(String itemId, JsonArray conditions, JsonArray functions) {
            this.itemId = itemId;
            this.conditions = conditions;
            this.functions = functions;
        }
    }

    /**
     * Build a map of entityId → Set of dimension IDs where that entity can naturally spawn.
     * Phase 1: Iterates each loaded ServerLevel's biome source to find which biomes are possible
     * in that dimension, then checks each biome's MobSpawnSettings for spawner entries.
     * Phase 2: Scans all registered structures for spawnOverrides, cross-references structure
     * biomes against each dimension's possible biomes, and adds those entity types too.
     * Fully automated — no hardcoded dimension or structure lists.
     */
    private static Map<String, Set<String>> buildMobDimensionMap(MinecraftServer server) {
        Map<String, Set<String>> map = new LinkedHashMap<>();

        // Pre-build dimension → possible biomes map (reused by both phases)
        Map<String, Set<Holder<Biome>>> dimensionBiomes = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimensionId = level.dimension().identifier().toString();
            Set<Holder<Biome>> biomes = level.getChunkSource().getGenerator().getBiomeSource()
                    .possibleBiomes();
            dimensionBiomes.put(dimensionId, biomes);
        }

        // ── Phase 1: Biome mob spawn settings ──────────────────────────────
        for (Map.Entry<String, Set<Holder<Biome>>> dimEntry : dimensionBiomes.entrySet()) {
            String dimensionId = dimEntry.getKey();
            for (Holder<Biome> biomeHolder : dimEntry.getValue()) {
                MobSpawnSettings spawnSettings = biomeHolder.value().getMobSettings();
                for (MobCategory category : MobCategory.values()) {
                    for (var weighted : spawnSettings.getMobs(category).unwrap()) {
                        EntityType<?> entityType = weighted.value().type();
                        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType).getPath();
                        map.computeIfAbsent(entityId, k -> new LinkedHashSet<>()).add(dimensionId);
                    }
                }
            }
        }

        int biomeScanCount = map.size();

        // ── Phase 2: Structure spawn overrides ─────────────────────────────
        Registry<Structure> structureRegistry = server.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE);

        int structureEntityCount = 0;

        for (Map.Entry<ResourceKey<Structure>, Structure> entry : structureRegistry.entrySet()) {
            Structure structure = entry.getValue();

            Map<MobCategory, StructureSpawnOverride> overrides = structure.spawnOverrides();
            if (overrides.isEmpty()) continue;

            // Determine which dimensions this structure generates in by checking
            // if any of its valid biomes overlap with each dimension's possible biomes
            HolderSet<Biome> structureBiomes = structure.biomes();

            for (Map.Entry<String, Set<Holder<Biome>>> dimEntry : dimensionBiomes.entrySet()) {
                String dimensionId = dimEntry.getKey();
                Set<Holder<Biome>> dimBiomeSet = dimEntry.getValue();

                boolean overlaps = false;
                for (Holder<Biome> dimBiome : dimBiomeSet) {
                    if (structureBiomes.contains(dimBiome)) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) continue;

                for (StructureSpawnOverride override : overrides.values()) {
                    for (var weighted : override.spawns().unwrap()) {
                        EntityType<?> entityType = weighted.value().type();
                        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType).getPath();
                        boolean isNew = map.computeIfAbsent(entityId, k -> new LinkedHashSet<>())
                                .add(dimensionId);
                        if (isNew) structureEntityCount++;
                    }
                }
            }
        }

        RecipeExtractorMod.LOGGER.info("[RecipeExtractor] Built mob dimension map: {} entity types " +
                        "({} from biome scan, {} new dimension entries from structure spawn overrides)",
                map.size(), biomeScanCount, structureEntityCount);
        return map;
    }

    private static int extractMobDrops(MinecraftServer server, JsonArray entries) {
        // Build dimension map from biome registries
        Map<String, Set<String>> mobDimensions = buildMobDimensionMap(server);

        // Phase 1: Base entity tables
        Map<String, List<MobDropRecord>> entityDrops = new LinkedHashMap<>();

        BuiltInRegistries.ENTITY_TYPE.forEach(entityType -> {
            Optional<ResourceKey<LootTable>> lootKey = entityType.getDefaultLootTable();
            if (lootKey.isEmpty()) return;

            String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType).getPath();

            LootTable table = server.reloadableRegistries().getLootTable(lootKey.get());
            if (table == LootTable.EMPTY) return;

            List<LootDropRecord> drops = new ArrayList<>();
            walkLootTable(table, drops, server, new HashSet<>());

            for (LootDropRecord drop : drops) {
                entityDrops.computeIfAbsent(entityId, k -> new ArrayList<>())
                        .add(new MobDropRecord(drop.itemId, drop.conditions, drop.functions));
            }
        });

        // Phase 2: Subtable scan — entities/ prefix (catches sheep/white, etc.)
        var lootLookup = server.reloadableRegistries().lookup()
                .lookup(Registries.LOOT_TABLE);

        if (lootLookup.isPresent()) {
            lootLookup.get().listElements().forEach(holder -> {
                ResourceKey<LootTable> key = holder.key();
                String path = key.identifier().getPath();

                if (!path.startsWith("entities/")) return;

                String subPath = path.substring("entities/".length());
                int slash = subPath.indexOf('/');
                if (slash < 0) return;
                String entityName = subPath.substring(0, slash);

                LootTable table = holder.value();
                if (table == LootTable.EMPTY) return;

                List<LootDropRecord> drops = new ArrayList<>();
                walkLootTable(table, drops, server, new HashSet<>());

                for (LootDropRecord drop : drops) {
                    entityDrops.computeIfAbsent(entityName, k -> new ArrayList<>())
                            .add(new MobDropRecord(drop.itemId, drop.conditions, drop.functions));
                }
            });
        }

        // Convert to JSON — one MOB_DROP entry per (item, entity) pair, dedup by itemId
        int count = 0;
        for (Map.Entry<String, List<MobDropRecord>> e : entityDrops.entrySet()) {
            String entityId = e.getKey();

            // Dedup by itemId — keep first occurrence (richest loot metadata)
            Map<String, MobDropRecord> seen = new LinkedHashMap<>();
            for (MobDropRecord drop : e.getValue()) {
                seen.putIfAbsent(drop.itemId, drop);
            }

            for (MobDropRecord drop : seen.values()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("itemId", drop.itemId);
                entry.addProperty("obtainMethod", "MOB_DROP");
                entry.addProperty("mobClass", entityId);
                if (drop.conditions != null && !drop.conditions.isEmpty()) {
                    entry.add("lootConditions", drop.conditions);
                }
                if (drop.functions != null && !drop.functions.isEmpty()) {
                    entry.add("lootFunctions", drop.functions);
                }
                // Attach spawn dimensions from biome registry scan
                Set<String> dimensions = mobDimensions.get(entityId);
                if (dimensions != null && !dimensions.isEmpty()) {
                    JsonArray dimArray = new JsonArray();
                    for (String dim : dimensions) dimArray.add(dim);
                    entry.add("spawnDimensions", dimArray);
                }
                entry.add("itemMatches", itemMatchesArray(drop.itemId));
                entries.add(entry);
                count++;
            }
        }

        return count;
    }

    // ── Loot table walker (structured output) ─────────────────────────

    /**
     * Recursively walk a LootTable to extract all possible drops with full metadata.
     * Captures conditions, functions, weight, quality, rolls per drop.
     */
    @SuppressWarnings("unchecked")
    private static void walkLootTable(LootTable table, List<LootDropRecord> drops,
                                       MinecraftServer server, Set<ResourceKey<LootTable>> visited) {
        if (table == null || table == LootTable.EMPTY) return;

        try {
            List<LootPool> pools = (List<LootPool>) LOOT_TABLE_POOLS.get(table);
            if (pools == null) return;

            for (LootPool pool : pools) {
                // Extract pool-level metadata
                JsonArray poolConditions = serializeConditionsSafe(pool, LOOT_POOL_CONDITIONS);
                JsonArray poolFunctions = serializeFunctionsSafe(pool, LOOT_POOL_FUNCTIONS);
                JsonObject poolRolls = serializeNumberProviderSafe(pool, LOOT_POOL_ROLLS);
                JsonObject poolBonusRolls = serializeNumberProviderSafe(pool, LOOT_POOL_BONUS_ROLLS);

                List<LootPoolEntryContainer> poolEntries =
                        (List<LootPoolEntryContainer>) LOOT_POOL_ENTRIES.get(pool);
                if (poolEntries != null) {
                    walkEntries(poolEntries, drops, server, visited,
                            poolConditions, poolFunctions, poolRolls, poolBonusRolls);
                }
            }
        } catch (IllegalAccessException e) {
            RecipeExtractorMod.LOGGER.warn("[RecipeExtractor] Reflection error walking loot table", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void walkEntries(List<LootPoolEntryContainer> entryList, List<LootDropRecord> drops,
                                     MinecraftServer server, Set<ResourceKey<LootTable>> visited,
                                     JsonArray poolConditions, JsonArray poolFunctions,
                                     JsonObject poolRolls, JsonObject poolBonusRolls) {
        for (LootPoolEntryContainer entry : entryList) {
            try {
                if (entry instanceof LootItem) {
                    Holder<Item> holder = (Holder<Item>) LOOT_ITEM_HOLDER.get(entry);
                    if (holder != null) {
                        // Extract entry-level metadata
                        int weight = 1, quality = 0;
                        JsonArray entryFunctions = new JsonArray();
                        JsonArray entryConditions = new JsonArray();

                        if (entry instanceof LootPoolSingletonContainer) {
                            weight = SINGLETON_WEIGHT.getInt(entry);
                            quality = SINGLETON_QUALITY.getInt(entry);
                            List<LootItemFunction> funcs =
                                    (List<LootItemFunction>) SINGLETON_FUNCTIONS.get(entry);
                            if (funcs != null) entryFunctions = serializeFunctions(funcs);
                        }

                        List<LootItemCondition> conds =
                                (List<LootItemCondition>) ENTRY_CONDITIONS.get(entry);
                        if (conds != null) entryConditions = serializeConditions(conds);

                        // Merge pool-level + entry-level
                        JsonArray mergedConditions = mergeArrays(poolConditions, entryConditions);
                        JsonArray mergedFunctions = mergeArrays(poolFunctions, entryFunctions);

                        drops.add(new LootDropRecord(
                                itemPath(holder.value()),
                                mergedConditions, mergedFunctions,
                                weight, quality, poolRolls, poolBonusRolls
                        ));
                    }
                } else if (entry instanceof NestedLootTable) {
                    Either<ResourceKey<LootTable>, LootTable> contents =
                            (Either<ResourceKey<LootTable>, LootTable>) NESTED_TABLE_CONTENTS.get(entry);
                    if (contents != null) {
                        contents.ifLeft(key -> {
                            if (!visited.contains(key)) {
                                visited.add(key);
                                LootTable nested = server.reloadableRegistries().getLootTable(key);
                                walkLootTable(nested, drops, server, visited);
                            }
                        });
                        contents.ifRight(inlineTable ->
                                walkLootTable(inlineTable, drops, server, visited));
                    }
                } else if (entry instanceof CompositeEntryBase) {
                    List<LootPoolEntryContainer> children =
                            (List<LootPoolEntryContainer>) COMPOSITE_CHILDREN.get(entry);
                    if (children != null) {
                        walkEntries(children, drops, server, visited,
                                poolConditions, poolFunctions, poolRolls, poolBonusRolls);
                    }
                }
            } catch (IllegalAccessException e) {
                RecipeExtractorMod.LOGGER.warn("[RecipeExtractor] Reflection error on entry {}", entry.getClass().getSimpleName(), e);
            }
        }
    }

    /** Merge two JsonArrays into one (non-destructive). */
    private static JsonArray mergeArrays(JsonArray a, JsonArray b) {
        JsonArray merged = new JsonArray();
        if (a != null) merged.addAll(a);
        if (b != null) merged.addAll(b);
        return merged;
    }

    // ── Loot condition/function serializers ────────────────────────────

    /** Safely reflect a List<LootItemCondition> field and serialize. */
    @SuppressWarnings("unchecked")
    private static JsonArray serializeConditionsSafe(Object obj, Field field) {
        try {
            List<LootItemCondition> list = (List<LootItemCondition>) field.get(obj);
            if (list != null && !list.isEmpty()) return serializeConditions(list);
        } catch (IllegalAccessException e) {
            // ignore
        }
        return new JsonArray();
    }

    /** Safely reflect a List<LootItemFunction> field and serialize. */
    @SuppressWarnings("unchecked")
    private static JsonArray serializeFunctionsSafe(Object obj, Field field) {
        try {
            List<LootItemFunction> list = (List<LootItemFunction>) field.get(obj);
            if (list != null && !list.isEmpty()) return serializeFunctions(list);
        } catch (IllegalAccessException e) {
            // ignore
        }
        return new JsonArray();
    }

    /** Safely reflect a NumberProvider field and serialize. */
    private static JsonObject serializeNumberProviderSafe(Object obj, Field field) {
        try {
            Object np = field.get(obj);
            if (np instanceof NumberProvider numberProvider) {
                return serializeNumberProvider(numberProvider);
            }
        } catch (IllegalAccessException e) {
            // ignore
        }
        return null;
    }

    /** Serialize a NumberProvider to JSON. */
    private static JsonObject serializeNumberProvider(NumberProvider np) {
        JsonObject obj = new JsonObject();
        if (np instanceof ConstantValue cv) {
            obj.addProperty("type", "constant");
            // ConstantValue has a float value field
            try {
                Field valueField = ConstantValue.class.getDeclaredField("value");
                valueField.setAccessible(true);
                obj.addProperty("value", valueField.getFloat(cv));
            } catch (Exception e) {
                obj.addProperty("value", 0);
            }
        } else if (np instanceof UniformGenerator ug) {
            obj.addProperty("type", "uniform");
            try {
                Field minField = UniformGenerator.class.getDeclaredField("min");
                Field maxField = UniformGenerator.class.getDeclaredField("max");
                minField.setAccessible(true);
                maxField.setAccessible(true);
                Object minNp = minField.get(ug);
                Object maxNp = maxField.get(ug);
                if (minNp instanceof NumberProvider mnp) obj.add("min", serializeNumberProvider(mnp));
                if (maxNp instanceof NumberProvider mxp) obj.add("max", serializeNumberProvider(mxp));
            } catch (Exception e) {
                obj.addProperty("error", "reflection_failed");
            }
        } else if (np instanceof BinomialDistributionGenerator bg) {
            obj.addProperty("type", "binomial");
            try {
                Field nField = BinomialDistributionGenerator.class.getDeclaredField("n");
                Field pField = BinomialDistributionGenerator.class.getDeclaredField("p");
                nField.setAccessible(true);
                pField.setAccessible(true);
                Object nNp = nField.get(bg);
                Object pNp = pField.get(bg);
                if (nNp instanceof NumberProvider nnp) obj.add("n", serializeNumberProvider(nnp));
                if (pNp instanceof NumberProvider pnp) obj.add("p", serializeNumberProvider(pnp));
            } catch (Exception e) {
                obj.addProperty("error", "reflection_failed");
            }
        } else {
            obj.addProperty("type", "unknown");
            obj.addProperty("class", np.getClass().getSimpleName());
        }
        return obj;
    }

    /** Serialize a list of loot conditions. */
    private static JsonArray serializeConditions(List<LootItemCondition> conditions) {
        JsonArray arr = new JsonArray();
        for (LootItemCondition cond : conditions) {
            arr.add(serializeCondition(cond));
        }
        return arr;
    }

    /** Serialize a single loot condition. */
    private static JsonObject serializeCondition(LootItemCondition cond) {
        JsonObject obj = new JsonObject();

        if (cond instanceof InvertedLootItemCondition inverted) {
            obj.addProperty("type", "inverted");
            try {
                Field termField = InvertedLootItemCondition.class.getDeclaredField("term");
                termField.setAccessible(true);
                LootItemCondition inner = (LootItemCondition) termField.get(inverted);
                if (inner != null) obj.add("term", serializeCondition(inner));
            } catch (Exception e) {
                obj.addProperty("error", "reflection_failed");
            }
        } else if (cond instanceof AllOfCondition || cond instanceof AnyOfCondition) {
            obj.addProperty("type", cond instanceof AllOfCondition ? "all_of" : "any_of");
            try {
                // CompositeLootItemCondition has a "terms" field
                Field termsField = cond.getClass().getSuperclass().getDeclaredField("terms");
                termsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<LootItemCondition> terms = (List<LootItemCondition>) termsField.get(cond);
                if (terms != null) {
                    JsonArray termsArr = new JsonArray();
                    for (LootItemCondition t : terms) termsArr.add(serializeCondition(t));
                    obj.add("terms", termsArr);
                }
            } catch (Exception e) {
                obj.addProperty("error", "reflection_failed");
            }
        } else if (cond instanceof MatchTool) {
            obj.addProperty("type", "match_tool");
            // MatchTool has a predicate field — extract what we can
            try {
                Field predField = MatchTool.class.getDeclaredField("predicate");
                predField.setAccessible(true);
                Object pred = predField.get(cond);
                if (pred != null) obj.addProperty("predicate", pred.toString());
            } catch (Exception e) {
                // best effort
            }
        } else if (cond instanceof LootItemRandomChanceCondition) {
            obj.addProperty("type", "random_chance");
            try {
                Field chanceField = LootItemRandomChanceCondition.class.getDeclaredField("probability");
                chanceField.setAccessible(true);
                obj.addProperty("chance", chanceField.getFloat(cond));
            } catch (Exception e) {
                // best effort
            }
        } else if (cond instanceof LootItemKilledByPlayerCondition) {
            obj.addProperty("type", "killed_by_player");
        } else if (cond instanceof ExplosionCondition) {
            obj.addProperty("type", "survives_explosion");
        } else if (cond instanceof LootItemBlockStatePropertyCondition) {
            obj.addProperty("type", "block_state_property");
            try {
                Field blockField = LootItemBlockStatePropertyCondition.class.getDeclaredField("block");
                blockField.setAccessible(true);
                Object blockHolder = blockField.get(cond);
                if (blockHolder instanceof Holder<?> h) {
                    obj.addProperty("block", h.getRegisteredName());
                }
            } catch (Exception e) {
                // best effort
            }
        } else if (cond instanceof LootItemEntityPropertyCondition) {
            obj.addProperty("type", "entity_property");
        } else if (cond instanceof LocationCheck) {
            obj.addProperty("type", "location_check");
        } else if (cond instanceof WeatherCheck) {
            obj.addProperty("type", "weather_check");
        } else if (cond instanceof TimeCheck) {
            obj.addProperty("type", "time_check");
        } else if (cond instanceof DamageSourceCondition) {
            obj.addProperty("type", "damage_source");
        } else if (cond instanceof BonusLevelTableCondition) {
            obj.addProperty("type", "table_bonus");
        } else if (cond instanceof ValueCheckCondition) {
            obj.addProperty("type", "value_check");
        } else {
            // Catch-all: record the class name so we know what we missed
            obj.addProperty("type", "unknown");
            obj.addProperty("class", cond.getClass().getSimpleName());
        }

        return obj;
    }

    /** Serialize a list of loot functions. */
    private static JsonArray serializeFunctions(List<LootItemFunction> functions) {
        JsonArray arr = new JsonArray();
        for (LootItemFunction func : functions) {
            arr.add(serializeFunction(func));
        }
        return arr;
    }

    /** Serialize a single loot function. */
    private static JsonObject serializeFunction(LootItemFunction func) {
        JsonObject obj = new JsonObject();

        if (func instanceof SetItemCountFunction setCount) {
            obj.addProperty("type", "set_count");
            try {
                Field valueField = SetItemCountFunction.class.getDeclaredField("value");
                valueField.setAccessible(true);
                Object np = valueField.get(setCount);
                if (np instanceof NumberProvider numberProvider) {
                    obj.add("count", serializeNumberProvider(numberProvider));
                }
            } catch (Exception e) {
                // best effort
            }
        } else if (func instanceof ApplyBonusCount) {
            obj.addProperty("type", "apply_bonus");
            try {
                Field enchField = ApplyBonusCount.class.getDeclaredField("enchantment");
                enchField.setAccessible(true);
                Object enchHolder = enchField.get(func);
                if (enchHolder instanceof Holder<?> h) {
                    obj.addProperty("enchantment", h.getRegisteredName());
                }
            } catch (Exception e) {
                // best effort
            }
        } else if (func instanceof ApplyExplosionDecay) {
            obj.addProperty("type", "explosion_decay");
        } else if (func instanceof SmeltItemFunction) {
            obj.addProperty("type", "furnace_smelt");
        } else if (func instanceof EnchantedCountIncreaseFunction) {
            obj.addProperty("type", "enchanted_count_increase");
            try {
                Field enchField = EnchantedCountIncreaseFunction.class.getDeclaredField("enchantment");
                enchField.setAccessible(true);
                Object enchHolder = enchField.get(func);
                if (enchHolder instanceof Holder<?> h) {
                    obj.addProperty("enchantment", h.getRegisteredName());
                }
                Field countField = EnchantedCountIncreaseFunction.class.getDeclaredField("value");
                countField.setAccessible(true);
                Object np = countField.get(func);
                if (np instanceof NumberProvider numberProvider) {
                    obj.add("count", serializeNumberProvider(numberProvider));
                }
            } catch (Exception e) {
                // best effort
            }
        } else if (func instanceof SetItemDamageFunction) {
            obj.addProperty("type", "set_damage");
        } else if (func instanceof LimitCount) {
            obj.addProperty("type", "limit_count");
        } else {
            obj.addProperty("type", "unknown");
            obj.addProperty("class", func.getClass().getSimpleName());
        }

        return obj;
    }

    // ── Part D: Brewing recipes via PotionBrewing reflection ─────────

    @SuppressWarnings("unchecked")
    private static int extractBrewing(MinecraftServer server, JsonArray entries) {
        int count = 0;

        try {
            // Get PotionBrewing instance — bootstrap creates the vanilla instance
            PotionBrewing brewing = server.potionBrewing();

            // Extract potion mixes: from(Potion) + ingredient(Item) → to(Potion)
            List<?> potionMixes = (List<?>) POTION_BREWING_POTION_MIXES.get(brewing);
            if (potionMixes != null) {
                for (Object mix : potionMixes) {
                    JsonObject entry = serializeBrewingMix(mix, "BREW_POTION");
                    if (entry != null) {
                        entries.add(entry);
                        count++;
                    }
                }
            }

            // Extract container mixes: from(Item) + ingredient(Item) → to(Item)
            List<?> containerMixes = (List<?>) POTION_BREWING_CONTAINER_MIXES.get(brewing);
            if (containerMixes != null) {
                for (Object mix : containerMixes) {
                    JsonObject entry = serializeBrewingMix(mix, "BREW_CONTAINER");
                    if (entry != null) {
                        entries.add(entry);
                        count++;
                    }
                }
            }

            // Extract valid containers (bottles that can be used in brewing)
            List<Ingredient> containers = (List<Ingredient>) POTION_BREWING_CONTAINERS.get(brewing);
            if (containers != null) {
                JsonObject containerEntry = new JsonObject();
                containerEntry.addProperty("obtainMethod", "BREW_CONTAINERS");
                JsonArray containerArr = new JsonArray();
                for (Ingredient ing : containers) {
                    JsonArray items = serializeIngredient(ing);
                    if (!items.isEmpty()) containerArr.addAll(items);
                }
                containerEntry.add("containers", containerArr);
                entries.add(containerEntry);
                count++;
            }

        } catch (Exception e) {
            RecipeExtractorMod.LOGGER.warn("[RecipeExtractor] Error extracting brewing data", e);
        }

        return count;
    }

    /** Serialize a PotionBrewing.Mix record via reflection. Works for both potion and container mixes. */
    private static JsonObject serializeBrewingMix(Object mix, String obtainMethod) {
        try {
            // Mix is a record with from(), ingredient(), to() accessors
            var fromMethod = mix.getClass().getMethod("from");
            var ingredientMethod = mix.getClass().getMethod("ingredient");
            var toMethod = mix.getClass().getMethod("to");

            Object from = fromMethod.invoke(mix);
            Ingredient ingredient = (Ingredient) ingredientMethod.invoke(mix);
            Object to = toMethod.invoke(mix);

            String fromId = holderToString(from);
            String toId = holderToString(to);
            if (fromId == null || toId == null) return null;

            JsonObject entry = new JsonObject();
            entry.addProperty("itemId", toId);
            entry.addProperty("obtainMethod", obtainMethod);
            entry.addProperty("brewFrom", fromId);
            entry.add("brewIngredient", serializeIngredient(ingredient));
            entry.addProperty("brewTo", toId);
            entry.add("itemMatches", itemMatchesArray(toId));
            return entry;
        } catch (Exception e) {
            RecipeExtractorMod.LOGGER.warn("[RecipeExtractor] Error serializing brewing mix: {}", mix.getClass().getSimpleName(), e);
            return null;
        }
    }

    /** Extract the path string from a Holder<?> — works for Holder<Potion> and Holder<Item>. */
    private static String holderToString(Object holderObj) {
        if (holderObj instanceof Holder<?> holder) {
            return holder.unwrapKey()
                    .map(key -> key.identifier().getPath())
                    .orElse(null);
        }
        return null;
    }

    // ── Part E: Enchantment registry extraction ─────────────────────

    private static int extractEnchantments(MinecraftServer server, JsonArray entries) {
        int count = 0;

        var enchantmentRegistry = server.reloadableRegistries().lookup()
                .lookup(Registries.ENCHANTMENT);
        if (enchantmentRegistry.isEmpty()) return 0;

        var registry = enchantmentRegistry.get();
        for (Holder<Enchantment> holder : registry.listElements().toList()) {
            Enchantment ench = holder.value();
            String enchId = holder.unwrapKey()
                    .map(key -> key.identifier().getPath())
                    .orElse("unknown");

            JsonObject entry = new JsonObject();
            entry.addProperty("itemId", enchId);
            entry.addProperty("obtainMethod", "ENCHANTMENT");
            entry.addProperty("enchantmentId", enchId);

            // Full definition data
            entry.addProperty("weight", ench.getWeight());
            entry.addProperty("anvilCost", ench.getAnvilCost());
            entry.addProperty("minLevel", ench.getMinLevel());
            entry.addProperty("maxLevel", ench.getMaxLevel());

            // Cost per level
            JsonArray costPerLevel = new JsonArray();
            for (int level = ench.getMinLevel(); level <= ench.getMaxLevel(); level++) {
                JsonObject levelCost = new JsonObject();
                levelCost.addProperty("level", level);
                levelCost.addProperty("minCost", ench.getMinCost(level));
                levelCost.addProperty("maxCost", ench.getMaxCost(level));
                costPerLevel.add(levelCost);
            }
            entry.add("costPerLevel", costPerLevel);

            // Supported items
            JsonArray supportedItems = new JsonArray();
            ench.getSupportedItems().stream()
                    .map(Holder::value)
                    .forEach(item -> supportedItems.add(itemPath(item)));
            entry.add("supportedItems", supportedItems);

            // Primary items (subset that can get this enchantment from enchanting table)
            Enchantment.EnchantmentDefinition def = ench.definition();
            if (def.primaryItems().isPresent()) {
                JsonArray primaryItems = new JsonArray();
                def.primaryItems().get().stream()
                        .map(Holder::value)
                        .forEach(item -> primaryItems.add(itemPath(item)));
                entry.add("primaryItems", primaryItems);
            }

            // Equipment slots
            JsonArray slots = new JsonArray();
            for (EquipmentSlotGroup slotGroup : def.slots()) {
                slots.add(slotGroup.toString());
            }
            entry.add("slots", slots);

            // Exclusive set (incompatible enchantments)
            HolderSet<Enchantment> exclusives = ench.exclusiveSet();
            if (exclusives.size() > 0) {
                JsonArray exclusiveArr = new JsonArray();
                exclusives.stream().forEach(exHolder -> {
                    String exId = exHolder.unwrapKey()
                            .map(key -> key.identifier().getPath())
                            .orElse("unknown");
                    exclusiveArr.add(exId);
                });
                entry.add("exclusiveWith", exclusiveArr);
            }

            // Description
            entry.addProperty("description", ench.description().getString());

            entry.add("itemMatches", itemMatchesArray(enchId));
            entries.add(entry);
            count++;
        }

        return count;
    }

    // ── Part F: Banner pattern registry extraction ───────────────────

    private static int extractBannerPatterns(MinecraftServer server, JsonArray entries) {
        int count = 0;

        var patternRegistry = server.reloadableRegistries().lookup()
                .lookup(Registries.BANNER_PATTERN);
        if (patternRegistry.isEmpty()) return 0;

        var registry = patternRegistry.get();
        for (Holder<BannerPattern> holder : registry.listElements().toList()) {
            BannerPattern pattern = holder.value();
            String patternId = holder.unwrapKey()
                    .map(key -> key.identifier().getPath())
                    .orElse("unknown");

            JsonObject entry = new JsonObject();
            entry.addProperty("itemId", patternId);
            entry.addProperty("obtainMethod", "BANNER_PATTERN");
            entry.addProperty("patternId", patternId);
            entry.addProperty("assetId", pattern.assetId().toString());
            entry.addProperty("translationKey", pattern.translationKey());
            entry.add("itemMatches", itemMatchesArray(patternId));

            entries.add(entry);
            count++;
        }

        return count;
    }

    // ── Part G: Item property extraction (Repairable, Enchantable, Equippable) ──

    private static int extractItemProperties(JsonArray entries) {
        int count = 0;

        for (Item item : BuiltInRegistries.ITEM) {
            String id = itemPath(item);
            ItemStack stack = new ItemStack(item);

            JsonObject props = new JsonObject();
            boolean hasData = false;

            // Repairable component — what materials repair this item
            Repairable repairable = stack.get(DataComponents.REPAIRABLE);
            if (repairable != null) {
                JsonArray repairItems = new JsonArray();
                repairable.items().stream()
                        .map(Holder::value)
                        .forEach(repairItem -> repairItems.add(itemPath(repairItem)));
                if (!repairItems.isEmpty()) {
                    props.add("repairsWith", repairItems);
                    hasData = true;
                }
            }

            // Enchantable component — enchantability value (higher = better enchantments)
            Enchantable enchantable = stack.get(DataComponents.ENCHANTABLE);
            if (enchantable != null) {
                props.addProperty("enchantability", enchantable.value());
                hasData = true;
            }

            // Equippable component — armor slot, dispensable, etc.
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable != null) {
                JsonObject equipData = new JsonObject();
                equipData.addProperty("slot", equippable.slot().getName());
                equipData.addProperty("dispensable", equippable.dispensable());
                equipData.addProperty("swappable", equippable.swappable());
                equipData.addProperty("damageOnHurt", equippable.damageOnHurt());
                equipData.addProperty("equipOnInteract", equippable.equipOnInteract());
                equippable.assetId().ifPresent(assetKey ->
                        equipData.addProperty("assetId", assetKey.identifier().toString()));
                equippable.cameraOverlay().ifPresent(overlay ->
                        equipData.addProperty("cameraOverlay", overlay.toString()));
                props.add("equippable", equipData);
                hasData = true;
            }

            // Max stack size
            int maxStack = stack.getMaxStackSize();
            if (maxStack != 64) {
                props.addProperty("maxStackSize", maxStack);
                hasData = true;
            }

            // Max damage (durability)
            int maxDamage = stack.getMaxDamage();
            if (maxDamage > 0) {
                props.addProperty("maxDurability", maxDamage);
                hasData = true;
            }

            if (hasData) {
                JsonObject entry = new JsonObject();
                entry.addProperty("itemId", id);
                entry.addProperty("obtainMethod", "ITEM_PROPERTIES");
                entry.add("properties", props);
                entry.add("itemMatches", itemMatchesArray(id));
                entries.add(entry);
                count++;
            }
        }

        return count;
    }
}
