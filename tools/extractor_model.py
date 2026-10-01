#!/usr/bin/env python3
"""Stage 2: Parse emma_extracted_recipes.json into a structured model per itemId.

Picks a canonical obtain method per item using a priority ordering that mirrors
the handwritten TaskCatalogue.java's semantic conventions.
"""

from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Methods that are never chosen as canonical — they are metadata or shortcuts,
# not primary collection paths.
NEVER_CANONICAL: frozenset[str] = frozenset({
    "STONECUTTER",
    "BREW_POTION",
    "BREW_CONTAINER",
    "BREW_CONTAINERS",
    "ENCHANTMENT",
    "BANNER_PATTERN",
    "ITEM_PROPERTIES",
    "SMITH_TRIM",
})

SMELT_METHODS: frozenset[str] = frozenset({
    "SMELT_FURNACE", "SMELT_BLAST", "SMELT_SMOKER", "SMELT_CAMPFIRE", "SMELT",
})

# Inputs that are acceptable as a smelting source (raw materials, cooked outputs, blocks).
# The key rule: reject smelts whose ONLY inputs are raw ore blocks (_ore suffix),
# because the catalogue convention is to smelt raw_* items, not ore blocks.
_RAW_SMELT_INPUT_SUFFIXES: tuple[str, ...] = (
    "raw_iron", "raw_gold", "raw_copper",
    "sand", "red_sand", "clay_ball", "kelp", "cactus",
    "cobblestone", "cobbled_deepslate",
    "stone", "basalt", "quartz_block",
    "sandstone", "red_sandstone",
    "ancient_debris", "netherrack",
    "stone_bricks", "nether_bricks",
    "polished_blackstone_bricks", "deepslate_bricks", "deepslate_tiles",
    "potato", "porkchop", "beef", "chicken", "mutton", "rabbit", "salmon", "cod",
)
_RAW_SMELT_INPUTS: frozenset[str] = frozenset(_RAW_SMELT_INPUT_SUFFIXES)
# Accept any log variant as a smelt input (for charcoal)
_LOG_SUFFIXES = ("_log", "_stem", "_block")  # bamboo_block, crimson_stem, etc.


_TOOL_SUFFIXES = (
    "_pickaxe", "_shovel", "_sword", "_axe", "_hoe",
    "_helmet", "_chestplate", "_leggings", "_boots",
    "_spear", "_mace", "_armor",
)


def _is_valid_smelt_input(raw_inp: str) -> bool:
    """Return True if `raw_inp` (without namespace) is an acceptable smelt source."""
    if raw_inp in _RAW_SMELT_INPUTS:
        return True
    if raw_inp.endswith("_log") or raw_inp.endswith("_stem"):
        return True  # any log/stem can be smelted to charcoal
    if raw_inp == "bamboo_block":
        return True
    # Reject pure ore blocks (deepslate_iron_ore, iron_ore, etc.)
    if raw_inp.endswith("_ore"):
        return False
    # Reject tool/armour items as smelt inputs (recovering nuggets from tools is
    # not a primary collection path the catalogue should model)
    if any(raw_inp.endswith(suffix) for suffix in _TOOL_SUFFIXES):
        return False
    # Accept everything else (cooked meats as re-smelt, etc.)
    return True


def _all_mine_are_incidental(item_id: str, entries: list[dict]) -> bool:
    """Return True if every MINE entry is 'drop-self with no tool tier requirement'.

    A MINE entry is incidental when:
    - mineBlock == itemId  (the item just drops itself when broken)
    - no 'requirement' field (no pickaxe/axe tier required, i.e. the item was placed
      by a player and is being picked up again)

    Primary mineable blocks (andesite, granite, diorite, …) always have a
    'requirement' field (HAND, WOOD, STONE, IRON, DIAMOND) even when mineBlock == itemId.
    Crafted-then-placed blocks (copper_door, black_banner, shelves, …) have no
    requirement field.
    """
    mine_entries = [e for e in entries if e["obtainMethod"] == "MINE"]
    if not mine_entries:
        return False
    for me in mine_entries:
        for mb in (me.get("mineBlocks") or []):
            if mb.get("block") != item_id:
                return False  # mining a DIFFERENT block = real mine
            if mb.get("requirement"):
                return False  # has tool tier requirement = real primary mine
    return True


def _has_craft_recipe(entries: list[dict]) -> bool:
    return any(
        e["obtainMethod"] in ("CRAFT_SHAPED_3x3", "CRAFT_SHAPED_2x2", "CRAFT_SHAPELESS")
        for e in entries
    )


# ---------------------------------------------------------------------------
# Canonical method picker
# ---------------------------------------------------------------------------

