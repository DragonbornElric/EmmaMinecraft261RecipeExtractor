# EmmaMinecraft Architecture

A brain-agnostic Minecraft automation package. The Python `gamer` package provides a
WebSocket bridge to a Fabric mod containing the Emmatone pathfinder and a utility-based
GOAP agent. Any AI brain can plug in via the Python API — the Java side runs with
zero AI dependencies.

```
LLM Agent (any brain)
    │ gamer.configure() + gamer.init_db()
    │ from gamer.emmatone_client import connect
    │
    ▼
gamer package (Python) ──────────► minecraft.db (SQLite)
    │                    writes:     world_state, build_progress,
    │                                container_cache, poi_registry
    │ WebSocket ws://localhost:8765
    │   commands ↓  events ↑
    ▼
emma-pathfinder (Java Fabric mod)
    ├── Emmatone pathfinder (A* navigation)
    ├── WebSocket bridge (50 command handlers)
    ├── GOAP agent (15 actions, 6 reflexes)
    └── Camera system (port 8766)
            │
            ▼
        Minecraft 26.1
```

**Key principles:**
- All Python commands are sync/blocking with configurable timeout
- Events flow async from Java → Python via WebSocket
- GOAP runs autonomously on the game thread — no AI framework dependency
- Build system is idempotent and resume-aware (diffs against world_state)
- POI registry converts coordinates to landmark-relative descriptions for LLM context

---

## Quick Start

```python
import gamer
gamer.configure({
    "player_port": 8765,
    "camera_port": 8766,
    "build": {"staging_radius": 24, "phase_max_blocks": 256},
})
gamer.init_db()

from gamer.emmatone_client import connect
client = connect()
client.wait_for_connection()

# Navigate somewhere
client.goto(100, 64, 200)

# Check state
print(client.health, client.hunger, client.position)

# Set GOAP goals for autonomous behavior
client.set_goap_goals([
    {"id": "get_iron", "type": "have_item", "priority": 5,
     "target": {"item": "iron_ingot", "count": 32}}
])
```

**LLM agent loop pattern:**
```python
while True:
    state = client.get_state_summary()   # formatted string for context injection
    response = llm.chat(system_prompt + state, user_message)
    for tool_call in response.tool_calls:
        result = dispatch_to_client_method(tool_call, client)
```

---

## WebSocket Protocol

The JSON protocol sits between the Python client and the Java bridge mod. The Python
`EmmatoneClient` handles ID correlation and timeout internally — consumers just call
methods.

**Command (Python → Java):**
```json
{"id": "cmd_a1b2c3d4", "type": "command", "command": "goto", "params": {"x": 100, "z": 200}}
```

**Response (Java → Python):**
```json
{"id": "cmd_a1b2c3d4", "type": "response", "status": "ok", "data": {...}}
```

**Unsolicited event (Java → Python):**
```json
{"type": "event", "event": "health_changed", "data": {"health": 15.0, "hunger": 18}}
```

---

## Python API Reference — EmmatoneClient

All methods are on the `EmmatoneClient` class. Module-level helpers:
- `connect(host, port) → EmmatoneClient` — connect singleton
- `get_client() → EmmatoneClient` — get existing singleton
- `disconnect()` — close singleton

**Key properties on the client instance:**
- `build_db` — `BuildDB` instance for guide/goal/world_state queries
- `smelting_tracker` — `SmeltingTracker` instance for furnace job management

### Connection & Lifecycle

| Method | Description |
|--------|-------------|
| `connect()` | Start WebSocket on daemon thread |
| `disconnect()` | Close connection cleanly |
| `wait_for_connection(timeout=10)` | Block until connected, return bool |

### Navigation & Movement

| Method | Key Params | Description |
|--------|-----------|-------------|
| `goto(x, y, z)` | y=None → any Y | Pathfind to coordinates via Emmatone |
| `look_at(x, y, z)` | | Turn to face position |
| `mount(radius=5)` | | Mount nearest rideable entity |
| `dismount()` | | Dismount vehicle |

### Combat

| Method | Key Params | Description |
|--------|-----------|-------------|
| `attack(radius=5)` | | Swing at nearest hostile mob |

### Resource Gathering

