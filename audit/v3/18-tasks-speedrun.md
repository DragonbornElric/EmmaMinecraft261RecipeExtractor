# Audit V3 — 18: tasks/speedrun/ (10 files)

Old: `adris/altoclef/tasks/speedrun/`
New: `goap/actions/DragonCombatAction.java` + `DestroyEndCrystalsAction.java` + `LocateStrongholdAction.java` + `ActivateEndPortalAction.java` + `BuildNetherPortalAction.java` + `EnterPortalAction.java` + `GoalDecomposer.java` (kill_dragon pipeline)

---

## 1. `BeatMinecraftTask.java` — Full beat-the-game orchestration

**Old:** Massive task that orchestrates the entire speedrun: collect resources → nether → blaze rods → ender pearls → stronghold → dragon. Uses `BeatMinecraftConfig` for thresholds. `prioritytask/` subdirectory has priority calculators for each phase.

**Emma equivalent:** `GoalDecomposer.java` kill_dragon pipeline

**Emma implementation:**
- kill_dragon prerequisites: 12 ender_eye, diamond sword/pickaxe, full diamond armor, 7 beds, 32 cooked_beef, 10 obsidian, flint_and_steel, 2 buckets, 32 torches, 1 shield
- Pipeline stages: build_nether_portal (12.0) → enter_nether (11.5) → return_overworld (11.0) → locate_stronghold (10.5) → activate_end_portal (10.0) → enter_end (9.5) → destroy_crystals (9.0) → kill_dragon_fight (8.5)
- Dimension travel injection: automatic based on item dimension requirements
- Nether item boost: blaze/ender_pearl/ender_eye +3.0 priority
- End item boost: bed +5.0, cooked_beef +2.0

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Orchestration | Explicit phase-by-phase task tree | Goal decomposition with priority scoring | Emma is more flexible (GOAP can interrupt for survival) |
| Config | BeatMinecraftConfig with thresholds | Hardcoded in GoalDecomposer | Less configurable |
| Priority calculation | Per-phase calculators in prioritytask/ | Priority offsets + feasibility boosts | Different scoring but same ordering |
| Phase ordering | Explicit sequential phases | Priority-based (higher priority = earlier) | Same effective order |

**Verdict:** IMPLEMENTED — Different architecture but same pipeline.

---

## 2. `DragonBreathTracker.java`

**Old:** Tracks dragon breath timing for safe bed placement. Dragon phase detection for perch timing.

**Emma equivalent:** `DragonCombatAction.java` checks dragon phase directly

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Breath tracking | Dedicated tracker | Direct phase check per tick | Emma doesn't predict breath timing, just reacts |
| Phase detection | Separate tracker | Inline in DragonCombatAction | Same info, different structure |

**Verdict:** IMPLEMENTED — Inline in DragonCombatAction.

---

## 3. `KillEnderDragonTask.java` + `KillEnderDragonWithBedsTask.java`

**Old:** Melee combat with timed attacks. Bed explosion strategy with breath timing. Perch detection. Crystal destruction prerequisite.

