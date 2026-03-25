package com.emma.bridge.goap;

import com.emma.bridge.EmmaBridgeMod;
import com.emma.bridge.catalogue.ItemRecipeEntry;
import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.catalogue.ObtainMethod;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Recursive goal decomposition engine.
 *
 * Expands goals into derived subgoals:
 *   - Composite goals (kill_dragon) → have_item prerequisites
 *   - have_item goals → transitive dependencies via ItemRecipeRegistry
 *
 * Subgoals are ranked by feasibility:
 *   - Items with nearby mineable blocks get priority boost
 *   - Items with all craft ingredients in inventory get priority boost
 *   - Items requiring unavailable containers get no boost
 *
 * Also creates prerequisite subgoals:
 *   - Tool prereqs: mining diamond ore requires iron_pickaxe
 *   - Container prereqs: smelting requires furnace, 3x3 crafting requires crafting_table
 *
 * Called from GoapTicker on goal change or inventory change (debounced).
 * Derived goals are transient — recomputed from scratch each time.
 */
public class GoalDecomposer {

    /** Priority offset for derived goals below their parent. */
    private static final float PRIORITY_OFFSET = 0.5f;

    /** Mining requirement → minimum pickaxe needed. */
    private static final Map<String, String> TOOL_FOR_REQUIREMENT = Map.of(
            "WOOD", "wooden_pickaxe",
            "STONE", "stone_pickaxe",
            "IRON", "iron_pickaxe",
            "DIAMOND", "diamond_pickaxe"
    );

    /** Obtain methods that require a specific container/workstation. */
    private static final Map<ObtainMethod, String> CONTAINER_FOR_METHOD = Map.of(
            ObtainMethod.SMELT, "furnace",
            ObtainMethod.SMELT_FURNACE, "furnace",
            ObtainMethod.SMELT_BLAST, "furnace",
            ObtainMethod.SMELT_SMOKER, "furnace",
            ObtainMethod.SMELT_CAMPFIRE, "furnace",
            ObtainMethod.CRAFT_SHAPED_3x3, "crafting_table",
            ObtainMethod.STONECUTTER, "stonecutter",
            ObtainMethod.SMITH, "smithing_table",
            ObtainMethod.BREW_POTION, "brewing_stand",
            ObtainMethod.BREW_CONTAINER, "brewing_stand"
    );

    /**
     * Get the correct tool item for a mining requirement + tool type.
     * E.g., (IRON, PICKAXE) → iron_pickaxe, (WOOD, AXE) → wooden_axe
     */
    private static String getToolForRequirement(String requirement, String toolType) {
        if (requirement == null || "HAND".equals(requirement)) return null;
        if (toolType == null || "HAND".equals(toolType)) return null;
        String prefix = switch (requirement) {
            case "WOOD" -> "wooden";
            case "STONE" -> "stone";
            case "IRON" -> "iron";
            case "DIAMOND" -> "diamond";
            default -> null;
        };
        if (prefix == null) return null;
        String suffix = switch (toolType) {
            case "PICKAXE" -> "_pickaxe";
            case "AXE" -> "_axe";
            case "SHOVEL" -> "_shovel";
            case "HOE" -> "_hoe";
            default -> "_pickaxe";
        };
        return prefix + suffix;
    }

    // ── Composite goal definitions ──────────────────────────────────

    /** Prerequisite item + count for a composite goal. */
    private record CompositePrereq(String item, int count) {}

    /** kill_dragon: full item prerequisites for an ender dragon speedrun. */
    private static final List<CompositePrereq> KILL_DRAGON_PREREQS = List.of(
            new CompositePrereq("ender_eye",         12),
            new CompositePrereq("diamond_sword",      1),
            new CompositePrereq("diamond_pickaxe",    1),
            new CompositePrereq("diamond_helmet",     1),
            new CompositePrereq("diamond_chestplate", 1),
            new CompositePrereq("diamond_leggings",   1),
            new CompositePrereq("diamond_boots",      1),
            new CompositePrereq("white_bed",          7),
            new CompositePrereq("cooked_beef",       32),
            new CompositePrereq("obsidian",          10),
            new CompositePrereq("flint_and_steel",    1),
            new CompositePrereq("bucket",             2),
            new CompositePrereq("torch",             32),
            new CompositePrereq("shield",             1)
    );