| Method | Key Params | Description |
|--------|-----------|-------------|
| `mine(block_type, quantity=1)` | | Mine N blocks via Emmatone |
| `farm(range=100, x, y, z)` | Optional center | Harvest + replant crops |
| `create_farm(radius=5, x, y, z)` | Optional center | Till dirt + plant seeds |
| `gather_resources(block_type, qty)` | | Resolve crafting deps, start mining |

### Building

| Method | Key Params | Description |
|--------|-----------|-------------|
| `build(guide_id, ox, oy, oz, goal_id)` | goal_id enables resume | Execute build from guide at origin |
| `start_build_goal(goal_id)` | | Full pipeline: BOM + substitutions + build |
| `litematica_load(file, ox, oy, oz)` | | Load .litematic schematic |
| `litematica_unload()` | | Remove schematic placement |
| `litematica_printer(enabled)` | | Toggle Litematica Printer |
| `litematica_status()` | | Query Litematica/Printer state |

### Inventory Management

| Method | Key Params | Description |
|--------|-----------|-------------|
| `get_inventory()` | | Current inventory slots |
| `full_inventory()` | | Merged inventory + overflow view |
| `has_materials(bom)` | {item: qty} | Check inventory against BOM → {met, missing, pct_ready} |
| `set_slot(slot, item)` | slot 0-8 or item name | Select hotbar slot |
| `move_item(from_slot, to_slot)` | | Move item between slots |
| `drop_item(slot, all)` | | Drop item(s) |
| `swap_hands()` | | Swap main hand ↔ offhand |

### Screen Interaction & Atomic Primitives

| Method | Key Params | Description |
|--------|-----------|-------------|
| `use_item(hand, duration_ticks)` | | Use held item (eat, shield, throw) |
| `interact_entity(entity_type, radius)` | | Right-click nearest matching entity |
| `interact_block(x, y, z, hand, face)` | | Right-click block (open GUI, button) |
| `place_block(x, y, z, face, facing)` | facing=cardinal for directional blocks | Place block from main hand |
| `break_block(x, y, z)` | | Break single block |
| `read_screen()` | | Read open screen (type, slots, trades) |
| `click_slot(slot, button, action)` | action: PICKUP, quick_move | Click slot in open screen |
| `close_screen()` | | Close current GUI |

### Storage System

| Method | Key Params | Description |
|--------|-----------|-------------|
| `storage_scan(radius=32)` | | Find containers, enriched with DB cache |
| `storage_deposit(pos, items)` | items: {name: count} | Deposit to container at pos |
| `storage_withdraw(pos, items)` | | Withdraw from container at pos |
| `storage_deposit_nearby(items)` | | Auto-find nearest container |
| `storage_total(item_names)` | | Totals across inventory + overflow + containers |
| `storage_base_inventory(base)` | base name string | All items at a named base |
| `storage_save_cache()` | | Manually persist container cache |

### Overflow (Virtual Inventory)

| Method | Key Params | Description |
|--------|-----------|-------------|
| `overflow_deposit(items)` | {name: count} | Move to virtual overflow |
| `overflow_withdraw(items)` | | Retrieve from overflow |
| `overflow_status()` | | Get overflow contents + capacity |
| `overflow_trash(items)` | | Permanently destroy items |
| `overflow_clear_junk()` | | Auto-trash throwaway items |
| `overflow_free_slots(target=5)` | | Free N inventory slots (junk first, then overflow) |

### Smelting

| Method | Key Params | Description |
|--------|-----------|-------------|
| `furnace_status()` | | Read open furnace slots + timers |
| `smelt_and_leave(furnace_pos, input_item, input_count, fuel_item, ...)` | | Load furnace → walk away → register job |
| `collect_smelting_output(job_id)` | None = oldest ready | Go collect finished output |
| `smelting_tracker` (property) | | Access SmeltingTracker for job queries |

### World Perception & Terrain

