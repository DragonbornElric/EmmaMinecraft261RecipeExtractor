#!/usr/bin/env python3
"""
Seed data for Emma's Minecraft block knowledge.

Populates block_resources (how to obtain blocks) and block_substitutions
(valid material swaps). Called once during first initialization.

All block names use minecraft: namespace format.
"""

from gamer.build_db import BuildDB

# ── Wood Variants ─────────────────────────────────────────────

WOOD_VARIANTS = [
    "minecraft:oak", "minecraft:spruce", "minecraft:birch",
    "minecraft:jungle", "minecraft:acacia", "minecraft:dark_oak",
    "minecraft:mangrove", "minecraft:cherry", "minecraft:crimson",
    "minecraft:warped",
]

# Block suffixes that apply to each wood variant
WOOD_SUFFIXES = ["_planks", "_log", "_slab", "_stairs", "_fence", "_fence_gate", "_door", "_trapdoor"]

# ── Stone Variants ────────────────────────────────────────────

STONE_VARIANTS = [
    "minecraft:stone", "minecraft:cobblestone", "minecraft:stone_bricks",
    "minecraft:mossy_stone_bricks", "minecraft:deepslate",
    "minecraft:cobbled_deepslate", "minecraft:deepslate_bricks",
    "minecraft:andesite", "minecraft:diorite", "minecraft:granite",
    "minecraft:tuff", "minecraft:blackstone",
]

STONE_SUFFIXES = ["", "_slab", "_stairs", "_wall"]

# ── Decorative Variants ──────────────────────────────────────

GLASS_VARIANTS = [
    "minecraft:glass", "minecraft:tinted_glass",
    "minecraft:white_stained_glass", "minecraft:light_gray_stained_glass",
    "minecraft:gray_stained_glass", "minecraft:black_stained_glass",
    "minecraft:brown_stained_glass", "minecraft:red_stained_glass",
    "minecraft:orange_stained_glass", "minecraft:yellow_stained_glass",
    "minecraft:lime_stained_glass", "minecraft:green_stained_glass",
    "minecraft:cyan_stained_glass", "minecraft:light_blue_stained_glass",
    "minecraft:blue_stained_glass", "minecraft:purple_stained_glass",
    "minecraft:magenta_stained_glass", "minecraft:pink_stained_glass",
]

CONCRETE_VARIANTS = [
    f"minecraft:{c}_concrete" for c in [
        "white", "light_gray", "gray", "black", "brown", "red",
        "orange", "yellow", "lime", "green", "cyan", "light_blue",
        "blue", "purple", "magenta", "pink",
    ]
]

TERRACOTTA_VARIANTS = [
    "minecraft:terracotta",
] + [f"minecraft:{c}_terracotta" for c in [
    "white", "light_gray", "gray", "black", "brown", "red",
    "orange", "yellow", "lime", "green", "cyan", "light_blue",
    "blue", "purple", "magenta", "pink",
]]

WOOL_VARIANTS = [
    f"minecraft:{c}_wool" for c in [
        "white", "light_gray", "gray", "black", "brown", "red",
        "orange", "yellow", "lime", "green", "cyan", "light_blue",
        "blue", "purple", "magenta", "pink",
    ]
]


# ── Legacy Numeric ID Mapping ────────────────────────────────

