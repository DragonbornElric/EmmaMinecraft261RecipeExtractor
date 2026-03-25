# Audit V3 — 15: tasks/movement/ (34 files)

Old: `adris/altoclef/tasks/movement/`
New: `goap/actions/NavigateToAction.java` + `ExploreAction.java` + `FleeFromAction.java` + `EnterPortalAction.java` + `LocateStrongholdAction.java` + `BuildNetherPortalAction.java` + `ActivateEndPortalAction.java` + `GoapNavHelper.java`

---

## Core Navigation

### 1. `GetToBlockTask.java` — Navigate to specific block

**Old:** Baritone `GoalBlock(position)`. Dimension check → DefaultGoToDimensionTask. Finished timeout: 10 seconds → wander + mark unreachable.

**Emma equivalent:** `NavigateToAction.java` + `GoapNavHelper.tickNavigateToBlock()`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Pathfinder | Baritone GoalBlock | Emmatone GoalBlock | Different pathfinder, same goal type |
| Dimension check | DefaultGoToDimensionTask | GoalDecomposer injects dimension goals | Same concept, different routing |
| Timeout | 10 seconds → wander + mark unreachable | 200 ticks (10s) → cancel | Same timeout, Emma doesn't wander after |
| Unreachable marking | blockScanner.requestBlockUnreachable() | state.unreachableBlocks map | Different tracking, same concept |
| MIN_DISTANCE | Not specified | 3.0 blocks | Emma doesn't navigate to very close positions |

**Verdict:** IMPLEMENTED

### 2-6. `GetToXZTask`, `GetToYTask`, `GetToChunkTask`, `GetCloseToBlockTask`, `GetWithinRangeOfBlockTask`

**Old:** Various Baritone goal types (GoalXZ, GoalYLevel, GoalChunk, GoalNear).

**Emma equivalent:** All handled by `GoapNavHelper.pathTo()` with appropriate Emmatone goal types.

**Verdict:** IMPLEMENTED — Emmatone has equivalent goal types.

---

## Flee/Escape

### 7. `RunAwayFromHostilesTask.java` — Flee from all hostiles

**Old:** `GoalRunAwayFromHostiles(mod, distanceToRun)`. Custom cost function. Optional skeleton inclusion flag.

**Emma equivalent:** `FleeFromAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Flee distance | Configurable parameter | FLEE_DISTANCE = 30.0 (hardcoded) | Emma always flees 30 blocks |
| Skeleton filter | Optional exclusion | No filter — flees from all Monster | No skeleton-specific behavior |
| Flee goal | GoalRunAwayFromHostiles (custom) | GoalRunAway (Emmatone built-in) | Similar |
| Force cancel | forceCancel() on Baritone | cancelEverything() on Emmatone | Same concept |

**Verdict:** IMPLEMENTED — Hardcoded distance vs configurable.

### 8. `RunAwayFromCreepersTask.java`

**Old:** Uses `getCreeperSafety()` — `distance * 0.2 if fusing`. Custom cost function weights fusing creepers much more heavily.

**Emma equivalent:** `FleeFromAction.java` with creeper bonus scoring.

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Creeper safety | Continuous: `distance * 0.2 if fuse > 0.001` | Binary: creeperBonus = 1.0 if fusing < 8 blocks, else 0.4 | Old was continuous, Emma is step function |
| Creeper-specific flee | Separate task with custom creeper cost | Integrated into FleeFromAction scoring | Same effect, different mechanism |

**Verdict:** IMPLEMENTED — Different creeper urgency formula but functionally equivalent.

### 9. `RunAwayFromEntitiesTask.java`

**Old:** Generic flee from entity list. Custom penalty weight. XZ-only option.

**Emma equivalent:** `FleeFromAction.java` (always 3D, no XZ-only option)

**Verdict:** IMPLEMENTED

### 10. `DodgeProjectilesTask.java`

**Old:** `GoalDodgeProjectiles(mod, horizontalDist, verticalDist)` — Baritone goal with horizontal + vertical dodge distances.

**Emma equivalent:** `ProjectileDodgeAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Dodge mechanism | Baritone custom goal (path around) | Strafe left/right + sprint | Old pathfinds around; Emma strafes |
| Dodge direction | Path-based (3D) | Perpendicular to projectile velocity | Emma is faster but less sophisticated |
| Horizontal/vertical | Configurable parameters | Fixed strafe | No configurable distances |
| Projectile prediction | ProjectileHelper trajectory calc | dot(velocity, toPlayer) > 0.3 | Old had trajectory prediction, Emma only checks direction |

