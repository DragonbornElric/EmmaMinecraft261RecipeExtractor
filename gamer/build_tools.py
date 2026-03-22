#!/usr/bin/env python3
"""
Phase 47: Construction Toolkit — Discrete build tools for Emma's agent loop.

Each function is a standalone, idempotent tool that queries reality fresh on every
call. No shared mutable state between calls — all state lives in the DB, inventory,
and placed blocks. Emma's LLM reasoning sequences these tools; there is no state
machine or auto-transition logic.

Usage from agent_tools.py:
    from gamer.build_tools import build_bom, build_gather, build_structural, ...
"""

import json
import logging
import math
import time
from typing import Optional

log = logging.getLogger(__name__)

# Block → item name mapping (blocks whose item name differs from block name)
BLOCK_TO_ITEM: dict[str, str] = {
    "wall_torch": "torch",
    "soul_wall_torch": "soul_torch",
    "redstone_wall_torch": "redstone_torch",
    # Wall signs → sign items
    "oak_wall_sign": "oak_sign",
    "spruce_wall_sign": "spruce_sign",
    "birch_wall_sign": "birch_sign",
    "jungle_wall_sign": "jungle_sign",
    "acacia_wall_sign": "acacia_sign",
    "dark_oak_wall_sign": "dark_oak_sign",
    "mangrove_wall_sign": "mangrove_sign",
    "cherry_wall_sign": "cherry_sign",
    "bamboo_wall_sign": "bamboo_sign",
    "crimson_wall_sign": "crimson_sign",
    "warped_wall_sign": "warped_sign",
    # Wall hanging signs → hanging sign items
    "oak_wall_hanging_sign": "oak_hanging_sign",
    "spruce_wall_hanging_sign": "spruce_hanging_sign",
    "birch_wall_hanging_sign": "birch_hanging_sign",
    "jungle_wall_hanging_sign": "jungle_hanging_sign",
    "acacia_wall_hanging_sign": "acacia_hanging_sign",
    "dark_oak_wall_hanging_sign": "dark_oak_hanging_sign",
    "mangrove_wall_hanging_sign": "mangrove_hanging_sign",
    "cherry_wall_hanging_sign": "cherry_hanging_sign",
    "bamboo_wall_hanging_sign": "bamboo_hanging_sign",
    "crimson_wall_hanging_sign": "crimson_hanging_sign",
    "warped_wall_hanging_sign": "warped_hanging_sign",
    # Redstone wire
    "redstone_wire": "redstone",
    # Crops → seed items
    "wheat": "wheat_seeds",
    "carrots": "carrot",
    "potatoes": "potato",
    "beetroots": "beetroot_seeds",
    # Cauldron variants → cauldron item
    "water_cauldron": "cauldron",
    "lava_cauldron": "cauldron",
    "powder_snow_cauldron": "cauldron",
    # Misc
    "kelp_plant": "kelp",
    "tall_seagrass": "seagrass",
    "short_grass": "grass",  # may not be obtainable
    "tall_grass": "grass",
    "large_fern": "fern",
}


def _strip_ns(name: str) -> str:
    """Strip 'minecraft:' prefix if present."""
    return name.replace("minecraft:", "")


def _item_for_block(block_type: str) -> str:
    """Convert block_type to the item name needed for gathering."""
    clean = _strip_ns(block_type)
    return BLOCK_TO_ITEM.get(clean, clean)


def _aggregate_bom_as_items(bom: dict[str, int]) -> dict[str, int]:
    """Convert a block-based BOM to an item-based BOM (merge wall_torch → torch etc.)."""
    items: dict[str, int] = {}
    for block, qty in bom.items():
        item = _item_for_block(block)
        items[item] = items.get(item, 0) + qty
    return items


# ── Design & Planning ─────────────────────────────────────────


def build_design(client, goal_id: int) -> dict:
    """Load schematic, confirm origin, return build overview.

    Returns: {guide_id, name, dimensions, block_count, origin, block_stats}
    """
    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    guide_id = goal.get("guide_id")
    if not guide_id:
        return {"status": "error", "reason": "Goal has no guide attached"}

    guide = client.build_db.get_guide(guide_id)
    if not guide:
        return {"status": "error", "reason": f"Guide {guide_id} not found"}

    classified = client.build_db.classify_guide_blocks(guide_id)
    origin = {
        "x": goal.get("location_x", 0) or 0,
        "y": goal.get("location_y", 0) or 0,
        "z": goal.get("location_z", 0) or 0,
    }

    return {
        "status": "ok",
        "guide_id": guide_id,
        "name": guide["name"],
        "dimensions": guide.get("dimensions"),
        "block_count": guide.get("block_count", 0),
        "origin": origin,
        "block_stats": {
            "structural": len(classified["structural"]),
            "deferred": len(classified["deferred"]),
            "excluded": len(classified["excluded"]),
        },
    }