LEGACY_BLOCK_IDS = {
    "0": "minecraft:air", "1": "minecraft:stone", "2": "minecraft:grass_block",
    "3": "minecraft:dirt", "4": "minecraft:cobblestone", "5": "minecraft:oak_planks",
    "6": "minecraft:oak_sapling", "7": "minecraft:bedrock", "8": "minecraft:water",
    "9": "minecraft:water", "10": "minecraft:lava", "11": "minecraft:lava",
    "12": "minecraft:sand", "13": "minecraft:gravel", "14": "minecraft:gold_ore",
    "15": "minecraft:iron_ore", "16": "minecraft:coal_ore", "17": "minecraft:oak_log",
    "18": "minecraft:oak_leaves", "19": "minecraft:sponge", "20": "minecraft:glass",
    "21": "minecraft:lapis_ore", "22": "minecraft:lapis_block",
    "23": "minecraft:dispenser", "24": "minecraft:sandstone",
    "25": "minecraft:note_block", "27": "minecraft:powered_rail",
    "28": "minecraft:detector_rail", "29": "minecraft:sticky_piston",
    "30": "minecraft:cobweb", "31": "minecraft:short_grass",
    "33": "minecraft:piston", "35": "minecraft:white_wool",
    "37": "minecraft:dandelion", "38": "minecraft:poppy",
    "39": "minecraft:brown_mushroom", "40": "minecraft:red_mushroom",
    "41": "minecraft:gold_block", "42": "minecraft:iron_block",
    "43": "minecraft:smooth_stone_slab", "44": "minecraft:smooth_stone_slab",
    "45": "minecraft:bricks", "46": "minecraft:tnt",
    "47": "minecraft:bookshelf", "48": "minecraft:mossy_cobblestone",
    "49": "minecraft:obsidian", "50": "minecraft:torch",
    "52": "minecraft:spawner", "53": "minecraft:oak_stairs",
    "54": "minecraft:chest", "56": "minecraft:diamond_ore",
    "57": "minecraft:diamond_block", "58": "minecraft:crafting_table",
    "60": "minecraft:farmland", "61": "minecraft:furnace",
    "65": "minecraft:ladder", "66": "minecraft:rail",
    "67": "minecraft:cobblestone_stairs", "69": "minecraft:lever",
    "70": "minecraft:stone_pressure_plate", "72": "minecraft:oak_pressure_plate",
    "73": "minecraft:redstone_ore", "76": "minecraft:redstone_torch",
    "77": "minecraft:stone_button", "78": "minecraft:snow",
    "79": "minecraft:ice", "80": "minecraft:snow_block",
    "81": "minecraft:cactus", "82": "minecraft:clay",
    "84": "minecraft:jukebox", "85": "minecraft:oak_fence",
    "86": "minecraft:pumpkin", "87": "minecraft:netherrack",
    "88": "minecraft:soul_sand", "89": "minecraft:glowstone",
    "91": "minecraft:jack_o_lantern", "95": "minecraft:white_stained_glass",
    "96": "minecraft:oak_trapdoor", "97": "minecraft:infested_stone",
    "98": "minecraft:stone_bricks", "99": "minecraft:brown_mushroom_block",
    "100": "minecraft:red_mushroom_block", "101": "minecraft:iron_bars",
    "102": "minecraft:glass_pane", "103": "minecraft:melon",
    "106": "minecraft:vine", "107": "minecraft:oak_fence_gate",
    "108": "minecraft:brick_stairs", "109": "minecraft:stone_brick_stairs",
    "110": "minecraft:mycelium", "111": "minecraft:lily_pad",
    "112": "minecraft:nether_bricks", "113": "minecraft:nether_brick_fence",
    "114": "minecraft:nether_brick_stairs", "116": "minecraft:enchanting_table",
    "120": "minecraft:end_portal_frame", "121": "minecraft:end_stone",
    "123": "minecraft:redstone_lamp", "126": "minecraft:oak_slab",
    "128": "minecraft:sandstone_stairs", "129": "minecraft:emerald_ore",
    "130": "minecraft:ender_chest", "133": "minecraft:emerald_block",
    "134": "minecraft:spruce_stairs", "135": "minecraft:birch_stairs",
    "136": "minecraft:jungle_stairs", "137": "minecraft:command_block",
    "138": "minecraft:beacon", "139": "minecraft:cobblestone_wall",
    "143": "minecraft:oak_button", "145": "minecraft:anvil",
    "146": "minecraft:trapped_chest", "148": "minecraft:heavy_weighted_pressure_plate",
    "149": "minecraft:comparator", "151": "minecraft:daylight_detector",
    "152": "minecraft:redstone_block", "153": "minecraft:nether_quartz_ore",
    "154": "minecraft:hopper", "155": "minecraft:quartz_block",
    "156": "minecraft:quartz_stairs", "157": "minecraft:activator_rail",
    "158": "minecraft:dropper", "159": "minecraft:white_terracotta",
    "160": "minecraft:white_stained_glass_pane", "161": "minecraft:acacia_leaves",
    "162": "minecraft:acacia_log", "163": "minecraft:acacia_stairs",
    "164": "minecraft:dark_oak_stairs", "165": "minecraft:slime_block",
    "166": "minecraft:barrier", "167": "minecraft:iron_trapdoor",
    "168": "minecraft:prismarine", "169": "minecraft:sea_lantern",
    "170": "minecraft:hay_block", "172": "minecraft:terracotta",
    "173": "minecraft:coal_block", "174": "minecraft:packed_ice",
    "175": "minecraft:sunflower", "179": "minecraft:red_sandstone",
    "180": "minecraft:red_sandstone_stairs", "181": "minecraft:red_sandstone_slab",
    "183": "minecraft:spruce_fence_gate", "184": "minecraft:birch_fence_gate",
    "185": "minecraft:jungle_fence_gate", "186": "minecraft:dark_oak_fence_gate",
    "187": "minecraft:acacia_fence_gate", "188": "minecraft:spruce_fence",
    "189": "minecraft:birch_fence", "190": "minecraft:jungle_fence",
    "191": "minecraft:dark_oak_fence", "192": "minecraft:acacia_fence",
    "193": "minecraft:spruce_door", "194": "minecraft:birch_door",
    "195": "minecraft:jungle_door", "196": "minecraft:acacia_door",
    "197": "minecraft:dark_oak_door",
}

