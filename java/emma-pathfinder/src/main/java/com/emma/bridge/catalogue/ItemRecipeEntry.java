package com.emma.bridge.catalogue;

/**
 * POJO representing one item entry in the data-driven item catalogue.
 * Loaded from item_recipes.json by GSON in ItemRecipeRegistry.
 *
 * This is a flat POJO — all entry types share the same class, with each
 * obtainMethod using a different subset of fields (others remain null/0).
 */
public class ItemRecipeEntry {

    // ── Inner static classes for structured loot/metadata ──────────

    /** Deserialized loot condition from loot table walking. */
    public static class LootCondition {
        private String type;           // "killed_by_player", "random_chance", "match_tool", etc.
        private Float chance;          // random_chance only
        private String predicate;      // match_tool only (toString of predicate)
        private String block;          // block_state_property only
        private String enchantment;    // enchantment context
        private LootCondition term;    // inverted only
        private LootCondition[] terms; // all_of / any_of only

        public String getType() { return type; }
        public Float getChance() { return chance; }
        public String getPredicate() { return predicate; }
        public String getBlock() { return block; }
        public String getEnchantment() { return enchantment; }
        public LootCondition getTerm() { return term; }
        public LootCondition[] getTerms() { return terms; }
    }

    /** Deserialized loot function from loot table walking. */
    public static class LootFunction {
        private String type;           // "set_count", "enchanted_count_increase", "apply_bonus", etc.
        private String enchantment;    // apply_bonus / enchanted_count_increase
        private NumberRange count;     // set_count range

        public String getType() { return type; }
        public String getEnchantment() { return enchantment; }
        public NumberRange getCount() { return count; }
    }

    /** Deserialized NumberProvider (constant, uniform, binomial). */
    public static class NumberRange {
        private String type;    // "constant", "uniform", "binomial"
        private Float value;    // constant only
        private NumberRange min; // uniform only
        private NumberRange max; // uniform only
        private NumberRange n;   // binomial only
        private NumberRange p;   // binomial only

        public String getType() { return type; }
        public Float getValue() { return value; }
        public NumberRange getMin() { return min; }
        public NumberRange getMax() { return max; }

        /** Get the constant value, or 0 if not a constant type. */
        public float getConstantValue() {
            return "constant".equals(type) && value != null ? value : 0f;
        }
    }

    /** Per-block metadata for MINE entries (toolType, mining requirement, loot data). */
    public static class MineBlockInfo {
        private String block;
        private String toolType;            // "PICKAXE", "AXE", "SHOVEL", "HOE", "HAND"
        private String requirement;         // "WOOD", "STONE", "IRON", "DIAMOND", or null (hand-mineable)
        private LootCondition[] conditions; // structured loot conditions
        private LootFunction[] functions;   // structured loot functions
        private int weight;                 // loot entry weight
        private int quality;                // loot entry quality
        private NumberRange rolls;          // pool roll count
        private NumberRange bonusRolls;     // pool bonus roll count

        public String getBlock() { return block; }
        public String getToolType() { return toolType; }
        public String getRequirement() { return requirement; }
        public LootCondition[] getConditions() { return conditions; }
        public LootFunction[] getFunctions() { return functions; }
        public int getWeight() { return weight; }
        public int getQuality() { return quality; }
        public NumberRange getRolls() { return rolls; }
        public NumberRange getBonusRolls() { return bonusRolls; }
    }

    /** Cost range for a single enchantment level. */
    public static class EnchantmentLevelCost {
        private int level;
        private int minCost;
        private int maxCost;

        public int getLevel() { return level; }
        public int getMinCost() { return minCost; }
        public int getMaxCost() { return maxCost; }
    }

    /** Equipment slot and behavior data from Equippable component. */
    public static class EquippableInfo {
        private String slot;           // "head", "chest", "legs", "feet"
        private boolean dispensable;
        private boolean swappable;
        private boolean damageOnHurt;
        private boolean equipOnInteract;
        private String assetId;

        public String getSlot() { return slot; }
        public boolean isDispensable() { return dispensable; }
        public boolean isSwappable() { return swappable; }
        public boolean isDamageOnHurt() { return damageOnHurt; }
        public boolean isEquipOnInteract() { return equipOnInteract; }
        public String getAssetId() { return assetId; }
    }

    /** Item property metadata: repair, enchantability, equipment, stack/durability. */
    public static class ItemProperties {
        private String[] repairsWith;
        private Integer enchantability;
        private EquippableInfo equippable;
        private Integer maxStackSize;
        private Integer maxDurability;

