# Bug Fix Plan: CraftItemAction Crafts Wrong Wood Variant

## The Bug

**Symptom:** GOAP goal is `spruce_planks`, player has only `spruce_logs` in inventory. Instead of crafting `spruce_planks`, it tries to craft `oak_wood` from `oak_logs` (which the player doesn't have). The `handlePlaceRecipe` call sends the oak_wood recipe, server can't fill the grid with oak_logs, craft fails silently. **The correct craft (spruce_planks) never executes.**

**Root Cause:** `hasItemOrTagEquivalent()` in `CraftItemAction.java` line 776. `findCraftableInChain()` iterates derived goals sorted by priority (line 170). It encounters `oak_wood` BEFORE `spruce_planks` in the list. When checking oak_wood's ingredients (needs `oak_log` × 4), `hasItemOrTagEquivalent` finds `spruce_log` via the `#logs` tag group and says "yes, we can craft this." So it returns `oak_wood` as the craftable item and **never reaches `spruce_planks` in the loop.** The recipe book API does NOT work this way — it sends a specific recipe to the server, and the server requires the exact items (oak_logs, not spruce_logs).

**Why previous sessions couldn't find it:** The scoring/ingredient-check code *looks* correct at a glance. The `slotAlts` loop (lines 229-239) already picks alternatives, so it seems like tag handling is covered. The bug is that `hasItemOrTagEquivalent` on line 243 *re-expands* to tag equivalents AFTER the alternative selection, making recipes pass the ingredient check when they shouldn't. This causes the loop to short-circuit on the wrong recipe before it ever evaluates the correct one (spruce_planks).

## File to Change

**One file only:** `java/emma-pathfinder/src/main/java/com/emma/bridge/goap/actions/CraftItemAction.java`

## The Fix (3 changes)

### Change 1: Replace `hasItemOrTagEquivalent` with exact match in ingredient check

**Location:** `resolveRecipe()` method, line 243

**Current code (line 241-247):**
```java
boolean hasAll = true;
for (var ingredientEntry : needed.entrySet()) {
    if (!hasItemOrTagEquivalent(state, ingredientEntry.getKey(), ingredientEntry.getValue())) {
        hasAll = false;
        break;
    }
}
```

**Replace with:**
```java
boolean hasAll = true;
for (var ingredientEntry : needed.entrySet()) {
    if (!state.hasItem(ingredientEntry.getKey(), ingredientEntry.getValue())) {
        hasAll = false;
        break;
    }
}
```

**Why:** The `slotAlts` loop above (lines 229-239) already resolves tag alternatives — it picks the specific variant the player actually has (e.g., `spruce_log` instead of `oak_log`). By the time we reach the `needed` map, keys are already concrete items like `minecraft:spruce_log`. Tag expansion here is redundant and wrong — it makes the check think `oak_log` is available when only `spruce_log` exists.

### Change 2: Match recipe book entry to the specific recipe variant

**Location:** `resolveRecipe()` method, lines 217-251

**Current code (line 217-218):**
```java
// Recipe book lookup happens BEFORE the ingredient loop
RecipeDisplayEntry bookEntry = RecipeBookLookup.findFirstCraftingRecipe(id);
if (bookEntry == null) return false;
```

**Move the recipe book lookup INSIDE the ingredient loop, after verifying ingredients match:**

```java
private boolean resolveRecipe(WorldState state, String itemId, boolean hasTableAccess) {
    String id = itemId.contains(":") ? itemId.split(":")[1] : itemId;
    List<ItemRecipeEntry> entries = ItemRecipeRegistry.getEntries(id);
    if (entries.isEmpty()) return false;

    for (ItemRecipeEntry entry : entries) {
        if (!entry.getObtainMethod().isCraftType()) continue;
        if (entry.getObtainMethod() != ObtainMethod.CRAFT_SHAPED_2x2 && !hasTableAccess) continue;

        String[][] grid = entry.getCraftGrid();
        if (grid == null) continue;

        // Check if we have all ingredients (resolve tag alternatives)
        Map<String, Integer> needed = new LinkedHashMap<>();
        for (String[] slotAlts : grid) {
            if (slotAlts == null || slotAlts.length == 0) continue;
            String best = slotAlts[0];
            for (String alt : slotAlts) {
                String full = alt.contains(":") ? alt : "minecraft:" + alt;
                if (state.hasItem(full, 1)) { best = alt; break; }
            }
            String fullBest = best.contains(":") ? best : "minecraft:" + best;
            needed.merge(fullBest, 1, Integer::sum);
        }

        boolean hasAll = true;
        for (var ingredientEntry : needed.entrySet()) {
            if (!state.hasItem(ingredientEntry.getKey(), ingredientEntry.getValue())) {
                hasAll = false;
                break;
            }
        }

        if (!hasAll) continue;

        // ONLY look up recipe book AFTER confirming ingredients match
        RecipeDisplayEntry bookEntry = RecipeBookLookup.findFirstCraftingRecipe(id);
        if (bookEntry == null) return false; // no recipe book entry at all — bail

        targetRecipe = entry;
        recipeDisplayId = bookEntry.id();
        return true;
    }
    return false;
}
```

**Why:** The original code grabs the first recipe book entry for the output item before checking any ingredients. If there are multiple ways to craft the same output, the recipe book entry might correspond to a different variant than the one we have ingredients for. Moving the lookup after ingredient verification ensures we only proceed when we know the ingredients match.

**Note:** `findFirstCraftingRecipe(id)` still returns by output item name, so for items with truly different recipes (not just wood variants), a future improvement would be to match the specific recipe. But for the current bug (wrong variant entirely), this ordering fix combined with Change 1 is sufficient — the ingredient check will reject oak_wood because the player doesn't have oak_logs.

### Change 3: Protect against `resolveRecipe` side effects in DONE phase

**Location:** DONE phase, lines 357-373

**Current code (lines 360-362):**
```java
boolean nextNeedsTable = resolveRecipe(cachedState, nextId, true)
        && targetRecipe != null
        && targetRecipe.getObtainMethod() != ObtainMethod.CRAFT_SHAPED_2x2;
```

**Replace with:**
```java
// Save current state before the side-effecting resolveRecipe call
ItemRecipeEntry prevRecipe = targetRecipe;
RecipeDisplayId prevDisplayId = recipeDisplayId;

boolean nextNeedsTable = resolveRecipe(cachedState, nextId, true)
        && targetRecipe != null
        && targetRecipe.getObtainMethod() != ObtainMethod.CRAFT_SHAPED_2x2;

if (!nextNeedsTable) {
    // resolveRecipe clobbered these — restore previous values
    // (doesn't matter much since we're going to BREAK_TABLE, but keeps state clean)
    targetRecipe = prevRecipe;
    recipeDisplayId = prevDisplayId;
}
```

**Why:** `resolveRecipe()` mutates `targetRecipe` and `recipeDisplayId` as side effects. When checking "is there a next craft in the chain?", if the answer is NO (or it's a 2x2 recipe), the fields have already been overwritten. This could cause the BREAK_TABLE phase to have stale/wrong recipe state.

## What NOT to Change

- **Do NOT remove `hasItemOrTagEquivalent` entirely** — it may be used correctly elsewhere or in future scoring. Just stop using it for ingredient verification in `resolveRecipe`.
- **Do NOT change `RecipeBookLookup`** — it's working correctly (returns recipes by output item). The problem is how CraftItemAction uses it.
- **Do NOT change the GOAP decomposer or GoalDecomposer** — it correctly generates wood variant goals. The bug is in the action's ingredient verification, not goal generation.
- **Do NOT change `TagGroups`** — tags are correct. Logs ARE in the same tag group. The issue is using tag groups for recipe ingredient verification where exact items matter.

## Why This Fixes the Bug

With all three changes applied, the flow for "need spruce_planks, have spruce_logs" becomes:

1. `findCraftableInChain` encounters `oak_wood` as a derived goal
2. `resolveRecipe("oak_wood")` checks ingredients:
   - `slotAlts` for oak_wood recipe = `["oak_log"]` (no alternatives)
   - `best` = `"oak_log"` (player doesn't have it, but it's the only option)
   - `needed` = `{"minecraft:oak_log": 4}`
   - **`state.hasItem("minecraft:oak_log", 4)` → FALSE** (exact match, no tag expansion)
   - `hasAll = false` → skips this recipe
3. `findCraftableInChain` continues, encounters `spruce_planks`
4. `resolveRecipe("spruce_planks")`:
   - `slotAlts` = `["spruce_log"]`
   - `needed` = `{"minecraft:spruce_log": 1}`
   - `state.hasItem("minecraft:spruce_log", 1)` → TRUE
   - Recipe book lookup: finds `spruce_planks` recipe → returns its `RecipeDisplayId`
   - Sets `targetRecipe` + `recipeDisplayId` for spruce_planks
5. GOAP crafts spruce_planks correctly

## Verification

1. **Build:** `cd java && ./build_and_deploy.sh --bridge`
2. **Test case 1:** Have only spruce_logs → goal spruce_planks → should craft spruce_planks (not oak_wood)
3. **Test case 2:** Have only birch_logs → goal birch_planks → should craft birch_planks
4. **Test case 3:** Have mixed logs (oak + spruce) → goal oak_stairs → should use oak_planks path
5. **Test case 4:** Sub-craft path: have spruce_logs + cobblestone, goal = stone_pickaxe → should craft spruce_planks → sticks → crafting_table → stone_pickaxe
6. **Test case 5:** No wood at all → should fail gracefully (not pick a random variant)
