#!/usr/bin/env python3
"""
Schematic import pipeline for Emma's build database.

Reads .schematic (legacy), .litematic (Litematica), and .schem (Sponge v2/v3)
files and imports them into the build_guides / build_guide_blocks tables.

Requires: nbtlib  (pip install nbtlib)
"""

import gzip
import json
import logging
import os
import re
from pathlib import Path
from typing import Optional

from gamer.block_catalog import normalize_block_name
from gamer.build_db import BuildDB

log = logging.getLogger(__name__)

# ── Block State Parsing ──────────────────────────────────────

def _parse_block_state(palette_key: str) -> tuple[str, dict]:
    """Split 'minecraft:oak_stairs[facing=north,half=top]' into
    ('minecraft:oak_stairs', {'facing': 'north', 'half': 'top'})
    """
    m = re.match(r'^([^\[]+)(?:\[(.+)\])?$', palette_key)
    if not m:
        return normalize_block_name(palette_key), {}
    name = normalize_block_name(m.group(1))
    state = {}
    if m.group(2):
        for kv in m.group(2).split(","):
            k, _, v = kv.partition("=")
            state[k.strip()] = v.strip()
    return name, state


def _compute_placement_order(blocks: list[dict]) -> list[dict]:
    """Sort blocks bottom-up (lowest Y first), then row-by-row within each layer."""
    return sorted(blocks, key=lambda b: (b["offset_y"], b["offset_z"], b["offset_x"]))


# ── .schematic (Legacy) ──────────────────────────────────────

def parse_schematic(filepath: str) -> dict:
    """Read legacy .schematic NBT format (Schematica / MCEdit).
    Returns {name, dimensions, blocks: [{block_type, block_state, offset_x/y/z}]}
    """
    try:
        import nbtlib
    except ImportError:
        raise ImportError("nbtlib is required: pip install nbtlib")

    nbt = nbtlib.load(filepath)
    root = nbt.get("Schematic", nbt)

    width = int(root["Width"])
    height = int(root["Height"])
    length = int(root["Length"])

    block_ids = root["Blocks"]
    block_data = root.get("Data", [0] * len(block_ids))

    blocks = []
    for y in range(height):
        for z in range(length):
            for x in range(width):
                idx = (y * length + z) * width + x
                bid = int(block_ids[idx])
                if bid == 0:  # air
                    continue
                # Map numeric ID to modern name
                block_type = normalize_block_name(str(bid))
                if block_type == "minecraft:air":
                    continue
                blocks.append({
                    "block_type": block_type,
                    "block_state": {},
                    "offset_x": x,
                    "offset_y": y,
                    "offset_z": z,
                })

    blocks = _compute_placement_order(blocks)
    for i, b in enumerate(blocks):
        b["placement_order"] = i

    name = Path(filepath).stem
    return {
        "name": name,
        "dimensions": {"x": width, "y": height, "z": length},
        "blocks": blocks,
        "source_format": "schematic",
    }


# ── .litematic (Litematica) ───────────────────────────────────

