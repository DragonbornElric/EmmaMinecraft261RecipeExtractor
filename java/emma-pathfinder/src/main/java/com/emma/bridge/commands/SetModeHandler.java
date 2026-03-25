package com.emma.bridge.commands;

import com.emma.bridge.catalogue.ItemRecipeRegistry;
import com.emma.bridge.goap.GoalSet;
import com.emma.bridge.goap.GoapTicker;
import com.emma.bridge.goap.UtilityScorer;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WebSocket command: set_mode
 *
 * Activates a named GOAP mode. All goal/tier/personality logic lives here —
 * Python sends a single command.
 *
 * Params:
 *   {"mode": "stop"}
 *   {"mode": "idle"}
 *   {"mode": "hero", "tier": "diamond"}
 *   {"mode": "gamer"}
 *
 * Supported modes:
 *   stop  — GOAP off, goals cleared
 *   idle  — GOAP on, survival goals only (building/item goals added separately)
 *   hero  — combat-focused hostile hunting with tiered equipment goals
 *   gamer — beat-the-game: kill_dragon composite goal + tech tree
 */
public class SetModeHandler implements ICommandHandler {

    // ── Mode tracking ───────────────────────────────────────────────

    public enum GoapMode { STOP, IDLE, HERO, GAMER }

    private static GoapMode currentMode = GoapMode.STOP;

    public static GoapMode getCurrentMode() { return currentMode; }

    // ── Personality presets ──────────────────────────────────────────

    private static final Map<String, Float> NEUTRAL_PERSONALITY = Map.of(
            "safety", 1.2f,
            "aggression", 0.8f,
            "exploration", 0.5f,
            "resource_hoarding", 0.8f
    );

    private static final Map<String, Float> HERO_PERSONALITY = Map.of(
            "safety", 0.5f,
            "aggression", 2.0f,
            "exploration", 1.5f,
            "resource_hoarding", 0.8f
    );

    private static final Map<String, Float> GAMER_OVERWORLD_PERSONALITY = Map.of(
            "safety", 1.0f,
            "aggression", 0.6f,
            "exploration", 1.2f,
            "resource_hoarding", 1.5f
    );

    // ── Constants ───────────────────────────────────────────────────

    private static final Set<String> VALID_TIERS = Set.of("iron", "diamond", "netherite");
    private static final String[] TOOL_TYPES = {"pickaxe", "sword", "axe", "shovel", "hoe"};
    private static final String[] ARMOR_TYPES = {"helmet", "chestplate", "leggings", "boots"};

    private final GoalSet goalSet;
    private final GoapTicker ticker;
    private final UtilityScorer scorer;

    public SetModeHandler(GoalSet goalSet, GoapTicker ticker, UtilityScorer scorer) {
        this.goalSet = goalSet;
        this.ticker = ticker;
        this.scorer = scorer;
    }

    @Override
    public String getCommand() {
        return "set_mode";
    }

    @Override
    public JsonObject execute(JsonObject params) {
        JsonObject result = new JsonObject();

        if (params == null || !params.has("mode")) {
            result.addProperty("status", "error");
            result.addProperty("error", "Missing 'mode' parameter");
            return result;
        }

        String mode = params.get("mode").getAsString().toLowerCase();

        return switch (mode) {
            case "stop"  -> executeStop(result);
            case "idle"  -> executeIdle(result);
            case "hero"  -> executeHero(params, result);
            case "gamer" -> executeGamer(result);
            default -> {
                result.addProperty("status", "error");
                result.addProperty("error", "Unknown mode: " + mode + ". Supported: stop, idle, hero, gamer");
                yield result;
            }
        };
    }

    // ── Stop mode ──────────────────────────────────────────────────

    private JsonObject executeStop(JsonObject result) {
        ticker.setEnabled(false);
        goalSet.setDynamicGoals(List.of());
        scorer.setPersonalityWeights(NEUTRAL_PERSONALITY);
        currentMode = GoapMode.STOP;

        result.addProperty("status", "ok");
        result.addProperty("mode", "stop");
        result.addProperty("goap_enabled", false);
        return result;
    }

    // ── Idle mode ──────────────────────────────────────────────────

    private JsonObject executeIdle(JsonObject result) {
        goalSet.setDynamicGoals(List.of());
        scorer.setPersonalityWeights(NEUTRAL_PERSONALITY);
        ticker.setEnabled(true);
        ticker.triggerDecomposition();
        currentMode = GoapMode.IDLE;

        result.addProperty("status", "ok");
        result.addProperty("mode", "idle");
        result.addProperty("goap_enabled", true);
        result.add("all_goals", goalSet.toJson());
        return result;
    }

    // ── Hero mode ──────────────────────────────────────────────────

    private JsonObject executeHero(JsonObject params, JsonObject result) {
        String tier = params.has("tier") ? params.get("tier").getAsString().toLowerCase() : "diamond";

        if (!VALID_TIERS.contains(tier)) {
            result.addProperty("status", "error");
            result.addProperty("error", "Invalid tier: " + tier + ". Use: iron, diamond, netherite");
            return result;
        }

        ticker.setEnabled(true);
        scorer.setPersonalityWeights(HERO_PERSONALITY);

        List<GoalSet.Goal> goals = buildHeroGoals(tier);
        goalSet.setDynamicGoals(goals);
        ticker.triggerDecomposition();
        currentMode = GoapMode.HERO;

        result.addProperty("status", "ok");
        result.addProperty("mode", "hero");
        result.addProperty("tier", tier);
        result.addProperty("goals_set", goals.size());
        result.addProperty("goap_enabled", true);
        result.add("all_goals", goalSet.toJson());
        return result;
    }

