# Audit V3 — 16: tasks/resources/ (45+ files) + wood variants (8 files)

Old: `adris/altoclef/tasks/resources/` (45+ per-item collection classes)
New: Data-driven via `ItemRecipeRegistry` (3121 entries) + 6 generic GOAP actions: `MineBlockAction`, `HuntMobAction`, `CollectFoodAction`, `PickupItemAction`, `EntityInteractAction`, `TransformBlockAction`

**Architectural note:** AltoClef had one Java class per resource type (CollectIronIngotTask, CollectPlanksTask, etc.). Emma replaces ALL of these with a data-driven approach: `ItemRecipeEntry` records describe how to obtain each item (MINE, CRAFT, SMELT, MOB_DROP, INTERACT, TRANSFORM), and generic GOAP actions execute those recipes. This section audits what specific logic from each old class survived the data-driven conversion.

---

## GROUP A: Mining-Based Collection (old files that mine blocks for items)

### Files: MineAndCollectTask, CollectPlanksTask, CollectSticksTask, CollectSaplingsTask, CollectFlowerTask, CollectHayBlockTask, CollectCoarseDirtTask, CollectCropTask, CollectWheatTask, CollectWheatSeedsTask, CollectCocoaBeansTask, GetBuildingMaterialsTask, CollectAmethystBlockTask, CollectBlockByOneTask, CollectDripstoneBlockTask, CollectRedSandstoneTask, CollectSandstoneTask, CollectQuartzTask, CollectNetherBricksTask

**Old implementation (MineAndCollectTask — base for all mining):**
- Inner class `MineOrCollectTask` finds nearest block to mine OR nearest dropped item
- **Drop priority:** items on ground get +10 distance bonus over blocks (prefer pickup)
- **Block blacklist:** unreachable blocks blacklisted after failure (75 attempt threshold)
- **Progress checker:** detects stuck mining, force-cancels pathfinding
- **Tool protection:** protects all pickaxes from disposal
- **Tool auto-equip:** equips better pickaxe from cursor if available

**Old implementation (CollectPlanksTask — special logic):**
- If have logs: craft planks in inventory (1 log → 4 planks)
- Scan range: close (10 blocks) if have enough logs, far (50 blocks) otherwise
- Nether logs: supports crimson/warped for nether wood

**Old implementation (CollectSticksTask — special logic):**
- Priority: bamboo craft (2→1) > dead bush mining (within 20 blocks) > plank craft (2→4)
- Dynamic range: close (10) if have log potential, far (35) otherwise

**Old implementation (CollectCropTask — special logic):**
- **Maturity tracking:** `_wasFullyGrown` set tracks crop maturity across chunk loads
- Only breaks mature crops (prevents yield loss)
- **Replanting:** replants seeds if mod setting enabled

**Old implementation (CollectCocoaBeansTask — special logic):**
- **Biome search:** searches jungle biome specifically
- **Maturity:** only harvests at age stage 2

**Emma equivalent:** `MineBlockAction.java` + `ItemRecipeRegistry` MINE entries

**Emma implementation:**
- **Block selection:** iterates all have_item goals with MINE obtain_method, calls `itemToMineableBlocks()` for block list, scans `state.nearbyBlocks` (32-block radius, updated every 20 ticks)
- **Variant selection (pickBestVariant):** picks variant with best average distance among top N blocks
- **Proximity scoring:** `1.0 / (1.0 + distance / 16.0)`
- **Tool requirement:** per-block `MineBlockInfo.getRequirement()` + `getToolType()`, scans inventory for matching tier tool
- **Tool breakage detection:** per-tick check if required tool still exists, cancels mine on breakage
- **Unreachable tracking:** marks blocks that fail to break as unreachable (time-based expiry)
- **Execution:** calls `GoapNavHelper.mineProcess().mineByName(0, blockName)` (Emmatone handles pathfinding + breaking)
- **No drop priority over blocks** — PickupItemAction is a separate action that competes by score
- **No progress checker** — relies on Emmatone's mine process + unreachable tracking
- **No maturity check for crops** — ItemRecipeEntry has CROP entries but MineBlockAction doesn't check crop age
- **No replanting**
- **No biome-specific search**
- **No dynamic range adjustment** (always 32-block scan)

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Drop vs mine priority | Drops get +10 distance bonus | Separate PickupItemAction competes by GOAP score | Similar outcome but different mechanism |
| Block blacklisting | After 75 failed attempts | After single mine-complete + block-still-exists | Emma is more aggressive about blacklisting |
| Crop maturity | Tracks `_wasFullyGrown`, only breaks mature | No maturity check in MineBlockAction | Breaks immature crops, losing yield |
| Crop replanting | Replants seeds | Not present | Crops not replanted after harvest |
| Biome search | CollectCocoaBeansTask searches jungle biome | No biome-aware search | Can't target specific biomes for resources |
| Dynamic scan range | 10-50 blocks depending on inventory | Fixed 32 blocks (scanner interval) | Less adaptive |
| Plank crafting from logs | CollectPlanksTask inlines craft decision | GoalDecomposer creates separate craft goal | Different orchestration, same result |
| Stick priority (bamboo/bush/plank) | Explicit 3-tier priority | GoalDecomposer picks based on dependencies | May not prefer bamboo sticks |
| Tool protection | Protects all pickaxes from disposal | Not present | Pickaxes could be deposited by StoreItemsAction (but it keeps 1 per tool category) |
| Progress checker | 75-attempt stuck detection | Emmatone mine process + unreachable marking | Different mechanism |

