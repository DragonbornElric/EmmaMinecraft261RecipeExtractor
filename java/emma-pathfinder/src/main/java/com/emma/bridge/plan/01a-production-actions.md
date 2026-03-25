# Plan 1a: Non-Crafting Production Actions — Smelting, Brewing, Stonecutting, Smithing

## Scope

Plan 01 covers crafting table (3x3) and inventory (2x2) crafting. This plan covers every other production method: smelting (furnace/blast/smoker/campfire), brewing, stonecutting, and smithing. For each, we evaluate what AltoClef had, what the GOAP has, and what's missing.

**API context:** AltoClef targets MC 1.21.8 with Yarn mappings. Our bridge targets MC 26.1 with direct Mojang names. All ported algorithms need identifier translation but the logic is portable. See Plan 01 §0 for details.

---

## 1. Production Method Coverage

| Method | Recipes in JSON | AltoClef Action | GOAP Action | Status |
|--------|----------------|----------------|-------------|--------|
| Crafting 2x2/3x3 | 1,070 | CraftInTableTask / CraftInInventoryTask | CraftItemAction | Covered (Plan 01) |
| Furnace smelting | 116 | SmeltInFurnaceTask | SmeltItemAction | Covered |
| Blast furnace | (subset of SMELT) | SmeltInBlastFurnaceTask | SmeltItemAction | Covered |
| Smoker | (subset of SMELT) | SmeltInSmokerTask | SmeltItemAction | Covered |
| Campfire | (subset of SMELT) | None | None | **MISSING** |
| Stonecutting | 275 | None | None | **MISSING** |
| Smithing transform | 12 | UpgradeInSmithingTableTask | None | **MISSING** |
| Smithing trim | 18 | UpgradeInSmithingTableTask | None | **MISSING** |
| Brewing potion | 66 | None | None | **MISSING** |
| Brewing container | (in above) | None | None | **MISSING** |

**4 of 10 production methods have GOAP actions. 6 are missing.** Of the missing 6, stonecutting (275 recipes) and smithing (30 recipes) are the highest impact.

---

## 2. Smelting: AltoClef vs GOAP

### AltoClef Architecture (Excellent Shared-Helper Pattern)

AltoClef uses a **shared `SmeltingHelper` class** that all three furnace tasks delegate to:

```
SmeltInFurnaceTask (ResourceTask)
  └─ DoSmeltInFurnaceTask (DoStuffInContainerTask)
      └─ SmeltingHelper.containerSubTask(FurnaceConfig)

SmeltInBlastFurnaceTask (ResourceTask)
  └─ DoSmeltInBlastFurnaceTask (DoStuffInContainerTask)
      └─ SmeltingHelper.containerSubTask(FurnaceConfig)

SmeltInSmokerTask (ResourceTask)
  └─ DoSmeltInSmokerTask (DoStuffInContainerTask)
      └─ SmeltingHelper.containerSubTask(FurnaceConfig)
```

**`FurnaceConfig`** holds the per-type differences (slot indices, fuel/cook state suppliers, display name). The actual smelting logic is 100% shared.

**`SmeltCache`** tracks furnace state:
- Material stack, fuel stack, output stack
- Burn percentage, fuel remaining
- Shared across all furnace types, updated each tick

**Key SmeltingHelper methods:**
1. `calculateMaterialsNeeded()` — accounts for items already in furnace + output + inventory. Computes exact deficit.
2. `calculateFuelNeeded()` — calculates fuel items needed based on material count and burn time per fuel item.
3. `selectBestFuel()` — picks the smallest fuel stack that covers the need; if none covers it, picks the largest. Prevents overfilling.
4. `containerSubTask()` pipeline:
   - **Fuel recovery:** If material slot empty but fuel remains, extract fuel first
   - **Output retrieval:** QUICK_MOVE output to inventory
   - **Material filling:** Guard on `neededMaterialsInSlot > 0` before inserting
   - **Fuel filling:** Two-phase cursor insertion + fallback injection
   - **Exit condition:** Clean screen close when all done

**Recovery:** Inherited from `DoStuffInContainerTask` — same two-phase break + collect as crafting table (see Plan 01 §8).

### GOAP SmeltItemAction

