#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict, deque
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

from catalogue_model import build_catalogue_model


CRAFT_METHODS = {"CRAFT_SHAPED_2x2", "CRAFT_SHAPED_3x3", "CRAFT_SHAPELESS"}
SMELT_METHODS = {"SMELT", "SMELT_FURNACE", "SMELT_BLAST", "SMELT_SMOKER", "SMELT_CAMPFIRE"}
SUPPORTED_METHODS = CRAFT_METHODS | SMELT_METHODS | {"MINE", "SMITH", "MOB_DROP"}
DEFAULT_ROOT_PATH = Path(__file__).resolve().parents[1]
SYNTHETIC_CANDIDATE_PREFIX = "__synthetic__:"

COLOR_PREFIXES = {
    "white",
    "orange",
    "magenta",
    "light_blue",
    "yellow",
    "lime",
    "pink",
    "gray",
    "light_gray",
    "cyan",
    "purple",
    "blue",
    "brown",
    "green",
    "red",
    "black",
}

TOOL_FAMILIES = [
    ("wooden", "planks", ("wooden_pickaxe", "wooden_shovel", "wooden_sword", "wooden_axe", "wooden_hoe")),
    ("stone", "cobblestone", ("stone_pickaxe", "stone_shovel", "stone_sword", "stone_axe", "stone_hoe")),
    ("iron", "iron_ingot", ("iron_pickaxe", "iron_shovel", "iron_sword", "iron_axe", "iron_hoe")),
    ("golden", "gold_ingot", ("golden_pickaxe", "golden_shovel", "golden_sword", "golden_axe", "golden_hoe")),
    ("diamond", "diamond", ("diamond_pickaxe", "diamond_shovel", "diamond_sword", "diamond_axe", "diamond_hoe")),
]

ARMOR_FAMILIES = [
    ("leather", "leather", ("leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots")),
    ("iron", "iron_ingot", ("iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots")),
    ("golden", "gold_ingot", ("golden_helmet", "golden_chestplate", "golden_leggings", "golden_boots")),
    ("diamond", "diamond", ("diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots")),
]

STATIC_ALIASES = [
    ("lapis", "lapis_lazuli"),
    ("netherite_upgrade", "netherite_upgrade_smithing_template"),
    ("book_and_quill", "writable_book"),
    ("eye_of_ender", "ender_eye"),
    ("wooden_pick", "wooden_pickaxe"),
    ("stone_pick", "stone_pickaxe"),
    ("iron_pick", "iron_pickaxe"),
    ("gold_pick", "golden_pickaxe"),
    ("diamond_pick", "diamond_pickaxe"),
    ("netherite_pick", "netherite_pickaxe"),
    ("door", "wooden_door"),
    ("trapdoor", "wooden_trapdoor"),
    ("fence", "wooden_fence"),
    ("fence_gate", "wooden_fence_gate"),
    ("minecart_with_chest", "chest_minecart"),
    ("minecart_with_furnace", "furnace_minecart"),
    ("minecart_with_hopper", "hopper_minecart"),
    ("minecart_with_tnt", "tnt_minecart"),
]

CROP_BLOCK_SEEDS = {
    "carrots": "carrot",
    "potatoes": "potato",
    "beetroots": "beetroot_seeds",
}

PREFERRED_MOB_CLASSES = {
    "bone": "skeleton",
    "gunpowder": "creeper",
    "spider_eye": "spider",
    "leather": "cow",
    "feather": "chicken",
    "rotten_flesh": "zombie",
    "rabbit_foot": "rabbit",
    "rabbit_hide": "rabbit",
    "slime_ball": "slime",
    "wither_skeleton_skull": "wither_skeleton",
    "ink_sac": "squid",
    "glow_ink_sac": "glow_squid",
    "string": "spider",
    "porkchop": "pig",
    "beef": "cow",
    "chicken": "chicken",
    "mutton": "sheep",
    "rabbit": "rabbit",
    "salmon": "salmon",
    "cod": "cod",
}

MOB_CLASS_EXPR_OVERRIDES = {
    "creeper": "Creeper.class",
    "slime": "Slime.class",
}

EXPLICIT_REGISTRATION_RE = re.compile(
    r'^\s*(?:simple|crop|mobCook|mob|mine|shear|smelt|smith|shapedRecipe2x2|shapedRecipe3x3|shapedRecipe2x2Block|shapedRecipe3x3Block|shapedRecipeSlab|shapedRecipeStairs|shapedRecipeWall|alias)\("([^"]+)"'
)


def log_progress(message: str) -> None:
    print(message, flush=True)

BASE_COSTS = {
    "MINE": 10.0,
    "MOB_DROP": 18.0,
    "SMELT": 28.0,
    "SMELT_FURNACE": 28.0,
    "SMELT_BLAST": 28.0,
    "SMELT_SMOKER": 28.0,
    "SMELT_CAMPFIRE": 32.0,
    "CRAFT_SHAPED_2x2": 45.0,
    "CRAFT_SHAPED_3x3": 50.0,
    "CRAFT_SHAPELESS": 45.0,
    "SMITH": 58.0,
}

DIMENSION_COSTS = {
    "OVERWORLD": 0.0,
    "NETHER": 20.0,
    "END": 35.0,
}

TOOL_OR_ARMOR_SUFFIXES = {
    "axe",
    "boots",
    "chestplate",
    "helmet",
    "hoe",
    "horse_armor",
    "leggings",
    "nautilus_armor",
    "pickaxe",
    "shovel",
    "spear",
    "sword",
}

CURRENT_WOOD_PREFIXES = {
    "acacia",
    "birch",
    "bamboo",
    "cherry",
    "crimson",
    "dark_oak",
    "jungle",
    "mangrove",
    "oak",
    "spruce",
    "warped",
}

WOOD_AGGREGATE_KEYS = {
    "boat",
    "hanging_sign",
    "leaves",
    "log",
    "planks",
    "sapling",
    "sign",
    "stick",
    "stripped_logs",
    "wooden_button",
    "wooden_door",
    "wooden_fence",
    "wooden_fence_gate",
    "wooden_pressure_plate",
    "wooden_slab",
    "wooden_stairs",
    "wooden_trapdoor",
}

WOOD_SLOT_AGGREGATES = {
    "_button": "wooden_button",
    "_door": "wooden_door",
    "_fence": "wooden_fence",
    "_fence_gate": "wooden_fence_gate",
    "_hanging_sign": "hanging_sign",
    "_leaves": "leaves",
    "_planks": "planks",
    "_pressure_plate": "wooden_pressure_plate",
    "_sapling": "sapling",
    "_sign": "sign",
    "_slab": "wooden_slab",
    "_stairs": "wooden_stairs",
    "_trapdoor": "wooden_trapdoor",
}

