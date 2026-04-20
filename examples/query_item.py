#!/usr/bin/env python3

import argparse
import json
from pathlib import Path


DEFAULT_EXPORT_PATH = Path(__file__).resolve().parents[1] / "emma_extracted_recipes_26.1.json"


def load_export(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def normalize_item_id(value: str) -> str:
    if value.startswith("minecraft:"):
        return value.split(":", 1)[1]
    return value


def matches_entry(entry: dict, query: str) -> bool:
    normalized = normalize_item_id(query)
    if entry.get("itemId") == normalized:
        return True

    for item_match in entry.get("itemMatches", []):
        if item_match == query or normalize_item_id(item_match) == normalized:
            return True

    return False


def format_choices(values: list[str]) -> str:
    if not values:
        return "<none>"
    return ", ".join(values)


def summarize_entry(entry: dict) -> list[str]:
    method = entry.get("obtainMethod", "UNKNOWN")
    lines = [f"- {method}"]

    if method.startswith("CRAFT_"):
        lines.append(f"  recipeId: {entry.get('recipeId', '<missing>')}")
        lines.append(f"  yield: {entry.get('craftYield', '<missing>')}")
        slots = [format_choices(slot) for slot in entry.get("craftGrid", [])]
        lines.append(f"  ingredients: {' | '.join(slots) if slots else '<missing>'}")
    elif method.startswith("SMELT"):
        lines.append(f"  recipeId: {entry.get('recipeId', '<missing>')}")
        lines.append(f"  from: {format_choices(entry.get('smeltFrom', []))}")
        lines.append(f"  xp: {entry.get('experience', '<missing>')}")
        lines.append(f"  cookingTime: {entry.get('cookingTime', '<missing>')}")
    elif method in {"SMITH", "SMITH_TRIM"}:
        lines.append(f"  recipeId: {entry.get('recipeId', '<missing>')}")
        if entry.get("smithTemplate"):
            lines.append(f"  template: {format_choices(entry.get('smithTemplate', []))}")
        lines.append(f"  base: {format_choices(entry.get('smithBase', []))}")
        lines.append(f"  material: {format_choices(entry.get('smithMaterial', []))}")
    elif method == "STONECUTTER":
        lines.append(f"  recipeId: {entry.get('recipeId', '<missing>')}")
        lines.append(f"  from: {format_choices(entry.get('stonecutterFrom', []))}")
        lines.append(f"  yield: {entry.get('stonecutterYield', entry.get('craftYield', '<missing>'))}")
    elif method == "MINE":
        blocks = []
        for block in entry.get("mineBlocks", []):
            requirement = block.get("requirement")
            suffix = f" ({requirement})" if requirement else ""
            blocks.append(f"{block.get('block', '<missing>')} via {block.get('toolType', 'UNKNOWN')}{suffix}")
        lines.append(f"  sources: {'; '.join(blocks) if blocks else '<missing>'}")
        if entry.get("miningRequirement"):
            lines.append(f"  bestRequirement: {entry['miningRequirement']}")
        if entry.get("dimension"):
            lines.append(f"  dimensionHint: {entry['dimension']}")
    elif method == "MOB_DROP":
        lines.append(f"  mob: {entry.get('mobClass', '<missing>')}")
        if entry.get("spawnDimensions"):
            lines.append(f"  spawnDimensions: {format_choices(entry['spawnDimensions'])}")
    elif method in {"BREW_POTION", "BREW_CONTAINER"}:
        lines.append(f"  from: {entry.get('brewFrom', '<missing>')}")
        lines.append(f"  ingredient: {format_choices(entry.get('brewIngredient', []))}")
        lines.append(f"  to: {entry.get('brewTo', '<missing>')}")
    elif method == "ENCHANTMENT":
        lines.append(f"  levels: {entry.get('minLevel', '<missing>')}..{entry.get('maxLevel', '<missing>')}")
        lines.append(f"  supportedItems: {len(entry.get('supportedItems', []))}")
        lines.append(f"  description: {entry.get('description', '<missing>')}")
    elif method == "BANNER_PATTERN":
        lines.append(f"  patternId: {entry.get('patternId', '<missing>')}")
        lines.append(f"  translationKey: {entry.get('translationKey', '<missing>')}")
    elif method == "ITEM_PROPERTIES":
        properties = entry.get("properties", {})
        if properties.get("repairsWith"):
            lines.append(f"  repairsWith: {format_choices(properties['repairsWith'])}")
        if "enchantability" in properties:
            lines.append(f"  enchantability: {properties['enchantability']}")
        if "maxStackSize" in properties:
            lines.append(f"  maxStackSize: {properties['maxStackSize']}")
        if "maxDurability" in properties:
            lines.append(f"  maxDurability: {properties['maxDurability']}")
        if "equippable" in properties:
            lines.append(f"  equippable: {properties['equippable']}")
    else:
        lines.append("  raw: unsupported method in demo formatter")

    return lines


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Query an Emma recipe extractor JSON file for all obtainability records for one item."
    )
    parser.add_argument("item", help="Item ID to query, for example acacia_boat or minecraft:acacia_boat")
    parser.add_argument(
        "--file",
        default=str(DEFAULT_EXPORT_PATH),
        help="Path to the extractor JSON file. Defaults to the checked-in 26.1 sample.",
    )
    args = parser.parse_args()

    export_path = Path(args.file)
    if not export_path.is_absolute():
        export_path = Path.cwd() / export_path

    data = load_export(export_path)
    entries = data.get("entries", [])
    matches = [entry for entry in entries if matches_entry(entry, args.item)]

    if not matches:
        print(f"No entries found for {args.item}")
        return 1

    normalized = normalize_item_id(args.item)
    print(f"Item: {normalized}")
    print(f"Matches: {len(matches)}")
    print()

    for entry in matches:
        for line in summarize_entry(entry):
            print(line)
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())