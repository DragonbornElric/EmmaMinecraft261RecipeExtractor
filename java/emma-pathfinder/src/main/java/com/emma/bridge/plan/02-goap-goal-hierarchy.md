# Plan 2: GOAP Goal Hierarchy — Making @get the Top-Level Action

## The Problem

When a user says `@get diamond_pickaxe`, GoalDecomposer creates ~15-20 derived goals (mine diamond ore, craft sticks, smelt iron, etc.) and dumps them into a **flat list**. Then every tick, UtilityScorer runs an auction where `MineBlockAction`, `CraftItemAction`, `SmeltItemAction`, `CollectFoodAction`, and others all compete at the same level against the same pool of goals.

The result: crafting sticks (score 7.52) competes head-to-head with mining cobblestone (score 4.65) and smelting iron (score 6.44). There's no concept of "mining is a prerequisite for crafting" — the system treats them as independent, equally valid actions.

AltoClef solved this with explicit task nesting: `CraftInTableTask` internally spawns `CollectRecipeCataloguedResourcesTask` which internally spawns mining/smelting subtasks. One goal, one orchestrator, sequential phases.

---

## Current Architecture: How It Actually Works

### Goal Creation Flow

```
@get diamond_pickaxe
  → ChatCommandInterceptor.parseGet("diamond_pickaxe")
  → Creates: GoalSet.Goal { type="have_item", item="diamond_pickaxe", priority=8.0 }
  → GoapTicker.triggerDecomposition()
  → GoalDecomposer.decompose(goalSet, worldState)
  → Returns flat list of ~15 derived goals
```

### GoalDecomposer.decompose() — What It Actually Does

Walks `ItemRecipeRegistry.getTransitiveDependencies()` starting from the target item. For each dependency:

1. Determines obtain method (MINE, CRAFT_SHAPED_2x2, CRAFT_SHAPED_3x3, SMELT_FURNACE, etc.)
2. Creates a derived goal with priority = `parent_priority - 0.5 - (index × 0.1)`
3. Adds tool prerequisites (mining diamond ore needs iron pickaxe → creates that goal too)
4. Adds container prerequisites (crafting needs crafting table → creates that goal)
5. Applies feasibility boost (+0.2 to +0.4) based on proximity/inventory

Example output for `@get diamond_pickaxe`:

```
User goal: have_item_diamond_pickaxe (priority 8.0)

Derived goals (FLAT, no tree structure):
  derived_diamond_craft_3x3       (priority 7.4, needs: 3 diamond + 2 sticks)
  derived_raw_diamond_mine        (priority 7.3, needs: iron_pickaxe)
  derived_diamond_ore_mine        (priority 7.2)
  derived_iron_pickaxe_craft_3x3  (priority 7.1, tool prereq)
  derived_iron_ingot_smelt        (priority 7.0)
  derived_raw_iron_mine           (priority 6.9)
  derived_stone_pickaxe_craft_3x3 (priority 6.8, tool prereq)
  derived_cobblestone_mine        (priority 6.7)
  derived_wooden_pickaxe_craft_3x3(priority 6.6, tool prereq)
  derived_sticks_craft_2x2        (priority 6.5)
  derived_oak_planks_craft_2x2    (priority 6.4)
  derived_oak_log_mine            (priority 6.3)
  derived_crafting_table_craft_2x2(priority 6.2, container prereq)
```

### UtilityScorer.scoreAndSelect() — The Auction

Every tick:

```java
for (GoapAction action : actionRegistry.getAll()) {
    float primaryScore = action.computeScore(worldState, goalSet);
    // primaryScore = goal.priority × action-specific multiplier

    float collateral = 0;
    for (GoalSet.Goal g : goalSet.getAllGoals()) {
        collateral += g.priority * action.relevanceToGoal(worldState, g);
    }

    float raw = (primaryScore * personalityBias) + collateral;
    float adjusted = raw * successRate + (isActive ? 1.5 : 0);

    if (adjusted > bestScore + switchThreshold) {
        winner = action;
    }
}
```

### How Actions Score Against These Goals

**CraftItemAction.computeScore():**
- Calls `findCraftableInChain()` which walks derived goals sorted by priority
- For each derived goal with CRAFT_* obtain method: checks if ingredients are in inventory
- Returns `goal.priority × 0.8` for the first craftable intermediate
- Example: if we have planks, scores "craft sticks" at `6.5 × 0.8 = 5.2`