| Method | Key Params | Description |
|--------|-----------|-------------|
| `scan_area(x, y, z, radius=32)` | include_light, include_biome | Scan blocks in radius |
| `get_heightmap(cx, cz, radius=20)` | step | 2D surface heightmap |
| `get_surface_map(cx, cz, radius=20)` | step, include_light/biome | 2D block-type map |
| `get_world_scan(cx, cz, radius=20)` | mode: surface/raw, y_min/max | Full 3D world scan |
| `get_biome(x, z, radius)` | include_temperature | Single point or survey |
| `get_world_info()` | | Time, weather, dimension, light level |
| `get_targeted_block()` | | What crosshair points at |
| `get_entities(radius=32, type, limit=50)` | | Nearby entities with full data |
| `get_effects()` | | Active status effects |

### EmmaClef Task System

High-level autonomous task chains that combine pathfinding, combat, crafting, and
resource gathering into composite behaviors.

| Method | Key Params | Description |
|--------|-----------|-------------|
| `emmaclef_task(task, args)` | | Execute task chain |
| `emmaclef_stop()` | | Stop current task chain |
| `emmaclef_status()` | | Get task chain status |

**Available tasks:**

| Task | Args | Behavior |
|------|------|----------|
| `get` | `"item_name count"` | Gather item: mine → craft → smelt as needed |
| `goto` | `"x y z"` | Navigate to coordinates |
| `follow` | `"player_name"` | Follow a player |
| `attack` | `"entity_type"` | Hunt and attack entity type |
| `food` | | Gather food (hunt animals, harvest crops) |
| `hero` | | Full combat mode (engage all hostiles) |
| `idle` | | Defensive idle (MobDefenseChain + FoodChain active) |
| `stop` | | Stop current task |

### Named Bases

| Method | Key Params | Description |
|--------|-----------|-------------|
| `set_base(name, x, y, z, radius=10)` | | Register named base |
| `list_bases()` | | List all bases |
| `clear_base(name)` | | Remove base |
| `protect_base(name, enabled)` | | Toggle block-breaking protection |
| `get_from_base(item, count, base)` | base="free" | Get items, routing to base for crafting/smelting |

### GOAP Control (from Python)

| Method | Key Params | Description |
|--------|-----------|-------------|
| `set_goap_goals(goals)` | list of goal dicts | Set dynamic GOAP goals |
| `set_personality(weights)` | dict | Set personality weights |
| `goap_debug(include_world_state)` | | Get GOAP debug state (scores, goals, history) |

**Goal format:**
```python
{"id": "get_diamonds", "type": "have_item", "priority": 5,
 "target": {"item": "diamond", "count": 5}}
```

**Personality keys:** `safety`, `aggression`, `exploration`, `resource_hoarding`
(floats, default ~1.0; higher = more desire for that category).

### Safety Systems

| Method | Key Params | Description |
|--------|-----------|-------------|
| `configure_panic_teleport(enabled, threshold, safe_x/y/z, cooldown_ms)` | | Configure panic teleport |
| `enable_panic_teleport()` | | Enable |
| `disable_panic_teleport()` | | Disable |
| `force_panic_teleport()` | | Force-trigger immediately |
| `panic_teleport_status()` | | Get config + state |

### Auto Torch

| Method | Key Params | Description |
|--------|-----------|-------------|
| `torch_toggle()` | | Toggle on/off |
| `torch_enable(enabled)` | | Enable or disable |
| `torch_status()` | | Get state |
| `torch_set_threshold(threshold)` | int | Set light level threshold |
| `torch_set_active(active)` | bool | Set active flag directly |

### Chat & Server Commands

| Method | Key Params | Description |
|--------|-----------|-------------|
| `send_chat(message)` | | Send chat message in-game |
| `send_command(command)` | without leading / | Send slash command |

### Task Control

| Method | Description |
|--------|-------------|
| `cancel()` | Cancel current Emmatone task |
| `respawn()` | Click respawn button after death |
| `get_status()` | Get bridge mod / Emmatone state |
| `adjust_priorities(health, hunger)` | Deterministic survival override (not LLM) — boosts safety/food goals at low vitals |

### State Properties

| Property | Type | Description |
|----------|------|-------------|
| `health` | float | Current health (0-20) |
| `hunger` | float | Current hunger (0-20) |
| `position` | dict | {x, y, z} |
| `active_task` | str\|None | Current task type |
| `active_goal_id` | int\|None | Current build goal ID (get/set) |
| `inventory` | list[dict] | Raw slot data |
| `inventory_counts` | dict | Aggregated {item_name: count} |
| `inventory_free_slots` | int | Empty main slots (0-36) |
| `recent_events` | list[dict] | Ring buffer (last 20 events) |
| `get_state_summary()` | str | Formatted multi-line state for LLM context |
| `on(event, handler)` | method | Register callback for event type (runs on listener thread) |