**Missing edge cases:**
- [ ] No crop maturity check — breaks immature wheat, potatoes, etc.
- [ ] No crop replanting
- [ ] No biome-specific resource search (jungle for cocoa, etc.)
- [ ] No dynamic scan range based on inventory state

**Verdict:** PARTIAL — Core mining works via data-driven entries but missing crop maturity, replanting, and biome awareness.

---

## GROUP B: Smelting-Based Collection

### Files: CollectIronIngotTask, CollectGoldIngotTask, CollectGoldNuggetsTask + SmeltInFurnaceTask (covered in 11-tasks-container.md)

**Old implementation (CollectIronIngotTask):** Delegates to SmeltInFurnaceTask(raw_iron → iron_ingot)

**Old implementation (CollectGoldIngotTask):**
- **Dimension-dependent strategy:**
  - Overworld: smelt raw gold
  - Nether: mine nether gold ore → nuggets, craft 9 nuggets → 1 ingot
  - Other: go to overworld
- **Nugget fallback:** if have enough nuggets (count × 9), craft instead

**Emma equivalent:** `GoalDecomposer` + `SmeltItemAction` + `CraftItemAction`

**Emma implementation:**
- ItemRecipeRegistry has SMELT entries for iron_ingot (from raw_iron), gold_ingot (from raw_gold)
- ItemRecipeRegistry has CRAFT entry for gold_ingot (9 gold_nuggets)
- ItemRecipeRegistry has MINE entry for gold_nugget (from nether_gold_ore)
- GoalDecomposer resolves dependencies transitively
- **No dimension-specific strategy switching** — GoalDecomposer does inject dimension travel goals based on entry's getDimension()

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Nether gold strategy | Explicit: mine nether gold ore → nuggets → craft ingots | Data-driven: GoalDecomposer walks dependencies | Same outcome if recipe entries are correct |
| Dimension switching | Explicit per-resource | Automatic via GoalDecomposer dimension injection | More general in Emma |
| Nugget fallback | Checks nugget count before mining | GoalDecomposer checks hasItem for each dep | Same concept |

**Verdict:** IMPLEMENTED — Data-driven approach covers these cases.

---

## GROUP C: Kill-Based Collection

### Files: KillAndLootTask, CollectMeatTask, CollectBlazeRodsTask, KillEndermanTask, CollectMagmaCreamTask, CollectEggsTask, ShearAndCollectBlockTask, CollectWoolTask, CollectMilkTask, CollectHoneycombTask

**Old implementation (CollectBlazeRodsTask — complex):**
- Navigate to Nether
- Locate blaze spawner via `SearchChunkForBlockTask(Blocks.NETHER_BRICKS)`
- Camp within 4 blocks of spawner
- Kill blazes: health threshold (flee if HP ≤ 10 + ≥5 blazes), distance check (32 blocks from spawner), lava avoidance (reject blazes >11 blocks above lava), LOS check
- Fire cleanup around spawner

**Old implementation (KillEndermanTask — complex):**
- **Warped forest search:** finds twisting vines/warped nylium
- **Camping:** stays ≤40 blocks from warped forest for enderman spawns
- **Y-limit:** stays below Y 125 (Nether roof)
- **Target priority:** already-angry endermen first
- **Forced dimension:** Nether

