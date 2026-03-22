# Phase 58: @player Agent

## Vision

Build a `@player` agent that uses Emmatone for low-level execution and a **needs-based opportunistic scanner** for high-level decision-making. EmmaClef's 181 task classes serve as **reference material** for game logic (recipes, crafting sequences, combat patterns) — not as the runtime framework.

### Core Architectural Insight

EmmaClef's value is its **knowledge** (how to craft, smelt, fight, navigate). Its liability is its **framework** (EventBus with 25 mixins for event sensing, strict task tree that tunnel-visions on one goal). The original developer built forced status update events to verify task completion instead of just checking inventory or world state. This leads to:

- **Tunnel vision** — walks past needed resources because they're not the active task
- **Poor prioritization** — can't handle multiple goals simultaneously
- **Wandering** — chain preemption switches context in ways that feel aimless
- **25 unnecessary mixins** — built a full sensory nervous system when the bridge already reports most events, and the rest can be polled each tick

**Decision:** Keep EmmaClef's logic as reference. Replace its EventBus and task tree with a needs-based scanner that runs on a single tick mixin.

## Architecture: GOAP + Emma Executive Layer

Two-layer design: Emma (Python/LLM) handles long-term planning with persistent memory. A GOAP planner (Java) handles real-time action selection each tick.

```
Emma (Python LLM) — executive function
  │ persistent memory (entities, facts, summaries in DB)
  │ long-term planning ("prepare for the nether", "build a base")
  │ decomposes goals into needs lists
  │ adjusts priorities based on context (stream, viewers, conversation)
  │ sets goals via WebSocket
  ▼
GOAP Planner (Java) — real-time action selection
  │ world state: inventory, health, nearby blocks, threats, hunger
  │ goals: desired state changes (have 3 diamonds, be safe, be fed)
  │ actions: mine, eat, fight, flee, craft, navigate, store, place torch
  │ each tick: score ALL possible actions, execute highest utility
  ▼
Executor
  │ Emmatone: pathfinding, mining (#mine, #goto)
  │ BlockInteraction: placement, container use
  │ DirectInput: movement, sneaking
  │ KillAura: combat
  │ AutoTorchPlacer: lighting (Phase 58a)
```

### Why GOAP, Not a Task Tree

EmmaClef uses a **strict task tree** — one active task, fixed priority hierarchy (survival > active task > idle). This creates tunnel vision: walks past iron you need because "mine diamonds" is the active task.

GOAP uses **unified utility scoring** — every possible action gets a score each tick, highest wins. No fixed hierarchy. A creeper at 2 blocks beats everything; a zombie at 20 blocks doesn't interrupt mining iron right in front of you. The threat level matters, not just the category.

```
This tick, what's the highest-utility action?

  Mine diamond ore (20 blocks away)     → goal:10 × proximity:0.3 = 3.0
  Mine iron ore (3 blocks away)         → goal:5  × proximity:0.9 = 4.5  ← winner
  Chop oak tree (6 blocks away)         → goal:3  × proximity:0.7 = 2.1
  Eat (health at 14/20)                 → goal:6  × urgency:0.3  = 1.8
  Kill zombie (4 blocks, approaching)   → goal:8  × threat:0.8   = 6.4  ← would beat iron
  Flee creeper (2 blocks, hissing)      → goal:15 × threat:1.0   = 15.0 ← always wins
```

Subtasks work the same way. "Get diamond pickaxe" decomposes into sub-needs (sticks, diamonds, crafting table). But you don't do them in sequence — if you walk past a tree and need sticks, the tree's high proximity makes its score competitive despite lower priority. Opportunistic, like a human.

### How a Human Plays (the model to emulate)

```
Heading to cave for diamonds.
  → oak tree 4 blocks off path? Chop it (need logs for torches).
  → iron ore in cave wall? Mine it (need iron for tools).
  → health low? Eat, then keep going.
  → hostile mob? Kill it, then keep going.
  → found diamonds? Mine them. Check: do I still need more? Keep going or head home.
```

EmmaClef can't do this — it processes one task at a time. GOAP does this naturally because all goals compete simultaneously.

### How This Differs from EmmaClef

| EmmaClef (current)                               | Phase 58 Player Agent                                                  |
| ------------------------------------------------ | ---------------------------------------------------------------------- |
| Strict task tree — one active task at a time    | GOAP — all goals scored simultaneously each tick                      |
| Fixed hierarchy: survival > task > idle          | Dynamic utility: threat at 2 blocks > task; threat at 20 blocks < task |
| EventBus with 25 mixins for game sensing         | Single tick mixin + polling + existing bridge events                   |
| Walks past needed resources if not active task   | Opportunistic: proximity boosts score of any needed item               |
| 181 task classes with deep inheritance           | GOAP actions + EmmaClef tasks as reference for procedures              |
| Chain preemption (FoodChain, MobDefenseChain)    | Survival competes in same scoring function — no special preemption    |
| Forced status update events to verify completion | Check inventory or world state directly                                |
| No executive planning layer                      | Emma LLM sets goals from persistent memory + conversation context      |

## Component Design

### Layer 1: Emma Executive (Python)

Emma's orchestrator already handles long-term planning. For Minecraft, she:

- Sets goals via WebSocket: `{"command": "set_goals", "params": {"goals": [...]}}`
- Decomposes high-level intent ("prepare for the nether") into concrete needs (diamond pickaxe, obsidian ×10, flint & steel)
- Uses persistent memory: "I stored iron in the east chest last session"
- Adjusts priorities from conversation: viewer says "get diamonds!" → boost diamond priority
- Receives status updates: GOAP reports what it's doing and why (for Emma to narrate)

Emma doesn't micromanage — she sets goals and lets GOAP handle the real-time execution. She only intervenes to change priorities or add/remove goals.

### Layer 2: GOAP Planner (Java)

#### WorldState.java

Snapshot of current game state, updated each tick by polling (not events):

- Player: health, hunger, position, on_fire, in_water, armor level, equipped armor per slot (item + enchantments + durability), available armor in inventory
- Inventory: items + counts (player + overflow + known storage)
- Nearby blocks: BlockScanner results for all goal-relevant block types
- Threats: hostile entities within detection range, distance + type
- Environment: light level, biome, dimension, time of day

#### GoalSet.java

Active goals set by Emma. Each goal is a desired world state change:

- `{id: "diamond", type: "have_item", item: "diamond", count: 3, priority: 10}`
- `{id: "safe", type: "no_threats", priority: 8}` (always active)
- `{id: "fed", type: "health_above", threshold: 16, priority: 6}` (always active)
- `{id: "lit", type: "light_above", threshold: 3, priority: 4}` (when underground)

Goals can be persistent (survival) or task-specific (set by Emma, removed when satisfied).

#### ActionRegistry.java

All possible actions the agent can take. Each action has:

- **Preconditions:** what must be true (e.g., mine diamond → need iron+ pickaxe, be near diamond ore)
- **Effects:** what changes (e.g., mine diamond → inventory +1 diamond)
- **Cost function:** `f(worldState, goal)` → utility score
- **Executor:** how to actually do it (Emmatone command, BlockInteraction call, etc.)

Core actions:

| Action            | Precondition                         | Effect                 | Executor                   |
| ----------------- | ------------------------------------ | ---------------------- | -------------------------- |
| MineBlock(type)   | Near block, have correct tool        | +1 item                | Emmatone `#mine`         |
| EatFood           | Has food, health < max               | Health restored        | Use food item              |
| AttackEntity(e)   | Entity in range                      | Entity damaged/killed  | KillAura                   |
| FleeFrom(pos)     | Threat detected                      | Distance from threat   | Emmatone `#goto` (away)  |
| CraftItem(recipe) | Has ingredients, near crafting table | +1 crafted item        | Open table, place recipe   |
| SmeltItem(input)  | Has input + fuel, near furnace       | +1 smelted item        | Open furnace, insert, wait |
| StoreItems        | Has items, near chest                | Items in storage       | Open chest, deposit        |
| NavigateTo(pos)   | None                                 | At target position     | Emmatone `#goto`         |
| PlaceTorch        | Has torch, dark area, underground    | Light level increased  | AutoTorchPlacer            |
| EquipBestArmor    | Has better armor in inventory        | Optimal armor equipped | Inventory slot swap        |

