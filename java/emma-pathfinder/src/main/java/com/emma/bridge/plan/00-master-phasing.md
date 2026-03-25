# Master Phasing — Implementation Sequence

Every task from Plans 01, 01a, 02, and 03, ordered by dependency. The sequence is the priority.

---

## Phase 1 — Inventory & State Foundation

_All scoring, feasibility, and action decisions depend on accurate inventory and world state data._

1. Plan 03, §4 Fix 1: Wire StorageHandler → WorldState.knownStorage
2. Plan 03, §4 Fix 2: Separate immediateItemCount vs totalItemCount
3. Plan 03, §4 Problem 3: Items-in-transit tracking (drops not yet picked up invisible to inventory)
4. Plan 03, §6 Fix 1: Tool durability in goal satisfaction

---

## Phase 2 — Crafting Logic Core Fixes

_Crafting is the most-used production method; these fixes are prerequisites for reliable item acquisition._

5. Plan 01, §10 Task 1: Port `canCraftItemNow()` inventory simulation
6. Plan 01, §10 Task 2: Eager recipe book lookup in `resolveRecipe()`
7. Plan 01, §10 Task 3: Fix `slotAlts[0]` fallback

---

## Phase 3 — Shared Container Infrastructure

_Must exist before retrofitting existing actions or building new container-based actions._

8. Plan 01a, §7 / §9 Dep 1: Build `ContainerLifecycle` utility (includes container recovery from Plan 01 §8)

---

## Phase 4 — Retrofit Existing Container Actions

_Validates ContainerLifecycle with real actions; fixes furnace abandonment and crafting table recovery. SmeltItemAction is retrofitted first — it has the most deficits (no recovery, no fuel management), making it the best stress test for ContainerLifecycle. Adopt the `FurnaceConfig` pattern from Plan 01a §2 so SmeltItemAction handles furnace/blast/smoker via config objects rather than duplication._

9. Plan 01a, §9 Dep 2: Retrofit SmeltItemAction to use ContainerLifecycle
10. Plan 01a, §2 Row 2: Port `selectBestFuel()`
11. Plan 01a, §2 Row 3: Port fuel recovery (RECOVER_FUEL state)
12. Plan 01a, §2 Row 4: Material-empty guard
13. Plan 01a, §2 Row 5: Already-burning detection
14. Plan 01a, §2 Row 6: Per-recipe burn time timeout
15. Plan 01a, §9 Dep 3: Retrofit CraftItemAction to use ContainerLifecycle
16. Plan 01, §10 Task 4: Manual placement fallback for CraftItemAction when recipe book lookup fails

---

## Phase 5 — Mining & Procurement Scoring

_Better material acquisition decisions improve efficiency for all modes that follow._

17. Plan 03, §2 Fix 1: Vein-aware mining (quantity-weighted scoring)
18. Plan 03, §2 Fix 2: Vein clustering (flood-fill grouping for ore bodies)
19. Plan 03, §5 Fix 1: Stronger pickup shortcut bonus
20. Plan 03, §3 Fix 1: Quantity-aware feasibility boost (MINE)
21. Plan 03, §3 Fix 2: Continuous feasibility scoring (0.0–1.0 scale for all methods)
22. Plan 03, §3 Fix 3: Missing method feasibility (mob drops, etc.)

---

## Phase 6 — Goal Accumulation (@get Foundation)

_GetItemAction needs goals to accumulate rather than overwrite._

23. Plan 02, Migration Phase 1 Step 1: Fix SetGoalsHandler "mode": "add"
24. Plan 02, Migration Phase 1 Step 2: Verify GoalSet.addDynamicGoal()

---

## Phase 7 — GetItemAction Core

_Central orchestrator that eliminates flat-goal competition; depends on Phases 2–5._

25. Plan 02, Migration Phase 2 Step 1: Make existing actions delegate-capable
26. Plan 02, Migration Phase 2 Step 2: Build AcquisitionPlanner
27. Plan 02, Migration Phase 2 Step 3: Build GetItemAction
28. Plan 02, Migration Phase 2 Step 4: Register GetItemAction
29. Plan 02, Migration Phase 2 Step 5: Suppress GoalDecomposer derived goals for @get

---

## Phase 8 — New Production Actions

_ContainerLifecycle and GetItemAction exist; new actions plug into both systems. All production methods built together so feasibility scoring covers every action that exists._

30. Plan 01a, §3: Build StonecutterAction
31. Plan 01a, §4: Build SmithingTableAction
32. Plan 01a, §5: Build BrewingStandAction
33. Plan 01a, §6: Campfire smelting
34. Plan 01a, §8 Item 1: Register new actions in action registry
35. Plan 01a, §8 Item 2: Each action's `computeScore()` filters by its obtain method
36. Plan 01a, §8 Item 3: Feasibility boost for STONECUTTER, SMITH, BREW

---

## Phase 9 — Multi-@get & @hero Integration

_Builds on GetItemAction to handle concurrent goals and hero progression tiers._

37. Plan 02, Migration Phase 3 Step 1: Multi-@get weighted scoring
38. Plan 02, Migration Phase 3 Step 2: Shared prerequisite detection
39. Plan 02, Migration Phase 4 Step 1: Hero goals → GetItemAction instances
40. Plan 02, Migration Phase 4 Step 2: Progression-based sequencing
41. Plan 02, Migration Phase 4 Step 3: Limit active hero GetItemActions

---

## Phase 10 — @build Mode

_Most complex mode; depends on GetItemAction + AcquisitionPlanner + BuildProcess._

42. Plan 02, Migration Phase 5 Step 1: Create BuildCampaignAction
43. Plan 02, Migration Phase 5 Step 2: GATHER phase (BOM + GetItemAction delegates)
44. Plan 02, Migration Phase 5 Step 3: BUILD phase (delegate to BuildProcess)
45. Plan 02, Migration Phase 5 Step 4: Refactor SetBuildGoalHandler
46. Plan 02, Migration Phase 5 Step 5: BuildProcess testing

---

## Standing Process — Edge Case Discovery

Throughout implementation, document new issues, gaps, and edge cases in `plan/04-discoveries.md`. This is not a deferred-work parking lot — only genuinely new findings go here.

## Out of Scope — @gamer Mode

Plan 02 documents @gamer architecture (speedrun pipeline, kill_dragon decomposition) but the migration path has no implementation tasks for it. @gamer implementation is out of scope for this round; architecture is documented in Plan 02 for future work.

---

## Cross-Reference

| Source | Tasks | Phases |
|--------|-------|--------|
| Plan 01 | 4 (#5–7, #16) | 2, 4 |
| Plan 01a | 15 (#8–15, #30–36) | 3, 4, 8 |
| Plan 02 | 17 (#23–29, #37–46) | 6, 7, 9, 10 |
| Plan 03 | 10 (#1–4, #17–22) | 1, 5 |
| **Total** | **46** | **10 phases** |

_Plan 01 §9 PORT row 5 (shared container recovery) is the same implementation target as Plan 01a §7 (ContainerLifecycle) — counted once as task #8._