---

## Build Tools Reference

**Module:** `gamer.build_tools`

Each function is standalone and idempotent — queries reality fresh on every call. No
shared mutable state. The LLM sequences these tools; there is no state machine.

| Function | Purpose | Returns |
|----------|---------|---------|
| `build_design(client, goal_id)` | Load schematic, confirm origin, return overview | dimensions, block_stats |
| `build_bom(client, goal_id)` | Full BOM, diff vs inventory + containers | shortfall, structural/deferred split |
| `build_phase_plan(client, goal_id)` | Split build into Y-layer phases | phases with block lists + BOMs |
| `build_gather(client, item, qty)` | Issue EmmaClef `get` for one material | task_id |
| `build_stage(client, goal_id)` | Deposit non-essentials to staging chests | deposited items |
| `build_clear_site(client, goal_id)` | Clear blocks at build site | cleared count |
| `build_structural(client, goal_id, phase_index)` | Place structural blocks (walls, floors) | placed/remaining |
| `build_finishing(client, goal_id)` | Place deferred blocks (doors, torches, ladders) | placed/remaining |
| `build_inspect(client, goal_id)` | Verify build against guide, report gaps | mismatches |
| `build_restock(client, goal_id, phase_index)` | Withdraw materials from staging chests | restocked items |
| `build_with_printer(client, goal_id)` | Full Litematica Printer pipeline | status |
| `minecraft_bed(client, action)` | Find, place, or use a bed | status |

**Recommended LLM build sequence:**
```
build_design → build_bom → build_gather (loop for shortfall)
    → build_stage → build_structural (per phase, with build_restock between phases)
    → build_finishing → build_inspect
```

---

## GOAP System

The Java-side GOAP runs entirely on the Minecraft client thread with zero AI
dependencies. It uses utility scoring to select the best action every tick.

### Tick Loop (GoapTicker)

```
Every END_CLIENT_TICK (50ms at 20 TPS):
  1. Update WorldState from game (vitals, threats, inventory, nearby blocks)
  2. Run ReflexLayer (sub-tick reactions — shield, MLG, respawn)
  3. If reflex suppresses scoring → skip to step 6
  4. Score all viable actions via UtilityScorer
  5. Select winner (must beat current by SWITCH_THRESHOLD = 0.5 margin)
  6. Execute or continue the active action
```

**Block scanning:** every 20 ticks (1 sec), 32-block horizontal radius, ±8 vertical.
**Goal decomposition:** event-driven on inventory change, 10-tick debounce.

### Scoring Formula

```
primary_score   = action.computeScore(worldState, goals)
personality_bias = 1.0 + INFLUENCE × (personality_weight - 1.0)
collateral      = Σ (goal.priority × action.relevanceToGoal) for non-primary goals
success_rate    = globalKnowledge.getSuccessRate(action)

final_score = (primary × personality_bias + collateral) × success_rate
if action is already active: final_score += HYSTERESIS_BONUS (1.5)
```

Personality weights scale *desire*, not *competence* — all actions run at full
capability regardless of personality settings. Default influence is 0.4 (moderate nudge).

### Goal System

Three tiers of goals:

| Tier | Source | Examples |
|------|--------|----------|
| **Survival** | Hardcoded, always active | `survive` (pri=8), `stay_fed` (pri=6), `be_lit` (pri=5) |
| **Dynamic** | Set by LLM via `set_goap_goals()` | `have_item` goals with targets |
| **Derived** | Auto-generated by GoalDecomposer | Transitive dependencies (diamond_pickaxe → iron_pickaxe → iron_ingot → raw_iron) |

### GOAP Actions

