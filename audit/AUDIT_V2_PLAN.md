# Audit V2 Plan: Data-Flow Tracing Audit

## Why V1 Failed

The V1 audit compared **what** each old file did vs **what** each new file does. It read comments, matched file names, and wrote "REDESIGNED" or "COVERED" based on surface-level equivalence. It missed real bugs like the `hasItemOrTagEquivalent` double-expansion in `CraftItemAction.resolveRecipe()` because it trusted inline comments instead of tracing actual data flow.

**The fix:** V2 audits **execution chains**, not files. For each critical gameplay chain (mine iron → smelt → craft pickaxe), trace the actual data through every function call, verify every handoff, and flag where values are silently wrong.

## Methodology

For each chain below:

1. **Start from the trigger** — what goal/mode creates the initial GOAP goals?
2. **Trace GoalDecomposer** — what sub-goals does it create? Read the actual `decomposeItem()` call with concrete inputs. Verify the dependency tree is correct.
3. **Trace the block scanner** — does `computeGoalRelevantBlocks()` add the right blocks to the scan set? Does `resolveBlock()` find them? Does `nearbyBlocks` get populated?
4. **Trace each action's scoring** — with concrete WorldState values, walk through `computeScore()` line by line. What score does it produce? Does it win the auction?
5. **Trace each action's execution** — walk through the state machine tick by tick. Where does it call Emmatone/MC APIs? What can go wrong at each step?
6. **Verify handoffs** — where one component produces a value consumed by another (e.g., `itemToMineableBlocks` returns block names that must match `nearbyBlocks` keys), verify the formats match (prefix `minecraft:` vs bare name, etc.)
7. **Check edge cases** — what happens when inventory is full? When the block is unreachable? When the tool breaks mid-mine? When the crafting table is obstructed?

**Rules:**
- Do NOT trust inline comments. Read what the code does.
- Do NOT mark anything "COVERED" without tracing the full data path.
- Every claim must reference a specific line number in a specific file.
- If a function has side effects, trace those too.

## Chains to Audit

### Chain 1: Bare-Hands to Iron Pickaxe

The most critical chain — if this doesn't work, nothing works.

**Expected sequence:**
1. Goal: `have_item iron_pickaxe`
2. Decompose: iron_pickaxe → 3× iron_ingot + 2× stick (3x3 craft)
3. Decompose iron_ingot: smelt raw_iron (need furnace)
4. Decompose raw_iron: mine iron_ore (need stone_pickaxe)
5. Decompose stone_pickaxe: 3× cobblestone + 2× stick (3x3 craft, need crafting_table)
6. Decompose cobblestone: mine stone (need wooden_pickaxe)
7. Decompose wooden_pickaxe: 3× planks + 2× stick (3x3 craft, need crafting_table)
8. Decompose crafting_table: 4× planks (2x2 craft)
9. Decompose stick: 2× planks (2x2 craft)
10. Decompose planks: mine logs (no tool needed)
11. Decompose furnace: 8× cobblestone (3x3 craft)