**MineBlockAction.computeScore():**
- Scans nearby mineable blocks that match any derived MINE goal
- Returns `goal.priority × (1.0 / (1.0 + distance/16.0))`
- Example: oak_log 5 blocks away scores `6.3 × (1.0 / 1.3125) = 4.8`

**SmeltItemAction.computeScore():**
- Finds derived SMELT goals where input item is in inventory and furnace is accessible
- Returns `goal.priority × 0.7`
- Example: raw_iron in inventory scores `7.0 × 0.7 = 4.9`

**Result:** All three compete in the same auction. The winner depends on which derived goal happens to have the highest priority and which action's multiplier is highest. There's no enforcement that "mine logs" happens before "craft planks" happens before "craft sticks."

---

## The Three Specific Failures

### Failure 1: Premature Craft Attempts

CraftItemAction wins the auction for "craft sticks" (score 5.2). It activates, enters FIND_TABLE state, navigates to a crafting table, opens the screen... and discovers it doesn't have enough planks. The recipe book call fails or the output slot is empty. After a timeout, it deactivates. Next tick, MineBlockAction wins for "mine oak_log." After mining, CraftItemAction wins again for "craft planks" instead of "craft sticks." One extra round-trip to the table wasted.

**Root cause:** CraftItemAction's `findCraftableInChain()` checks inventory counts but doesn't simulate deduction. If 4 planks are needed for sticks AND 4 for a crafting table, and we have 4 planks total, both appear "craftable" but only one actually is.

### Failure 2: Action Thrashing Between Unrelated Goals

