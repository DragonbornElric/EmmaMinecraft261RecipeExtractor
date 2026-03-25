# Audit V3 Summary — Implementation-Level Comparison

**Scope:** 445 old AltoClef Java files compared against EmmaMinecraft261 by reading actual source code from both codebases. Every claim references specific constants, algorithms, and methods from the real code.

---

## Verdict Distribution

| Verdict | Count | Meaning |
|---------|-------|---------|
| IMPLEMENTED | ~180 | Equivalent logic confirmed by code reading |
| PARTIAL | ~65 | Logic exists but specific behaviors/edge cases missing |
| GAP | ~30 | Functionality absent, potentially needed |
| NOT_NEEDED | ~170 | Obsolete (multiversion=37, event types=18, task framework=7, abstract bases, etc.) |

---

## All GAPS — Consolidated & Prioritized

### CRITICAL — Blocks core gameplay chains

| # | Gap | Audit File | Detail |
|---|-----|-----------|--------|
| C1 | No bucket filling action | 16-resources | `BlockInteraction.tryCollectFluid()` API exists but no GOAP action triggers it autonomously. Cannot fill water/lava buckets. Blocks MLG pre-stocking, obsidian creation, water-based portal construction. |
| C2 | No manual crafting fallback | 11-container | Recipe book-only crafting. If `RecipeBookLookup.findFirstCraftingRecipe()` returns null (modded items, edge cases), crafting fails entirely. Old code fell back to per-slot manual placement. |
| C3 | No ranged combat (bow/crossbow) | 13-entity | `ShootArrowSimpleProjectileTask` has no equivalent. Cannot destroy end crystals at range, fight blazes safely, or engage phantoms from distance. |

### HIGH — Significantly impacts autonomous survival

| # | Gap | Audit File | Detail |
|---|-----|-----------|--------|
| H1 | No entity blacklisting | 21-trackers | Old: UUID-based, 60s timeout, max 20 entries. New: nothing. Mobs stuck behind fences/walls/trees are targeted indefinitely, wasting all time. |
| H2 | No item discard for field inventory | 18-speedrun | Old: `UselessItems` list + `EnsureFreeInventorySlotTask`. New: only deposits in containers. When inventory full and no container nearby, bot deadlocks. |
| H3 | No food blacklist / food scoring | 03-chains | Old: rotten flesh -100 penalty, saturation×8 when low health, spider eye excluded. New: picks highest hunger value only. Eats pufferfish, spider eye, rotten flesh without penalty. |
| H4 | No fortress/structure finding | 16-resources | Old: `SearchChunkForBlockTask(Nether Bricks)` to find fortresses. New: wanders randomly. Cannot navigate to Nether Fortress for blaze rods. |
| H5 | No centralized input cleanup | 06-control | Old: `InputCleanup.reset()` clears all 5 input states on task cancel. New: distributed across action abort() methods. Crash mid-action leaves orphaned input states (stuck sneaking/sprinting/using). |
| H6 | No spawner camping for blazes | 16-resources | Old: camps within 4 blocks of blaze spawner, lava avoidance, LOS check, health-based flee. New: simple nearest-mob chase, no spawner awareness. |
| H7 | No crop maturity checking | 16-resources | Old: `_wasFullyGrown` set tracked maturity, only broke mature crops. New: breaks immature wheat/potatoes/carrots, losing yield. |

### MEDIUM — Reduces efficiency or blocks specific features

| # | Gap | Audit File | Detail |
|---|-----|-----------|--------|
| M1 | No piglin bartering | 16-resources | Old: full trading implementation with safety (hoglin avoidance, blacklisting, baby filter). New: absent. Blocks reliable nether ender pearls and obsidian. |
| M2 | No bucket portal method | 18-speedrun | `BuildNetherPortalAction` requires pre-mined obsidian. Old had bucket method (water+lava → obsidian in-place). Critical for early game without diamond pickaxe. |
| M3 | No furnace type preference | 11-container | Old: 3 separate tasks (furnace, blast, smoker). New: uses nearest of any type. Misses 2× speed from blast furnace (ores) and smoker (food). |
| M4 | No fuel efficiency ranking | 11-container | Old: best-fit algorithm (smallest overshoot). New: first fuel item found. May burn coal blocks when sticks suffice. |
| M5 | No uniform material enforcement | 16-resources | Old: `sameMask` ensures all planks in door recipe are same wood type. New: resolves per-slot independently. Mixed plank beds possible. |
| M6 | No visited-area exploration tracking | 15-movement | Old: marks explored chunks, avoids re-searching. New: random target generation. May repeatedly explore same areas. |
| M7 | No follow-player action | 15-movement | Old: `FollowPlayerTask` with distance parameter. New: nothing. Cannot continuously follow another player. |
| M8 | No smithing table action | 18-speedrun | Recipe data exists (SMITH entries in ItemRecipeRegistry). No GOAP action executes them. Cannot upgrade to netherite. |
| M9 | No furnace recovery | 11-container | Old: breaks and collects placed furnaces after use. New: furnaces left in world. Resource waste and world clutter. |
| M10 | No enemy check before eating | 03-chains | Old: 10-block radius scan before starting to eat, 4-block while eating. New: no pre-eat enemy scan. May start eating while skeleton is shooting. |
| M11 | No projectile trajectory prediction | 15-movement | Old: `ProjectileHelper` with ballistic math, velocity-based closest-approach. New: `dot(velocity, toPlayer) > 0.3` direction check only. |
| M12 | No gravel recycling for flint | 16-resources | Old: place-and-remine gravel loop (10% flint chance). New: mine only. Much slower flint collection. |
| M13 | No lead prediction for combat aiming | 06-control | Old: `getLeadPredictedAimPos(eyePos, entity, 1.5f)`. New: looks directly at eye position. Misses moving targets. |
| M14 | No shield re-fire on knockback | 06-control | Old: detects `shieldActive && !isBlocking()`, re-fires offhand interact. New: shield stays down until next `shouldFire()` check. |
| M15 | No deequip mechanism | 06-control | Old: `forceDeequipHitTool()` prevents accidental tool use, `forceDeequipRightClickableItem()` prevents ender pearl throws, etc. New: nothing. May strip logs when trying to open chests, throw ender pearls accidentally. |