**Trace each step:**
- [ ] `GoalDecomposer.decomposeItem("iron_pickaxe")` — read the actual code with this input. What goals does it create? In what order? Does it inject tool prerequisites correctly (stone_pickaxe before iron_ore)?
- [ ] `GoalDecomposer.decomposeItem("cobblestone")` — does it know cobblestone comes from mining `stone` blocks? Does it create a `have_item wooden_pickaxe` prerequisite?
- [ ] `computeGoalRelevantBlocks()` — with the full goal set from decomposition, what blocks end up in the scan set? Specifically: does `minecraft:stone` appear? Does `minecraft:oak_log` (or any log variant) appear?
- [ ] `MineBlockAction.computeScore()` — when the goal is `have_item cobblestone` and `nearbyBlocks` contains `minecraft:stone`, does the scoring loop find it? Check: does `itemToMineableBlocks("cobblestone")` return `["stone", ...]` and does the `nearbyBlocks` lookup use the same key format?
- [ ] `MineBlockAction.hasRequiredTool()` — when mining stone for cobblestone, does it correctly require WOOD tier pickaxe? What happens if we don't have one yet — does the action score 0 and yield to CraftItemAction for the wooden pickaxe?
- [ ] `CraftItemAction.findCraftableInChain()` — with goals for planks, sticks, crafting_table, wooden_pickaxe, stone_pickaxe, iron_pickaxe... which one does it pick first? Is it actually the most "raw" (planks from logs)? Verify the sort order of derived goals.
- [ ] `CraftItemAction.resolveRecipe("oak_planks")` — when player has `oak_log`, trace through slotAlts. Does `item_recipes.json` have a craft entry for oak_planks with `["oak_log", "oak_wood", ...]`? Does the slotAlts loop find oak_log? Does `state.hasItem("minecraft:oak_log", 1)` return true?
- [ ] `CraftItemAction` state machine: FIND_TABLE → EQUIP_TABLE → PLACE_TABLE flow. When no crafting table exists and the recipe is 3x3, does `startSubCraftTable()` correctly inline a 2x2 craft of the table itself? What if we need the table for `wooden_pickaxe` but the table recipe needs 4 planks — does it craft planks first (2x2) then table (2x2) then pickaxe (3x3)?
- [ ] `SmeltItemAction` — when goal is `have_item iron_ingot` and we have `raw_iron` + a furnace nearby, trace the full state machine. Does it find the furnace? Navigate? Open? Insert fuel? Insert raw_iron? Wait? Extract?
- [ ] Fuel selection in SmeltItemAction — what does it use as fuel? Does it burn planks/logs that we need for tools? Is there a fuel priority?

### Chain 2: Food Acquisition from Zero

**Expected sequence:**
1. Hunger drops below threshold → `stay_fed` goal priority increases
2. `CollectFoodAction` or `EatFoodAction` wins auction
3. If no food in inventory: need to acquire food
4. Options: kill animals (HuntMobAction), harvest crops (MineBlockAction), craft bread from wheat

**Trace:**
- [ ] `EatFoodAction.checkPreconditions()` — what counts as "has food"? Does `state.hasFood()` check the right thing?
- [ ] `CollectFoodAction.computeScore()` — when no food exists, what score does this produce? Does it beat other actions?
- [ ] `CollectFoodAction` phases — EVALUATE → DIRECT_GATHER or GOAL_CHAIN_MONITOR. What triggers each path? If food blocks are nearby (wheat, potatoes), does DIRECT_GATHER mine them? If not, does GOAL_CHAIN_MONITOR inject the right goals?
- [ ] `HuntMobAction` — when hunting a cow for beef, trace: entity scan → nearest cow → pathfind → attack → wait for drop → does `PickupItemAction` collect the beef? Or does it just sit there?
- [ ] Food after kill — raw beef needs cooking. Does the decomposer create smelt goals for raw food? Does `SmeltItemAction` handle food smelting (smoker preference)?

### Chain 3: Combat Survival Loop

**Expected sequence:**
1. Zombie approaches within 16 blocks → appears in `state.threats`
2. Reflexes fire: PreEquipWeapon (if 3.5-8 blocks), ForceField (if < 3.5), ShieldBlock (if ranged)
3. AttackEntityAction or FleeFromAction wins auction
4. Combat until mob dies or player flees

**Trace:**
- [ ] `WorldState.updateThreats()` — exact scan logic. What AABB size? What entity types qualify? How is the list sorted?
- [ ] `ForceFieldReflex.shouldFire()` — does it correctly check all gate conditions (not eating, not falling, not shielding)? When it fires, does `CombatHelper.tryAttack()` actually connect? Check attack cooldown logic.
- [ ] `AttackEntityAction.computeScore()` — with a zombie at 10 blocks, trace the score formula. What's the priority? The proximityUrgency? The gearFactor? Does it beat other actions?
- [ ] `AttackEntityAction.execute()` — pathfind to zombie, attack when in range. What happens if the zombie moves? Does it re-path? What if another mob spawns behind?
- [ ] `FleeFromAction` trigger conditions — at what health/threat combo does flee outscore attack? With 2 zombies and 8 health, trace both scores.
- [ ] `ShieldBlockReflex` — when a skeleton is shooting, trace the detection (is it checking `isUsingItem()`?), the shield swap (offhand slot manipulation), the hold duration. What if we don't have a shield?

