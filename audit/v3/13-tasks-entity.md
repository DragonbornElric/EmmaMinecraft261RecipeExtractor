# Audit V3 — 13: tasks/entity/ (11 files)

Old: `adris/altoclef/tasks/entity/`
New: `goap/actions/AttackEntityAction.java` + `HuntMobAction.java` + `HuntHostileAction.java` + `EntityInteractAction.java` + `CombatHelper.java`

---

## 1. `AbstractKillEntityTask.java` — Base entity killing

**Old implementation:**
- CONSIDER_COMBAT_RANGE = 10, OTHER_FORCE_FIELD_RANGE = 2
- Hardcoded weapon damage table (netherite sword=8 ... golden shovel=2.5)
- Attack cooldown: `getAttackCooldownProgress(0) >= 1.0`
- Ground check: only attacks if grounded OR falling OR in water
- Lead prediction: `ProjectileHelper.getLeadPredictedAimPos(eyePos, entity, 1.5f)`
- Equips best weapon via `forceEquipItem()`

**Emma equivalent:** `CombatHelper.tryAttack()` + `AttackEntityAction.java`

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Weapon damage source | Hardcoded damage table | DataComponents.ATTRIBUTE_MODIFIERS read | Emma reads actual damage (more accurate for modded) |
| Attack cooldown check | `getAttackCooldownProgress(0) >= 1.0` | `getAttackStrengthScale(0.0f) < 1.0f` | MC 26.1 API rename, same logic |
| Ground check | onGround OR velocity.Y<0 OR inWater | Not present | Emma attacks while airborne (may miss crit opportunity) |
| Lead prediction | 1.5f velocity lead | Direct eye position look | Misses moving targets |
| Force field range | 2 blocks for non-primary targets | ForceFieldReflex: 3.5 block AABB for ALL threats | Different scope |
| Combat range guard | 10 blocks | 16 blocks (ENGAGE_RANGE) | Emma engages from further away |

**Verdict:** PARTIAL — Core attack works but missing ground check and lead prediction.

---

## 2. `KillEntityTask.java` + `KillEntitiesTask.java`

**Old:** Simple wrappers. KillEntityTask targets a single entity. KillEntitiesTask uses DoToClosestEntityTask to kill multiple matching entities.

**Emma equivalent:** `AttackEntityAction.java` + `HuntHostileAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Single target | KillEntityTask wraps one entity | AttackEntityAction targets nearest hostile | Emma can't target a specific entity by reference |
| Multi-target | KillEntitiesTask with predicate filter | HuntHostileAction with class filter | Similar |
| Target specification | Entity reference or predicate | Goal-based (hunt_mob or hunt_hostile goal) | Emma uses goals, not direct entity references |

**Verdict:** IMPLEMENTED — Different targeting mechanism but same outcome for autonomous play.

---

## 3. `HeroTask.java` — Kill all nearby hostiles

**Old implementation:**
- Check food → eat first
- Collect XP orbs if nearby
- Scan for HostileEntity/SlimeEntity → kill + loot drops
- Pick up `HOSTILE_MOB_DROPS` predefined set
- Wander if nothing found

**Emma equivalent:** `HuntHostileAction.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| XP orb collection | Dedicated XP orb pickup | Not present | XP orbs ignored |
| Drop collection | Explicit pickup after kill | Separate PickupItemAction competes by score | Similar outcome |
| Wander when idle | TimeoutWanderTask | Patrol with random ±32 offset | Similar |
| Food check | Eats before hunting | EatFoodAction competes by score | GOAP handles priority |
| Slime inclusion | Explicit SlimeEntity check | HuntHostileAction includes Slime | Same |

**Verdict:** IMPLEMENTED — GOAP scoring replaces explicit priority checks.

---

## 4. `DoToClosestEntityTask.java` — Act on nearest matching entity

**Old:** Generic base — finds closest entity matching predicate + class filter, runs task on it. Uses EntityTracker with reachability check.

**Emma equivalent:** Inline in each action (HuntMobAction, EntityInteractAction each find nearest)

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Reachability check | `entityTracker.isEntityReachable(obj)` | Not present | May target entities behind walls |
| Custom origin | `getOriginSupplier()` for distance calculation | Always player position | Can't search from arbitrary point |

**Verdict:** NOT_NEEDED — Pattern replaced by per-action entity scanning.

---

## 5. `GiveItemToPlayerTask.java`

**Old implementation:**
- Collect items → follow player (0.5 block distance) → look at head (+0.2Y) → throw items
- Throw timeout: 400ms between throws
- LOS check: `seesPlayer(target, self, 6)` (6 block distance)
- End condition: player ≥4 blocks away after throwing

**Emma equivalent:** None — no item giving action exists

**Verdict:** GAP — Cannot give items to other players.

---

## 6. `ShearSheepTask.java`

**Old:** Equips shears, interacts with shearable sheep (checks `isShearable()` + `!isSheared()`)

**Emma equivalent:** `EntityInteractAction.java` with INTERACT entry for shears + sheep

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Shearable check | `sheep.isShearable() && !sheep.isSheared()` | Not checked — interacts with any sheep | May waste time on already-sheared sheep |
| Tool equip | Explicit shears equip | EntityInteractAction EQUIP phase | Same |

**Verdict:** PARTIAL — Works but no sheared-state check (may interact with already-sheared sheep).

---

## 7. `ShootArrowSimpleProjectileTask.java`

**Old implementation:**
- **Bow charge:** `velocity = (useTime - useTimeLeft) / 20f; velocity = (v² + v*2) / 3`
- **Ballistic pitch:** `pitch = -atan((v² - sqrt(v⁴ - g(g*h² + 2*relY*v²))) / (g*h))` where g=0.006f
- **Yaw:** `atan2(targetZ - playerZ, targetX - playerX) - 90°`
- **Anti-spam:** checks for existing arrows moving toward target
- **State machine:** wait for look → hold use key → charge bow → release
- **Height adjustment:** `posY -= 1.9f - target.getHeight()`

**Emma equivalent:** None — no bow/ranged combat action exists

**Verdict:** GAP — Cannot use bows, crossbows, or ranged weapons. Blocks end crystal destruction at range and safe blaze combat.

---

## 8. `KillPlayerTask.java`

**Old:** Searches entities for matching player name (case-insensitive), delegates to AbstractKillEntityTask.

**Emma equivalent:** None — no PvP combat

**Verdict:** NOT_NEEDED — Intentional omission for cooperative server.

---

## 9. `ThrowSplashPotionTask.java`

**Old:** Equips splash potion, throws at target entity.

**Emma equivalent:** None — no potion usage

**Verdict:** GAP — Cannot use splash potions for combat or utility (healing, fire resistance).

---

## Summary

| File | Verdict | Key Gaps |
|------|---------|----------|
| AbstractKillEntityTask | PARTIAL | No ground check, no lead prediction |
| KillEntityTask | IMPLEMENTED | Different mechanism |
| KillEntitiesTask | IMPLEMENTED | Goal-based |
| HeroTask | IMPLEMENTED | No XP collection |
| DoToClosestEntityTask | NOT_NEEDED | Pattern replaced |
| GiveItemToPlayerTask | GAP | No item giving |
| ShearSheepTask | PARTIAL | No sheared-state check |
| ShootArrowSimpleProjectileTask | GAP | No ranged combat |
| KillPlayerTask | NOT_NEEDED | Intentional |
| ThrowSplashPotionTask | GAP | No potion usage |