### LOW — Quality of life and edge cases

| # | Gap | Audit File | Detail |
|---|-----|-----------|--------|
| L1 | No proactive lava avoidance | 03-chains | Old: velocity-based 2-block lookahead. New: reacts only when already in lava. |
| L2 | No fire block breaking | 03-chains | Old: PutOutFireTask extinguishes fire blocks. New: EnvironmentalHazardAction flees from fire. Fire persists for next pass. |
| L3 | No water bucket self-extinguishing | 03-chains | Old: places water then retrieves bucket when on fire. New: no active fire remedy. |
| L4 | No auto-reconnect on disconnect | 03-chains | Old: DeathMenuChain auto-reconnects on server kick. New: stays disconnected. |
| L5 | No death commands | 03-chains | Old: configurable commands executed on death with {deathmessage} template. New: none. |
| L6 | No slot action throttle | 06-control | Old: configurable delay between slot clicks. New: no throttle. May cause server desync. |
| L7 | No eating interruption recovery timing | 03-chains | Old: re-fires `interactItem()` immediately. New: waits 36 ticks (1.8s) before recovery. |
| L8 | No inventory refresh/sync | 06-control | Old: periodic double-click slot refresh. New: none. Client-server desync may accumulate. |
| L9 | No MLG cone search | 15-movement | Old: 40-block cone with 25° pitch, 6-20 yaw divisions. New: straight down only. |
| L10 | No ground check before melee | 06-control | Old: only attacks if grounded/falling/swimming. New: attacks in any state. |
| L11 | No fireball deflection | 06-control | Old: KillAura.forceHit for fireballs. New: no fireball targeting. |
| L12 | No cursor slot cleanup mechanism | 11-container | Old: EnsureFreeCursorSlotTask. New: ToolEquipReflex drops after 20 ticks. |
| L13 | No structure-specific looting | 18-speedrun | Desert temples, ruined portals, bastions. |
| L14 | No splash potion usage | 13-entity | Cannot use potions for combat or utility. |
| L15 | No give-items-to-player | 13-entity | Cannot hand items to other players. |
| L16 | No XP orb collection | 13-entity | XP orbs ignored during combat. |
| L17 | No biome-aware exploration | 15-movement | Cannot target specific biomes for resources. |
| L18 | No nether highway transit | 15-movement | No ÷8 coordinate mapping for long-distance travel. |
| L19 | No crop replanting | 16-resources | Harvested crops not replanted. |
| L20 | No sheared-state check for sheep | 13-entity | May waste time interacting with already-sheared sheep. |

---

## Key Implementation Differences (Not Gaps — Just Different)

These are behaviors that work differently between old and new. Neither is necessarily wrong, but the difference may explain bugs:

| Area | Old Value/Approach | New Value/Approach | Which File |
|------|--------------------|--------------------|------------|
| ForceField range | Configurable (default infinity) | Hardcoded 3.5 blocks | 06-control |
| Shield vs creeper | EXCLUDED from shielding | INCLUDED (fuse > 0.5) | 06-control |
| Shield minimum hold | None (instant drop) | 5 ticks minimum | 06-control |
| Shield pauses pathing | Yes (Baritone paused) | No (Emmatone keeps pathing) | 06-control |
| Eat trigger (hunger) | Multiple thresholds (10, 15) | Single: hunger < 18 | 03-chains |
| Eat trigger (health) | Forces eating when health < 14 | No health-based trigger | 03-chains |
| Attack reach constant | Dynamic from settings | ATTACK_REACH = 3.0 | 13-entity |
| Flee distance | Configurable parameter | Hardcoded 30.0 | 15-movement |
| Weapon damage source | Hardcoded damage table | DataComponents.ATTRIBUTE_MODIFIERS | 06-control |
| Block scanner interval | Event-driven | Every 20 ticks | 21-trackers |
| Stuck detection window | 500 entries (25 seconds) | 100 entries (5 seconds) | 03-chains |
| Shift release threshold | 10 ticks (0.5 sec) | 200 ticks (10 sec) | 03-chains |
| Cursor overflow threshold | 1 tick | 20 ticks (1 sec) | 03-chains |
| MLG pickup timing | 0.25-tick polling | 5-tick fixed delay | 03-chains |

---

## Audit Files Index

| File | Scope | Entries |
|------|-------|---------|
| [06-control.md](06-control.md) | control/ (8 files) | 8 |
| [03-chains.md](03-chains.md) | chains/ (11 files) | 11 |
| [11-tasks-container.md](11-tasks-container.md) | tasks/container (16) + crafting core (3) | 16 |
| [16-tasks-resources.md](16-tasks-resources.md) | tasks/resources (45+) + wood variants (8) | 53 |
| [13-tasks-entity.md](13-tasks-entity.md) | tasks/entity (11 files) | 10 |
| [15-tasks-movement.md](15-tasks-movement.md) | tasks/movement (34 files) | 20+ |
| [18-tasks-speedrun.md](18-tasks-speedrun.md) | speedrun (10) + construction (14) + misc (6) | 20+ |
| [21-trackers.md](21-trackers.md) | trackers (16 files) | 16 |
| [remaining-sections.md](remaining-sections.md) | core, butler, commands, eventbus, mixins, multiversion, utils, etc. | ~290 |