        public String[] getRepairsWith() { return repairsWith; }
        public Integer getEnchantability() { return enchantability; }
        public EquippableInfo getEquippable() { return equippable; }
        public Integer getMaxStackSize() { return maxStackSize; }
        public Integer getMaxDurability() { return maxDurability; }
    }

    // ── Universal fields ──────────────────────────────────────────

    private String itemId;
    private String[] itemMatches;
    private ObtainMethod obtainMethod;
    private String recipeId;            // full recipe ID, e.g. "minecraft:iron_ingot_from_smelting"
    private String group;               // recipe group, e.g. "wooden_fence"

    // ── MINE fields ───────────────────────────────────────────────

    private MineBlockInfo[] mineBlocks;
    private String miningRequirement;   // "HAND", "WOOD", "STONE", "IRON", "DIAMOND"

    // ── CRAFT fields ──────────────────────────────────────────────

    private String[][] craftGrid;       // length 4 (2x2) or 9 (3x3), each slot is array of alternatives
    private int craftYield = 1;
    private String craftingCategory;    // "BUILDING", "REDSTONE", "EQUIPMENT", "MISC"
    private int shapedWidth;
    private int shapedHeight;

    // ── SMELT fields ──────────────────────────────────────────────

    private String[] smeltFrom;         // all valid smelt inputs (tag-expanded)
    private String cookingCategory;     // "FOOD", "BLOCKS", "MISC"
    private float experience;
    private int cookingTime;            // ticks

    // ── SMITH fields ──────────────────────────────────────────────

    private String[] smithTemplate;
    private String[] smithBase;
    private String[] smithMaterial;
    private String trimPattern;         // SMITH_TRIM only

    // ── MOB_DROP fields ───────────────────────────────────────────

    private String mobClass;            // entity type path, e.g. "cow", "blaze"
    private LootCondition[] lootConditions;
    private LootFunction[] lootFunctions;
    private String[] spawnDimensions;   // e.g. ["minecraft:overworld", "minecraft:the_nether"]

    // ── SHEAR fields ──────────────────────────────────────────────

    private String[] shearBlocks;

    // ── CROP fields ───────────────────────────────────────────────

    private String[] cropBlocks;
    private String[] cropSeeds;

    // ── Modifiers ─────────────────────────────────────────────────

    private String dimension;           // derived: "NETHER", "END", or null (any/overworld)
    private boolean dontMineIfPresent;
    private boolean craftOnly;

    // ── TRANSFORM fields ──────────────────────────────────────────

    private String transformInput;
    private String transformTool;       // tool category (e.g., "axe") — null for fluid/water
    private String transformType;       // "TOOL_USE", "WATER_CONTACT", "FLUID", "OXIDATION"

    // ── INTERACT fields ───────────────────────────────────────────

    private String interactEntity;      // e.g., "Sheep", "Cow"
    private String interactBlock;       // e.g., "beehive" — null for entity interactions
    private String interactTool;        // e.g., "shears", "bucket"

    // ── CUSTOM fields ─────────────────────────────────────────────

    private String customTaskClass;

    // ── STONECUTTER fields ────────────────────────────────────────

    private String[] stonecutterFrom;
    private int stonecutterYield;

    // ── BREW fields ───────────────────────────────────────────────

    private String brewFrom;            // base potion/container ID
    private String[] brewIngredient;    // ingredient item IDs
    private String brewTo;              // result potion/container ID
    private String[] containers;        // BREW_CONTAINERS only: valid container items

    // ── ENCHANTMENT fields ────────────────────────────────────────

    private String enchantmentId;
    private int weight;                 // enchantment weight (rarity)
    private int anvilCost;
    private int minLevel;
    private int maxLevel;
    private EnchantmentLevelCost[] costPerLevel;
    private String[] supportedItems;    // all items that accept this enchantment
    private String[] primaryItems;      // subset for enchanting table
    private String[] slots;             // equipment slot groups: "HEAD", "CHEST", etc.
    private String[] exclusiveWith;     // incompatible enchantments
    private String description;         // human-readable name

    // ── BANNER_PATTERN fields ─────────────────────────────────────

    private String patternId;
    private String assetId;
    private String translationKey;

    // ── ITEM_PROPERTIES fields ────────────────────────────────────

    private ItemProperties properties;