def build_bom(client, goal_id: int) -> dict:
    """Compute full BOM, diff against inventory + containers, return shortfall.

    Separates structural vs deferred blocks. Accounts for already-placed blocks.
    Returns enough context for Emma to decide: gather more, start building, or resume.
    """
    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    guide_id = goal.get("guide_id")
    if not guide_id:
        return {"status": "error", "reason": "Goal has no guide attached"}

    guide = client.build_db.get_guide(guide_id)
    if not guide:
        return {"status": "error", "reason": f"Guide {guide_id} not found"}

    # Full BOM from schematic
    full_bom = client.build_db.get_guide_bill_of_materials(guide_id)
    structural_bom = client.build_db.get_structural_bom(guide_id)
    deferred_bom = client.build_db.get_deferred_bom(guide_id)

    # Convert to item names for gather/inventory comparison
    full_bom_items = _aggregate_bom_as_items(full_bom)
    structural_bom_items = _aggregate_bom_as_items(structural_bom)
    deferred_bom_items = _aggregate_bom_as_items(deferred_bom)

    # Count already-placed blocks (via world_state diff)
    origin_x = goal.get("location_x", 0) or 0
    origin_y = goal.get("location_y", 0) or 0
    origin_z = goal.get("location_z", 0) or 0

    all_blocks = client.build_db.get_guide_blocks(guide_id)
    remaining = client._filter_placed_blocks(
        all_blocks, origin_x, origin_y, origin_z, goal_id
    )
    placed_count = len(all_blocks) - len(remaining)

    # BOM of remaining blocks only (what we still need to place)
    remaining_bom: dict[str, int] = {}
    for b in remaining:
        bt = b["block_type"]
        remaining_bom[bt] = remaining_bom.get(bt, 0) + 1
    remaining_items = _aggregate_bom_as_items(remaining_bom)

    # Query available supply: inventory + containers
    in_inventory = client.inventory_counts
    in_containers: dict[str, int] = {}
    try:
        if remaining_items:
            totals = client.storage_total(list(remaining_items.keys()))
            for item, counts in totals.items():
                in_containers[item] = counts.get("containers", 0)
    except Exception as exc:
        log.debug("storage_total failed (bridge may not be connected): %s", exc)

    # Calculate shortfall
    available: dict[str, int] = {}
    shortfall: dict[str, int] = {}
    for item, needed in remaining_items.items():
        inv = in_inventory.get(item, 0)
        ctr = in_containers.get(item, 0)
        total_avail = inv + ctr
        available[item] = total_avail
        if total_avail < needed:
            shortfall[item] = needed - total_avail

    # Estimate gather trips
    free_slots = client.inventory_free_slots
    unique_materials = len(shortfall)
    if unique_materials > 0 and free_slots > 0:
        slots_per_material = max(1, free_slots // unique_materials)
        capacity_per_trip = slots_per_material * 64
        total_shortfall = sum(shortfall.values())
        estimated_trips = max(1, math.ceil(total_shortfall / capacity_per_trip))
    else:
        estimated_trips = 0

    return {
        "status": "ok",
        "goal_name": goal["name"],
        "guide_name": guide["name"],
        "block_count": guide.get("block_count", 0),
        "placed": placed_count,
        "remaining": len(remaining),
        "full_bom": full_bom_items,
        "structural_bom": structural_bom_items,
        "deferred_bom": deferred_bom_items,
        "remaining_bom": remaining_items,
        "available": available,
        "shortfall": shortfall,
        "in_inventory": {k: v for k, v in in_inventory.items() if v > 0},
        "estimated_gather_trips": estimated_trips,
    }


def build_phase_plan(client, goal_id: int) -> dict:
    """Split schematic into buildable phases by Y-layer and material capacity.

    Returns phase list with per-phase BOM. Classifies blocks as
    structural / deferred / excluded per the Deferred Block Registry.
    """
    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    guide_id = goal.get("guide_id")
    if not guide_id:
        return {"status": "error", "reason": "Goal has no guide attached"}

    # Get config for phase sizing
    from gamer import config as _cfg
    build_cfg = _cfg.get("build", {}) or {}

    max_blocks = build_cfg.get("phase_max_blocks", 256)
    layer_height = 2  # match Emmatone layerHeight

    phases = client.build_db.split_into_phases(
        guide_id,
        max_blocks_per_phase=max_blocks,
        layer_height=layer_height,
    )

    # Add per-phase item-name BOMs (structural + deferred combined)
    for phase in phases:
        phase["bom_items"] = _aggregate_bom_as_items(phase["bom"])
        phase["deferred_bom_items"] = _aggregate_bom_as_items(phase["deferred_bom"])
        # Combined BOM for restocking: structural + deferred for this Y-range
        combined = dict(phase["bom"])
        for bt, cnt in phase["deferred_bom"].items():
            combined[bt] = combined.get(bt, 0) + cnt
        phase["total_bom_items"] = _aggregate_bom_as_items(combined)

    # Deferred (finishing) phase — safety-net for the whole build
    deferred_bom = client.build_db.get_deferred_bom(guide_id)
    classified = client.build_db.classify_guide_blocks(guide_id)
    deferred_count = len(classified["deferred"])
    excluded_count = len(classified["excluded"])

    return {
        "status": "ok",
        "phases": phases,
        "finishing_phase": {
            "block_count": deferred_count,
            "bom": _aggregate_bom_as_items(deferred_bom),
        },
        "excluded_count": excluded_count,
        "total_phases": len(phases) + (1 if deferred_count > 0 else 0),
    }


# ── Resource Acquisition ──────────────────────────────────────


def build_gather(client, item: str, quantity: int) -> dict:
    """Issue EmmaClef 'get' for one material type.

    Blocks until the EmmaClef task starts (returns immediately with task_id).
    The caller (Emma) can check status with minecraft_status / emmaclef_status.

    Returns: {status, item, requested, task_result}
    """
    if not client.connected:
        return {"status": "error", "reason": "Emmatone not connected"}

    clean_item = _strip_ns(item)
    try:
        result = client.emmaclef_task("get", f"{clean_item} {quantity}")
        return {
            "status": "ok",
            "item": clean_item,
            "requested": quantity,
            "task_result": result,
        }
    except Exception as exc:
        log.warning("build_gather failed for %s ×%d: %s", item, quantity, exc)
        return {"status": "error", "item": clean_item, "reason": str(exc)}


def build_stage(client, goal_id: int) -> dict:
    """Deposit non-essential inventory to staging chests near the build site.

    Keeps tools, armor, and food. Creates chests if none exist nearby.
    Returns updated BOM status.
    """
    if not client.connected:
        return {"status": "error", "reason": "Emmatone not connected"}

    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    from gamer import config as _cfg
    build_cfg = _cfg.get("build", {}) or {}

    keep_food = build_cfg.get("keep_food_count", 16)

    # Identify materials to deposit (everything except tools, armor, food)
    inv = client.inventory_counts
    deposit_items: dict[str, int] = {}
    for item, count in inv.items():
        if count <= 0:
            continue
        # Keep essential items
        if _is_essential_item(item):
            continue
        deposit_items[item] = count

    if not deposit_items:
        return {
            "status": "ok",
            "deposited": {},
            "message": "Nothing to deposit — inventory is empty or only essentials.",
        }

    # Use storage_deposit_nearby — it finds/creates chests automatically
    try:
        result = client.storage_deposit_nearby(deposit_items)
        return {
            "status": "ok",
            "deposited": deposit_items,
            "storage_result": result,
        }
    except Exception as exc:
        log.warning("build_stage failed: %s", exc)
        return {"status": "error", "reason": str(exc)}


def _is_essential_item(item: str) -> bool:
    """Check if an item should be kept during staging (tools, armor, food)."""
    essential_keywords = {
        "sword", "pickaxe", "axe", "shovel", "hoe",
        "helmet", "chestplate", "leggings", "boots",
        "shield", "bow", "crossbow", "trident",
        "bread", "cooked", "steak", "golden_apple",
        "apple", "melon_slice", "baked_potato", "pumpkin_pie",
        "mushroom_stew", "rabbit_stew", "beetroot_soup",
        "totem_of_undying", "elytra",
    }
    clean = _strip_ns(item)
    return any(kw in clean for kw in essential_keywords)


# ── Construction ───────────────────────────────────────────────


def build_clear_site(client, goal_id: int) -> dict:
    """Clear the build volume using Emmatone clearArea().

    Sends clearArea command for the schematic bounding box.
    Returns status with block count info.
    """
    if not client.connected:
        return {"status": "error", "reason": "Emmatone not connected"}

    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    guide_id = goal.get("guide_id")
    if not guide_id:
        return {"status": "error", "reason": "Goal has no guide attached"}

    guide = client.build_db.get_guide(guide_id)
    if not guide:
        return {"status": "error", "reason": f"Guide {guide_id} not found"}

    origin_x = goal.get("location_x", 0) or 0
    origin_y = goal.get("location_y", 0) or 0
    origin_z = goal.get("location_z", 0) or 0

    dims = guide.get("dimensions") or {}
    dx = dims.get("x", 16)
    dy = dims.get("y", 16)
    dz = dims.get("z", 16)

    # clearArea clears from corner1 to corner2
    # Add 1-block perimeter for Emmatone access
    try:
        result = client._send_command("clear_area", {
            "x1": origin_x - 1,
            "y1": origin_y,
            "z1": origin_z - 1,
            "x2": origin_x + dx,
            "y2": origin_y + dy,
            "z2": origin_z + dz,
        }, timeout=10.0)
        return {
            "status": "ok",
            "origin": {"x": origin_x, "y": origin_y, "z": origin_z},
            "dimensions": dims,
            "clear_result": result,
        }
    except Exception as exc:
        log.warning("build_clear_site failed: %s", exc)
        return {"status": "error", "reason": str(exc)}


def build_structural(client, goal_id: int, phase_index: int | None = None) -> dict:
    """Issue Emmatone build for structural blocks (deferred blocks in buildIgnoreBlocks).

    If phase_index is given, only builds that phase's Y-band blocks (structural +
    deferred). Java's internal 3-phase pipeline (CLEARING → BUILD → FINISHING)
    handles the structural/deferred split automatically per submitted block subset.

    If phase_index is None, builds ALL blocks (full schematic).

    Returns: {status, placed, total, remaining, pct}
    """
    if not client.connected:
        return {"status": "error", "reason": "Emmatone not connected"}

    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    guide_id = goal.get("guide_id")
    if not guide_id:
        return {"status": "error", "reason": "Goal has no guide attached"}

    # Move to current if planned
    if goal["status"] in ("planned", "future"):
        client.build_db.update_goal_status(goal_id, "current")

    client._active_goal_id = goal_id

    origin_x = goal.get("location_x", 0) or 0
    origin_y = goal.get("location_y", 0) or 0
    origin_z = goal.get("location_z", 0) or 0

    # Initialize progress tracking (safe on resume — INSERT OR IGNORE)
    client.build_db.init_progress_from_guide(goal_id, guide_id)

    if phase_index is not None:
        # ── Per-phase build: send only this Y-band's blocks ──
        phases = client.build_db.split_into_phases(guide_id)
        if phase_index >= len(phases):
            return {"status": "error", "reason": f"Phase {phase_index} out of range (max {len(phases) - 1})"}

        phase = phases[phase_index]
        y_min, y_max = phase["y_min"], phase["y_max"]

        # Get ALL blocks (structural + deferred) for this Y-band
        band_blocks = client.build_db.get_blocks_for_y_range(guide_id, y_min, y_max)
        if not band_blocks:
            return {"status": "ok", "placed": 0, "total": 0, "remaining": 0, "pct": 100.0,
                    "message": f"Phase {phase_index} (Y {y_min}-{y_max}): no blocks to place."}

        # Filter out already-placed blocks
        remaining_blocks = client._filter_placed_blocks(
            band_blocks, origin_x, origin_y, origin_z, goal_id
        )
        if not remaining_blocks:
            return {"status": "already_complete", "placed": len(band_blocks), "total": len(band_blocks),
                    "remaining": 0, "pct": 100.0,
                    "message": f"Phase {phase_index} (Y {y_min}-{y_max}): all blocks already placed!"}

        # Map to Java format
        guide = client.build_db.get_guide(guide_id)
        build_name = f"{guide['name']}_phase{phase_index}" if guide else f"guide_{guide_id}_phase{phase_index}"

        mapped_blocks = []
        for b in remaining_blocks:
            entry = {
                "type": b["block_type"],
                "x": b["offset_x"],
                "y": b["offset_y"],
                "z": b["offset_z"],
            }
            bs = b.get("block_state")
            if bs and isinstance(bs, dict) and bs:
                entry["state"] = ",".join(f"{k}={v}" for k, v in bs.items())
            mapped_blocks.append(entry)

        # Normalize negative offsets for Java's 0-based schematic arrays
        if mapped_blocks:
            min_x = min(b["x"] for b in mapped_blocks)
            min_y = min(b["y"] for b in mapped_blocks)
            min_z = min(b["z"] for b in mapped_blocks)
            if min_x < 0 or min_y < 0 or min_z < 0:
                shift_x = -min_x if min_x < 0 else 0
                shift_y = -min_y if min_y < 0 else 0
                shift_z = -min_z if min_z < 0 else 0
                for b in mapped_blocks:
                    b["x"] += shift_x
                    b["y"] += shift_y
                    b["z"] += shift_z
                origin_x -= shift_x
                origin_y -= shift_y
                origin_z -= shift_z

        # Cancel pending idle, suppress auto-idle during build
        client._cancel_idle_timer()
        client._idle_suppressed = True

        build_result = client._send_command("build", {
            "guide_id": guide_id,
            "name": build_name,
            "origin": {"x": origin_x, "y": origin_y, "z": origin_z},
            "blocks": mapped_blocks,
        }, timeout=30.0)

        # Check for Java-side error
        if build_result.get("status") == "error":
            return {
                "status": "error",
                "reason": build_result.get("error", build_result.get("reason", "unknown Java error")),
            }

        total_phase = len(band_blocks)
        remaining_count = len(remaining_blocks)
        placed = total_phase - remaining_count

        return {
            "status": build_result.get("status", "ok"),
            "phase_index": phase_index,
            "y_range": {"min": y_min, "max": y_max},
            "placed": placed,
            "total": total_phase,
            "remaining": remaining_count,
            "pct": round(placed / total_phase * 100, 1) if total_phase > 0 else 100.0,
            "message": (
                f"Phase {phase_index} (Y {y_min}-{y_max}): {remaining_count} blocks to place "
                f"out of {total_phase}. Java handles structural→deferred ordering."
            ),
        }

    # ── Full build (no phase_index): send all blocks ──
    build_result = client.build(
        guide_id, origin_x, origin_y, origin_z,
        goal_id=goal_id,
    )

    # Check for Java-side error
    if build_result.get("status") == "error":
        return {
            "status": "error",
            "reason": build_result.get("reason", build_result.get("error", "unknown Java error")),
        }

    remaining = build_result.get("remaining", 0)
    total = client.build_db.get_guide(guide_id).get("block_count", 0)
    placed = total - remaining

    classified = client.build_db.classify_guide_blocks(guide_id)
    structural_total = len(classified["structural"])

    return {
        "status": build_result.get("status", "ok"),
        "placed": placed,
        "total": total,
        "structural_total": structural_total,
        "remaining": remaining,
        "pct": round(placed / total * 100, 1) if total > 0 else 100.0,
        "message": (
            f"Build started: {remaining} blocks remaining out of {total}. "
            f"Structural: {structural_total}, deferred blocks will be placed in finishing pass."
        ),
    }


def build_finishing(client, goal_id: int) -> dict:
    """Issue Emmatone build for deferred blocks only (doors, torches, ladders, etc.).

    This sends only the deferred blocks to Emmatone. The Java side runs without
    buildIgnoreBlocks so all attachment/interactable blocks are attempted.
    Walls/floors must already exist from the structural pass.

    Returns: {status, placed, total_deferred, pct}
    """
    if not client.connected:
        return {"status": "error", "reason": "Emmatone not connected"}

    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    guide_id = goal.get("guide_id")
    if not guide_id:
        return {"status": "error", "reason": "Goal has no guide attached"}

    client._active_goal_id = goal_id

    origin_x = goal.get("location_x", 0) or 0
    origin_y = goal.get("location_y", 0) or 0
    origin_z = goal.get("location_z", 0) or 0

    # Get only deferred blocks
    classified = client.build_db.classify_guide_blocks(guide_id)
    deferred_blocks = classified["deferred"]

    if not deferred_blocks:
        return {
            "status": "ok",
            "placed": 0,
            "total_deferred": 0,
            "pct": 100.0,
            "message": "No deferred blocks to place — finishing pass not needed.",
        }

    # Filter out already-placed deferred blocks
    remaining = client._filter_placed_blocks(
        deferred_blocks, origin_x, origin_y, origin_z, goal_id
    )

    if not remaining:
        return {
            "status": "ok",
            "placed": len(deferred_blocks),
            "total_deferred": len(deferred_blocks),
            "pct": 100.0,
            "message": "All deferred blocks already placed!",
        }

    # Map to Java format
    guide = client.build_db.get_guide(guide_id)
    build_name = f"{guide['name']}_finishing" if guide else f"guide_{guide_id}_finishing"

    mapped_blocks = []
    for b in remaining:
        entry = {
            "type": b["block_type"],
            "x": b["offset_x"],
            "y": b["offset_y"],
            "z": b["offset_z"],
        }
        bs = b.get("block_state")
        if bs and isinstance(bs, dict) and bs:
            entry["state"] = ",".join(f"{k}={v}" for k, v in bs.items())
        mapped_blocks.append(entry)

    # Normalize negative offsets for Java's 0-based schematic arrays
    if mapped_blocks:
        min_x = min(b["x"] for b in mapped_blocks)
        min_y = min(b["y"] for b in mapped_blocks)
        min_z = min(b["z"] for b in mapped_blocks)
        if min_x < 0 or min_y < 0 or min_z < 0:
            shift_x = -min_x if min_x < 0 else 0
            shift_y = -min_y if min_y < 0 else 0
            shift_z = -min_z if min_z < 0 else 0
            for b in mapped_blocks:
                b["x"] += shift_x
                b["y"] += shift_y
                b["z"] += shift_z
            origin_x -= shift_x
            origin_y -= shift_y
            origin_z -= shift_z

    # Cancel pending idle, suppress auto-idle during build
    client._cancel_idle_timer()
    client._idle_suppressed = True

    try:
        result = client._send_command("build", {
            "guide_id": guide_id,
            "name": build_name,
            "origin": {"x": origin_x, "y": origin_y, "z": origin_z},
            "blocks": mapped_blocks,
            "finishing": True,  # Java side: skip buildIgnoreBlocks setup
        }, timeout=30.0)

        # Check for Java-side error
        if result.get("status") == "error":
            return {
                "status": "error",
                "reason": result.get("error", result.get("reason", "unknown Java error")),
            }

        placed = len(deferred_blocks) - len(remaining)
        return {
            "status": result.get("status", "ok"),
            "placed": placed,
            "total_deferred": len(deferred_blocks),
            "remaining_deferred": len(remaining),
            "pct": round(placed / len(deferred_blocks) * 100, 1) if deferred_blocks else 100.0,
            "message": (
                f"Finishing pass started: {len(remaining)} deferred blocks to place "
                f"(doors, torches, ladders, etc.)."
            ),
        }
    except Exception as exc:
        log.warning("build_finishing failed: %s", exc)
        return {"status": "error", "reason": str(exc)}


# ── Verification ───────────────────────────────────────────────


def build_inspect(client, goal_id: int) -> dict:
    """Diff placed blocks vs schematic. Report gaps (accounting for substitutions).

    Queries actual world state — no cached data. Safe to call any time.

    Returns: {placed, total, pct, structural_status, deferred_status, defects}
    """
    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    guide_id = goal.get("guide_id")
    if not guide_id:
        return {"status": "error", "reason": "Goal has no guide attached"}

    origin_x = goal.get("location_x", 0) or 0
    origin_y = goal.get("location_y", 0) or 0
    origin_z = goal.get("location_z", 0) or 0

    # Get all blocks and classify
    all_blocks = client.build_db.get_guide_blocks(guide_id)
    classified = client.build_db.classify_guide_blocks(guide_id)

    # Filter placed — what remains is still missing
    remaining = client._filter_placed_blocks(
        all_blocks, origin_x, origin_y, origin_z, goal_id
    )

    # Build a set of remaining positions for fast lookup
    remaining_positions = set()
    for b in remaining:
        remaining_positions.add((b["offset_x"], b["offset_y"], b["offset_z"]))

    # Classify remaining into structural vs deferred
    structural_remaining = [
        b for b in remaining
        if client.build_db.classify_block(b["block_type"]) == "structural"
    ]
    deferred_remaining = [
        b for b in remaining
        if client.build_db.classify_block(b["block_type"]) == "deferred"
    ]

    total = len(all_blocks) - len(classified["excluded"])
    placed = total - len(remaining)
    pct = round(placed / total * 100, 1) if total > 0 else 100.0

    structural_total = len(classified["structural"])
    deferred_total = len(classified["deferred"])

    # Build defect list (first 20 for context)
    defects = []
    for b in remaining[:20]:
        defects.append({
            "block_type": b["block_type"],
            "item_needed": _item_for_block(b["block_type"]),
            "pos": {
                "x": origin_x + b["offset_x"],
                "y": origin_y + b["offset_y"],
                "z": origin_z + b["offset_z"],
            },
            "category": client.build_db.classify_block(b["block_type"]),
        })

    return {
        "status": "ok",
        "placed": placed,
        "total": total,
        "pct": pct,
        "structural": {
            "placed": structural_total - len(structural_remaining),
            "total": structural_total,
            "remaining": len(structural_remaining),
        },
        "deferred": {
            "placed": deferred_total - len(deferred_remaining),
            "total": deferred_total,
            "remaining": len(deferred_remaining),
        },
        "defects": defects,
        "defect_count": len(remaining),
        "complete": len(remaining) == 0,
    }


def build_restock(client, goal_id: int, phase_index: int | None = None) -> dict:
    """Withdraw materials from staging chests into inventory for a build phase.

    If phase_index is given, withdraws only that phase's BOM.
    Otherwise withdraws as much as inventory can hold from the full remaining BOM.

    Returns: {loaded, shortfalls, ready}
    """
    if not client.connected:
        return {"status": "error", "reason": "Emmatone not connected"}

    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    guide_id = goal.get("guide_id")
    if not guide_id:
        return {"status": "error", "reason": "Goal has no guide attached"}

    # Determine what materials we need
    if phase_index is not None:
        phases = client.build_db.split_into_phases(guide_id)
        if phase_index >= len(phases):
            return {"status": "error", "reason": f"Phase {phase_index} out of range (max {len(phases)-1})"}
        phase = phases[phase_index]
        # Get ALL blocks (structural + deferred) for this Y-range
        band_blocks = client.build_db.get_blocks_for_y_range(
            guide_id, phase["y_min"], phase["y_max"]
        )
        full_bom: dict[str, int] = {}
        for b in band_blocks:
            bt = b["block_type"]
            full_bom[bt] = full_bom.get(bt, 0) + 1
        phase_bom = _aggregate_bom_as_items(full_bom)
    else:
        # Full remaining BOM
        bom_result = build_bom(client, goal_id)
        if bom_result.get("status") != "ok":
            return bom_result
        phase_bom = bom_result.get("remaining_bom", {})

    if not phase_bom:
        return {"status": "ok", "loaded": {}, "shortfalls": {}, "ready": True}

    # Scan nearby containers
    try:
        containers = client.storage_scan(radius=32)
    except Exception:
        containers = []

    if not containers:
        return {
            "status": "ok",
            "loaded": {},
            "shortfalls": phase_bom,
            "ready": False,
            "message": "No staging chests found nearby. Gather materials first.",
        }

    # Withdraw from closest containers first
    loaded: dict[str, int] = {}
    shortfalls: dict[str, int] = {}

    for container in sorted(containers, key=lambda c: c.get("distance", 999)):
        pos = container.get("pos")
        if not pos:
            continue
        container_items = {i["item"]: i["count"] for i in container.get("items", [])}

        # Build withdrawal request for this container
        withdraw_items: dict[str, int] = {}
        for item, needed in phase_bom.items():
            already = loaded.get(item, 0)
            still_need = needed - already
            if still_need <= 0:
                continue
            available_in_container = container_items.get(item, 0)
            if available_in_container > 0:
                withdraw_items[item] = min(still_need, available_in_container)

        if withdraw_items:
            try:
                pos_tuple = (pos[0], pos[1], pos[2]) if isinstance(pos, list) else pos
                client.storage_withdraw(pos_tuple, withdraw_items)
                for item, count in withdraw_items.items():
                    loaded[item] = loaded.get(item, 0) + count
            except Exception as exc:
                log.warning("Withdrawal from %s failed: %s", pos, exc)

    # Calculate remaining shortfalls
    for item, needed in phase_bom.items():
        got = loaded.get(item, 0)
        if got < needed:
            shortfalls[item] = needed - got

    return {
        "status": "ok",
        "loaded": loaded,
        "shortfalls": shortfalls,
        "ready": len(shortfalls) == 0,
    }


# ── Litematica Printer Build (Phase 49, Track 2) ─────────────


def build_with_printer(client, goal_id: int) -> dict:
    """Full automated build using Litematica Printer.

    Replaces Emmatone's #build with walk-and-print pattern:
    per-phase restock → convert → load litematic → printer on →
    walk sweep → mid-phase restock → printer off → unload → inspect →
    re-sweep if needed.

    Returns: {status, phases_completed, total_placed, total_remaining}
    """
    if not client.connected:
        return {"status": "error", "reason": "Emmatone not connected"}

    goal = client.build_db.get_goal(goal_id)
    if not goal:
        return {"status": "error", "reason": f"Goal {goal_id} not found"}

    guide_id = goal.get("guide_id")
    if not guide_id:
        return {"status": "error", "reason": "Goal has no guide attached"}

    # Verify Litematica is available
    status = client.litematica_status(timeout=5.0)
    if status.get("error") == "litematica_not_installed":
        return {"status": "error", "reason": "Litematica not installed in Minecraft"}

    # Move to current if planned
    if goal["status"] in ("planned", "future"):
        client.build_db.update_goal_status(goal_id, "current")

    client._active_goal_id = goal_id
    client.build_db.init_progress_from_guide(goal_id, guide_id)

    origin_x = goal.get("location_x", 0) or 0
    origin_y = goal.get("location_y", 0) or 0
    origin_z = goal.get("location_z", 0) or 0

    # ── Generate per-phase .litematic files + sweep paths ──
    from gamer.guide_to_litematic import convert_phase_plan

    plan = convert_phase_plan(client, goal_id)
    if plan.get("status") != "ok":
        return plan

    phases = plan.get("phases", [])
    finishing = plan.get("finishing_phase")

    # Cancel pending idle, suppress auto-idle during build
    client._cancel_idle_timer()
    client._idle_suppressed = True
    client._idle_suppressed_at = time.monotonic()

    phases_completed = 0
    total_placed = 0
    errors = []

    try:
        # ── Structural phases (bottom-up) ──
        for phase in phases:
            phase_result = _run_printer_phase(
                client, goal_id, phase, is_finishing=False
            )
            if phase_result.get("status") == "error":
                errors.append(f"Phase {phase['index']}: {phase_result.get('reason')}")
                continue
            phases_completed += 1
            total_placed += phase_result.get("placed_this_phase", 0)

        # ── Finishing phase (deferred blocks) ──
        if finishing and finishing.get("block_count", 0) > 0:
            finish_result = _run_printer_phase(
                client, goal_id, finishing, is_finishing=True
            )
            if finish_result.get("status") == "ok":
                phases_completed += 1
                total_placed += finish_result.get("placed_this_phase", 0)
            elif finish_result.get("status") == "error":
                errors.append(f"Finishing: {finish_result.get('reason')}")
    finally:
        # Ensure printer is off and placement unloaded
        try:
            client.litematica_printer(enabled=False)
        except Exception:
            pass
        try:
            client.litematica_unload()
        except Exception:
            pass
        # Re-enable auto-idle
        client._idle_suppressed = False
        client._schedule_idle(delay=3.0)

    # Final inspection
    final = build_inspect(client, goal_id)
    remaining = final.get("defect_count", 0) if final.get("status") == "ok" else -1

    result = {
        "status": "ok" if not errors else "partial",
        "phases_completed": phases_completed,
        "total_phases": plan.get("total_phases", 0),
        "total_placed": total_placed,
        "total_remaining": remaining,
        "complete": remaining == 0,
    }
    if errors:
        result["errors"] = errors

    if remaining == 0:
        client.build_db.update_goal_status(goal_id, "done")
        log.info("Build %d complete via Printer!", goal_id)
    else:
        log.info("Build %d: %d blocks remaining after Printer pass", goal_id, remaining)

    return result


def _run_printer_phase(
    client,
    goal_id: int,
    phase: dict,
    is_finishing: bool = False,
    max_resweeps: int = 2,
) -> dict:
    """Execute one Printer phase: load → printer on → sweep → inspect.

    Args:
        client: EmmatoneClient
        goal_id: Build goal ID
        phase: Phase dict from convert_phase_plan (file, sweep_waypoints, bom, etc.)
        is_finishing: Whether this is the finishing (deferred) phase
        max_resweeps: Max re-sweep attempts for missed blocks

    Returns: {status, placed_this_phase}
    """
    file = phase["file"]
    origin = phase.get("placement_origin", {})
    waypoints = phase.get("sweep_waypoints", [])
    bom = phase.get("bom", {})
    label = "finishing" if is_finishing else f"phase {phase.get('index', '?')}"

    log.info("Printer %s: %d blocks, %d waypoints", label, phase.get("block_count", 0), len(waypoints))

    # Restock before phase
    if bom:
        restock_result = build_restock(client, goal_id)
        if restock_result.get("shortfalls"):
            log.warning("Restock shortfalls for %s: %s", label, restock_result["shortfalls"])

    for sweep_attempt in range(1 + max_resweeps):
        # Load schematic
        load_result = client.litematica_load(
            file,
            origin.get("x", 0),
            origin.get("y", 0),
            origin.get("z", 0),
        )
        if load_result.get("error"):
            return {"status": "error", "reason": f"Load failed: {load_result.get('error')}"}

        # Enable printer
        printer_result = client.litematica_printer(enabled=True)
        if printer_result.get("error"):
            client.litematica_unload()
            return {"status": "error", "reason": f"Printer failed: {printer_result.get('error')}"}

        # Walk sweep waypoints
        for i, wp in enumerate(waypoints):
            goto_result = client.goto(wp["x"], wp["y"], wp["z"], timeout=30.0)

            # Mid-sweep inventory check (every 10 waypoints)
            if i > 0 and i % 10 == 0 and bom:
                _mid_sweep_restock(client, goal_id, bom)

            # Brief pause at each waypoint for Printer to place nearby blocks
            time.sleep(0.5)

        # Disable printer + unload
        client.litematica_printer(enabled=False)
        client.litematica_unload()

        # Inspect
        inspect = build_inspect(client, goal_id)
        if inspect.get("status") != "ok":
            break

        remaining = inspect.get("defect_count", 0)
        if remaining == 0 or sweep_attempt >= max_resweeps:
            break

        log.info("Printer %s: %d blocks remaining, re-sweeping (%d/%d)",
                 label, remaining, sweep_attempt + 1, max_resweeps)

    # Calculate how many we placed
    placed = phase.get("block_count", 0) - remaining if remaining >= 0 else phase.get("block_count", 0)

    return {
        "status": "ok",
        "placed_this_phase": max(0, placed),
        "remaining": remaining,
    }


def _mid_sweep_restock(client, goal_id: int, bom: dict):
    """Check inventory and restock if running low mid-sweep."""
    try:
        inv_result = client._send_command("status", {}, timeout=5.0)
        inv = inv_result.get("inventory", [])
        if not inv:
            return

        # Count items we have
        have = {}
        for slot in inv:
            item = slot.get("item", "")
            if item and item != "minecraft:air":
                clean = item.replace("minecraft:", "")
                have[clean] = have.get(clean, 0) + slot.get("count", 0)

        # Check if any BOM item is below 10
        need_restock = False
        for item, needed in bom.items():
            if have.get(item, 0) < min(10, needed):
                need_restock = True
                break

        if need_restock:
            log.info("Mid-sweep restock triggered")
            client.litematica_printer(enabled=False)
            build_restock(client, goal_id)
            client.litematica_printer(enabled=True)
    except Exception as exc:
        log.debug("Mid-sweep restock check failed (non-critical): %s", exc)


# ── Bed Placement & Spawn Setting ─────────────────────────────

BED_COLORS = (
    "red_bed", "orange_bed", "yellow_bed", "lime_bed", "green_bed",
    "cyan_bed", "light_blue_bed", "blue_bed", "purple_bed",
    "magenta_bed", "pink_bed", "white_bed", "light_gray_bed",
    "gray_bed", "black_bed", "brown_bed",
)


def _find_bed_in_inventory(inv: dict[str, int]) -> Optional[str]:
    """Find any bed colour variant in inventory.  Returns item name or None."""
    for bed in BED_COLORS:
        if inv.get(bed, 0) > 0:
            return bed
    return None


def _find_bed_spot(client, px: int, py: int, pz: int):
    """Find 2 adjacent solid blocks at the same Y with air above, within 8 blocks.

    Returns (x, y, z) of the first foundation block (bed placed ON TOP via face=up),
    or None if nothing suitable found.
    """
    radius = 8
    hmap = client.get_heightmap(px, pz, radius=radius, step=1)
    heights = hmap.get("heights", [])
    if not heights:
        return None

    candidates = []
    for dz in range(-radius, radius + 1):
        row = dz + radius
        if row < 0 or row >= len(heights):
            continue
        for dx in range(-radius, radius + 1):
            col = dx + radius
            if col < 0 or col >= len(heights[row]):
                continue
            y = heights[row][col]
            # Check cardinal neighbours for a same-height partner
            for adx, adz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                acol = col + adx
                arow = row + adz
                if 0 <= arow < len(heights) and 0 <= acol < len(heights[arow]):
                    if heights[arow][acol] == y:
                        dist = abs(dx) + abs(dz)
                        candidates.append((dist, px + dx, y, pz + dz))
                        break  # one match is enough for this cell

    if not candidates:
        return None
    candidates.sort()
    _, bx, by, bz = candidates[0]
    return (bx, by, bz)


def minecraft_bed(client, action: str = "auto", force: bool = False) -> dict:
    """Find, place, or use a bed.  Picks up beds Emma placed after use.

    Actions:
        auto      – find/place + set spawn + sleep if night + pick up if placed
        set_spawn – set spawn only (works daytime) + pick up if placed
        sleep     – only succeeds at night
        pickup    – break a nearby bed and collect it
    """
    # ── Preflight ────────────────────────────────────────────────
    world = client.get_world_info()
    dimension = world.get("dimension", "")
    is_night = not world.get("is_daytime", True)

    if "overworld" not in dimension and not force:
        return {"status": "error",
                "reason": f"Beds explode in {dimension}!  Use force=true for bed bombing."}

    status = client.get_status()
    pos = status.get("position", {})
    px, py, pz = int(pos.get("x", 0)), int(pos.get("y", 64)), int(pos.get("z", 0))

    # ── Pickup-only mode ─────────────────────────────────────────
    if action == "pickup":
        return _bed_pickup(client, px, py, pz)

    # ── Scan for existing beds ───────────────────────────────────
    placed_new = False
    bed_pos = None

    scan = client.scan_area(px, py, pz, radius=20)
    blocks = scan.get("blocks", [])
    bed_blocks = [b for b in blocks
                  if b.get("block_type", "").replace("minecraft:", "").endswith("_bed")]

    # Prefer head parts so we interact with the right half
    heads = [b for b in bed_blocks if "part=head" in b.get("block_state", "")]
    if not heads:
        heads = bed_blocks  # fallback: take whatever we found

    if heads:
        # Pick closest
        closest = min(heads, key=lambda b: (b["x"] - px) ** 2 + (b["z"] - pz) ** 2)
        bed_pos = (closest["x"], closest["y"], closest["z"])
    else:
        # ── No bed nearby — place one ────────────────────────────
        inv = client.inventory_counts
        bed_item = _find_bed_in_inventory(inv)
        if not bed_item:
            return {"status": "error",
                    "reason": "No bed in inventory and no bed found nearby"}

        spot = _find_bed_spot(client, px, py, pz)
        if not spot:
            return {"status": "error",
                    "reason": "No suitable flat spot found within 8 blocks"}

        sx, sy, sz = spot
        # Navigate close to placement spot
        client.goto(sx, None, sz, timeout=15.0)
        time.sleep(0.3)

        # Equip the bed
        client.set_slot(item=bed_item)
        time.sleep(0.2)

        # Face the placement block and place
        client.look_at(sx, sy + 1, sz)
        time.sleep(0.1)
        client.place_block(sx, sy, sz, face="up")
        time.sleep(0.5)

        bed_pos = (sx, sy + 1, sz)
        placed_new = True

    # ── Interact with the bed ────────────────────────────────────
    bx, by, bz = bed_pos

    if action == "sleep" and not is_night:
        return {"status": "error", "reason": "Cannot sleep during daytime",
                "bed_at": {"x": bx, "y": by, "z": bz}}

    # Navigate + interact
    client.goto(bx, None, bz, timeout=15.0)
    time.sleep(0.3)
    client.interact_block(bx, by, bz)
    time.sleep(0.5)

    result = {
        "status": "ok",
        "action": "placed_new" if placed_new else "used_existing",
        "bed_at": {"x": bx, "y": by, "z": bz},
        "spawn_set": "overworld" in dimension,
        "sleeping": is_night,
    }

    if "overworld" not in dimension:
        result["warning"] = "Bed exploded (force mode)!"
        result["spawn_set"] = False
        return result

    # ── Pick up bed if we placed it ──────────────────────────────
    if placed_new and action in ("auto", "set_spawn"):
        time.sleep(1.0)  # wait for sleep/spawn to register
        pickup = _bed_break_and_collect(client, bx, by, bz)
        result["picked_up"] = pickup.get("collected", False)

    return result


def _bed_pickup(client, px: int, py: int, pz: int) -> dict:
    """Find nearest bed within 20 blocks, break it, and collect the drop."""
    scan = client.scan_area(px, py, pz, radius=20)
    blocks = scan.get("blocks", [])
    bed_blocks = [b for b in blocks
                  if b.get("block_type", "").replace("minecraft:", "").endswith("_bed")]
    if not bed_blocks:
        return {"status": "error", "reason": "No bed found nearby to pick up"}

    closest = min(bed_blocks, key=lambda b: (b["x"] - px) ** 2 + (b["z"] - pz) ** 2)
    bx, by, bz = closest["x"], closest["y"], closest["z"]
    return _bed_break_and_collect(client, bx, by, bz)


def _bed_break_and_collect(client, bx: int, by: int, bz: int) -> dict:
    """Break a bed at (bx, by, bz) and walk over to collect the drop."""
    client.goto(bx, None, bz, timeout=10.0)
    time.sleep(0.3)
    client.break_block(bx, by, bz)
    time.sleep(0.8)
    # Walk onto the drop to collect
    client.goto(bx, None, bz, timeout=5.0)
    time.sleep(0.5)
    return {"status": "ok", "collected": True, "from": {"x": bx, "y": by, "z": bz}}
