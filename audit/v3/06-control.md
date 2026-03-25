# Audit V3 — 06: control/ (8 files)

Old: `adris/altoclef/control/`
New: `com/emma/bridge/control/` + `com/emma/bridge/util/CombatHelper.java` + `com/emma/bridge/goap/actions/ScreenHelper.java` + reflexes

---

## 1. `BlockInteraction.java`

**Old implementation details:**
- Reach check: dynamic via `mod.getClientBaritone().getPlayerContext().playerController().getBlockReachDistance()`
- Face computation: eye-to-block-center vector, picks axis with max absolute value. Priority: X >= Y >= Z
- `createHitResult()`: hit point at block center + face_offset * 0.5 (face center). `isInside = false`
- `createFluidHitResult()`: hit point at block CENTER (not face edge), face auto-computed
- `rightClickBlock()`: supports optional `facing` param to override player yaw (SOUTH→0, WEST→90, NORTH→180, EAST→-90). Calls `interactBlock(player, MAIN_HAND, bhr)`. Always swings hand regardless of result.
- `rightClickFluid()`: validates player not standing inside block + fluid is still source (not flowing). Creates fluid hit result at center.
- `tryCollectFluid()`: **two-tick pattern** — tick 1: calls lookAt() + checks `isLookingAt(pos, 5.0°)` → false, returns false. Tick 2: already facing → calls `interactItem()`. Validates reach, not-inside, has LOS, bucket equipped.
- `tryDumpFluid()`: same two-tick pattern as collect
- `isLookingAt()`: dot product of look vector and direction-to-target, converted to degrees via acos. Returns angle < tolerance.
- `startBreaking()`: calls `attackBlock(pos, face)` + swings hand
- `continueBreaking()`: calls `updateBlockBreakingProgress(pos, face)` + swings hand
- `fluidLOSObstruction()`: raycast with COLLIDER+SOURCE_ONLY, returns obstructing block pos or null
- Angle tolerance for fluid ops: **5.0 degrees**

**Emma equivalent:** `com/emma/bridge/control/BlockInteraction.java`

**Emma implementation details:**
- Reach check: dynamic via `EmmatoneAPI.getProvider().getPrimaryEmmatone().getPlayerContext().playerController().getBlockReachDistance()` (Emmatone instead of Baritone, same pattern)
- Face computation: same algorithm — eye-to-center, max absolute axis, same X >= Y >= Z priority
- `createHitResult()`: same — block center + face_offset * 0.5, `isInside = false`
- `createFluidHitResult()`: same — center point, auto-computed face
- `rightClickBlock()`: supports optional `facing` override (same yaw mapping). Calls `useItemOn(player, MAIN_HAND, bhr)` (MC 26.1 API rename of interactBlock). Always swings hand.
- `rightClickFluid()`: same validation (not inside + still source). Same center hit result.
- `tryCollectFluid()`: **same two-tick pattern** — `isLookingAt(pos, 5.0°)` gate. Same validation chain (reach, not-inside, LOS, bucket).
- `tryDumpFluid()`: same two-tick pattern
- `isLookingAt()`: same dot product + acos approach
- `startBreaking()`: calls `startDestroyBlock(pos, face)` (26.1 rename) + swings
- `continueBreaking()`: calls `continueDestroyBlock(pos, face)` (26.1 rename) + swings
- `fluidLOSObstruction()`: same raycast pattern
- **Additional:** `lastInteractedBlockPos` static field tracked for ContainerTracker association
- **Additional:** `placeBlock(IPlayerContext, BlockPos, Direction)` — Emmatone pathfinder placement API. Only swings on SUCCESS (vs. old always-swings).
- **Additional:** `forceEquipItem(Item)` — full hotbar+inventory search+swap logic. Old code had this in SlotHandler, Emma inlines it in BlockInteraction.
- **Additional:** `calcYawPitch()` and `lookAt()` — delegates to Emmatone RotationUtils. Old code handled rotation via InputControls.forceLook() or LookHelper.

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Reach distance source | Baritone PlayerContext | Emmatone PlayerContext | Same concept, different pathfinder |
| MC API names | interactBlock, attackBlock, updateBlockBreakingProgress | useItemOn, startDestroyBlock, continueDestroyBlock | MC 26.1 renames only |
| Angle tolerance | 5.0° | 5.0° | Same |
| Face computation algo | Max absolute axis, X≥Y≥Z priority | Same | Same |
| Hit result construction | center + face*0.5 | Same | Same |
| Swing behavior (rightClick) | Always swings | Always swings | Same |
| Swing behavior (placeBlock) | N/A (no separate place method) | Only swings on SUCCESS | Emma is more correct |
| forceEquipItem location | SlotHandler.forceEquipItem() | BlockInteraction.forceEquipItem() | Different file, same concept |
| Equipment search | SlotHandler scans screen slots | BlockInteraction scans inventory 0-8 then 9-35 with PICKUP+swap | Emma uses raw inventory indices, old used screen handler slots |
| Endless Inventory fallback | No | EndinvBridge.extractToSlot() | Emma can pull items from EndinvBridge |
| Rotation control | Separate LookHelper/InputControls | Inline lookAt() via Emmatone RotationUtils | Different plumbing, same result |
| Container tracking | No | lastInteractedBlockPos static | Emma tracks which block was right-clicked for container association |