    // ── Dependency graph ──────────────────────────────────────────

    private String[] dependencies;      // explicit deps (auto-derived when absent)
    private String[] aliases;

    // ── Factory methods for runtime construction (MC RecipeManager) ──

    public static ItemRecipeEntry ofCraft(String itemId, ObtainMethod method, String[][] craftGrid, int craftYield) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = method;
        e.craftGrid = craftGrid;
        e.craftYield = craftYield;
        return e;
    }

    public static ItemRecipeEntry ofSmelt(String itemId, String[] smeltFrom) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = ObtainMethod.SMELT;
        e.smeltFrom = smeltFrom;
        return e;
    }

    public static ItemRecipeEntry ofSmelt(String itemId, ObtainMethod method, String[] smeltFrom,
                                           float experience, int cookingTime) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = method;
        e.smeltFrom = smeltFrom;
        e.experience = experience;
        e.cookingTime = cookingTime;
        return e;
    }

    public static ItemRecipeEntry ofSmith(String itemId, String[] smithBase, String[] smithMaterial) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = ObtainMethod.SMITH;
        e.smithBase = smithBase;
        e.smithMaterial = smithMaterial;
        return e;
    }

    public static ItemRecipeEntry ofSmith(String itemId, String[] smithTemplate, String[] smithBase, String[] smithMaterial) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = ObtainMethod.SMITH;
        e.smithTemplate = smithTemplate;
        e.smithBase = smithBase;
        e.smithMaterial = smithMaterial;
        return e;
    }

    public static ItemRecipeEntry ofSmithTrim(String itemId, String[] smithTemplate, String[] smithBase,
                                               String[] smithMaterial, String trimPattern) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = ObtainMethod.SMITH_TRIM;
        e.smithTemplate = smithTemplate;
        e.smithBase = smithBase;
        e.smithMaterial = smithMaterial;
        e.trimPattern = trimPattern;
        return e;
    }

    public static ItemRecipeEntry ofStonecutter(String itemId, String[] inputItems) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = ObtainMethod.STONECUTTER;
        e.smeltFrom = inputItems;           // backward compat
        e.stonecutterFrom = inputItems;     // proper field
        return e;
    }

    public static ItemRecipeEntry ofStonecutter(String itemId, String[] inputItems, int yield) {
        ItemRecipeEntry e = ofStonecutter(itemId, inputItems);
        e.stonecutterYield = yield;
        e.craftYield = yield;
        return e;
    }

    public static ItemRecipeEntry ofTransform(String itemId, String input, String tool, String type) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = ObtainMethod.TRANSFORM;
        e.transformInput = input;
        e.transformTool = tool;
        e.transformType = type;
        if (input != null) {
            e.dependencies = new String[]{input};
        }
        return e;
    }

    public static ItemRecipeEntry ofInteract(String itemId, String entity, String tool) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = ObtainMethod.INTERACT;
        e.interactEntity = entity;
        e.interactTool = tool;
        if (tool != null) {
            e.dependencies = new String[]{tool};
        }
        return e;
    }

    public static ItemRecipeEntry ofInteractBlock(String itemId, String block, String tool) {
        ItemRecipeEntry e = new ItemRecipeEntry();
        e.itemId = itemId;
        e.itemMatches = new String[]{"minecraft:" + itemId};
        e.obtainMethod = ObtainMethod.INTERACT;
        e.interactBlock = block;
        e.interactTool = tool;
        if (tool != null) {
            e.dependencies = new String[]{tool};
        }
        return e;
    }

    // ── Dimension derivation (called from ItemRecipeRegistry.load) ──

    /**
     * Derive the legacy 'dimension' field from the new 'spawnDimensions' array.
     * If spawnDimensions is null/empty, checks the fallback map in ItemRecipeRegistry.
     * - All nether → "NETHER"
     * - All end → "END"
     * - Mixed or overworld → null (any dimension)
     */
    void deriveDimensionFromSpawnDimensions() {
        String[] dims = this.spawnDimensions;

        // Apply fallback for mobs missing spawnDimensions (structure/special spawns)
        if ((dims == null || dims.length == 0) && this.mobClass != null) {
            dims = ItemRecipeRegistry.FALLBACK_MOB_DIMENSIONS.get(this.mobClass);
            if (dims != null) {
                this.spawnDimensions = dims;
            }
        }

        if (dims == null || dims.length == 0) {
            this.dimension = null;
            return;
        }

        boolean hasNether = false, hasEnd = false, hasOverworld = false;
        for (String d : dims) {
            if (d.contains("the_nether")) hasNether = true;
            else if (d.contains("the_end")) hasEnd = true;
            else hasOverworld = true;
        }

        if (hasNether && !hasEnd && !hasOverworld) {
            this.dimension = "NETHER";
        } else if (hasEnd && !hasNether && !hasOverworld) {
            this.dimension = "END";
        } else {
            this.dimension = null;
        }
    }

    // ── Getters ───────────────────────────────────────────────────

    public String getItemId() { return itemId; }
    public String[] getItemMatches() { return itemMatches; }
    public ObtainMethod getObtainMethod() { return obtainMethod; }
    public MineBlockInfo[] getMineBlocks() { return mineBlocks; }

    /** Convenience: just the block name strings (for call sites that only need names). */
    public String[] getMineBlockNames() {
        if (mineBlocks == null) return null;
        String[] names = new String[mineBlocks.length];
        for (int i = 0; i < mineBlocks.length; i++) {
            names[i] = mineBlocks[i].getBlock();
        }
        return names;
    }
    public String getMiningRequirement() { return miningRequirement; }
    public String[][] getCraftGrid() { return craftGrid; }
    public int getCraftYield() { return craftYield; }
    public String[] getSmeltFrom() { return smeltFrom; }
    public String[] getSmithBase() { return smithBase; }
    public String[] getSmithMaterial() { return smithMaterial; }
    public String getMobClass() { return mobClass; }
    public String[] getShearBlocks() { return shearBlocks; }
    public String[] getCropBlocks() { return cropBlocks; }
    public String[] getCropSeeds() { return cropSeeds; }
    public String getDimension() { return dimension; }
    public boolean isDontMineIfPresent() { return dontMineIfPresent; }
    public boolean isCraftOnly() { return craftOnly; }
    public String getTransformInput() { return transformInput; }
    public String getTransformTool() { return transformTool; }
    public String getTransformType() { return transformType; }
    public String getInteractEntity() { return interactEntity; }
    public String getInteractBlock() { return interactBlock; }
    public String getInteractTool() { return interactTool; }
    public String getCustomTaskClass() { return customTaskClass; }
    public String[] getDependencies() { return dependencies; }
    public String[] getAliases() { return aliases; }

    // Universal metadata
    public String getRecipeId() { return recipeId; }
    public String getGroup() { return group; }

    // CRAFT
    public String getCraftingCategory() { return craftingCategory; }
    public int getShapedWidth() { return shapedWidth; }
    public int getShapedHeight() { return shapedHeight; }

    // SMELT
    public String getCookingCategory() { return cookingCategory; }
    public float getExperience() { return experience; }
    public int getCookingTime() { return cookingTime; }

    // SMITH
    public String[] getSmithTemplate() { return smithTemplate; }
    public String getTrimPattern() { return trimPattern; }

    // STONECUTTER
    public String[] getStonecutterFrom() { return stonecutterFrom; }
    public int getStonecutterYield() { return stonecutterYield; }

    // MOB_DROP
    public LootCondition[] getLootConditions() { return lootConditions; }
    public LootFunction[] getLootFunctions() { return lootFunctions; }
    public String[] getSpawnDimensions() { return spawnDimensions; }

    // BREW
    public String getBrewFrom() { return brewFrom; }
    public String[] getBrewIngredient() { return brewIngredient; }
    public String getBrewTo() { return brewTo; }
    public String[] getContainers() { return containers; }

    // ENCHANTMENT
    public String getEnchantmentId() { return enchantmentId; }
    public int getEnchantmentWeight() { return weight; }
    public int getAnvilCost() { return anvilCost; }
    public int getMinLevel() { return minLevel; }
    public int getMaxLevel() { return maxLevel; }
    public EnchantmentLevelCost[] getCostPerLevel() { return costPerLevel; }
    public String[] getSupportedItems() { return supportedItems; }
    public String[] getPrimaryItems() { return primaryItems; }
    public String[] getSlots() { return slots; }
    public String[] getExclusiveWith() { return exclusiveWith; }
    public String getDescription() { return description; }

    // BANNER_PATTERN
    public String getPatternId() { return patternId; }
    public String getAssetId() { return assetId; }
    public String getTranslationKey() { return translationKey; }

    // ITEM_PROPERTIES
    public ItemProperties getProperties() { return properties; }
}