**Emma equivalent:** `DragonCombatAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Bed strategy | Bed placement during perch + detonation with breath timing | Place at (0,64,-1), detonate when dragon < 4.0 blocks | Emma doesn't use breath timing (may take more damage) |
| Melee fallback | Separate task | Same action switches strategy when out of beds | Unified |
| Fixed positions | Not described | PORTAL_CENTER=(0,64,0), BED_PLACE=(0,64,-1), STAND=(0,64,-3) | Emma uses hardcoded optimal positions |
| Perch detection | Dragon phase tracking | Checks LANDING/SITTING_FLAMING/SITTING_SCANNING/SITTING_ATTACKING | Same phases |
| Crystal prerequisite | Must destroy crystals first | Score = 0 if crystals > 0 | Same |
| Retreat | Not described | 8 blocks for 15 ticks after detonation | Explicit retreat phase |
| Melee combat | Timed attacks | Sprint-attack on cooldown | Similar |

**Verdict:** IMPLEMENTED — Comprehensive dragon fight with bed + melee strategy.

---

## 4. `OneCycleTask.java` + `WaitForDragonAndPearlTask.java`

**Old:** One-cycle kill strategies (single-perch kill). Ender pearl throw + bed timing.

**Emma equivalent:** Not present — DragonCombatAction uses multi-cycle approach

**Verdict:** NOT_NEEDED — Multi-cycle is safer and still effective.

---

## 5. `UselessItems.java` — Items safe to discard

**Old:** Predefined list of items safe to throw away when inventory is full. Used by EnsureFreeInventorySlotTask.

**Emma equivalent:** `StoreItemsAction.java` keep logic (keeps food, tools, shield, armor). No discard.

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Discard list | Explicit useless items list | No discard — deposits or ignores | When no container nearby and inventory full, Emma deadlocks |
| Free slot logic | Throw away useless items | Only deposits in containers | Can't free space in the field |

**Verdict:** GAP — No item discard mechanism for field inventory management.

---

# Audit V3 — 12: tasks/construction/ (14 files)

Old: `adris/altoclef/tasks/construction/`
New: `goap/actions/BuildNetherPortalAction.java` + `BuildStructureAction.java` + `EstablishBaseAction.java` + `PlaceTorchAction.java`

---

## 1. `PlaceBlockTask.java` + `PlaceBlockNearbyTask.java` — Block placement

**Old:** Place block at specific coordinates. PlaceBlockNearbyTask finds nearby valid position.

**Emma equivalent:** Inline in actions (CraftItemAction table placement, SmeltItemAction furnace placement, BuildNetherPortalAction frame placement)

**Verdict:** IMPLEMENTED — Inline in actions, not a separate utility.

## 2. `DestroyBlockTask.java` — Break specific block

**Old:** Navigate to block + break it.

**Emma equivalent:** `GoapNavHelper.mineProcess().mineByName()` in various actions

**Verdict:** IMPLEMENTED

## 3-4. `ConstructNetherPortalBucketTask.java` + `ConstructNetherPortalObsidianTask.java` + `ConstructNetherPortalSpeedrunTask.java`

**Old:** Multiple portal construction strategies: bucket method (lava+water), direct obsidian placement, speedrun optimized.

**Emma equivalent:** `BuildNetherPortalAction.java`

**Emma implementation:**
- 10-block obsidian frame: bottom beam → left column → right column → top beam
- Site finding: ring search r=2-16, 4×5 clearance check, solid ground
- Ground detection: Y ± 5 search
- Navigation: 300 tick timeout, 4.0 arrival
- Per-block placement: navigate + right-click with 100 tick timeout
- Ignition: right-click bottom frame with flint_and_steel, verify NETHER_PORTAL block

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Bucket method | Lava + water → obsidian formation | Not present | Requires pre-mined obsidian only |
| Speedrun method | Optimized rapid placement | Not present | Only one build strategy |
| Site validation | Various checks per method | Ring search + clearance check | Different site selection |
| Frame layout | Standard 4×5 | Standard 4×5 (same) | Same |
| Ignition | Flint and steel | Flint and steel | Same |

**Verdict:** PARTIAL — Direct obsidian placement works. Bucket method (critical for early game when no diamond pick) is missing.

## 5-6. `ClearLiquidTask.java` + `ClearRegionTask.java`

**Old:** Clear liquid blocks or all blocks in a region.

**Emma equivalent:** Not present

**Verdict:** NOT_NEEDED — Not needed for autonomous survival/dragon kill.

## 7. `PlaceObsidianBucketTask.java`

**Old:** Place obsidian by pouring lava then water on it.

**Emma equivalent:** Not present (no bucket filling action)

**Verdict:** GAP — Depends on bucket filling which is also missing.

## 8. `ProjectileProtectionWallTask.java`

**Old:** Builds wall for projectile protection when low health.

**Emma equivalent:** Not present

**Verdict:** GAP — No emergency wall building.

## 9. `PutOutFireTask.java`

**Old:** Breaks fire blocks to extinguish them.

**Emma equivalent:** EnvironmentalHazardAction flees from fire (doesn't break it)

**Verdict:** PARTIAL — Flees but doesn't extinguish.

## 10. `ConstructIronGolemTask.java`

**Old:** Builds iron golem from iron blocks + carved pumpkin.

**Emma equivalent:** Not present

**Verdict:** NOT_NEEDED — Not needed for survival/dragon.

## 11. `BuildSchematicTask.java` + `PlaceStructureBlockTask.java`

**Old:** Build from Baritone schematic.

**Emma equivalent:** `BuildStructureAction.java` (uses schematic parser + build plan from Python side)

**Verdict:** IMPLEMENTED — Different system but building capability exists.

---

# Audit V3 — 14: tasks/misc/ (6 files)

## 1. `SleepThroughNightTask.java` + `PlaceBedAndSetSpawnTask.java`

**Old:** Find bed, navigate, sleep. Place bed + set spawn.

**Emma equivalent:** `SleepAction.java` — full 11-phase state machine with bed finding, placement, navigation, sleeping, wakeup, collection.

**Verdict:** IMPLEMENTED — Emma's is more sophisticated (placement algorithm, endinv extraction, all 16 bed colors).

## 2. `EquipArmorTask.java`

**Old:** Equips armor pieces to proper slots.

**Emma equivalent:** `ArmorEquipReflex.java` + `EquipBestArmorAction.java`

**Verdict:** IMPLEMENTED — Reflex for reactive equip + action for proactive upgrade.

## 3. `LootDesertTempleTask.java` + `RavageDesertTemplesTask.java` + `RavageRuinedPortalsTask.java`

**Old:** Structure-specific looting tasks.

**Emma equivalent:** Not present

**Verdict:** GAP — No structure-specific looting (desert temples, ruined portals).
