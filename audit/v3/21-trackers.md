# Audit V3 — 21: trackers/ (16 files)

Old: `adris/altoclef/trackers/`
New: `goap/WorldState.java` + `goap/GoapTicker.java` (block scanner) + `events/` (EventReporter + sub-trackers)

**Architectural note:** AltoClef had a `TrackerManager` owning separate tracker instances (EntityTracker, BlockTracker, etc.), updated each tick via EventBus subscriptions. Emma replaces this with a single `WorldState` snapshot updated each tick in `GoapTicker`, plus `EventReporter` sub-trackers for real-time events.

---

## 1. `TrackerManager.java` + `Tracker.java` — Base tracker infrastructure

**Old:** Manager pattern — creates and ticks all trackers. Base class provides `setDirty()` / `ensureUpdated()` pattern.

**Emma equivalent:** `GoapTicker.java` tick sequence updates WorldState directly.

**Verdict:** NOT_NEEDED — No tracker manager needed; direct per-tick updates.

---

## 2. `EntityTracker.java` — Entity scanning + caching

**Old implementation:**
- Maintains searchable caches: items on ground, hostile mobs, players, projectiles
- Entity class filtering + predicate filtering
- `isEntityReachable(entity)` — pathfinding reachability check
- `getClosestEntity(class, predicate)` — nearest entity with filter
- Hostile detection with `isProbablyHostileToPlayer()` check
- Player tracking by name

**Emma equivalent:** `WorldState.java` per-tick entity scanning

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Scan method | Entity caches updated via EventBus | Per-tick AABB scan (16 blocks for threats, 32 for items) | Same result, different mechanism |
| Reachability check | Pathfinding-based reachability | Not present | May target unreachable entities |
| Hostile detection | `isProbablyHostileToPlayer()` predicate | Monster class check + enderman creepy check | Similar but Emma's is simpler |
| Player tracking | By name | Not tracked | Players not tracked as entities |
| Item entity tracking | Dedicated cache | Per-action AABB scan (PickupItemAction, CollectFoodAction) | Same |
| Projectile tracking | Dedicated cache | WorldState.incomingProjectiles per tick | Same |

**Missing:** No entity reachability check (old used pathfinding to verify).

**Verdict:** PARTIAL — Core scanning works but no reachability check.

---

## 3. `CraftingRecipeTracker.java` — Recipe lookups

**Old:** Wraps ServerRecipeManager for item-to-recipe mappings.

**Emma equivalent:** `ItemRecipeRegistry.java` + `RecipeBookLookup.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Recipe source | ServerRecipeManager (server-synced) | Client recipe book + JSON data file (3121 entries) | Emma has pre-extracted data + runtime recipe book |
| Scope | Runtime recipe manager only | Pre-extracted JSON + live recipe book | Emma has more data (mob drops, mine blocks, etc.) |

**Verdict:** IMPLEMENTED — More comprehensive in Emma.

---

## 4. `EntityStuckTracker.java` — Stuck entity detection

**Old:** Detects entities that haven't moved for extended periods. Uses position history.

**Emma equivalent:** Not present as dedicated tracker. HuntMobAction has 400-tick timeout.

**Verdict:** PARTIAL — Timeout exists but no position-based stuck detection.

---

## 5. `MiscBlockTracker.java` — Beds, portals, etc.

**Old:** Tracks specific block positions (beds, portals, notable blocks).

**Emma equivalent:** `PortalRegistry.java` + `BaseRegistry.java` + GoapTicker block scanner

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Portal tracking | In MiscBlockTracker | Dedicated PortalRegistry (persistent) | Emma persists portal locations |
| Bed tracking | In MiscBlockTracker | SleepAction scans locally | No persistent bed tracking |
| Block scanning | EventBus-driven | Every-20-tick block scanner (32 block radius) | Different timing |

**Verdict:** IMPLEMENTED — Different but covers same needs.

---

## 6. `SimpleChunkTracker.java` — Chunk load/scan tracking

**Old:** Tracks loaded/scanned chunks to avoid re-scanning.

**Emma equivalent:** GoapTicker block scanner runs every 20 ticks regardless of chunk state.

**Verdict:** NOT_NEEDED — Block scanner is poll-based, not event-based.

---

## 7. `UserBlockRangeTracker.java` — User-specified block ranges

**Old:** Tracks blocks in user-defined ranges for custom scanning.

**Emma equivalent:** Not present.

**Verdict:** NOT_NEEDED — No user-defined scanning ranges.

---

## 8-11. `blacklisting/` — Entity and block blacklists

**Old:**
- `AbstractObjectBlacklist.java` — base blacklist with timeout
- `EntityLocateBlacklist.java` — UUID-based, 60-second timeout, max 20 entries
- `WorldLocateBlacklist.java` — BlockPos-based blacklist

**Emma equivalent:** `WorldState.unreachableBlocks` (Map<BlockPos, Long>) for blocks. No entity blacklist.

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Block blacklist | WorldLocateBlacklist | unreachableBlocks map (tick-based expiry) | Similar |
| Entity blacklist | UUID-based, 60s timeout, max 20 | Not present | Loops on unreachable entities |
| Timeout mechanism | Timer-based | Tick-based expiry in unreachableBlocks | Similar for blocks |

**Missing:** No entity blacklisting — mobs stuck behind walls/fences are targeted indefinitely.

**Verdict:** PARTIAL — Block blacklisting exists. Entity blacklisting is missing.

---

## 12-16. `storage/` — Container and inventory tracking

**Old:**
- `ContainerCache.java` — cached container contents
- `ContainerSubTracker.java` — tracks container contents per position
- `ContainerType.java` — container type enum
- `InventorySubTracker.java` — player inventory state
- `ItemStorageTracker.java` — unified access (inventory + containers)

**Emma equivalent:** `ContainerTracker.java` + `InventoryTracker.java` + `WorldState` inventory fields + `EndinvBridge.java`

| Aspect | Old | New | Impact |
|--------|-----|-----|--------|
| Container caching | Per-position ContainerCache | LRU cache (256 max) with hash-based change detection | Similar with different eviction |
| Inventory tracking | InventorySubTracker | InventoryTracker (hash-based, debounced WebSocket broadcast) | Similar |
| Unified access | ItemStorageTracker queries both | WorldState.hasItem() checks player + endinv + containers | Emma has 3-scope access (adds EndinvBridge) |
| Container type | Enum-based | Block class checking (ChestBlock, BarrelBlock, etc.) | Different detection |
| Container fullness | ContainerCache.isFull() | Not checked before operations | May fail silently on full containers |

**Verdict:** PARTIAL — Core tracking works. Missing container fullness pre-checks.