New actions can be added without changing the planner — just register with preconditions + effects + cost.

#### Armor Scoring System

GOAP action `EquipBestArmor(slot)` runs as a low-urgency background goal, spiking to survival-level when dimension rules are violated (e.g., no gold helmet in Nether). Scores each armor piece in inventory against what's currently equipped, considering material tier, enchantments weighted by current dimension, and remaining durability.

##### Score Formula

```
armor_score = (material_tier × 10) + enchantment_score + (durability_pct × 2)
```

Where `enchantment_score` is the sum of all enchantment values, weighted by dimension:

```
enchantment_score = Σ (enchant_level × base_value × dimension_weight)
```

##### Material Tier Rankings

| Tier | Material  | Base Score |
| ---- | --------- | ---------- |
| 0    | Leather   | 0          |
| 1    | Gold      | 1          |
| 2    | Chainmail | 2          |
| 3    | Iron      | 3          |
| 4    | Diamond   | 4          |
| 5    | Netherite | 5          |

##### Enchantment Base Values (per level)

| Enchantment           | Slots     | Base Value | Notes                                        |
| --------------------- | --------- | ---------- | -------------------------------------------- |
| Protection            | All       | 4          | Universal damage reduction                   |
| Fire Protection       | All       | 4          | Mutually exclusive with Protection           |
| Blast Protection      | All       | 4          | Mutually exclusive with Protection           |
| Projectile Protection | All       | 4          | Mutually exclusive with Protection           |
| Feather Falling       | FEET only | 5          | 12% fall reduction per level                 |
| Thorns                | All       | 1          | Minor passive damage                         |
| Respiration           | HEAD only | 3          | +15s underwater per level                    |
| Aqua Affinity         | HEAD only | 2          | Removes mining penalty underwater            |
| Depth Strider         | FEET only | 2          | Water movement speed                         |
| Frost Walker          | FEET only | 1          | Walk on water (conflicts with Depth Strider) |
| Soul Speed            | FEET only | 2          | Speed on soul sand/soil                      |
| Swift Sneak           | LEGS only | 1          | Sneak speed                                  |
| Unbreaking            | All       | 2          | Durability longevity                         |
| Mending               | All       | 3          | XP-based repair                              |

##### Dimension Weight Multipliers

Each enchantment's base value is multiplied by a dimension-specific weight:

| Enchantment           | Overworld | Nether | End | Rationale                                          |
| --------------------- | --------- | ------ | --- | -------------------------------------------------- |
| Protection            | 1.0       | 0.7    | 0.8 | Universal but specialists beat it per-dimension    |
| Fire Protection       | 0.1       | 1.5    | 0.0 | Critical in Nether (lava, blazes, ghasts, magma)   |
| Blast Protection      | 0.6       | 1.2    | 0.1 | Creepers (OW), Ghasts (Nether), nothing in End     |
| Projectile Protection | 0.7       | 0.3    | 1.3 | Skeletons (OW), Shulker bullets (End)              |
| Feather Falling       | 0.5       | 0.6    | 1.5 | Critical in End: Shulker levitation + Chorus falls |
| Thorns                | 0.3       | 0.3    | 0.5 | Minor; slightly better vs Shulker swarms           |
| Respiration           | 0.8       | 0.0    | 0.1 | No water in Nether, rare in End                    |
| Aqua Affinity         | 0.6       | 0.0    | 0.0 | Underwater mining, OW only                         |
| Depth Strider         | 0.7       | 0.0    | 0.1 | Water traversal, OW only                           |
| Soul Speed            | 0.0       | 1.0    | 0.0 | Soul sand only exists in Nether                    |
| Unbreaking            | 1.0       | 1.0    | 1.0 | Always valuable                                    |
| Mending               | 1.0       | 1.0    | 1.0 | Always valuable                                    |

##### Hard Overrides (bypass scoring)

These override the scoring system entirely:

