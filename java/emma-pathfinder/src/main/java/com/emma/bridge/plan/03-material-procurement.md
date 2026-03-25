# Plan 3: Material Scanning & Procurement

## The Problem

When the GOAP needs materials (ore, logs, food, etc.), several systems cooperate to find and acquire them: WorldState tracks what's nearby, MineBlockAction picks what to mine, GoalDecomposer estimates feasibility, and PickupItemAction grabs drops. These systems have gaps and weak integration that cause the agent to make poor procurement decisions.

---

## 1. How Block Scanning Actually Works

### WorldState.nearbyBlocks — External, Not Autonomous

`WorldState.nearbyBlocks` is a `Map<String, List<BlockPos>>` populated **externally** via `setNearbyBlocks()`. It does NOT scan on its own. An external BlockScanner (wired in GoapTicker) tells WorldState which block types and positions exist nearby.

**What this means:**
- Only blocks the BlockScanner is configured to look for are tracked
- If nobody configured scanning for `diamond_ore`, WorldState has zero diamond ore positions
- The scan list is driven by GoalDecomposer's derived goals — when a MINE goal exists for `iron_ore`, that block type gets added to the scan list

**Scan radius:** Determined by the BlockScanner, typically the loaded chunk radius (~128 blocks horizontal, full Y range).

**Update frequency:** Every tick (via GoapTicker).

### What It Stores

For each scanned block type: a `List<BlockPos>` of all matching positions. This gives us both existence and count (`list.size()`), plus exact positions for distance calculation. The data is there — the problem is how it's used downstream.

---

## 2. How MineBlockAction Picks What to Mine

### Current Selection Algorithm

`MineBlockAction.computeScore()` does:

1. For each derived goal with `obtainMethod: MINE`:
   - Get the block type(s) that produce this item (from `ItemRecipeEntry.mineBlockNames`)
   - Call `pickBestVariant()` to choose which block type to target
   - Find the **nearest single block** of that type
   - Score = `goal.priority × (1.0 / (1.0 + distance / 16.0))`

2. `pickBestVariant()` logic:
   - For each block variant (e.g., `iron_ore` vs `deepslate_iron_ore`):
     - Check if `positions.size() >= needed` (enough blocks of this type)
     - If yes, compute average distance to the nearest N blocks
     - Pick the variant with the lowest average distance
   - If no variant has enough blocks, pick the variant with the most blocks

### Problems

**Problem 1: Picks nearest single block, not best vein.** If a single iron ore is 5 blocks away and a vein of 30 iron ore is 20 blocks away, the agent mines the single ore. For a goal needing 8 iron ingots, this means 8 separate trips to scattered single ores instead of one trip to a rich vein.

**Problem 2: No vein clustering.** The algorithm treats each block independently. Two iron ores at (10,64,10) and (10,64,11) are not recognized as part of the same vein. Vein awareness would let the agent plan "mine this cluster of 12 ores" rather than "mine the nearest one, then recalculate."

**Problem 3: Distance scoring doesn't account for quantity needed.** Scoring uses `1/(1+dist/16)` which only cares about the nearest block. If the goal needs 32 iron ore, the distance to the 32nd ore matters more than the distance to the 1st. A vein of 32 ore at distance 30 is better than a single ore at distance 5 — but the current formula disagrees.

**Problem 4: Variant selection uses average distance, not total travel.** `pickBestVariant()` computes average distance to the nearest N blocks. But average distance doesn't reflect actual mining time — a cluster of N blocks at distance 30 takes less time than N scattered blocks at average distance 20 (because you don't travel between cluster members).

### Proposed Fix: Quantity-Weighted Vein Scoring

```java
float scoreMiningTarget(List<BlockPos> positions, int needed, BlockPos playerPos) {
    // Sort by distance from player
    positions.sort(Comparator.comparingDouble(p -> p.distSqr(playerPos)));

    // Take the nearest `needed` blocks
    int count = Math.min(positions.size(), needed);
    if (count == 0) return 0;

    // Travel cost: distance to first block + sum of inter-block distances
    double travelCost = Math.sqrt(positions.get(0).distSqr(playerPos));
    for (int i = 1; i < count; i++) {
        travelCost += Math.sqrt(positions.get(i).distSqr(positions.get(i - 1)));
    }

    // Score: blocks obtainable / travel cost
    // Higher = more efficient procurement
    float efficiency = (float) count / (float) (1.0 + travelCost / 16.0);
    return efficiency;
}
```

This naturally prefers dense veins (low inter-block distance) over scattered blocks (high inter-block travel). A cluster of 20 ores at distance 30 would score higher than 3 scattered ores at average distance 10 when the goal needs 8+.

### Proposed Fix: Vein Clustering

Simple flood-fill clustering to identify connected ore bodies:

