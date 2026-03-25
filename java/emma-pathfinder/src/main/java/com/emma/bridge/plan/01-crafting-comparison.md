# Plan 1: AltoClef Crafting vs GOAP Crafting — Code Evaluation & Port Strategy

## Executive Summary

The old AltoClef refactor uses **hardcoded recipes with explicit task composition** (collect → craft → recover). The new GOAP uses **JSON-driven recipes with flat goal scoring**. The new approach is better for maintenance (zero hardcoding), but lost critical crafting logic during the port: recipe validation, transitive dependency walking, and the two-phase collect-then-craft pipeline.

This document evaluates every material difference in the actual code, identifies what was lost, and proposes what to port back.

**Note: this plan covers crafting table (3x3) and inventory (2x2) crafting only.** Smelting, brewing, stonecutting, and smithing are covered in [Plan 01a](01a-production-actions.md).

---

## 0. API Version Context

AltoClef was written for **MC 1.21.8** using **Yarn mappings** (obfuscated → deobfuscated). Our bridge is written for **MC 26.1** using **direct unobfuscated Mojang names** with Fabric Loader only — no Yarn, no intermediary. This is why API calls look different between the two codebases even when doing the same thing:

- AltoClef: `mod.getPlayer().currentScreenHandler.syncId` (Yarn)
- Bridge: `player.containerMenu.containerId` (Mojang)
- AltoClef: `MinecraftClient.getInstance()` (Yarn)
- Bridge: `Minecraft.getInstance()` (Mojang)

The logic and algorithms are portable. The API surface names are not — every ported method needs its identifiers translated to Mojang names. MC 26.1 is the first fully unobfuscated release, so Yarn was discontinued and all mods use `loom.officialMojangMappings()` (identity mapping on 26.1).

---

## 1. Recipe Definition: Hardcoded vs Data-Driven

### AltoClef (`altoclef/TaskCatalogue.java`)

Recipes are Java method calls registered in a static HashMap:

```java
// TaskCatalogue.java — every recipe is a method call
shapedRecipe2x2("crafting_table", Items.CRAFTING_TABLE, 1, p, p, p, p);
tools("wooden", Items.OAK_PLANKS, ...);
armor("iron", Items.IRON_INGOT, ...);
simple("stick", Items.STICK, CollectSticksTask::new);
smelt("iron_ingot", Items.IRON_INGOT, "raw_iron");
```

Each helper (`shapedRecipe2x2`, `shapedRecipe3x3`, `tools`, `armor`, `smelt`) constructs a `CraftingRecipe` object with `ItemTarget[]` slots and registers it in the catalogue. String aliases like `p = "planks"` resolve to `Item[]` arrays via `ItemHelper.getItemMatches(name)`.

**Problem:** Every MC version update requires manually editing 600+ lines of Java. Adding a new item means editing source and recompiling.

### GOAP (`catalogue/ItemRecipeRegistry.java` + `item_recipes.json`)

Recipes are 3,121 JSON entries loaded at startup:

```json
{
  "itemId": "crafting_table",
  "recipeId": "minecraft:crafting_table",
  "obtainMethod": "CRAFT_SHAPED_3x3",
  "craftGrid": [["planks"],["planks"],["planks"],["planks"],null,null,null,null,null],
  "craftYield": 1,
  "shapedWidth": 2,
  "shapedHeight": 2
}
```

Each grid slot is a `String[]` of valid alternatives (e.g., `["oak_planks","birch_planks","spruce_planks",...]`). The JSON is regenerated per MC version via `/emma_extract` on the server — zero manual editing.

**Verdict:** GOAP approach is strictly superior for maintenance. Do NOT port the hardcoded catalogue. Keep JSON.

---

## 2. Recipe Resolution at Craft Time

### AltoClef: Two-Layer Lookup

**Layer 1 — `CraftingRecipeTracker.java`:** At world join, iterates `RecipeManager.values()`, converts each MC `CraftingRecipe` to an internal `adris.altoclef.util.CraftingRecipe` object, and indexes them by output `Item`. Has a known bug at line 162: only takes the FIRST item from each ingredient's alternatives array, so tag-based ingredients (like "any planks") lose all but one variant.