**Old implementation (CollectMeatTask):**
- Scoring: `100 * hunger_per_meat / distance²`
- Smelts raw meat via smoker for cooked variants
- Food potential calculation

**Old implementation (CollectWoolTask):**
- Priority: break wool blocks > shear sheep > kill sheep
- Color filtering for specific dye colors
- Sheared sheep excluded from killing

**Old implementation (CollectHoneycombTask):**
- Find bee nest, place campfire below (prevents anger), get shears, wait for honey level 5, harvest

**Emma equivalent:** `HuntMobAction.java` + `HuntHostileAction.java` + `CollectFoodAction.java` + `EntityInteractAction.java`

**Emma implementation:**
- **HuntMobAction mob registry:** Cow, Pig, Sheep, Chicken, Rabbit, MushroomCow, Goat, Horse, Donkey, Llama, Turtle, Squid, GlowSquid, Cod, Salmon, Blaze, EnderMan (17 types)
- **HuntMobAction algorithm:** find nearest mob in 32-block radius, path to it, attack when within 3.5 blocks. Simple loop with 400-tick timeout.
- **HuntHostileAction:** scans for Monster instances in 32-block radius. Gear-gated scoring. Patrol with random ±32 offset when no targets.
- **EntityInteractAction:** supports shearing sheep, milking cows, mooshroom stew, beehive harvesting. Has EQUIP → NAVIGATE → INTERACT state machine. Beehive checks for campfire below (±5 blocks).
- **CollectFoodAction:** 6-tier priority: ground items > crops > food animals > multi-step food > navigate to base > navigate to surface. Crops via Emmatone FarmProcess.

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Blaze rod strategy | Spawner camping (4 blocks), lava avoidance, LOS check, health-based flee | HuntMobAction: simple nearest-mob chase, no spawner awareness | No spawner camping, no lava avoidance, no health-based flee — blazes in lava are targeted |
| Fortress finding | SearchChunkForBlockTask(Nether Bricks) | No structure finding | Can't find Nether Fortress — wanders randomly |
| Enderman strategy | Warped forest search, Y-limit, angry-first targeting | HuntMobAction: simple nearest enderman | No biome awareness, no Y-limit, no angry-first |
| Meat scoring | hunger_per_meat / distance² | goal.priority × proximity factor | Different formula — Emma doesn't weight by hunger value |
| Meat smelting | Explicit smoker smelting of raw meat | GoalDecomposer creates smelt goals for cooked variants | Same outcome, different orchestration |
| Wool collection | Break > shear > kill priority, color filtering | EntityInteractAction for shearing, MineBlockAction for blocks, HuntMobAction for killing | No explicit priority order, no color filtering |
| Honeycomb | Find nest, place campfire, wait for honey level 5 | EntityInteractAction: checks campfire exists, interacts | Emma doesn't wait for honey level or place campfire |
| Egg collection | Waits near chickens | No waiting logic — only PickupItemAction | Can't collect eggs (no entity produces them on demand) |
| Mob types mapped | Varied per task (specific to each resource) | 17 types in HuntMobAction registry | Missing: magma_cube, slime, witch, ghast, hoglin, piglin, guardian, elder_guardian, shulker, phantom |
| Entity blacklisting | Blacklists unreachable mobs | Not present | Loops on unreachable mobs (stuck behind fence, in tree) |

**Missing edge cases:**
- [ ] No spawner camping for blaze rods — can't efficiently farm blazes
- [ ] No fortress/structure finding — can't navigate to Nether fortresses
- [ ] No warped forest search for enderman farming
- [ ] No entity blacklisting — loops on unreachable mobs
- [ ] No crop maturity awareness in food collection
- [ ] No color-specific wool collection
- [ ] No honey level check for beehive harvesting
- [ ] No campfire placement for safe beehive interaction
- [ ] Missing 10+ mob types from hunt registry

**Verdict:** PARTIAL — Basic hunting and interaction work but missing strategic behaviors (spawner camping, structure finding, entity blacklisting) and several mob types.

---

## GROUP D: Bucket/Fluid Operations

### Files: CollectBucketLiquidTask, CollectObsidianTask