# String-based block renames across Minecraft versions (1.13 Flattening + later).
# Mojang publishes these in each version's data-pack changelog.
RENAMED_BLOCK_IDS: dict[str, str] = {
    # 1.13  Flattening — common string renames
    "minecraft:grass":                 "minecraft:short_grass",
    "minecraft:tallgrass":             "minecraft:short_grass",
    "minecraft:deadbush":              "minecraft:dead_bush",
    "minecraft:waterlily":             "minecraft:lily_pad",
    "minecraft:snow_layer":            "minecraft:snow",
    "minecraft:reeds":                 "minecraft:sugar_cane",
    "minecraft:web":                   "minecraft:cobweb",
    "minecraft:noteblock":             "minecraft:note_block",
    "minecraft:wooden_slab":           "minecraft:oak_slab",
    "minecraft:stone_stairs":          "minecraft:cobblestone_stairs",
    "minecraft:lit_furnace":           "minecraft:furnace",
    "minecraft:lit_redstone_lamp":     "minecraft:redstone_lamp",
    "minecraft:unlit_redstone_torch":  "minecraft:redstone_torch",
    "minecraft:powered_repeater":      "minecraft:repeater",
    "minecraft:unpowered_repeater":    "minecraft:repeater",
    "minecraft:powered_comparator":    "minecraft:comparator",
    "minecraft:unpowered_comparator":  "minecraft:comparator",
    "minecraft:piston_head":           "minecraft:piston_head",
    "minecraft:mob_spawner":           "minecraft:spawner",
    "minecraft:fence":                 "minecraft:oak_fence",
    "minecraft:fence_gate":            "minecraft:oak_fence_gate",
    "minecraft:wooden_door":           "minecraft:oak_door",
    "minecraft:trapdoor":              "minecraft:oak_trapdoor",
    "minecraft:wooden_button":         "minecraft:oak_button",
    "minecraft:wooden_pressure_plate": "minecraft:oak_pressure_plate",
    "minecraft:planks":                "minecraft:oak_planks",
    "minecraft:log":                   "minecraft:oak_log",
    "minecraft:log2":                  "minecraft:acacia_log",
    "minecraft:leaves":                "minecraft:oak_leaves",
    "minecraft:leaves2":               "minecraft:acacia_leaves",
    "minecraft:wool":                  "minecraft:white_wool",
    "minecraft:stained_hardened_clay": "minecraft:white_terracotta",
    "minecraft:stained_glass":         "minecraft:white_stained_glass",
    "minecraft:stained_glass_pane":    "minecraft:white_stained_glass_pane",
    "minecraft:carpet":                "minecraft:white_carpet",
    "minecraft:concrete":              "minecraft:white_concrete",
    "minecraft:concrete_powder":       "minecraft:white_concrete_powder",
    "minecraft:dye":                   "minecraft:white_dye",
    "minecraft:silver_shulker_box":    "minecraft:light_gray_shulker_box",
    "minecraft:silver_glazed_terracotta": "minecraft:light_gray_glazed_terracotta",
    "minecraft:stonebrick":            "minecraft:stone_bricks",
    "minecraft:end_bricks":            "minecraft:end_stone_bricks",
    "minecraft:red_nether_brick":      "minecraft:red_nether_bricks",
    "minecraft:magma":                 "minecraft:magma_block",
    "minecraft:nether_wart_block":     "minecraft:nether_wart_block",
    "minecraft:brick_block":           "minecraft:bricks",
    "minecraft:golden_rail":           "minecraft:powered_rail",
    "minecraft:lit_pumpkin":           "minecraft:jack_o_lantern",
    "minecraft:monster_egg":           "minecraft:infested_stone",

    # 1.17 — Caves & Cliffs renames
    "minecraft:grass_path":            "minecraft:dirt_path",

    # 1.19.3 — wool/sign splits
    "minecraft:sign":                  "minecraft:oak_sign",
    "minecraft:wall_sign":             "minecraft:oak_wall_sign",

    # 1.20 — bamboo & cherry additions (old mods sometimes pre-used these)
    "minecraft:grass_block":           "minecraft:grass_block",  # no-op, kept for completeness

    # 1.20.3 — short_grass rename finalised
    "minecraft:tall_grass":            "minecraft:tall_grass",  # no-op, kept for completeness
}


