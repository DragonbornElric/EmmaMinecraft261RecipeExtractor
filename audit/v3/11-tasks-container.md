# Audit V3 — 11: tasks/container/ (16 files) + crafting core (3 files)

Old: `adris/altoclef/tasks/container/` + `tasks/CraftGeneric*.java` + `tasks/CraftInInventoryTask.java`
New: `goap/actions/CraftItemAction.java` + `goap/actions/SmeltItemAction.java` + `goap/actions/StoreItemsAction.java` + `goap/actions/ScreenHelper.java` + `catalogue/` + `goap/GoalDecomposer.java`

---

## 1. `DoStuffInContainerTask.java` — Base container orchestrator

**Old implementation details:**
- **Container finding:** `BlockScanner.getNearestBlock()` for target block type
- **Cost-vs-make decision:** if walk distance > `getCostToMakeNew()`, places a new container instead
- **Container placement force timer:** 1 second minimum between placement attempts
- **Just-placed timer:** 3 seconds before scanning for nearest again
- **Distance threshold for new placement:** 40 blocks
- **Recovery phase after work:**
  1. IDLE → BREAKING: break placed container (20-second timeout)
  2. BREAKING → COLLECTING: walk to drop position for pickup
  3. COLLECTING: 3 ticks max wait, then cleanup
- **Dungeon chest avoidance:** scans 6-block radius for SPAWNER blocks, caches results
- **Full container detection:** checks `ContainerCache.isFull()`
- **Reachability check:** `WorldHelper.canReach(blockPos)` with 2-tick retry timeout

**Emma equivalent:** Logic distributed across CraftItemAction (FIND_TABLE/EQUIP_TABLE/PLACE_TABLE/BREAK_TABLE/COLLECT_TABLE phases) and SmeltItemAction (FIND_FURNACE/EQUIP_FURNACE/PLACE_FURNACE phases)