**14-state state machine:**
```
IDLE → FIND_FURNACE → EQUIP_FURNACE → PLACE_FURNACE → NAVIGATE →
OPEN → WAIT_SCREEN → PLACE_RECIPE → WAIT_RECIPE → INSERT_FUEL →
INSERT_FUEL_PLACE → WAIT_SMELT → EXTRACT → DONE
```

**What it does well:**
- Uses Recipe Book API (`handlePlaceRecipe`) with rate limiting
- Batch-aware: calculates how many smelts needed based on inventory + EndInv
- Furnace placement with navigate-to logic
- Fuel selection from player inventory with EndInv fallback
- Cook progress tracking via PropertyDelegate

**What it does poorly:**

1. **No container recovery** — placed furnaces are abandoned in the world. AltoClef's `DoStuffInContainerTask` breaks and collects the container after use. SmeltItemAction has no BREAK_FURNACE or COLLECT_FURNACE states.

2. **No fuel recovery** — if smelting finishes with leftover fuel items in the fuel slot, they stay in the furnace. AltoClef's SmeltingHelper extracts leftover fuel before closing.

3. **No material-empty guard** — if raw input is exhausted mid-smelt, the action can enter an infinite loop waiting for cook progress that never comes. AltoClef guards with `neededMaterialsInSlot > 0`.

4. **No "already burning" optimization** — if the furnace is already burning with correct material, SmeltItemAction still goes through the full PLACE_RECIPE → INSERT_FUEL flow. AltoClef detects `isBurning()` and skips fuel insertion.

5. **Hardcoded timeout** — waits 200 ticks (10 seconds) for no cook progress before bailing. AltoClef uses per-recipe burn time to compute expected completion.

6. **14 states vs 6 logical phases** — the state machine is more complex than necessary. AltoClef achieves the same with `SmeltingHelper.containerSubTask()` as a single method with if/else branches.

### What to Port for Smelting

| From AltoClef | To GOAP |
|---------------|---------|
| `DoStuffInContainerTask` recovery (break + collect) | New shared `ContainerRecovery` utility used by SmeltItemAction |
| `SmeltingHelper.selectBestFuel()` | Replace SmeltItemAction's fuel selection |
| `SmeltingHelper` fuel recovery (extract leftover) | Add RECOVER_FUEL state before DONE |
| Material-empty guard | Add check before WAIT_SMELT |
| Already-burning detection | Skip fuel insertion if `isBurning()` |
| Per-recipe burn time timeout | Replace hardcoded 200 ticks |

### Shared Container Helper Pattern

The strongest design in AltoClef's smelting code is the **`SmeltingHelper` + `FurnaceConfig`** pattern. We should adopt this:

```java
public class FurnaceConfig {
    final int materialSlot, fuelSlot, outputSlot;
    final Supplier<Boolean> isBurning;
    final Supplier<Integer> cookProgress;
    final String displayName;
}

// One SmeltItemAction handles all furnace types via config:
FurnaceConfig FURNACE = new FurnaceConfig(0, 1, 2, ...);
FurnaceConfig BLAST   = new FurnaceConfig(0, 1, 2, ...);
FurnaceConfig SMOKER  = new FurnaceConfig(0, 1, 2, ...);
```

This prevents the current situation where adding blast furnace or smoker support means duplicating the entire state machine.

---

## 3. Stonecutting: Currently Missing

### What Exists in JSON

275 stonecutter recipes with `obtainMethod: "STONECUTTER"`. Example:

```json
{
  "itemId": "stone_bricks",
  "recipeId": "minecraft:stone_bricks_from_stone_stonecutting",
  "obtainMethod": "STONECUTTER",
  "craftGrid": [["stone"]],
  "craftYield": 1
}
```

The `craftGrid` for stonecutting always has exactly 1 slot (the input item), with alternatives if applicable.

### What GoalDecomposer Does

GoalDecomposer already maps `STONECUTTER` to the `"stonecutter"` container prerequisite (line 45-56). When decomposing a goal that can be obtained via stonecutting, it creates:
- A derived goal with `obtainMethod: STONECUTTER`
- A container prerequisite for `have_item: stonecutter`

But there's no GOAP action to fulfill the STONECUTTER obtain method, so these goals sit unsatisfied.