    /** Composite goal type → ordered prerequisite list. Extensible. */
    private static final Map<String, List<CompositePrereq>> COMPOSITE_GOALS = Map.of(
            "kill_dragon", KILL_DRAGON_PREREQS
    );

    // ── Pipeline stage definitions (non-item goals for kill_dragon) ──

    /** A non-item pipeline stage goal (portal building, dimension travel, etc.). */
    private record PipelineStage(String id, String type, float priority, JsonObject target) {
        PipelineStage(String id, String type, float priority) {
            this(id, type, priority, new JsonObject());
        }
    }

    private static final List<PipelineStage> KILL_DRAGON_PIPELINE;
    static {
        JsonObject enterNether = new JsonObject();
        enterNether.addProperty("dimension", "minecraft:the_nether");
        JsonObject returnOverworld = new JsonObject();
        returnOverworld.addProperty("dimension", "minecraft:overworld");
        JsonObject enterEnd = new JsonObject();
        enterEnd.addProperty("dimension", "minecraft:the_end");

        KILL_DRAGON_PIPELINE = List.of(
                new PipelineStage("build_nether_portal", "build_nether_portal", 12.0f),
                new PipelineStage("enter_nether",        "enter_dimension",     11.5f, enterNether),
                new PipelineStage("return_overworld",    "enter_dimension",     11.0f, returnOverworld),
                new PipelineStage("locate_stronghold",   "locate_stronghold",   10.5f),
                new PipelineStage("activate_end_portal", "activate_end_portal", 10.0f),
                new PipelineStage("enter_end",           "enter_dimension",      9.5f, enterEnd),
                new PipelineStage("destroy_crystals",    "destroy_end_crystals", 9.0f),
                new PipelineStage("kill_dragon_fight",   "kill_ender_dragon",    8.5f)
        );
    }

    /**
     * Decompose all have_item goals into derived subgoals.
     *
     * @param goalSet  The current goal set (reads user goals only)
     * @param state    Current world state (for inventory checks)
     * @return List of derived goals to set on GoalSet
     */
    public List<GoalSet.Goal> decompose(GoalSet goalSet, WorldState state) {
        List<GoalSet.Goal> derived = new ArrayList<>();
        Set<String> seen = new HashSet<>();  // prevent duplicate subgoals

        for (GoalSet.Goal goal : goalSet.getUserGoals()) {
            if (goal.isSurvival()) continue;

            // Composite goals (kill_dragon, etc.) → expand into have_item prereqs
            List<CompositePrereq> prereqs = COMPOSITE_GOALS.get(goal.type);
            if (prereqs != null) {
                decomposeComposite(goal, prereqs, state, derived, seen);
                continue;
            }

            // Build structure goals → have_item subgoals for each material
            if ("build_structure".equals(goal.type)) {
                decomposeBuildStructure(goal, state, derived, seen);
                continue;
            }

            if (goal.target == null || !goal.target.has("item")) continue;

            String goalItem = goal.target.get("item").getAsString();
            String goalId = stripNamespace(goalItem);
            int goalCount = goal.target.has("count") ? goal.target.get("count").getAsInt() : 1;

            // Skip if we already have the item (or better equipped)
            if (state.isGoalItemSatisfied(goalItem, goalCount)) {
                EmmaBridgeMod.LOGGER.debug("[GoalDecomposer] Skipping {} — already satisfied", goalId);
                continue;
            }

            // Walk full dependency chain
            int before = derived.size();
            decomposeItem(goalId, goal.id, goal.priority, state, derived, seen);
            EmmaBridgeMod.LOGGER.debug("[GoalDecomposer] {} → {} subgoals", goalId, derived.size() - before);
        }

        if (!derived.isEmpty()) {
            EmmaBridgeMod.LOGGER.info("[GoalDecomposer] Expanded {} user goals into {} subgoals",
                    goalSet.getUserGoals().size(), derived.size());
        }

        return derived;
    }