def _pick_canonical(item_id: str, entries: list[dict]) -> str | None:
    methods = {e["obtainMethod"] for e in entries}
    effective = methods - NEVER_CANONICAL

    if "MINE" in effective:
        # Prefer MINE only when mining a DIFFERENT block to obtain this item
        # (e.g. stone → cobblestone, coal_ore → coal).  If every mine entry is
        # "drop-self" (mined block == item_id) AND a craft recipe exists, fall
        # through to CRAFT — the item is primarily crafted, not mined.
        if not (_all_mine_are_incidental(item_id, entries) and _has_craft_recipe(entries)):
            return "MINE"
        # Drop-self with craft: fall through to prefer CRAFT

    if "MOB_DROP" in effective:
        return "MOB_DROP"

    if "SMITH" in effective:
        return "SMITH"

    # Smelt — only if at least one input is a valid raw material
    for e in entries:
        if e["obtainMethod"] in SMELT_METHODS:
            smelt_from = e.get("smeltFrom") or []
            clean_inputs = [
                s.replace("minecraft:", "") for s in smelt_from
            ]
            if clean_inputs and any(_is_valid_smelt_input(inp) for inp in clean_inputs):
                return e["obtainMethod"]

    for method in ("CRAFT_SHAPED_3x3", "CRAFT_SHAPED_2x2", "CRAFT_SHAPELESS"):
        if method in effective:
            return method

    return None


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def build_extractor_model(json_path: str | Path) -> dict:
    """Load the extractor JSON and return a dict keyed by itemId."""
    data = json.loads(Path(json_path).read_text(encoding="utf-8"))
    entries: list[dict] = data.get("entries", [])

    by_item: dict[str, list[dict]] = defaultdict(list)
    for e in entries:
        if "itemId" in e:
            by_item[e["itemId"]].append(e)

    result: dict[str, dict] = {}
    for item_id, item_entries in by_item.items():
        canonical = _pick_canonical(item_id, item_entries)
        methods = [e["obtainMethod"] for e in item_entries]

        # Collect craft grid ingredients for the canonical shaped/shapeless entry
        craft_ingredients: list[str] = []
        for e in item_entries:
            if e["obtainMethod"] == canonical and canonical in (
                "CRAFT_SHAPED_3x3", "CRAFT_SHAPED_2x2", "CRAFT_SHAPELESS"
            ):
                grid = e.get("craftGrid") or []
                for slot in grid:
                    if slot:
                        for item in slot:
                            clean = item.replace("minecraft:", "")
                            if clean not in craft_ingredients:
                                craft_ingredients.append(clean)

        # Collect smelt input for canonical smelt entry
        smelt_inputs: list[str] = []
        for e in item_entries:
            if e["obtainMethod"] == canonical and canonical in SMELT_METHODS:
                smelt_from = e.get("smeltFrom") or []
                smelt_inputs = [s.replace("minecraft:", "") for s in smelt_from]

        # Collect smith base/material
        smith_base: list[str] = []
        smith_material: list[str] = []
        for e in item_entries:
            if e["obtainMethod"] == "SMITH":
                smith_base = [s.replace("minecraft:", "") for s in (e.get("smithBase") or [])]
                smith_material = [s.replace("minecraft:", "") for s in (e.get("smithMaterial") or [])]

        # Group field (useful for wood-family detection)
        group = next(
            (e.get("group", "") for e in item_entries if e.get("group")), ""
        )

        result[item_id] = {
            "obtainMethods": methods,
            "canonicalMethod": canonical,
            "group": group,
            "isMineable": "MINE" in methods,
            "isMobDrop": "MOB_DROP" in methods,
            "hasShapedRecipe": any(m in ("CRAFT_SHAPED_3x3", "CRAFT_SHAPED_2x2") for m in methods),
            "hasShapelessRecipe": "CRAFT_SHAPELESS" in methods,
            "craftIngredients": craft_ingredients,
            "smeltInputs": smelt_inputs,
            "smithBase": smith_base,
            "smithMaterial": smith_material,
        }

    return result


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Build an extractor model from emma_extracted_recipes.json")
    parser.add_argument("json", help="Path to emma_extracted_recipes.json")
    parser.add_argument("--out", help="Optional JSON output path")
    args = parser.parse_args()
    model = build_extractor_model(args.json)
    output = json.dumps(model, indent=2)
    if args.out:
        Path(args.out).write_text(output, encoding="utf-8")
        print(f"Wrote {args.out}")
    total = len(model)
    no_canonical = sum(1 for v in model.values() if not v["canonicalMethod"])
    print(f"Total items: {total}, no canonical method: {no_canonical}", file=sys.stderr)