### What AltoClef Has

Nothing — AltoClef has no stonecutter support either.

### What to Build

A `StonecutterAction` GOAP action following the same pattern as CraftItemAction:

```
State machine:
IDLE → FIND_STONECUTTER → EQUIP_STONECUTTER → PLACE_STONECUTTER →
NAVIGATE → OPEN → WAIT_SCREEN → SELECT_RECIPE → EXTRACT →
RECOVER_CONTAINER → DONE
```

**Key difference from crafting:** Stonecutter has a **recipe selection list** in its GUI, not a 3x3 grid. The player places the input item, selects from available output recipes, and clicks the output slot. This maps to:

1. Open stonecutter screen
2. Place input item in input slot (slot 0)
3. Select the desired recipe from the recipe list (this is a `SelectRecipe` packet in MC 26.1)
4. Click output slot to collect result
5. Repeat for batch quantity

**Stonecutting is often more efficient than crafting** — e.g., 1 stone → 1 stone brick (stonecutter) vs 4 stone → 4 stone bricks (crafting table). GoalDecomposer's feasibility boost should prefer stonecutting when both methods are available and a stonecutter is accessible.

---

## 4. Smithing: Currently Missing

### What Exists in JSON

30 smithing recipes:
- 12 `SMITH` (netherite upgrades: diamond tool/armor → netherite)
- 18 `SMITH_TRIM` (armor trims: cosmetic modifications)

Example:

```json
{
  "itemId": "netherite_chestplate",
  "recipeId": "minecraft:netherite_chestplate_smithing",
  "obtainMethod": "SMITH",
  "craftGrid": [
    ["netherite_upgrade_smithing_template"],
    ["diamond_chestplate"],
    ["netherite_ingot"]
  ],
  "craftYield": 1
}
```

The `craftGrid` for smithing has 3 slots: template (slot 0), base item (slot 1), addition (slot 2).

### What AltoClef Has

`UpgradeInSmithingTableTask` extending `DoStuffInContainerTask`:
- Collects template, base item, and addition as prerequisites
- Opens smithing table screen
- Fills all 3 input slots via cursor-based placement
- Extracts output via QUICK_MOVE
- Recovers container via inherited `DoStuffInContainerTask` logic

### What to Build

A `SmithingTableAction` GOAP action:

```
State machine:
IDLE → FIND_TABLE → EQUIP_TABLE → PLACE_TABLE →
NAVIGATE → OPEN → WAIT_SCREEN → FILL_SLOTS → EXTRACT →
RECOVER_CONTAINER → DONE
```

**Key differences from crafting:**
- No Recipe Book API — smithing table doesn't use `handlePlaceRecipe()`. Items must be placed manually into 3 specific slots.
- **Template slot** is unique to smithing — requires a smithing template item (consumed on use)
- For `@hero netherite` tier, this is the only way to upgrade diamond → netherite equipment

---

## 5. Brewing: Currently Missing

### What Exists in JSON

66 brewing recipes across two types:
- `BREW_POTION`: ingredient + base potion → result potion
- `BREW_CONTAINER`: ingredient + base container → result container (e.g., splash potion, lingering potion)

Example:

```json
{
  "itemId": "healing_potion",
  "recipeId": "emma:brew_healing_potion",
  "obtainMethod": "BREW_POTION",
  "craftGrid": [["glistering_melon_slice"], ["awkward_potion"]],
  "craftYield": 1
}
```

### What AltoClef Has

Only `BrewingStandSlot.java` with slot constants — no actual brewing task implementation.

### What to Build

A `BrewingStandAction` GOAP action. This is the most complex container interaction because:

1. **Fuel required:** Blaze powder in the fuel slot (separate from ingredients)
2. **3 parallel brew slots:** Can brew 3 potions simultaneously
3. **Two-phase recipe:** Base potion + ingredient = result (not a single input)
4. **Brew time:** 20 seconds per brew cycle (400 ticks) — longest wait of any production method
5. **Chain brewing:** Some recipes require multiple steps (water bottle → awkward potion → healing potion)