```java
List<List<BlockPos>> clusterBlocks(List<BlockPos> positions) {
    Set<BlockPos> remaining = new HashSet<>(positions);
    List<List<BlockPos>> clusters = new ArrayList<>();

    while (!remaining.isEmpty()) {
        BlockPos seed = remaining.iterator().next();
        List<BlockPos> cluster = new ArrayList<>();
        Queue<BlockPos> frontier = new LinkedList<>();
        frontier.add(seed);
        remaining.remove(seed);

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            cluster.add(current);
            // Check 6 neighbors (or 26 for diagonal adjacency)
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (remaining.remove(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
        clusters.add(cluster);
    }
    return clusters;
}
```

Then score by cluster rather than by individual block — the agent targets the best cluster, not the nearest single ore.

---

## 3. How GoalDecomposer Estimates Feasibility

### Current Feasibility Boost Logic

`GoalDecomposer.computeFeasibilityBoost()` returns a bonus (0.0 to 0.4) added to derived goal priority:

| Method | Condition | Boost |
|--------|-----------|-------|
| MINE | At least 1 block nearby in `nearbyBlocks` | +0.3 |
| CRAFT_* | All ingredients in player inventory | +0.4 |
| SMELT_* | Input item in inventory AND furnace accessible | +0.2 |
| MOB_DROP | (not checked) | 0.0 |
| STONECUTTER | (not checked) | 0.0 |
| SMITH | (not checked) | 0.0 |
| BREW_* | (not checked) | 0.0 |

### Problems

**Problem 1: MINE boost is boolean.** `!nearby.isEmpty()` gives the same +0.3 whether there's 1 ore or 1,000 ores nearby. No distinction between "barely feasible" and "abundantly available."

**Problem 2: No quantity awareness.** If a recipe needs 8 iron ingots (→ 8 iron ore), the boost doesn't check whether 8 ores are actually nearby. 1 ore gives the same boost as 100.

**Problem 3: CRAFT boost doesn't consider derivable ingredients.** It checks `state.hasItem(alt, 1)` for each craft slot. If the player has oak_logs but not oak_planks, the CRAFT boost for a recipe needing planks is 0.0 — even though planks are trivially derivable from logs.

**Problem 4: Missing method boosts.** MOB_DROP, STONECUTTER, SMITH, and BREW have no feasibility logic at all. They always get 0.0 boost.

### Proposed Fix: Continuous Feasibility Score

Replace the binary boost with a 0.0–1.0 feasibility score:

```java
float computeFeasibility(WorldState state, ItemRecipeEntry entry, ObtainMethod method, int needed) {
    switch (method) {
        case MINE -> {
            int available = 0;
            for (String block : entry.getMineBlockNames()) {
                List<BlockPos> nearby = state.nearbyBlocks.get(qualify(block));
                if (nearby != null) available += nearby.size();
            }
            if (available == 0) return 0.0f;
            // Ratio of available to needed, capped at 1.0
            float abundance = Math.min(1.0f, (float) available / needed);
            // Distance penalty: nearest block distance
            float distPenalty = 1.0f / (1.0f + nearestDist / 32.0f);
            return abundance * distPenalty;
        }
        case CRAFT_SHAPED_2x2, CRAFT_SHAPED_3x3, CRAFT_SHAPELESS -> {
            int slotsReady = 0;
            int totalSlots = 0;
            for (String[] slotAlts : entry.getCraftGrid()) {
                if (slotAlts == null) continue;
                totalSlots++;
                for (String alt : slotAlts) {
                    if (state.hasItem(qualify(alt), 1) || canDeriveFromOwned(alt, state)) {
                        slotsReady++;
                        break;
                    }
                }
            }
            return totalSlots == 0 ? 0.0f : (float) slotsReady / totalSlots;
        }
        case SMELT_FURNACE, SMELT_BLAST, SMELT_SMOKER -> {
            boolean hasInput = false;
            for (String input : entry.getSmeltFrom()) {
                if (state.hasItem(qualify(input), 1)) { hasInput = true; break; }
            }
            boolean hasFurnace = hasContainerAccess(state, "furnace");
            if (hasInput && hasFurnace) return 1.0f;
            if (hasInput) return 0.7f;  // can place furnace
            if (hasFurnace) return 0.3f;  // need to acquire input
            return 0.1f;  // need both
        }
        case MOB_DROP -> {
            // Check if mob type is nearby
            boolean mobNearby = state.hasNearbyEntity(entry.getMobType());
            return mobNearby ? 0.6f : 0.1f;
        }
        default -> { return 0.1f; }
    }
}
```

Then use `feasibility × 0.5` as the boost (max +0.5) instead of the current fixed values.

---

## 4. How Inventory Awareness Works

### Three Inventory Sources

`WorldState.totalItemCount(itemId)` sums three sources:

1. **`playerInventory`** — direct hotbar + main inventory slots. Updated every tick.
2. **`endinvInventory`** — EndInv mod's infinite storage cache. Updated every tick via `EndinvBridge.getAllItems()`.
3. **`knownStorage`** — items in nearby chests/barrels. **Optionally populated** via `setKnownStorage()` — only works if StorageHandler is wired.

### Problems

**Problem 1: knownStorage is not automatically wired.** `StorageHandler.handleScan()` detects nearby containers and reads their contents via `ContainerTracker` cache, but **does not update `WorldState.knownStorage`**. The comment says "populated externally via setter (wired from Phase 58c StorageHandler when available)" — suggesting this integration is incomplete.