| Action | Primary Goal | Category | Preconditions | Key Behavior |
|--------|-------------|----------|---------------|-------------|
| **AttackEntity** | survive | aggression | Hostile within 16 blocks, not eating/shielding | Equip weapon, face target, attack at cooldown. Danger tier + health urgency scoring. |
| **FleeFrom** | survive | safety | Threats nearby + low health/gear | Emmatone GoalRunAway 30 blocks. Critical health ×3 multiplier. |
| **EatFood** | stay_fed | neutral | Has food, hunger < 18 | Equip food, hold use key 32+ ticks. Min active: 40 ticks. |
| **MineBlock** | dynamic | resource_hoarding | Nearby blocks matching a goal | Emmatone mineByName(). Tag-aware matching. |
| **CraftItem** | dynamic | resource_hoarding | Has recipe ingredients | Recipe Book API: find table → navigate → open → handlePlaceRecipe → extract output. Auto sub-crafts crafting table via 2x2. |
| **SmeltItem** | dynamic | resource_hoarding | Has furnace + materials | 10-phase state machine: find furnace → navigate → open → insert → wait → extract. |
| **NavigateTo** | dynamic | exploration | Position-targeted goal exists | Emmatone GoalBlock pathfinding. Complete within 3 blocks. |
| **PlaceTorch** | be_lit | neutral | Has torches, light ≤ threshold | Equip torch, place on floor or wall. 40-tick cooldown. |
| **StoreItems** | manage_inv | resource_hoarding | Free slots ≤ 5 or pending request | Find container → navigate → open → transfer. Supports deposit/withdraw/deposit_nearby. |
| **EquipBestArmor** | survive | safety | Better armor available | Score: tier×10 + enchants + durability. Nether gold helmet override. Curse of Binding block. |
| **DeathRecovery** | survive | safety | Recent death, position known | Navigate to death pos → scan for dropped items → prioritize by value. 15-min timeout. |
| **EnvironmentalHazard** | survive | safety | In lava/fire/drowning/powder snow/DOT | Jump/flee from hazard. Urgency: lava 1.5, drowning 1.4, fire 1.2. |
| **CollectFood** | stay_fed | resource_hoarding | Food items < 5 | Try overflow withdrawal first, then hunt animals, then mine crops. Target: 16 items. |
| **ProjectileDodge** | survive | safety | Incoming projectile, no shield | Perpendicular strafe from projectile velocity vector. |
| **Unstuck** | (utility=2.0) | neutral | Pathing active + position unchanged 100 ticks | Random movement → jump → break adjacent block. 120 ticks total. |

### Reflexes

Reflexes are always-on sub-tick reactions that run before GOAP scoring. Some suppress
scoring entirely (exclusive control) while others run in parallel.

| Reflex | Trigger | Suppresses Scoring | Behavior |
|--------|---------|-------------------|----------|
| **ShieldBlock** | Creeper fuse/Skeleton bow drawn/Projectile < 8 blocks | Yes | Equip shield to offhand, raise. Min 5-tick hold. |
| **ForceField** | Hostile within 3.5 blocks (melee range) | No | Equip weapon, swing at all nearby hostiles every cooldown tick. |
| **MLGBucket** | velocityY < -0.7, not grounded | Yes | Look down → place water bucket → pick up. Chorus fruit fallback. |
| **PreEquipWeapon** | Hostile 3.5-8 blocks away (approaching) | No | Equip best weapon proactively. |
| **AutoRespawn** | Death screen showing | Yes | Wait 2 ticks → click respawn. |
| **ToolEquip** | Mining with wrong tool tier | No | Equip correct tool for block type. |

### WorldState Fields

Key fields available to actions for scoring decisions:

| Category | Fields |
|----------|--------|
| **Vitals** | health, maxHealth, hunger, saturation, onFire, inLava, inWater, airSupply |
| **Position** | posX/Y/Z, dimension (overworld/nether/end) |
| **Inventory** | playerInventory (Map), overflowInventory, freeSlots, hasShield, hasFood |
| **Threats** | List\<ThreatInfo\> sorted by distance (entity, distance, health, type) |
| **Projectiles** | List\<ProjectileInfo\> (distance, type, velocity) |
| **Equipment** | bestWeaponDamage, equippedArmor, availableArmor |
| **Environment** | lightLevel, timeOfDay, weather |
| **Blocks** | nearbyBlocks (scanned every 20 ticks, 32-block radius) |
| **Effects** | Active status effects (potion type, amplifier, duration) |