```
State machine:
IDLE → FIND_STAND → EQUIP_STAND → PLACE_STAND →
NAVIGATE → OPEN → WAIT_SCREEN → INSERT_FUEL →
INSERT_BASE_POTIONS → INSERT_INGREDIENT → WAIT_BREW →
EXTRACT → RECOVER_CONTAINER → DONE
```

---

## 6. Campfire Smelting: Currently Missing

### What Exists

A subset of smelting recipes have `obtainMethod: SMELT_CAMPFIRE`. Campfire cooking is unique:
- No fuel required (campfire burns indefinitely)
- 4 cooking slots (can cook 4 items simultaneously)
- 30-second cook time per item (600 ticks) — 3x slower than furnace
- Campfire must exist in world (craft + place) or be found naturally

---

## 7. Shared Container Infrastructure

### The Pattern to Adopt

All container-based production actions share the same lifecycle:

```
1. Find existing container OR place one
2. Navigate to container
3. Open container screen
4. Perform production (recipe-specific)
5. Extract output
6. Recover container (break + collect if we placed it)
```

Steps 1-3 and 6 are identical across all container types. Only step 4 differs.

### Proposed Shared Infrastructure

**`ContainerLifecycle` utility class:**

```java
public class ContainerLifecycle {
    enum Phase {
        FIND, EQUIP, PLACE, NAVIGATE, OPEN, WAIT_SCREEN,
        // ... action-specific phases injected here ...
        RECOVER_BREAK, RECOVER_COLLECT, DONE
    }

    // Shared state
    BlockPos containerPos;
    boolean placedByUs;
    String containerBlockId;  // "crafting_table", "furnace", "brewing_stand", etc.

    // Shared methods
    Phase tickFind(WorldState state, int scanRadius);
    Phase tickEquip(WorldState state);
    Phase tickPlace(WorldState state);
    Phase tickNavigate(WorldState state, double arrivalDist);
    Phase tickOpen(WorldState state);
    Phase tickWaitScreen(Class<?> expectedMenuType);
    Phase tickRecoverBreak(WorldState state, int timeoutTicks);
    Phase tickRecoverCollect(WorldState state, int timeoutTicks);
}
```

Each action (CraftItemAction, SmeltItemAction, StonecutterAction, etc.) owns a `ContainerLifecycle` instance and delegates the shared phases to it. The action only implements its own production logic (steps 4-5).

**Benefits:**
- Container recovery is consistent everywhere (fixes furnace abandonment)
- New container actions only need to implement the production-specific states
- Bug fixes to find/place/navigate/recover apply to all actions at once
- Matches AltoClef's `DoStuffInContainerTask` design but adapted for GOAP state machines

---

## 8. GoalDecomposer Integration

GoalDecomposer already knows about all container types (line 45-56 `CONTAINER_FOR_METHOD` map). When we add new production actions, the decomposer's existing container prerequisite injection will automatically create the right prerequisite goals.

What's needed:
1. **Register new actions in the action registry** so UtilityScorer includes them in the auction
2. **Each action's `computeScore()` must check its obtain method** — StonecutterAction only scores for goals with `obtainMethod: STONECUTTER`, SmithingTableAction only for `SMITH`/`SMITH_TRIM`, etc.
3. **Feasibility boost for new methods** — add cases for STONECUTTER, SMITH, BREW in `computeFeasibilityBoost()`

---

## 9. Implementation Summary

| Action | Recipes Covered | Effort |
|--------|----------------|--------|
| SmeltItemAction fixes (recovery, fuel, guards) | 116 existing | Low (modify existing) |
| Shared `ContainerLifecycle` utility | All container actions | Medium (refactor) |
| StonecutterAction | 275 | Medium (new action) |
| SmithingTableAction | 30 | Medium (new action, manual slots) |
| BrewingStandAction | 66 | High (complex, multi-phase, chain brewing) |
| Campfire smelting | (subset of SMELT) | Low |

### Dependency Order

```
1. ContainerLifecycle utility (enables everything else)
2. Retrofit SmeltItemAction to use ContainerLifecycle (fixes recovery + validates pattern)
3. Retrofit CraftItemAction to use ContainerLifecycle (consistent recovery)
4. Build StonecutterAction using ContainerLifecycle
5. Build SmithingTableAction using ContainerLifecycle
6. Build BrewingStandAction using ContainerLifecycle
```