def parse_litematic(filepath: str) -> dict:
    """Read .litematic NBT format (Litematica mod).
    Returns {name, dimensions, blocks: [{block_type, block_state, offset_x/y/z}]}
    """
    try:
        import nbtlib
    except ImportError:
        raise ImportError("nbtlib is required: pip install nbtlib")

    nbt = nbtlib.load(filepath)

    # Litematica structure: root has Metadata and Regions
    metadata = nbt.get("Metadata", {})
    regions = nbt.get("Regions", {})

    total_blocks = []
    total_width = 0
    total_height = 0
    total_length = 0

    for region_name, region in regions.items():
        palette = region.get("BlockStatePalette", [])
        size = region.get("Size", {})
        rx = abs(int(size.get("x", 0)))
        ry = abs(int(size.get("y", 0)))
        rz = abs(int(size.get("z", 0)))
        total_width = max(total_width, rx)
        total_height = max(total_height, ry)
        total_length = max(total_length, rz)

        # Parse packed BlockStates long array
        # Note: nbtlib.LongArray.__bool__ can return False even when non-empty,
        # so check len() explicitly instead of truthiness.
        block_states = region.get("BlockStates", [])
        if len(block_states) == 0 or len(palette) == 0:
            continue

        volume = rx * ry * rz
        if volume == 0:
            continue

        # Bits per entry = max(2, ceil(log2(len(palette))))
        import math
        bits = max(2, math.ceil(math.log2(max(len(palette), 2))))
        mask = (1 << bits) - 1

        # Convert long array to list of ints
        long_list = [int(v) for v in block_states]

        for y in range(ry):
            for z in range(rz):
                for x in range(rx):
                    idx = (y * rz + z) * rx + x
                    bit_offset = idx * bits
                    long_idx = bit_offset // 64
                    bit_start = bit_offset % 64

                    if long_idx >= len(long_list):
                        continue

                    val = long_list[long_idx]
                    # Handle unsigned interpretation of Java longs
                    if val < 0:
                        val += (1 << 64)
                    palette_idx = (val >> bit_start) & mask

                    # Check if we need to read across long boundary
                    if bit_start + bits > 64 and long_idx + 1 < len(long_list):
                        next_val = long_list[long_idx + 1]
                        if next_val < 0:
                            next_val += (1 << 64)
                        remaining = bits - (64 - bit_start)
                        palette_idx |= (next_val & ((1 << remaining) - 1)) << (64 - bit_start)

                    if palette_idx >= len(palette):
                        continue

                    entry = palette[palette_idx]
                    block_name = str(entry.get("Name", "minecraft:air"))
                    if block_name == "minecraft:air":
                        continue

                    block_state = {}
                    props = entry.get("Properties", {})
                    for k, v in props.items():
                        block_state[str(k)] = str(v)

                    total_blocks.append({
                        "block_type": normalize_block_name(block_name),
                        "block_state": block_state,
                        "offset_x": x,
                        "offset_y": y,
                        "offset_z": z,
                    })

    total_blocks = _compute_placement_order(total_blocks)
    for i, b in enumerate(total_blocks):
        b["placement_order"] = i

    name_str = str(metadata.get("Name", Path(filepath).stem))
    return {
        "name": name_str,
        "dimensions": {"x": total_width, "y": total_height, "z": total_length},
        "blocks": total_blocks,
        "source_format": "litematic",
    }


# ── .schem (Sponge Schematic v2/v3) ──────────────────────────