---

## Events Reference

Events flow from Java → Python via WebSocket. The `EmmatoneClient` auto-handles state
updates internally. Register custom handlers with `client.on("event_name", handler_fn)`.

| Event | Key Data | Auto-handled |
|-------|----------|-------------|
| `position` / `position_update` | x, y, z | Updates `_position` |
| `health_changed` | health, hunger | Updates `_health`, `_hunger` |
| `damage_taken` | amount, attacker_type, fatal | Records death if fatal |
| `death_postmortem` | death_message, combat_log, hostile_count, player_state | Enriches `_last_death` with position, persists to JSONL |
| `task_started` | task | Updates `_active_task`, cancels idle timer |
| `task_complete` | task_type | Schedules auto-idle (unless build in progress) |
| `task_failed` | task_type, reason | Schedules auto-idle, clears idle suppression |
| `task_stopped` | task_type | Schedules auto-idle, clears idle suppression |
| `inventory_changed` | slots, selected_slot | Updates inventory cache, checks armor tier |
| `container_contents` | pos, type, items, slots | Persists to DB + in-memory cache |
| `block_placed` | x, y, z, block_type | Increments build progress, updates world_state |
| `block_broken` | x, y, z | Updates world_state to air |
| `torch_out` | | Logged |
| `panic_teleport` | health, threshold, safe_x/y/z | Clears active task + idle suppression |
| `player_stats` | (Mojang statistics) | Cached, pushed to overlay |
| `chat_message` | message | Ring buffer |

**Note:** `chain_preempted` task failures are ignored — the task will auto-resume after
the defense/food chain finishes.

---

## LLM Agent Integration Guide

### Agent Loop Pattern

```python
import gamer
from gamer.emmatone_client import connect

gamer.configure({...})
gamer.init_db()
client = connect()
client.wait_for_connection()

# Register event handlers for reactive behavior
client.on("damage_taken", lambda d: print(f"Took {d['amount']} damage!"))
client.on("death_postmortem", lambda d: handle_death(d))

while True:
    # 1. Inject state into LLM context
    state = client.get_state_summary()

    # 2. LLM decides actions (tool calls)
    response = llm.chat(system_prompt + "\n" + state, user_message)

    # 3. Execute tool calls against the client
    for tool_call in response.tool_calls:
        result = getattr(client, tool_call.name)(**tool_call.args)
```

### State Summary Format

`get_state_summary()` returns a pipe-delimited string with:

```
[MINECRAFT STATE] | [ACTIVE BUILD: Oak Cabin (goal #12)]
Location: (200, 64, -150) | Progress: 340/500 blocks (68%)
Next: Run build_structural to continue placing blocks |
Task: idle | Position: 47 blocks east of Village Wall |
Health: 18/20, Hunger: 20/20 | Light: 12, Time: morning |
Recent: task_complete, damage from zombie (3) |
Holding: diamond_pickaxe (slot 0) |
Armor: head=iron_helmet, chest=iron_chestplate, legs=iron_leggings, feet=iron_boots |
Inventory: 64x oak_planks, 32x cobblestone, 16x cooked_beef, ...
```

### Two Approaches to Goal Setting

**1. GOAP Goals (autonomous):** Set goals and let the GOAP agent figure out how to
achieve them. Good for long-term objectives.

```python
client.set_goap_goals([
    {"id": "get_diamonds", "type": "have_item", "priority": 5,
     "target": {"item": "diamond", "count": 5}},
    {"id": "build_shelter", "type": "navigate_to", "priority": 3,
     "target": {"x": 100, "y": 64, "z": 200}},
])
```

The GoalDecomposer automatically creates sub-goals for transitive dependencies
(diamond_pickaxe → crafting_table + sticks + diamonds).

**2. Direct Commands (immediate):** Call client methods directly when the LLM wants
precise control.

```python
client.goto(100, 64, 200)                          # navigate
client.mine("diamond_ore", 3)                       # mine specific blocks
client.emmaclef_task("get", "diamond_pickaxe 1")   # full gather chain
client.place_block(100, 65, 200, face="up")         # precise placement
```

