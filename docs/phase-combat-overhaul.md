# Phase: Combat System Overhaul

> **Status:** In Progress
> **Created:** 2026-03-16
> **Goal:** Fix combat so the bot actively engages threats instead of standing still
> blocking. Bring back the effective defense behavior from the old MobDefenseChain
> (commit `6049d92` in EmmaAICoHost) while keeping the GOAP architecture.

## Context

The old AltoClef-based `MobDefenseChain` had effective combat: it approached
skeletons while blocking, hit-and-backed-off from creepers, and continuously
faced threats. The GOAP rewrite lost these behaviors. Currently:

- Bot stands still blocking repeatedly (ShieldBlock spam)
- Never approaches ranged attackers (skeletons)
- ForceField swings without facing the target (whiffs)
- AttackEntity has no pathfinding — only hits what walks into reach
- Creepers blow up in face because there's no retreat after shield block
- ShieldBlock suppresses scoring, preventing AttackEntity from ever winning

## Checklist

### Fix 1: ForceField must face target before swinging
- **File:** `goap/reflex/ForceFieldReflex.java`
- **Problem:** `fire()` (line 49-78) swings at nearest hostile but never calls
  `BlockInteraction.lookAt()`. Hits whiff because player isn't facing the mob.
- **Fix:** Before the attack call (line 75), add:
  ```java
  BlockInteraction.lookAt(nearest.getEyePosition());
  ```
- **Risk:** Low. Pure improvement.
- [x] Add lookAt call before attack
- [x] Verify build compiles
- [ ] Test: mob in melee range, bot should visibly rotate then swing

### Fix 2: AttackEntity must approach targets
- **File:** `goap/actions/AttackEntityAction.java`
- **Problem:** `tick()` (line 139-174) only looks at target and attacks when
  within `ATTACK_REACH` (3.0 blocks). No pathfinding to close distance. The
  old `KillEntitiesTask` used Emmatone to path toward targets.
- **Fix:** In `tick()`, if target exists but is beyond ATTACK_REACH, issue
  Emmatone pathfinding toward it:
  ```java
  if (distSq > ATTACK_REACH * ATTACK_REACH) {
      // Path toward target
      BlockPos targetPos = currentTarget.blockPosition();
      if (!emmatone.getPathingBehavior().isPathing()) {
          emmatone.getCustomGoalProcess().setGoalAndPath(
              new GoalNear(targetPos, (int) ATTACK_REACH));
      }
  }
  ```
- **Also:** Cancel pathing in `onDeactivated()` so we don't keep walking after
  switching away.
- [x] Add approach pathfinding when target > ATTACK_REACH
- [x] Cancel pathing in onDeactivated()
- [x] Removed isShielding precondition check (AttackEntity can score during shield)
- [x] Verify build compiles
- [ ] Test: skeleton at 10 blocks, bot should path toward it while PreEquipWeapon fires

### Fix 3: ShieldBlock should not suppress scoring during active combat
- **File:** `goap/reflex/ShieldBlockReflex.java`
- **Problem:** `suppressesScoring()` (line 40) returns `true` unconditionally.
  While shield is up, no GOAP auction runs — AttackEntity can never outbid the
  current action. Creates shield-spam loop with no offensive counter.
- **Approach options:**
  - **Option A:** Change `suppressesScoring()` to return `false`. Shield still
    fires as a reflex, but GOAP can switch to AttackEntity between blocks. Risk:
    might cause weapon/shield slot fighting.
  - **Option B:** Only suppress scoring if no combat action is active:
    ```java
    public boolean suppressesScoring() {
        return !GoapStateFlags.get().isForceFieldActive;
    }
    ```
  - **Option C (preferred):** Allow AttackEntity specifically to score during
    shield. In GoapTicker, skip suppression for combat-tagged actions.
- **Note:** The old system had NO reflexes — shield was part of the chain task
  itself, interleaved with attacks. The reflex architecture creates this
  problem because shield and attack are decoupled.