WOOD_SYNTHETIC_PRELUDE = [
    '        // Synthetic wood section mirrors EmmaClef\'s aggregate and per-species wood catalogue semantics.',
    '        mine("log", MiningRequirement.HAND, ItemHelper.LOG, ItemHelper.LOG).anyDimension();',
    '        woodTasks("log", wood -> wood.log, (wood, count) -> new MineAndCollectTask(wood.log, count, new Block[]{Block.byItem(wood.log)}, MiningRequirement.HAND), true);',
    '        mine("oak_sapling", Blocks.OAK_LEAVES, Items.OAK_SAPLING);',
    '        mine("spruce_sapling", Blocks.SPRUCE_LEAVES, Items.SPRUCE_SAPLING);',
    '        mine("birch_sapling", Blocks.BIRCH_LEAVES, Items.BIRCH_SAPLING);',
    '        mine("jungle_sapling", Blocks.JUNGLE_LEAVES, Items.JUNGLE_SAPLING);',
    '        mine("acacia_sapling", Blocks.ACACIA_LEAVES, Items.ACACIA_SAPLING);',
    '        mine("dark_oak_sapling", Blocks.DARK_OAK_LEAVES, Items.DARK_OAK_SAPLING);',
    '        mine("mangrove_propagule", Blocks.MANGROVE_PROPAGULE, Items.MANGROVE_PROPAGULE);',
    '        mine("cherry_sapling", Blocks.CHERRY_LEAVES, Items.CHERRY_SAPLING);',
    '        simple("sapling", ItemHelper.SAPLINGS, CollectSaplingsTask::new);',
    '        shear("leaves", ItemHelper.itemsToBlocks(ItemHelper.LEAVES), ItemHelper.LEAVES).dontMineIfPresent();',
    '        for (CataloguedResource resource : woodTasks(',
    '                "leaves",',
    '                woodItems -> woodItems.leaves,',
    '                (woodItems, count) -> {',
    '                    if (woodItems.isNetherWood()) {',
    '                        return new MineAndCollectTask(woodItems.leaves, count, new Block[]{Block.byItem(woodItems.leaves)}, MiningRequirement.HAND).forceDimension(Dimension.NETHER);',
    '                    } else {',
    '                        return new ShearAndCollectBlockTask(woodItems.leaves, count, Block.byItem(woodItems.leaves));',
    '                    }',
    '                })',
    '        ) {',
    '            resource.dontMineIfPresent();',
    '        }',
    '        mine("bamboo", Blocks.BAMBOO, Items.BAMBOO);',
    '        simple("planks", ItemHelper.PLANKS, CollectPlanksTask::new).dontMineIfPresent();',
    '        for (CataloguedResource woodCatalogue : woodTasks("planks", wood -> wood.planks, (wood, count) -> {',
    '            CollectPlanksTask result = new CollectPlanksTask(wood.planks, count);',
    '            if (wood.isNetherWood()) {',
    '                result.logsInNether();',
    '            }',
    '            return result;',
    '        }, true)) {',
    '            woodCatalogue.dontMineIfPresent();',
    '        }',
    '        simple("stripped_logs", ItemHelper.STRIPPED_LOGS, CollectStrippedLogTask::new).dontMineIfPresent();',
    '        for (CataloguedResource woodCatalogue : woodTasks("stripped_logs", wood -> wood.strippedLog,',
    '                (wood, count) -> new CollectStrippedLogTask(wood.strippedLog, count))) {',
    '            woodCatalogue.dontMineIfPresent();',
    '        }',
    '        simple("stick", Items.STICK, CollectSticksTask::new);',
    '        shapedRecipe2x2("bamboo_planks", Items.BAMBOO_PLANKS, 2, "bamboo_block", null, null, null);',
    '        shapedRecipe3x3Block("bamboo_block", Items.BAMBOO_BLOCK, "bamboo");',
    '        simple("boat", ItemHelper.WOOD_BOAT, CollectBoatTask::new);',
    '        woodTasks("boat", woodItems -> woodItems.boat, (woodItems, count) -> new CollectBoatTask(woodItems.boat, woodItems.prefix + "_planks", count));',
    '        simple("wooden_pressure_plate", ItemHelper.WOOD_PRESSURE_PLATE, CollectWoodenPressurePlateTask::new);',
    '        woodTasks("pressure_plate", woodItems -> woodItems.pressurePlate, (woodItems, count) -> new CollectWoodenPressurePlateTask(woodItems.pressurePlate, woodItems.prefix + "_planks", count));',
    '        simple("wooden_button", ItemHelper.WOOD_BUTTON, CollectWoodenButtonTask::new);',
    '        woodTasks("button", woodItems -> woodItems.button, (woodItems, count) -> new CraftInInventoryTask(new RecipeTarget(woodItems.button, 1, CraftingRecipe.newShapedRecipe(woodItems.prefix + "_button", new ItemTarget[]{new ItemTarget(woodItems.planks, 1), null, null, null}, 1))));',
    '        simple("sign", ItemHelper.WOOD_SIGN, CollectSignTask::new).dontMineIfPresent();',
    '        woodTasks("sign", woodItems -> woodItems.sign, (woodItems, count) -> new CollectSignTask(woodItems.sign, woodItems.prefix + "_planks", count));',
    '        simple("hanging_sign", ItemHelper.WOOD_HANGING_SIGN, CollectHangingSignTask::new).dontMineIfPresent();',
    '        woodTasks("hanging_sign", woodItems -> woodItems.hangingSign, (woodItems, count) -> new CollectHangingSignTask(woodItems.hangingSign, woodItems.prefix + "_stripped_logs", count));',
    '        simple("wooden_stairs", ItemHelper.WOOD_STAIRS, CollectWoodenStairsTask::new);',
    '        woodTasks("stairs", woodItems -> woodItems.stairs, (woodItems, count) -> new CollectWoodenStairsTask(woodItems.stairs, woodItems.prefix + "_planks", count));',
    '        simple("wooden_slab", ItemHelper.WOOD_SLAB, CollectWoodenSlabTask::new);',
    '        woodTasks("slab", woodItems -> woodItems.slab, (woodItems, count) -> new CollectWoodenSlabTask(woodItems.slab, woodItems.prefix + "_planks", count));',
    '        simple("wooden_door", ItemHelper.WOOD_DOOR, CollectWoodenDoorTask::new);',
    '        woodTasks("door", woodItems -> woodItems.door, (woodItems, count) -> new CollectWoodenDoorTask(woodItems.door, woodItems.prefix + "_planks", count));',
    '        simple("wooden_trapdoor", ItemHelper.WOOD_TRAPDOOR, CollectWoodenTrapDoorTask::new);',
    '        woodTasks("trapdoor", woodItems -> woodItems.trapdoor, (woodItems, count) -> new CollectWoodenTrapDoorTask(woodItems.trapdoor, woodItems.prefix + "_planks", count));',
    '        simple("wooden_fence", ItemHelper.WOOD_FENCE, CollectFenceTask::new);',
    '        woodTasks("fence", woodItems -> woodItems.fence, (woodItems, count) -> new CollectFenceTask(woodItems.fence, woodItems.prefix + "_planks", count));',
    '        simple("wooden_fence_gate", ItemHelper.WOOD_FENCE_GATE, CollectFenceGateTask::new);',
    '        woodTasks("fence_gate", woodItems -> woodItems.fenceGate, (woodItems, count) -> new CollectFenceGateTask(woodItems.fenceGate, woodItems.prefix + "_planks", count));',
    '        alias("door", "wooden_door");',
    '        alias("trapdoor", "wooden_trapdoor");',
    '        alias("fence", "wooden_fence");',
    '        alias("fence_gate", "wooden_fence_gate");',
]


@dataclass(frozen=True)
class RawCandidate:
    key: str
    item_id: str
    method: str
    entry: dict[str, Any]
    slot_options: tuple[tuple[str, ...] | None, ...]


@dataclass(frozen=True)
class ResolvedCandidate:
    candidate_key: str
    item_id: str
    method: str
    deps: tuple[str, ...]
    total_cost: float


@dataclass
class HelperGenerationPlan:
    lines: list[str]
    alias_lines: list[str]
    covered_items: set[str]
    generated_items: set[str]


@dataclass(frozen=True)
class BaselineCatalogue:
    known_names: frozenset[str]
    structurally_covered_item_ids: frozenset[str]

    def covers_item_id(self, item_id: str) -> bool:
        return item_id in self.known_names or item_id in self.structurally_covered_item_ids

    def covers_name(self, name: str) -> bool:
        return name in self.known_names


@dataclass(frozen=True)
class SemanticOverride:
    required_items: frozenset[str]
    lines: tuple[str, ...]
    covered_items: tuple[str, ...]


SEMANTIC_OVERRIDES = [
    SemanticOverride(
        required_items=frozenset({"flint"}),
        lines=('        simple("flint", Items.FLINT, CollectFlintTask::new);',),
        covered_items=("flint",),
    ),
    SemanticOverride(
        required_items=frozenset({"sandstone"}),
        lines=('        simple("sandstone", Items.SANDSTONE, CollectSandstoneTask::new).dontMineIfPresent();',),
        covered_items=("sandstone",),
    ),
    SemanticOverride(
        required_items=frozenset({"red_sandstone"}),
        lines=('        simple("red_sandstone", Items.RED_SANDSTONE, CollectRedSandstoneTask::new).dontMineIfPresent();',),
        covered_items=("red_sandstone",),
    ),
    SemanticOverride(
        required_items=frozenset({"obsidian"}),
        lines=('        simple("obsidian", Items.OBSIDIAN, CollectObsidianTask::new).dontMineIfPresent();',),
        covered_items=("obsidian",),
    ),
    SemanticOverride(
        required_items=frozenset({"wheat"}),
        lines=('        simple("wheat", Items.WHEAT, CollectWheatTask::new);',),
        covered_items=("wheat",),
    ),
    SemanticOverride(
        required_items=frozenset({"wheat_seeds"}),
        lines=('        simple("wheat_seeds", Items.WHEAT_SEEDS, CollectWheatSeedsTask::new);',),
        covered_items=("wheat_seeds",),
    ),
    SemanticOverride(
        required_items=frozenset({"iron_ingot"}),
        lines=('        simple("iron_ingot", Items.IRON_INGOT, CollectIronIngotTask::new).forceDimension(Dimension.OVERWORLD);',),
        covered_items=("iron_ingot",),
    ),
    SemanticOverride(
        required_items=frozenset({"gold_ingot"}),
        lines=('        simple("gold_ingot", Items.GOLD_INGOT, CollectGoldIngotTask::new).anyDimension();',),
        covered_items=("gold_ingot",),
    ),
    SemanticOverride(
        required_items=frozenset({"hay_block"}),
        lines=('        simple("hay_block", Items.HAY_BLOCK, CollectHayBlockTask::new).dontMineIfPresent();',),
        covered_items=("hay_block",),
    ),
    SemanticOverride(
        required_items=frozenset({"nether_bricks"}),
        lines=('        simple("nether_bricks", Items.NETHER_BRICKS, CollectNetherBricksTask::new).dontMineIfPresent();',),
        covered_items=("nether_bricks",),
    ),
    SemanticOverride(
        required_items=frozenset({"torch"}),
        lines=('        shapedRecipe2x2("torch", Items.TORCH, 4, "coal", null, "stick", null).craftOnly();',),
        covered_items=("torch",),
    ),
]

BLACKLISTED_CRAFT_TRANSFORMS = {
    ("honey_bottle", frozenset({"glass_bottle", "honey_block"})): "reverse honey extraction from honey_block",
}


def load_baseline_catalogue(template_path: Path) -> BaselineCatalogue:
    model = build_catalogue_model(template_path)
    return BaselineCatalogue(
        known_names=frozenset(model["allKnownNames"]),
        structurally_covered_item_ids=frozenset(model["structurallyCoveredJsonIds"]),
    )


def has_baseline_name_overlap(names: set[str] | tuple[str, ...], baseline: BaselineCatalogue | None) -> bool:
    if baseline is None:
        return False
    return any(name in baseline.known_names for name in names)


def normalize_item_id(value: str | None) -> str:
    if not value:
        return ""
    if ":" in value:
        return value.split(":", 1)[1]
    return value


def java_constant(identifier: str) -> str:
    return identifier.replace(":", "_").replace("-", "_").replace("/", "_").replace(".", "_").upper()


def item_expr(item_id: str) -> str:
    return f"Items.{java_constant(item_id)}"


def block_expr(block_id: str) -> str:
    return f"Blocks.{java_constant(block_id)}"


def java_string(value: str) -> str:
    return json.dumps(value)


def split_color_prefix(item_id: str) -> tuple[str | None, str]:
    for color in sorted(COLOR_PREFIXES, key=len, reverse=True):
        prefix = color + "_"
        if item_id.startswith(prefix):
            return color, item_id[len(prefix) :]
    return None, item_id


def normalized_spawn_dimension(value: str | None) -> str | None:
    if not value:
        return None
    normalized = normalize_item_id(value)
    mapping = {
        "overworld": "OVERWORLD",
        "the_nether": "NETHER",
        "nether": "NETHER",
        "the_end": "END",
        "end": "END",
    }
    return mapping.get(normalized)


def parse_slot(slot: Any) -> tuple[str, ...] | None:
    if slot is None:
        return None
    if not isinstance(slot, list):
        raise ValueError(f"Unexpected craftGrid slot payload: {slot!r}")
    values = tuple(normalize_item_id(entry) for entry in slot if entry)
    return values or None


def is_unsupported_recolor_recipe(item_id: str, entry: dict[str, Any]) -> bool:
    if entry.get("obtainMethod") not in CRAFT_METHODS:
        return False

    output_color, output_base = split_color_prefix(item_id)
    if output_color is None:
        return False

    for slot in entry.get("craftGrid", []):
        if not isinstance(slot, list):
            continue
        for raw_value in slot:
            dependency_item = normalize_item_id(raw_value)
            dependency_color, dependency_base = split_color_prefix(dependency_item)
            if dependency_color is not None and dependency_base == output_base and dependency_item != item_id:
                return True

    return False