**When to use each:**
- GOAP goals: long-running autonomous behavior, survival, resource accumulation
- Direct commands: specific actions the LLM has decided on, building sequences, precise interactions

### Build Workflow for the Agent

An LLM agent orchestrates builds by sequencing `build_tools` functions:

```python
from gamer.build_tools import (build_design, build_bom, build_gather,
    build_stage, build_structural, build_finishing, build_inspect)

# 1. Pick a guide and create a goal
guides = client.build_db.search_guides("cabin")
goal_id = client.build_db.create_goal(
    "Oak Cabin", guide_id=guides[0]["id"],
    location_x=100, location_y=64, location_z=200)

# 2. Check what we're building
design = build_design(client, goal_id)

# 3. What materials do we need?
bom = build_bom(client, goal_id)
for item, qty in bom["shortfall"].items():
    build_gather(client, item, qty)          # gather each missing material

# 4. Deposit non-essentials to staging chests
build_stage(client, goal_id)

# 5. Build structural blocks (per phase)
from gamer.build_tools import build_phase_plan
plan = build_phase_plan(client, goal_id)
for i in range(len(plan["phases"])):
    build_structural(client, goal_id, phase_index=i)

# 6. Place deferred blocks (doors, torches, ladders)
build_finishing(client, goal_id)

# 7. Verify completion
result = build_inspect(client, goal_id)
```

### Pre-Stream Deliberation

The `Deliberator` class (`gamer.deliberation`) provides optional LLM-powered goal
planning for streaming sessions:

```python
from gamer.deliberation import Deliberator

delib = Deliberator(client.build_db, client)
candidates = delib.generate_candidates()          # evaluate feasibility
result = delib.run_deliberation(candidates, persona="...", session_notes="...")
delib.finalize_selections(result, selected_goal_ids)
```

This is the only component that makes an LLM call from within the gamer package itself.

---

## Supporting Systems

### POI Registry (`gamer.poi`)

Converts raw coordinates to human-readable landmark references for LLM context.

```python
from gamer.poi import POIRegistry
poi = POIRegistry()
poi.upsert_poi("Village Wall", 200, 64, -150, poi_type="landmark")
poi.upsert_poi("Iron Mine", 350, 40, -200, poi_type="resource")

# Spatial queries
coords = poi.resolve_name("Village Wall")         # → (200, 64, -150)
direction = POIRegistry.compass_direction(dx, dz)  # → "NE"
distance = POIRegistry.horizontal_distance(x1, z1, x2, z2)
```

Compass mapping: +X = East, -X = West, +Z = South, -Z = North.
POIs auto-sync from build goals on connect.

### Camera & Stream Director

**CameraBot** (`gamer.camera_bot`): WebSocket client to a separate spectator account
on port 8766. Sends camera preset commands.

```python
from gamer.camera_bot import connect_camera
cam = connect_camera()
cam.set_preset("overhead")    # overhead, build_closeup, combat_tight,
                               # side_track, wide_overhead, mine_angle,
                               # front_face, celebration
```

**StreamDirector** (`gamer.stream_director`): Deterministic rules mapping task states
to OBS scenes + camera presets. Minimum 2-second hold time to prevent flickering.

| Task State | OBS Scene | Camera Preset |
|------------|-----------|---------------|
| building | MC Build Cam | overhead |
| mining | MC First Person | mine_angle |
| navigating | MC Cinematic | side_track |
| combat | MC First Person | combat_tight |
| idle | MC Face Cam | front_face |
| celebration | MC Cinematic | celebration |

### Smelting Tracker (`gamer.smelting_tracker`)

Async "load and leave" pattern for furnace operations:

```python
# Load furnace and walk away
result = client.smelt_and_leave((100, 65, 200), "raw_iron", 32)

# ... do other things ...

# Check if ready
ready = client.smelting_tracker.get_ready_jobs()

# Collect when done
output = client.collect_smelting_output(result["job_id"])
```

Jobs track: furnace position/type, input/output items, estimated completion time,
status (smelting → ready → collected). Auto-collection happens before idle transitions.

### Schematic Parser & Guide Export

**Import** (`gamer.schematic_parser`): Reads `.schematic`, `.litematic`, `.schem` files
into `build_guide_blocks` table.

