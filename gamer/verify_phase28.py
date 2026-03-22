#!/usr/bin/env python3
"""Quick verification script for Phase 28 Build DB."""

from gamer.build_db import BuildDB
from gamer.block_catalog import normalize_block_name

db = BuildDB()
passed = 0
total = 0

def check(label, condition):
    global passed, total
    total += 1
    if condition:
        passed += 1
        print(f"  [PASS] {label}")
    else:
        print(f"  [FAIL] {label}")

# Substitutions
subs = db.get_substitutions("minecraft:oak_planks")
check("oak_planks has substitutes", len(subs) > 0)
print(f"         ({len(subs)} substitutes found)")

best = db.get_best_available_substitute(
    "minecraft:oak_planks", {"minecraft:spruce_planks"})
check("best_available_substitute -> spruce_planks", best == "minecraft:spruce_planks")

# Dependencies
deps = db.resolve_dependencies("minecraft:oak_planks", 64)
check("resolve_dependencies returns tasks", len(deps) > 0)
log_task = [d for d in deps if d["block"] == "minecraft:oak_log"]
check("oak_planks depends on oak_log", len(log_task) > 0)
if log_task:
    check("oak_log qty=16 for 64 planks", log_task[0]["qty"] == 16)

# Resources
methods = db.get_resource_methods("minecraft:oak_planks")
check("oak_planks has resource methods", len(methods) > 0)
check("oak_planks method is craft", methods[0]["method"] == "craft")

# World state
db.update_world_block(0, 64, 0, "minecraft:stone", "discovered")
db.update_world_block(1, 64, 0, "minecraft:diamond_ore", "discovered")
count = db.count_available("minecraft:stone")
check("count_available stone = 1", count == 1)
types = db.get_available_block_types()
check("available types includes stone", "minecraft:stone" in types)
nearby = db.get_blocks_in_radius(0, 64, 0, 5)
check("blocks in radius finds 2", len(nearby) == 2)

# Build goals
goal_id = db.create_goal("Starter House", functional_goal="shelter")
check("create_goal returns id", goal_id is not None and goal_id > 0)
db.update_goal_status(goal_id, "planned")
goals = db.get_active_goals()
check("active goals includes Starter House", any(g["name"] == "Starter House" for g in goals))
found = db.query_goal_info("house")
check("query_goal_info finds house", len(found) > 0)

# Build guides (manual)
guide_id = db.add_guide("Test Hut", description="A tiny test hut",
                        dimensions={"x": 3, "y": 3, "z": 3}, block_count=0,
                        tags=["test", "small"])
blocks = [
    {"block_type": "minecraft:oak_planks", "block_state": {}, "offset_x": x, "offset_y": 0, "offset_z": z, "placement_order": x + z * 3}
    for x in range(3) for z in range(3)
]
db.add_guide_blocks(guide_id, blocks)
guide = db.get_guide(guide_id)
check("guide has correct name", guide["name"] == "Test Hut")
check("guide block_count = 9", guide["block_count"] == 9)
bom = db.get_guide_bill_of_materials(guide_id)
check("bill of materials oak_planks = 9", bom.get("minecraft:oak_planks") == 9)

# Progress tracking
db.init_progress_from_guide(goal_id, guide_id)
progress = db.get_progress(goal_id)
check("progress initialized", len(progress) > 0)
db.update_progress(goal_id, "minecraft:oak_planks", 5)
summary = db.get_progress_summary(goal_id)
check("progress summary correct", summary["placed_blocks"] == 5 and summary["total_blocks"] == 9)
check("progress percent ~55.6%", abs(summary["percent"] - 55.6) < 1)

# Legacy block name normalization
check("normalize '4' -> cobblestone", normalize_block_name("4") == "minecraft:cobblestone")
check("normalize 'stone' -> minecraft:stone", normalize_block_name("stone") == "minecraft:stone")
check("normalize 'grass' -> short_grass", normalize_block_name("grass") == "minecraft:short_grass")
check("normalize 'stonebrick' -> stone_bricks", normalize_block_name("minecraft:stonebrick") == "minecraft:stone_bricks")
check("normalize 'grass_path' -> dirt_path", normalize_block_name("minecraft:grass_path") == "minecraft:dirt_path")

# Search guides
results = db.search_guides("Hut")
check("search_guides finds Test Hut", any(g["name"] == "Test Hut" for g in results))

# Duplicate check
check("guide_exists detects duplicate", db.guide_exists("custom", {"x": 3, "y": 3, "z": 3}))

print(f"\n=== Results: {passed} passed, {total - passed} failed ===")
