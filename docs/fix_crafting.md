# Crafting System Reference — MC 26.1

## Method: Recipe Book API via `handlePlaceRecipe`

All crafting uses Minecraft's vanilla Recipe Book API. No recipes are hardcoded, no manual slot manipulation — everything is looked up from the `ClientRecipeBook` and executed via a single server packet.

## The Flow

```
CraftItemAction (GOAP)
  ├─ Scoring: walk dependency chain → resolveRecipe() → RecipeBookLookup → RecipeDisplayId
  └─ Execution state machine:
       FIND_TABLE → NAVIGATE → OPEN → WAIT_SCREEN → PLACE_RECIPE → WAIT_RECIPE → EXTRACT → DONE
       (2x2 recipes skip straight to OPEN with player inventory)
```

## API Calls (in order)

| Step | API Call | What It Does |
|------|----------|-------------|
| 1. Detect screen | `player.containerMenu instanceof CraftingMenu` (3x3) or `InventoryMenu` (2x2) | Confirms correct screen is open |
| 2. Look up recipe | `RecipeBookLookup.findFirstCraftingRecipe(itemId)` | Iterates `ClientRecipeBook.getCollections()` → filters `ShapedCraftingRecipeDisplay` / `ShapelessCraftingRecipeDisplay` → matches output item → returns `RecipeDisplayEntry` |
| 3. Place recipe | `client.gameMode.handlePlaceRecipe(handler.containerId, recipeDisplayId, true)` | Server auto-fills crafting grid from player inventory + EndInv. `true` = craft max (batch) |
| 4. Wait for output | Poll `handler.slots.get(OUTPUT_SLOT).getItem()` | Wait up to 40 ticks for server to process |
| 5. Extract output | `client.gameMode.handleContainerInput(containerId, OUTPUT_SLOT, 0, ContainerInput.QUICK_MOVE, player)` | Shift-clicks output into inventory. Always succeeds (EndInv ensures space) |

## Recipe Lookup (`RecipeBookLookup`)

```java
// Lazy-cached utility: itemId → RecipeDisplayId
// Lives in com.emma.bridge.catalogue.RecipeBookLookup

RecipeDisplayEntry entry = RecipeBookLookup.findFirstCraftingRecipe("stone_pickaxe");
RecipeDisplayId id = entry.id();  // pass to handlePlaceRecipe()
```

Cache builds on first access by iterating all `RecipeCollection`s from `ClientRecipeBook`.
Invalidated on world join/disconnect via `ItemRecipeRegistry.reset()`.

## EndInv Integration

The `emma-endinv` mod has server-side mixins on `ServerPlaceRecipe` that:
1. Include EndInv items in `StackedItemContents` (so recipes show as craftable)
2. Extract items from EndInv when normal inventory search fails during `moveItemToGrid`

This means `handlePlaceRecipe` works transparently with EndInv items — no special handling needed in `CraftItemAction`.

## Key Files

| File | Role |
|------|------|
| `goap/actions/CraftItemAction.java` | GOAP action: scoring + state machine execution |
| `catalogue/RecipeBookLookup.java` | Recipe book lookup: itemId → `RecipeDisplayId` (lazy cached) |
| `catalogue/ItemRecipeRegistry.java` | Item catalogue: JSON + MC RecipeManager data, dependency walking |
| `catalogue/ItemRecipeEntry.java` | Recipe data POJO (craftGrid, smeltFrom, etc.) |
| `goap/actions/ScreenHelper.java` | Shared utilities: `closeIfOpen()`, `findItem()` |

## Recipe Unlock Behavior

Recipes unlock client-side automatically when the player picks up any ingredient. By the time GOAP has gathered materials, the recipe is already available in `ClientRecipeBook`.

## Sub-Crafting

When a 3x3 recipe is needed but no crafting table is available, `CraftItemAction` auto-sub-crafts one:
1. Look up `crafting_table` in recipe book
2. Open player inventory (2x2 grid)
3. `handlePlaceRecipe` with crafting_table recipe
4. Extract → place table → resume original craft

The server picks which planks to use automatically.