**Impact:** The GOAP doesn't know about items in chests. If there are 64 iron ingots in a chest 5 blocks away, `totalItemCount("iron_ingot")` returns 0 (unless they're also in EndInv).

**Fix:** Wire `StorageHandler.handleScan()` results into `WorldState.setKnownStorage()` automatically in GoapTicker's update cycle.

**Problem 2: EndInv items treated as immediately available.** `totalItemCount()` includes EndInv items as if they're in the player's hand. But extracting from EndInv requires a server round-trip. Actions that check "do I have enough?" via `totalItemCount()` may start executing before the items are actually accessible.

**Fix:** Distinguish between `immediateItemCount()` (player inventory only) and `totalItemCount()` (all sources). Actions should use `immediateItemCount()` for "can I craft right now?" and `totalItemCount()` for "is this goal satisfied?"

**Problem 3: No "items in transit" tracking.** If MineBlockAction mines an ore and the drop is on the ground, it's not in any inventory yet. But PickupItemAction might not have collected it. During this window, the item doesn't count toward goal satisfaction or craft feasibility.

**Fix:** Needs design — no solution proposed yet.

---

## 5. How PickupItemAction Works

### Current Behavior

Scans for dropped items within 32 blocks. Scores using:

```
score = goal.priority × proximityFactor × shortcutBonus
```

**Shortcut bonus:** Items that directly satisfy top-level goals get bonus 1.0. Items in the dependency chain get `steps_saved / (chain_depth + 1)`.

### This Works Well

PickupItemAction correctly prioritizes high-value drops (e.g., an iron pickaxe on the ground skips the entire mine→smelt→craft chain). No changes needed for the pickup logic itself.

### Integration Gap

PickupItemAction runs in the main GOAP auction, competing with MineBlockAction and CraftItemAction. If a dropped iron ingot is 2 blocks away but iron ore is also 2 blocks away, PickupItemAction should obviously win (ingot is more processed). It does — the shortcut bonus handles this. But if the ingot is 10 blocks away and the ore is 2 blocks away, the distance factor can override the shortcut bonus, causing the agent to mine new ore instead of picking up a free ingot.

**Fix:** PickupItemAction's shortcut bonus should be stronger — maybe `shortcutBonus = 2.0 × steps_saved / chain_depth` instead of the current formula. Or: add a "free item" bonus that always makes pickup beat mining for the same goal.

---

## 6. Tool Availability for Mining

### Current Behavior

GoalDecomposer injects tool prerequisites when a mining goal requires a specific tool tier. E.g., mining `iron_ore` requires `stone_pickaxe`, so a `have_item: stone_pickaxe` goal is created with higher priority.

MineBlockAction checks tool availability in its `computeScore()` — if the required tool isn't in inventory, the score is reduced or zeroed.

### Problem: Tool Durability Not Tracked

From the known issues: "Stone pickaxe doesn't re-craft after breaking (tool durability not tracked by goal satisfaction)."

`WorldState.isGoalItemSatisfied()` checks tool **existence** but not **durability**. Once a stone_pickaxe exists (even at 1 durability), the goal is satisfied. When it breaks, the goal doesn't reactivate until the next decomposition cycle — which may not happen if no inventory changes are detected.

**Fix:** Add durability threshold to tool goal satisfaction:

```java
boolean isToolGoalSatisfied(String toolItem, int minDurabilityPercent) {
    for (ItemStack stack : player.getInventory().items) {
        if (matchesItem(stack, toolItem)) {
            float durability = 1.0f - (float) stack.getDamageValue() / stack.getMaxDamage();
            if (durability >= minDurabilityPercent / 100.0f) return true;
        }
    }
    return false;
}
```

With a threshold like 10%, the goal reactivates when the tool is nearly broken, giving time to craft a replacement before it shatters.

---

## 7. Summary of Fixes

### Correctness Fixes

| Fix | Component | Impact |
|-----|-----------|--------|
| Wire StorageHandler → WorldState.knownStorage | GoapTicker + WorldState | GOAP knows about items in nearby chests |
| Quantity-aware feasibility boost | GoalDecomposer | Correct priority ranking for procurement goals |
| Tool durability in goal satisfaction | WorldState | Tools re-craft before breaking |

### Efficiency Fixes

| Fix | Component | Impact |
|-----|-----------|--------|
| Vein-aware mining (cluster scoring) | MineBlockAction | Agent targets rich veins instead of single scattered blocks |
| Separate immediateItemCount vs totalItemCount | WorldState | Prevents premature craft attempts with EndInv items |
| Stronger pickup shortcut bonus | PickupItemAction | Free items always beat mining for same goal |

### Optimizations

| Fix | Component | Impact |
|-----|-----------|--------|
| Vein clustering (flood-fill) | New utility | Agent understands ore bodies as units |
| Continuous feasibility (0.0-1.0) | GoalDecomposer | Smoother priority differentiation |
| Missing method feasibility (mob, stonecutter, etc.) | GoalDecomposer | Better scoring for non-mining procurement |