def flattened_slot_items(slot_options: tuple[tuple[str, ...] | None, ...]) -> frozenset[str]:
    values: set[str] = set()
    for slot in slot_options:
        if slot is None:
            continue
        values.update(slot)
    return frozenset(values)


def suspicious_reverse_container_recipe(item_id: str, slot_options: tuple[tuple[str, ...] | None, ...]) -> bool:
    if not item_id.endswith("_bottle"):
        return False
    stem = item_id[: -len("_bottle")]
    return f"{stem}_block" in flattened_slot_items(slot_options)


def blacklisted_craft_reason(
    item_id: str,
    method: str,
    slot_options: tuple[tuple[str, ...] | None, ...],
) -> str | None:
    if method not in CRAFT_METHODS:
        return None

    flattened_items = flattened_slot_items(slot_options)
    exact_reason = BLACKLISTED_CRAFT_TRANSFORMS.get((item_id, flattened_items))
    if exact_reason is not None:
        return exact_reason

    if suspicious_reverse_container_recipe(item_id, slot_options):
        return "suspicious reverse container recipe from compressed block"

    return None


def apply_semantic_overrides(
    present_item_ids: set[str],
    lines: list[str],
    covered_items: set[str],
    generated_items: set[str],
    available_names: set[str],
    baseline: BaselineCatalogue | None = None,
) -> None:
    for override in SEMANTIC_OVERRIDES:
        if not override.required_items <= present_item_ids:
            continue
        if has_baseline_name_overlap(override.covered_items, baseline):
            continue
        lines.extend(override.lines)
        covered_items.update(override.covered_items)
        generated_items.update(override.covered_items)
        available_names.update(override.covered_items)


def pad_shapeless_slots(raw_slots: list[tuple[str, ...] | None]) -> tuple[tuple[str, ...] | None, ...]:
    grid_size = 4 if len(raw_slots) <= 4 else 9
    if len(raw_slots) > 9:
        raise ValueError(f"Shapeless recipe exceeds 3x3 grid size: {len(raw_slots)} slots")
    padded = list(raw_slots)
    padded.extend([None] * (grid_size - len(padded)))
    return tuple(padded)


def expand_shaped_slots(entry: dict[str, Any]) -> tuple[tuple[str, ...] | None, ...]:
    width = int(entry.get("shapedWidth", 0))
    height = int(entry.get("shapedHeight", 0))
    if width <= 0 or height <= 0:
        raise ValueError("Shaped recipe is missing shapedWidth/shapedHeight")

    raw_slots = [parse_slot(slot) for slot in entry.get("craftGrid", [])]
    expected = width * height
    if len(raw_slots) != expected:
        raise ValueError(
            f"shaped recipe for {entry.get('itemId')} has {len(raw_slots)} slots but expected {expected} from shapedWidth*shapedHeight"
        )

    global_width = 2 if entry.get("obtainMethod") == "CRAFT_SHAPED_2x2" else 3
    global_size = 4 if global_width == 2 else 9
    full_slots: list[tuple[str, ...] | None] = [None] * global_size
    index = 0
    for row in range(height):
        for col in range(width):
            full_slots[row * global_width + col] = raw_slots[index]
            index += 1
    return tuple(full_slots)


def is_hard_pruned_dependency(output_item: str, dependency_item: str) -> bool:
    if output_item.endswith("_ingot"):
        stem = output_item[: -len("_ingot")]
        if dependency_item in {f"{stem}_block", f"{stem}_nugget"}:
            return True
        if dependency_item.startswith(stem + "_") and dependency_item[len(stem) + 1 :] in TOOL_OR_ARMOR_SUFFIXES:
            return True
    if output_item.endswith("_nugget"):
        stem = output_item[: -len("_nugget")]
        if dependency_item.startswith(stem + "_") and dependency_item[len(stem) + 1 :] in TOOL_OR_ARMOR_SUFFIXES:
            return True
    return False


def preferred_slot_option(slot: tuple[str, ...]) -> str:
    return min(
        slot,
        key=lambda option: (
            0 if synthetic_catalogue_name_for_item(option) is not None else 1,
            option_bias(option),
            option,
        ),
    )


def collapse_slot_for_search(slot: tuple[str, ...] | None) -> tuple[str, ...] | None:
    if slot is None or len(slot) <= 1:
        return slot

    aggregate = slot_catalogue_name(slot)
    if aggregate is not None:
        return (aggregate,)

    families = {wood_family(item_id) for item_id in slot}
    families.discard(None)
    if len(families) == 1:
        family = next(iter(families))
        if family in CURRENT_WOOD_PREFIXES:
            return (preferred_slot_option(slot),)

    return slot


def canonicalize_slot_options_for_search(
    item_id: str,
    slot_options: tuple[tuple[str, ...] | None, ...],
) -> tuple[tuple[str, ...] | None, ...]:
    canonical_slots: list[tuple[str, ...] | None] = []
    for slot in slot_options:
        if slot is None:
            canonical_slots.append(None)
            continue

        filtered_slot = tuple(option for option in slot if not is_hard_pruned_dependency(item_id, option))
        if not filtered_slot:
            raise ValueError("all candidate dependencies were pruned as recycle transforms")

        canonical_slots.append(collapse_slot_for_search(filtered_slot))

    return tuple(canonical_slots)


def parse_candidates(
    data: dict[str, Any],
    progress_every: int,
) -> tuple[dict[str, list[RawCandidate]], Counter, list[str]]:
    raw_by_item: dict[str, list[RawCandidate]] = defaultdict(list)
    skipped_methods: Counter = Counter()
    parse_warnings: list[str] = []
    entries = data.get("entries", [])
    total_entries = len(entries)

    log_progress(f"Parsing {total_entries} extractor entries")

    for index, entry in enumerate(entries, start=1):
        item_id = normalize_item_id(entry.get("itemId"))
        method = entry.get("obtainMethod")
        if not item_id or not method:
            if progress_every > 0 and (index % progress_every == 0 or index == total_entries):
                log_progress(
                    f"Parsed {index}/{total_entries} entries; distinct items={len(raw_by_item)}; warnings={len(parse_warnings)}; skipped={sum(skipped_methods.values())}"
                )
            continue
        if method not in SUPPORTED_METHODS:
            skipped_methods[method] += 1
            if progress_every > 0 and (index % progress_every == 0 or index == total_entries):
                log_progress(
                    f"Parsed {index}/{total_entries} entries; distinct items={len(raw_by_item)}; warnings={len(parse_warnings)}; skipped={sum(skipped_methods.values())}"
                )
            continue
        if is_unsupported_recolor_recipe(item_id, entry):
            skipped_methods[method] += 1
            parse_warnings.append(f"{item_id}: unsupported recolor transform was pruned from canonical acquisition search")
            if progress_every > 0 and (index % progress_every == 0 or index == total_entries):
                log_progress(
                    f"Parsed {index}/{total_entries} entries; distinct items={len(raw_by_item)}; warnings={len(parse_warnings)}; skipped={sum(skipped_methods.values())}"
                )
            continue

        try:
            if method == "MINE":
                slot_options: tuple[tuple[str, ...] | None, ...] = ()
            elif method == "MOB_DROP":
                slot_options = ()
            elif method in SMELT_METHODS:
                smelt_from = tuple(normalize_item_id(value) for value in entry.get("smeltFrom", []) if value)
                if not smelt_from:
                    raise ValueError("missing smeltFrom")
                slot_options = (smelt_from,)
            elif method == "SMITH":
                smith_base = tuple(normalize_item_id(value) for value in entry.get("smithBase", []) if value)
                smith_material = tuple(normalize_item_id(value) for value in entry.get("smithMaterial", []) if value)
                if not smith_base or not smith_material:
                    raise ValueError("missing smithBase or smithMaterial")
                slot_options = (smith_base, smith_material)
            elif method == "CRAFT_SHAPELESS":
                raw_slots = [parse_slot(slot) for slot in entry.get("craftGrid", [])]
                raw_slots = [slot for slot in raw_slots if slot is not None]
                if not raw_slots:
                    raise ValueError("empty shapeless craftGrid")
                slot_options = pad_shapeless_slots(raw_slots)
            else:
                slot_options = expand_shaped_slots(entry)
            slot_options = canonicalize_slot_options_for_search(item_id, slot_options)

            blacklisted_reason = blacklisted_craft_reason(item_id, method, slot_options)
            if blacklisted_reason is not None:
                skipped_methods[method] += 1
                parse_warnings.append(f"{item_id}: {blacklisted_reason}")
                continue
        except ValueError as error:
            skipped_methods[method] += 1
            parse_warnings.append(f"{item_id}: {error}")
            continue

        key = str(entry.get("recipeId") or f"{method}:{item_id}:{len(raw_by_item[item_id])}")
        raw_by_item[item_id].append(RawCandidate(key=key, item_id=item_id, method=method, entry=entry, slot_options=slot_options))

        if progress_every > 0 and (index % progress_every == 0 or index == total_entries):
            log_progress(
                f"Parsed {index}/{total_entries} entries; distinct items={len(raw_by_item)}; warnings={len(parse_warnings)}; skipped={sum(skipped_methods.values())}"
            )

    return raw_by_item, skipped_methods, parse_warnings


def candidate_has_transform_sibling(item_id: str, raw_by_item: dict[str, list[RawCandidate]]) -> bool:
    return any(candidate.method != "MINE" for candidate in raw_by_item.get(item_id, []))


def normalized_dimension(value: str | None) -> str | None:
    if not value:
        return None
    upper = value.upper()
    if upper in DIMENSION_COSTS:
        return upper
    return None


def entry_dimension(entry: dict[str, Any]) -> str | None:
    explicit_dimension = normalized_dimension(entry.get("dimension"))
    if explicit_dimension is not None:
        return explicit_dimension

    spawn_dimensions = {
        normalized_spawn_dimension(value)
        for value in entry.get("spawnDimensions", [])
        if value
    }
    spawn_dimensions.discard(None)
    if len(spawn_dimensions) == 1:
        return next(iter(spawn_dimensions))
    return None


def self_mine_penalty(candidate: RawCandidate, raw_by_item: dict[str, list[RawCandidate]]) -> float:
    if candidate.method != "MINE":
        return 0.0
    if not candidate_has_transform_sibling(candidate.item_id, raw_by_item):
        return 0.0
    blocks = [normalize_item_id(block.get("block")) for block in candidate.entry.get("mineBlocks", [])]
    return 60.0 if candidate.item_id in blocks else 0.0