**Emma implementation details:**
- **Container finding:** scans `state.nearbyBlocks` (GoapTicker block scanner, 32-block radius)
- **No cost-vs-make decision** — if no container found, immediately attempts to equip/place
- **No placement timers** — equip + place with 2-tick server sync delay
- **Recovery:** CraftItemAction BREAK_TABLE + COLLECT_TABLE (60-tick timeout). SmeltItemAction does NOT recover placed furnaces.
- **No dungeon chest avoidance**
- **No full container detection** (ContainerTracker tracks contents but SmeltItemAction doesn't query fullness)
- **No reachability check before attempting to navigate**

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Walk-vs-make decision | Cost comparison: distance vs resource cost | Always make if not found nearby | May place unnecessary furnaces/tables |
| Placement timers | 1s force, 3s cooldown | 2-tick sync delay only | May spam placements on failure |
| Container recovery | Break + walk + pickup (20s timeout) | CraftItemAction: break + collect (60s). SmeltItemAction: NO recovery | Furnaces are left behind after smelting |
| Dungeon chest avoidance | 6-block SPAWNER scan | Not present | May interact with trapped chests near spawners |
| Full container detection | ContainerCache.isFull() | Not checked | May try to insert into full furnace |
| Reachability pre-check | WorldHelper.canReach() | Not present — navigates then times out if unreachable | Wastes time navigating to unreachable containers |
| Block above chest | Breaks solid block above chest before opening | Not checked | May fail to open chests under solid blocks |

**Missing edge cases:**
- [ ] No cost analysis for container placement vs walking to existing
- [ ] No furnace recovery after smelting (furnaces accumulate in world)
- [ ] No dungeon/spawner avoidance
- [ ] No reachability pre-check
- [ ] No solid-block-above-chest handling

**Verdict:** PARTIAL

---

## 2. `CraftInTableTask.java` — 3×3 crafting table crafting

**Old implementation details:**
- **Inner class `DoCraftInTableTask`** extends DoStuffInContainerTask
- **Container target:** crafting_table block
- **Recipe targets:** array of RecipeTarget (supports batch: multiple items)
- **Material collection:** first collects ALL materials via `CollectRecipeCataloguedResourcesTask` before opening table
- **Container subtask flow:**
  1. Check cursor empty, clean up if not
  2. Check if target satisfied (have enough items) → close screen
  3. Try recipe book crafting first (`CraftGenericWithRecipeBooksTask`)
  4. If recipe book fails (100-tick timeout): fall back to manual (`CraftGenericManuallyTask`)
  5. Shift-click output to inventory
- **Recipe book:** `JankCraftingRecipeMapping.findNetworkRecipeId(outputItem)` → `clickRecipe(syncId, recipeId, true)`
- **Manual crafting fallback:** per-slot ingredient placement via MoveItemToSlotFromInventoryTask
- **Multiple targets:** processes in order, exits when all satisfied
- **Cost to make new table:** if nearest within 40 blocks → INFINITY (use existing). If have logs/planks → 10. Else → 100.

**Emma equivalent:** `CraftItemAction.java`

**Emma implementation details:**
- **Container target:** crafting_table (or player 2×2 inventory for small recipes)
- **Recipe targets:** single item at a time from GoalDecomposer chain
- **No pre-collection:** attempts to craft immediately if ingredients are in inventory. GoalDecomposer creates mining/gathering goals separately.
- **Phase flow:**
  1. FIND_TABLE → EQUIP_TABLE → PLACE_TABLE (if needed)
  2. NAVIGATE → OPEN → WAIT_SCREEN (40-tick timeout)
  3. PLACE_RECIPE (via handlePlaceRecipe, rate-limited 1 per 2 ticks)
  4. WAIT_RECIPE (40-tick timeout for output to appear)
  5. EXTRACT (shift-click output slot 0)
  6. If craftsRemaining > 0: loop back to PLACE_RECIPE
  7. BREAK_TABLE → COLLECT_TABLE (if table was placed)
- **Recipe book only:** uses `RecipeBookLookup.findFirstCraftingRecipe()` → `handlePlaceRecipe()`. **No manual crafting fallback.**
- **Batch crafting:** calculates `recipePlaceTarget = ceil((goalCount - haveCount) / yield)`, capped at `64 / inputsPerCraft`
- **Sub-craft for table:** if no crafting table and need 3×3, sub-crafts table from planks using 2×2 grid
- **Table reuse:** after DONE, checks `findCraftableInChain()` for next item needing table → reuses placed table
- **Cost analysis:** none — places table if not found

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Material pre-collection | Collects ALL ingredients before opening table | Expects ingredients already in inventory (GOAP scored separately) | Old guaranteed materials present; Emma may open table without all ingredients |
| Recipe book vs manual | Recipe book first, manual fallback on 100-tick timeout | Recipe book ONLY, no manual fallback | If recipe book lookup fails (new modded items, edge cases), crafting fails entirely |
| Manual slot placement | Per-slot MoveItemToSlotFromInventoryTask | Not present | Can't craft items missing from recipe book |
| Batch crafting | Process multiple RecipeTargets in sequence | Single item, loops via craftsRemaining counter | Same throughput, different orchestration |
| Table placement cost | Distance vs material cost comparison | Always places if not found | May place tables unnecessarily close to existing ones |
| Table reuse | Via DoStuffInContainerTask lifecycle | Explicit findCraftableInChain() check after DONE | Emma actively chains crafts |
| Recipe rate limiting | Per mod settings containerItemMoveDelay | 1 call per 2 ticks (hardcoded) | Emma has fixed rate vs old's configurable |
| 2×2 crafting | Separate CraftInInventoryTask class | Same CraftItemAction with usePlayerGrid flag | Emma unified; old had separate paths |
| Sub-craft table | Separate task via TaskCatalogue | Inline sub-craft (save/restore target) | Emma's is self-contained |
| Cursor cleanup | Explicit cleanup before each slot op | Not present in crafting flow | Leftover cursor items could disrupt crafting |

**Missing edge cases:**
- [ ] No manual crafting fallback — recipe book-only means some items may be uncraftable
- [ ] No cursor cleanup during crafting operations
- [ ] No material pre-validation before opening table (may waste time opening then closing)
- [ ] No configurable rate limiting for slot operations

**Verdict:** PARTIAL — Core crafting works via recipe book but missing manual fallback and several safety checks.

---

## 3. `CraftGenericManuallyTask.java` — Manual slot-by-slot crafting

**Old implementation details:**
- For each recipe slot (0 to slotCount):
  - Get required item and calculate count per slot: `ceil(targetCount / outputCount)`
  - If slot has wrong item: extract via QUICK_MOVE
  - If slot has right item but < required: add more via MoveItemToSlotFromInventoryTask
  - If slot has right item but > required: right-click to extract half
  - After all slots filled: shift-click output
- **Half-stack extraction:** right-click on oversatisfied slot to split
- **Inaccessible item check:** moves items from container-only slots to inventory first

**Emma equivalent:** None — no manual crafting exists in Emma

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Manual slot placement | Full per-slot logic with oversatisfied handling | Not present | Cannot craft items without recipe book entries |
| Half-stack splitting | Right-click to extract excess | Not present | N/A |
| Inaccessible item recovery | MoveInaccessibleItemToInventoryTask | Not present | Items stuck in wrong slots can't be recovered |

**Verdict:** GAP — No manual crafting. Items not in recipe book cannot be crafted.

---

## 4. `CraftGenericWithRecipeBooksTask.java` — Recipe book crafting

**Old implementation details:**
- Uses `JankCraftingRecipeMapping.findNetworkRecipeId(outputItem)` to find recipe
- Calls `mod.getController().clickRecipe(syncId, recipeId, true)` (makeAll=true)
- 100-tick timeout (5 seconds) → marks failed
- On failure: allows fallback to manual crafting

**Emma equivalent:** `RecipeBookLookup.java` + `CraftItemAction.handlePlaceRecipe()`

**Emma implementation details:**
- `RecipeBookLookup.findFirstCraftingRecipe(bareItemId)` — scans ClientRecipeBook collections
- Caches: ShapedCraftingRecipeDisplay and ShapelessCraftingRecipeDisplay only
- Cache invalidation on world change/disconnect
- Calls `client.gameMode.handlePlaceRecipe(containerId, recipeDisplayId, makeAll)` (makeAll=false for rate-limited)
- 40-tick timeout for output to appear
- Cache validity: tracks recipe count, rebuilds if collections grow

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Recipe lookup | JankCraftingRecipeMapping with NetworkRecipeId | RecipeBookLookup with RecipeDisplayId | Different MC API versions (26.1 uses display IDs) |
| API call | clickRecipe(syncId, recipeId, true) | handlePlaceRecipe(containerId, displayId, makeAll) | MC 26.1 recipe API change |
| makeAll parameter | true (craft as many as possible) | false (rate-limited batches) | Emma crafts in controlled batches |
| Timeout | 100 ticks (5 sec) | 40 ticks (2 sec) | Emma times out faster |
| Failure handling | Marks failed, allows manual fallback | Returns to DONE phase | No fallback in Emma |
| Cache invalidation | Not described | On world change + recipe count growth | Emma has explicit cache management |

**Verdict:** IMPLEMENTED — Updated for MC 26.1 recipe API with better caching but shorter timeout and no manual fallback.

---

## 5. `CraftInInventoryTask.java` — 2×2 player crafting

**Old implementation details:**
- Separate class for 2×2 crafting in player inventory
- Opens player inventory screen
- Uses same CraftGenericManuallyTask/RecipeBook flow as table crafting
- Slot mapping: PlayerSlot.getCraftInputSlot(0-3), PlayerSlot.CRAFT_OUTPUT_SLOT
- No container placement needed

**Emma equivalent:** `CraftItemAction.java` with `usePlayerGrid = true`

**Emma implementation details:**
- Same CraftItemAction class, toggled by `usePlayerGrid` flag
- Opens player inventory: `client.setScreen(new InventoryScreen(player))`
- Output slot: 0 (same as table)
- Input slots: 1-4 (2×2 grid)
- Player inventory slots: 5-40
- Uses same recipe book flow

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Class structure | Separate CraftInInventoryTask class | Same CraftItemAction with flag | Emma is simpler |
| Slot mapping | PlayerSlot.getCraftInputSlot(0-3) | Hardcoded slots 1-4 | Same slots, different access |
| Manual fallback | Available via CraftGenericManuallyTask | Not available | Same gap as table crafting |

**Verdict:** IMPLEMENTED — Unified into CraftItemAction with usePlayerGrid flag.

---

## 6. `SmeltInFurnaceTask.java` + `SmeltInBlastFurnaceTask.java` + `SmeltInSmokerTask.java`

**Old implementation details:**
- Three separate classes, each targeting a specific furnace type
- **Slot layout:** input=0, fuel=1, output=2 (same for all three)
- **Per-tick container subtask flow:**
  1. Get output: if output slot non-empty → QUICK_MOVE to player (check inventory full first)
  2. Recover excess fuel: if material slot empty and fuel remains → QUICK_MOVE fuel to player
  3. Fill materials: MoveItemToSlotFromInventoryTask for input
  4. Fill fuel: calculate needs, find best fuel, inject
  5. Exit: if input empty + output empty + cook% ≤ 0 → close
- **Fuel needs calculation:** `needs = materialCount - (fuelSlotAmount + burningFuelCount + burnPercentage)`
- **Fuel selection (SmeltingHelper.findBestFuel):**
  - Iterates all fuel items in inventory
  - Prefers smallest overshoot (closest match to needs)
  - If all exceed needs: picks largest undershoot (partial fill)
  - Perfect fit preferred over overshoot
- **Furnace cache (SmeltingHelper):**
  - PropertyDelegate indices: [0]=cookProgress, [1]=fuelLeft, [2]=burnDuration, [3]=cookTimeMax
  - Updated every tick container is open
  - Safety clamping: negatives → 0
- **Cost to make new furnace:** if furnace has contents (burn/fuel/items) → 9999999 (never replace active furnace). If have >8 cobblestone → 10-100 (sliding). If have wood → 50. Else → 100.
- **Blast furnace extra cost:** also needs >5 raw iron + cobblestone
- **Smoker extra cost:** also needs >4 logs + cobblestone
- **Recovery:** breaks and picks up placed furnace after smelting complete

**Emma equivalent:** `SmeltItemAction.java`

**Emma implementation details:**
- **Single class for all furnace types** — scans for FURNACE, BLAST_FURNACE, or SMOKER
- **Slot layout:** INPUT=0, FUEL=1, OUTPUT=2 (same)
- **Phase flow:** FIND_FURNACE → NAVIGATE → OPEN → WAIT_SCREEN → INSERT_INPUT → INSERT_FUEL → WAIT_COOK → EXTRACT
- **INSERT_INPUT:** shift-click raw material from player inventory to furnace
- **INSERT_FUEL:** check burnTimeRemaining via ContainerData[0]; if > 0, skip to WAIT_COOK. Else find fuel and shift-click.
- **WAIT_COOK:** poll output slot; track progress via ContainerData[2]/[3]; 200-tick timeout on no progress
- **EXTRACT:** shift-click output slot 2; if input still has material, loop to WAIT_COOK
- **Fuel selection:** scans player inventory for items in FUEL_ITEMS set + pattern matches. No preference ordering (picks first match).
- **Furnace placement:** if no furnace found, checks EndinvBridge first, then places from inventory. Cardinal + diagonal + player position placement scan.
- **No recovery:** placed furnaces are not broken/collected after use
- **No furnace type preference:** uses nearest furnace regardless of type (blast furnace for ores would be 2× faster)
- **Cook progress tracking:** ContainerData indices [0]=burnTimeRemaining, [2]=cookProgress, [3]=totalCookTime
- **Progress stall:** 0.001f threshold (0.1% change) for detecting no progress
- **Screen close detection:** if menu closes during WAIT_COOK, resets to IDLE

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Furnace type classes | 3 separate classes (Furnace, BlastFurnace, Smoker) | 1 unified class | Emma is simpler but loses type-specific optimizations |
| Furnace type preference | Each class targets its type specifically | Uses nearest of any type | Won't prefer blast furnace for ores (2× speed) or smoker for food (2× speed) |
| Fuel selection | Best-fit algorithm: smallest overshoot > perfect > largest undershoot | First match in inventory | May waste high-value fuel (coal blocks) when sticks would suffice |
| Fuel items supported | isSupportedFuel() predicate | FUEL_ITEMS set + pattern matching | Different fuel lists — need to verify coverage |
| Excess fuel recovery | QUICK_MOVE fuel back when done | Not present | Excess fuel stays in furnace |
| Output extraction | Shift-clicks with inventory-full pre-check | Shift-clicks without full check | May silently fail if inventory full |
| Active furnace protection | Cost 9999999 for furnace with contents | Not checked | May try to use a furnace that's already smelting something |
| Furnace recovery | Breaks and collects placed furnace | No recovery | Furnaces accumulate in world |
| Material needs calculation | materialCount - (fuelSlot + burning + burnPercent) | Checks burnTimeRemaining only | Less precise fuel calculation |
| Cook progress indices | [0]=cooking, [1]=fuelLeft, [2]=burnDuration, [3]=cookTimeMax | [0]=burnTimeRemaining, [2]=cookProgress, [3]=totalCookTime | Different index interpretation — need to verify MC 26.1 furnace ContainerData layout |
| Multiple smelting targets | 1 target at a time (warns if > 1) | 1 target at a time | Same |
| Inventory full handling | Pre-checks before shift-click | No pre-check | Silent failure on full inventory |

**Missing edge cases:**
- [ ] No furnace type preference — doesn't use blast furnace for ores or smoker for food
- [ ] No fuel efficiency ranking — picks first fuel found, may waste coal blocks
- [ ] No excess fuel recovery
- [ ] No inventory full check before shift-clicking output
- [ ] No protection for active furnaces (may interact with furnace already smelting other items)
- [ ] No furnace recovery after smelting

**Verdict:** PARTIAL — Core smelting works but lacks furnace type optimization, fuel efficiency, and several safety checks.

---

## 7. `SmeltingHelper.java` — Shared smelting utilities

**Old implementation details:**
- `updateCache()`: reads PropertyDelegate[0-3] for progress/fuel tracking
- `findBestFuel(needs)`: preference algorithm (smallest overshoot > perfect > undershoot)
- `getFuelAmount(Item)`: hardcoded fuel values for each item
- `isSupportedFuel(Item)`: whitelist check
- Safety: clamp negatives to 0

**Emma equivalent:** Fuel logic is inline in SmeltItemAction

**Emma implementation details:**
- Fuel items: FUEL_ITEMS constant set + pattern matching (contains "planks", "log", etc.)
- No fuel value (burn time) tracking
- No preference algorithm
- Progress tracked via ContainerData directly in SmeltItemAction

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Fuel values | Hardcoded burn time per item | Not tracked — uses any fuel | Can't calculate how much fuel is needed for N items |
| Fuel preference | Best-fit: minimize waste | First match | May burn 8 coal blocks to smelt 1 item |
| Fuel caching | Per-tick cache update | Direct ContainerData read | Same info, different caching |

**Verdict:** PARTIAL — Fuel selection is significantly simplified, losing efficiency optimization.

---

## 8. `ContainerStoredTracker.java` — Tracks items moved to containers

**Old implementation details:**
- Subscribes to `SlotClickChangedEvent`
- Tracks only items moved to *this specific* container (not pre-existing items)
- Filters: `slot.isSlotInPlayerInventory() == false` and `acceptDeposit` predicate
- Used by StoreInContainerTask to verify deposits

**Emma equivalent:** `ContainerTracker.java`

**Emma implementation details:**
- Tracks ALL container contents via hash comparison (not per-deposit)
- MAX_CACHE_SIZE = 256 (LRU)
- On open: associates position with BlockInteraction.lastInteractedBlockPos
- On change: captures full ContainerSnapshot (items map + slot details)
- On close: broadcasts container_contents WebSocket event
- Container types: ChestBlock (27/54), HopperMenu, DispenserMenu

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Tracking granularity | Per-deposit (tracks what YOU put in) | Full snapshot (all contents) | Emma can't distinguish player-deposited vs pre-existing items |
| Event source | SlotClickChangedEvent subscription | Hash-based polling + Fabric API | Different mechanisms |
| Cache size | Per-container instance | Global LRU cache (256 max) | Emma may evict old container data |
| Container types | All via AbstractDoToStorageContainerTask | Chest, double chest, hopper, dispenser | Emma's type list may miss some containers |

**Verdict:** PARTIAL — Different tracking approach. Emma tracks full snapshots but can't track per-deposit items.

---

## 9. `StoreInAnyContainerTask.java` + `StoreInContainerTask.java` + `StoreInStashTask.java`

**Old implementation details:**
- **StoreInAnyContainerTask:** scans for nearest storage container (chest, barrel, shulker), deposits items
- **StoreInContainerTask:** deposits at specific container position
- **StoreInStashTask:** deposits at designated stash location (configurable)
- Container types: CHEST, TRAPPED_CHEST, BARREL, all SHULKER_BOXES
- Shift-clicks items from player inventory to container
- Inventory full detection + logging

**Emma equivalent:** `StoreItemsAction.java`

**Emma implementation details:**
- **Unified class** for deposit and withdrawal
- **FREE_SLOT_THRESHOLD = 5** — triggers autonomous deposit when ≤ 5 free slots
- **Container search:** radius 32, types ChestBlock/BarrelBlock/ShulkerBoxBlock
- **Y-range:** autonomous ±2, bridge request ±4
- **Keep logic:** 1 food stack, 1 per tool category, 1 shield, armor upgrades
- **Rate:** 1 shift-click per tick
- **Timeouts:** NAV 200 ticks, SCREEN 40, TRANSFER 200
- **DEPOSIT vs WITHDRAW:** determined by StorageRequest type
- **Container slot boundary:** `handler.slots.size() - 36`
- **No designated stash location**

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Stash system | Configurable stash location | No designated stash | Can't store items at a specific base location |
| Trapped chest support | Explicit support | Not mentioned in container types | May miss trapped chests |
| Keep logic | None (stores everything requested) | Keeps food, tools, shield, armor upgrades | Emma is smarter about what to deposit |
| Autonomous trigger | Not present (always explicit) | FREE_SLOT_THRESHOLD ≤ 5 | Emma auto-deposits when running low on space |
| Withdrawal | Separate pickup tasks | Same class with WITHDRAW mode | Emma unified |
| Y-range limits | No explicit Y limit | ±2 autonomous, ±4 request | Emma avoids underground containers for auto-deposit |

**Verdict:** IMPLEMENTED — Emma's is more sophisticated with keep logic and autonomous triggering, but lacks stash/designated storage.

---

## 10. `CraftInAnvilTask.java`

**Old implementation details:**
- Throws `NotImplementedException` — not actually implemented in old code either

**Emma equivalent:** None

**Verdict:** NOT_NEEDED — Was never implemented in old code.

---

## 11. `UpgradeInSmithingTableTask.java`

**Old implementation details:**
- Slots: template input, material input, tool input, output
- Collects: smithing template + base item + upgrade material
- Handles armor currently equipped: removes to inventory before upgrading
- Requires empty slot for armor removal
- Uses DoStuffInContainerTask lifecycle for table finding/opening

**Emma equivalent:** None — no smithing table action exists

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Smithing table | Full implementation with armor handling | Not present | Cannot upgrade to netherite gear or apply armor trims |
| Template handling | Collects required template | N/A | N/A |
| Equipped armor handling | Removes and re-equips during upgrade | N/A | N/A |

**Verdict:** GAP — Cannot perform smithing table upgrades. Recipe data exists in ItemRecipeRegistry (SMITH entries) but no action executes them.

---

## 12. `LootContainerTask.java`

**Old implementation details:**
- Opens container, shift-clicks all items from container slots to player inventory
- **Stuck detection:** 40-tick no-progress threshold (same slot attempted twice or inventory full)
- Uses AbstractDoToStorageContainerTask base for container finding/opening
- Tracks items per-slot, picks best matching slot for each target

**Emma equivalent:** `StoreItemsAction.java` with WITHDRAW mode

**Emma implementation details:**
- WITHDRAW mode: iterates container slots 0..containerSlotCount, shift-clicks matching items
- 1 item per tick rate
- 200-tick transfer timeout
- No stuck detection for looting specifically

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Stuck detection | 40-tick no-progress check | 200-tick global timeout | Emma takes longer to give up on stuck loots |
| Autonomous looting | Via task with specific targets | Only responds to bridge requests | Can't autonomously explore and loot discovered chests |
| Item selection | Best matching slot per target | Any matching item | Minor — same outcome |

**Verdict:** PARTIAL — Withdrawal works but no autonomous looting of discovered containers.

---

## 13. `PickupFromContainerTask.java`

**Old implementation details:**
- Picks up specific items from a known container
- Extends LootContainerTask with item target filtering

**Emma equivalent:** `StoreItemsAction.java` WITHDRAW mode with item filter

**Verdict:** IMPLEMENTED — Covered by StoreItemsAction WITHDRAW mode.

---

## 14. `AbstractDoToStorageContainerTask.java`

**Old implementation details:**
- Base class: scans for target block, detects container type, opens container
- Handles: block above chest (breaks it), container type detection, chunk-loaded check
- Calls `onContainerOpenSubtask()` when screen handler matches

**Emma equivalent:** Logic inline in CraftItemAction/SmeltItemAction/StoreItemsAction

**Verdict:** NOT_NEEDED — Base class pattern replaced by inline phase logic in each action.

---

## 15-16. `StoreInStashTask.java` (covered in #9) + Additional patterns

**StoreInStashTask** — Deposits at a designated stash location. Not present in Emma (no stash concept — BaseRegistry exists but StoreItemsAction doesn't use it for stash).

**Verdict:** GAP — No stash/home-base storage designation.