### Chain 4: Smelt Pipeline (Raw Iron → Iron Ingot)

**Expected sequence:**
1. Have raw_iron, need iron_ingot → smelt goal exists
2. SmeltItemAction scores and wins
3. Find/place furnace → navigate → open → insert fuel → insert raw_iron → wait → extract

**Trace:**
- [ ] `SmeltItemAction.computeScore()` — does it check for both the input item AND fuel availability? What if we have raw_iron but no fuel?
- [ ] `SmeltItemAction.tickFindFurnace()` — searches `nearbyBlocks` for furnace types. What if none exist? Does it transition to crafting a furnace? Or does it just score 0 and yield?
- [ ] Furnace crafting prerequisite — does `GoalDecomposer` inject a `have_item furnace` sub-goal when smelting is needed? Trace through `CONTAINER_FOR_METHOD` mapping.
- [ ] `SmeltItemAction.tickInsertFuel()` — what items does it consider as fuel? Check `isFuel()` predicate. Does it prioritize coal over planks? Could it burn the last planks needed for tool crafting?
- [ ] `SmeltItemAction.tickInsertInput()` — slot manipulation. Does it correctly shift-click raw_iron into the furnace input slot? What if the furnace already has a different item in the input slot?
- [ ] `SmeltItemAction.tickWaitSmelt()` — how does it know smelting is complete? Does it read the furnace progress via the `AbstractFurnaceScreenHandlerAccessor` mixin? What's the timeout?
- [ ] `SmeltItemAction.tickExtract()` — does it shift-click the output? What if inventory is full?

### Chain 5: Goal Decomposition Edge Cases

- [ ] **Circular dependencies** — does `GoalDecomposer` handle cycles? Example: if a recipe somehow references itself transitively, does it infinite-loop or does the `seen` set catch it?
- [ ] **Multiple obtain methods** — for items with both MINE and CRAFT paths (e.g., `stone` can be mined with silk touch OR smelted from cobblestone), does the decomposer create OR-goals? Does the scoring correctly prefer the easier path?
- [ ] **Tag variant propagation** — when decomposer resolves `#planks` to `spruce_planks` (because we have spruce_logs), does that choice propagate correctly to ALL downstream consumers (crafting table recipe, stick recipe, tool recipes)?
- [ ] **Inventory count tracking** — when decomposer creates goals for "need 3 iron_ingot" and "need 8 cobblestone", does it account for items already in inventory? Or does it create goals for the full amount regardless?
- [ ] **Goal priority ordering** — are raw materials (logs, cobblestone) scored higher than intermediate products (planks, sticks) so they get gathered first? Trace `GoalDecomposer.computeStepsSaved()` and the priority assignment.

### Chain 6: Block Scanner → Action Handoff

This is pure data-flow verification — no gameplay logic, just format matching.

- [ ] `computeGoalRelevantBlocks()` returns strings like `"minecraft:stone"`. Does `resolveBlock()` correctly parse this into a `Block` object?
- [ ] `nearbyBlocks` keys are the strings from `resolveBlock()` output. Are they always `"minecraft:X"` prefixed? Or sometimes bare `"stone"`?
- [ ] `MineBlockAction.itemToMineableBlocks()` returns strings from `ItemRecipeEntry.getMineBlockNames()`. What format are those in? Are they `"stone"` or `"minecraft:stone"`?
- [ ] When `MineBlockAction` searches `state.nearbyBlocks` for the blocks from `itemToMineableBlocks`, does the key format match? This is the exact kind of mismatch that causes "walking past stone when you need cobblestone."
- [ ] Same check for `CraftItemAction` searching for crafting tables, and `SmeltItemAction` searching for furnaces.
- [ ] `TagGroups.findNearbyWithTagEquivalents()` — does it expand correctly? If we need `oak_log` and the scanner found `spruce_log`, does this function bridge the gap? Or does it only work for blocks, not items?