    // ── Gamer mode (beat-the-game / kill dragon) ───────────────────

    private JsonObject executeGamer(JsonObject result) {
        ticker.setEnabled(true);
        scorer.setPersonalityWeights(GAMER_OVERWORLD_PERSONALITY);

        // Single composite goal — GoalDecomposer handles the full tech tree
        List<GoalSet.Goal> goals = List.of(
                new GoalSet.Goal("kill_dragon", "kill_dragon", 15.0f, new JsonObject())
        );
        goalSet.setDynamicGoals(goals);
        ticker.triggerDecomposition();
        currentMode = GoapMode.GAMER;

        result.addProperty("status", "ok");
        result.addProperty("mode", "gamer");
        result.addProperty("goals_set", goals.size());
        result.addProperty("goap_enabled", true);
        result.add("all_goals", goalSet.toJson());
        return result;
    }

    // ── Hero goal builder ──────────────────────────────────────────

    private List<GoalSet.Goal> buildHeroGoals(String tier) {
        List<GoalSet.Goal> goals = new ArrayList<>();

        // Primary: hunt hostiles (drives HuntHostileAction patrol + AttackEntity)
        goals.add(new GoalSet.Goal("hunt_hostiles", "hunt_hostile", 10.0f, new JsonObject()));

        // ── Bootstrap tools (always included — need them to mine iron) ──

        // Wooden tools: 9.0 → 8.6
        addToolGoals(goals, "wooden", 9.0f);

        // Stone tools: 8.4 → 8.0
        addToolGoals(goals, "stone", 8.4f);

        // ── Iron tier (all tiers need iron as baseline) ──

        // Iron tools: 7.5 → 7.1
        addToolGoals(goals, "iron", 7.5f);

        // Shield: 7.0
        goals.add(itemGoal("hero_shield", "minecraft:shield", 1, 7.0f));

        // Iron armor: 6.4 → 6.1
        addArmorGoals(goals, "iron", 6.4f);

        // ── Food (always — dynamic from recipe registry) ──

        addFoodGoals(goals, 6.0f);

        // ── Diamond tier (diamond and netherite) ──

        if ("diamond".equals(tier) || "netherite".equals(tier)) {
            // Diamond tools: 5.4 → 5.0
            addToolGoals(goals, "diamond", 5.4f);

            // Diamond armor: 4.8 → 4.5
            addArmorGoals(goals, "diamond", 4.8f);
        }

        // ── Netherite tier ──

        if ("netherite".equals(tier)) {
            // Netherite tools: 4.4 → 4.0
            addToolGoals(goals, "netherite", 4.4f);

            // Netherite armor: 3.8 → 3.5
            addArmorGoals(goals, "netherite", 3.8f);
        }

        return goals;
    }

    /**
     * Add goals for all 5 tool types at a given material tier.
     * Priority decreases by 0.1 per tool: pickaxe, sword, axe, shovel, hoe.
     */
    private void addToolGoals(List<GoalSet.Goal> goals, String material, float basePriority) {
        for (int i = 0; i < TOOL_TYPES.length; i++) {
            String tool = TOOL_TYPES[i];
            String itemId = "minecraft:" + material + "_" + tool;
            String goalId = "hero_" + material + "_" + tool;
            goals.add(itemGoal(goalId, itemId, 1, basePriority - (i * 0.1f)));
        }
    }

    /**
     * Add goals for all 4 armor types at a given material tier.
     * Priority decreases by 0.1 per slot: helmet, chestplate, leggings, boots.
     */
    private void addArmorGoals(List<GoalSet.Goal> goals, String material, float basePriority) {
        for (int i = 0; i < ARMOR_TYPES.length; i++) {
            String slot = ARMOR_TYPES[i];
            String itemId = "minecraft:" + material + "_" + slot;
            String goalId = "hero_" + material + "_" + slot;
            goals.add(itemGoal(goalId, itemId, 1, basePriority - (i * 0.1f)));
        }
    }

    /**
     * Add food goals from ItemRecipeRegistry. Picks the top 2 foods by acquisition
     * efficiency with nutrition >= 4 (same ranking CollectFoodAction uses).
     */
    private void addFoodGoals(List<GoalSet.Goal> goals, float basePriority) {
        int added = 0;
        for (var candidate : ItemRecipeRegistry.getFoodCandidatesByEfficiency()) {
            if (candidate.nutrition() < 4) continue;
            String fullItem = "minecraft:" + candidate.itemId();
            String goalId = "hero_food_" + candidate.itemId();
            // First food: 16 count at base priority, second: 8 count at -0.5
            int count = added == 0 ? 16 : 8;
            float priority = basePriority - (added * 0.5f);
            goals.add(itemGoal(goalId, fullItem, count, priority));
            if (++added >= 2) break;
        }
    }

    /**
     * Build a single have_item goal.
     */
    private static GoalSet.Goal itemGoal(String id, String item, int count, float priority) {
        JsonObject target = new JsonObject();
        target.addProperty("item", item);
        target.addProperty("count", count);
        return new GoalSet.Goal(id, "have_item", priority, target);
    }
}