    /**
     * Decompose a single item into subgoals. Creates a subgoal for EACH unique
     * obtain method per dependency (OR-style). The GOAP auction decides which
     * method is actually feasible. Once the item is in inventory, all subgoals
     * for it are skipped.
     */
    private void decomposeItem(String itemId, String parentGoalId, float parentPriority,
                                WorldState state, List<GoalSet.Goal> derived, Set<String> seen) {

        // Resolve deps with state awareness: for each craft slot with alternatives
        // (e.g., [oak_planks, spruce_planks, ...]), picks the one we already have
        // OR can derive from owned items (e.g., have oak_wood → oak_planks is derivable).
        // This naturally prunes the tree — no need for tag group hacks.
        List<String> deps = ItemRecipeRegistry.getResolvedDependencies(itemId,
                fullId -> state.hasItem(fullId, 1) || canDeriveFromOwned(fullId, state));

        // Compute per-dep recipe counts so we skip only when we have ENOUGH
        Map<String, Integer> depCounts = computeDepCounts(itemId, state);

        for (String dep : deps) {
            String fullDep = dep.contains(":") ? dep : "minecraft:" + dep;
            String cleanDep = stripNamespace(fullDep);

            // Skip if we have enough for the recipe (not just 1)
            int needed = depCounts.getOrDefault(cleanDep, 1);
            if (state.hasItem(fullDep, needed)) {
                EmmaBridgeMod.LOGGER.debug("[GoalDecomposer] Skipping dep {} — have enough ({} needed, inv={}, endinv={}, storage={})",
                        cleanDep, needed,
                        state.playerInventory.getOrDefault(fullDep, 0),
                        state.endinvInventory.getOrDefault(fullDep, 0),
                        state.knownStorage.getOrDefault(fullDep, 0));
                continue;
            }

            List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(cleanDep);
            if (entries.isEmpty()) continue;

            // Check if this item requires a different dimension — inject travel goals
            String reqDim = getRequiredDimension(cleanDep);
            if (reqDim != null) {
                injectDimensionTravel(reqDim, state.dimension,
                        parentPriority - PRIORITY_OFFSET, parentGoalId, derived, seen, state);
            }

            // Count distinct methods to decide if goal ID needs method suffix
            long distinctMethods = entries.stream()
                    .map(ItemRecipeEntry::getObtainMethod).distinct().count();

            // Create subgoals for EACH unique obtain method
            Set<ObtainMethod> methodsSeen = EnumSet.noneOf(ObtainMethod.class);
            for (ItemRecipeEntry entry : entries) {
                ObtainMethod method = entry.getObtainMethod();
                if (method.isMetadataOnly()) continue;  // skip ITEM_PROPERTIES, etc.
                if (methodsSeen.contains(method)) continue;
                methodsSeen.add(method);

                String goalId = "derived_" + cleanDep;
                if (distinctMethods > 1) {
                    goalId += "_" + method.name().toLowerCase();
                }
                if (seen.contains(goalId)) continue;
                seen.add(goalId);

                float subPriority = parentPriority - PRIORITY_OFFSET;
                subPriority += computeFeasibilityBoost(state, entry, method);

                // Build target JSON and create subgoal
                JsonObject target = new JsonObject();
                target.addProperty("item", fullDep);
                target.addProperty("count", 1);
                target.addProperty("obtain_method", method.name());

                String subType = buildSubgoalTarget(target, entry, method, state,
                        subPriority, parentGoalId, derived, seen);

                derived.add(new GoalSet.Goal(goalId, subType, subPriority, target, parentGoalId));
            }
        }
    }