def mob_drop_preference_penalty(candidate: RawCandidate, raw_by_item: dict[str, list[RawCandidate]]) -> float:
    if candidate.method != "MOB_DROP":
        return 0.0
    if candidate.item_id in PREFERRED_MOB_CLASSES:
        return 0.0

    has_non_mob_transform = any(
        sibling.method not in {"MOB_DROP", "MINE"}
        for sibling in raw_by_item.get(candidate.item_id, [])
    )
    return 200.0 if has_non_mob_transform else 0.0


def mining_requirement_expr(entry: dict[str, Any]) -> str:
    requirement = (entry.get("miningRequirement") or "HAND").upper()
    if requirement not in {"HAND", "WOOD", "STONE", "IRON", "DIAMOND"}:
        requirement = "HAND"
    return f"MiningRequirement.{requirement}"


def wood_family(item_id: str) -> str | None:
    normalized = item_id
    if normalized.startswith("stripped_"):
        normalized = normalized[len("stripped_") :]
    if normalized in {"bamboo", "bamboo_block", "bamboo_raft"}:
        return "bamboo"
    if normalized == "mangrove_propagule":
        return "mangrove"
    for prefix in sorted(CURRENT_WOOD_PREFIXES, key=len, reverse=True):
        if normalized == prefix or normalized.startswith(prefix + "_"):
            return prefix
    return None


def is_plank_like(item_id: str) -> bool:
    return item_id.endswith("_planks")


def is_log_like(item_id: str) -> bool:
    normalized = item_id
    if normalized.startswith("stripped_"):
        normalized = normalized[len("stripped_") :]
    return normalized.endswith(("_log", "_wood", "_stem", "_hyphae")) or normalized in {"bamboo", "bamboo_block"}


def synthetic_catalogue_name_for_item(item_id: str) -> str | None:
    if item_id in WOOD_AGGREGATE_KEYS:
        return item_id
    if item_id in {
        "oak_sapling",
        "spruce_sapling",
        "birch_sapling",
        "jungle_sapling",
        "acacia_sapling",
        "dark_oak_sapling",
        "mangrove_propagule",
        "cherry_sapling",
        "bamboo",
        "bamboo_block",
        "bamboo_planks",
    }:
        return item_id
    if item_id == "bamboo_raft":
        return "bamboo_boat"

    family = wood_family(item_id)
    if family not in CURRENT_WOOD_PREFIXES:
        return None

    if item_id.startswith("stripped_") and item_id.endswith(("_log", "_stem")):
        return f"{family}_stripped_logs"
    if item_id.endswith("_stem") and family in {"crimson", "warped"}:
        return f"{family}_log"
    if item_id.endswith("_log"):
        return item_id
    if item_id.endswith("_leaves") and family not in {"crimson", "warped", "bamboo"}:
        return item_id
    if item_id.endswith("_planks") and family != "bamboo":
        return item_id
    if item_id.endswith((
        "_button",
        "_door",
        "_fence",
        "_fence_gate",
        "_hanging_sign",
        "_pressure_plate",
        "_sign",
        "_slab",
        "_stairs",
        "_trapdoor",
        "_boat",
    )):
        return item_id
    return None


def is_synthetic_wood_covered_item(item_id: str) -> bool:
    return synthetic_catalogue_name_for_item(item_id) is not None