**Layer 2 — `JankCraftingRecipeMapping.java`:** At craft time, maps the internal recipe back to a `NetworkRecipeId` by scanning the `ClientRecipeBook`. Filters for `ShapedCraftingRecipeDisplay` and `ShapelessCraftingRecipeDisplay` only. If lookup fails, falls back to manual grid placement.

**Decision flow:**
```
1. Look up recipe book ID → if found, use CraftGenericWithRecipeBooksTask
2. If not found, fall back to CraftGenericManuallyTask (slot-by-slot placement)
```

The recipe book lookup happens FIRST, before any ingredient checking. This is an eager fail-fast pattern.

### GOAP: Single-Layer Lookup

**`CraftItemAction.resolveRecipe()`** (lines 222-265): For each `ItemRecipeEntry` from the JSON registry, iterates the craft grid slots, picks the first alternative the player has in inventory (falls back to `slotAlts[0]`), sums up needed ingredients, checks inventory has all of them, and THEN looks up the recipe book via `RecipeBookLookup.findFirstCraftingRecipe()`.

**Problem:** Recipe book lookup is lazy — happens AFTER the full ingredient check. If the recipe isn't in the book (not yet unlocked), all the ingredient checking was wasted. The AltoClef approach of checking the book FIRST is more efficient.

**Second problem:** Falls back to `slotAlts[0]` when no alternative is in inventory. This can pick an item the player can't obtain at all, wasting the entire craft attempt.

### What to Port

Port the **eager recipe book check** pattern from AltoClef. In `resolveRecipe()`, look up `RecipeBookLookup.findFirstCraftingRecipe(recipeId)` BEFORE iterating ingredients. If the recipe isn't in the book, skip to the next entry immediately.

Do NOT port the `CraftingRecipeTracker` or `JankCraftingRecipeMapping` — `RecipeBookLookup` already does the same job with less code.

---

## 3. Transitive Dependency Walking ("Can I Craft This?")

### AltoClef: `CraftingHelper.canCraftItemNow()` (lines 35-92)

Performs a **recursive inventory simulation**:

```
canCraftItemNow(recipe, inventorySnapshot, alreadyChecked):
  for each slot in recipe:
    target = slot.getItemTarget()
    if target is empty: continue

    found = false
    for each stack in inventorySnapshot:
      if target.matches(stack.item) and stack.count >= 1:
        deduct stack from inventorySnapshot
        found = true
        break

    if not found:
      // Recurse: can we CRAFT this ingredient?
      for each sub-recipe that produces target:
        if sub-recipe not in alreadyChecked:
          alreadyChecked.add(sub-recipe)
          if canCraftItemNow(sub-recipe, inventorySnapshot, alreadyChecked):
            found = true
            break

    if not found: return false

  return true
```

Key properties:
- **Inventory deduction is simulated** — it clones the inventory and removes items as they're "used", preventing double-counting
- **Cycle detection** via `alreadyChecked` HashSet — prevents infinite loops (e.g., if recipe A needs B and B needs A)
- **Stops at first viable path** — doesn't enumerate all possible alternatives
- Returns a boolean, not a plan — the caller decides what to do with it

### GOAP: `CraftItemAction.findCraftableInChain()` (lines 153-215)

Walks the GoalDecomposer's derived goals in priority order:

```
findCraftableInChain(state, goalItem, goals):
  derivedGoals = goals.getDerivedGoalsFor(goalItem)
  sort derivedGoals by priority ascending (most foundational first)

  for each derived goal (bottom-up):
    if derived.obtainMethod is CRAFT_*:
      recipe = registry.getRecipe(derived.itemId)
      if recipe != null and hasIngredients(state, recipe):
        return derived.itemId  // craft this one first

  return null  // nothing craftable right now
```

Key properties:
- **No inventory simulation** — checks `state.hasItem()` which reads live inventory, no deduction
- **No recursion** — walks a pre-computed flat list from GoalDecomposer
- **Double-counting bug** — if crafting sticks needs 2 planks and crafting a pickaxe also needs planks, both checks read the same inventory count without deduction. Can report "craftable" when there aren't actually enough planks for both.
- **Depends on GoalDecomposer** having already created the correct derived goals in the correct order