With `@hero diamond` mode, 20+ goals exist simultaneously. Mining stone (priority 6.8) might beat crafting iron tools (priority 7.1 × 0.8 = 5.68) because MineBlockAction's distance factor gives a bonus for nearby stone. So the agent mines stone for 10 seconds, then CraftItemAction's score exceeds it (because cobblestone count now satisfies the stone goal, reducing MineBlock's score). Agent walks to crafting table. But hysteresis bonus keeps switching back and forth.

**Root cause:** Mining and crafting are scored as independent goals, not as sequential steps toward one parent goal. The 1.5 hysteresis bonus and 0.5 switch threshold aren't enough to prevent thrashing when two actions have scores within ~2 points.

### Failure 3: No Priority Ordering Within a Dependency Chain

Goal decomposer assigns priorities by index (`parent - 0.5 - index × 0.1`), which creates a loose ordering. But this ordering only matters if actions respect it. CraftItemAction's `findCraftableInChain()` sorts derived goals by priority and tries the lowest first (most foundational). Good. But MineBlockAction doesn't do this — it scores whatever block is nearest, regardless of whether that block's goal is a prerequisite for other goals.

Example: Iron ore is 10 blocks away, oak log is 3 blocks away. MineBlockAction scores iron ore at `6.9 × 0.615 = 4.24` and oak log at `6.3 × 0.842 = 5.3`. Agent mines oak logs. But the actual dependency chain requires oak logs → planks → sticks + planks → wooden pickaxe → mine stone → stone pickaxe → mine iron. The agent is correct to mine oak logs first, but only by accident (proximity), not by design.

---

## What AltoClef Did Differently

AltoClef doesn't have GOAP. It has a **task tree**:

```
BeatMinecraftTask.onTick():
  if (needWoodenPickaxe):
    return new CraftInTableTask(woodenPickaxeRecipe)
      → DoCraftInTableTask.onResourceTick():
          if (!hasRecipeMaterials):
            return new CollectRecipeCataloguedResourcesTask(recipe)
              → CollectPlanksTask
                → MineBlockTask(OAK_LOG)
          else:
            return containerSubTask()  // open table, craft
```

One task owns the entire chain. Sub-tasks block parent progress. No competing scores — the parent task decides which sub-task to execute, in order.

**What we want from this model:** Not the task tree itself (GOAP is better for reactive behavior), but the **guarantee that prerequisites complete before the parent action fires.**

---

## Proposed Solution: Tiered Goal Execution

### Concept: @get as a Meta-Action

Instead of decomposing `@get diamond_pickaxe` into 15 flat goals that all compete, treat `@get` as a **single GOAP action** that internally manages a dependency chain. Sub-goals don't participate in the main auction — they're resolved by the @get action's own internal logic.

### Architecture

```
Main GOAP Auction (unchanged):
  ├─ AttackEntity (survival)
  ├─ FleeFrom (survival)
  ├─ EatFood (survival)
  ├─ GetItemAction (@get diamond_pickaxe)  ← NEW: replaces CraftItem+MineBlock+Smelt for this goal
  ├─ PlaceTorch (ambient)
  ├─ EquipBestArmor (ambient)
  └─ ... other actions

GetItemAction internally manages:
  Phase 1: Determine acquisition plan
    → Walk dependency tree
    → Identify what we have vs. what we need
    → Order steps: mine → craft intermediates → smelt → craft final

  Phase 2: Execute current step
    → If current step is MINE: navigate to block, mine it (using MineBlock logic)
    → If current step is CRAFT: navigate to table, craft (using CraftItem logic)
    → If current step is SMELT: navigate to furnace, smelt (using SmeltItem logic)

  Phase 3: Advance to next step when current completes
    → Check if step's output is now in inventory
    → If yes, move to next step
    → If all steps done, mark @get goal as satisfied
```

### How This Fixes the Three Failures

**Failure 1 (premature crafts):** GetItemAction's internal planner won't advance to the CRAFT phase until the MINE phase has produced enough materials. No separate CraftItemAction competing in the auction.

**Failure 2 (action thrashing):** Only ONE GetItemAction is in the auction for `@get diamond_pickaxe`. It has a single score that reflects the overall goal's priority. No sub-goals competing with each other.

**Failure 3 (no ordering):** GetItemAction's internal planner walks the dependency chain bottom-up and enforces order. Mine logs → craft planks → craft sticks → craft wooden pickaxe → mine stone → etc.

---

## Detailed Design

### Option A: GetItemAction (Monolithic Internal Planner)

One new action class that subsumes CraftItem, MineBlock, SmeltItem for @get goals.

```java
public class GetItemAction extends GoapAction {
    enum InternalPhase {
        PLAN,           // build dependency chain
        EXECUTE_STEP,   // delegate to current step's logic
        ADVANCE,        // move to next step
        DONE
    }

    private List<AcquisitionStep> plan;  // ordered steps
    private int currentStepIndex;
    private GoapAction currentDelegate;  // borrows logic from MineBlock/CraftItem/Smelt

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        // Only scores for have_item goals with @get origin
        GoalSet.Goal getGoal = findGetGoal(goals);
        if (getGoal == null) return 0;
        if (state.hasItem(getGoal.item, getGoal.count)) return 0;  // already satisfied

        // High score — this is the primary goal action
        return getGoal.priority * 0.9f;
    }

    @Override
    public void tick() {
        switch (internalPhase) {
            case PLAN:
                plan = buildAcquisitionPlan(targetItem);
                currentStepIndex = 0;
                internalPhase = EXECUTE_STEP;
                break;

            case EXECUTE_STEP:
                AcquisitionStep step = plan.get(currentStepIndex);
                if (step.isSatisfied(worldState)) {
                    internalPhase = ADVANCE;
                } else {
                    step.tick(worldState);  // delegate to mine/craft/smelt logic
                }
                break;

            case ADVANCE:
                currentStepIndex++;
                if (currentStepIndex >= plan.size()) {
                    internalPhase = DONE;
                } else {
                    internalPhase = EXECUTE_STEP;
                }
                break;
        }
    }
}
```

**Pros:**
- Clean, single-responsibility
- No auction interference between sub-steps
- Easy to debug: log the plan, see which step we're on

**Cons:**
- Duplicates logic from MineBlockAction, CraftItemAction, SmeltItemAction
- Those actions still exist for non-@get goals (ambient mining, survival crafting)
- If the plan becomes stale (e.g., someone steals our items), replanning is complex

### Option B: Hierarchical Goal Blocking (Minimal Change)

Keep existing actions but add **prerequisite blocking** so actions can't fire until their prerequisites are met.

```java
// In GoalDecomposer: add dependency edges to derived goals
class DerivedGoal extends GoalSet.Goal {
    List<String> prerequisiteGoalIds;  // must be satisfied before this goal is actionable
}

// In UtilityScorer: check prerequisites before scoring
for (GoapAction action : actions) {
    GoalSet.Goal primaryGoal = action.getPrimaryGoal(goals);
    if (primaryGoal instanceof DerivedGoal dg) {
        boolean blocked = false;
        for (String prereqId : dg.prerequisiteGoalIds) {
            GoalSet.Goal prereq = goals.getGoal(prereqId);
            if (prereq != null && !prereq.isSatisfied(worldState)) {
                blocked = true;
                break;
            }
        }
        if (blocked) continue;  // skip this action entirely
    }

    float score = action.computeScore(state, goals);
    // ... rest of scoring
}
```

Example: `derived_diamond_pickaxe_craft_3x3` has prerequisites `[derived_diamond_mine, derived_sticks_craft]`. Until both are satisfied, CraftItemAction can't score for diamond_pickaxe. It CAN still score for sticks (which has prerequisite `derived_planks_craft` which has prerequisite `derived_oak_log_mine`).

**Pros:**
- Minimal code change — adds ~30 lines to GoalDecomposer and ~15 to UtilityScorer
- Existing actions work unchanged
- Natural: bottom-up execution emerges from blocking

**Cons:**
- Still has flat auction — actions compete within the same tier
- Doesn't fix thrashing between same-tier actions (e.g., mining stone vs mining iron when both are unblocked)
- Prerequisite satisfaction checks add per-tick cost

### Option C: Hybrid — GetItemAction as Orchestrator + Existing Actions as Delegates

GetItemAction exists in the auction but delegates to existing action instances:

```java
public class GetItemAction extends GoapAction {
    private final MineBlockAction mineDelegate;
    private final CraftItemAction craftDelegate;
    private final SmeltItemAction smeltDelegate;

    private List<AcquisitionStep> plan;
    private GoapAction activeDelegate;

    @Override
    public float computeScore(WorldState state, GoalSet goals) {
        GoalSet.Goal getGoal = findGetGoal(goals);
        if (getGoal == null || state.hasItem(getGoal.item, getGoal.count)) return 0;
        return getGoal.priority * 0.9f;
    }

    @Override
    public void tick() {
        AcquisitionStep step = plan.get(currentStepIndex);
        if (step.isSatisfied(worldState)) {
            advanceStep();
            return;
        }

        // Delegate to the right action for this step type
        switch (step.type) {
            case MINE:
                activeDelegate = mineDelegate;
                mineDelegate.setTarget(step.blockId, step.pos);
                break;
            case CRAFT:
                activeDelegate = craftDelegate;
                craftDelegate.setTarget(step.itemId, step.recipe);
                break;
            case SMELT:
                activeDelegate = smeltDelegate;
                smeltDelegate.setTarget(step.itemId, step.inputItem);
                break;
        }
        activeDelegate.tick();
    }
}
```

**Pros:**
- No logic duplication — reuses existing action code
- Single auction entry — no inter-step thrashing
- Plan-based execution with replanning on failure

**Cons:**
- Requires existing actions to support being "driven" externally (setTarget + tick without scoring)
- Moderate refactor to make actions callable from outside the auction

---

## Chosen Approach: Option C (Hybrid)

Option C gives us the best of both worlds:
- **Single GOAP entry** in the auction (no flat-goal thrashing)
- **Reuses existing action logic** (no duplication)
- **Plan-based ordering** (mine → craft → smelt → assemble)
- **Reactive replanning** (if items are lost, recompute the plan)

### Implementation Steps

**Step 1: Make existing actions delegate-capable**

Add to `MineBlockAction`, `CraftItemAction`, `SmeltItemAction`:

```java
// New method: execute externally without scoring
public void setExternalTarget(String itemId, /* other params */) {
    this.targetItem = itemId;
    this.externallyDriven = true;
    // Reset state machine to appropriate starting phase
}

public boolean isExternallyComplete() {
    return phase == DONE;
}
```

The existing `computeScore()` and `execute()` paths remain untouched for non-@get goals (survival, ambient).

**Step 2: Build AcquisitionPlanner**

New class that takes an item ID and builds an ordered list of steps:

```java
public class AcquisitionPlanner {
    public List<AcquisitionStep> plan(String targetItem, WorldState state) {
        // 1. Get transitive dependencies from ItemRecipeRegistry
        // 2. Topological sort by dependency depth (leaves first)
        // 3. For each dependency:
        //    - If already in inventory (sufficient count): skip
        //    - If obtainMethod is MINE: add MineStep
        //    - If obtainMethod is CRAFT_*: add CraftStep
        //    - If obtainMethod is SMELT_*: add SmeltStep
        //    - If obtainMethod is MOB_DROP: add HuntStep
        // 4. Add tool prerequisites inline (need wooden_pickaxe before mining stone)
        // 5. Return ordered list
    }
}
```

This replaces GoalDecomposer's role for @get goals. GoalDecomposer still handles @hero and other multi-goal modes.

**Step 3: Create GetItemAction**

Registers in the action registry alongside existing actions. Scores only for `have_item` goals created by `@get`. Internally delegates to existing actions via their `setExternalTarget()` API.

**Step 4: Suppress derived goals for @get**

When a goal is created by `@get`, mark it so GoalDecomposer doesn't create derived goals for it. GetItemAction handles decomposition internally. This prevents the flat-list problem.

For `@hero` mode (which creates many goals), GoalDecomposer still works as before — but each of the 20 hero goals gets its own GetItemAction instance in the auction, and each one manages its own internal plan.

---

## Interaction with Survival Goals

Survival goals (`survive`, `stay_fed`, `be_lit`) should still interrupt GetItemAction:

```
Main auction scores:
  GetItemAction: 7.2 (working on diamond_pickaxe)
  EatFood: 8.5 (hunger at 3/20)
  FleeFrom: 12.0 (creeper nearby)

→ FleeFrom wins (survival override)
→ GetItemAction pauses (hysteresis preserved)
→ After creeper leaves, GetItemAction resumes at same step
```

This works naturally with the existing auction. GetItemAction's score stays constant at `goal.priority × 0.9`, and survival actions naturally score higher when threats are present.

---

## Multiple @get Commands: Weighted Concurrent Goals

Currently `@get iron_pickaxe` then `@get iron_sword` **overwrites** the first goal — `SetGoalsHandler` calls `goalSet.setDynamicGoals()` which replaces all non-survival goals. The `"mode": "add"` parameter in the JSON is present but ignored.

### Fix: @get Accumulates Goals

Multiple `@get` commands should create multiple concurrent GetItemAction instances:

```
@get iron_pickaxe    → GetItemAction #1 (iron_pickaxe)
@get iron_sword      → GetItemAction #2 (iron_sword)

Both compete in the auction. Winner is chosen by WEIGHTED SCORE.
```

### Weighting Multiple @get Goals

When two GetItemActions compete, the winner should be determined by **ease of procurement**, not just raw priority. Score each GetItemAction using:

```java
float computeScore(WorldState state, GoalSet goals) {
    GoalSet.Goal goal = findMyGoal(goals);
    if (goal == null || state.hasItem(goal.item, goal.count)) return 0;

    AcquisitionPlan plan = getOrBuildPlan(goal.item, state);

    // Factor 1: How close are we to completion? (0.0 = just started, 1.0 = almost done)
    float completionRatio = (float) plan.completedSteps() / plan.totalSteps();

    // Factor 2: Distance to next step's material
    float proximity = plan.currentStep().proximityScore(state);  // 0.0 = far, 1.0 = adjacent

    // Factor 3: Material abundance for next step
    float abundance = plan.currentStep().abundanceScore(state);  // 0.0 = scarce, 1.0 = plentiful

    // Factor 4: Shared prerequisites already done (iron_pickaxe and iron_sword share sticks + iron)
    float sharedWorkBonus = computeSharedWorkBonus(plan, state);

    // Weighted combination
    float ease = (completionRatio * 0.3f) + (proximity * 0.3f) + (abundance * 0.25f) + (sharedWorkBonus * 0.15f);

    return goal.priority * ease;
}
```

**Example:** `@get iron_pickaxe` and `@get iron_sword` both need iron ingots + sticks. If the agent already has sticks and is near iron ore:
- iron_pickaxe needs 3 iron + 2 sticks → 2/4 steps done, iron ore 10 blocks away, 15 ores visible
- iron_sword needs 2 iron + 1 stick → 1/3 steps done, same iron ore, same quantity

Both have similar scores, but iron_pickaxe wins slightly (higher completion ratio). After crafting the pickaxe, iron_sword scores highest (shared iron smelting already done, sticks already crafted). The agent naturally chains related goals.

### Shared Prerequisite Optimization

When multiple @get goals share prerequisites (both need iron ingots), the AcquisitionPlanner should batch the shared work:

```
@get iron_pickaxe: needs 3 iron_ingot, 2 sticks
@get iron_sword:   needs 2 iron_ingot, 1 stick

Planner sees overlap:
  - Total iron_ingot needed: 5
  - Total sticks needed: 3
  - Mine 5 iron_ore (batched)
  - Smelt 5 iron_ingot (batched)
  - Craft 3 sticks (batched, needs 2 planks)
  - Craft iron_pickaxe
  - Craft iron_sword
```

The second GetItemAction's plan detects that iron_ingot and stick goals are already being handled by the first action's plan and scores itself lower until those shared steps complete.

### Implementation

1. **Fix SetGoalsHandler** to respect `"mode": "add"` parameter — merge instead of replace
2. **GoalSet.addDynamicGoal()** already exists for single-goal addition — wire it to @get
3. **Each @get goal gets its own GetItemAction** — multiple instances in the action registry
4. **GetItemAction.computeScore()** uses the weighted formula above

---

## Mode Architecture: @get, @hero, @gamer as Separate Paths

### Current Problem: Modes Overwrite Each Other

`@hero diamond` then `@get iron_pickaxe` → hero goals deleted, only pickaxe remains. `@get` then `@hero` → get goal deleted. They are mutually exclusive because both call `goalSet.setDynamicGoals()` which clears everything.

### Design: Non-Competing Goal Tiers

Two tiers. Tier 0 (survival) always runs. Tier 1 is the active mode — exactly one of @get, @hero, @gamer, or @build. Switching modes turns the previous one off.

```
┌──────────────────────────────────────────────────────────────┐
│                        GOAP Ticker                            │
│                                                               │
│  Tier 0: Survival (always active, all modes)                  │
│    ├─ FleeFrom, AttackEntity (reactive)                       │
│    ├─ EatFood (hunger-driven)                                 │
│    ├─ PlaceTorch, EquipArmor (ambient)                        │
│    └─ Priority: 8.0-12.0 (overrides everything)               │
│                                                               │
│  Tier 1: Active Mode (exactly one at a time)                  │
│    ├─ @get goals → GetItemAction instances                     │
│    ├─ @hero goals → GetItemAction instances                    │
│    │   (hero items scored by progression order)                │
│    ├─ @gamer goals → pipeline stages                           │
│    │   (kill_dragon decomposition)                             │
│    ├─ @build → BuildCampaignAction                             │
│    │   (GATHER all materials → BUILD entire schematic)         │
│    └─ Priority: 4.0-9.0 (yields to survival)                  │
│                                                               │
│  @stop = Tier 1 disabled (GOAP Off)                           │
│  @idle = Tier 1 empty (survival only)                          │
└──────────────────────────────────────────────────────────────┘
```

### Key Rules

1. **@stop = GOAP Off.** Ticker stops. No actions tick. Agent stands still.

2. **@idle = GOAP On, Tier 1 empty.** Only survival actions run (eat, flee, torch, armor). Agent maintains itself but doesn't pursue goals.

3. **@get = GOAP On, Tier 1 has GetItemAction(s).** Each @get creates a GetItemAction. Multiple @get commands accumulate. Score-weighted by ease/distance/abundance (see above).

4. **@hero = GOAP On, Tier 1 has hero GetItemActions.** Each hero equipment goal becomes a GetItemAction, scored by progression priority (wooden tools → stone → iron → diamond → netherite). Only 1-2 hero goals active at a time; rest blocked until current completes.

5. **@gamer = GOAP On, Tier 1 has speedrun pipeline.** kill_dragon decomposes into pipeline stages (build portal → enter nether → find fortress → kill blaze → find stronghold → kill dragon). Each stage is a composite goal with its own GetItemAction(s) for required items.

6. **@build = GOAP On, Tier 1 has BuildCampaignAction.** See "Build Mode" section below. Two internal phases: GATHER (uses GetItemAction delegates to collect ALL materials into EndInv upfront) then BUILD (delegates to Emmatone BuildProcess).

7. **Switching modes clears previous mode.** `@hero` after `@get` replaces @get goals with hero goals. `@build` after `@hero` replaces hero goals with build goals. Only one mode active at a time.

8. **Survival always wins.** Survival actions have higher base priority (8.0-12.0) and win when triggered. During @build's BUILD phase, Emmatone BuildProcess pauses via `builder.pause()` during survival interrupts and resumes after.

---

## Build Mode: @build as a Tier 1 Mode

### Current Architecture

Build mode is driven by Python via the `set_build_goal` WebSocket command. Python parses schematics, splits into phases, and sends each phase. Java's `GoalDecomposer.decomposeBuildStructure()` creates flat `have_item` subgoals for materials, which compete in the GOAP auction alongside everything else. `BuildStructureAction` only activates when `allMaterialsReady()` returns true.

### What's Changed: EndInv Eliminates Phased Gathering

With EndInv (effectively infinite storage), the old constraint of "gather phase 1 materials → build phase 1 → gather phase 2 materials → build phase 2" is gone. The agent can **gather ALL materials for the entire schematic upfront** into EndInv, then build the whole thing in one pass. No phased gathering needed.

### What's Changed: BuildProcess is Already Robust

The Emmatone BuildProcess (`emmatone/process/BuilderProcess.java`) is a hybrid of **Baritone's A* pathfinding** and **Litematica's Print block placement** logic. It already handles:

- **Structural ordering** via `PlacementOrderer` — foundation-first, support-aware placement
- **Layer-by-layer building** — prevents player from getting trapped
- **Pause/resume** — `builder.pause()` / `builder.resume()` for survival interrupts
- **GuideSchematic** — in-memory schematic from Python JSON (no file I/O)
- **Pathfinding to placement positions** — Baritone finds paths to each placeable block
- **Block-type matching** — prevents infinite re-placing (only checks block type, ignores directional properties)
- **Incremental recalc** — `recalcNearby()` for efficient progress tracking

Initial tests are very good. This just needs thorough testing, not a rewrite.

### Design: BuildCampaignAction

BuildCampaignAction is a Tier 1 mode (mutually exclusive with @get/@hero/@gamer). Two internal phases: GATHER everything, then BUILD everything.

```
BuildCampaignAction (Tier 1 — one per @build command)
    │
    ├─ Internal State Machine:
    │
    │   GATHER phase:
    │     ├─ Compute TOTAL material BOM for ENTIRE schematic
    │     ├─ Subtract what's already in inventory + EndInv
    │     ├─ Create GetItemAction delegates for each deficit material
    │     ├─ Score-weight materials by ease/proximity/abundance
    │     │   (same as multi-@get weighting)
    │     ├─ Materials go into EndInv (unlimited capacity)
    │     └─ Transition to BUILD when ALL materials accounted for
    │
    │   BUILD phase:
    │     ├─ Navigate to build origin
    │     ├─ Call EmmatoneAPI.getBuilderProcess().build(schematic, origin)
    │     ├─ BuildProcess handles everything: pathfinding, ordering,
    │     │   layer-by-layer placement, block matching
    │     ├─ Can pause for survival interrupts (builder.pause())
    │     └─ Transition to DONE when builder completes
    │
    │   DONE:
    │     └─ Broadcast build_complete to Python
    │
    └─ Survival Interrupts:
        ├─ GATHER phase: GetItemAction delegates pause (hysteresis)
        ├─ BUILD phase: BuildProcess pauses (builder.pause())
        └─ Resume seamlessly after survival action completes
```

### Why Gather-All-Then-Build Works Now

**Before EndInv:** Player inventory = 36 slots. Can't carry enough for a whole building. Must gather per-phase.

**With EndInv:** Unlimited storage. Gather 500 oak_planks, 300 cobblestone, 64 glass_panes — all stored in EndInv. Then BuildProcess places blocks, pulling from EndInv as needed.

This simplifies everything:
- No phase-by-phase Python orchestration for materials
- No `build_phase_complete` → gather next phase → `set_build_goal` round-trips
- Single GATHER pass → single BUILD pass
- BuildProcess already handles layer-by-layer placement internally

### How @build Uses GetItemAction

The GATHER phase internally creates GetItemAction delegates for each material — same Option C pattern as @get. The difference is scale: a building might need 20+ distinct materials in quantities of 64-500+.

```
@build oak_house
    → Schematic BOM: oak_planks×256, cobblestone×128, glass_pane×64, oak_door×2, torch×16
    → Subtract EndInv: oak_planks: 256-40=216 needed, cobblestone: 128-0=128 needed, ...
    → GetItemAction delegates created for each deficit
    → AcquisitionPlanner orders: mine oak_logs, craft planks, mine stone, mine sand,
      smelt glass, craft glass_panes, craft doors, mine coal, craft torches
    → Agent gathers everything into EndInv
    → BuildCampaignAction transitions to BUILD
    → EmmatoneAPI.getBuilderProcess().build(schematic, origin)
    → Done
```

### Python Integration

Python's role simplifies: parse schematic, compute full BOM, send ONE `set_build_goal` command with the complete schematic + materials. No phase-by-phase orchestration. Java handles gather + build end-to-end.

```
Python: set_build_goal(full_schematic_data)
  → Java: BuildCampaignAction enters GATHER phase
  → Java: gathers ALL materials via GetItemAction delegates → EndInv
  → Java: transitions to BUILD phase
  → Java: Emmatone BuildProcess builds entire schematic
  → Java: broadcasts build_complete
  → Python: done (or sends next building if multi-building campaign)
```

### BuildProcess: Needs Testing, Not Rewriting

The Emmatone BuildProcess is a Baritone pathfinding + Litematica Print hybrid that's already been initially tested successfully. Key areas for thorough testing:

1. **Large schematics** (500+ blocks) — performance of `recalcNearby()` and `PlacementOrderer`
2. **Multi-layer builds** — layer advancement, Y-range masking correctness
3. **Block support ordering** — doors, torches, signs placed after supporting blocks
4. **Survival interrupts** — pause/resume mid-build doesn't lose state
5. **EndInv integration** — BuildProcess can pull materials from EndInv during placement
6. **GuideSchematic block matching** — block-type-only matching prevents infinite re-place loops

---

## Migration Path

### Phase 1: Fix @get Accumulation

1. Fix `SetGoalsHandler` to support `"mode": "add"` (merge goals instead of replace)
2. Verify `GoalSet.addDynamicGoal()` works correctly for adding without clearing
3. Test: `@get iron_pickaxe` then `@get iron_sword` → both goals exist

### Phase 2: Build GetItemAction + AcquisitionPlanner

1. Make existing actions delegate-capable (`setExternalTarget()`)
2. Build `AcquisitionPlanner` (topological sort of dependency tree)
3. Build `GetItemAction` (orchestrator with internal plan)
4. Register GetItemAction in action registry
5. Suppress GoalDecomposer derived goals for @get goals

### Phase 3: Multi-@get Weighting

1. Implement weighted scoring (ease/distance/abundance/shared-work)
2. Add shared prerequisite detection between concurrent GetItemActions
3. Test: `@get iron_pickaxe` + `@get iron_sword` → batches shared iron/stick work

### Phase 4: @hero Mode Integration

1. Convert each hero equipment goal into a GetItemAction instance
2. Add progression-based sequencing (wooden → stone → iron → diamond)
3. Only 1-2 hero GetItemActions active at a time
4. Test: `@hero diamond` → agent progresses through tool tiers in order

### Phase 5: @build Mode Integration

1. Create `BuildCampaignAction` with GATHER → BUILD internal state machine
2. GATHER phase: compute full BOM, subtract EndInv, create GetItemAction delegates for deficits
3. BUILD phase: delegate to `EmmatoneAPI.getBuilderProcess().build()` (already works)
4. Refactor `SetBuildGoalHandler` to send full schematic + BOM (no per-phase splitting)
5. Thorough testing of BuildProcess (see testing checklist in Build Mode section)
6. Test: `set_build_goal` → agent gathers everything into EndInv → builds entire schematic

---

## Files to Modify

| File | Change | Phase |
|------|--------|-------|
| `commands/SetGoalsHandler.java` (or equivalent) | Support `"mode": "add"` to merge instead of replace | 1 |
| `goap/GoalSet.java` | Ensure `addDynamicGoal()` doesn't clear existing goals | 1 |
| `goap/actions/MineBlockAction.java` | Add `setExternalTarget()` + `isExternallyComplete()` | 2 |
| `goap/actions/CraftItemAction.java` | Add `setExternalTarget()` + `isExternallyComplete()` | 2 |
| `goap/actions/SmeltItemAction.java` | Add `setExternalTarget()` + `isExternallyComplete()` | 2 |
| `goap/actions/GetItemAction.java` | **NEW FILE** — orchestrator action | 2 |
| `goap/AcquisitionPlanner.java` | **NEW FILE** — dependency-ordered step planner | 2 |
| `goap/GoalDecomposer.java` | Skip decomposition for @get-origin goals | 2 |
| `events/ChatCommandInterceptor.java` | Mark @get goals; support accumulation | 2 |
| `goap/UtilityScorer.java` | Multi-GetItemAction weighted selection | 3 |
| `commands/SetModeHandler.java` | Hero goals → GetItemAction instances; mode isolation | 4 |
| `goap/actions/BuildCampaignAction.java` | **NEW FILE** — GATHER→BUILD orchestrator using GetItemAction + BuildProcess | 5 |
| `commands/SetBuildGoalHandler.java` | Accept full schematic + BOM; create BuildCampaignAction | 5 |
| `goap/actions/BuildStructureAction.java` | Refactor: BUILD phase logic extracted for use by BuildCampaignAction | 5 |
| `emmatone/process/BuilderProcess.java` | Testing only — verify EndInv integration, large schematics, survival interrupts | 5 |