**Missing:** No trajectory-predicted dodge. Emma reacts to direction only, not predicted impact point.

**Verdict:** PARTIAL — Strafing works but no trajectory prediction.

### 11. `EscapeFromLavaTask.java`

**Old implementation:**
- **Strength:** 100 (heuristic weight for lava avoidance)
- **Eating while in lava:** scores food by saturation (spider eye excluded), eats best food
- **Heuristic:** lava=+100, lava-adjacent=+50, water=-100
- **Fallback (fully surrounded):** searches 4×4×4 cube for solid block, places Netherrack or throwaway to create escape path
- **Block placement:** checks reach, finds non-UP face, places block

**Emma equivalent:** `EnvironmentalHazardAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Escape method | Heuristic-based Baritone pathing with custom costs | `GoalRunAway(10, currentPos)` via Emmatone | Old had nuanced lava-avoidance heuristic; Emma just runs away |
| Eating in lava | Explicit food scoring (saturation × 10, exclude spider eye) | Not present | Old tried to heal while escaping lava |
| Block placement fallback | Places blocks to create escape path when fully surrounded | Not present | If fully surrounded by lava, Emma has no escape strategy |
| Lava vs water heuristic | Water = -100 (attracts toward water) | No heuristic | Old pathfinding preferred water direction |

**Verdict:** PARTIAL — Basic escape works but missing eating-in-lava and block placement fallback.

### 12. `GetOutOfWaterTask.java`

**Old:** Jump + place blocks below when at water surface. Uses throwaway blocks. Custom water-escape heuristic.

**Emma equivalent:** `EnvironmentalHazardAction.java` drowning response

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Block placement | Places blocks below to create platform | Not present | Just jumps + swims forward |
| Heuristic | Water=1, adjacent=0.5 (path toward shore) | GoalRunAway from current pos | Different escape pattern |

**Verdict:** PARTIAL — Basic swimming escape but no block placement.

---

## Exploration

### 13. `SearchChunksExploreTask.java`

**Old:** Listens for ChunkLoadEvent, marks chunks explored, avoids re-searching. Custom chunk override for prioritization.

**Emma equivalent:** `ExploreAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Chunk tracking | Marks explored chunks, avoids revisiting | No visited-area tracking | May repeatedly explore same areas |
| Target generation | ChunkLoadEvent-based, with override | Random angle + 50-80 block distance | Less systematic |
| Underground awareness | Not described | Checks for underground ore → targets Y=16-48 | Emma has mining-depth awareness |
| Timeout | Event-driven | 400 ticks per segment | Emma is time-bounded |

**Verdict:** PARTIAL — Has mining-depth awareness but no visited-area tracking.

### 14. `TimeoutWanderTask.java`

**Old:** Baritone explore process. Stuck detection via MovementProgressChecker. Fail counter > 10 → finished. Kills 1-block-away mobs. SafeRandomShimmyTask on stuck.

**Emma equivalent:** `ExploreAction.java` random target generation

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Stuck detection | MovementProgressChecker + fail counter | UnstuckAction (separate action) | Different mechanism |
| Mob clearing | Kills nearby mobs blocking path | Not present | Stuck on mobs |
| Distance limit | Configurable distanceToWander | Fixed 50-80 blocks | Less configurable |

**Verdict:** IMPLEMENTED — Different mechanism but same purpose.

---

## Dimension Travel

### 15. `DefaultGoToDimensionTask.java`