def normalize_block_name(raw: str) -> str:
    """Map legacy numeric IDs, old string names, or bare names to current minecraft: namespace."""
    if raw in LEGACY_BLOCK_IDS:
        return LEGACY_BLOCK_IDS[raw]
    if ":" not in raw:
        raw = f"minecraft:{raw}"
    if raw in RENAMED_BLOCK_IDS:
        return RENAMED_BLOCK_IDS[raw]
    return raw


# ── Seed Functions ────────────────────────────────────────────

def seed_block_substitutions(db: BuildDB):
    """Insert common material swap rules for all variant groups."""

    def _insert_variant_group(variants: list[str], reason: str = "aesthetic",
                              context: str = "any"):
        """For each block in the group, every other block is a substitute."""
        for i, original in enumerate(variants):
            priority = 1
            for j, substitute in enumerate(variants):
                if i == j:
                    continue
                db.add_substitution(original, substitute, priority, context, reason)
                priority += 1

    # Wood planks substitutions
    wood_planks = [f"{w}_planks" for w in WOOD_VARIANTS]
    _insert_variant_group(wood_planks, "aesthetic")

    # Wood logs
    wood_logs = [f"{w}_log" for w in WOOD_VARIANTS if "crimson" not in w and "warped" not in w]
    nether_logs = ["minecraft:crimson_stem", "minecraft:warped_stem"]
    _insert_variant_group(wood_logs, "aesthetic")

    # Wood slabs
    wood_slabs = [f"{w}_slab" for w in WOOD_VARIANTS]
    _insert_variant_group(wood_slabs, "aesthetic")

    # Wood stairs
    wood_stairs = [f"{w}_stairs" for w in WOOD_VARIANTS]
    _insert_variant_group(wood_stairs, "aesthetic")

    # Wood fences
    wood_fences = [f"{w}_fence" for w in WOOD_VARIANTS]
    _insert_variant_group(wood_fences, "aesthetic")

    # Wood doors
    wood_doors = [f"{w}_door" for w in WOOD_VARIANTS]
    _insert_variant_group(wood_doors, "aesthetic")

    # Stone variants
    _insert_variant_group(STONE_VARIANTS, "aesthetic")

    # Stone slabs
    stone_slabs = [f"{s}_slab" for s in STONE_VARIANTS if s != "minecraft:stone"]
    stone_slabs.insert(0, "minecraft:stone_slab")
    _insert_variant_group(stone_slabs, "aesthetic")

    # Stone stairs
    stone_stairs = [f"{s}_stairs" for s in STONE_VARIANTS if s != "minecraft:stone"]
    stone_stairs.insert(0, "minecraft:stone_stairs")
    _insert_variant_group(stone_stairs, "aesthetic")

    # Glass
    _insert_variant_group(GLASS_VARIANTS, "aesthetic")

    # Concrete
    _insert_variant_group(CONCRETE_VARIANTS, "aesthetic")

    # Terracotta
    _insert_variant_group(TERRACOTTA_VARIANTS, "aesthetic")

    # Wool
    _insert_variant_group(WOOL_VARIANTS, "aesthetic")

    # Context-specific: medieval builds prefer stone over concrete
    for stone in STONE_VARIANTS:
        for concrete in CONCRETE_VARIANTS:
            db.add_substitution(concrete, stone, 5, "medieval", "availability")

    # Context-specific: modern builds allow quartz/concrete
    db.add_substitution("minecraft:stone", "minecraft:quartz_block", 3, "modern", "aesthetic")
    db.add_substitution("minecraft:stone", "minecraft:smooth_quartz", 4, "modern", "aesthetic")
    for concrete in CONCRETE_VARIANTS[:4]:  # white, light_gray, gray, black
        db.add_substitution("minecraft:stone", concrete, 5, "modern", "aesthetic")