**Missing edge cases:**
- None significant — BlockInteraction is nearly 1:1 across both codebases

**Verdict:** IMPLEMENTED

---

## 2. `DirectInput.java`

**Old implementation details:**
- `setSneaking()`: calls `player.setSneaking(sneaking)` — null-safe
- `setSprinting()`: calls `player.setSprinting(sprinting)` — null-safe
- `isSneaking()`: returns `player.isSneaking()`
- `isJumping()`: if ManualSteering active, reads from ManualSteering; else reads `player.input.playerInput.jump()`
- `setForward/Back/Left/Right/Jumping()`: delegates to ManualSteering via `ensureSteering()` + setter
- `ensureSteering()`: auto-starts ManualSteering on first press, does NOT auto-stop
- `pressForwardOnce()`: starts steering, sets forward=true
- `setUseHeld()`: sets `options.useKey.setPressed(held)` directly

**Emma equivalent:** `com/emma/bridge/control/DirectInput.java`

**Emma implementation details:**
- `setSneaking()`: calls `player.setShiftKeyDown(sneaking)` (26.1 rename of setSneaking)
- `setSprinting()`: calls `player.setSprinting(sprinting)`
- `isSneaking()`: returns `player.isShiftKeyDown()` (26.1 rename)
- `isJumping()`: reads `player.input.keyPresses.jump()` (26.1 renamed from playerInput)
- `setForward/Back/Left/Right/Jumping()`: same pattern — `ensureSteering()` + ManualSteering setter
- `ensureSteering()`: same logic — auto-starts on press, no auto-stop
- `pressForwardOnce()`: same
- `setUseHeld()`: calls `options.keyUse.setDown(held)` (26.1 rename)

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Sneak API | player.setSneaking() | player.setShiftKeyDown() | MC 26.1 rename only |
| Jump query | player.input.playerInput.jump() | player.input.keyPresses.jump() | MC 26.1 rename only |
| Use key API | options.useKey.setPressed() | options.keyUse.setDown() | MC 26.1 rename only |

**Missing edge cases:** None — 1:1 port with API renames.

**Verdict:** IMPLEMENTED

---

## 3. `InputCleanup.java`

**Old implementation details:**
- `reset()`: sets sneak=false, sprint=false, releases use hold, releases jump key, stops ManualSteering. Null-safe player check.
- Order: sneak → sprint → useHeld → jumpKey → ManualSteering.stop()

**Emma equivalent:** Not found as a dedicated class.