**Old implementation (CollectBucketLiquidTask):**
- Find source liquid blocks (not flowing)
- Clear obstacles above liquid
- Anti-spill: checks adjacent lava when collecting water without fire resistance
- Safety: rejects liquid at player position
- Source detection via proper fluid handling
- Progress checker + blacklist for unreachable liquids
- 150ms cooldown between interactions
- Dimension handling: switches to overworld for water in Nether

**Old implementation (CollectObsidianTask):**
- Priority: mine existing obsidian (within 800 blocks) > trade with piglins in Nether > create via lava+water in overworld
- Diamond pickaxe prerequisite check
- Nether strategy: piglin trading (11.475 gold per obsidian)
- Overworld strategy: place water above lava source → collect formed obsidian
- Dynamic placement repositioning

**Emma equivalent:** `BlockInteraction.tryCollectFluid()` exists but no GOAP action triggers it. No obsidian creation action.

**Emma implementation:**
- `BlockInteraction.tryCollectFluid()` and `tryDumpFluid()` exist (same two-tick pattern as old)
- **No bucket-filling GOAP action** — no action scores for "fill bucket with water/lava"
- **No obsidian creation action** — no water-on-lava placement logic
- GoalDecomposer has MINE entries for obsidian (natural mining) but no CRAFT/TRANSFORM for creation

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Bucket filling | Full task with safety, blacklisting, anti-spill | API exists but no GOAP action triggers it | Cannot autonomously fill water/lava buckets |
| Obsidian creation | Lava+water placement strategy | Not present | Must mine natural obsidian only (very rare) |
| Piglin trading for obsidian | In Nether: gold → piglins → obsidian | Not present | No piglin trading at all |
| Diamond pickaxe prereq | Explicit SatisfyMiningRequirementTask check | GoalDecomposer creates tool prereq goals | Same outcome for mining, but no obsidian creation path |

**Missing edge cases:**
- [ ] No bucket-filling action — cannot fill water or lava buckets autonomously
- [ ] No obsidian creation (water + lava) — blocks nether portal construction when no natural obsidian exists
- [ ] No piglin trading — reliable nether source for obsidian/pearls unavailable

**Verdict:** GAP — Bucket filling and obsidian creation are completely absent as autonomous actions.

---

## GROUP E: Special Resource Collection

### Files: CollectFlintTask, TradeWithPiglinsTask, CarveThenCollectTask, ShearAndCollectBlockTask, CollectStrippedLogTask

**Old implementation (CollectFlintTask):**
- Mine gravel for random flint drops (10% chance)
- If no nearby gravel but have in inventory: place gravel → re-mine (recycle loop)
- If no gravel anywhere: collect gravel first

**Old implementation (TradeWithPiglinsTask):**
- Collect gold → find piglins → equip gold → right-click → collect drops
- Trading timeout: 2 seconds per piglin
- Blacklist failed traders
- Hoglin avoidance: abort if hoglin ≤64 blocks
- Baby filter, line-of-sight check

**Old implementation (CarveThenCollectTask):**
- Shears + pumpkin → carved_pumpkin (right-click interaction)

**Old implementation (CollectStrippedLogTask):**
- Axe + log → stripped log (right-click interaction)

**Emma equivalent:**
- Flint: `MineBlockAction` with MINE entry for flint (from gravel). No recycle loop.
- Piglins: Not present. No trading action.
- Carving: `TransformBlockAction` with TOOL_USE type. Needs TRANSFORM entry in registry.
- Stripping: `TransformBlockAction` with TOOL_USE type. Has entries for stripped logs.

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Flint recycling | Place+remine gravel loop | Mine only, no recycling | Much slower flint collection (10% drop rate, no reuse) |
| Piglin trading | Full implementation with safety | Not present | No gold-for-items trading |
| Pumpkin carving | Explicit carve task | TransformBlockAction TOOL_USE (if entry exists) | Need to verify TRANSFORM entry for pumpkin |
| Log stripping | Explicit strip task | TransformBlockAction TOOL_USE | Works if TRANSFORM entries are correct |

**Missing edge cases:**
- [ ] No gravel recycling for flint — 10× slower flint collection
- [ ] No piglin trading — blocks reliable nether ender pearl and obsidian acquisition

**Verdict:** PARTIAL — Transform actions work for stripping/carving. Flint recycling and piglin trading are missing.

---

## GROUP F: Variant Crafting (wood/* files)