def seed_block_resources(db: BuildDB):
    """Insert crafting/mining/smelting data for common blocks."""

    # ── Wood (all variants) ──
    for wood in WOOD_VARIANTS:
        stem = "stem" if "crimson" in wood or "warped" in wood else "log"
        log = f"{wood}_{stem}"
        planks = f"{wood}_planks"
        slab = f"{wood}_slab"
        stairs = f"{wood}_stairs"
        fence = f"{wood}_fence"
        fence_gate = f"{wood}_fence_gate"
        door = f"{wood}_door"
        trapdoor = f"{wood}_trapdoor"

        # Logs — mine trees
        db.add_resource(log, "mine", time_cost=3.0, resource_cost=1.0)

        # Planks — craft from logs (1 log → 4 planks)
        db.add_resource(planks, "craft",
                        ingredients=[{"block": log, "qty": 1}],
                        output_qty=4, time_cost=0.5, resource_cost=0.25)

        # Slabs — craft from planks (3 planks → 6 slabs)
        db.add_resource(slab, "craft",
                        ingredients=[{"block": planks, "qty": 3}],
                        output_qty=6, time_cost=0.5, resource_cost=0.5)

        # Stairs — craft from planks (6 planks → 4 stairs)
        db.add_resource(stairs, "craft",
                        ingredients=[{"block": planks, "qty": 6}],
                        output_qty=4, time_cost=0.5, resource_cost=1.5)

        # Fence — craft from planks + sticks (4 planks + 2 sticks → 3 fences)
        db.add_resource(fence, "craft",
                        ingredients=[{"block": planks, "qty": 4},
                                     {"block": "minecraft:stick", "qty": 2}],
                        output_qty=3, time_cost=0.5, resource_cost=2.0)

        # Fence gate
        db.add_resource(fence_gate, "craft",
                        ingredients=[{"block": planks, "qty": 2},
                                     {"block": "minecraft:stick", "qty": 4}],
                        output_qty=1, time_cost=0.5, resource_cost=2.0)

        # Door (6 planks → 3 doors)
        db.add_resource(door, "craft",
                        ingredients=[{"block": planks, "qty": 6}],
                        output_qty=3, time_cost=0.5, resource_cost=1.5)

        # Trapdoor (6 planks → 2 trapdoors)
        db.add_resource(trapdoor, "craft",
                        ingredients=[{"block": planks, "qty": 6}],
                        output_qty=2, time_cost=0.5, resource_cost=1.5)

    # Sticks (2 planks → 4 sticks)
    db.add_resource("minecraft:stick", "craft",
                    ingredients=[{"block": "minecraft:oak_planks", "qty": 2}],
                    output_qty=4, time_cost=0.5, resource_cost=0.1)

    # ── Stone family ──
    db.add_resource("minecraft:cobblestone", "mine", time_cost=2.0, resource_cost=0.5,
                    tool_required="wooden_pickaxe")
    db.add_resource("minecraft:stone", "smelt",
                    ingredients=[{"block": "minecraft:cobblestone", "qty": 1}],
                    time_cost=10.0, resource_cost=1.5)
    db.add_resource("minecraft:stone_bricks", "craft",
                    ingredients=[{"block": "minecraft:stone", "qty": 4}],
                    output_qty=4, time_cost=0.5, resource_cost=2.0)
    db.add_resource("minecraft:mossy_stone_bricks", "craft",
                    ingredients=[{"block": "minecraft:stone_bricks", "qty": 1},
                                 {"block": "minecraft:vine", "qty": 1}],
                    time_cost=0.5, resource_cost=3.0)
    db.add_resource("minecraft:deepslate", "mine", time_cost=3.0, resource_cost=1.0,
                    tool_required="wooden_pickaxe")
    db.add_resource("minecraft:cobbled_deepslate", "mine", time_cost=3.0, resource_cost=1.0,
                    tool_required="wooden_pickaxe")
    db.add_resource("minecraft:deepslate_bricks", "craft",
                    ingredients=[{"block": "minecraft:polished_deepslate", "qty": 4}],
                    time_cost=0.5, resource_cost=3.0)
    db.add_resource("minecraft:polished_deepslate", "craft",
                    ingredients=[{"block": "minecraft:cobbled_deepslate", "qty": 4}],
                    time_cost=0.5, resource_cost=2.0)

    for variant in ["andesite", "diorite", "granite"]:
        block = f"minecraft:{variant}"
        db.add_resource(block, "mine", time_cost=2.0, resource_cost=0.5,
                        tool_required="wooden_pickaxe")
        db.add_resource(f"minecraft:polished_{variant}", "craft",
                        ingredients=[{"block": block, "qty": 4}],
                        time_cost=0.5, resource_cost=1.0)

    db.add_resource("minecraft:tuff", "mine", time_cost=2.0, resource_cost=0.5,
                    tool_required="wooden_pickaxe")
    db.add_resource("minecraft:blackstone", "mine", time_cost=2.0, resource_cost=1.5,
                    tool_required="wooden_pickaxe")

    # Sandstone
    db.add_resource("minecraft:sandstone", "craft",
                    ingredients=[{"block": "minecraft:sand", "qty": 4}],
                    time_cost=0.5, resource_cost=1.0)
    db.add_resource("minecraft:sand", "mine", time_cost=1.5, resource_cost=0.5)
    db.add_resource("minecraft:red_sandstone", "craft",
                    ingredients=[{"block": "minecraft:red_sand", "qty": 4}],
                    time_cost=0.5, resource_cost=1.0)
    db.add_resource("minecraft:red_sand", "mine", time_cost=1.5, resource_cost=0.5)

    # ── Ores ──
    ores = [
        ("minecraft:coal_ore", "wooden_pickaxe", 3.0),
        ("minecraft:iron_ore", "stone_pickaxe", 4.0),
        ("minecraft:gold_ore", "iron_pickaxe", 5.0),
        ("minecraft:diamond_ore", "iron_pickaxe", 6.0),
        ("minecraft:emerald_ore", "iron_pickaxe", 6.0),
        ("minecraft:lapis_ore", "stone_pickaxe", 4.0),
        ("minecraft:redstone_ore", "iron_pickaxe", 5.0),
        ("minecraft:copper_ore", "stone_pickaxe", 4.0),
    ]
    for ore, tool, cost in ores:
        db.add_resource(ore, "mine", time_cost=cost, resource_cost=cost / 2,
                        tool_required=tool)

    # Smelting ores into ingots/items
    smelts = [
        ("minecraft:iron_ingot", "minecraft:iron_ore"),
        ("minecraft:gold_ingot", "minecraft:gold_ore"),
        ("minecraft:copper_ingot", "minecraft:copper_ore"),
    ]
    for result, ore in smelts:
        db.add_resource(result, "smelt",
                        ingredients=[{"block": ore, "qty": 1}],
                        time_cost=10.0, resource_cost=3.0)

    # ── Glass ──
    db.add_resource("minecraft:glass", "smelt",
                    ingredients=[{"block": "minecraft:sand", "qty": 1}],
                    time_cost=10.0, resource_cost=1.0)
    db.add_resource("minecraft:glass_pane", "craft",
                    ingredients=[{"block": "minecraft:glass", "qty": 6}],
                    output_qty=16, time_cost=0.5, resource_cost=1.0)
    db.add_resource("minecraft:tinted_glass", "craft",
                    ingredients=[{"block": "minecraft:glass", "qty": 1},
                                 {"block": "minecraft:amethyst_shard", "qty": 4}],
                    time_cost=0.5, resource_cost=5.0)

    # ── Bricks ──
    db.add_resource("minecraft:bricks", "craft",
                    ingredients=[{"block": "minecraft:brick", "qty": 4}],
                    time_cost=0.5, resource_cost=4.0)
    db.add_resource("minecraft:brick", "smelt",
                    ingredients=[{"block": "minecraft:clay_ball", "qty": 1}],
                    time_cost=10.0, resource_cost=2.0)
    db.add_resource("minecraft:clay_ball", "mine", time_cost=1.0, resource_cost=0.5)
    db.add_resource("minecraft:nether_bricks", "craft",
                    ingredients=[{"block": "minecraft:nether_brick", "qty": 4}],
                    time_cost=0.5, resource_cost=3.0)
    db.add_resource("minecraft:nether_brick", "smelt",
                    ingredients=[{"block": "minecraft:netherrack", "qty": 1}],
                    time_cost=10.0, resource_cost=1.5)
    db.add_resource("minecraft:netherrack", "mine", time_cost=1.0, resource_cost=1.0,
                    tool_required="wooden_pickaxe")

    # ── Concrete ──
    for color in ["white", "light_gray", "gray", "black", "brown", "red",
                   "orange", "yellow", "lime", "green", "cyan", "light_blue",
                   "blue", "purple", "magenta", "pink"]:
        db.add_resource(f"minecraft:{color}_concrete", "craft",
                        ingredients=[{"block": f"minecraft:{color}_concrete_powder", "qty": 1}],
                        time_cost=2.0, resource_cost=2.0)
        db.add_resource(f"minecraft:{color}_concrete_powder", "craft",
                        ingredients=[{"block": "minecraft:sand", "qty": 4},
                                     {"block": "minecraft:gravel", "qty": 4},
                                     {"block": f"minecraft:{color}_dye", "qty": 1}],
                        time_cost=0.5, resource_cost=2.0)

    # ── Misc common blocks ──
    db.add_resource("minecraft:dirt", "mine", time_cost=0.5, resource_cost=0.1)
    db.add_resource("minecraft:gravel", "mine", time_cost=1.0, resource_cost=0.3)
    db.add_resource("minecraft:clay", "mine", time_cost=1.5, resource_cost=0.5)
    db.add_resource("minecraft:obsidian", "mine", time_cost=10.0, resource_cost=5.0,
                    tool_required="diamond_pickaxe")
    db.add_resource("minecraft:glowstone", "mine", time_cost=2.0, resource_cost=3.0)
    db.add_resource("minecraft:sea_lantern", "mine", time_cost=2.0, resource_cost=4.0)
    db.add_resource("minecraft:torch", "craft",
                    ingredients=[{"block": "minecraft:stick", "qty": 1},
                                 {"block": "minecraft:coal", "qty": 1}],
                    time_cost=0.5, resource_cost=0.5)
    db.add_resource("minecraft:coal", "mine", time_cost=3.0, resource_cost=1.0,
                    tool_required="wooden_pickaxe")
    db.add_resource("minecraft:crafting_table", "craft",
                    ingredients=[{"block": "minecraft:oak_planks", "qty": 4}],
                    time_cost=0.5, resource_cost=1.0)
    db.add_resource("minecraft:furnace", "craft",
                    ingredients=[{"block": "minecraft:cobblestone", "qty": 8}],
                    time_cost=0.5, resource_cost=2.0)
    db.add_resource("minecraft:chest", "craft",
                    ingredients=[{"block": "minecraft:oak_planks", "qty": 8}],
                    time_cost=0.5, resource_cost=2.0)
    db.add_resource("minecraft:ladder", "craft",
                    ingredients=[{"block": "minecraft:stick", "qty": 7}],
                    output_qty=3, time_cost=0.5, resource_cost=1.0)
    db.add_resource("minecraft:bookshelf", "craft",
                    ingredients=[{"block": "minecraft:oak_planks", "qty": 6},
                                 {"block": "minecraft:book", "qty": 3}],
                    time_cost=0.5, resource_cost=5.0)

    # ── Tools (basic) ──
    db.add_resource("minecraft:wooden_pickaxe", "craft",
                    ingredients=[{"block": "minecraft:oak_planks", "qty": 3},
                                 {"block": "minecraft:stick", "qty": 2}],
                    time_cost=0.5, resource_cost=1.0)
    db.add_resource("minecraft:stone_pickaxe", "craft",
                    ingredients=[{"block": "minecraft:cobblestone", "qty": 3},
                                 {"block": "minecraft:stick", "qty": 2}],
                    time_cost=0.5, resource_cost=2.0)
    db.add_resource("minecraft:iron_pickaxe", "craft",
                    ingredients=[{"block": "minecraft:iron_ingot", "qty": 3},
                                 {"block": "minecraft:stick", "qty": 2}],
                    time_cost=0.5, resource_cost=5.0)
    db.add_resource("minecraft:diamond_pickaxe", "craft",
                    ingredients=[{"block": "minecraft:diamond", "qty": 3},
                                 {"block": "minecraft:stick", "qty": 2}],
                    time_cost=0.5, resource_cost=15.0)


def seed_all(db: BuildDB = None):
    """Seed both block_resources and block_substitutions."""
    if db is None:
        db = BuildDB()
    seed_block_resources(db)
    seed_block_substitutions(db)
    return db


if __name__ == "__main__":
    print("Seeding block catalog...")
    db = seed_all()
    # Quick verification
    conn = db._conn()
    res_count = conn.execute("SELECT COUNT(*) FROM block_resources").fetchone()[0]
    sub_count = conn.execute("SELECT COUNT(*) FROM block_substitutions").fetchone()[0]
    conn.close()
    print(f"  block_resources: {res_count} rows")
    print(f"  block_substitutions: {sub_count} rows")
    print("Done.")