**Emma implementation details:**
- No `InputCleanup` class exists. Input cleanup is scattered:
  - `GoapTicker` resets ManualSteering via `ManualSteering.stop()` when switching actions
  - `GoapAction` subclasses individually clean up their inputs in `abort()` methods
  - `DirectInput.setUseHeld(false)` called in reflex `release()` methods (ShieldBlockReflex, etc.)
- No single centralized "reset all inputs" method

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Reset approach | Single method resets ALL 5 inputs + stops steering | Distributed across action abort() methods and reflex release() | Risk of orphaned input state if an action crashes without calling abort() |
| Sneak reset | Always reset in cleanup | Reset per-action | If action crashes mid-sneak, player stays sneaking |
| Sprint reset | Always reset in cleanup | Reset per-action | Same orphan risk |
| Use-held reset | Always reset in cleanup | Reset in ShieldBlockReflex.release(), eating action abort | Same orphan risk |
| Jump reset | Always reset in cleanup | Not explicitly reset in a centralized place | Player could remain jumping after crash |

**Missing edge cases:**
- [ ] No centralized input state reset. If a GOAP action throws an exception mid-execution, orphaned input states (sneak, sprint, use-held, jump) may persist until another action explicitly clears them.
- [ ] Old code called this on every task cancel/completion. Emma relies on each action cleaning up after itself.

**Verdict:** PARTIAL — Functionality exists but is distributed. Missing centralized crash-safe cleanup.

---

## 4. `InputControls.java`

**Old implementation details:**
- Fully deprecated — all methods are no-ops except `forceLook(yaw, pitch)` which directly sets player rotation
- `rejectClicks()`: throws UnsupportedOperationException if anyone tries to use click inputs through this class
- Purpose: transition artifact from Phase 52, kept only for `forceLook()`

**Emma equivalent:** None needed — rotation is handled via `BlockInteraction.lookAt()` and Emmatone RotationUtils.

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| forceLook | Direct player.setYaw/setPitch | BlockInteraction.lookAt() via Emmatone RotationUtils | Same result, different path |
| Click rejection | Throws exception (dev guard) | N/A — no legacy code path exists | N/A |

**Verdict:** NOT_NEEDED — Deprecated file, all remaining functionality (`forceLook`) is handled by `BlockInteraction.lookAt()`.

---

## 5. `KillAura.java`