### Files: CraftWithMatchingMaterialsTask, CraftWithMatchingPlanksTask, CraftWithMatchingWoolTask, CraftWithMatchingStrippedLogsTask, CollectBoatTask, CollectFenceTask, CollectFenceGateTask, CollectHangingSignTask, CollectSignTask, CollectWoodenButtonTask, CollectWoodenDoorTask, CollectWoodenPressurePlateTask, CollectWoodenSlabTask, CollectWoodenStairsTask, CollectWoodenTrapDoorTask

**Old implementation (CraftWithMatchingMaterialsTask):**
- `sameMask` boolean array: which recipe slots must use same material
- Calculates majority material (most available matching item)
- If sufficient: crafts with uniform material
- If insufficient: collects more matching material

**Old implementation (CraftWithMatchingPlanksTask):**
- Counts logs as 4× planks: `logs_count * 4 + planks_count`
- Auto-converts logs to planks when short
- Selects majority plank type

**Emma equivalent:** `TagGroups.java` + `CraftItemAction.resolveRecipe()` + `ItemRecipeRegistry` slot alternatives

**Emma implementation:**
- **TagGroups:** 12 plank variants, 11 log variants, etc. (hardcoded groups)
- **Recipe resolution:** `resolveRecipe()` iterates slot alternatives, picks first alternative player has via `state.hasItem()`
- **Slot alternatives:** each craft grid slot can have multiple alternatives (e.g., ["oak_planks", "spruce_planks", ...])
- **GoalDecomposer dependency collapse:** `canDeriveFromOwned()` checks if all craft ingredients present one level deep
- **Super groups:** wood_sources (logs+wood+stripped+planks), stone_sources (stone+cobblestone+deepslate+blackstone) for goal suppression

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Material matching | sameMask ensures uniform material per recipe | Slot alternatives resolved independently per slot | Emma may use MIXED plank types in one recipe (oak planks + spruce planks in same door) |
| Majority selection | Calculates which material has most available, uses that | Picks first alternative that exists in inventory per slot | May not pick the most abundant variant |
| Log-to-plank counting | Counts logs as 4× planks | GoalDecomposer walks craft dependency (1 log → 4 planks) | Same outcome via different mechanism |
| Auto-conversion | Converts logs to matching plank type | GoalDecomposer creates craft subgoal for planks | Same outcome |
| Wool color matching | CraftWithMatchingWoolTask forces single color | No wool color constraint | Beds/banners may use mixed wool colors |

**Missing edge cases:**
- [ ] No same-material enforcement across slots — recipes that need uniform planks may get mixed variants
- [ ] No majority-material optimization — may pick rare variant instead of abundant one
- [ ] No wool color matching — beds and banners can have mixed colors

**Verdict:** PARTIAL — Variant resolution works via slot alternatives but doesn't enforce uniform materials across recipe slots.

---

## GROUP G: Remaining Simple Resources

### Files: CollectBedTask, CollectEggsTask, CollectMilkTask, GetSmithingTemplateTask

| File | Old Logic | Emma | Verdict |
|------|-----------|------|---------|
| CollectBedTask | CraftWithMatchingWoolTask + break world beds | CraftItemAction + MineBlockAction (if bed entries exist) | PARTIAL — no uniform wool |
| CollectEggsTask | Wait near chickens for drops | No waiting action | GAP |
| CollectMilkTask | Get bucket, find cow, right-click | EntityInteractAction with bucket + cow | IMPLEMENTED (if INTERACT entry exists) |
| GetSmithingTemplateTask | Search bastions via nether brick blocks, loot chests | Not present — no structure search or autonomous looting | GAP |

---

## Summary: tasks/resources/ Consolidated

| Category | Files | Verdict | Key Gaps |
|----------|-------|---------|----------|
| Mining-based | 19 | PARTIAL | No crop maturity, no replanting, no biome search |
| Smelting-based | 3 | IMPLEMENTED | Data-driven covers all cases |
| Kill-based | 10 | PARTIAL | No spawner camping, no structure finding, missing mob types, no entity blacklist |
| Bucket/Fluid | 2 | GAP | No bucket filling action, no obsidian creation |
| Special resources | 5 | PARTIAL | No gravel recycling, no piglin trading |
| Variant crafting | 15 | PARTIAL | No uniform material enforcement |
| Simple resources | 4 | MIXED | Eggs GAP, smithing template GAP, others partial |