**Old:** Overworld↔Nether via portal entry or construction. Nether→End via overworld intermediate. Checks for portals within 2000 blocks.

**Emma equivalent:** `GoalDecomposer` dimension injection + `BuildNetherPortalAction` + `EnterPortalAction`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Portal search | Scans 2000-block radius | PortalRegistry (persistent storage) | Emma uses saved portal locations |
| Portal construction | ConstructNetherPortalBucketTask | BuildNetherPortalAction | Different implementation |
| Nether→End routing | Goes to Overworld first | GoalDecomposer creates intermediate goals | Same |

**Verdict:** IMPLEMENTED

### 16. `EnterNetherPortalTask.java`

**Old:** Portal timeout 10 seconds. Standable portal check (portal block + solid below). Wander 5 blocks if timeout. Build new portal if none found.

**Emma equivalent:** `EnterPortalAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Portal timeout | 10 seconds | 200 ticks (10 sec) for dimension change | Same |
| Standable check | Portal block + solid ground below | Not described — navigates to portal block | May try to stand in mid-air portal |
| Wander on timeout | Wanders 5 blocks, re-enters | Not present — deactivates | May fail if first entry attempt doesn't work |
| Build fallback | Constructs new portal if none | GoalDecomposer creates build_nether_portal goal | Same outcome, different routing |
| Exit portal (End→Overworld) | Custom | Hardcoded (0, 62, 0) | Emma knows exit portal location |

**Verdict:** IMPLEMENTED — Minor differences in timeout handling.

### 17. `GoToStrongholdPortalTask.java` + `LocateStrongholdCoordinatesTask.java`

**Old implementation (LocateStrongholdCoordinatesTask):**
- EYE_RETHROW_DISTANCE = 10, SECOND_EYE_THROW_DISTANCE = 30
- **Triangulation:** 2D XZ intersection of two ender eye trajectories
- Eye throw: stores origin, tracks delta via entity movement
- Angle check: if 2nd eye angle ≥ 1st, assumes different stronghold → rethrow
- Math: `t2 = (d1.z*(s2.x-s1.x) - d1.x*(s2.z-s1.z)) / (d1.x*d2.z - d1.z*d2.x)`

**Emma equivalent:** `LocateStrongholdAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Triangulation math | Same 2D XZ line intersection | Same formula | Identical |
| Perpendicular move | Implicit (walks to second position) | Explicit: 40 blocks perpendicular to ray 1 | Emma has explicit perpendicular movement |
| Parallel ray check | Not described | `|denom| < 0.001` check | Emma detects degenerate case |
| Eye tracking | Stores entity delta, computes direction | Scans 64-block radius for EyeOfEnder entity | Same concept |
| Minimum eyes | 12 | 2 (minimum for triangulation) | Emma needs fewer eyes |
| FastTravel | Uses FastTravelTask (nether routing) | Direct Emmatone pathfinding | No nether shortcut for stronghold travel |
| Result storage | Returns coordinates | Sets worldState.strongholdKnown + X/Z | Persistent in state |

**Verdict:** IMPLEMENTED — Same triangulation algorithm.

---

## Special Movement

### 18. `MLGBucketTask.java`

**Old (extremely complex):**
- Cone raycast: height=40, pitch=25°, 8 pitch divisions, 6-20 yaw divisions
- Landing block priority: water > lava(with protection) > highest-Y block > closest-XZ
- Projectile motion prediction: `ticksToTravel = (-v + sqrt(v² + 2g*d)) / g`
- PD loop for forward/sideways velocity control
- 5 raycasts per tick (center + 4 corners)
- Jump management per block type (lava=always, vines=when inside)
- Clutch items: HAY_BLOCK, TWISTING_VINES
- Fall damage calculation with vulnerability/resistance modifiers