**Old implementation details:**
- **Strategy enum:** OFF, FASTEST, DELAY, SMART
- **forceFieldRange:** defaults to `Double.POSITIVE_INFINITY`, configurable
- **Per-tick lifecycle:** `tickStart()` clears targets → `applyAura(entity)` adds targets → `tickEnd()` executes attacks/shields
- **Target sorting:** `targets.stream().min(squaredDistanceTo)` — nearest entity
- **Attack cooldown:** checks `player.getAttackCooldownProgress(0) < 1` — waits for full cooldown in DELAY/SMART mode. FASTEST ignores cooldown.
- **Ground check for melee:** only attacks if `onGround OR velocity.Y < 0 OR in water`
- **Force hit priority:** FireballEntity always gets forceHit status (immediate attack)
- **Lead prediction:** `ProjectileHelper.getLeadPredictedAimPos(eyePos, entity, 1.5f)` for aiming (except Fireballs — center point)
- **Shield logic (complex):**
  - Shield activation guards: no potions, within range, not falling, MLG done, not chorus fruit effect
  - Shield excluded for: Creeper, Hoglin, Zoglin, Warden, Wither (melee mobs where blocking doesn't help)
  - Shield equip: moves to offhand via `forceEquipItemToOffhand(Items.SHIELD)`
  - Shield activation: pauses Baritone pathing, starts sneaking, fires offhand interact, holds use key
  - Shield re-fire: if `shieldActive && !player.isBlocking()`, re-fires offhand interact (handles knockback interrupts)
  - Shield deactivation: restores Baritone, stops sneaking, releases use, stops item use
  - Food-in-hand conflict: if offhand has food during shield equip, moves food to inventory first
- **Distance fallback:** attacks within `forceFieldRange²` OR within `40` blocks squared (hard fallback)
- **Weapon equip:** calls `MobDefenseChain.getBestWeapon()` then `slotHandler.forceEquipItem()`
- **Sneak multiplier during shield:** movement reduced via ManualSteering 0.3f (inherited from Input)

**Emma equivalent:** `CombatHelper.java` + `ForceFieldReflex.java` + `ShieldBlockReflex.java` + `PreEquipWeaponReflex.java`

**Emma implementation details:**
- **No strategy enum** — ForceFieldReflex is always-on when threats are in melee range
- **ForceField melee range:** `MELEE_RANGE = 3.5` blocks (hardcoded constant vs old's configurable infinity)
- **ForceField per-tick:** fires every tick while threat within 3.5 blocks. Scans AABB inflated by MELEE_RANGE for Monster instances.
- **Attack cooldown:** `CombatHelper.tryAttack()` checks `player.getAttackStrengthScale(0.0f) < 1.0f` → returns false (same cooldown gate as old DELAY mode)
- **Ground check for melee:** NOT present in CombatHelper.tryAttack() — attacks regardless of ground state
- **Force hit priority (Fireballs):** NOT present — no special fireball handling
- **Lead prediction:** NOT present — looks at entity eye position directly, no lead-ahead
- **Enderman filter:** ForceFieldReflex skips neutral endermen via `!enderMan.isCreepy()` (old code didn't have this check in KillAura)
- **Shield logic (ShieldBlockReflex):**
  - Trigger conditions: has shield, not eating, shield not on cooldown
  - Minimum hold: `MIN_HOLD_TICKS = 5` (old had no minimum hold, held until threat cleared)
  - Creeper check: `creeper.getSwelling(1.0f) > 0.5f` within 6 blocks (old EXCLUDED creepers from shielding)
  - Ranged threat checks: AbstractSkeleton isUsingItem (12 blocks), Pillager isUsingItem (12 blocks), Blaze always (12 blocks), Drowned with trident (12 blocks)
  - Projectile check: any WorldState projectile < 8.0 blocks
  - Shield equip: finds shield slot, clicks to offhand slot 45 (same PICKUP+swap pattern)
  - Shield activation: sets `GoapStateFlags.isShielding`, sets use key held. Does NOT pause pathfinding (old paused Baritone).
  - Shield re-fire: NOT present (no check for knockback-interrupted shield)
  - Shield deactivation: clears flag, releases use key, resets holdTicks
  - Food-in-hand conflict: NOT handled (old moved food out of offhand first)
- **Weapon equip:** `CombatHelper.equipBestWeapon()` scans all inventory for highest ATTACK_DAMAGE attribute. Reads actual DataComponents (old used MobDefenseChain helper).
- **Weapon damage calc:** reads `Attributes.ATTACK_DAMAGE` modifier, base = 1.0 + modifier amount
- **No configurable range** — ForceField is hardcoded 3.5, ShieldBlock scans are 6/12/8

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Strategy selection | Enum: OFF/FASTEST/DELAY/SMART | Always DELAY-equivalent (cooldown-gated) | No FASTEST (spam) mode. SMART's fireball priority lost. |
| ForceField range | Configurable, default infinity | Hardcoded 3.5 blocks | Emma only attacks in melee range; old could be set wider |
| Distance fallback | forceFieldRange² OR < 40 | AABB inflated 3.5 | No 40-block fallback |
| Ground check for attack | onGround OR velocity.Y<0 OR in water | None | Emma attacks while airborne/falling — may not get crit bonus or could miss due to knockback |
| Fireball deflection | forceHit priority, immediate attack | Not present | Ghast fireballs can't be reflected |
| Lead prediction | 1.5f velocity lead on aim | Direct eye position | Miss moving targets at range |
| Shield vs Creeper | EXCLUDED creeper from shield | INCLUDED — shields when fuse > 50% | Old: don't waste shield cooldown on creeper explosion. Emma: block the explosion. Different strategies, Emma's is arguably better. |
| Shield for Hoglin/Zoglin/Warden/Wither | Excluded | Not checked (shields against everything) | Marginal — these mobs can break shield guard anyway |
| Shield minimum hold | None (instant drop) | 5 ticks minimum | Emma keeps shield up briefly after threat passes; prevents flicker |
| Shield re-fire on interrupt | Yes — re-activates if shieldActive but not blocking | No | Knockback interrupts shield, Emma doesn't restart it |
| Shield pauses pathing | Yes (Baritone paused) | No (Emmatone keeps pathing) | Emma walks while shielding; old stopped to block. Could be better or worse depending on situation. |
| Food conflict during shield | Moves food from offhand before equipping shield | Not handled | If food is in offhand, shield equip could fail or behave unexpectedly |
| Weapon damage source | MobDefenseChain.getBestWeapon() helper | DataComponents.ATTRIBUTE_MODIFIERS + Attributes.ATTACK_DAMAGE | Emma reads actual damage; old used a helper method (need to check what it did) |
| Enderman neutral check | Not in KillAura | ForceFieldReflex skips non-creepy endermen | Emma improvement — won't aggro neutral endermen |
| Weapon equip scope | Screen handler slots | Inventory 0-35 + EndinvBridge fallback | Emma also searches EndinvBridge for weapons |
| Multi-target attack | FASTEST mode hits ALL targets per tick | ForceField hits only nearest | Emma never hits multiple mobs per tick |

**Missing edge cases:**
- [ ] No fireball deflection — ghast fireballs cannot be punched back
- [ ] No lead prediction for moving targets — will miss entities strafing at range
- [ ] No ground check before attacking — airborne attacks miss crit bonus opportunity and may miss due to momentum
- [ ] No shield re-fire after knockback interrupt — shield stays down until next shouldFire() check (1 tick later at minimum, but may miss fast follow-up attacks)
- [ ] No food-in-offhand conflict resolution during shield equip
- [ ] No configurable force field range
- [ ] No FASTEST mode for spam attacks (PvP or swarmed)
- [ ] No multi-target cycling (only hits nearest)

**Verdict:** PARTIAL

---

## 6. `ManualSteering.java`

**Old implementation details:**
- Singleton pattern: static `instance` field
- `start()`: saves current `player.input`, installs ManualSteering instance. No-op if Baritone's `ControlledInput` is active (detected by class name contains "ControlledInput").
- `stop()`: restores saved input, or creates fresh `KeyboardInput` if savedInput null
- `tick()`: creates `PlayerInput` from desired flags, calculates forward/sideways multipliers, applies 0.3f sneak multiplier, outputs `Vec2f(sideways, forward)` as movementVector
- `getMovementMultiplier()`: conflicting directions (both pressed) = 0.0f; single direction = ±1.0f
- `clearAll()`: zeros all flags without uninstalling

**Emma equivalent:** `com/emma/bridge/control/ManualSteering.java`

**Emma implementation details:**
- Same singleton pattern
- `start()`: saves current input, installs instance. No-op if Emmatone's `ControlledInput` active (same class name check).
- `stop()`: same restore logic
- `tick()`: creates `Input` from desired flags (26.1 renamed from PlayerInput), same forward/sideways calculation, same 0.3f sneak multiplier, outputs `Vec2(sideways, forward)` as moveVector (26.1 renamed)
- `getMovementMultiplier()`: same logic
- `clearAll()`: same

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Input class | PlayerInput | Input (26.1 rename) | API rename only |
| Movement vector | Vec2f → movementVector | Vec2 → moveVector | API rename only |
| Pathfinder detection | "ControlledInput" class name check | Same | Same |
| Sneak multiplier | 0.3f | 0.3f | Same |
| Conflict resolution | Both pressed = 0.0f | Same | Same |

**Missing edge cases:** None — 1:1 port.

**Verdict:** IMPLEMENTED

---

## 7. `PlayerExtraController.java`

**Old implementation details:**
- **Block break tracking:** subscribes to `BlockBreakingEvent` (pos + progress) and `BlockBreakingCancelEvent` (reset) via EventBus
- `blockBreakPos`: current block being broken (null when idle)
- `blockBreakProgress`: 0.0–1.0 progress value
- `isBreakingBlock()`: returns `blockBreakPos != null`
- `getBreakingBlockProgress()`: returns progress (for UI or timeout decisions)
- **Entity range check:** `inRange(entity)` uses dynamic `mod.getModSettings().getEntityReachRange()`
- **Attack:** `attack(entity)` is range-gated — returns immediately if `!inRange()`. Calls `mod.getController().attackEntity(player, entity)` + swing hand.

**Emma equivalent:** `CombatHelper.tryAttack()` for attack. No direct block break progress tracking.

**Emma implementation details:**
- **Block break tracking:** NOT present as a dedicated tracker. Block breaking progress is managed internally by `MineBlockAction` state machine (BREAKING state, uses `gameMode.getDestroyProgress()` or just calls continueBreaking each tick)
- **Entity range check:** `CombatHelper.tryAttack()` does NOT check range — it only checks attack cooldown. Range checking is done by callers (ForceFieldReflex uses AABB inflated 3.5, AttackEntityAction checks its own nav distance).
- **Attack:** `CombatHelper.tryAttack()` checks cooldown, equips weapon, calls `gameMode.attack(player, target)` + swing. No range gate in the method itself.

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Block break progress tracking | Dedicated EventBus-subscribed tracker | No dedicated tracker; MineBlockAction manages internally | Cannot query "is any block being broken?" globally. Old used this for detecting stuck mining. |
| Block break cancellation event | Tracked via BlockBreakingCancelEvent | Not tracked | No detection of ghost block-breaking state after interruption |
| Entity reach range | Dynamic from mod settings | No centralized range check (caller-side) | Different callers may use inconsistent ranges |
| Attack range gate | Inside attack() method | Not in CombatHelper.tryAttack() — caller responsibility | If caller forgets range check, attacks from too far (server rejects) |
| Attack API | mod.getController().attackEntity() | gameMode.attack() | Same effect, different API path |

**Missing edge cases:**
- [ ] No block break progress query — old used this for stuck detection and to avoid re-starting already-in-progress breaks
- [ ] No block break cancellation detection — ghost breaking states after interruption
- [ ] No centralized entity reach range check — inconsistent range enforcement across callers

**Verdict:** PARTIAL — Attack functionality present but block-break tracking and centralized range gating are missing.

---

## 8. `SlotHandler.java`

**Old implementation details:**
- **Timer-based throttle:** `slotActionTimer` with configurable interval from `mod.getModSettings().getContainerItemMoveDelay()`. `canDoSlotAction()` returns false if timer hasn't elapsed.
- **Override flag:** `overrideTimerOnce` bypasses timer for critical actions (forced equips)
- **clickSlot():** validates window slot != -1 (invalid slot redirects to UNDEFINED/PICKUP). Delegates to `clickWindowSlot()` which calls `mod.getController().clickSlot(syncId, windowSlot, mouseButton, type, player)` with try-catch.
- **forceEquipItem(Item):** targets hotbar slot 1, uses SWAP action for screen items. If item is in cursor, uses PICKUP instead. Returns true/false.
- **forceEquipItemToOffhand(Item):** finds item in inventory, clicks it (PICKUP), then clicks offhand (PICKUP) to swap.
- **forceDeequipHitTool():** deequips items with `DataComponentTypes.TOOL` component. Complex three-case logic: cursor-is-bad, equip-is-bad, equip-empty.
- **forceDeequipRightClickableItem():** long list of right-clickable items (bucket, bow, crossbow, flint_and_steel, ender_pearl, fishing_rod, compass, shield, etc.) — deequips to prevent accidental use.
- **refreshInventory():** double-clicks each slot with PICKUP to force server sync.
- **Eating interlock:** `forceEquipItem(ItemTarget, unInterruptable)` checks `mod.getFoodChain().isTryingToEat()` — refuses to equip unless unInterruptable=true.

**Emma equivalent:** `BlockInteraction.forceEquipItem()` + `ScreenHelper.java` + `CombatHelper.equipBestWeapon()` + `ShieldBlockReflex.equipShieldToOffhand()`

**Emma implementation details:**
- **No timer-based throttle.** No `canDoSlotAction()` or delay between slot operations.
- **No override flag.**
- **BlockInteraction.forceEquipItem(Item):** searches hotbar 0-8 (setSelectedSlot), then main inventory 9-35 (PICKUP + swap to slot 36 + cleanup cursor), then EndinvBridge fallback.
- **CombatHelper.equipBestWeapon():** searches all slots for highest ATTACK_DAMAGE, swaps to held slot via PICKUP.
- **ShieldBlockReflex.equipShieldToOffhand():** finds shield via InventoryScanner, converts to screen slot (hotbar 0-8 → +36, main 9-35 → same), clicks source PICKUP, clicks offhand 45 PICKUP, cleans up cursor.
- **ScreenHelper:** minimal — only `closeIfOpen()` and `findItem()` (linear scan).
- **No forceDeequipHitTool()** — no method to deequip tools before placement.
- **No forceDeequipRightClickableItem()** — no method to deequip right-clickable items.
- **No refreshInventory()** — no double-click slot sync.
- **No eating interlock** — no check for ongoing eating when equipping items.

**Implementation differences:**

| Aspect | Old (AltoClef) | New (Emma) | Impact |
|--------|---------------|------------|--------|
| Slot action throttle | Timer-based, configurable delay | None | Emma may spam slot clicks faster than server processes; could cause desyncs |
| Override mechanism | overrideTimerOnce flag | N/A | N/A (no throttle to override) |
| Equip target slot | Always hotbar 1 | Selected slot or slot 0 | Minor — old always used slot 1, Emma uses current or first |
| Equip action type | SWAP for screen items, PICKUP for cursor | PICKUP + swap for all | Same end result, different packet sequence |
| Offhand equip | Dedicated method, uses PICKUP chain | ShieldBlockReflex-specific, same PICKUP chain | Same logic, shield-only in Emma |
| Deequip hit tool | Yes — prevents tool damage during placement | No | Emma may accidentally use a tool when right-clicking a block (e.g., axe strips logs instead of opening container) |
| Deequip right-clickable | Yes — prevents accidental use of 20+ item types | No | Emma may accidentally throw ender pearls, use flint_and_steel, eat food, etc. when trying to interact |
| Inventory refresh | Double-click sync | No | May accumulate client-server inventory desync over time |
| Eating interlock | Checks isTryingToEat() | No | May interrupt eating to equip tools |
| Slot validation | Window slot -1 check with redirect | No validation | Invalid slot clicks could cause errors |
| Error handling | Try-catch on clickSlot | No try-catch visible | Exceptions during slot clicks may crash action |
| EndinvBridge fallback | No | Yes (BlockInteraction.forceEquipItem) | Emma can pull items from Endless Inventory |

**Missing edge cases:**
- [ ] No slot action throttle — rapid slot clicks may cause server desync
- [ ] No deequip mechanism for tools — accidentally strip logs, trigger hoes on farmland, etc.
- [ ] No deequip mechanism for right-clickable items — accidentally throw ender pearls, use potions, etc.
- [ ] No inventory refresh/sync mechanism
- [ ] No eating interlock — equip actions can interrupt food consumption
- [ ] No try-catch on slot click operations
- [ ] No invalid window slot validation

**Verdict:** PARTIAL — Core equip/swap functionality present but missing safety guards (throttle, deequip, eating interlock, error handling).