### What to Port

Port the **inventory simulation with deduction** from `CraftingHelper.canCraftItemNow()`. Adapt it to work with `ItemRecipeEntry` and `WorldState`:

```java
boolean canCraftWithCurrentInventory(WorldState state, ItemRecipeEntry recipe, Set<String> checked) {
    Map<String, Integer> simInventory = new HashMap<>(state.getInventorySnapshot());
    return simulateCraft(simInventory, recipe, checked);
}

boolean simulateCraft(Map<String, Integer> inv, ItemRecipeEntry recipe, Set<String> checked) {
    for (String[] slotAlts : recipe.getCraftGrid()) {
        if (slotAlts == null) continue;
        boolean filled = false;
        for (String alt : slotAlts) {
            String full = qualify(alt);
            if (inv.getOrDefault(full, 0) >= 1) {
                inv.merge(full, -1, Integer::sum);  // deduct
                filled = true;
                break;
            }
        }
        if (!filled) {
            // Recursive: can we craft any alternative?
            for (String alt : slotAlts) {
                if (checked.contains(alt)) continue;
                checked.add(alt);
                List<ItemRecipeEntry> subRecipes = registry.getCraftRecipes(alt);
                for (ItemRecipeEntry sub : subRecipes) {
                    if (simulateCraft(inv, sub, checked)) {
                        // After recursive craft, the output should be in inv
                        inv.merge(qualify(alt), recipe.getCraftYield(), Integer::sum);
                        inv.merge(qualify(alt), -1, Integer::sum); // use one
                        filled = true;
                        break;
                    }
                }
                if (filled) break;
            }
        }
        if (!filled) return false;
    }
    return true;
}
```

This gives the GOAP system the same power as AltoClef's crafting validation without adopting the hardcoded recipe catalogue.

---

## 4. Crafting Pipeline: Task Composition vs State Machine

### AltoClef Pipeline (`CraftInTableTask` → `DoCraftInTableTask`)

Explicit nested task composition:

```
CraftInTableTask.onTick():
  ├─ Phase 1: CollectRecipeCataloguedResourcesTask
  │   (for each ingredient, spawn a collect subtask)
  │   (subtask may recursively craft intermediates)
  │   → blocks until ALL materials in inventory
  │
  ├─ Phase 2: containerSubTask()
  │   ├─ Find/place crafting table
  │   ├─ Walk to table
  │   ├─ Open table screen
  │   └─ Delegate to CraftGenericWithRecipeBooksTask or CraftGenericManuallyTask
  │
  └─ Phase 3: Cleanup
      ├─ Close screen
      └─ Clear cursor slot
```

Each phase **blocks** until complete. Material collection is explicit and recursive. The crafting action never fires until all ingredients are confirmed in inventory.

### GOAP Pipeline (`CraftItemAction`)

State machine with 13 states:

```
IDLE → FIND_TABLE → EQUIP_TABLE → PLACE_TABLE → NAVIGATE → OPEN →
WAIT_SCREEN → PLACE_RECIPE → WAIT_RECIPE → EXTRACT → BREAK_TABLE →
COLLECT_TABLE → DONE
```

Material collection is **not part of CraftItemAction** — the GOAP system creates separate `MineBlockAction` and `SmeltItemAction` goals for ingredients via GoalDecomposer. CraftItemAction assumes ingredients are already present.

**Key difference:** AltoClef's crafting action is self-contained (collects + crafts + cleans up). GOAP's crafting action trusts the goal system to have gathered materials first. This creates a race condition: CraftItemAction might win the scoring auction and start executing before MineBlockAction has finished gathering ingredients.

### What to Port

Do NOT port the full task composition model — the GOAP state machine is cleaner and the separation of concerns (mine separately, craft separately) is architecturally sound. But port the **guard check**: CraftItemAction should return score 0 (or not activate) unless `simulateCraft()` confirms all ingredients are present. This prevents the race condition.

---

## 5. Screen Interaction: Manual Placement vs Recipe Book API