```python
from gamer.schematic_parser import import_schematic
result = import_schematic("builds/cabin.litematic", name="Oak Cabin")
# → {"guide_id": 3, "block_count": 500, "dimensions": {"x": 12, "y": 8, "z": 10}}
```

**Export** (`gamer.guide_to_litematic`): Converts build guides back to `.litematic`
for in-game Litematica Printer visualization.

---

## Database Schema

**File:** `minecraft.db` (SQLite, WAL mode, R-tree spatial index)

```
build_guides ──1:N──► build_guide_blocks
     │
     └──1:N──► build_goals ──1:N──► build_progress

block_substitutions    (original → substitute, with priority + context)
block_resources        (block → method: mine/craft/smelt + ingredients)
world_state            (x, y, z → block_type + state + timestamps)
world_state_rtree      (R-tree spatial index on world_state)
container_cache        (x, y, z → type + items JSON + slot counts)
poi_registry           (name → x, y, z + poi_type + source)
```

| Table | Key Fields | Purpose |
|-------|-----------|---------|
| `build_guides` | name, dimensions, block_count, difficulty, tags | Schematic metadata |
| `build_guide_blocks` | guide_id, block_type, block_state, offset_x/y/z, placement_order | Individual blocks in a guide |
| `block_substitutions` | original_block, substitute_block, priority, context | Material swap rules |
| `block_resources` | block_type, method, ingredients, output_qty, tool_required | How to obtain blocks |
| `world_state` | x, y, z, block_type, state, discovered_at, updated_at | Discovered/placed blocks |
| `build_goals` | guide_id, name, status, location_x/y/z, priority_weight | Build projects + tracking |
| `build_progress` | goal_id, block_type, placed_count, total_count | Per-block progress |
| `container_cache` | x, y, z, type, items (JSON), total_slots, empty_slots | Persistent container contents |
| `poi_registry` | name, x, y, z, poi_type, source, description | Points of interest |

**Goal statuses:** `future` → `planned` → `current` → `paused` → `complete` / `abandoned`

---

## Java Mod Structure

### emma-pathfinder (Main Mod)

Output: `emma-bridge-mod-0.2.0.jar` (~1MB, includes bundled Emmatone pathfinder)

| Package | Purpose |
|---------|---------|
| `com.emma.bridge` | Mod entry points, bridge server lifecycle |
| `com.emma.bridge.commands` | 50 WebSocket command handlers + CommandRouter |
| `com.emma.bridge.goap` | GoapTicker, UtilityScorer, WorldState, GoalSet, GoalDecomposer |
| `com.emma.bridge.goap.actions` | All scored GOAP actions |
| `com.emma.bridge.goap.reflex` | All reflexes (ShieldBlock, ForceField, MLG, etc.) |
| `com.emma.bridge.events` | Event listeners/reporters (damage, inventory, blocks) |
| `com.emma.bridge.control` | BlockInteraction, DirectInput, ManualSteering |
| `com.emma.bridge.camera` | CameraTracker, CameraPresets |
| `emmatone/` | Bundled Emmatone pathfinder (Mojang mappings) |

### emma-overflow

Output: `emma-overflow-0.1.0.jar` — Virtual inventory overflow storage + item
destruction. Provides the C2S/S2C protocol for deposit/withdraw/trash operations.

### emma-gameplay-logger (external repo)

Former local output: `emma-gameplay-logger-0.1.0.jar` — Server-side event logger.
Records block placement, combat, food consumption, equipment changes to JSONL
session files.

This module now lives in a separate repository: `https://github.com/DragonbornElric/EmmaMinecraft261Logger`

Commands: `/logger start <player>`, `/logger stop <player>`, `/logger status`

### Build & Deploy

```bash
cd java && ./build_and_deploy.sh           # all mods → Emma + CameraBot instances
cd java && ./build_and_deploy.sh --bridge   # bridge + overflow only
cd java && ./build_and_deploy.sh --server   # all + deploy to remote server
```

**MC 26.1, Fabric Loader 0.18.4, Fabric Loom 1.15, Java 25, unobfuscated source (Yarn discontinued)**

**Critical:** Never use `MinecraftClient.getInstance().execute()` for Emmatone calls —
it deadlocks. Use the tick queue pattern instead.