| Condition                         | Override                                        | Priority     |
| --------------------------------- | ----------------------------------------------- | ------------ |
| In Nether + gold helmet available | Force gold helmet on HEAD slot                  | Survival     |
| In Nether + no gold helmet        | Keep current helmet (don't unequip for nothing) | —           |
| Curse of Binding on piece         | Never swap TO this piece (can't remove it)      | Hard block   |
| Durability < 5%                   | Deprioritize (about to break)                   | Soft penalty |

##### Scoring Examples

**Nether — choosing between two helmets:**

Iron Helmet (Unbreaking III, 90% durability): `material 30 + enchants 6 + durability 1.8 = 37.8`
BUT: Hard override — Gold Helmet forced in Nether regardless of score (Piglin aggro prevention).

**End — choosing between two boots:**

Diamond Boots (Feather Falling IV, Protection III): `material 40 + FF(4×5×1.5=30) + Prot(3×4×0.8=9.6) + durability 2 = 81.6`
Iron Boots (Projectile Protection IV, Feather Falling II): `material 30 + PP(4×4×1.3=20.8) + FF(2×5×1.5=15) + durability 2 = 67.8`
Diamond boots win (81.6 vs 67.8) — higher material tier + better Feather Falling.

**Overworld — choosing between two chestplates:**

Iron Chestplate (Protection IV, Unbreaking III, Mending): `material 30 + Prot(4×4×1.0=16) + Unb(3×2×1.0=6) + Mend(1×3×1.0=3) + durability 2 = 57.0`
Diamond Chestplate (no enchantments, 80% durability): `material 40 + enchants 0 + durability 1.6 = 41.6`
Enchanted iron beats unenchanted diamond (57.0 vs 41.6) — correct real-world decision.

##### MC 1.21.8 Enchantment API

Reading enchantments from ItemStack uses data components (not NBT):

```java
// Check if item has any enchantments
if (stack.hasEnchantments()) {
    ItemEnchantmentsComponent enchants = stack.getOrDefault(
        DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
    for (var entry : enchants.getEnchantmentEntries()) {
        RegistryEntry<Enchantment> enchantment = entry.getKey();
        int level = entry.getIntValue();
        // Score based on enchantment ID + level + dimension weights
    }
}

// Check for Curse of Binding (already in codebase: EnchantmentHelperVer)
EnchantmentHelper.hasAnyEnchantmentsWith(stack,
    EnchantmentEffectComponentTypes.PREVENT_ARMOR_CHANGE);
```

##### Existing Code to Reuse

- `StorageHelper.isArmorEquipped(Item...)` — check equipped armor per slot
- `StorageHelper.getItemStackInSlot(PlayerSlot.ARMOR_*_SLOT)` — get equipped ItemStack with enchantments
- `WorldHelper.getCurrentDimension()` → `Dimension.OVERWORLD/NETHER/END`
- `EnchantmentHelperVer.hasBindingCurse()` — Curse of Binding check
- `ItemHelper.GOLDEN_ARMORS/IRON_ARMORS/DIAMOND_ARMORS/NETHERITE_ARMORS` — tier arrays
- `mod.getItemStorage().getItemStacksPlayerInventory(false)` — scan inventory for armor candidates
- `EquipArmorTask` — existing task for slot swapping via `MoveItemToSlotFromInventoryTask`

##### Protection Stacking Rules (reference)

- Protection, Fire/Blast/Projectile Protection are **mutually exclusive per piece** (only one type per armor item)
- All protection types **stack across pieces** up to **80% damage reduction cap**
- Specialized protections are 2× as effective as general Protection (8% vs 4% per level)
- Feather Falling stacks with boot protection enchantment (different effect categories)
- Optimal Nether set: Fire Protection IV ×3 pieces + Protection IV ×1 piece = 80% cap for fire damage
- Optimal End set: Feather Falling IV boots + Projectile Protection on 1–2 pieces + Protection on remainder

#### UtilityScorer.java

Each tick, scores every viable action (preconditions met) against current world state:

```
score = goal_priority × relevance_to_goal × proximity_factor × urgency_factor
```

Where:

- `goal_priority` — set by Emma (diamonds = 10, iron = 5, etc.)
- `relevance_to_goal` — does this action directly advance a goal? (1.0 = yes, 0.5 = indirect prerequisite)
- `proximity_factor` — `1.0 / (1.0 + distance / 16.0)` — nearby things score higher
- `urgency_factor` — for survival: `1.0 - (health / maxHealth)` ramps up as health drops; for threats: `1.0 / (1.0 + distance / 5.0)`

Survival goals don't get special treatment — they just have high base priority and urgency curves that spike when health/hunger drops. A zombie at 20 blocks with full health scores low. Same zombie at 3 blocks with half health scores very high. Naturally.

### Layer 3: Action Sequences (from EmmaClef reference)

Some "actions" are multi-step procedures. These are extracted from EmmaClef's task classes as reference:

- **Furnace use:** navigate to furnace → open → insert fuel + input → wait → collect
- **Crafting:** navigate to table → open → place recipe → collect
- **Chest use:** navigate → open → deposit/withdraw → close
- **Combat:** approach → KillAura auto-attacks → collect drops
- **Bed:** find flat surface → place bed → right-click → sleep

These are state machines within a single action, not separate GOAP goals. The planner selects "SmeltItem" as the best action; the action's internal state machine handles the multi-step procedure.

### StorageTracker.java (Phase 58c) (Implented - Not Tested)

- DB of known container locations + contents at Emma's base
- Updated when Emma opens a chest (poll screen contents)
- Queried by WorldState: "total iron across all storage + inventory + overflow"
- NeedsList uses this: "I need 10 iron" minus "3 in inventory + 5 in chest" = "need 2 more"
- Goal: "store items" triggers when at base with overflow items or inventory full of non-essentials

## What Stays from EmmaClef (reuse directly)

- `BlockScanner` — already tracks blocks by type across loaded chunks
- `ItemStorageTracker` — inventory tracking
- `KillAura` — combat execution
- `AutoTorchPlacer` — already built (Phase 58a)
- `TaskCatalogue` recipe knowledge — extract as data lookup
- `BlockInteraction`, `DirectInput` — shared utilities (already our code)

## Mixin Reduction

EmmaClef's 25 event-publishing mixins become unnecessary once the EventBus is replaced by polling:

**Keep (4 total):**

1. `ClientTickMixin` — the one tick hook everything runs on
2. `ToolSetMixin` — redirects Emmatone's tool selection (behavioral patch, can't poll)
3. `MovementHelperMixin` — lets Emmatone break infested blocks
4. `MovementHelperBlockProtectionMixin` — protects schematic blocks from Emmatone

**Remove (22 event/accessor mixins):** All replaced by per-tick polling in the tick callback or existing bridge events. See Fabric independence section below.

**Add (2 bridge mixins):** Replace Fabric API event registrations with direct mixins on `MinecraftClient` and `ClientPlayerInteractionManager`. See Phase MC261 doc.

**Final count: 6 mixins** (down from 30).

## Transition Strategy

EmmaClef doesn't get deleted — it gets sidelined:

1. Build NeedsList + OpportunityScanner + SurvivalChecks alongside existing EmmaClef
2. New WebSocket commands (`set_needs`, `scan_status`, `storage_update`) work independently
3. Existing `@get`, `@mine` EmmaClef commands still work during transition
4. Once scanner is proven in testing, stop routing through EmmaClef task tree
5. EmmaClef code remains in source as reference for action sequences
6. Dead mixins removed after scanner is stable

## Fabric Independence

Phase 58 enables a major dependency reduction:

- **Drop Fabric API** — only 2 of 60+ modules used; replace with 2 custom mixins (~100 lines)
- **Keep Fabric Loader** — just a mixin host (~2MB), stable, lightweight
- **Long-term (MC 26.1):** Optionally drop Fabric Loader by hosting SpongePowered Mixin via Java Agent — test any snapshot without waiting for Fabric updates
- Full details in `.github/phases/phase_Minecraft261.md`

## Reference Documents

### GOAP Architecture References

- **Jeff Orkin's F.E.A.R. paper:** `.github/phases/Phase_58_GOAP/FEAR_Three_States_And_A_Plan.md` — the foundational GOAP paper, summarized
- **JavaGOAP library:** `https://github.com/ph1387/JavaGOAP` — clean Java GOAP implementation, MIT licensed, can be used as dependency or reference
- **CBot (StarCraft GOAP bot):** `https://github.com/ph1387/CBot` — real-time strategy bot using JavaGOAP, closest to our use case
- **GPGOAP (C reference):** `https://github.com/stolk/GPGOAP` — ~500 lines, cleanest implementation to study the core algorithm
- **cppGOAP (C++ reference):** `https://github.com/cpowell/cppGOAP` — A* through action space, directly based on Orkin's work
- **GOAP + Utility AI hybrid:** `https://goldensyrupgames.com/blog/2024-05-04-grab-n-throw-utility-goap-ai/` — exactly the pattern we're building (utility scores which goal, GOAP plans how)
- **EmmaClef GOAP Grade:** `.github/phases/Phase_58_GOAP/EmmaClef_GOAP_Grade.md` — evaluation of EmmaClef's priority system vs GOAP gold standard (D- overall: 27/100). Covers priority scoring failures, brute-force retry patterns, combat rigidity, sub-agent model design, collateral scoring, personality weights as live control surface, and the agent decision debugger spec.

### Emma / Minecraft References

- **Emmatone built-in capabilities:** `.github/phases/phase_58_Emmatone_notes.md` — what Emmatone already does well (`#farm`, `#mine`, `#explore`, `#follow`, `#build`, `#waypoint`, etc.)
- **EmmaClef command research:** `emma_voice/EmmaClef_commands_research.md` — full command inventory, what's wired vs missing
- **EmmaClef commands reference:** `emma_voice/EmmaClef_Commands_Reference.md` — complete command reference
- **Fabric/mixin audit:** Brainstorm session March 2026 — traced all 30 EmmaClef mixins, found 5 dead, 3 dead imports, identified polling replacements for all 22 event-publishing mixins

## Sub-Phases

### Phase 58a: Auto Torch Placement — IMPLEMENTED

Automatic torch placement during underground activities. Two-layer control (enabled + active), 3-tick cooldown, off-hand priority, torch block guard, `torch_out` WebSocket event.

- Phase doc: `.github/phases/phase_58a_auto_torch_placement.md`
- Reference impl: `.github/phases/Phase_58_Torch_Logic/AutoTorchPlacer.java`
- WebSocket command: `torch` (enable/disable/toggle/status/set_threshold/active)
- EmmaClef commands: `@torch [on|off]`, `@torchlevel <n>`
- Python: `Emmatone_client.py` torch_* methods, `agent_tools.py` mc_torch, `cli_tools.py` mc_torch

### Phase 58b: Named Bases — (Implemented - Needs testing)

- Phase doc: `.github/phases/phase_58b_named_bases.md`

### Phase 58c: Storage Management — (Implemented - Needs testing)

- Phase doc: `.github/phases/phase_58c_storage_management.md`
- Track items in player inventory, overflow, and storage containers
- Prioritize emptying overflow into storage when at base

### Phase 58d: NeedsList + OpportunityScanner — (Implented - Not Tested)PLANNE

Core of the new player agent:

1. `NeedsList.java` — flat prioritized goal list, set via WebSocket
2. `OpportunityScanner.java` — per-tick scan for nearby needed resources
3. `SurvivalChecks.java` — simple threshold-based survival (replaces FoodChain/MobDefenseChain)
4. `ReflectionCache.java` — cached MethodHandles for private field access (replaces accessor mixins)
5. `GameStatePoller.java` — per-tick state diffing, publishes to EventBus during transition (replaces 22 event mixins)
6. `ArmorScorer.java` — weighted armor scoring: material tier + enchantment value (dimension-adjusted) + durability. Forces gold helmet in Nether. See Armor Scoring System section below.

### Phase 58e: EmmaClef Mixin Removal — AFTER 58d

Once scanner is stable:

1. Remove 22 dead event/accessor mixins from `EmmaClef.mixins.json`
2. Delete corresponding mixin + event class files
3. Drop `fabric-api` dependency from `build.gradle` and `fabric.mod.json`
4. Add 2 bridge mixins to `emma-bridge.mixins.json` (lifecycle + block interaction)

### Phase 58f: Agent Decision Debugger — WITH 58d (Implented - Not Tested)

(User: Lots of this is functionally done with .github\phases\phase_58d_player_logger.md, the only piece missing is logging what the GOAP decision tree did, which would be client side, we just have to make sure we time stamp events for both to the mS so we can compare.)

EmmaClef's current debugging is `print()` level — `CommandStatusOverlay` renders task class names on the HUD, `TaskTreeHandler` returns a flat JSON list via WebSocket, and `watch_task_tree.py` polls it in a terminal. No scores, no "why," no competing alternatives, no history. You can see WHAT the agent is doing but never WHY it chose that over something else.

The agent needs a real debugger — one that shows the decision-making process, not just the outcome. This isn't optional; without it, tuning utility curves and personality weights is blind guessing. It also needs a **combat log and death post-mortem system** so we can understand deaths without babysitting.

#### Combat Log & Death Post-Mortem

**Problem:** Emma died to a Piglin in the Nether with full diamond armor. No way to debug what happened — current `DamageListener` only sends basic `damage_taken` events with a 100ms cooldown (drops rapid hits from multiple attackers), `player.getAttacker()` is often null/stale, and Python only records the fatal blow's cause. No combat timeline, no armor durability, no nearby hostile count, no fight/flee decision reasoning, no dimension info.

**Solution:** A combat ring buffer that records every combat event, and on death, dumps the full timeline + player/environment state as a single `death_postmortem` event. Works with EmmaClef now, carries forward into GOAP unchanged.

**Combat Log Ring Buffer (`CombatLog.java`, `com.emma.bridge.events`):**

- 64-entry fixed-size ring buffer — O(1) write, zero GC pressure
- Records continuously with no cooldown (unlike `DamageListener`'s 100ms throttle)
- At ~3-4 events/second during combat, captures ~16-32 seconds of history

Event types recorded:

| Type                   | Details                                                                                                  | Recorded By                      |
| ---------------------- | -------------------------------------------------------------------------------------------------------- | -------------------------------- |
| `damage_taken`       | amount, health_after, attacker_type, attacker_health, attacker_distance                                  | DamageListener                   |
| `damage_dealt`       | target_type, target_health, weapon, distance                                                             | KillAura                         |
| `shield_block`       | duration_ms                                                                                              | KillAura / MobDefenseChain       |
| `heal`               | amount, health_after                                                                                     | DamageListener (health increase) |
| `armor_changed`      | old_value, new_value                                                                                     | HealthTracker                    |
| `food_eaten`         | item, hunger_after                                                                                       | FoodChain                        |
| `defense_decision`   | action (fight/flee/shield), canDealWith score, dangerousness score, hostile_count, armor, weapon, health | MobDefenseChain → GOAP auction  |
| `effect_gained/lost` | effect_id, amplifier                                                                                     | DamageListener (tick check)      |

**Death Post-Mortem Event (`death_postmortem`):**

Fired once when `DeathScreen` appears. Single JSON payload containing everything needed to reconstruct the death:

```
player_state:
  - position (x, y, z)
  - dimension (minecraft:the_nether, etc.)
  - armor value (0-20)
  - equipped items with durability per slot (head/chest/legs/feet/mainhand/offhand)
  - food level + saturation
  - active effects (fire_resistance, etc.)
  - was_on_fire, was_in_lava, was_in_water

attacker_info:
  - type, health, max_health, distance, position
  - equipment (weapon, armor)

nearby_hostiles:
  - count of hostiles within 16 blocks
  - list (type, health, distance) for up to 8 nearest

chain_state (current EmmaClef):
  - active chain name, active task
  - shielding flag, food chain eating/has_food
  → replaced by GOAP state once 58d ships: active strategy, goal scores, switch history

combat_log:
  - all ring buffer entries with timestamps (last ~30s of combat)

death_message:
  - text from DeathScreen (e.g., "Emma was slain by Piglin")
```

**Persistent Storage (Python):**

- Each post-mortem appended to `logs/death_postmortems.jsonl` (one JSON object per line)
- Greppable, shareable, no DB table needed — diagnostic telemetry
- Status report enhanced with dimension + hostile count for recent deaths
- Ring buffer increased from 20→50 events to capture more combat context

**GOAP Integration (58d):**

- `CombatLog` carries forward directly — GOAP actions record to same ring buffer
- `defense_decision` entries become GOAP auction snapshots (strategy scores, personality weights)
- Death post-mortem adds GOAP state: active strategy, goal scores, switch history, knowledge base
- The combat log IS the "why did Emma die?" replay

**Implementation files:**

| File                                           | Change                                                         |
| ---------------------------------------------- | -------------------------------------------------------------- |
| `com/emma/bridge/events/CombatLog.java`      | **NEW** — singleton ring buffer + `buildPostMortem()` |
| `com/emma/bridge/events/DamageListener.java` | Record to CombatLog, emit `death_postmortem` on death        |
| `com/emma/bridge/events/HealthTracker.java`  | Record armor changes                                           |
| `adris/EmmaClef/control/KillAura.java`       | Record damage dealt + shield blocks                            |
| `adris/EmmaClef/chains/MobDefenseChain.java` | Record fight/flee decisions with scores                        |
| `gamer/Emmatone_client.py`                   | Handle `death_postmortem`, persist to JSONL                  |

#### Head Rotation & Pitch Tracking

**Problem:** Emma's head movement looks robotic — snapping 180° to attack a mob behind her, then snapping back. During combat with multiple targets, she flips between them unnaturally. When mining or building, pitch changes are too abrupt or too slow. These are obvious to watch but impossible to diagnose without data. Right now it's "her head isn't pitching up fast enough" — subjective, no numbers, no replay.

**Solution:** Log yaw/pitch as a time series in the combat log ring buffer, and add a dedicated `rotation_log` to the debugger.

**What to track:**

- **Per-tick rotation:** yaw, pitch, delta_yaw, delta_pitch (degrees moved this tick)
- **Rotation source:** what requested the rotation — `KillAura` (target switch), `LookHelper.lookAt()`, `TravelLookOverride` (travel blending), `HeadRecenter` (pitch reset), Emmatone pathing, `BuilderProcess` (block placement)
- **Target switches:** when combat switches targets, log old_target → new_target + angle between them
- **Angular velocity:** degrees/tick — a real player rarely exceeds ~15°/tick for yaw, ~10°/tick for pitch. EmmaClef's instant snaps are 90-180°/tick.

**Rotation event in CombatLog:**

| Type              | Details                                                                             | Recorded By                                |
| ----------------- | ----------------------------------------------------------------------------------- | ------------------------------------------ |
| `rotation_snap` | delta_yaw, delta_pitch, source, target_entity (if combat), angle_to_previous_target | LookHelper / KillAura / TravelLookOverride |

Only recorded when `abs(delta_yaw) > 30` or `abs(delta_pitch) > 20` — captures the unnatural snaps without flooding the buffer during smooth movement.

**What this enables:**

- **Quantify the problem:** "KillAura caused 47 rotation snaps >90° in 30 seconds" vs "her head looks weird"
- **Tune smoothing:** `TravelLookOverride` already blends yaw during travel — extend the same approach to combat target switches with configurable max angular velocity
- **GOAP integration:** when GOAP replaces KillAura's target selection, the rotation log shows whether the new system produces more natural movement
- **Stream quality:** head snapping is one of the most visible "this is a bot" tells — data-driven tuning makes Emma look like a player, not a turret

#### What the Debugger Shows

**1. Live Utility Auction (the core view)**

Every tick, every scored action — what won, what lost, and why:

```
=== Tick 14,832 — Active Goal: get_diamonds (pri: 10) ===

  STRATEGIES (current: DigAndAttack [+1.5 hysteresis])
  -------------------------------------------------------
  DigAndAttack     7.5 + 1.5 = 9.0  *** ACTIVE ***
    progress: 0.8 (breaking block at -3,64,12)
    knowledge: obstruction confirmed by MeleeRush
  BowAttack        5.0
    preconditions: OK (has bow, 12 arrows)
  MeleeRush        1.2
    success_rate: 0.15 (0 hits / 3 taken)
  BlockAndPoke     4.0
    preconditions: OK (has cobblestone x34)

  COMPETING GOALS
  -------------------------------------------------------
  get_diamonds     10 x 0.7 relevance x 0.3 prox = 2.1
  stay_fed          6 x 0.2 urgency (16/20 hp) = 1.2
  survive           8 x 0.1 urgency (zombie 18 blks) = 0.8
  get_iron          5 x 0.0 (no iron nearby) = 0.0

  PERSONALITY WEIGHTS
  { safety: 0.5, aggression: 0.4, exploration: 0.6 }

  WORLD STATE (changed this tick)
  health: 16 → 14  |  block(-3,64,12): stone → air (broken!)
```

**2. Strategy Switch History (why did it change?)**

```
  RECENT SWITCHES (last 5)
  -------------------------------------------------------
  tick 14,810: MeleeRush → DigAndAttack
    reason: MeleeRush score crashed (8.0 → 1.2)
    trigger: 0 hits landed, 3 hits taken, obstruction found at (-3,64,12)

  tick 14,790: idle → MeleeRush
    reason: zombie entered detection range (6 blocks)
    trigger: survive goal spiked (0.8 → 6.4), MeleeRush won auction

  tick 14,600: MineIron → NavigateToCave
    reason: iron ore exhausted in current vein
    trigger: no iron ore in BlockScanner range
```

**3. Knowledge Base (what the agent has learned this session)**

```
  STRATEGY KNOWLEDGE
  -------------------------------------------------------
  obstructions: [(-3,64,12) stone — CLEARED]
  threats: [zombie at (-5,64,14) — 12hp, approaching]
  unreachable_blocks: [(-10,60,20) — marked tick 14,200]
  failed_strategies: {
    MeleeRush: { attempts: 1, last_result: "obstruction", success_rate: 0.15 }
  }
```

**4. Collateral Scoring Breakdown (for ties)**

```
  COLLATERAL ANALYSIS: MineIronOre
  -------------------------------------------------------
  primary (get_iron):    5 x 0.9 prox = 4.5
  collateral (diamonds): iron_pickaxe is prerequisite = +2.5
  collateral (build):    iron for anvil = +1.0
  total: 8.0

  vs MineCoal:
  primary (fuel):   3 x 0.9 = 2.7
  collateral: none
  total: 2.7
```

#### Implementation

**Java side — `AgentDebugState.java`:**

Singleton that accumulates debug data each tick. Cheap — just stores references and scores that are already computed by the GOAP planner. No extra computation:

```java
class AgentDebugState {
    // Populated by GOAP planner each tick
    List<ScoredStrategy> lastAuction;      // all strategies + scores
    StrategyAgent activeStrategy;
    float hysteresisBonus;
    List<ScoredGoal> goalScores;           // all goals + scores
    Map<String, Float> personalityWeights;
    List<StrategySwitchEvent> switchHistory; // ring buffer, last 20
    StrategyKnowledge knowledge;           // shared knowledge ref
    WorldState lastWorldState;
    WorldState worldStateDiff;             // what changed this tick
}
```

**WebSocket command — `agent_debug`:**

Returns the full debug state as JSON. Same pattern as existing `task_tree` command but with the GOAP data:

```json
{
  "command": "agent_debug",
  "params": { "include_history": true, "include_knowledge": true }
}
```

Response is the structured debug state. Python side can poll or subscribe.

**Display options (pick one or more):**

1. **In-game HUD overlay** — replaces `CommandStatusOverlay`. Rendered by a single mixin on `InGameHud.render()`. Shows live auction + active strategy + world state changes. Toggle with a keybind. Compact mode (scores only) or expanded mode (full breakdown).
2. **WebSocket stream to Python** — `agent_debug_stream` command subscribes to per-tick debug events. Python renders in terminal (like `watch_task_tree.py` but rich) or forwards to Emma's GUI as a new tab.
3. **Emma GUI tab** — `gui/minecraft_tab.py` already exists. Add a "Decision Debugger" panel that shows the live auction, switch history, and knowledge base. Tkinter treeview for the strategy hierarchy, color-coded scores (green = winning, red = losing, yellow = close to switching).
4. **Web overlay** — `overlay/` already has a WebSocket-driven overlay system. A debug overlay page at `localhost:port/debug` renders the GOAP state as a live web dashboard. Best for streaming — can be captured by OBS as a browser source for debug streams.

**Recommended:** Options 1 + 2. In-game HUD for quick visual check during play. WebSocket stream to Python for detailed inspection and logging. GUI tab and web overlay are nice-to-haves that build on the same JSON data.

#### What This Replaces

| Current (EmmaClef)                                  | New (GOAP Debugger)                                                              |
| --------------------------------------------------- | -------------------------------------------------------------------------------- |
| `CommandStatusOverlay` — task class names on HUD | Live utility auction with scores, personality weights, world state diff          |
| `TaskTreeHandler` — flat JSON of task chain      | Structured GOAP state: goals, strategies, knowledge, switch history              |
| `watch_task_tree.py` — 1s poll in terminal       | Real-time WebSocket stream with rich Python/GUI renderer                         |
| `Task.setDebugState(String)` — per-task text     | `StrategyReport` with structured findings, obstruction data, progress metrics  |
| `Debug.logMessage()` — console spam              | Switch history with causal chain: what triggered the switch, what scores changed |
| No "why" visibility                                 | Full auction breakdown: every strategy's score, every factor in the formula      |

#### Why This Is Non-Negotiable

Without the debugger, tuning the GOAP system is impossible. Questions like:

- "Why did Emma run from a zombie she could easily kill?" → check personality weights + threat scoring
- "Why is she ignoring iron ore right next to her?" → check collateral scoring + relevance mapping
- "Why does she keep switching between two strategies?" → check hysteresis threshold + score gap
- "Why did she die?" → replay switch history, find the bad decision, see what score produced it

These are the questions that turn a mediocre agent into a good one. The debugger is how you answer them.

### Phase 58m: Functional Build Schematics — PLANNED

Prebuilt schematics for gameplay-critical structures. EmmaClef has task classes for some of these (e.g., `ConstructNetherPortalObsidianTask`) but they fail in practice — even with materials in inventory. Instead of hardcoded Java block placement, ship `.litematic` files that feed into the existing build pipeline (`schematic_parser.py` → `build_guides` DB → Emmatone/Printer placement).

#### Why Schematics, Not Code

EmmaClef's portal builder places blocks one at a time with complex state machines for edge cases (lava casting, frame protection, interior clearing). It's brittle. The build system already handles multi-block placement reliably — schematic import, BOM computation, phase planning, structural/deferred passes, resume on failure. Use what works.

#### Functional Build Catalog

15 structures that serve specific gameplay tasks (not shelters or aesthetics):

| #  | Build                              | Purpose                       | Key Materials                           | Complexity |
| -- | ---------------------------------- | ----------------------------- | --------------------------------------- | ---------- |
| 1  | **Nether Portal**            | Dimension travel              | 10 obsidian, flint & steel              | Simple     |
| 2  | **Enchanting Setup**         | Level 30 enchants             | 1 enchanting table, 15 bookshelves      | Medium     |
| 3  | **Brewing Station**          | Potions                       | Brewing stand, cauldron, chest          | Simple     |
| 4  | **Smelting Array**           | Batch ore processing          | 4–8 furnaces, chests                   | Medium     |
| 5  | **Infinite Water Source**    | Renewable water               | 2×2 hole, 2 water buckets              | Trivial    |
| 6  | **Cobblestone Generator**    | Infinite cobblestone          | Lava bucket, water bucket, channel      | Simple     |
| 7  | **Crop Farm**                | Food / resources              | 9×9 irrigated plot, fence, gate        | Medium     |
| 8  | **Animal Pen**               | Breeding livestock            | Fenced enclosure, gate                  | Simple     |
| 9  | **XP / Mob Farm**            | XP + mob drops                | Dark room or spawner-based              | Complex    |
| 10 | **Villager Trading Hall**    | Organized trading             | Workstations, beds, cell walls          | Complex    |
| 11 | **Beacon Pyramid**           | Status effects                | 1–4 tier mineral block pyramid, beacon | Medium     |
| 12 | **Anvil + Smithing Station** | Tool repair / upgrade         | Anvil, smithing table, grindstone       | Simple     |
| 13 | **Sugar Cane Farm**          | Paper → books → bookshelves | Water/sand rows, fence                  | Medium     |
| 14 | **Fishing Platform**         | AFK fishing                   | Open-sky platform over water            | Simple     |
| 15 | **Conduit Frame**            | Underwater breathing          | Prismarine 5×5 ring, conduit           | Medium     |

#### Storage & Build Pipeline

The DB is the source of truth — `.litematic` files are generated from it at build time.

- **Definition:** Block lists defined programmatically in a new `gamer/functional_builds.py` module, same pattern as `block_catalog.py` seeding substitutions/resources
- **Seeding:** `seed_functional_builds()` inserts all 15 builds into `build_guides` + `build_guide_blocks` via `BuildDB.add_guide()` / `add_guide_blocks()`. Called during DB init (idempotent — skips if guide name already exists).
- **Tags:** Each guide tagged `functional` + purpose (e.g., `portal`, `enchanting`, `farming`, `smelting`). Enables lookup by need.
- **At build time:** `guide_to_litematic.py` converts the DB blocks → `.litematic` file → Litematica Printer places them. Existing `convert_phase_plan()` handles multi-phase builds, sweep waypoints, and BOMs automatically.
- **Substitutions:** Standard `block_catalog.py` rules apply — wood type, stone type swap automatically based on available materials
- **Each build is a list of dicts:** `{block_type, block_state, offset_x, offset_y, offset_z}` with offsets relative to origin (0,0,0). Placement order computed by `_compute_placement_order()` (Y→Z→X sort).

#### GOAP Integration

New action type in `ActionRegistry`:

```
DeployFunctionalBuild(build_id)
  preconditions:
    - has all materials (BOM check against inventory + storage)
    - no existing instance nearby (scan for key block within radius)
    - suitable placement site found (flat ground, correct dimension)
  effects:
    - functional structure exists at location
    - unlocks dependent actions (enchanting, brewing, smelting, etc.)
  executor:
    - select site (flat area near base or current position)
    - create build_goal linked to functional guide
    - run build pipeline (clear → structural → finishing)
    - post-placement activation (light portal, fill water source, etc.)
```

#### Activation via Needs

Emma's executive layer decomposes high-level goals into needs that trigger functional builds:

| Emma says                    | GOAP decomposes to                                  | Functional build triggered      |
| ---------------------------- | --------------------------------------------------- | ------------------------------- |
| "Prepare for the nether"     | Need obsidian ×10, need flint & steel, need portal | `functional_nether_portal`    |
| "Get enchanted diamond gear" | Need enchanting table, need bookshelves ×15        | `functional_enchanting_setup` |
| "Brew potions"               | Need brewing stand, need blaze powder, need bottles | `functional_brewing_station`  |
| "Smelt all this ore"         | Need furnace access, have 20+ raw ore               | `functional_smelting_array`   |
| "Set up a farm"              | Need food source, have seeds                        | `functional_crop_farm`        |
| "I need XP"                  | Need XP source, no spawner/farm nearby              | `functional_xp_farm`          |

#### Post-Placement Actions

Some builds need activation after block placement:

- **Nether Portal:** Light with flint & steel (right-click inside frame)
- **Infinite Water Source:** Place 2 water buckets in opposite corners
- **Cobblestone Generator:** Place lava last (after water channel is set)
- **Crop Farm:** Till soil with hoe, plant seeds, bone meal optional
- **Smelting Array:** Load fuel into furnaces

These are state machine steps within the `DeployFunctionalBuild` action, not separate GOAP goals.

#### Priority Order for Implementation

1. **Nether Portal** — highest demand, EmmaClef's version is broken, simple schematic
2. **Enchanting Setup** — critical progression milestone, exact block placement matters (bookshelf distance)
3. **Smelting Array** — batch processing needed constantly, simple but high-value
4. **Infinite Water Source** — trivial build, prerequisite for farms and cobble gen
5. **Cobblestone Generator** — early-game resource multiplication
6. **Crop Farm** — food sustainability
7. **Brewing Station** — nether progression
8. **Anvil + Smithing Station** — tool maintenance cluster
9. Everything else as needed

## Atomic Action Decomposition

Every complex Minecraft objective — from beating the Dragon to building a Guardian Farm — decomposes into the same ~20 atomic actions. GOAP's job is sequencing them differently per goal. If Emma can do each atom reliably, the builds are just data.

### Hard Atoms (environment interaction — what EmmaClef can't do)

| Atomic Action                                                    | Used By                                                | Why It's Hard                                                                                                                                                       |
| ---------------------------------------------------------------- | ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Herd entity with boat/rail**                             | Villager Hall                                          | Push villager into boat, drive boat to cell, break boat — entity physics + pathfinding around the entity                                                           |
| **Build over void/lava** (scaffolding)                     | Wither Skel, Blaze, Enderman                           | Place bridge blocks while sneaking at edge — fall = death, must scaffold outward                                                                                   |
| **Drain fluid section** (wall → sand/sponge → dry cycle) | Guardian Farm                                          | Iterative: seal section with walls, fill with sand or sponge, furnace-dry sponges, repeat. Fluid re-enters if wall has gaps. Massive scale — entire ocean monument |
| **Fight during construction**                              | Wither Skel, Blaze, Guardian                           | Can't just pause building — mobs spawn on the platforms you're building. Must interleave combat + placement                                                        |
| **Spawn-proof area** (slabs/light)                         | Wither Skel, Slime, Blaze                              | Cover every surface in the right material at the right light level — miss one block and spawns break the farm                                                      |
| **Manage debuffs**                                         | Guardian (Mining Fatigue), Wither Skel (Wither effect) | Drink milk/potions at the right time, retreat when debuffed                                                                                                         |
| **Breed + cure villagers**                                 | Villager Hall                                          | Feed villagers → wait for baby → grow up → zombie cure cycle (splash weakness + golden apple + wait 3-5 min)                                                     |
| **Navigate 3D underwater**                                 | Guardian Farm                                          | Air management, Depth Strider, water breathing potions, pathfinding in a flooded maze                                                                               |
| **Build around spawner** (don't break it)                  | Blaze Farm                                             | Protected block — build platforms around it without accidentally destroying it                                                                                     |
| **Excavate to bedrock**                                    | Slime Farm                                             | Dig out entire chunk (16×16×40+), manage water/lava encounters, haul materials out                                                                                |

### Easy Atoms (Emma can mostly do these already)

| Atomic Action           | Status                                                            |
| ----------------------- | ----------------------------------------------------------------- |
| Mine blocks             | Working (Emmatone `#mine`)                                      |
| Place blocks in pattern | Working (BuilderProcess, Phase 57d fixes)                         |
| Craft items             | Working (EmmaClef CraftInTableTask)                               |
| Smelt items             | Working (Phase 57d furnace fixes)                                 |
| Navigate to coordinates | Working (Emmatone `#goto`)                                      |
| Eat food                | Working (FoodChain)                                               |
| Kill hostile mob        | Working (KillAura + MobDefenseChain)                              |
| Place/use redstone      | Just block placement — same as any build                         |
| Brew potions            | Variant of smelting (brewing stand container)                     |
| Enchant items           | Place item + lapis, click enchant button — container interaction |

### The Pattern

Every farm is just `navigate hostile environment` + `build structure` + `manage entities` + `fight while doing it`. The builds themselves aren't hard — they're schematics. What's hard is the **interleaving** — doing multiple things simultaneously instead of tunnel-visioning on one.

This is exactly what GOAP solves and what EmmaClef's task tree cannot:

```
GOAP tick during Wither Skeleton farm construction:

  Build next platform block    → score: 5.0
  Kill wither skeleton (4 blk) → score: 8.5  ← wins, but RESUME building after
  Eat (health 8/20)            → score: 7.0
  Retreat (wither effect)      → score: 12.0 ← would win if debuffed
  Place slab (spawn-proof)     → score: 4.5
```

EmmaClef can't do this — it would either be 100% in combat mode (MobDefenseChain preempts everything) or 100% building (ignoring the skeleton walking up behind it). GOAP naturally interleaves because everything competes in the same scoring function.

## Milestone: Beat the Ender Dragon

### Why the Dragon Is the Right First Test

An 8-year-old can beat the Dragon but can't build a Villager Trading Hall. The Dragon fight is a **linear goal chain with clear preconditions** — no entity herding, no underwater building, no fluid mechanics. Just: navigate, fight, craft, navigate, fight, boss. Every single step is an atom Emma either already has or almost has.

It's also a natural test of GOAP's multi-goal planning — the Dragon isn't one task, it's a chain of prerequisites that span three dimensions. If EmmaClef's task tree tunnels on "get blaze rods" and ignores the Enderman standing right there, GOAP's proximity scoring picks up the pearl opportunistically. That's exactly the behavior difference we want to showcase.

And for the stream? "Emma beat the Ender Dragon" is a way better clip than "Emma built a slime farm." That's the kind of thing people share.

### Prerequisite Tree

```
Beat Dragon
  └─ Enter End Portal
       └─ Fill portal frame (12 Eyes of Ender)
            ├─ Craft Eyes (Blaze Powder + Ender Pearl)
            │    ├─ Kill Blazes (need Nether access)
            │    │    └─ Find Fortress → combat in cramped space + fire damage
            │    └─ Kill Endermen (or Piglin barter)
            └─ Find Stronghold (throw Eyes, follow trajectory)
                 └─ Navigate maze to portal room
  └─ Destroy End Crystals
       ├─ Bow the exposed ones
       └─ Pillar up to caged ones → break iron bars → destroy crystal
  └─ Hit Dragon when it perches
       └─ Dodge breath attacks, don't fall off island
```

### Hardest Sub-Problem

The **caged End Crystals** — pillar up on obsidian while the dragon knocks you around, break iron bars, hit crystal, get down without dying. This requires:

- Scaffolding up (place blocks below feet while jumping)
- Combat awareness (dragon dive-bombs during the climb)
- Multi-step interaction at height (break iron bars → hit crystal → descend)
- Fall damage management (don't just jump off — scaffold back down or use water bucket)

Everything else in the Dragon fight is atoms Emma can already do. The caged crystals are the one place where multiple hard atoms converge — building at height + combat + environmental hazard (void/fall).

## Farm Difficulty Progression

Ranked by which hard atoms each build requires. Each level adds new atoms on top of the previous:

### 1. Blaze Farm

**Tests:** Fight during construction + build around spawner
**Hard atoms:** 2 (combat interleaving, spawner protection)

Simplest Nether farm. Spawner-based, small build area. Navigate to a fortress, find a spawner, build platforms around it without breaking it, fight blazes during construction, spawn-proof the area, build a collection system. If Emma can do this, the GOAP "fight while building" interleaving is proven.

### 2. Slime Farm

**Tests:** Long-duration autonomous operation + massive excavation
**Hard atoms:** 1 (excavate to bedrock) + spawn-proofing

Dig out an entire chunk (16×16×40+ blocks). Not mechanically complex — just mine, mine, mine — but proves the agent can operate autonomously for extended periods without getting stuck, losing items, or dying to unexpected lava/water. Volume test.

### 3. Wither Skeleton Farm

**Tests:** Scaffolding over lava + combat + spawn-proofing (all at once)
**Hard atoms:** 3 (build over lava, fight during construction, spawn-proof area)

Combines multiple hard atoms simultaneously. Build platforms in the Nether fortress over lava lakes, fight wither skeletons during construction, slab every surface to control spawns. The wither effect debuff adds time pressure — must retreat or drink milk when hit.

### 4. Enderman Farm + End Enchanting Setup

**Tests:** Building over void + using enchanting system properly
**Hard atoms:** 2 (build over void, enchantment interaction)

Building over the void in the End — one wrong step and everything is lost. No recovery from falling into the void. Requires careful scaffolding + sneak-edge awareness. Also includes setting up and *using* an enchanting table: bookshelf placement at correct distance, level 30 enchant workflow (place item + lapis, select enchantment, evaluate results, decide whether to keep or grind and re-roll).

### 5. Villager Trading Hall

**Tests:** Entity herding + zombie curing + using the hall for trades
**Hard atoms:** 3 (herd entity, breed + cure villagers, trade interaction)

The hardest single atom to implement is **entity herding** — push a villager into a boat, drive it to a cell, break the boat, repeat. Entity physics are unpredictable. Then: zombie cure cycle (splash weakness + golden apple + wait 3-5 min per villager). But building it is only half — *using* the hall requires trade selection, job assignment (place/break workstations to reroll), and understanding which trades are worth locking in. The "use it properly" layer is a new kind of atom: GUI interaction with game knowledge.

### 6. Guardian Farm

**Tests:** Every hard atom at once — the final boss
**Hard atoms:** 5+ (drain fluid, fight during construction, manage debuffs, navigate underwater, build at scale)

The hardest build in Minecraft. The guardians themselves aren't the challenge — they just spawn and die in the kill chamber. The challenge is **draining the ocean monument**: seal sections with walls, fill with sand or sponge blocks (a LOT of sand), furnace-dry sponges, remove sand, repeat for the entire monument. Underwater 3D navigation while elder guardians inflict Mining Fatigue. Building while guardians spawn on you. The sheer scale — hundreds of thousands of blocks of water to displace — makes this the ultimate test of long-duration autonomous operation in a hostile environment.

---

## Phase 58p: GOAP Behavior Tuning & In-Game Commands (2026-03-11)

**Context:** First live GOAP test was surprisingly successful — Emma gathered food, recovered gear after death, dodged skeleton arrows beautifully (better than EmmaClef ever could). Several behaviors needed scoring adjustments based on live observation.

### Changes Implemented

**1. Combat Scoring — Attack vs Flee Balance**

- `UtilityScorer.java`: Default personality weights — aggression `0.5→0.8`, safety `0.5→0.6`
- `FleeFromAction.java`: Health > 15 HP → healthFactor `0.1` (near-full = almost never flee); weapon discount: single non-creeper threat with weapon → score ×0.4
- `AttackEntityAction.java`: Weapon bonus ×1.3 if damage > 3; single-target bonus ×1.2 for lone non-creeper
- **Result:** AttackEntity now beats FleeFrom+hysteresis at full HP with weapon (3.62 vs 1.56)

**2. Auto-Torch Placement**

- `PlaceTorchAction.java`: personality `"safety"→"neutral"` (×1.0 instead of ×0.5)
- `GoalSet.java`: `be_lit` priority `3→5`
- **Result:** PlaceTorch max score 6.6 (was 2.3), wins over non-urgent actions

**3. Food Overflow — Withdraw Before Farming**

- `CollectFoodAction.java`: New state machine `CHECK_OVERFLOW→WITHDRAW_WAIT→HUNT`; checks `OverflowClientApi.getCachedStatus()` for food items before farming

**4. Death Recovery — Wider Scan & Value Priority**

- `DeathRecoveryAction.java`: Scan radius `12→24` blocks; timeout `100→200` ticks (10s); value-weighted pickup (diamond=10, weapons=8, tools=6, iron=5, food=3, other=1)

**5. Armor Equip as Reflex (Always Fire)**

- **New:** `reflex/ArmorEquipReflex.java` — runs every 20 ticks, full armor scoring with material tiers, enchantments, dimension-aware Nether gold override. Does NOT suppress scoring.
- `EmmaBridgeClient.java`: Removed `EquipBestArmorAction` from scored actions, added `ArmorEquipReflex` to reflex layer

**6. GOAP Action Logger**

- `GoapTicker.java`: Periodic status log every 40 ticks (2s) — active action, runner-up, HP, food, light, threats, inventory. Action switch logging with scores. Reflex fire/release change detection.

**7. In-Game @ Commands**

- **New:** `events/ChatCommandInterceptor.java` — Fabric `ClientSendMessageEvents.ALLOW_CHAT` interceptor. Parses `@command args` from Minecraft chat, routes to `CommandRouter`. Supports: `@stop`, `@get`, `@goto`, `@mine`, `@status`, `@inventory`, `@debug`, `@goals`, `@personality`, `@goap on/off`, `@farm`, `@torch`, `@respawn`, plus fallthrough for any registered command.
- `AgentDebugHandler.java`: Added `enable`/`disable`/`log_on`/`log_off` control actions
- `EmmaBridgeClient.java`: Registers `ChatCommandInterceptor` after router setup

### Files Modified

| File                                      | Change                                             |
| ----------------------------------------- | -------------------------------------------------- |
| `goap/UtilityScorer.java`               | Default aggression=0.8, safety=0.6                 |
| `goap/actions/FleeFromAction.java`      | Lower flee at full HP, weapon discount             |
| `goap/actions/AttackEntityAction.java`  | Weapon + single-target bonuses                     |
| `goap/actions/PlaceTorchAction.java`    | Personality neutral                                |
| `goap/GoalSet.java`                     | be_lit priority 3→5                               |
| `goap/action/CollectFoodAction.java`    | Overflow check before farming                      |
| `goap/actions/DeathRecoveryAction.java` | Wider scan, value priority                         |
| `goap/reflex/ArmorEquipReflex.java`     | **New** — always-fire armor equip           |
| `goap/GoapTicker.java`                  | Periodic GOAP logging                              |
| `events/ChatCommandInterceptor.java`    | **New** — @ command interceptor             |
| `commands/AgentDebugHandler.java`       | Enable/disable/log control actions                 |
| `EmmaBridgeClient.java`                 | Register ArmorEquipReflex + ChatCommandInterceptor |

All paths relative to `gamer/minecraft/bridge_mod/src/main/java/com/emma/bridge/`

---

### Phase 58g: Recursive Goal Decomposition (2026-03-11)

**Problem:** `@get cooked_beef` set goal `have_item_cooked_beef` (score 4.00) but no action claimed it. SmeltItem needs raw_beef already in inventory. CraftItem has no recipe for cooked_beef. CollectFood only responds to `stay_fed`. AttackEntity only targets hostiles. Same gap exists for any multi-step item chain.

**Solution:** Event-driven goal decomposition layer that expands `have_item` goals into subgoals using `ItemRecipeRegistry`, plus two new GOAP actions for MOB_DROP items and opportunistic item pickup.

#### New Files

**1. `goap/GoalDecomposer.java`** — Core decomposition engine.

- Called when goals change (immediate) or inventory changes (10-tick debounce)
- Walks `ItemRecipeRegistry.getTransitiveDependencies()` in topological order
- For each missing intermediate: creates derived subgoal with type matching ObtainMethod
  - MINE → `have_item` (claimed by MineBlockAction)
  - CRAFT_* → `have_item` (claimed by CraftItemAction)
  - SMELT → `have_item` (claimed by SmeltItemAction)
  - MOB_DROP → `hunt_mob` (claimed by HuntMobAction)
  - CROP → `harvest_crop` (claimed by CollectFoodAction)
- Tool prerequisites: if MINE requires iron pickaxe and we don't have one, creates `have_item_iron_pickaxe` subgoal (recursive)
- Derived goal priority: `parentPriority - 0.5` (competes but slightly below parent)
- Static utilities `getChainDepth()` and `computeStepsSaved()` for PickupItemAction's shortcut bonus

**2. `goap/action/HuntMobAction.java`** — Handles `hunt_mob` derived goals (MOB_DROP items).

- Maps mob class names → entity classes (CowEntity, PigEntity, SheepEntity, ChickenEntity, RabbitEntity)
- Scores on `hunt_mob` goal type: `goal.priority * proximity_factor`
- Finds target mob in 32-block range, paths via Emmatone GoalNear, attacks on cooldown
- Uses `CombatHelper.equipBestWeapon()`, `BlockInteraction.lookAt()`
- Personality: `"aggression"`
- Distinct from AttackEntityAction (which only targets HostileEntity for self-defense)

**3. `goap/action/PickupItemAction.java`** — Opportunistic pickup of dropped items matching any `have_item` goal.

- Scans ItemEntities in 32-block range against all have_item goals (user + derived)
- Scoring: `goal.priority × proximity_factor × chain_shortcut_bonus`
  - `proximity_factor = 1.0 / (1.0 + distance / 10.0)`
  - `chain_shortcut_bonus = steps_saved / total_chain_depth` (clamped 0.1–1.0)
- Example: diamond_pickaxe at 20 blocks scores ~2.64, log at 10 blocks scores ~0.375 — high-value pickups dramatically outscore raw materials
- Paths to best item via Emmatone GoalNear, auto-pickup on collision
- Personality: `"exploration"`

#### Modified Files

| File                                   | Change                                                                                                                                                                                                                                                                                                   |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `goap/GoalSet.java`                  | Added `derivedGoals` list; `getGoals()` returns user+derived; `getUserGoals()`, `getDerivedGoals()`, `setDerivedGoals()` methods; Goal class: `parentGoalId`, `isDerived()`, 5-arg constructor; `setDynamicGoals()` clears derived; `toJson()` includes `is_derived`/`parent_goal` |
| `goap/GoapTicker.java`               | GoalDecomposer instance; inventory hash change detection each tick;`decomposeDirty` flag with 10-tick debounce; `triggerDecomposition()` for immediate recompute; `runDecomposition()`                                                                                                             |
| `goap/AgentDebugState.java`          | ScoredGoal: added `isDerived`/`parentGoalId` fields, 5-arg constructor, serialized in `toJson()`                                                                                                                                                                                                   |
| `goap/UtilityScorer.java`            | Goal scoring passes `isDerived()`/`parentGoalId` to ScoredGoal constructor                                                                                                                                                                                                                           |
| `commands/SetGoalsHandler.java`      | Calls `ticker.triggerDecomposition()` after setting goals; includes `derived_goals` count in response                                                                                                                                                                                                |
| `events/ChatCommandInterceptor.java` | `@goals` display separates user goals `[Goals]` from derived `[Derived]` with parent refs                                                                                                                                                                                                          |
| `EmmaBridgeClient.java`              | Registers HuntMobAction + PickupItemAction in action registry                                                                                                                                                                                                                                            |

#### Behavior: Opportunistic, Not Serial

The system is **not** a serial task queue. Subgoals are a snapshot of what's still needed, recomputed on inventory change:

- **Item found on ground:** PickupItemAction scores high via shortcut bonus → picks it up → inventory changes → redecompose → subgoals vanish
- **Intermediate acquired by any means:** iron_ingot picked up → derived_raw_iron + derived_iron_ingot subgoals disappear → chain skips ahead
- **Actions compete independently each tick:** CraftItem checks "do I have ingredients NOW?" — if ingredients appear from any source, it crafts immediately

#### Example: `@get cooked_beef`

```
Before: have_item_cooked_beef (pri=8.0) → all actions score 0
After decomposition:
  have_item_cooked_beef (pri=8.0, user)
  derived_beef (pri=7.5, type=hunt_mob, target={item:beef, mob_class:CowEntity})
→ HuntMobAction scores 7.5 × aggression → hunts cow → raw_beef drops
→ inventory changes → redecompose → SmeltItem now has raw_beef → smelts
→ goal satisfied
```

#### Example: `@get diamond_pickaxe` (empty inventory)

Full decomposition tree: oak_log → planks → sticks → cobblestone → wooden_pickaxe → stone_pickaxe → raw_iron → iron_ingot → iron_pickaxe → diamond → diamond_pickaxe. Each step auto-progresses as inventory updates.

#### Refresh Strategy

- **On goal change:** Immediate recompute (SetGoalsHandler triggers it)
- **On inventory change:** 10-tick debounce (0.5s) via hash comparison of WorldState.playerInventory