def slot_catalogue_name(slot: tuple[str, ...] | None) -> str | None:
    if slot is None or len(slot) <= 1:
        return None

    families = {wood_family(item_id) for item_id in slot}
    families.discard(None)
    if len(families) <= 1:
        return None

    if all(is_log_like(item_id) for item_id in slot):
        return "log"

    if all(item_id != "bamboo_raft" and item_id.endswith("_boat") for item_id in slot):
        return "boat"

    for suffix, aggregate in sorted(WOOD_SLOT_AGGREGATES.items(), key=lambda pair: len(pair[0]), reverse=True):
        if all(item_id.endswith(suffix) for item_id in slot):
            return aggregate

    if all(item_id in {"mangrove_propagule", "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "acacia_sapling", "dark_oak_sapling", "cherry_sapling"} for item_id in slot):
        return "sapling"

    return None


def structural_variant_penalty(item_id: str) -> float:
    penalty = 0.0
    if item_id.startswith("chiseled_"):
        penalty += 8.0
    if item_id.startswith("cut_"):
        penalty += 6.0
    if item_id.endswith("_pillar"):
        penalty += 5.0
    return penalty


def option_bias(item_id: str) -> float:
    bias = structural_variant_penalty(item_id)
    if item_id.startswith("white_"):
        bias -= 1.0
    if item_id.startswith("stripped_"):
        bias += 4.0
    if item_id.endswith("_wood"):
        bias += 2.0
    if item_id.endswith("_log") or item_id.endswith("_stem"):
        bias -= 2.0
    if item_id.startswith("crimson_") or item_id.startswith("warped_"):
        bias += 8.0
    return bias


def explicit_registration_name(line: str) -> str | None:
    match = EXPLICIT_REGISTRATION_RE.match(line)
    if match is None:
        return None
    return match.group(1)


def assert_no_duplicate_explicit_registrations(lines: list[str]) -> None:
    seen: dict[str, str] = {}
    for line in lines:
        registration_name = explicit_registration_name(line)
        if registration_name is None:
            continue
        previous_line = seen.get(registration_name)
        if previous_line is not None:
            raise ValueError(
                "duplicate generated catalogue registration for "
                f"{registration_name}: {previous_line.strip()} || {line.strip()}"
            )
        seen[registration_name] = line


def dependency_penalty(output_item: str, dependency_item: str) -> float:
    penalty = option_bias(dependency_item)

    if output_item.endswith("_ingot"):
        stem = output_item[: -len("_ingot")]
        raw_variant = f"raw_{stem}"
        ore_variants = {f"{stem}_ore", f"deepslate_{stem}_ore"}
        if dependency_item == raw_variant:
            penalty -= 8.0
        if dependency_item in ore_variants:
            penalty += 24.0
        if dependency_item in {f"{stem}_block", f"{stem}_nugget"}:
            penalty += 150.0
        if dependency_item.startswith(stem + "_") and dependency_item.split("_")[-1] in TOOL_OR_ARMOR_SUFFIXES:
            penalty += 150.0
    if output_item.endswith("_nugget"):
        stem = output_item[: -len("_nugget")]
        if dependency_item.startswith(stem + "_") and dependency_item.split("_")[-1] in TOOL_OR_ARMOR_SUFFIXES:
            penalty += 120.0
    if dependency_item == f"{output_item}_block":
        penalty += 150.0
    return penalty


def candidate_base_cost(candidate: RawCandidate, raw_by_item: dict[str, list[RawCandidate]], extra_penalties: dict[str, float]) -> float:
    cost = BASE_COSTS[candidate.method] + extra_penalties.get(candidate.key, 0.0)
    if candidate.method == "MINE":
        cost += self_mine_penalty(candidate, raw_by_item)
    if candidate.method == "MOB_DROP":
        cost += mob_drop_preference_penalty(candidate, raw_by_item)
    if candidate.method in {"MINE", "MOB_DROP"}:
        cost += DIMENSION_COSTS.get(entry_dimension(candidate.entry), 0.0)
    return cost


def is_terminal_search_item(item_id: str) -> bool:
    return item_id in WOOD_AGGREGATE_KEYS or is_synthetic_wood_covered_item(item_id)


def terminal_resolved_candidate(item_id: str) -> ResolvedCandidate:
    return ResolvedCandidate(
        candidate_key=f"{SYNTHETIC_CANDIDATE_PREFIX}{item_id}",
        item_id=item_id,
        method="SYNTHETIC",
        deps=(),
        total_cost=0.0,
    )


def resolve_candidates(
    raw_by_item: dict[str, list[RawCandidate]],
    progress_every: int,
    heartbeat_calls: int,
) -> tuple[dict[str, ResolvedCandidate], list[list[str]]]:
    extra_penalties: dict[str, float] = defaultdict(float)
    all_items = tuple(sorted(raw_by_item))
    total_items = len(all_items)

    log_progress(f"Resolving candidates for {total_items} items")

    for attempt in range(1, 13):
        log_progress(f"Resolve pass {attempt}/12 started")
        call_count = 0
        next_heartbeat = heartbeat_calls if heartbeat_calls > 0 else 0

        @lru_cache(maxsize=None)
        def choose_item(item_id: str, stack: tuple[str, ...]) -> ResolvedCandidate | None:
            nonlocal call_count, next_heartbeat
            call_count += 1
            if heartbeat_calls > 0 and call_count >= next_heartbeat:
                log_progress(
                    f"Resolve pass {attempt}/12 heartbeat: recursive calls={call_count}; current={item_id}; stackDepth={len(stack)}"
                )
                next_heartbeat += heartbeat_calls
            if is_terminal_search_item(item_id):
                return terminal_resolved_candidate(item_id)
            if item_id in stack:
                return None
            candidates = raw_by_item.get(item_id, [])
            best: ResolvedCandidate | None = None

            for candidate in candidates:
                deps: list[str] = []
                total_cost = candidate_base_cost(candidate, raw_by_item, extra_penalties)
                failed = False

                for slot in candidate.slot_options:
                    if slot is None:
                        continue
                    best_option: str | None = None
                    best_option_cost: float | None = None
                    for option in slot:
                        resolved = choose_item(option, stack + (item_id,))
                        if resolved is None:
                            continue
                        option_cost = resolved.total_cost + dependency_penalty(item_id, option)
                        if best_option_cost is None or option_cost < best_option_cost or (
                            option_cost == best_option_cost and option < (best_option or option)
                        ):
                            best_option = option
                            best_option_cost = option_cost

                    if best_option is None or best_option_cost is None:
                        failed = True
                        break

                    deps.append(best_option)
                    total_cost += best_option_cost

                if failed:
                    continue

                resolved_candidate = ResolvedCandidate(
                    candidate_key=candidate.key,
                    item_id=item_id,
                    method=candidate.method,
                    deps=tuple(deps),
                    total_cost=total_cost,
                )
                if best is None or resolved_candidate.total_cost < best.total_cost:
                    best = resolved_candidate

            return best

        selected: dict[str, ResolvedCandidate] = {}
        for index, item_id in enumerate(all_items, start=1):
            resolved = choose_item(item_id, ())
            if resolved is not None:
                selected[item_id] = resolved
            if progress_every > 0 and (index % progress_every == 0 or index == total_items):
                log_progress(
                    f"Resolve pass {attempt}/12: evaluated {index}/{total_items} items; selected={len(selected)}"
                )

        cycles = find_cycles(selected)
        log_progress(f"Resolve pass {attempt}/12 complete: selected={len(selected)}; cycles={len(cycles)}")
        if not cycles:
            return selected, []
        for cycle in cycles:
            for item_id in cycle:
                candidate_key = selected[item_id].candidate_key
                extra_penalties[candidate_key] += 250.0

    log_progress("Resolve penalty retries exhausted; falling back to no-penalty resolution")
    selected: dict[str, ResolvedCandidate] = {}
    call_count = 0
    next_heartbeat = heartbeat_calls if heartbeat_calls > 0 else 0
    for index, item_id in enumerate(all_items, start=1):
        resolved, nested_calls = choose_item_without_penalty(raw_by_item, item_id, heartbeat_calls)
        call_count += nested_calls
        if heartbeat_calls > 0 and call_count >= next_heartbeat:
            log_progress(
                f"Fallback resolution heartbeat: recursive calls={call_count}; current={item_id}"
            )
            while next_heartbeat <= call_count:
                next_heartbeat += heartbeat_calls
        if resolved is not None:
            selected[item_id] = resolved
        if progress_every > 0 and (index % progress_every == 0 or index == total_items):
            log_progress(
                f"Fallback resolution: evaluated {index}/{total_items} items; selected={len(selected)}"
            )

    cycles = find_cycles(selected)
    log_progress(f"Fallback resolution complete: selected={len(selected)}; cycles={len(cycles)}")
    return selected, cycles


def choose_item_without_penalty(
    raw_by_item: dict[str, list[RawCandidate]],
    item_id: str,
    heartbeat_calls: int,
) -> tuple[ResolvedCandidate | None, int]:
    call_count = 0
    next_heartbeat = heartbeat_calls if heartbeat_calls > 0 else 0

    @lru_cache(maxsize=None)
    def choose_item(local_item_id: str, stack: tuple[str, ...]) -> ResolvedCandidate | None:
        nonlocal call_count, next_heartbeat
        call_count += 1
        if heartbeat_calls > 0 and call_count >= next_heartbeat:
            log_progress(
                f"Fallback resolution nested heartbeat: recursive calls={call_count}; current={local_item_id}; stackDepth={len(stack)}"
            )
            next_heartbeat += heartbeat_calls
        if is_terminal_search_item(local_item_id):
            return terminal_resolved_candidate(local_item_id)
        if local_item_id in stack:
            return None
        best: ResolvedCandidate | None = None
        for candidate in raw_by_item.get(local_item_id, []):
            deps: list[str] = []
            total_cost = candidate_base_cost(candidate, raw_by_item, {})
            failed = False
            for slot in candidate.slot_options:
                if slot is None:
                    continue
                best_option: str | None = None
                best_option_cost: float | None = None
                for option in slot:
                    resolved = choose_item(option, stack + (local_item_id,))
                    if resolved is None:
                        continue
                    option_cost = resolved.total_cost + dependency_penalty(local_item_id, option)
                    if best_option_cost is None or option_cost < best_option_cost:
                        best_option = option
                        best_option_cost = option_cost
                if best_option is None or best_option_cost is None:
                    failed = True
                    break
                deps.append(best_option)
                total_cost += best_option_cost
            if failed:
                continue
            resolved_candidate = ResolvedCandidate(candidate.key, local_item_id, candidate.method, tuple(deps), total_cost)
            if best is None or resolved_candidate.total_cost < best.total_cost:
                best = resolved_candidate
        return best

    return choose_item(item_id, ()), call_count


def find_cycles(selected: dict[str, ResolvedCandidate]) -> list[list[str]]:
    visited: set[str] = set()
    on_stack: set[str] = set()
    stack: list[str] = []
    index_by_item: dict[str, int] = {}
    cycles: list[list[str]] = []

    def visit(item_id: str) -> None:
        visited.add(item_id)
        on_stack.add(item_id)
        index_by_item[item_id] = len(stack)
        stack.append(item_id)

        for dependency in selected[item_id].deps:
            if dependency not in selected:
                continue
            if dependency not in visited:
                visit(dependency)
            elif dependency in on_stack:
                start = index_by_item[dependency]
                cycle = stack[start:].copy()
                if cycle and cycle not in cycles:
                    cycles.append(cycle)

        on_stack.remove(item_id)
        stack.pop()
        index_by_item.pop(item_id, None)

    for item_id in sorted(selected):
        if item_id not in visited:
            visit(item_id)

    return cycles


def topo_sort(selected: dict[str, ResolvedCandidate]) -> list[str]:
    indegree: dict[str, int] = {item_id: 0 for item_id in selected}
    children: dict[str, list[str]] = defaultdict(list)
    for item_id, resolved in selected.items():
        for dependency in resolved.deps:
            if dependency not in selected:
                continue
            indegree[item_id] += 1
            children[dependency].append(item_id)

    queue = deque(sorted(item_id for item_id, degree in indegree.items() if degree == 0))
    ordered: list[str] = []
    while queue:
        item_id = queue.popleft()
        ordered.append(item_id)
        for child in sorted(children[item_id]):
            indegree[child] -= 1
            if indegree[child] == 0:
                queue.append(child)

    remaining = sorted(item_id for item_id, degree in indegree.items() if degree > 0)
    ordered.extend(remaining)
    return ordered


def render_blocks(entry: dict[str, Any]) -> str:
    blocks = []
    for block in entry.get("mineBlocks", []):
        block_id = normalize_item_id(block.get("block"))
        if block_id:
            blocks.append(block_id)
    unique_blocks = sorted(dict.fromkeys(blocks))
    if not unique_blocks:
        raise ValueError(f"MINE entry for {entry.get('itemId')} has no mineBlocks")
    if len(unique_blocks) == 1:
        return block_expr(unique_blocks[0])
    return "new Block[]{" + ", ".join(block_expr(block_id) for block_id in unique_blocks) + "}"


def render_dimension_suffix(entry: dict[str, Any]) -> str:
    dimension = entry_dimension(entry)
    if dimension == "NETHER":
        return ".forceDimension(Dimension.NETHER)"
    if dimension == "END":
        return ".forceDimension(Dimension.END)"
    return ""


def mob_class_expr(entry: dict[str, Any]) -> str:
    mob_class = normalize_item_id(entry.get("mobClass"))
    if mob_class in MOB_CLASS_EXPR_OVERRIDES:
        return MOB_CLASS_EXPR_OVERRIDES[mob_class]
    return f"Entities.{java_constant(mob_class)}"


def infer_shaped_helper_call(
    item_id: str,
    output_expr: str,
    output_count: int,
    slot_names: list[str | None],
) -> str | None:
    if len(slot_names) == 4 and slot_names.count(None) == 0 and len(set(slot_names)) == 1:
        material = slot_names[0]
        if material is None:
            return None
        if output_count == 1:
            return f'shapedRecipe2x2Block({java_string(item_id)}, {output_expr}, {java_string(material)});'
        return f'shapedRecipe2x2Block({java_string(item_id)}, {output_expr}, {output_count}, {java_string(material)});'

    if len(slot_names) != 9:
        return None

    if slot_names.count(None) == 0 and len(set(slot_names)) == 1:
        material = slot_names[0]
        if material is not None:
            return f'shapedRecipe3x3Block({java_string(item_id)}, {output_expr}, {java_string(material)});'

    if item_id.endswith("_slab"):
        for row_start in (0, 3, 6):
            row = slot_names[row_start : row_start + 3]
            others = slot_names[:row_start] + slot_names[row_start + 3 :]
            if all(slot is None for slot in others) and len(set(row)) == 1:
                material = row[0]
                if material is not None:
                    return f'shapedRecipeSlab({java_string(item_id)}, {output_expr}, {java_string(material)});'

    if item_id.endswith("_stairs"):
        stairs_indices = [0, 3, 4, 6, 7, 8]
        if all(slot_names[index] is None for index in {1, 2, 5}) and len({slot_names[index] for index in stairs_indices}) == 1:
            material = slot_names[0]
            if material is not None:
                return f'shapedRecipeStairs({java_string(item_id)}, {output_expr}, {java_string(material)});'

    if item_id.endswith("_wall"):
        wall_indices = [0, 1, 2, 3, 4, 5]
        if slot_names[6:] == [None, None, None] and len({slot_names[index] for index in wall_indices}) == 1:
            material = slot_names[0]
            if material is not None:
                return f'shapedRecipeWall({java_string(item_id)}, {output_expr}, {java_string(material)});'

    return None


def direct_craft_call(item_id: str, output_expr: str, output_count: int, slot_names: list[str | None]) -> str:
    if len(slot_names) not in {4, 9}:
        raise ValueError(f"craft recipe for {item_id} has unexpected slot count {len(slot_names)}")

    slot_values = ["null" if slot_name is None else java_string(slot_name) for slot_name in slot_names]
    helper = "shapedRecipe2x2" if len(slot_names) == 4 else "shapedRecipe3x3"
    return (
        f'{helper}({java_string(item_id)}, {output_expr}, {output_count}, '
        + ", ".join(slot_values)
        + ");"
    )


def candidate_slot_names_for_helper(candidate: RawCandidate) -> list[str | None]:
    slot_names: list[str | None] = []
    for slot in candidate.slot_options:
        if slot is None:
            slot_names.append(None)
            continue

        catalogue_name = slot_catalogue_name(slot)
        if catalogue_name is not None:
            slot_names.append(catalogue_name)
            continue

        preferred = preferred_slot_option(slot)
        slot_names.append(synthetic_catalogue_name_for_item(preferred) or preferred)

    return slot_names


def helper_line_for_craft_candidate(candidate: RawCandidate) -> str | None:
    if candidate.method not in {"CRAFT_SHAPED_2x2", "CRAFT_SHAPED_3x3"}:
        return None

    return infer_shaped_helper_call(
        candidate.item_id,
        item_expr(candidate.item_id),
        int(candidate.entry.get("craftYield", 1)),
        candidate_slot_names_for_helper(candidate),
    )


def helper_candidate_dependency_names(candidate: RawCandidate) -> tuple[str, ...]:
    return tuple(slot_name for slot_name in candidate_slot_names_for_helper(candidate) if slot_name is not None)


def choose_helper_craft_line(item_id: str, raw_candidates: list[RawCandidate], available_names: set[str]) -> str | None:
    helper_candidates: list[tuple[tuple[float, int, str], str]] = []
    for candidate in raw_candidates:
        dependency_names = helper_candidate_dependency_names(candidate)
        if any(dependency_name not in available_names for dependency_name in dependency_names):
            continue
        line = helper_line_for_craft_candidate(candidate)
        if line is None:
            continue
        sort_key = (
            BASE_COSTS[candidate.method],
            -int(candidate.entry.get("craftYield", 1)),
            candidate.key,
        )
        helper_candidates.append((sort_key, line))

    if not helper_candidates:
        return None

    helper_candidates.sort(key=lambda pair: pair[0])
    return helper_candidates[0][1]


def source_item_ids(data: dict[str, Any]) -> set[str]:
    result: set[str] = set()
    for entry in data.get("entries", []):
        item_id = normalize_item_id(entry.get("itemId"))
        if item_id:
            result.add(item_id)
    return result


def choose_mob_entry(item_id: str, entries: list[dict[str, Any]]) -> dict[str, Any]:
    preferred_mob_class = PREFERRED_MOB_CLASSES.get(item_id)
    if preferred_mob_class is not None:
        for entry in entries:
            if normalize_item_id(entry.get("mobClass")) == preferred_mob_class:
                return entry

    return min(
        entries,
        key=lambda entry: (
            DIMENSION_COSTS.get(entry_dimension(entry) or "OVERWORLD", 0.0),
            normalize_item_id(entry.get("mobClass")),
        ),
    )


def warning_item_id(warning: str) -> str | None:
    if ": " not in warning:
        return None
    return warning.split(": ", 1)[0]


def build_helper_generation_plan(
    data: dict[str, Any],
    selected: dict[str, ResolvedCandidate],
    raw_by_item: dict[str, list[RawCandidate]],
    baseline: BaselineCatalogue | None = None,
) -> HelperGenerationPlan:
    lines: list[str] = []
    alias_lines: list[str] = []
    covered_items: set[str] = set()
    generated_items: set[str] = set()
    baseline_known_names = set() if baseline is None else set(baseline.known_names)
    available_names: set[str] = set(selected) | set(WOOD_AGGREGATE_KEYS) | baseline_known_names
    present_item_ids = source_item_ids(data)

    apply_semantic_overrides(present_item_ids, lines, covered_items, generated_items, available_names, baseline)

    wool_variants = {f"{color}_wool" for color in COLOR_PREFIXES}
    if wool_variants <= present_item_ids and not has_baseline_name_overlap(wool_variants | {"wool"}, baseline):
        lines.append('        simple("wool", ItemHelper.WOOL, CollectWoolTask::new);')
        lines.append('        colorfulTasks("wool", color -> color.wool, (color, count) -> new CollectWoolTask(color.color, count));')
        covered_items.update(wool_variants)
        generated_items.update(wool_variants | {"wool"})
        available_names.update(wool_variants | {"wool"})

    bed_variants = {f"{color}_bed" for color in COLOR_PREFIXES}
    if bed_variants <= present_item_ids and not has_baseline_name_overlap(bed_variants | {"bed"}, baseline):
        lines.append('        simple("bed", ItemHelper.BED, CollectBedTask::new);')
        lines.append('        colorfulTasks("bed", colors -> colors.bed, (colors, count) -> new CollectBedTask(colors.bed, colors.colorName + "_wool", count));')
        covered_items.update(bed_variants)
        generated_items.update(bed_variants | {"bed"})
        available_names.update(bed_variants | {"bed"})

    for entry in data.get("entries", []):
        if entry.get("obtainMethod") != "MINE":
            continue
        item_id = normalize_item_id(entry.get("itemId"))
        if baseline is not None and baseline.covers_item_id(item_id):
            continue
        mine_blocks = {normalize_item_id(block.get("block")) for block in entry.get("mineBlocks", []) if block.get("block")}
        if len(mine_blocks) != 1 or item_id not in {"carrot", "potato", "poisonous_potato", "beetroot", "beetroot_seeds"}:
            continue
        block_id = next(iter(mine_blocks))
        seed_item = CROP_BLOCK_SEEDS.get(block_id)
        if seed_item is None:
            continue
        line = f'        crop({java_string(item_id)}, {item_expr(item_id)}, {block_expr(block_id)}, {item_expr(seed_item)});'
        if line not in lines:
            lines.append(line)
        covered_items.add(item_id)
        generated_items.add(item_id)
        available_names.add(item_id)

    for item_id in sorted(selected):
        if item_id in covered_items or is_synthetic_wood_covered_item(item_id):
            continue
        if baseline is not None and baseline.covers_item_id(item_id):
            continue
        helper_line = choose_helper_craft_line(item_id, raw_by_item.get(item_id, []), available_names)
        if helper_line is None:
            continue
        lines.append(f"        {helper_line}")
        covered_items.add(item_id)
        generated_items.add(item_id)
        available_names.add(item_id)

    smelt_outputs_by_input: dict[str, list[str]] = defaultdict(list)
    for item_id, resolved in selected.items():
        if resolved.method in SMELT_METHODS and len(resolved.deps) == 1:
            smelt_outputs_by_input[resolved.deps[0]].append(item_id)

    mob_entries_by_item: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for entry in data.get("entries", []):
        if entry.get("obtainMethod") != "MOB_DROP":
            continue
        item_id = normalize_item_id(entry.get("itemId"))
        if item_id:
            mob_entries_by_item[item_id].append(entry)

    for item_id in sorted(mob_entries_by_item):
        resolved = selected.get(item_id)
        if resolved is None or resolved.method != "MOB_DROP":
            continue
        if item_id in covered_items:
            continue
        if baseline is not None and baseline.covers_item_id(item_id):
            continue
        entry = choose_mob_entry(item_id, mob_entries_by_item[item_id])
        if item_id == "blaze_rod":
            lines.append('        simple("blaze_rod", Items.BLAZE_ROD, CollectBlazeRodsTask::new).forceDimension(Dimension.NETHER);')
            covered_items.add(item_id)
            generated_items.add(item_id)
            available_names.add(item_id)
            continue
        cooked_outputs = sorted(smelt_outputs_by_input.get(item_id, []))
        if cooked_outputs:
            cooked_name = cooked_outputs[0]
            if has_baseline_name_overlap({item_id, cooked_name}, baseline):
                continue
            if cooked_name == f"cooked_{item_id}" and render_dimension_suffix(entry) == "":
                lines.append(
                    f'        mobCook({java_string(item_id)}, {item_expr(item_id)}, {item_expr(cooked_name)}, {mob_class_expr(entry)});'
                )
            else:
                lines.append(
                    f'        mob({java_string(item_id)}, {item_expr(item_id)}, {mob_class_expr(entry)}){render_dimension_suffix(entry)};'
                )
                lines.append(f'        smelt({java_string(cooked_name)}, {item_expr(cooked_name)}, {java_string(item_id)});')
            covered_items.update({item_id, cooked_name})
            generated_items.update({item_id, cooked_name})
            available_names.update({item_id, cooked_name})
            continue

        lines.append(f'        mob({java_string(item_id)}, {item_expr(item_id)}, {mob_class_expr(entry)}){render_dimension_suffix(entry)};')
        covered_items.add(item_id)
        generated_items.add(item_id)
        available_names.add(item_id)

    if "egg" in present_item_ids and not (baseline is not None and baseline.covers_name("egg")):
        lines.append('        simple("egg", Items.EGG, CollectEggsTask::new);')
        covered_items.add("egg")
        generated_items.add("egg")
        available_names.add("egg")

    if "flower" in present_item_ids and not (baseline is not None and baseline.covers_name("flower")):
        lines.append('        simple("flower", ItemHelper.FLOWER, CollectFlowerTask::new);')
        covered_items.add("flower")
        generated_items.add("flower")
        available_names.add("flower")

    if (
        "honeycomb" in present_item_ids or {"honeycomb_block", "honey_bottle"} & present_item_ids
    ) and not (baseline is not None and baseline.covers_name("honeycomb")):
        lines.append('        simple("honeycomb", Items.HONEYCOMB, CollectHoneycombTask::new);')
        covered_items.add("honeycomb")
        generated_items.add("honeycomb")
        available_names.add("honeycomb")

    if ("milk" in present_item_ids or "milk_bucket" in present_item_ids) and not (baseline is not None and baseline.covers_name("milk")):
        lines.append('        simple("milk", Items.MILK_BUCKET, CollectMilkTask::new);')
        covered_items.add("milk")
        generated_items.add("milk")
        available_names.add("milk")

    if "water_bucket" in present_item_ids and not (baseline is not None and baseline.covers_name("water_bucket")):
        lines.append('        simple("water_bucket", Items.WATER_BUCKET, CollectBucketLiquidTask.CollectWaterBucketTask::new);')
        covered_items.add("water_bucket")
        generated_items.add("water_bucket")
        available_names.add("water_bucket")

    if "lava_bucket" in present_item_ids and not (baseline is not None and baseline.covers_name("lava_bucket")):
        lines.append('        simple("lava_bucket", Items.LAVA_BUCKET, CollectBucketLiquidTask.CollectLavaBucketTask::new);')
        covered_items.add("lava_bucket")
        generated_items.add("lava_bucket")
        available_names.add("lava_bucket")

    if "netherite_upgrade_smithing_template" in present_item_ids and not (baseline is not None and baseline.covers_name("netherite_upgrade_smithing_template")):
        lines.append('        simple("netherite_upgrade_smithing_template", Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, GetSmithingTemplateTask::new);')
        covered_items.add("netherite_upgrade_smithing_template")
        generated_items.add("netherite_upgrade_smithing_template")
        available_names.add("netherite_upgrade_smithing_template")

    if {"brown_mushroom", "red_mushroom"} <= present_item_ids and not (baseline is not None and baseline.covers_name("mushroom")):
        lines.append('        mine("mushroom", MiningRequirement.HAND, new Block[]{Blocks.BROWN_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM, Blocks.RED_MUSHROOM_BLOCK}, Items.BROWN_MUSHROOM, Items.RED_MUSHROOM);')
        covered_items.add("mushroom")
        generated_items.add("mushroom")
        available_names.add("mushroom")

    special_recipe_lines = [
        ("shears", '        shapedRecipe2x2("shears", Items.SHEARS, 1, "iron_ingot", null, null, "iron_ingot");'),
        ("compass", '        shapedRecipe3x3("compass", Items.COMPASS, 1, null, "iron_ingot", null, "iron_ingot", "redstone", "iron_ingot", null, "iron_ingot", null);'),
        ("shield", '        shapedRecipe3x3("shield", Items.SHIELD, 1, "planks", "iron_ingot", "planks", "planks", "planks", "planks", null, "planks", null);'),
        ("clock", '        shapedRecipe3x3("clock", Items.CLOCK, 1, null, "gold_ingot", null, "gold_ingot", "redstone", "gold_ingot", null, "gold_ingot", null);'),
        ("map", '        shapedRecipe3x3("map", Items.MAP, 1, "paper", "paper", "paper", "paper", "compass", "paper", "paper", "paper", "paper");'),
        ("fishing_rod", '        shapedRecipe3x3("fishing_rod", Items.FISHING_ROD, 1, null, null, "stick", null, "stick", "string", "stick", null, "string");'),
        ("carrot_on_a_stick", '        shapedRecipe2x2("carrot_on_a_stick", Items.CARROT_ON_A_STICK, 1, "fishing_rod", "carrot", null, null);'),
        ("warped_fungus_on_a_stick", '        shapedRecipe2x2("warped_fungus_on_a_stick", Items.WARPED_FUNGUS_ON_A_STICK, 1, "fishing_rod", "warped_fungus", null, null);'),
        ("leather_horse_armor", '        shapedRecipe3x3("leather_horse_armor", Items.LEATHER_HORSE_ARMOR, 1, "leather", null, "leather", "leather", "leather", "leather", "leather", null, "leather");'),
        ("lead", '        shapedRecipe3x3("lead", Items.LEAD, 1, "string", "string", null, "string", "slime_ball", null, null, null, "string");'),
        ("minecart", '        shapedRecipe3x3("minecart", Items.MINECART, 1, null, null, null, "iron_ingot", null, "iron_ingot", "iron_ingot", "iron_ingot", "iron_ingot");'),
        ("armor_stand", '        shapedRecipe3x3("armor_stand", Items.ARMOR_STAND, 1, "stick", "stick", "stick", null, "stick", null, "stick", "smooth_stone_slab", "stick");'),
        ("iron_chain", '        shapedRecipe3x3("chain", registryItem("iron_chain"), 1, null, "iron_nugget", null, null, "iron_ingot", null, null, "iron_nugget", null);'),
        ("crossbow", '        shapedRecipe3x3("crossbow", Items.CROSSBOW, 1, "stick", "iron_ingot", "stick", "string", "tripwire_hook", "string", null, "stick", null);'),
        ("chest_minecart", '        shapedRecipe2x2("chest_minecart", Items.CHEST_MINECART, 1, "chest", null, "minecart", null);'),
        ("furnace_minecart", '        shapedRecipe2x2("furnace_minecart", Items.FURNACE_MINECART, 1, "furnace", null, "minecart", null);'),
        ("hopper_minecart", '        shapedRecipe2x2("hopper_minecart", Items.HOPPER_MINECART, 1, "hopper", null, "minecart", null);'),
        ("tnt_minecart", '        shapedRecipe2x2("tnt_minecart", Items.TNT_MINECART, 1, "tnt", null, "minecart", null);'),
        ("pumpkin_pie", '        shapedRecipe2x2("pumpkin_pie", Items.PUMPKIN_PIE, 1, "pumpkin", "sugar", null, "egg");'),
        ("cake", '        shapedRecipe3x3("cake", Items.CAKE, 1, "milk", "milk", "milk", "sugar", "egg", "sugar", "wheat", "wheat", "wheat").dontMineIfPresent();'),
    ]

    for item_id, line in special_recipe_lines:
        if item_id in present_item_ids:
            resource_name = "chain" if item_id == "iron_chain" else item_id
            if baseline is not None and baseline.covers_name(resource_name):
                continue
            lines.append(line)
            covered_items.add(resource_name)
            generated_items.add(resource_name)
            available_names.add(resource_name)

    for family_name, material_name, outputs in TOOL_FAMILIES:
        if set(outputs) <= present_item_ids and not has_baseline_name_overlap(set(outputs), baseline):
            lines.append(
                f'        tools({java_string(family_name)}, {java_string(material_name)}, '
                + ", ".join(item_expr(item_id) for item_id in outputs)
                + ');'
            )
            covered_items.update(outputs)
            generated_items.update(outputs)
            available_names.update(outputs)

    for family_name, material_name, outputs in ARMOR_FAMILIES:
        if set(outputs) <= present_item_ids and not has_baseline_name_overlap(set(outputs), baseline):
            lines.append(
                f'        armor({java_string(family_name)}, {java_string(material_name)}, '
                + ", ".join(item_expr(item_id) for item_id in outputs)
                + ');'
            )
            covered_items.update(outputs)
            generated_items.update(outputs)
            available_names.update(outputs)

    for entry in data.get("entries", []):
        if entry.get("obtainMethod") != "SMITH":
            continue
        item_id = normalize_item_id(entry.get("itemId"))
        if baseline is not None and baseline.covers_item_id(item_id):
            continue
        smith_base = tuple(normalize_item_id(value) for value in entry.get("smithBase", []) if value)
        smith_material = tuple(normalize_item_id(value) for value in entry.get("smithMaterial", []) if value)
        if not item_id or not smith_base or not smith_material:
            continue
        base_item = preferred_slot_option(smith_base) if len(smith_base) > 1 else smith_base[0]
        material_item = preferred_slot_option(smith_material) if len(smith_material) > 1 else smith_material[0]
        lines.append(
            f'        smith({java_string(item_id)}, {item_expr(item_id)}, {java_string(material_item)}, {java_string(base_item)});'
        )
        covered_items.add(item_id)
        generated_items.add(item_id)
        available_names.add(item_id)

    existing_explicit_names = set(baseline_known_names)
    existing_explicit_names.update({
        registration_name
        for registration_name in (
            explicit_registration_name(line)
            for line in [*WOOD_SYNTHETIC_PRELUDE, *lines, *alias_lines]
        )
        if registration_name is not None
    })

    for alias_name, original_name in STATIC_ALIASES:
        if alias_name in existing_explicit_names:
            continue
        if original_name in available_names:
            alias_lines.append(f'        alias({java_string(alias_name)}, {java_string(original_name)});')
            generated_items.add(alias_name)
            available_names.add(alias_name)
            existing_explicit_names.add(alias_name)

    return HelperGenerationPlan(
        lines=lines,
        alias_lines=alias_lines,
        covered_items=covered_items,
        generated_items=generated_items,
    )


def emit_candidate(resolved: ResolvedCandidate, raw_lookup: dict[str, RawCandidate]) -> str:
    candidate = raw_lookup[resolved.candidate_key]
    item_id = resolved.item_id
    if candidate.method == "MINE":
        return (
            f'mine({java_string(item_id)}, {mining_requirement_expr(candidate.entry)}, '
            f'{render_blocks(candidate.entry)}, {item_expr(item_id)}){render_dimension_suffix(candidate.entry)};'
        )

    if candidate.method == "MOB_DROP":
        return f'mob({java_string(item_id)}, {item_expr(item_id)}, {mob_class_expr(candidate.entry)}){render_dimension_suffix(candidate.entry)};'

    if candidate.method in SMELT_METHODS:
        sources = [normalize_item_id(value) for value in candidate.entry.get("smeltFrom", []) if value]
        primary = resolved.deps[0]
        optional = [source for source in sources if source != primary]
        primary_catalogue = synthetic_catalogue_name_for_item(primary) or primary
        optional_args = ""
        if optional:
            optional_args = ", " + ", ".join(item_expr(source) for source in optional)
        return f'smelt({java_string(item_id)}, {item_expr(item_id)}, {java_string(primary_catalogue)}{optional_args});'

    if candidate.method == "SMITH":
        base_item, material_item = resolved.deps
        return f'smith({java_string(item_id)}, {item_expr(item_id)}, {java_string(material_item)}, {java_string(base_item)});'

    slot_names: list[str | None] = []
    dependency_index = 0
    for slot in candidate.slot_options:
        if slot is None:
            slot_names.append(None)
            continue
        catalogue_name = slot_catalogue_name(slot)
        if catalogue_name is not None:
            slot_names.append(catalogue_name)
        else:
            dependency_name = resolved.deps[dependency_index]
            slot_names.append(synthetic_catalogue_name_for_item(dependency_name) or dependency_name)
        dependency_index += 1

    output_count = int(candidate.entry.get("craftYield", 1))
    if candidate.method != "CRAFT_SHAPELESS":
        helper_call = infer_shaped_helper_call(
            item_id,
            item_expr(item_id),
            output_count,
            slot_names,
        )
        if helper_call is not None:
            return helper_call

    return direct_craft_call(item_id, item_expr(item_id), output_count, slot_names)


def extract_template_parts(template_text: str) -> tuple[str, str]:
    static_start = template_text.find("    static {\n")
    helper_start = template_text.find("\n    private static CataloguedResource put(")
    if static_start == -1 or helper_start == -1 or helper_start <= static_start:
        raise ValueError("Template does not look like an Emmaclef TaskCatalogue.java file")
    return template_text[:static_start], template_text[helper_start:]


def find_static_block_bounds(template_text: str) -> tuple[int, int]:
    marker = "static {"
    start = template_text.find(marker)
    if start == -1:
        raise ValueError("Template does not look like an Emmaclef TaskCatalogue.java file")

    brace_index = template_text.find("{", start)
    depth = 0
    in_string = False
    in_char = False
    escape = False

    for index in range(brace_index, len(template_text)):
        ch = template_text[index]
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            continue
        if in_char:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == "'":
                in_char = False
            continue
        if ch == '"':
            in_string = True
            continue
        if ch == "'":
            in_char = True
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return brace_index, index

    raise ValueError("Unterminated static block in template")


def assert_no_baseline_name_collisions(lines: list[str], baseline: BaselineCatalogue | None) -> None:
    if baseline is None:
        return

    collisions = sorted(
        {
            registration_name
            for registration_name in (explicit_registration_name(line) for line in lines)
            if registration_name is not None and baseline.covers_name(registration_name)
        }
    )
    if collisions:
        raise ValueError(
            "append-only generation attempted to overwrite existing catalogue names: "
            + ", ".join(collisions[:10])
        )


def render_addition_lines(
    ordered_items: list[str],
    selected: dict[str, ResolvedCandidate],
    raw_lookup: dict[str, RawCandidate],
    source_json: Path,
    skipped_methods: Counter,
    parse_warnings: list[str],
    unresolved_items: list[str],
    helper_plan: HelperGenerationPlan,
    baseline: BaselineCatalogue | None,
) -> list[str]:
    new_parse_warnings = [
        warning
        for warning in parse_warnings
        if (warning_item_id(warning) is None or baseline is None or not baseline.covers_item_id(warning_item_id(warning)))
    ]
    emitted_items = [
        item_id
        for item_id in ordered_items
        if not is_synthetic_wood_covered_item(item_id)
        and item_id not in helper_plan.covered_items
        and (baseline is None or not baseline.covers_item_id(item_id))
    ]
    new_unresolved_items = [
        item_id
        for item_id in unresolved_items
        if baseline is None or not baseline.covers_item_id(item_id)
    ]

    if not helper_plan.lines and not helper_plan.alias_lines and not emitted_items:
        return []

    lines = [
        "        // AUTO-GENERATED canonical additions by tools/generate_task_catalogue.py.",
        "        // Existing TaskCatalogue entries are preserved and treated as the canonical baseline.",
        f"        // Source JSON: {source_json.as_posix()}",
        f"        // Preserved baseline names: {0 if baseline is None else len(baseline.known_names)}",
        f"        // Appended helper-generated items: {len(helper_plan.generated_items)}",
        f"        // Appended JSON-driven items: {len(emitted_items)}",
        f"        // Unresolved new items: {len(new_unresolved_items)}",
    ]
    if skipped_methods:
        summary = ", ".join(f"{method}={count}" for method, count in sorted(skipped_methods.items()))
        lines.append(f"        // Skipped entry families: {summary}")
    if new_parse_warnings:
        lines.append("        // Some new entries were skipped during parsing; see the report JSON for details.")
    lines.append("")

    lines.extend(helper_plan.lines)
    if helper_plan.lines:
        lines.append("")

    for item_id in emitted_items:
        lines.append("        " + emit_candidate(selected[item_id], raw_lookup))

    if helper_plan.alias_lines:
        lines.append("")
        lines.extend(helper_plan.alias_lines)

    assert_no_duplicate_explicit_registrations(lines)
    assert_no_baseline_name_collisions(lines, baseline)
    return lines


def append_additions_to_template(template_text: str, addition_lines: list[str]) -> str:
    if not addition_lines:
        return template_text

    _, static_end = find_static_block_bounds(template_text)
    insert_at = template_text.rfind("\n", 0, static_end) + 1
    prefix = template_text[:insert_at]
    separator = "" if prefix.endswith("\n\n") else "\n"
    addition_text = "\n".join(addition_lines) + "\n"
    return prefix + separator + addition_text + template_text[insert_at:]


def render_static_block(
    ordered_items: list[str],
    selected: dict[str, ResolvedCandidate],
    raw_lookup: dict[str, RawCandidate],
    source_json: Path,
    skipped_methods: Counter,
    parse_warnings: list[str],
    unresolved_items: list[str],
    helper_plan: HelperGenerationPlan,
) -> str:
    synthetic_selected = [item_id for item_id in ordered_items if is_synthetic_wood_covered_item(item_id)]
    emitted_items = [
        item_id
        for item_id in ordered_items
        if not is_synthetic_wood_covered_item(item_id) and item_id not in helper_plan.covered_items
    ]
    lines = [
        "    static {",
        "        // AUTO-GENERATED by tools/generate_task_catalogue.py.",
        f"        // Source JSON: {source_json.as_posix()}",
        f"        // JSON-resolved items: {len(selected)}",
        f"        // Helper-generated items: {len(helper_plan.generated_items)}",
        f"        // Synthetic wood-covered items: {len(synthetic_selected)}",
        f"        // Emitted JSON-driven items: {len(emitted_items)}",
        f"        // Unresolved items: {len(unresolved_items)}",
    ]
    if skipped_methods:
        summary = ", ".join(f"{method}={count}" for method, count in sorted(skipped_methods.items()))
        lines.append(f"        // Skipped entry families: {summary}")
    if parse_warnings:
        lines.append("        // Some entries were skipped during parsing; see the report JSON for details.")
    lines.append("")
    lines.extend(WOOD_SYNTHETIC_PRELUDE)
    lines.append("")
    lines.extend(helper_plan.lines)
    if helper_plan.lines:
        lines.append("")

    for item_id in ordered_items:
        if is_synthetic_wood_covered_item(item_id) or item_id in helper_plan.covered_items:
            continue
        lines.append("        " + emit_candidate(selected[item_id], raw_lookup))

    if helper_plan.alias_lines:
        lines.append("")
        lines.extend(helper_plan.alias_lines)

    assert_no_duplicate_explicit_registrations(lines)

    lines.append("    }")
    lines.append("")
    return "\n".join(lines)


def build_report(
    source_json: Path,
    selected: dict[str, ResolvedCandidate],
    unresolved_items: list[str],
    skipped_methods: Counter,
    parse_warnings: list[str],
    cycles: list[list[str]],
    helper_plan: HelperGenerationPlan,
) -> dict[str, Any]:
    synthetic_covered = sorted(item_id for item_id in selected if is_synthetic_wood_covered_item(item_id))
    return {
        "sourceJson": str(source_json),
        "resolvedItemCount": len(selected),
        "helperGeneratedItemCount": len(helper_plan.generated_items),
        "helperGeneratedItems": sorted(helper_plan.generated_items),
        "syntheticWoodCoveredCount": len(synthetic_covered),
        "syntheticWoodCoveredItems": synthetic_covered,
        "unresolvedItemCount": len(unresolved_items),
        "skippedMethods": dict(sorted(skipped_methods.items())),
        "parseWarnings": parse_warnings,
        "cycles": cycles,
        "resolvedItems": sorted(selected),
        "unresolvedItems": unresolved_items,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate deterministic canonical TaskCatalogue additions from Emma extractor JSON while preserving the existing handwritten catalogue as the baseline."
    )
    parser.add_argument(
        "--file",
        default=str(DEFAULT_ROOT_PATH / "emma_extracted_recipes.json"),
        help="Path to the extractor JSON file.",
    )
    parser.add_argument("--template", required=True, help="Path to the existing Emmaclef TaskCatalogue.java template.")
    parser.add_argument("--output", required=True, help="Path where the generated TaskCatalogue.java should be written.")
    parser.add_argument("--report", help="Optional path for a JSON generation report.")
    parser.add_argument(
        "--progress-every",
        type=int,
        default=250,
        help="Print progress after every N parsed or resolved items. Use 0 to disable periodic progress logging.",
    )
    parser.add_argument(
        "--heartbeat-calls",
        type=int,
        default=20000,
        help="Print a resolver heartbeat after this many recursive dependency calls. Use 0 to disable recursive heartbeats.",
    )
    args = parser.parse_args()

    source_json = Path(args.file).resolve()
    template_path = Path(args.template).resolve()
    output_path = Path(args.output).resolve()
    report_path = Path(args.report).resolve() if args.report else None

    log_progress(f"Loading extractor JSON from {source_json}")
    data = json.loads(source_json.read_text(encoding="utf-8"))
    extractor_version = int(data.get("extractionMeta", {}).get("extractorVersion", 0))
    log_progress(
        f"Loaded extractorVersion {extractor_version} with {len(data.get('entries', []))} entries"
    )
    if extractor_version < 3:
        raise SystemExit(
            "Extractor JSON is older than version 3. Re-run /emma_extract with the updated extractor before generating TaskCatalogue.java."
        )

    raw_by_item, skipped_methods, parse_warnings = parse_candidates(
        data,
        args.progress_every,
    )
    log_progress(
        f"Parsed candidates for {len(raw_by_item)} distinct items; warnings={len(parse_warnings)}; skipped={sum(skipped_methods.values())}"
    )
    selected, cycles = resolve_candidates(raw_by_item, args.progress_every, args.heartbeat_calls)
    if cycles:
        parse_warnings.append(f"Unresolved cycles remained after penalty retries: {cycles}")

    log_progress(f"Reading template from {template_path}")
    template_text = template_path.read_text(encoding="utf-8")
    baseline_catalogue = load_baseline_catalogue(template_path)

    helper_plan = build_helper_generation_plan(data, selected, raw_by_item, baseline_catalogue)
    parse_warnings = [
        warning
        for warning in parse_warnings
        if (
            warning_item_id(warning) is None
            or (
                warning_item_id(warning) not in helper_plan.covered_items
                and not baseline_catalogue.covers_item_id(warning_item_id(warning))
            )
        )
    ]
    unresolved_items = sorted(
        item_id
        for item_id in raw_by_item
        if (
            item_id not in selected
            and item_id not in helper_plan.covered_items
            and not baseline_catalogue.covers_item_id(item_id)
        )
    )

    raw_lookup = {
        candidate.key: candidate
        for candidates in raw_by_item.values()
        for candidate in candidates
    }

    log_progress(f"Topologically ordering {len(selected)} resolved items")
    ordered_items = topo_sort(selected)
    log_progress("Rendering generated TaskCatalogue.java")
    addition_lines = render_addition_lines(
        ordered_items,
        selected,
        raw_lookup,
        source_json,
        skipped_methods,
        parse_warnings,
        unresolved_items,
        helper_plan,
        baseline_catalogue,
    )
    generated_text = append_additions_to_template(template_text, addition_lines)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    log_progress(f"Writing generated Java to {output_path}")
    output_path.write_text(generated_text, encoding="utf-8")

    report = build_report(source_json, selected, unresolved_items, skipped_methods, parse_warnings, cycles, helper_plan)
    if report_path is not None:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        log_progress(f"Writing JSON report to {report_path}")
        report_path.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")

    appended_item_count = len(
        {
            *helper_plan.generated_items,
            *[
                item_id
                for item_id in ordered_items
                if item_id not in helper_plan.covered_items and not baseline_catalogue.covers_item_id(item_id)
            ],
        }
    )
    print(f"Appended {appended_item_count} canonical catalogue items to {output_path}")
    print(f"Unresolved items: {len(unresolved_items)}")
    if skipped_methods:
        summary = ", ".join(f"{method}={count}" for method, count in sorted(skipped_methods.items()))
        print(f"Skipped entry families: {summary}")
    if parse_warnings:
        print(f"Warnings: {len(parse_warnings)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())