- [x] Chose Option A: suppressesScoring() returns false
- [x] Added lookAt nearest threat while blocking (face what you're blocking)
- [x] Verify build compiles
- [ ] Test: skeleton firing, bot should block arrow then swing, not just block forever

### Fix 4: Creeper retreat after shield block
- **File:** `goap/actions/FleeFromAction.java` + possibly new reflex
- **Problem:** When creeper fuses, ShieldBlock fires. But after block, bot
  just stands there. Old system had `RunAwayFromCreepersTask` with
  `CREEPER_KEEP_DISTANCE = 10`. Bot would shield if fuse > 0.5 AND health ok,
  otherwise flee with priority scaled by fuse time (50 + fuse*50).
- **Current FleeFrom scoring:** Creeper bonus is only 0.4f (line 135). Not
  enough to outbid hysteresis on other actions. Critical health override (3x)
  only triggers at HP <= 6.
- **Fix options:**
  - **Option A:** Boost creeper proximity bonus in FleeFrom. When fusing
    creeper within 6 blocks, add urgency multiplier proportional to fuse time.
  - **Option B:** Add creeper retreat to ShieldBlock reflex release — when
    shield drops and a fused creeper is within blast radius, immediately issue
    a short GoalRunAway.
  - **Option C:** New `CreeperRetreatReflex` that backs off 5-8 blocks when
    fuse detected, independent of GOAP scoring.
- **Key behavior to restore:** hit creeper, back off. If health/gear allows,
  re-approach and hit again. If not, keep distance.
- [x] Added fusingCreeperDistance to WorldState (detected during threat scan)
- [x] Boosted FleeFrom creeper urgency: fusing creeper within 8 blocks → creeperBonus=1.0 (bypasses urgency cap)
- [x] Verify build compiles
- [ ] Test: creeper approaches, bot should hit it then back off before explosion

### Fix 5: Audit mob-specific handling gaps
- **Problem:** The old system had explicit handling for many mob types. The
  new system only has mob-specific logic in:
  - `CombatHelper.getDangerTier()` — classifies danger but doesn't change behavior
  - `ShieldBlockReflex` — creeper fuse + skeleton bow draw
  - `AttackEntityAction` — single creeper avoidance in scoring
  - `FleeFromAction` — creeper bonus in scoring
- **Mobs needing review:**

| Mob | Old Behavior | Current Behavior | Gap |
|-----|-------------|-----------------|-----|
| **Skeleton** | Approach while blocking, kill | Block only, no approach | Fix 2+3 |
| **Creeper** | Shield if fuse+health ok, else flee scaled by fuse | Shield reflex, generic flee | Fix 4 |
| **Witch** | Classified as ranged/poisonous, extended range | MODERATE tier, no special handling | May need potion dodge |
| **Enderman** | Avoid eye contact, flee if provoked | MODERATE tier, no special handling | Need look-away logic |
| **Warden** | Always flee (EXTREME) | EXTREME tier → 0.1x attack penalty | OK-ish via scoring |
| **Wither Skeleton** | Flee if health < threshold | HIGH tier | OK-ish via scoring |
| **Blaze** | Ranged threat | MODERATE tier | Shield for fireballs? |
| **Drowned** | Can throw tridents | MODERATE tier | Shield for tridents? |
| **Phantom** | Flying mob | Not in danger tiers | May fly out of range |
| **Spider** | Wall climbing | STANDARD tier | May path weirdly |
| **Pillager** | Ranged crossbow | Not in danger tiers! | Add to ShieldBlock |
| **Piglin Brute** | Very high damage | HIGH tier | OK via scoring |

- [x] Add Pillager to ShieldBlock reflex (crossbow users, same as skeleton)
- [x] Enderman: skip neutral endermen in threat scan, ForceField, and AttackEntity (avoid provoking)
- [x] Added Pillager and Phantom to CombatHelper MODERATE danger tier
- [x] ShieldBlock uses AbstractSkeleton (catches Stray, Bogged, WitherSkeleton)
- [x] Witch: potions already caught by projectile scan + DoT effects boost flee urgency (no new code needed)
- [x] Blaze: added to ShieldBlock as always-ranged threat (no isUsingItem — fireballs are constant)
- [x] Drowned: added to ShieldBlock — shield triggers when Drowned holds trident
- [x] Phantom: AttackEntity skips pathfinding for airborne Phantoms (waits for swoop); FleeFrom returns 0 for Phantom-only threats (can't outrun them)

### Fix 6: Verify projectile detection coverage
- **File:** `goap/WorldState.java` line 245-268
- **Problem:** Projectile scan uses `instanceof Projectile` which should catch
  all projectile types, but need to verify:
  - Arrows (Arrow, SpectralArrow)
  - Tridents (ThrownTrident)
  - Fireballs (SmallFireball, Fireball)
  - Potions (ThrownPotion — witch throws these)
  - Shulker bullets (ShulkerBullet)
  - Crossbow bolts (same as Arrow?)
- [x] Verified: `instanceof Projectile` catches all types (Arrow, SpectralArrow, ThrownTrident, SmallFireball, Fireball, ThrownSplashPotion, ThrownLingeringPotion, ShulkerBullet, WitherSkull, BreezeWindCharge) — all extend Projectile base class
- [ ] Add logging to confirm projectile detection in combat (optional, low priority)

## Architecture Notes

### File locations (all under `com.emma.bridge.goap`)
```
reflex/ForceFieldReflex.java      — melee auto-swing
reflex/ShieldBlockReflex.java     — reactive shield blocking
reflex/PreEquipWeaponReflex.java  — weapon equip 3.5-8 blocks
actions/AttackEntityAction.java   — GOAP attack action
actions/FleeFromAction.java       — GOAP flee action
WorldState.java                   — threat + projectile scanning
GoapTicker.java                   — reflex → scoring → execution loop
util/CombatHelper.java            — danger tiers, weapon selection, equipment scoring
control/BlockInteraction.java     — lookAt rotation utility
```

### Key constants
| Constant | Value | Location |
|----------|-------|----------|
| ForceField melee range | 3.5 blocks | ForceFieldReflex:23 |
| ShieldBlock creeper range | 6 blocks | ShieldBlockReflex:61 |
| ShieldBlock skeleton range | 12 blocks | ShieldBlockReflex:69 |
| ShieldBlock projectile range | 8 blocks | ShieldBlockReflex:79 |
| ShieldBlock min hold | 5 ticks | ShieldBlockReflex:32 |
| AttackEntity engage range | 16 blocks | AttackEntityAction:38 |
| AttackEntity attack reach | 3 blocks | AttackEntityAction:41 |
| PreEquipWeapon range | 3.5-8 blocks | PreEquipWeaponReflex:20-21 |
| FleeFrom distance | 30 blocks | FleeFromAction:35 |
| FleeFrom critical HP | 6.0 | FleeFromAction:41 |
| Threat scan radius | 16 blocks | WorldState:272 |
| Projectile scan radius | 12 blocks | WorldState:247 |

### Old system reference
- Repo: `/c/Users/Owner/EmmaAICoHost`
- Commit: `6049d92` (tip of phase-58)
- Key file: MobDefenseChain (search git history)
- That commit had: approach-while-blocking, creeper hit-and-retreat,
  fuse-time-scaled priority, continuous target facing, KillAura integration

## Implementation Order

1. **Fix 1** (ForceField lookAt) — 5 min, zero risk, immediate improvement
2. **Fix 2** (AttackEntity approach) — 15 min, moderate risk, biggest behavior change
3. **Fix 3** (ShieldBlock scoring) — 10 min, needs testing, enables attack+block interleave
4. **Fix 4** (Creeper retreat) — 15 min, moderate risk, prevents explosion deaths
5. **Fix 5+6** (Mob audit) — can be done incrementally, lower priority