    /**
     * Compute per-ingredient required counts from the item's craft recipes.
     * Resolves slot alternatives the same way as getResolvedDepsForEntry:
     * picks the first alternative the player has, otherwise uses slotAlts[0].
     * Returns a map from bare item ID → count needed in the recipe.
     */
    private Map<String, Integer> computeDepCounts(String itemId, WorldState state) {
        Map<String, Integer> counts = new HashMap<>();
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(itemId);
        for (ItemRecipeEntry entry : entries) {
            if (!entry.getObtainMethod().isCraftType()) continue;
            String[][] grid = entry.getCraftGrid();
            if (grid == null) continue;

            Map<String, Integer> gridCounts = new HashMap<>();
            for (String[] slotAlts : grid) {
                if (slotAlts == null || slotAlts.length == 0) continue;
                String best = null;
                for (String alt : slotAlts) {
                    String full = alt.contains(":") ? alt : "minecraft:" + alt;
                    if (state.hasItem(full, 1) || canDeriveFromOwned(full, state)) {
                        best = alt;
                        break;
                    }
                }
                if (best == null) best = slotAlts[0];
                String cleanBest = stripNamespace(best.contains(":") ? best : "minecraft:" + best);
                gridCounts.merge(cleanBest, 1, Integer::sum);
            }

            for (var e : gridCounts.entrySet()) {
                counts.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        return counts;
    }

    /**
     * Check if an item can be derived (one craft step) from items we already own.
     * Used to collapse tag-equivalent alternatives: e.g., if we have oak_wood,
     * oak_planks is "derivable" → the planks slot collapses to just oak_planks
     * instead of keeping all 12 wood variants.
     *
     * Only checks one level deep to avoid performance issues.
     */
    private boolean canDeriveFromOwned(String fullId, WorldState state) {
        String id = fullId.contains(":") ? fullId.split(":")[1] : fullId;
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);

        for (ItemRecipeEntry entry : entries) {
            if (!entry.getObtainMethod().isCraftType()) continue;

            String[][] grid = entry.getCraftGrid();
            if (grid == null) continue;

            boolean hasAll = true;
            for (String[] slotAlts : grid) {
                if (slotAlts == null || slotAlts.length == 0) continue;
                boolean hasAny = false;
                for (String alt : slotAlts) {
                    String full = alt.contains(":") ? alt : "minecraft:" + alt;
                    if (state.hasItem(full, 1)) { hasAny = true; break; }
                }
                if (!hasAny) { hasAll = false; break; }
            }
            if (hasAll) return true;
        }
        return false;
    }

    /**
     * Compute a priority boost based on how feasible this obtain method is
     * given the current world state. Higher boost = more immediately actionable.
     */
    private float computeFeasibilityBoost(WorldState state, ItemRecipeEntry entry, ObtainMethod method) {
        float boost = 0f;
        switch (method) {
            case MINE -> {
                // No boost if entry requires a different dimension
                if (entry.getDimension() != null &&
                        !ItemRecipeRegistry.isDimensionMatch(entry.getDimension(), state.dimension)) {
                    boost = -0.2f;
                    break;
                }
                // Boost if mineable blocks are nearby
                if (entry.getMineBlockNames() != null) {
                    for (String block : entry.getMineBlockNames()) {
                        String fullBlock = block.contains(":") ? block : "minecraft:" + block;
                        List<BlockPos> nearby = state.nearbyBlocks.get(fullBlock);
                        if (nearby != null && !nearby.isEmpty()) {
                            boost = 0.3f;
                            break;
                        }
                    }
                }
            }
            case CRAFT_SHAPED_3x3, CRAFT_SHAPED_2x2, CRAFT_SHAPELESS -> {
                // Boost if all ingredients already in inventory (any alternative per slot)
                if (entry.getCraftGrid() != null) {
                    boolean hasAll = true;
                    for (String[] slotAlts : entry.getCraftGrid()) {
                        if (slotAlts != null && slotAlts.length > 0) {
                            boolean hasAny = false;
                            for (String alt : slotAlts) {
                                String full = alt.contains(":") ? alt : "minecraft:" + alt;
                                if (state.hasItem(full, 1)) { hasAny = true; break; }
                            }
                            if (!hasAny) { hasAll = false; break; }
                        }
                    }
                    if (hasAll) boost = 0.4f;
                }
            }
            case SMELT, SMELT_FURNACE, SMELT_BLAST, SMELT_SMOKER, SMELT_CAMPFIRE -> {
                // Boost if any smelt input is in inventory AND furnace is accessible
                if (entry.getSmeltFrom() != null) {
                    for (String input : entry.getSmeltFrom()) {
                        String full = input.contains(":") ? input : "minecraft:" + input;
                        if (state.hasItem(full, 1) && hasContainerAccess(state, "furnace")) {
                            boost = 0.2f;
                            break;
                        }
                    }
                }
            }
            case STONECUTTER -> {
                // smeltFrom holds stonecutter input alternatives for STONECUTTER entries
                if (entry.getSmeltFrom() != null) {
                    for (String input : entry.getSmeltFrom()) {
                        String full = input.contains(":") ? input : "minecraft:" + input;
                        if (state.hasItem(full, 1) && hasContainerAccess(state, "stonecutter")) {
                            boost = 0.1f;
                            break;
                        }
                    }
                }
            }
            case MOB_DROP -> {
                if (entry.getDimension() != null &&
                        !ItemRecipeRegistry.isDimensionMatch(entry.getDimension(), state.dimension)) {
                    boost = -0.2f;
                }
            }
            default -> {}
        }
        return boost;
    }

    /**
     * Build the subgoal target JSON and return the goal type string.
     * Handles method-specific target properties (mob class, mine block, etc.)
     * and creates prerequisite subgoals (tool prereqs, container prereqs).
     */
    private String buildSubgoalTarget(JsonObject target, ItemRecipeEntry entry, ObtainMethod method,
                                       WorldState state, float subPriority, String parentGoalId,
                                       List<GoalSet.Goal> derived, Set<String> seen) {
        String subType;

        switch (method) {
            case MOB_DROP -> {
                subType = "hunt_mob";
                if (entry.getMobClass() != null) {
                    target.addProperty("mob_class", entry.getMobClass());
                }
            }
            case CROP -> {
                subType = "harvest_crop";
                if (entry.getCropBlocks() != null && entry.getCropBlocks().length > 0) {
                    target.addProperty("crop_block", entry.getCropBlocks()[0]);
                }
            }
            case MINE -> {
                subType = "have_item";
                if (entry.getMineBlockNames() != null && entry.getMineBlockNames().length > 0) {
                    target.addProperty("mine_block", entry.getMineBlockNames()[0]);
                }

                // Tool prerequisite: use per-block data when available, fall back to top-level
                String req = null;
                String toolItem = null;

                if (entry.getMineBlocks() != null && entry.getMineBlocks().length > 0) {
                    // Use the first (preferred) block's per-block requirement + tool type
                    ItemRecipeEntry.MineBlockInfo blockInfo = entry.getMineBlocks()[0];
                    req = blockInfo.getRequirement();
                    if (req != null && !req.equals("HAND")) {
                        toolItem = getToolForRequirement(req, blockInfo.getToolType());
                    }
                } else {
                    // Fallback for old data format without MineBlockInfo
                    req = entry.getMiningRequirement();
                    if (req != null && !req.equals("HAND")) {
                        toolItem = TOOL_FOR_REQUIREMENT.get(req);
                    }
                }

                if (toolItem != null) {
                    String fullTool = "minecraft:" + toolItem;
                    if (!state.hasItem(fullTool, 1) && !seen.contains("tool_" + toolItem)) {
                        seen.add("tool_" + toolItem);

                        // Create per-method goals for the tool (with obtain_method)
                        // so CraftItemAction can discover and craft it
                        List<ItemRecipeEntry> toolEntries = ItemRecipeRegistry.getEntries(toolItem);
                        long distinctToolMethods = toolEntries.stream()
                                .map(ItemRecipeEntry::getObtainMethod)
                                .filter(m -> !m.isMetadataOnly()).distinct().count();

                        Set<ObtainMethod> toolMethodsSeen = EnumSet.noneOf(ObtainMethod.class);
                        for (ItemRecipeEntry toolEntry : toolEntries) {
                            ObtainMethod m = toolEntry.getObtainMethod();
                            if (m.isMetadataOnly() || toolMethodsSeen.contains(m)) continue;
                            toolMethodsSeen.add(m);

                            String toolGoalId = "derived_" + toolItem;
                            if (distinctToolMethods > 1) {
                                toolGoalId += "_" + m.name().toLowerCase();
                            }
                            if (seen.contains(toolGoalId)) continue;
                            seen.add(toolGoalId);

                            float toolPriority = subPriority
                                    + computeFeasibilityBoost(state, toolEntry, m);

                            JsonObject toolTarget = new JsonObject();
                            toolTarget.addProperty("item", fullTool);
                            toolTarget.addProperty("count", 1);
                            toolTarget.addProperty("obtain_method", m.name());
                            toolTarget.addProperty("is_tool_prereq", true);

                            String toolSubType = buildSubgoalTarget(toolTarget, toolEntry, m,
                                    state, toolPriority, parentGoalId, derived, seen);

                            derived.add(new GoalSet.Goal(toolGoalId, toolSubType,
                                    toolPriority, toolTarget, parentGoalId));
                        }

                        decomposeItem(toolItem, parentGoalId, subPriority, state, derived, seen);
                    }
                }
            }
            case INTERACT -> {
                if (entry.getInteractEntity() != null) {
                    subType = "interact_entity";
                    target.addProperty("interact_entity", entry.getInteractEntity());
                    target.addProperty("interact_tool", entry.getInteractTool());
                } else if (entry.getInteractBlock() != null) {
                    subType = "interact_block";
                    target.addProperty("interact_block", entry.getInteractBlock());
                    target.addProperty("interact_tool", entry.getInteractTool());
                } else {
                    subType = "have_item";
                }
            }
            case TRANSFORM -> {
                subType = "transform_block";
                if (entry.getTransformInput() != null) {
                    target.addProperty("transform_input", entry.getTransformInput());
                }
                if (entry.getTransformTool() != null) {
                    target.addProperty("transform_tool", entry.getTransformTool());
                }
                target.addProperty("transform_type", entry.getTransformType());
            }
            default -> subType = "have_item";
        }

        // General container prerequisite: SMELT→furnace, CRAFT_3x3→crafting_table, etc.
        String container = CONTAINER_FOR_METHOD.get(method);
        if (container != null && !hasContainerAccess(state, container) && !seen.contains(container)) {
            seen.add(container);
            JsonObject containerTarget = new JsonObject();
            containerTarget.addProperty("item", "minecraft:" + container);
            containerTarget.addProperty("count", 1);
            containerTarget.addProperty("is_container_prereq", true);

            derived.add(new GoalSet.Goal(
                    "derived_container_" + container,
                    "have_item",
                    subPriority,
                    containerTarget,
                    parentGoalId
            ));

            // Recursively decompose the container (e.g., furnace = 8 cobblestone)
            decomposeItem(container, parentGoalId, subPriority, state, derived, seen);
        }

        return subType;
    }

    /**
     * Check if a container/workstation is accessible (in inventory or nearby in world).
     * GoapTicker always scans for furnace, blast_furnace, smoker, crafting_table.
     */
    private boolean hasContainerAccess(WorldState state, String container) {
        String fullId = "minecraft:" + container;
        if (state.hasItem(fullId, 1)) return true;
        List<BlockPos> nearby = state.nearbyBlocks.get(fullId);
        return nearby != null && !nearby.isEmpty();
    }

    // ── Static utility methods (used by other actions) ──────────────────

    /**
     * Compute the chain depth for an item — how many transitive deps it has.
     * Used by PickupItemAction for shortcut bonus calculation.
     */
    public static int getChainDepth(String itemId) {
        String cleanId = stripNamespace(itemId);
        return ItemRecipeRegistry.getTransitiveDependencies(cleanId).size();
    }

    /**
     * Compute how many subgoals would be eliminated by obtaining this item.
     * Used by PickupItemAction for shortcut bonus.
     *
     * @param itemId    Item being picked up
     * @param goalItem  Top-level goal item
     * @return steps saved (0 if item is not in the chain)
     */
    public static int computeStepsSaved(String itemId, String goalItem) {
        String cleanGoal = stripNamespace(goalItem);
        String cleanItem = stripNamespace(itemId);

        List<String> fullChain = ItemRecipeRegistry.getTransitiveDependencies(cleanGoal);
        fullChain.add(cleanGoal);

        if (!fullChain.contains(cleanItem)) return 0;

        // Steps saved = item's own deps that are also in the chain + 1 (itself)
        List<String> itemDeps = ItemRecipeRegistry.getTransitiveDependencies(cleanItem);
        int saved = 1; // the item itself
        for (String dep : itemDeps) {
            if (fullChain.contains(dep)) saved++;
        }
        return saved;
    }

    // ── Composite goal decomposition ──────────────────────────────

    /**
     * Decompose a composite goal (e.g., kill_dragon) into have_item subgoals,
     * then recursively decompose those via the existing item dependency chain.
     *
     * Priority spacing: each prereq gets basePriority - PRIORITY_OFFSET - (index * 0.1),
     * so earlier prereqs in the list are prioritized. The existing feasibility boost
     * system then reorders dynamically based on what's actually achievable.
     */
    private void decomposeComposite(GoalSet.Goal compositeGoal,
                                     List<CompositePrereq> prereqs,
                                     WorldState state,
                                     List<GoalSet.Goal> derived,
                                     Set<String> seen) {
        float basePriority = compositeGoal.priority;

        for (int i = 0; i < prereqs.size(); i++) {
            CompositePrereq prereq = prereqs.get(i);
            String fullItem = "minecraft:" + prereq.item();
            String goalId = "composite_" + prereq.item() + "_" + prereq.count();

            if (seen.contains(goalId)) continue;
            seen.add(goalId);

            // Skip if already satisfied (or better equipped)
            if (state.isGoalItemSatisfied(fullItem, prereq.count())) continue;

            // Sub-priority descends from parent, spaced by index
            float subPriority = basePriority - PRIORITY_OFFSET - (i * 0.1f);
            subPriority += dimensionBoost(prereq.item(), state.dimension);

            JsonObject target = new JsonObject();
            target.addProperty("item", fullItem);
            target.addProperty("count", prereq.count());

            derived.add(new GoalSet.Goal(goalId, "have_item", subPriority, target, compositeGoal.id));

            // Recursively decompose via existing item chain
            decomposeItem(prereq.item(), compositeGoal.id, subPriority, state, derived, seen);
        }

        // Emit pipeline stage goals (portal building, dimension travel, dragon fight, etc.)
        List<PipelineStage> pipeline = "kill_dragon".equals(compositeGoal.type) ? KILL_DRAGON_PIPELINE : List.of();
        for (PipelineStage stage : pipeline) {
            if (seen.contains(stage.id())) continue;

            // Gate: skip stages that are already satisfied
            if (isPipelineStageSatisfied(stage, state)) continue;

            seen.add(stage.id());
            derived.add(new GoalSet.Goal(stage.id(), stage.type(), stage.priority(),
                    stage.target(), compositeGoal.id));
        }
    }

    // ── Build structure decomposition ──────────────────────────────

    /**
     * Decompose a build_structure goal into have_item subgoals for each material.
     * Materials are stored in the goal target as {"materials": {"minecraft:oak_planks": 64, ...}}.
     * Each material is recursively decomposed via decomposeItem for tool/container prereqs.
     */
    private void decomposeBuildStructure(GoalSet.Goal goal, WorldState state,
                                          List<GoalSet.Goal> derived, Set<String> seen) {
        if (goal.target == null || !goal.target.has("materials")) return;

        JsonObject materials = goal.target.getAsJsonObject("materials");
        float basePriority = goal.priority;
        int i = 0;

        for (var entry : materials.entrySet()) {
            String fullItem = entry.getKey();
            int count = entry.getValue().getAsInt();
            String cleanItem = stripNamespace(fullItem);
            String goalId = "build_mat_" + cleanItem;

            if (seen.contains(goalId)) continue;
            if (state.isGoalItemSatisfied(fullItem, count)) continue;

            seen.add(goalId);
            float subPriority = basePriority - PRIORITY_OFFSET - (i * 0.05f);

            JsonObject target = new JsonObject();
            target.addProperty("item", fullItem);
            target.addProperty("count", count);
            derived.add(new GoalSet.Goal(goalId, "have_item", subPriority, target, goal.id));

            // Recursively decompose item dependencies (tools, furnaces, etc.)
            decomposeItem(cleanItem, goal.id, subPriority, state, derived, seen);
            i++;
        }

        EmmaBridgeMod.LOGGER.info("[GoalDecomposer] Build structure '{}' → {} material subgoals",
                goal.id, i);
    }

    /**
     * Check if a pipeline stage goal is already satisfied by current world state.
     */
    private boolean isPipelineStageSatisfied(PipelineStage stage, WorldState state) {
        return switch (stage.id()) {
            case "build_nether_portal" -> state.hasNetherPortal;
            case "enter_nether" -> state.dimension.contains("the_nether");
            case "return_overworld" -> state.dimension.contains("overworld")
                    && state.hasItem("minecraft:ender_eye", 12);
            case "locate_stronghold" -> state.strongholdKnown;
            case "activate_end_portal" -> state.hasEndPortal;
            case "enter_end" -> state.dimension.contains("the_end");
            case "destroy_crystals" -> state.dimension.contains("the_end") && state.endCrystalCount == 0;
            case "kill_dragon_fight" -> !state.dragonAlive && state.dimension.contains("the_end");
            default -> false;
        };
    }

    /**
     * Dimension-aware priority boost for composite prereqs.
     * Boosts items relevant to the current dimension so the bot focuses
     * on what's available right now.
     */
    private static float dimensionBoost(String item, String dimension) {
        if (dimension.contains("nether")) {
            // In the nether: boost blaze/pearl acquisition
            if (item.contains("blaze") || item.contains("ender_pearl") || item.contains("ender_eye")) {
                return 3.0f;
            }
        } else if (dimension.contains("the_end")) {
            // In the end: beds and food are critical
            if (item.contains("bed")) return 5.0f;
            if (item.contains("cooked")) return 2.0f;
        }
        return 0f;
    }

    // ── Dimension-gated material support ─────────────────────────

    /**
     * Determine if an item requires a specific dimension to obtain.
     * Returns "NETHER" or "END" only if ALL entries for this item are gated
     * to that single dimension. Returns null if any entry is dimension-agnostic
     * (meaning the item can be obtained without travel, e.g. via crafting).
     */
    private static String getRequiredDimension(String itemId) {
        List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(itemId);
        if (entries.isEmpty()) return null;

        String requiredDim = null;
        for (ItemRecipeEntry entry : entries) {
            String dim = entry.getDimension();
            if (dim == null) return null;  // at least one path has no dimension gate
            if (requiredDim == null) {
                requiredDim = dim;
            } else if (!requiredDim.equals(dim)) {
                return null;  // entries span multiple dimensions
            }
        }
        return requiredDim;
    }

    /**
     * Inject dimension-travel prerequisite goals if the player is not in the
     * required dimension for an item. Reuses the same goal IDs as the
     * kill_dragon pipeline so the {@code seen} set deduplicates.
     */
    private void injectDimensionTravel(String requiredDim, String currentDimension,
                                        float itemPriority, String parentGoalId,
                                        List<GoalSet.Goal> derived, Set<String> seen,
                                        WorldState state) {
        if (ItemRecipeRegistry.isDimensionMatch(requiredDim, currentDimension)) return;

        switch (requiredDim) {
            case "NETHER" -> injectNetherTravel(itemPriority, parentGoalId, derived, seen, state);
            case "END"    -> injectEndTravel(itemPriority, parentGoalId, derived, seen, state);
        }
    }

    private void injectNetherTravel(float itemPriority, String parentGoalId,
                                     List<GoalSet.Goal> derived, Set<String> seen,
                                     WorldState state) {
        // Portal building (skip if portal already exists)
        if (!state.hasNetherPortal && !seen.contains("build_nether_portal")) {
            seen.add("build_nether_portal");
            derived.add(new GoalSet.Goal("build_nether_portal", "build_nether_portal",
                    itemPriority + 2.0f, new JsonObject(), parentGoalId));

            // Decompose portal materials: 10 obsidian + flint_and_steel
            decomposeItem("obsidian", parentGoalId, itemPriority + 1.5f, state, derived, seen);
            decomposeItem("flint_and_steel", parentGoalId, itemPriority + 1.5f, state, derived, seen);
        }

        // Enter nether
        if (!seen.contains("enter_nether")) {
            seen.add("enter_nether");
            JsonObject enterTarget = new JsonObject();
            enterTarget.addProperty("dimension", "minecraft:the_nether");
            derived.add(new GoalSet.Goal("enter_nether", "enter_dimension",
                    itemPriority + 1.0f, enterTarget, parentGoalId));
        }
    }

    private void injectEndTravel(float itemPriority, String parentGoalId,
                                  List<GoalSet.Goal> derived, Set<String> seen,
                                  WorldState state) {
        // Locate stronghold (skip if already known)
        if (!state.strongholdKnown && !seen.contains("locate_stronghold")) {
            seen.add("locate_stronghold");
            derived.add(new GoalSet.Goal("locate_stronghold", "locate_stronghold",
                    itemPriority + 3.0f, new JsonObject(), parentGoalId));
        }

        // Activate end portal (skip if already active)
        if (!state.hasEndPortal && !seen.contains("activate_end_portal")) {
            seen.add("activate_end_portal");
            derived.add(new GoalSet.Goal("activate_end_portal", "activate_end_portal",
                    itemPriority + 2.5f, new JsonObject(), parentGoalId));

            // End portal needs ender_eyes
            decomposeItem("ender_eye", parentGoalId, itemPriority + 2.0f, state, derived, seen);
        }

        // Enter the End
        if (!seen.contains("enter_end")) {
            seen.add("enter_end");
            JsonObject enterTarget = new JsonObject();
            enterTarget.addProperty("dimension", "minecraft:the_end");
            derived.add(new GoalSet.Goal("enter_end", "enter_dimension",
                    itemPriority + 1.0f, enterTarget, parentGoalId));
        }
    }

    private static String stripNamespace(String id) {
        return id.contains(":") ? id.split(":")[1] : id;
    }
}
