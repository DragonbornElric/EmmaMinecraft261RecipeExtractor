#!/usr/bin/env python3
"""Stage 1: Parse a TaskCatalogue.java into a structured model for reconciliation.

Reuses parsing primitives from compare_task_catalogues.py without modifying them.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Iterator

# ---------------------------------------------------------------------------
# Borrow primitives from the existing comparison script
# ---------------------------------------------------------------------------
_TOOLS_DIR = Path(__file__).parent
sys.path.insert(0, str(_TOOLS_DIR))
from compare_task_catalogues import (
    extract_static_block,
    extract_string_args,
    strip_comments,
)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Wood type prefixes present in the catalogue's WoodItems enum as of MC ~1.20.4.
# pale_oak is NOT included because it was added later (26.1.2) — that omission
# is exactly the gap the validator should surface.
OLD_WOOD_PREFIXES = [
    "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
    "mangrove", "cherry", "bamboo", "crimson", "warped",
]

DYE_COLORS = [
    "white", "orange", "magenta", "light_blue", "yellow", "lime",
    "pink", "gray", "light_gray", "cyan", "purple", "blue",
    "brown", "green", "red", "black",
]

# All helpers that take a catalogue name as their first string argument.
NAME_HELPERS = {
    "mine", "simple", "smelt", "smith", "mob", "crop", "alias",
    "shapedRecipe2x2", "shapedRecipe3x3",
    "shapedRecipe2x2Block", "shapedRecipe3x3Block",
    "shapedRecipeSlab", "shapedRecipeStairs", "shapedRecipeWall",
    "shear",
}

# Collect*Task class patterns (to identify specialized collectors in simple() calls)
_COLLECTOR_PAT = re.compile(
    r"\b(Collect\w+Task(?:\.\w+)?|Kill\w+Task|CarveThenCollectTask"
    r"|MineAndCollectTask|ShearAndCollectBlockTask|GetSmithingTemplateTask"
    r"|CollectBucketLiquidTask\.\w+)\b"
)

# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _helper_calls_with_end(code: str, helper_name: str) -> Iterator[tuple[str, int]]:
    """Yield (args_content, end_pos) for every invocation of helper_name."""
    pattern = re.compile(rf"\b{re.escape(helper_name)}\s*\(")
    for match in pattern.finditer(code):
        start = match.end() - 1  # position of opening (
        depth = 0
        in_string = False
        escape = False
        for idx in range(start, len(code)):
            ch = code[idx]
            if in_string:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == '"':
                    in_string = False
                continue
            if ch == '"':
                in_string = True
                continue
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    yield code[start + 1 : idx], idx
                    break


def _extract_modifiers(code: str, from_pos: int) -> list[str]:
    """Scan from from_pos to the next ; and extract modifier method calls."""
    end = code.find(";", from_pos)
    if end == -1:
        return []
    chunk = code[from_pos:end]
    modifiers: list[str] = []
    if ".dontMineIfPresent()" in chunk:
        modifiers.append("dontMineIfPresent")
    if ".craftOnly()" in chunk:
        modifiers.append("craftOnly")
    if ".anyDimension()" in chunk:
        modifiers.append("anyDimension")
    if ".mineIfPresent()" in chunk:
        modifiers.append("mineIfPresent")
    m = re.search(r"\.forceDimension\s*\(\s*Dimension\.(\w+)\s*\)", chunk)
    if m:
        modifiers.append(f"forceDimension:{m.group(1)}")
    return modifiers


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def build_catalogue_model(java_path: str | Path) -> dict:
    """Parse TaskCatalogue.java and return a structured model dict."""
    text = Path(java_path).read_text(encoding="utf-8")
    uncommented = strip_comments(text)
    static_block = extract_static_block(uncommented)

    explicit_entries: list[dict] = []
    special_collectors: dict[str, str] = {}

    for helper in NAME_HELPERS:
        for args, end_pos in _helper_calls_with_end(static_block, helper):
            string_args = extract_string_args(args)
            if not string_args:
                continue
            name = string_args[0]
            modifiers = _extract_modifiers(static_block, end_pos + 1)

            collector_class: str | None = None
            if helper == "simple":
                m = _COLLECTOR_PAT.search(args)
                if m:
                    collector_class = m.group(1)
                    special_collectors[name] = collector_class

            explicit_entries.append({
                "name": name,
                "helper": helper,
                "stringArgs": string_args,
                "modifiers": modifiers,
                "collectorClass": collector_class,
            })

    # mobCook: registers both uncooked and cooked names
    mobcook_entries: list[dict] = []
    for args, end_pos in _helper_calls_with_end(static_block, "mobCook"):
        string_args = extract_string_args(args)
        if not string_args:
            continue
        uncooked = string_args[0]
        # 3rd arg (index 2 in string_args) would be the cooked item name, but it's
        # Items.COOKED_PORKCHOP (not a string literal), so we synthesise it.
        cooked = f"cooked_{uncooked}"
        modifiers = _extract_modifiers(static_block, end_pos + 1)
        mobcook_entries.append({
            "name": uncooked, "helper": "mobCook",
            "stringArgs": string_args, "modifiers": modifiers, "collectorClass": None,
        })
        mobcook_entries.append({
            "name": cooked, "helper": "mobCookCooked",
            "stringArgs": string_args, "modifiers": modifiers, "collectorClass": None,
        })

    # tools expansion
    tools_entries: list[dict] = []
    for args, _ in _helper_calls_with_end(static_block, "tools"):
        string_args = extract_string_args(args)
        if not string_args:
            continue
        material = string_args[0]
        for suffix in ("pickaxe", "shovel", "sword", "axe", "hoe"):
            tools_entries.append({
                "name": f"{material}_{suffix}", "source": "tools", "parent": material,
            })

    # armor expansion
    armor_entries: list[dict] = []
    for args, _ in _helper_calls_with_end(static_block, "armor"):
        string_args = extract_string_args(args)
        if not string_args:
            continue
        material = string_args[0]
        for suffix in ("helmet", "chestplate", "leggings", "boots"):
            armor_entries.append({
                "name": f"{material}_{suffix}", "source": "armor", "parent": material,
            })

    # smith entries
    smith_entries: list[dict] = []
    for args, _ in _helper_calls_with_end(static_block, "smith"):
        string_args = extract_string_args(args)
        if string_args:
            smith_entries.append({"name": string_args[0], "helper": "smith", "stringArgs": string_args})

    # woodTasks suffixes
    woodtasks_suffixes: set[str] = set()
    for args, _ in _helper_calls_with_end(static_block, "woodTasks"):
        sa = extract_string_args(args)
        if sa:
            woodtasks_suffixes.add(sa[0])

    # colorfulTasks suffixes
    colortasks_suffixes: set[str] = set()
    for args, _ in _helper_calls_with_end(static_block, "colorfulTasks"):
        sa = extract_string_args(args)
        if sa:
            colortasks_suffixes.add(sa[0])

    # Expand wood-derived names (using OLD_WOOD_PREFIXES — pale_oak intentionally absent)
    wood_derived: set[str] = set()
    for suffix in woodtasks_suffixes:
        for prefix in OLD_WOOD_PREFIXES:
            wood_derived.add(f"{prefix}_{suffix}")
    # Special-case: woodTasks("stripped_logs") generates "oak_stripped_logs" as the key,
    # but the MC item id is "stripped_oak_log". Track the reverse mapping separately so the
    # validator knows "stripped_oak_log" is covered (even though the catalogue key differs).
    stripped_log_json_names: set[str] = set()
    if "stripped_logs" in woodtasks_suffixes:
        for prefix in OLD_WOOD_PREFIXES:
            stripped_log_json_names.add(f"stripped_{prefix}_log")
    # Similarly, woodTasks("log") → "oak_log" covers the JSON "oak_log" (matches fine).
    # woodTasks("boat") → "oak_boat" covers "oak_boat" in JSON (bamboo_raft is different but
    # that's legitimately new for the catalogue to handle).

    # Expand color-derived names
    color_derived: set[str] = set()
    for suffix in colortasks_suffixes:
        for color in DYE_COLORS:
            color_derived.add(f"{color}_{suffix}")

    # Collect aliases
    aliases: dict[str, str] = {}
    for entry in explicit_entries:
        if entry["helper"] == "alias" and len(entry["stringArgs"]) >= 2:
            aliases[entry["stringArgs"][0]] = entry["stringArgs"][1]

    # Build allKnownNames: everything the catalogue would register at runtime
    all_known: set[str] = set()
    for e in explicit_entries:
        all_known.add(e["name"])
    for new_name in aliases:
        all_known.add(new_name)
    for e in mobcook_entries:
        all_known.add(e["name"])
    for e in tools_entries:
        all_known.add(e["name"])
    for e in armor_entries:
        all_known.add(e["name"])
    for e in smith_entries:
        all_known.add(e["name"])
    all_known.update(wood_derived)
    all_known.update(color_derived)

    # Additional coverage set: JSON itemIds that are structurally covered by the catalogue
    # under a different key name (stripped_oak_log → oak_stripped_logs, etc.)
    structurally_covered_json_ids: set[str] = set(stripped_log_json_names)

    return {
        "explicitEntries": explicit_entries,
        "mobcookEntries": mobcook_entries,
        "toolsEntries": tools_entries,
        "armorEntries": armor_entries,
        "smithEntries": smith_entries,
        "aliases": aliases,
        "woodTasksSuffixes": sorted(woodtasks_suffixes),
        "colorTasksSuffixes": sorted(colortasks_suffixes),
        "derivedWoodNames": sorted(wood_derived),
        "derivedColorNames": sorted(color_derived),
        "specialCollectorTable": special_collectors,
        "allKnownNames": sorted(all_known),
        "structurallyCoveredJsonIds": sorted(structurally_covered_json_ids),
    }


if __name__ == "__main__":
    import argparse, json
    parser = argparse.ArgumentParser(description="Build a structured model from TaskCatalogue.java")
    parser.add_argument("java", help="Path to TaskCatalogue.java")
    parser.add_argument("--out", help="Optional JSON output path")
    args = parser.parse_args()
    model = build_catalogue_model(args.java)
    output = json.dumps(model, indent=2)
    if args.out:
        Path(args.out).write_text(output, encoding="utf-8")
        print(f"Wrote {args.out}")
    else:
        print(output[:4000], "..." if len(output) > 4000 else "")
    print(f"\nTotal allKnownNames: {len(model['allKnownNames'])}", file=sys.stderr)