def parse_schem(filepath: str) -> dict:
    """Read .schem Sponge Schematic v2/v3 (WorldEdit, FAWE).
    Returns {name, dimensions, blocks: [{block_type, block_state, offset_x/y/z}]}
    """
    try:
        import nbtlib
    except ImportError:
        raise ImportError("nbtlib is required: pip install nbtlib")

    nbt = nbtlib.load(filepath)

    # Sponge v3 wraps in a "Schematic" tag, v2 may not
    root = nbt.get("Schematic", nbt)

    width = int(root["Width"])
    height = int(root["Height"])
    length = int(root["Length"])

    # Sponge v2/v3: Palette maps block state strings to IDs
    # v3 uses "Blocks" → "Palette" and "Blocks" → "Data"
    # v2 uses root "Palette" and "BlockData"
    if "Blocks" in root and isinstance(root["Blocks"], dict):
        # v3
        palette_raw = root["Blocks"].get("Palette", {})
        block_data = root["Blocks"].get("Data", [])
    else:
        # v2
        palette_raw = root.get("Palette", {})
        block_data = root.get("BlockData", [])

    # Build reverse palette: id → (block_name, block_state)
    palette = {}
    for key_str, idx in palette_raw.items():
        block_name, block_state = _parse_block_state(str(key_str))
        palette[int(idx)] = (block_name, block_state)

    # Decode varint-encoded block data
    block_indices = _decode_varints(block_data, width * height * length)

    blocks = []
    for i, palette_idx in enumerate(block_indices):
        if palette_idx not in palette:
            continue
        block_name, block_state = palette[palette_idx]
        if block_name == "minecraft:air":
            continue

        # Sponge schem index: (y * length + z) * width + x
        x = i % width
        z = (i // width) % length
        y = i // (width * length)

        blocks.append({
            "block_type": block_name,
            "block_state": block_state,
            "offset_x": x,
            "offset_y": y,
            "offset_z": z,
        })

    blocks = _compute_placement_order(blocks)
    for i, b in enumerate(blocks):
        b["placement_order"] = i

    name = Path(filepath).stem
    return {
        "name": name,
        "dimensions": {"x": width, "y": height, "z": length},
        "blocks": blocks,
        "source_format": "schem",
    }


def _decode_varints(data, expected_count: int) -> list[int]:
    """Decode a varint-encoded byte array (Sponge Schematic format)."""
    result = []
    value = 0
    bits = 0
    data_list = list(data)

    for byte_val in data_list:
        byte_val = int(byte_val) & 0xFF
        value |= (byte_val & 0x7F) << bits
        bits += 7
        if (byte_val & 0x80) == 0:
            result.append(value)
            value = 0
            bits = 0
        if len(result) >= expected_count:
            break

    return result


# ── .nbt (Vanilla Structure) ─────────────────────────────────

def parse_nbt_structure(filepath: str) -> dict:
    """Read .nbt vanilla structure format (Structure Block / data packs).
    Returns {name, dimensions, blocks: [{block_type, block_state, offset_x/y/z}]}
    """
    try:
        import nbtlib
    except ImportError:
        raise ImportError("nbtlib is required: pip install nbtlib")

    nbt = nbtlib.load(filepath)

    # Size: list of 3 ints [x, y, z]
    size = nbt.get("size", [0, 0, 0])
    width = int(size[0])
    height = int(size[1])
    length = int(size[2])

    # Palette: list of {Name: str, Properties?: {str: str}}
    # Some files use "palettes" (plural, list of palette lists) for randomized structures
    palette_raw = nbt.get("palette")
    if palette_raw is None:
        palettes = nbt.get("palettes")
        if palettes and len(palettes) > 0:
            palette_raw = palettes[0]
        else:
            palette_raw = []

    parsed_palette = []
    for entry in palette_raw:
        block_name = normalize_block_name(str(entry.get("Name", "minecraft:air")))
        block_state = {}
        props = entry.get("Properties", {})
        for k, v in props.items():
            block_state[str(k)] = str(v)
        parsed_palette.append((block_name, block_state))

    # Blocks: list of {pos: [x, y, z], state: int, nbt?: compound}
    raw_blocks = nbt.get("blocks", [])
    blocks = []
    for b in raw_blocks:
        state_idx = int(b.get("state", 0))
        if state_idx >= len(parsed_palette):
            log.warning("Block state index %d out of palette range (%d) in %s",
                        state_idx, len(parsed_palette), filepath)
            continue
        block_name, block_state = parsed_palette[state_idx]
        if block_name == "minecraft:air":
            continue

        pos = b.get("pos", [0, 0, 0])
        blocks.append({
            "block_type": block_name,
            "block_state": block_state,
            "offset_x": int(pos[0]),
            "offset_y": int(pos[1]),
            "offset_z": int(pos[2]),
        })

    blocks = _compute_placement_order(blocks)
    for i, blk in enumerate(blocks):
        blk["placement_order"] = i

    name = Path(filepath).stem
    return {
        "name": name,
        "dimensions": {"x": width, "y": height, "z": length},
        "blocks": blocks,
        "source_format": "nbt",
    }


# ── Auto-detect and Import ────────────────────────────────────

FORMAT_PARSERS = {
    ".schematic": parse_schematic,
    ".litematic": parse_litematic,
    ".schem": parse_schem,
    ".nbt": parse_nbt_structure,
}


def import_schematic_to_db(filepath: str, db: BuildDB = None) -> int:
    """Auto-detect format, parse, and insert into DB. Returns guide_id."""
    if db is None:
        db = BuildDB()

    ext = Path(filepath).suffix.lower()
    parser = FORMAT_PARSERS.get(ext)
    if parser is None:
        raise ValueError(f"Unsupported schematic format: {ext}")

    # Fast duplicate check by filename before expensive parse
    source = Path(filepath).name
    if db.guide_exists_by_source(source):
        log.info(f"Skipping existing: {source}")
        return -1

    result = parser(filepath)

    # Full duplicate check (same source + dimensions)
    dims = result["dimensions"]
    if db.guide_exists(source, dims):
        log.info(f"Skipping duplicate: {source}")
        return -1

    guide_id = db.add_guide(
        name=result["name"],
        source=source,
        source_format=result["source_format"],
        dimensions=dims,
        block_count=len(result["blocks"]),
        difficulty=_estimate_difficulty(len(result["blocks"])),
        tags=_auto_tag(result),
    )
    db.add_guide_blocks(guide_id, result["blocks"])

    log.info(f"Imported '{result['name']}' → guide_id={guide_id}, "
             f"{len(result['blocks'])} blocks")
    return guide_id


def batch_import(directory: str, db: BuildDB = None) -> dict:
    """Scan a directory for schematic files, import all.
    Returns {"imported": N, "skipped": M, "errors": [...]}
    """
    if db is None:
        db = BuildDB()

    imported = 0
    skipped = 0
    errors = []

    # Collect all schematic files first for progress reporting
    all_files = []
    for root, dirs, files in os.walk(directory):
        for fname in sorted(files):
            ext = Path(fname).suffix.lower()
            if ext in FORMAT_PARSERS:
                all_files.append((root, fname))

    total = len(all_files)
    for i, (root, fname) in enumerate(all_files, 1):
        fpath = os.path.join(root, fname)
        try:
            guide_id = import_schematic_to_db(fpath, db)
            if guide_id == -1:
                skipped += 1
                print(f"  [{i}/{total}] {fname} — skipped")
            else:
                imported += 1
                print(f"  [{i}/{total}] {fname} — imported (id={guide_id})")
        except Exception as e:
            errors.append({"file": fname, "error": str(e)})
            print(f"  [{i}/{total}] {fname} — ERROR: {e}")
            log.error(f"Failed to import {fname}: {e}")

    return {"imported": imported, "skipped": skipped, "errors": errors}


def _estimate_difficulty(block_count: int) -> int:
    """Estimate build difficulty from block count."""
    if block_count < 50:
        return 1
    elif block_count < 200:
        return 2
    elif block_count < 1000:
        return 3
    elif block_count < 5000:
        return 4
    return 5


def _auto_tag(result: dict) -> list[str]:
    """Generate automatic tags based on block types and dimensions."""
    tags = []
    block_types = {b["block_type"] for b in result["blocks"]}
    dims = result["dimensions"]

    # Size tags
    volume = dims["x"] * dims["y"] * dims["z"]
    if volume < 100:
        tags.append("small")
    elif volume < 1000:
        tags.append("medium")
    else:
        tags.append("large")

    # Material tags
    wood_blocks = {b for b in block_types if "planks" in b or "log" in b or "wood" in b}
    stone_blocks = {b for b in block_types if "stone" in b or "brick" in b}
    if wood_blocks:
        tags.append("wood")
    if stone_blocks:
        tags.append("stone")
    if any("glass" in b for b in block_types):
        tags.append("glass")
    if any("wool" in b for b in block_types):
        tags.append("wool")

    # Structure tags
    if dims["y"] > 5 and dims["x"] > 3 and dims["z"] > 3:
        tags.append("building")
    if any("door" in b for b in block_types):
        tags.append("habitable")
    if any("farmland" in b or "wheat" in b or "carrot" in b for b in block_types):
        tags.append("farm")

    return tags


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 2:
        print("Usage: python schematic_parser.py <file_or_dir>")
        sys.exit(1)

    target = sys.argv[1]
    db = BuildDB()

    if os.path.isdir(target):
        result = batch_import(target, db)
        print(f"Imported: {result['imported']}, Skipped: {result['skipped']}, "
              f"Errors: {len(result['errors'])}")
        for err in result["errors"]:
            print(f"  ERROR: {err['file']}: {err['error']}")
    else:
        guide_id = import_schematic_to_db(target, db)
        if guide_id == -1:
            print("Skipped (duplicate)")
        else:
            guide = db.get_guide(guide_id)
            print(f"Imported: {guide['name']} (id={guide_id}, "
                  f"{guide['block_count']} blocks)")