### Chain 7: Crafting Table Lifecycle

- [ ] When `CraftItemAction` needs a 3x3 recipe and no crafting table exists:
  - Does it sub-craft a table from planks (2x2)?
  - Does it place the table? Where? What if all adjacent blocks are occupied?
  - After placing, does it navigate to the table and open it?
  - After crafting, does it break and collect the table?
  - If there's a second 3x3 recipe in the chain, does it reuse the placed table?
- [ ] When a crafting table already exists nearby:
  - Does the block scanner find it? (Always scanned — line 563)
  - Does `CraftItemAction` prefer using the existing table over placing a new one?
  - What if the existing table is obstructed (block in front of it)?
- [ ] Crafting table placed on a slope/ledge — does `BlockInteraction.rightClickBlock()` handle non-flat terrain?

### Chain 8: Tool Tier Gating

- [ ] `MineBlockAction.hasRequiredTool()` — trace with concrete inputs:
  - Mining `stone` (needs WOOD pickaxe): `requirement = "WOOD"`, `toolType = "PICKAXE"`. Does it scan inventory for any wooden+ pickaxe?
  - Mining `iron_ore` (needs STONE pickaxe): same trace
  - Mining `diamond_ore` (needs IRON pickaxe): same trace
  - Mining `obsidian` (needs DIAMOND pickaxe): same trace
- [ ] What does "has required tool" actually check? Does it use `ItemClassifier.getMaterialTier()`? Does it compare the tier correctly (WOOD=0, STONE=1, IRON=2, DIAMOND=3)?
- [ ] When the required tool is NOT in inventory, does `MineBlockAction` return score 0? And does `GoalDecomposer` have a sub-goal for crafting that tool? Verify the tool prerequisite injection in `buildSubgoalTarget()`.
- [ ] Tool durability — what happens if the pickaxe breaks mid-mine? Does `ToolEquipReflex` swap to the next best? What if there is no next best?

## Output Format

For each chain, produce a markdown file in `audit/v2/`:

```
# Chain N: [Name]

## Traced Execution Path

### Step 1: [Component.method()]
**File:** path/to/File.java:123
**Input:** concrete values
**Code path:** line-by-line trace of what actually executes
**Output:** what it returns/mutates
**Handoff to:** next component

### Step 2: ...

## Bugs Found
1. **[BUG]** Description — file:line — what's wrong and why

## Format Mismatches Found
1. **[MISMATCH]** Component A produces "stone", Component B expects "minecraft:stone" — file:line

## Edge Cases Not Handled
1. Description — what happens, what should happen

## Verified Working
1. Description — traced and confirmed correct
```

## Execution Order

1. **Chain 6 first** (block scanner → action handoff) — this is the most likely source of silent failures. Pure format verification, no gameplay logic needed.
2. **Chain 1 second** (iron pickaxe) — the critical gameplay chain.
3. **Chain 4 third** (smelt pipeline) — iron pickaxe depends on this.
4. **Chain 7 fourth** (crafting table lifecycle) — iron pickaxe depends on this.
5. **Chain 8 fifth** (tool tier gating) — interleaved with chain 1.
6. **Chain 5 sixth** (decomposition edge cases) — validates the goal engine.
7. **Chain 2 seventh** (food) — survival basics.
8. **Chain 3 eighth** (combat) — survival basics.

## Time Estimate

Each chain requires reading 3-8 source files cover-to-cover and tracing specific execution paths. Expect 30-60 minutes per chain. Total: ~6 hours of focused code reading.