**Emma equivalent:** `MLGBucketReflex.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Landing search | Cone raycast 40-block radius, 25° pitch, 6-20 yaw divisions | Straight down only | Old could clutch on angled falls; Emma only clutches directly below |
| Block priority | Water > protected lava > highest Y > closest XZ | Water only (chorus fruit fallback) | Emma can only clutch with water bucket |
| Motion prediction | Full projectile physics with PD velocity control | None — looks straight down | Old steered toward landing pads; Emma falls passively |
| Clutch items | Water bucket + hay block + twisting vines | Water bucket + chorus fruit | Different fallback items |
| Fall damage calc | Physics-based with enchant modifiers | None | Emma always attempts MLG regardless of fall height |
| Complexity | ~500 lines, most complex task in old code | ~100 lines, simple state machine | Massively simplified |

**Verdict:** PARTIAL — Basic straight-down water bucket works but no cone search, no steering, no alternative landing blocks.

### 19. `FollowPlayerTask.java`

**Old:** Follow distance configurable (default 2). GetToEntityTask if player loaded, GetToBlockTask if not. Fails if at last pos but player gone.

**Emma equivalent:** None — no follow-player action exists

**Verdict:** GAP — Cannot follow another player continuously.

### 20. `PickupDroppedItemTask.java`

**Old:** Progress checker + stuck shimmy. Item fall prediction (adjusts Y for falling items). Mining requirement (stone pickaxe) as prerequisite. Annoying block detection (vines, grass, etc.). Free inventory slot check on collision.

**Emma equivalent:** `PickupItemAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Fall prediction | Adjusts Y for falling items (-1 or -2) | No prediction — targets current pos | May navigate to mid-air item |
| Mining prerequisite | Gets stone pickaxe first | Not present | May try to pick up items in hard-to-reach areas without tools |
| Annoying blocks | Detects vines/grass blocking path | UnstuckAction handles generically | Less specific |
| Free inventory check | Ensures slot before collision | Not present | Item may drop back if inventory full |
| Stuck detection | MovementProgressChecker + shimmy | UnstuckAction | Different mechanism |
| Shortcut scoring | N/A | computeShortcutBonus (chain depth analysis) | Emma prioritizes items closer to goal |

**Verdict:** PARTIAL — Has chain-depth scoring but missing fall prediction and inventory checks.

---

## Remaining Files (Brief)

| File | Old Logic | Emma | Verdict |
|------|-----------|------|---------|
| IdleTask | Sleep if can, else nothing | SleepAction is separate | IMPLEMENTED |
| DefenseTask | Move to defensive position | Not present (FleeFromAction is different) | NOT_NEEDED |
| FastTravelTask | Nether coordinate mapping (÷8) | Not present | GAP — no nether highway transit |
| GoInDirectionXZTask | Walk in compass direction | Not present | NOT_NEEDED |
| SafeRandomShimmyTask | Random short movement for unsticking | UnstuckAction phase 1 | IMPLEMENTED |
| ChunkSearchTask / SearchChunkForBlockTask | Search specific chunks for blocks | GoapTicker block scanner | IMPLEMENTED (different approach) |
| SearchWithinBiomeTask | Biome-targeted search | Not present | GAP — no biome-aware exploration |
| CustomBaritoneGoalTask | Execute arbitrary Baritone goal | GoapNavHelper wraps Emmatone | IMPLEMENTED |
| ThrowEnderPearlSimpleProjectileTask | Throw ender pearl at location | Not present | GAP |

---

## Summary

| Category | Verdict | Key Gaps |
|----------|---------|----------|
| Core navigation | IMPLEMENTED | Minor differences |
| Flee/escape | IMPLEMENTED | Hardcoded distances |
| Dodge | PARTIAL | No trajectory prediction |
| Lava/water escape | PARTIAL | No block placement, no eating-in-lava |
| Exploration | PARTIAL | No visited-area tracking |
| Dimension travel | IMPLEMENTED | Same algorithms |
| MLG | PARTIAL | No cone search or steering |
| Following | GAP | No player following |
| Biome search | GAP | No biome-aware exploration |
| Nether highway | GAP | No ÷8 coordinate transit |
| Ender pearl throw | GAP | No projectile items |