### AltoClef: `CraftGenericManuallyTask` (slot-by-slot)

```java
for (int craftSlot = 0; craftSlot < recipe.getSlotCount(); craftSlot++) {
    ItemTarget toFill = recipe.getSlot(craftSlot);
    ItemStack present = getSlotStack(craftSlot);

    if (toFill.isEmpty()) {
        if (present != AIR) removeFromSlot(craftSlot);
    } else {
        if (!toFill.matches(present) || present.count < needed) {
            return new MoveItemToSlotFromInventoryTask(toFill, craftSlot);
        }
    }
}
// All slots filled → extract output
return new ReceiveCraftingOutputSlotTask();
```

Places items one at a time. Validates each slot. Handles oversatisfied slots (too many items in a slot). Very robust but slow.

Also has `CraftGenericWithRecipeBooksTask` which calls `clickRecipe(syncId, recipeId, true)` once per craft — faster but requires recipe book.

### GOAP: `handlePlaceRecipe()` (batch-aware)

```java
int craftsNeeded = ceil(stillNeeded / outputPerCraft);
int maxPerBatch = max(1, 64 / inputsPerCraft);
recipePlaceTarget = min(craftsNeeded, maxPerBatch);

// Rate-limited: 1 call per 2 ticks
client.gameMode.handlePlaceRecipe(handler.containerId, recipeDisplayId, false);
```

Uses Minecraft's native recipe placement API. The server fills the grid automatically from player inventory. Batch-aware: computes how many times to call the API based on stack limits. Rate-limited to avoid server spam.

**Verdict:** GOAP approach is strictly superior. Do NOT port manual slot placement. Keep recipe book API with batch logic.

### What to Port

Port the **manual placement fallback** as a safety net. If `RecipeBookLookup` fails to find a recipe (recipe not unlocked, or modded recipe), fall back to slot-by-slot placement using the AltoClef pattern. This is a robustness improvement, not a replacement.

---

## 6. Batch Crafting

### AltoClef

No batch crafting. Calls `clickRecipe()` once per craft operation, loops externally.

### GOAP

Sophisticated batch system:
- Computes `craftsNeeded = ceil(deficit / yield)`
- Computes `maxPerBatch = max(1, 64 / slotsUsed)` to respect stack limits
- Calls `handlePlaceRecipe()` that many times, rate-limited to 1 call per 2 ticks
- Tracks `craftsRemaining` across batches
- Re-checks output slot for expected items before proceeding

**Verdict:** GOAP is strictly better. Do NOT port AltoClef's single-craft loop.

---

## 7. Tag/Ingredient Alternative Resolution

### AltoClef

`ItemTarget` holds `Item[]` matches resolved at construction time via `ItemHelper.getItemMatches(name)`. Matching is pre-computed. But `CraftingRecipeTracker` has the bug at line 162 where it only takes `items[0]` from each ingredient, discarding alternatives.

### GOAP

`ItemRecipeEntry.craftGrid` stores `String[][]` with all alternatives per slot. Resolution is dynamic at craft time in `resolveRecipe()`: iterates alternatives and picks the first one the player has. Falls back to `slotAlts[0]`.

**GOAP is better** — dynamic resolution adapts to what's actually in inventory. But the fallback to `slotAlts[0]` is wrong: it should skip the recipe entirely if no alternative is available, not blindly pick the first one.

### What to Port

Fix the fallback: if no alternative for a slot is in inventory AND no alternative can be crafted (via `simulateCraft()`), skip this recipe entry entirely. Don't fall back to `slotAlts[0]`.

---

## 8. Crafting Table Management

### AltoClef (`DoStuffInContainerTask`) — Superior Recovery Logic

`DoStuffInContainerTask` is a **shared base class** used by ALL container interactions (crafting table, furnace, brewing stand, smithing table). Its recovery pipeline is the cleanest part of the old code:

**Two-phase recovery after work is done:**
1. **BREAKING phase** (timeout: 20 seconds for hardness 3.5 blocks + buffer):
   - Checks inventory has space (tries overflow storage if full)
   - Enables block-breaking behavior
   - Delegates to `DestroyBlockTask(placedPos)`
   - Safety timeout prevents getting stuck on unbreakable blocks
