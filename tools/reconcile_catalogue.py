#!/usr/bin/env python3
"""Stages 3-5: Reconcile the handwritten TaskCatalogue.java against MC 26.1.2 recipes.

Outputs:
  generated/stage1-catalogue-model.json    — parsed catalogue model
  generated/stage2-mc26-model.json         — parsed extractor model
  generated/validation-report.json         — reconciliation report
  generated/catalogue-additions.java       — paste-ready new entries (missingNew only)
  generated/catalogue-review.md            — stale/obsolete/unsafe entries for human review
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

_TOOLS_DIR = Path(__file__).parent
sys.path.insert(0, str(_TOOLS_DIR))
from catalogue_model import (
    DYE_COLORS,
    OLD_WOOD_PREFIXES,
    build_catalogue_model,
)
from extractor_model import NEVER_CANONICAL, build_extractor_model

# ---------------------------------------------------------------------------
# Wood/colour coverage helpers
# ---------------------------------------------------------------------------

# JSON group field → expected woodTasks suffix in the catalogue
_GROUP_TO_WOOD_SUFFIX: dict[str, str] = {
    "planks": "planks",
    "wooden_stairs": "stairs",
    "wooden_slab": "slab",
    "wooden_door": "door",
    "wooden_trapdoor": "trapdoor",
    "wooden_fence": "fence",
    "wooden_fence_gate": "fence_gate",
    "wooden_button": "button",
    "wooden_pressure_plate": "pressure_plate",
    "wooden_sign": "sign",
    "hanging_sign": "hanging_sign",
    "boat": "boat",
    "chest_boat": "boat",  # chest boats are a separate woodTasks? Currently not in catalogue
    "shelf": "shelf",      # new in 26.1.2 — catalogue won't have this
}

# JSON group field → expected colorfulTasks suffix
_GROUP_TO_COLOR_SUFFIX: dict[str, str] = {
    "wool": "wool",
    "bed": "bed",
    "banner": "banner",
    "carpet": "carpet",
    "shulker_box": "shulker_box",
    "stained_glass": "stained_glass",
    "stained_glass_pane": "stained_glass_pane",
    "concrete": "concrete",
    "concrete_powder": "concrete_powder",
    "terracotta": "terracotta",
    "glazed_terracotta": "glazed_terracotta",
    "candle": "candle",
}

# Canonical method equivalence to catalogue helper kind
_METHOD_TO_HELPER: dict[str, str] = {
    "MINE": "mine",
    "MOB_DROP": "mob",
    "SMITH": "smith",
    "SMELT_FURNACE": "smelt",
    "SMELT_BLAST": "smelt",
    "SMELT_SMOKER": "smelt",
    "SMELT_CAMPFIRE": "smelt",
    "SMELT": "smelt",
    "CRAFT_SHAPED_3x3": "shaped3x3",
    "CRAFT_SHAPED_2x2": "shaped2x2",
    "CRAFT_SHAPELESS": "shapeless",
}

_SHAPED_HELPERS = frozenset({
    "shapedRecipe2x2", "shapedRecipe3x3",
    "shapedRecipe2x2Block", "shapedRecipe3x3Block",
    "shapedRecipeSlab", "shapedRecipeStairs", "shapedRecipeWall",
})
_MINE_LIKE_HELPERS = frozenset({"mine", "shear", "crop"})

# Items where the catalogue deliberately uses a specialized collector —
# they must never receive a `missingNew` proposal even if the JSON has a craft path.
# This set is derived at runtime from catalogue_model's specialCollectorTable.


def _is_covered_by_wood_macro(item_id: str, group: str, cat_model: dict) -> bool:
    """Return True if the item is covered by an existing woodTasks expansion."""
    wood_suffixes = set(cat_model["woodTasksSuffixes"])

    # Check via group field — also verify the item's prefix is a known old wood type
    # so that pale_oak_planks (group="planks") is NOT marked as covered here.
    if group in _GROUP_TO_WOOD_SUFFIX:
        required_suffix = _GROUP_TO_WOOD_SUFFIX[group]
        if required_suffix in wood_suffixes:
            for prefix in OLD_WOOD_PREFIXES:
                if item_id.startswith(prefix + "_"):
                    return True
            return False  # group matches but prefix is a new wood type (e.g. pale_oak)

    # Check via name pattern: {known_old_prefix}_{suffix}
    for prefix in OLD_WOOD_PREFIXES:
        if item_id.startswith(prefix + "_"):
            suffix = item_id[len(prefix) + 1:]
            if suffix in wood_suffixes:
                return True

    return False


def _is_covered_by_color_macro(item_id: str, group: str, cat_model: dict) -> bool:
    """Return True if the item is covered by an existing colorfulTasks expansion."""
    color_suffixes = set(cat_model["colorTasksSuffixes"])

    if group in _GROUP_TO_COLOR_SUFFIX:
        required_suffix = _GROUP_TO_COLOR_SUFFIX[group]
        if required_suffix in color_suffixes:
            return True

    for color in DYE_COLORS:
        if item_id.startswith(color + "_"):
            suffix = item_id[len(color) + 1:]
            if suffix in color_suffixes:
                return True

    return False


def _is_macro_abstract_parent(name: str, cat_model: dict) -> bool:
    """Return True if `name` is an abstract catalogue key that intentionally has no direct
    MC item-ID counterpart — i.e. it drives a woodTasks/colorfulTasks expansion.

    Examples: "planks" (all {prefix}_planks), "wooden_door" (all {prefix}_door),
              "wool" (all {color}_wool), "bed" (all {color}_bed).
    """
    wood_suffixes = set(cat_model["woodTasksSuffixes"])
    color_suffixes = set(cat_model["colorTasksSuffixes"])

    if name in wood_suffixes or name in color_suffixes:
        return True
    # "wooden_door" → suffix "door" in woodTasksSuffixes
    if name.startswith("wooden_") and name[len("wooden_"):] in wood_suffixes:
        return True
    return False


def _catalogue_helper_for(entry: dict) -> str:
    """Return the broad helper category for a catalogue explicit entry."""
    h = entry["helper"]
    if h in _SHAPED_HELPERS:
        return "shaped"
    if h in _MINE_LIKE_HELPERS:
        return "mine"
    return h  # smelt, smith, mob, simple, alias, ...


def _ingredients_match(cat_entry: dict, mc_info: dict) -> bool:
    """Loosely check if catalogue ingredients overlap with JSON craft/smelt ingredients.

    Many catalogue recipes use Java variables (p="planks", s="stick", etc.) rather than
    inline string literals, so extract_string_args only captures literal args.  When no
    literal ingredient args are present we cannot compare, so we return True (assume fine).
    """
    canonical = mc_info["canonicalMethod"]
    if canonical in ("CRAFT_SHAPED_3x3", "CRAFT_SHAPED_2x2", "CRAFT_SHAPELESS"):
        json_ingredients = set(mc_info["craftIngredients"])
        cat_args = set(cat_entry["stringArgs"][1:])  # first arg is the item name
        cat_args.discard("null")
        cat_args.discard("")
        if not cat_args:
            return True  # No literal ingredients captured — variable references; assume fine
        # Direct overlap
        if cat_args & json_ingredients:
            return True
        # Abstract key match: catalogue uses multi-item keys like "wool", "planks", "log".
        # "planks" covers "oak_planks" (prefix: "planks_" → no, but "oak_planks" ends "_planks")
        # "wool"   covers "white_wool"  (suffix: "_wool")
        # "log"    covers "oak_log"     (suffix: "_log")
        for cat_arg in cat_args:
            suffix = "_" + cat_arg
            if any(j.endswith(suffix) for j in json_ingredients):
                return True
            # Also prefix-match: e.g. cat_arg "planks" covers "planks" itself or "planks_..."
            prefix = cat_arg + "_"
            if any(j.startswith(prefix) for j in json_ingredients):
                return True
        return False
    if canonical and canonical.startswith("SMELT"):
        json_inputs = set(mc_info["smeltInputs"])
        cat_args = set(cat_entry["stringArgs"][1:])
        cat_args.discard("null")
        if not cat_args:
            return True
        return bool(cat_args & json_inputs)
    return True  # No ingredient comparison needed for mine/mob/smith


# ---------------------------------------------------------------------------
# Core reconciliation
# ---------------------------------------------------------------------------

def reconcile(cat_model: dict, mc_model: dict) -> dict:
    all_known: set[str] = set(cat_model["allKnownNames"])
    structurally_covered: set[str] = set(cat_model["structurallyCoveredJsonIds"])
    special_collectors: set[str] = set(cat_model["specialCollectorTable"].keys())
    aliases: dict[str, str] = cat_model["aliases"]

    # Build a quick lookup: catalogue name → entry (for stale comparison)
    cat_by_name: dict[str, dict] = {}
    for e in cat_model["explicitEntries"]:
        cat_by_name.setdefault(e["name"], e)

    # Derived names (not in explicitEntries but in allKnownNames due to macros)
    derived_names: set[str] = (
        set(cat_model["derivedWoodNames"])
        | set(cat_model["derivedColorNames"])
        | {e["name"] for e in cat_model["toolsEntries"]}
        | {e["name"] for e in cat_model["armorEntries"]}
        | {e["name"] for e in cat_model["mobcookEntries"]}
    )

    report: dict = {
        "cataloguedCorrectly": [],
        "cataloguedSpecialCollector": [],
        "cataloguedDerived": [],
        "missingNew": [],
        "staleRecipe": [],
        "obsoleteInCatalogue": [],
        "macroAbstractParent": [],   # abstract keys that drive woodTasks/colorfulTasks — valid by design
        "abstractFamilyKey": [],     # other abstract keys with no direct MC item-ID (extractor gap?)
        "unhandledMethod": [],
        "structurallyCovered": [],
        "uncovered": [],
    }

    mc_item_ids: set[str] = set(mc_model.keys())

    # ---- Pass 1: classify every MC 26.1.2 item --------------------------------
    for item_id, mc_info in mc_model.items():
        canonical = mc_info["canonicalMethod"]
        group = mc_info.get("group", "")

        # In specialCollectorTable → always correct by design (check before unhandledMethod
        # so items like water_bucket that have no canonical JSON method are still recognised)
        if item_id in special_collectors:
            report["cataloguedSpecialCollector"].append(item_id)
            continue

        # Ignore metadata-only items
        if canonical is None and all(m in NEVER_CANONICAL for m in mc_info["obtainMethods"]):
            report["unhandledMethod"].append({
                "itemId": item_id,
                "methods": mc_info["obtainMethods"],
            })
            continue

        # Structurally covered (e.g., stripped_oak_log → oak_stripped_logs)
        if item_id in structurally_covered:
            report["structurallyCovered"].append(item_id)
            continue

        # Exact name match in allKnownNames
        if item_id in all_known:
            if item_id in derived_names or item_id in aliases:
                report["cataloguedDerived"].append(item_id)
            elif item_id in cat_by_name:
                cat_entry = cat_by_name[item_id]
                if not _ingredients_match(cat_entry, mc_info):
                    report["staleRecipe"].append({
                        "itemId": item_id,
                        "catalogueHelper": cat_entry["helper"],
                        "catalogueArgs": cat_entry["stringArgs"],
                        "catalogueLine": cat_entry.get("lineNumber"),
                        "jsonCanonical": canonical,
                        "jsonIngredients": (
                            mc_info["craftIngredients"]
                            or mc_info["smeltInputs"]
                        ),
                    })
                else:
                    report["cataloguedCorrectly"].append(item_id)
            else:
                report["cataloguedCorrectly"].append(item_id)
            continue

        # Covered by woodTasks macro (old prefixes — palette doesn't include pale_oak yet)
        if _is_covered_by_wood_macro(item_id, group, cat_model):
            report["cataloguedDerived"].append(item_id)
            continue

        # Covered by colorfulTasks macro
        if _is_covered_by_color_macro(item_id, group, cat_model):
            report["cataloguedDerived"].append(item_id)
            continue

        # Not covered — classify as missing or unhandled
        if canonical is None:
            report["uncovered"].append({
                "itemId": item_id,
                "methods": mc_info["obtainMethods"],
            })
            continue

        if canonical in NEVER_CANONICAL:
            report["unhandledMethod"].append({
                "itemId": item_id,
                "methods": mc_info["obtainMethods"],
            })
            continue

        # Check for bogus proposals: items that are best obtained via a Collect*Task
        # but we can't auto-emit those (the human must write one).
        # Items with MOB_DROP or MINE canonical that have no craft backup are
        # safe to add as mine/mob entries.
        report["missingNew"].append({
            "itemId": item_id,
            "canonicalMethod": canonical,
            "group": group,
            "craftIngredients": mc_info["craftIngredients"],
            "smeltInputs": mc_info["smeltInputs"],
            "smithBase": mc_info["smithBase"],
            "smithMaterial": mc_info["smithMaterial"],
            "isMineable": mc_info["isMineable"],
            "isMobDrop": mc_info["isMobDrop"],
        })

    # ---- Pass 2: find catalogue entries with no direct MC JSON counterpart --------
    for name in sorted(all_known):
        if name in aliases:
            continue  # alias targets are fine to be absent as their own itemId
        if name not in mc_item_ids:
            if name not in cat_by_name:
                continue  # derived macro name (e.g. oak_planks) — already handled in Pass 1
            entry = cat_by_name[name]
            payload = {
                "catalogueName": name,
                "helper": entry["helper"],
                "stringArgs": entry["stringArgs"],
            }
            if _is_macro_abstract_parent(name, cat_model):
                # woodTasks/colorfulTasks driver — intentionally abstract, valid by design
                report["macroAbstractParent"].append(payload)
            else:
                # Abstract family key (mushroom, sapling, etc.) or genuine rename/removal.
                # Not auto-removable; needs human verification.
                report["abstractFamilyKey"].append(payload)

    return report


# ---------------------------------------------------------------------------
# Emission helpers
# ---------------------------------------------------------------------------

_SECTION_HINTS: list[tuple[str, list[str]]] = [
    ("/// RAW RESOURCES", [
        "log", "dirt", "andesite", "granite", "diorite", "calcite", "tuff",
        "netherrack", "blackstone", "basalt", "soul_sand", "coal", "raw_",
        "diamond", "emerald", "redstone", "lapis", "amethyst", "sand", "gravel",
        "clay", "ancient_debris", "bamboo", "sapling", "mushroom", "vine",
        "kelp", "flower", "breeze_rod", "dried_ghast", "firefly_bush", "leaf_litter",
    ]),
    ("// MATERIALS", [
        "planks", "stick", "stone", "glass", "ingot", "nugget", "brick",
        "nether_brick", "scrap", "charcoal", "quartz", "copper_block",
        "iron_block", "gold_block", "raw_iron_block", "raw_gold_block",
        "polished", "deepslate", "cut_copper", "tuff_brick", "chiseled",
        "resin", "pale_oak",
    ]),
    ("/// TOOLS", [
        "pickaxe", "shovel", "sword", "axe", "hoe", "bow", "arrow",
        "bucket", "shears", "compass", "spear", "mace", "hammer",
    ]),
    ("// FURNITURE", [
        "crafting_table", "furnace", "chest", "barrel", "bookshelf", "shelf",
        "smithing", "grindstone", "anvil", "cauldron", "hopper", "dropper",
        "dispenser", "piston", "observer", "lantern", "torch", "candle",
        "bed", "sign", "hanging_sign", "door", "trapdoor", "fence",
        "rail", "minecart",
    ]),
    ("/// FOOD", [
        "beef", "porkchop", "chicken", "mutton", "rabbit", "salmon", "cod",
        "bread", "cake", "cookie", "pie", "stew", "soup", "apple",
        "carrot", "potato", "beet", "wheat", "milk",
    ]),
]


def _guess_section(item_id: str) -> str:
    for section, keywords in _SECTION_HINTS:
        if any(k in item_id for k in keywords):
            return section
    return "// MISC"


def _format_mine_entry(item_id: str) -> str:
    item_const = item_id.upper()
    block_const = item_id.upper()
    return f'            mine("{item_id}", Items.{item_const});'


def _format_smelt_entry(item_id: str, smelt_inputs: list[str]) -> str:
    # Pick the best smelt input (prefer raw_* over ore blocks)
    raw_inputs = [s for s in smelt_inputs if not s.endswith("_ore")]
    primary = raw_inputs[0] if raw_inputs else (smelt_inputs[0] if smelt_inputs else "???")
    item_const = item_id.upper()
    return f'            smelt("{item_id}", Items.{item_const}, "{primary}");'


def _format_mob_entry(item_id: str) -> str:
    item_const = item_id.upper()
    return f'            mob("{item_id}", Items.{item_const}, /* TODO: mob class */);'


def _format_shaped_entry(item_id: str, ingredients: list[str], method: str) -> str:
    item_const = item_id.upper()
    # Detect single-ingredient 2x2 block pattern
    unique = list(dict.fromkeys(ingredients))  # preserve order, deduplicate
    if method == "CRAFT_SHAPED_2x2" and len(unique) == 1:
        return f'            shapedRecipe2x2Block("{item_id}", Items.{item_const}, "{unique[0]}");'
    if method == "CRAFT_SHAPED_3x3" and len(unique) == 1:
        return f'            shapedRecipe3x3Block("{item_id}", Items.{item_const}, "{unique[0]}");'
    # General form — just a comment with evidence
    ing_str = ", ".join(f'"{i}"' for i in ingredients[:9])
    size = "3x3" if method in ("CRAFT_SHAPED_3x3",) else "2x2"
    return (
        f'            // TODO: shapedRecipe{size}("{item_id}", Items.{item_const}, '
        f'1, {ing_str}); // verify grid layout'
    )


def _format_smith_entry(item_id: str, smith_base: list[str], smith_material: list[str]) -> str:
    item_const = item_id.upper()
    base = smith_base[0] if smith_base else "???"
    mat = smith_material[0] if smith_material else "???"
    return f'            smith("{item_id}", Items.{item_const}, "{mat}", "{base}");'


def _needs_human_review(entry: dict) -> tuple[bool, str]:
    """Return (unsafe, reason) — True means skip auto-emit, put in review."""
    item_id = entry["itemId"]
    canonical = entry["canonicalMethod"]
    ingredients = entry.get("craftIngredients", [])

    # If canonical is a craft but we detect a potentially bogus ingredient chain:
    # an ingredient that shares a root with the output item suggests an inversion.
    if canonical in ("CRAFT_SHAPED_3x3", "CRAFT_SHAPED_2x2", "CRAFT_SHAPELESS"):
        item_base = item_id.replace("_block", "").replace("_bottle", "")
        for ing in ingredients:
            ing_base = ing.replace("_block", "")
            if item_base and len(item_base) > 4 and item_base in ing_base:
                return True, f"Possible craft inversion: {item_id} from {ing}"

    # mob entries need a human to supply the mob class
    if canonical == "MOB_DROP":
        return True, "Mob drop — requires human to specify mob class"

    return False, ""


def emit_additions(report: dict, out_dir: Path) -> None:
    today = date.today().isoformat()
    lines: list[str] = [
        f"// === ADDED BY VALIDATOR {today} — review before merging ===",
        "// Each section below corresponds to a TaskCatalogue section.",
        "// Lines marked TODO need layout verification or a mob class.",
        "",
    ]

    by_section: dict[str, list[str]] = {}
    unsafe: list[dict] = []

    for entry in sorted(report["missingNew"], key=lambda e: e["itemId"]):
        needs_review, reason = _needs_human_review(entry)
        if needs_review:
            unsafe.append({**entry, "reviewReason": reason})
            continue

        item_id = entry["itemId"]
        canonical = entry["canonicalMethod"]
        section = _guess_section(item_id)

        if canonical == "MINE":
            line = _format_mine_entry(item_id)
        elif canonical and canonical.startswith("SMELT"):
            line = _format_smelt_entry(item_id, entry.get("smeltInputs", []))
        elif canonical == "SMITH":
            line = _format_smith_entry(item_id, entry.get("smithBase", []), entry.get("smithMaterial", []))
        elif canonical in ("CRAFT_SHAPED_3x3", "CRAFT_SHAPED_2x2", "CRAFT_SHAPELESS"):
            line = _format_shaped_entry(item_id, entry.get("craftIngredients", []), canonical)
        else:
            line = f'            // UNSUPPORTED: "{item_id}" via {canonical}'

        by_section.setdefault(section, []).append(line)

    # Emit unsafe proposals as commented-out TODO blocks
    if unsafe:
        by_section.setdefault("// REQUIRES HUMAN REVIEW", [])
        for entry in sorted(unsafe, key=lambda e: e["itemId"]):
            item_id = entry["itemId"]
            reason = entry.get("reviewReason", "")
            canonical = entry["canonicalMethod"]
            ingredients = entry.get("craftIngredients") or entry.get("smeltInputs") or []
            ing_str = ", ".join(ingredients[:4])
            by_section["// REQUIRES HUMAN REVIEW"].append(
                f'            // TODO: "{item_id}" via {canonical} [{ing_str}] — {reason}'
            )

    for section, section_lines in sorted(by_section.items()):
        lines.append(section)
        lines.extend(section_lines)
        lines.append("")

    out_path = out_dir / "catalogue-additions.java"
    out_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"Wrote {out_path}  ({len(report['missingNew'])} missingNew items)")


def emit_review_doc(report: dict, out_dir: Path) -> None:
    today = date.today().isoformat()
    parts: list[str] = [
        f"# TaskCatalogue Reconciliation Review — {today}\n",
        "Generated by `tools/reconcile_catalogue.py`. "
        "All three sections require human review before any changes are applied.\n",
    ]

    # Stale recipes
    stale = report["staleRecipe"]
    parts.append(f"## Stale Recipes ({len(stale)})\n")
    parts.append(
        "These catalogue entries have ingredients that differ from what MC 26.1.2 reports.\n"
    )
    if stale:
        for s in sorted(stale, key=lambda x: x["itemId"]):
            parts.append(f"### `{s['itemId']}`")
            parts.append(f"- Catalogue helper: `{s['catalogueHelper']}`")
            parts.append(f"- Catalogue args: `{s['catalogueArgs']}`")
            parts.append(f"- JSON canonical: `{s['jsonCanonical']}`")
            parts.append(f"- JSON ingredients: `{s['jsonIngredients']}`")
            parts.append("")
    else:
        parts.append("_None found._\n")

    # Abstract family keys (no direct MC item-ID match)
    abstract_keys = report.get("abstractFamilyKey", [])
    parts.append(f"## Abstract Family Keys / Possible Extractor Gaps ({len(abstract_keys)})\n")
    parts.append(
        "These catalogue entries use generic/abstract names (e.g. `mushroom`, `sapling`, `chain`) "
        "that have no exact matching itemId in the MC 26.1.2 extractor output.\n"
        "They are intentionally kept as family or abstract keys, or may cover items the extractor "
        "did not capture (e.g. items obtained from the world rather than recipes).\n"
        "**Do not delete these without verifying the item family is still valid.**\n"
    )
    if abstract_keys:
        for o in sorted(abstract_keys, key=lambda x: x["catalogueName"]):
            parts.append(f"- `{o['catalogueName']}` (helper: `{o['helper']}`, args: `{o['stringArgs']}`)")
        parts.append("")
    else:
        parts.append("_None found._\n")

    # Obsolete entries — true renames/removals (no abstract-key explanation)
    obsolete = report["obsoleteInCatalogue"]
    parts.append(f"## Truly Obsolete Entries ({len(obsolete)})\n")
    parts.append(
        "These explicit catalogue entries have no corresponding itemId in MC 26.1.2 "
        "and are NOT abstract family keys. They are candidates for removal.\n"
    )
    if obsolete:
        for o in sorted(obsolete, key=lambda x: x["catalogueName"]):
            parts.append(f"- `{o['catalogueName']}` (helper: `{o['helper']}`, args: `{o['stringArgs']}`)")
        parts.append("")
    else:
        parts.append("_None found._\n")

    # Unsafe/unhandled proposals
    uncovered = report["uncovered"]
    unhandled = report["unhandledMethod"]
    parts.append(f"## Items Without Auto-Proposed Additions ({len(uncovered) + len(unhandled)})\n")
    parts.append(
        "Items the validator found in MC 26.1.2 but could not safely auto-propose "
        "(mob drops lacking a mob class, craft inversions, metadata-only items, etc.).\n"
        "These are also listed as `// TODO` comments in `catalogue-additions.java`.\n"
    )
    if uncovered or unhandled:
        for u in sorted(uncovered, key=lambda x: x["itemId"]):
            parts.append(f"- `{u['itemId']}` — no canonical obtain method (methods: {u['methods']})")
        for u in sorted(unhandled, key=lambda x: x.get("itemId", "")):
            item_id = u.get("itemId", "?")
            parts.append(f"- `{item_id}` — metadata-only methods: {u['methods']}")
        parts.append("")
    else:
        parts.append("_None found._\n")

    out_path = out_dir / "catalogue-review.md"
    out_path.write_text("\n".join(parts), encoding="utf-8")
    print(f"Wrote {out_path}")


# ---------------------------------------------------------------------------
# Sanity checks
# ---------------------------------------------------------------------------

def run_sanity_checks(report: dict, cat_model: dict) -> list[str]:
    failures: list[str] = []
    special_collectors = set(cat_model["specialCollectorTable"].keys())

    # 1. Specialized collectors must not appear in staleRecipe
    stale_names = {s["itemId"] for s in report["staleRecipe"]}
    bad = special_collectors & stale_names
    if bad:
        failures.append(f"FAIL: Special collectors appeared in staleRecipe: {sorted(bad)}")

    # 2. Items in allKnownNames must not also appear in missingNew
    missing_ids = {e["itemId"] for e in report["missingNew"]}
    all_known_set = set(cat_model["allKnownNames"])
    # Only check the core wood/colour items that SHOULD be in allKnownNames
    wood_check = {"oak_planks", "spruce_planks", "bamboo_planks", "crimson_planks", "warped_planks"}
    color_check = {f"{c}_wool" for c in DYE_COLORS} | {f"{c}_bed" for c in DYE_COLORS}
    for name in (wood_check | color_check) & all_known_set:
        if name in missing_ids:
            failures.append(f"FAIL: {name!r} is in allKnownNames but also in missingNew")

    # 3. No duplicate names between allKnownNames and missingNew
    collisions = set(cat_model["allKnownNames"]) & missing_ids
    if collisions:
        failures.append(f"FAIL: missingNew collides with allKnownNames: {sorted(collisions)[:10]}")

    # 4. No smelt proposals with _ore-only inputs
    for e in report["missingNew"]:
        canonical = e["canonicalMethod"]
        if canonical and canonical.startswith("SMELT"):
            inputs = e.get("smeltInputs", [])
            if inputs and all(inp.endswith("_ore") for inp in inputs):
                failures.append(
                    f"FAIL: Smelt proposal for {e['itemId']!r} has only ore inputs: {inputs}"
                )

    return failures


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(
        description="Reconcile TaskCatalogue.java against emma_extracted_recipes.json"
    )
    parser.add_argument(
        "--catalogue",
        default=r"C:\Users\Owner\Emmaclef\java\emmaclef\src\main\java\emma\emmaclef\TaskCatalogue.java",
        help="Path to the handwritten TaskCatalogue.java",
    )
    parser.add_argument(
        "--extractor",
        default=r"C:\Users\Owner\Emma-RecipeExtractor\emma_extracted_recipes.json",
        help="Path to emma_extracted_recipes.json",
    )
    parser.add_argument(
        "--out",
        default=r"C:\Users\Owner\Emma-RecipeExtractor\generated",
        help="Output directory",
    )
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    print("Stage 1: Parsing catalogue…")
    cat_model = build_catalogue_model(args.catalogue)
    s1_path = out_dir / "stage1-catalogue-model.json"
    s1_path.write_text(json.dumps(cat_model, indent=2, sort_keys=True), encoding="utf-8")
    print(f"  {len(cat_model['allKnownNames'])} known names -> {s1_path}")

    print("Stage 2: Parsing extractor JSON…")
    mc_model = build_extractor_model(args.extractor)
    s2_path = out_dir / "stage2-mc26-model.json"
    s2_path.write_text(json.dumps(mc_model, indent=2, sort_keys=True), encoding="utf-8")
    print(f"  {len(mc_model)} unique itemIds -> {s2_path}")

    print("Stage 3: Reconciling…")
    report = reconcile(cat_model, mc_model)

    # Print bucket summary
    for bucket, items in report.items():
        print(f"  {bucket}: {len(items)}")

    report_path = out_dir / "validation-report.json"
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    print(f"  -> {report_path}")

    print("Stage 4: Sanity checks…")
    failures = run_sanity_checks(report, cat_model)
    if failures:
        for f in failures:
            print(f"  {f}", file=sys.stderr)
        print(f"  {len(failures)} sanity check(s) FAILED — review output carefully", file=sys.stderr)
    else:
        print("  All sanity checks passed.")

    print("Stage 5: Emitting outputs…")
    emit_additions(report, out_dir)
    emit_review_doc(report, out_dir)

    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
