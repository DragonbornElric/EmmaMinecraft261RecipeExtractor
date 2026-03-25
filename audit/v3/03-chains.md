# Audit V3 — 03: chains/ (11 files)

Old: `adris/altoclef/chains/`
New: Distributed across `goap/reflex/`, `goap/actions/`, `goap/GoapTicker.java`, `goap/ReflexLayer.java`

---

## 1. `DeathMenuChain.java`

**Old implementation details:**
- `deathRetryTimer = TimerReal(8)` — 8-second retry for respawn failures
- `reconnectTimer = TimerGame(1)` — 1 tick before reconnect attempt
- `waitOnDeathScreenBeforeRespawnTimer = TimerGame(2)` — 2 ticks before calling `player.requestRespawn()`
- Death command execution: splits by " & ", supports `{deathmessage}` template replacement
- Auto-reconnect: on DisconnectedScreen, opens MultiplayerScreen, waits 1 tick, calls `ConnectScreenVer.connect()`
- Tracks `deathCount` total
- Uses `prevScreen` to detect screen class transitions

**Emma equivalent:** `AutoRespawnReflex.java`

**Emma implementation details:**
- `RESPAWN_DELAY = 2` ticks before respawn (same as old's 2-tick wait)
- Trigger: `client.screen instanceof DeathScreen`
- Action: calls `client.player.respawn()` + `client.setScreen(null)`
- Suppresses GOAP scoring while active (old chain had priority system instead)
- Resets `deathTicks = 0` on release

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Respawn delay | 2 game ticks | 2 ticks | Same |
| Respawn API | `player.requestRespawn()` | `player.respawn()` | MC 26.1 rename |
| Screen close | Not explicit | `client.setScreen(null)` | Emma explicitly closes death screen |
| Death count | Tracked in `deathCount` field | Not tracked in reflex (tracked in `DeathContext`) | Death context available elsewhere |
| Death commands | Executes configurable commands with `{deathmessage}` | Not present | No death-triggered chat commands |
| Auto-reconnect | Opens MultiplayerScreen → ConnectScreen after disconnect | Not present | No auto-reconnect on server kick/disconnect |
| Retry on failure | 8-second real-time retry timer | No retry — fires every tick while on death screen | Emma retries naturally since shouldFire() re-triggers |
| Scoring suppression | Priority-based (chain priority) | `suppressesScoring() = true` — blocks all GOAP actions | Same effect, different mechanism |

**Missing edge cases:**
- [ ] No auto-reconnect on server disconnect — if server kicks the bot, it stays disconnected
- [ ] No death command execution — can't send chat messages or run commands on death
- [ ] No death count tracking in the reflex itself (but DeathContext tracks deaths separately for recovery)

**Verdict:** PARTIAL — Respawn works, but auto-reconnect and death commands are missing.

---

## 2. `FoodChain.java`

**Old implementation details:**
- **Thresholds:**
  - `alwaysEatWhenWitherOrFireAndHealthBelow = 6`
  - `alwaysEatWhenBelowHunger = 10`
  - `alwaysEatWhenBelowHealth = 14`
  - `alwaysEatWhenBelowHungerAndPerfectFit = 15`
  - `prioritizeSaturationWhenBelowHealth = 8`
- **Food scoring:**
  - `saturationMultiplier = 8.0` when health < 8
  - `saturationWastePenalty = 1.0` (penalty for wasting saturation when nearly full)
  - `hungerWastePenalty = 2.0` (penalty for overfilling hunger)
  - `hungerNotFilledPenalty = 1.0` (penalty for underfilling)
  - `rottenFleshPenalty = 100.0` (extreme penalty for rotten flesh)
- **Enemy detection radius:**
  - While eating: 4 blocks
  - Before starting to eat: 10 blocks (matches MobDefenseChain)
  - Excludes: spider eyes, neutral piglins, calm endermen
- **Eating flow:**
  - Equips food → `interactItem()` → holds use key → pauses Baritone interaction
  - Self-heal: re-fires `interactItem()` if eating interrupted
  - On stop: releases use, re-equips shield if available
- **Priority system:**
  - Returns `NEGATIVE_INFINITY` if: in nether portal, putting out fire, shielding, doing acrobatics, touching dragon breath, auto-eat disabled, in lava, MLG falling, blocking
  - Priority 55 for overflow withdrawal
  - Priority 45 for food collection
- **Overflow withdrawal:** attempts to pull food from EndinvBridge before collecting new food

**Emma equivalent:** `EatFoodAction.java` + `CollectFoodAction.java`

**Emma implementation details:**
- **Thresholds:**
  - `HUNGER_THRESHOLD = 18` — only eats below 18 hunger (old: multiple thresholds from 10-15)
  - `HUNGER_CRITICAL = 6` — score spike at 6 hunger
  - No health-based eating triggers (old forced eating when health < 14)
- **Food scoring:** picks highest hunger value only — no saturation scoring, no waste penalties, no rotten flesh penalty
- **Enemy detection:** none during eating — `GoapStateFlags.isEating` prevents ForceField/ShieldBlock from interrupting, but no pre-eat enemy check
- **Eating flow:**
  - Equips food → waits 2 ticks → `useItem()` → holds use key
  - Re-fire: if `eatTicks > 36` and not using item, re-equips and re-fires
  - On deactivate: releases use key, clears isEating flag
- **Score formula:**
  ```
  urgency = 1.0 - (hunger / 20.0)
  if hunger <= 6: urgency = max(urgency, 0.8)
  if DOT effect: urgency = max(urgency, 0.6)
  if hunger effect: urgency = max(urgency, 0.7)
  score = stay_fed_priority × urgency
  ```
- **Minimum active ticks:** 40 (prevents interruption during eating animation)
- **No shield re-equip after eating**
- **No overflow/EndinvBridge withdrawal in eating action** (EndinvBridge accessed elsewhere)

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Eat trigger (hunger) | Multiple: 10 (always), 15 (perfect fit) | Single: < 18 | Emma eats more aggressively (at 17 hunger vs old's 10) |
| Eat trigger (health) | < 14 forces eating regardless of hunger | No health-based trigger | Old ate when hurt even if not hungry; Emma doesn't |
| Eat trigger (wither/fire) | Eats when health < 6 and has DOT | DOT boosts urgency by 0.6 but doesn't force eating | Less aggressive DOT response |
| Food scoring | Saturation × 8.0 when low health, waste penalties, rotten flesh -100 | Highest hunger value only | Emma picks max hunger restore; ignores saturation. May eat golden carrot (6 hunger) instead of cooked beef (8 hunger) when saturation matters. Eats rotten flesh without penalty. |
| Rotten flesh | -100 penalty (effectively blacklisted) | No penalty | Emma will eat rotten flesh, spider eyes, pufferfish |
| Food blacklist | Spider eyes always excluded from eating | No blacklist | Will eat poison/harmful foods |
| Enemy check before eating | 10-block radius scan, pauses if hostiles | No pre-eat enemy check | May start eating with skeleton shooting at player |
| Enemy check during eating | 4-block radius, stops eating if too close | GoapStateFlags.isEating prevents conflicting reflexes | Different mechanism — GOAP scoring continues during eating |
| Shield re-equip | Re-equips shield after eating stops | Not present | Shield stays unequipped after eating |
| Baritone/Emmatone pause | Pauses Baritone interaction during eating | No Emmatone pause | Pathfinding continues during eating |
| Eating interruption recovery | Re-fires interactItem() immediately | Waits 36 ticks then re-fires | 1.8-second delay before recovery vs immediate |
| Overflow withdrawal | Pulls food from EndinvBridge before field collection | Not in EatFoodAction | EndinvBridge food access handled elsewhere |
| Perfect-fit optimization | Picks food that fills hunger exactly, minimizing waste | Picks highest hunger value | Old minimized waste; Emma may overfill |

**Missing edge cases:**
- [ ] No food blacklist — eats rotten flesh, pufferfish, spider eyes
- [ ] No saturation scoring — golden carrots (best saturation) ranked below steak
- [ ] No health-based eating trigger — won't eat when hurt but not hungry
- [ ] No enemy proximity check before eating — starts eating under fire
- [ ] No shield re-equip after eating
- [ ] No waste minimization — always picks highest hunger food even if it overfills
- [ ] No rotten flesh penalty — will consume harmful food

**Verdict:** PARTIAL — Core eating mechanics work but food selection, safety checks, and optimization are significantly simplified.

---

## 3. `MLGBucketFallChain.java`

**Old implementation details:**
- **Fall detection:** `!swimming && !inWater && !onGround && !climbing && velocity.Y < -0.7`
- **pickupRepeatTimer = 0.25** game ticks (attempt bucket pickup every 0.25 ticks)
- **waterBlockTimeout = 2** game ticks (wait for server block update confirmation)
- **Water pickup logic:** after landing, if has empty bucket + no water bucket + placed water within 5.5 blocks + water reachable → `BlockInteraction.tryCollectFluid()` every 0.25 ticks
- **Unreachable water fallback:** if water placed but unreachable (player not inside block), delegates to ItemTask to pick up
- **Chorus fruit fallback:** if no water bucket but has chorus fruit + levitation effect duration ≤ 70 ticks → equips and eats chorus fruit
- **Priority:** 100 when falling, 60 during water collection/chorus eating

**Emma equivalent:** `MLGBucketReflex.java`

**Emma implementation details:**
- **Fall detection:** same conditions — `velocity.Y < -0.7 && !onGround && !inWater && !onClimbable`
- **Pickup delay:** fixed `5 ticks` after landing (vs old's immediate 0.25-tick polling)
- **Water placement:** looks straight down (`setXRot(90.0f)`), equips water bucket, calls `useItem()`
- **Water pickup:** after 5-tick delay, equips empty bucket, looks down, calls `useItem()` to collect
- **Chorus fruit fallback:** if no water bucket, equips chorus fruit, holds use key. Exits when `chorusTicks > 35 OR onGround` (vs old's ≤ 70 ticks on levitation effect)
- **GoapStateFlags set:** `isFalling`, `isMLGActive`, `isChorusFruiting`
- **Suppresses scoring:** YES

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Fall velocity threshold | < -0.7 | < -0.7 | Same |
| Water pickup timing | Polls every 0.25 ticks (aggressive) | Fixed 5-tick delay after landing | Emma waits longer — water may despawn if server is slow, but avoids premature pickup |
| Pickup verification | Checks: has bucket, no water bucket, water within 5.5 blocks, water reachable | Equips bucket, looks down, uses item (no distance check) | Emma simpler but less robust — doesn't verify water still exists |
| Server block update wait | 2-tick timeout for confirmation | No explicit wait — 5-tick delay implicitly covers this | Likely sufficient |
| Unreachable water fallback | Delegates to ItemTask for pathfinding pickup | No fallback — just tries useItem() | If water is displaced (flowing), Emma may fail to pick it up |
| Chorus fruit trigger | Levitation effect ≤ 70 ticks + no water bucket | No water bucket (regardless of levitation) | Emma triggers chorus on any fall without water, even non-levitation falls |
| Chorus exit condition | Levitation duration check | `chorusTicks > 35 OR onGround` | Different exit criteria — old was effect-based, Emma is time/ground based |
| Scoring suppression | Priority 100 (beats everything) | `suppressesScoring() = true` | Same effect |

**Missing edge cases:**
- [ ] No distance check for water pickup — may try to collect water that flowed away
- [ ] No unreachable-water fallback — if water placed on a ledge below, no pathfinding to retrieve it
- [ ] Chorus fruit triggers on any fall without water, not just levitation falls (minor — chorus teleportation still helps)

**Verdict:** IMPLEMENTED — Core MLG works with slightly different pickup timing. Edge cases around water displacement are less robust.

---

## 4. `MobDefenseChain.java`

**Old implementation details:**
- **Distance constants:**
  - `DANGER_KEEP_DISTANCE = 30.0` blocks (full flee)
  - `CREEPER_KEEP_DISTANCE = 10.0`
  - `ARROW_KEEP_DISTANCE_HORIZONTAL = 2.0`
  - `ARROW_KEEP_DISTANCE_VERTICAL = 10.0`
  - `SAFE_KEEP_DISTANCE = 8.0`
- **Ignored mobs (defense only, not attack):** Warden, Wither, Enderman, Blaze, WitherSkeleton, Hoglin, Zoglin, PiglinBrute, Vindicator, MagmaCube
- **Creeper safety:** `distance * 0.2 if fuse > 0.001, else raw distance` — weighs fusing creepers much more heavily
- **Projectile detection (squared distances):**
  - Cached projectiles within 150 blocks² (~12 blocks)
  - Excludes: ghast balls, dragon fireballs
  - For arrows/small fireballs: velocity-based "moving toward" check + closest approach calculation
  - Shield trigger: horizontal < 2.0², vertical < 10.0
  - Skeleton bow-draw: `itemUseTime > 15` ticks within 10 blocks → shield
- **Weapon priority (hardcoded):** netherite sword (8) > diamond (7) > iron (6) > stone (5) > golden/wooden (4) > axes (7-10)
- **Dangerousness scoring:** base = entity count. Enderman/Slime/Blaze: +1. Drowned with trident: +5.
- **Can-deal-with formula:** `armorValue * 3.6/20 + weaponDamage * 0.8 + (hasShield ? 3 : 0)`
- **Hostile tracking ranges:**
  - Melee mobs: 10 blocks (`annoyingRange`)
  - Ranged mobs (Skeleton, Witch, Pillager, Piglin, Stray, CaveSpider): 20 blocks (35 if no shield)
  - Must pass `LookHelper.seesPlayer()` (LOS check)
- **Fire escape:** scans 3×3 grid at feet for `AbstractFireBlock`, pauses Baritone, breaks fire block
- **Decision flow (priority-based):**
  1. Peaceful mode → skip
  2. Universally dangerous mob within 6 blocks + angry + health ≤ 10 → flee (70)
  3. Creeper fusing → shield (if applicable) or flee (50 + fuse×50)
  4. Projectile → shield (60)
  5. MLG/falling → skip defense entirely
  6. Force field pass-through for close entities
  7. Low health + projectile → wall/dodge (65)
  8. In danger + no dragon breath → flee (70)
  9. Can deal with annoying hostiles → kill (65)
  10. Can't deal → flee (80)

**Emma equivalent:** `ForceFieldReflex.java` + `ShieldBlockReflex.java` + `PreEquipWeaponReflex.java` + `FleeFromAction.java` + `AttackEntityAction.java` + `HuntHostileAction.java` + `ProjectileDodgeAction.java`

**Emma implementation details:**
- **ForceField:** MELEE_RANGE = 3.5 blocks. Hits nearest Monster. Cooldown-gated. No multi-target.
- **ShieldBlock:** creeper scan 6 blocks (fuse > 0.5), ranged scan 12 blocks (skeleton/pillager isUsingItem, blaze always, drowned with trident), projectile scan 8 blocks. MIN_HOLD_TICKS = 5.
- **PreEquipWeapon:** range 3.5-8.0 blocks. Calls `CombatHelper.equipBestWeapon()`.
- **FleeFrom scoring:**
  ```
  healthFactor: critical(≤6)=1.0, low(≤10)=0.7, high(>15)=0.1, else 0.3
  DOT boost: max(hf, 0.6). Weakness: max(hf, 0.5). Blindness: max(hf, 0.6). Slowness: max(hf, 0.4)
  dangerBonus: EXTREME=0.8(1.0 if under-geared), HIGH=0.4(0.7 if low), MODERATE=0.1(0.3 if low)
  outnumbered: min(nearThreats/3, 1.0)
  creeperBonus: fusing<8blocks=1.0, nearby=0.4
  proximityFactor: 1/(1+closestDist/8)
  gearPenalty: 1.5 - min(1.0, equipScore/10)
  score = survive × max(hf, outnumbered) + creeperBonus × proximityFactor × gearPenalty
  ```
  - FLEE_DISTANCE = 30.0 blocks (same as old)
  - Single-threat with weapon: score × 0.4 (strong fight bias)
  - All phantoms: returns 0 (can't outrun)
- **CombatHelper danger tiers:** EXTREME (warden, wither), HIGH (wither_skeleton, hoglin, piglin_brute, vindicator, ravager, evoker), MODERATE (enderman, blaze, drowned, creeper, witch, pillager, phantom), STANDARD (everything else)
- **CombatHelper equipment score:** `armorValue * 3.6/20 + weaponDamage * 0.8 + (hasShield ? 3 : 0)` — identical formula to old

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Defense architecture | Single chain with priority decisions | Split across 3 reflexes + 3 scored actions | Emma is more modular but decisions are independent |
| Creeper safety weighting | `distance * 0.2 if fusing` (continuous) | Binary: fuse > 0.5 for shield, fuse presence for flee bonus | Old had continuous creeper urgency; Emma has step function |
| Projectile detection | Velocity-based moving-toward check + closest-approach point calculation | `dot(velocity, toPlayer) > 0.3` in WorldState | Old calculated closest approach distance; Emma only checks if moving toward |
| Projectile shield trigger | Horizontal < 2.0², Vertical < 10.0 | Distance < 8.0 | Different geometry — old was flat+tall box, Emma is sphere |
| Skeleton bow detection | `itemUseTime > 15` ticks (about to shoot) | `isUsingItem()` (any draw state) | Emma triggers earlier (on draw start vs near-release) |
| Flee distance | 30.0 | 30.0 | Same |
| Ignored mobs for defense | Warden, Wither, Enderman, Blaze, etc. | No ignored list — all Monsters processed | Emma doesn't ignore specific mobs for defense decisions |
| LOS check for hostiles | `LookHelper.seesPlayer()` required | No LOS check — all monsters in AABB counted | Emma counts mobs behind walls as threats |
| Hostile tracking range (melee) | 10 blocks | 16 blocks (WorldState AABB) | Emma scans wider |
| Hostile tracking range (ranged) | 20 blocks (35 no shield) | 16 blocks (WorldState), 12 blocks (ShieldBlock scan) | Old scanned farther for ranged mobs |
| Fire escape | Scans 3×3, breaks fire block | EnvironmentalHazardAction: flee from fire area | Old broke the fire block; Emma flees from it |
| Can-deal-with formula | `armor*3.6/20 + damage*0.8 + shield*3` | Same formula in CombatHelper | Identical |
| Weapon priority | Hardcoded damage table (sword > axe per tier) | Reads actual DataComponents ATTACK_DAMAGE | Emma reads real damage values (better for modded items) |
| Multi-target force field | KillAura FASTEST hits ALL targets per tick | ForceField only hits nearest | Old could clear crowds faster |
| Projectile wall building | ProjectileProtectionWallTask when low health | Not present | No emergency wall construction |
| Group kiting | Partial retreat concept in flee distances | Full 30-block flee only | No partial retreat (5-7 blocks back then re-engage) |
| Dragon breath avoidance | Checks for dragon breath in defense decisions | Not checked by FleeFrom (separate hazard action) | Different routing but covered |

**Missing edge cases:**
- [ ] No approach-while-blocking — shield drops while walking toward ranged threats (old paused pathing)
- [ ] No projectile closest-approach calculation — shields based on distance only, not trajectory
- [ ] No LOS check for threat counting — mobs behind walls inflate threat scores
- [ ] No partial retreat/kiting — full 30-block flee or full commit, no middle ground
- [ ] No fire block breaking — flees from fire instead of extinguishing it
- [ ] No projectile protection wall building when low health
- [ ] No multi-target melee (only hits nearest)
- [ ] No skeleton bow-draw timing (triggers on any draw, not just near-release)

**Verdict:** PARTIAL — Core defense loop (force field + shield + flee) works but lacks tactical sophistication of the old system.

---

## 5. `PlayerDefenseChain.java`

**Old implementation details:**
- **Retaliation thresholds:** 2 hits before fighting back, 1 hit if health < 14
- **Swing timeout:** 0.4 seconds for swing detection
- **Damage inference:** if attacker unknown, checks entities within 5 blocks that recently swung and can see player (60° cone)
- **Forget timers:** 6 seconds idle → reset hit count, 30 seconds → stop attacking
- **Priority:** 55 when attacking player, 0 otherwise

**Emma equivalent:** None

**Emma implementation details:**
- No player defense system. Players are not tracked as threats. `WorldState.updateThreats()` only scans for Monster instances.
- No retaliation logic, no damage inference, no player tracking.

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Player as threat | Tracked with hit counting and retaliation | Not tracked at all | Cannot defend against hostile players |
| Damage inference | Infers attacker from nearby swinging entities | No damage source tracking | N/A |
| PvP combat | KillPlayerTask after threshold | No PvP capability | Cannot fight back on PvP servers |

**Verdict:** NOT_NEEDED — Intentionally omitted for cooperative server. Players are allies in Emma's use case.

---

## 6. `PlayerInteractionFixChain.java`

**Old implementation details:**
- **Timers:**
  - `stackHeldTimeout = 1` game tick — cursor item persistence detection
  - `generalDuctTapeSwapTimeout = 30` ticks — inventory refresh rate
  - `shiftDepressTimeout = 10` ticks — stuck sneak release
  - `betterToolTimer = 0` — immediate tool check
  - `mouseMovingButScreenOpenTimeout = 1` tick — screen close detection
- **Tool equipping:** while breaking blocks, checks if better tool exists in inventory (slots ≥ 9). Skips hotbar if Baritone pathing. Only swaps if item class differs.
- **Shift release:** if sneak held > 10 ticks without purpose, releases
- **Inventory refresh:** every 30 ticks if not breaking blocks, double-clicks slots
- **Cursor overflow:** if cursor has item for > 1 tick, moves to inventory or throws
- **Screen close:** if player rotation changes by > 0.1° while screen open for > 1 tick, closes screen (ignoring Chat, GameMenu, Death, SleepingChat)

**Emma equivalent:** `ToolEquipReflex.java` (partial)

**Emma implementation details:**
- **ToolEquipReflex** covers:
  - Stuck shift: `SHIFT_STUCK_THRESHOLD = 200` ticks (vs old's 10)
  - Stuck cursor: `CURSOR_STUCK_THRESHOLD = 20` ticks (vs old's 1)
  - Better tool equip: uses Emmatone ToolSet API + EndinvBridge fallback
- **No inventory refresh mechanism**
- **No screen close on rotation change**
- **No general "duct tape" periodic fixes**

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Shift release threshold | 10 ticks (0.5 sec) | 200 ticks (10 sec) | Emma takes 20× longer to detect stuck sneak |
| Cursor overflow threshold | 1 tick | 20 ticks (1 sec) | Emma allows cursor items to persist longer |
| Better tool detection | Class-based comparison, avoids hotbar if Baritone pathing | Emmatone ToolSet API (enchantment-aware) | Emma's tool selection is smarter but different triggering |
| Inventory refresh | Every 30 ticks | Not present | Potential client-server desync accumulation |
| Screen close on rotation | Closes if yaw/pitch delta > 0.1° | Not present | Stuck screens possible |
| Tool swap for inventory-only | Swaps from slots ≥ 9 only | Uses ToolSet across all slots + EndinvBridge | Emma searches more broadly |

**Missing edge cases:**
- [ ] Shift release much slower (200 vs 10 ticks) — player may remain stuck sneaking for 10 seconds
- [ ] No inventory refresh — desyncs accumulate
- [ ] No screen-close-on-rotation — screens can block gameplay if stuck open
- [ ] No general periodic watchdog fixes

**Verdict:** PARTIAL — Tool equip reflex covers one aspect. Missing inventory refresh, screen watchdog, and has much slower stuck-shift detection.

---

## 7. `PreEquipItemChain.java`

**Old implementation details:**
- Priority: -1 (background, never interrupts)
- Checks: not eating, current Baritone path has no hard blocks to break or place
- If path is clear: equips sword if any kill-entity task active in the chain
- Uses `BlockStateInterface` to inspect path segments for breakable blocks

**Emma equivalent:** `PreEquipWeaponReflex.java`

**Emma implementation details:**
- PRE_EQUIP_RANGE = 8.0 blocks, MELEE_RANGE = 3.5 blocks
- Trigger: any hostile at distance 3.5-8.0 blocks
- Preconditions: not eating, not shielding
- Action: `CombatHelper.equipBestWeapon(player)`
- Does NOT check pathfinding state or block-breaking needs

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Trigger condition | Kill-entity task active + clear path | Hostile within 3.5-8.0 blocks | Emma is proximity-based vs task-based |
| Path check | Verifies path has no blocks to break/place | No path check | Emma may equip sword while path needs pickaxe |
| Equip timing | Background priority (-1), runs when nothing else needs | Reflex layer, runs every tick before scoring | Emma is faster to react |
| Eating check | Yes | Yes | Same |
| Tool conflict | Won't equip sword if path needs tool | No conflict check | May swap pickaxe for sword mid-mine when mob approaches |

**Missing edge cases:**
- [ ] No path-needs-tool check — may swap mining tool for sword when mob is 7 blocks away but not threatening
- [ ] Tool equip reflex should handle re-equipping the mining tool, but there's a 1-tick gap

**Verdict:** IMPLEMENTED — Different trigger mechanism but functionally equivalent. Tool conflict risk is mitigated by ToolEquipReflex re-equipping.

---

## 8. `SingleTaskChain.java`

**Old implementation details:**
- Abstract base class for chains that run a single task
- `mainTask` field, `interrupted` flag
- Lifecycle: `onTick()` checks interrupted → resets or ticks task → `onTaskFinish()`
- `setTask()`: stops old task, sets and resets new task (object equality compare)
- `onInterrupt()`: sets flag, interrupts running task

**Emma equivalent:** `GoapAction.java` (abstract base)

**Emma implementation details:**
- GoapAction is the base class for all GOAP actions
- Lifecycle: `execute()` (start) → `tick()` (each tick) → `onDeactivated()` (stop)
- `isActive()` query, `getMinimumActiveTicks()` for commitment lock
- No subtask nesting — each action is a flat state machine

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Architecture | Task tree with subtask nesting | Flat state machine per action | Simpler, no deep call stacks |
| Interruption | `interrupted` flag + `onInterrupt()` | `onDeactivated()` called by GoapTicker | Cleaner — no flag checking |
| Task comparison | Object equality for change detection | Action identity via registry | Same concept |
| Minimum commitment | None (can be interrupted any tick) | `getMinimumActiveTicks()` lock | Emma prevents thrashing |

**Verdict:** NOT_NEEDED — Replaced by GoapAction base class with fundamentally different architecture.

---

## 9. `UnstuckChain.java`

**Old implementation details:**
- **Position history:** 500-entry LinkedList (25 seconds at 20 tps)
- **Water stuck:** 100+ entries, in water, not on ground, full air, X/Z variance > 0.75 over 5 seconds → `GetOutOfWaterTask`
- **Powder snow:** `inPowderedSnow()` check → scans for nearest powder snow → `DestroyBlockTask`
- **End portal frame:** standing on frame with `EYE=false` + not eating → press forward once
- **Eating glitch:** `eatingTicks > 140` (7 seconds) → stops eating, sets stuck
- **Entity blocks placement:** while pathing + using item, raycasts 5.0 blocks for entity in way → after 2 ticks, pathfinds to closest place position
- **Shimmy task:** `SafeRandomShimmyTask` for 5 ticks after any stuck resolution
- **Priority:** 55 when active, else NEGATIVE_INFINITY

**Emma equivalent:** `UnstuckAction.java`

**Emma implementation details:**
- **Position buffer:** 100-entry circular buffer (5 seconds at 20 tps, vs old's 25 seconds)
- **Stuck threshold:** displacement < 2.0 blocks over 100 ticks
- **Additional precondition:** Emmatone must be actively pathing (won't trigger while idle)
- **Phase machine:**
  - Phase 1 (0-20 ticks): random movement (forward/back/left/right, 30% jump chance)
  - Phase 2 (21-40 ticks): jump + forward
  - Phase 3 (41-80 ticks): break adjacent blocks (4 cardinal + 1 above)
  - Phase 4 (81+): reset and deactivate
- **Score:** 2.0 (very low, only wins when nothing else scores)
- **No specific detection for:** water stuck, powder snow, end portal frame, eating glitch, entity blocking placement

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| History window | 500 entries (25 sec) | 100 entries (5 sec) | Emma detects stuck faster but may false-positive on brief pauses |
| Stuck criteria | Per-situation (water, snow, portal, eating, entity) | Single displacement check (< 2.0 blocks over 5 sec) | Emma is simpler but less specific — can't distinguish WHY stuck |
| Water stuck detection | X/Z variance check + in-water + full-air | No specific water detection | May not detect treading water (still moving enough to exceed 2.0 threshold) |
| Powder snow | Dedicated detection + block breaking | No specific detection (general stuck may trigger) | Phase 3 block-breaking may help, but not targeted |
| End portal frame | Specific block check + forward press | No specific detection | Could get stuck on unfilled end portal frame |
| Eating glitch | 7-second eating detection | No specific detection | Eating can get stuck indefinitely (EatFoodAction has 36-tick re-fire but no global timeout) |
| Entity blocks placement | Raycast for entity in way → pathfind around | No entity detection | May endlessly try to place against an entity |
| Resolution strategy | Specific fix per cause (break block, swim, step forward) | Generic: random movement → jump → break blocks → give up | Less targeted but covers more cases generically |
| Shimmy after fix | 5-tick SafeRandomShimmyTask | Phase 1 is effectively a shimmy (20 ticks) | Emma's shimmy is longer |

**Missing edge cases:**
- [ ] No water-stuck detection (treading water without making progress)
- [ ] No end portal frame stuck detection
- [ ] No eating glitch detection (eating forever without consuming)
- [ ] No entity-blocking-placement detection (trying to place block against an entity)
- [ ] No powder snow specific detection
- [ ] Shorter history window (5 sec vs 25 sec) may miss slow stucks

**Verdict:** PARTIAL — Generic stuck detection works but lacks the specific-cause detection and targeted fixes of the old system.

---

## 10. `UserTaskChain.java`

**Old implementation details:**
- Manages user-issued task lifecycle: start → tick → finish → callback
- `taskStopwatch`: elapsed time tracking with formatted duration output
- `currentOnFinish`: completion callback
- `runningIdleTask` / `nextTaskIdleFlag`: idle/background task distinction (suppresses TaskFinishedEvent for idle tasks)
- Duration formatting: "X days Y hours Z minutes S.SSS seconds"
- On finish: publishes `TaskFinishedEvent` if not idle, cancels Baritone, runs idle command

**Emma equivalent:** `GoapTicker.java` action management

**Emma implementation details:**
- No concept of "user task" — all actions are GOAP-scored
- `SetGoalsHandler` sets goals (not tasks), `GoalDecomposer` creates sub-goals
- No task stopwatch or duration tracking
- No completion callbacks
- No idle task distinction
- Action switching logged with score, HP, food count

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Architecture | User issues tasks, chain manages lifecycle | User sets goals, GOAP selects actions | Fundamentally different |
| Duration tracking | Stopwatch with formatted output | Not tracked | No "task took X minutes" reporting |
| Completion callbacks | `currentOnFinish` callback | No callback — goals are removed when satisfied | Different notification model |
| Idle tasks | Explicit idle/background distinction | No distinction — all actions compete by score | Simpler |

**Verdict:** NOT_NEEDED — Replaced by goal-based GOAP system. No direct equivalent needed.

---

## 11. `WorldSurvivalChain.java`

**Old implementation details:**
- **Drowning:** if in water + air < maxAir + not pathing → `jumping = true`
- **Lava detection:** `isInLavaOhShit()` — in lava without fire resistance, OR on fire + recently in lava (1 tick window via `wasInLavaTimer`)
  - Triggers: `EscapeFromLavaTask` (priority 100)
- **Proactive lava avoidance:** velocity-based lookahead — checks 1-2 blocks ahead in movement direction at feet and below
  - Triggers: `SafeRandomShimmyTask` (priority 95)
- **Fire escape:** on fire + no fire resistance + touching `AbstractFireBlock`
  - Triggers: `DoToClosestBlockTask(PutOutFireTask)` (priority 100)
- **Water extinguishing:** on fire + no fire resistance + has water bucket
  - Places water on solid block below player → retrieves bucket after
  - Priority: 90 (place) / 60 (retrieve)
- **Portal stuck detection:** in portal + NOT executing `EnterNetherPortalTask` → after 5 ticks, `SafeRandomShimmyTask` (priority 60)
  - Sets `interactionPaused = true` while stuck
- **wasInLavaTimer = 1 tick** — debounce for lava/fire transition

**Emma equivalent:** `EnvironmentalHazardAction.java`

**Emma implementation details:**
- **Hazard urgency tiers:**
  - Lava: 1.5
  - Dragon breath: 1.3
  - Drowning (< 1/3 air): 1.4
  - Fire: 1.2
  - Poison/wither: 0.6-0.9
  - Powder snow: 0.8
  - Magma/wither_rose/sweet_berry: 0.7
- **Health scaling:** `urgency *= 1.0 + (1.0 - health/maxHealth)`
- **Block hazard scan:** feet, below, 4 cardinal directions for fire/magma/wither_rose/berry
- **Drowning response:** `DirectInput.setJumping(true) + setForward(true)` (swim up and forward)
- **Lava/fire/hazards:** `GoalRunAway(10.0, currentPos)` via Emmatone (flee 10 blocks from current position)
- **Score:** `survive_priority × urgency`
- **No proactive lava avoidance** (velocity lookahead)
- **No water bucket extinguishing** (place water then pick up)
- **No portal stuck detection** (separate: EnterPortalAction exists)

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Drowning response | Jump only (waits for pathfinder) | Jump + forward (actively swims) | Emma is more aggressive about escaping water |
| Lava detection | Active lava check + 1-tick fire debounce | `player.isInLava()` direct check | Old handled lava-to-fire transition better |
| Proactive lava avoidance | Velocity-based 2-block lookahead | Not present | Emma walks into lava before reacting |
| Fire response | Breaks fire block (PutOutFireTask) | Flees from fire area (GoalRunAway 10 blocks) | Old eliminated the fire; Emma just runs away (fire persists) |
| Water extinguishing | Places water bucket → retrieves after | Not present | Old could self-extinguish with water bucket; Emma has no remedy |
| Portal stuck | 5-tick detection + shimmy | Not in EnvironmentalHazardAction (EnterPortalAction handles portals) | Different routing |
| Hazard coverage | Lava, fire, drowning, portal | Lava, fire, drowning, dragon breath, powder snow, poison/wither, magma, wither rose, berry bush | Emma covers more hazard types |
| Status effect handling | Not covered | Poison/wither urgency 0.6-0.9 | Emma at least tracks DOT effects (no active remedy though) |
| Health scaling | Priority constants (100, 95, 90, 60) | `urgency *= healthScaler` (continuous) | Emma scales urgency with health damage |

**Missing edge cases:**
- [ ] No proactive lava avoidance — doesn't look ahead in movement direction
- [ ] No fire block breaking — flees from fire instead of removing it (fire persists for next pass)
- [ ] No water bucket self-extinguishing
- [ ] No lava/fire transition debounce (1-tick timer in old code)
- [ ] No DOT effect active remedy (milk, golden apple) — just tracks urgency

**Verdict:** PARTIAL — Covers more hazard types but lacks proactive avoidance and active remedies (fire breaking, water placement).