2. **COLLECTING phase** (timeout: 3 ticks):
   - Block is gone, walks to drop position via `GetToBlockTask(placedPos)` to trigger auto-pickup
   - Resets once item collected or timeout expires

**Key properties:**
- `isRecoveryDone()` gate prevents the parent task from exiting before container is recovered
- Inventory-full handling tries overflow before abandoning recovery
- Shared across all container types — furnace, crafting table, brewing stand all use the same recovery path

### GOAP (`CraftItemAction` states FIND_TABLE through COLLECT_TABLE)

Explicit state machine:
- `FIND_TABLE`: Scans nearby blocks for `minecraft:crafting_table`
- `EQUIP_TABLE`: If none found, checks inventory for a table; if missing, triggers sub-craft
- `PLACE_TABLE`: Places table on an adjacent block, marks `placedTable=true`
- After crafting: `BREAK_TABLE` → `COLLECT_TABLE` (recovers the table)

Known issue: diagonal placement can be ~0.5 blocks too far for interaction range.

**GOAP has recovery but it's action-specific, not shared.** CraftItemAction has its own BREAK_TABLE → COLLECT_TABLE states. SmeltItemAction has **no recovery at all** — furnaces placed by the agent are abandoned in the world. Other container actions (stonecutter, smithing, brewing) don't exist yet.

### What to Port

Port `DoStuffInContainerTask`'s recovery logic as a **shared utility** (e.g., `ContainerRecovery` helper) that any container-using GOAP action can call. This gives us:
- Consistent recovery across all container types (craft, smelt, brew, stonecutter, smith)
- Inventory-full handling with overflow fallback
- Timeout safety on both breaking and collecting phases
- Single place to maintain recovery logic instead of duplicating per action

---

## 9. Summary: What to Port, What to Keep

### PORT from AltoClef into GOAP:

| Logic | Source File | Target | Why |
|-------|------------|--------|-----|
| Recursive inventory simulation | `CraftingHelper.canCraftItemNow()` | `CraftItemAction` | Prevents double-counting, validates craftability before attempting |
| Eager recipe book check | `CraftInTableTask.containerSubTask()` | `CraftItemAction.resolveRecipe()` | Fail-fast if recipe not in book |
| Manual placement fallback | `CraftGenericManuallyTask` | New utility class | Safety net for unregistered recipes |
| Ingredient-not-found → skip recipe | `CollectRecipeCataloguedResourcesTask` | `CraftItemAction.resolveRecipe()` | Don't fall back to slotAlts[0] |
| Shared container recovery (break + collect) | `DoStuffInContainerTask` | New `ContainerRecovery` utility | Consistent recovery across all container actions; fixes furnace abandonment |

### KEEP in GOAP (do not replace):

| Component | Why |
|-----------|-----|
| JSON recipe registry (`ItemRecipeRegistry`) | Zero-maintenance, auto-extracted per MC version |
| Recipe book API with batching | Faster and more reliable than manual slot placement |
| State machine pipeline | Cleaner than nested task composition for GOAP architecture |
| Crafting table find/place/navigate states | Explicit states, better than inherited container task |
| `RecipeBookLookup` | Simpler than `JankCraftingRecipeMapping` |

### DELETE from AltoClef consideration:

| Component | Why |
|-----------|-----|
| `TaskCatalogue` hardcoded recipes | Replaced by JSON — no maintenance burden |
| `CraftingRecipeTracker` MC→internal conversion | `ItemRecipeRegistry` handles this better |
| `ItemHelper.getItemMatches()` string→item resolution | GOAP uses string IDs throughout, no Item objects needed |
| `CollectRecipeCataloguedResourcesTask` | GOAP separates collection into MineBlock/SmeltItem actions |

---

## 10. Implementation Tasks

1. Port `canCraftItemNow()` inventory simulation → fixes double-counting and premature craft attempts
2. Make recipe book lookup eager in `resolveRecipe()` → eliminates wasted ingredient checks
3. Fix `slotAlts[0]` fallback → prevents impossible craft attempts
4. Add manual placement fallback
