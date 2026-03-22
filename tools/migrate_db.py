#!/usr/bin/env python3
"""
One-time migration: copy Minecraft-specific tables from emma_sessions.db
to the new standalone minecraft.db.

Usage:
    python tools/migrate_db.py [--source path/to/emma_sessions.db] [--target path/to/minecraft.db]
    python tools/migrate_db.py --drop-source   # also drop migrated tables from source after copy

Tables migrated:
    build_guides, build_guide_blocks, block_substitutions, block_resources,
    world_state, world_state_rtree, build_goals, build_progress, poi_registry
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import sys

# Add parent dir so gamer package is importable
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from gamer.db import init_db, get_connection

TABLES = [
    "build_guides",
    "build_guide_blocks",
    "block_substitutions",
    "block_resources",
    "world_state",
    "build_goals",
    "build_progress",
    "poi_registry",
]

# world_state_rtree is a virtual table (R-tree) — needs special handling
RTREE_TABLE = "world_state_rtree"


def migrate(source_path: str, target_path: str, drop_source: bool = False) -> None:
    if not os.path.exists(source_path):
        print(f"ERROR: Source database not found: {source_path}")
        sys.exit(1)

    # Ensure target schema exists
    init_db(target_path)

    src = sqlite3.connect(source_path)
    src.row_factory = sqlite3.Row
    tgt = get_connection(target_path)

    total_rows = 0

    for table in TABLES:
        # Check if table exists in source
        exists = src.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            (table,)
        ).fetchone()
        if not exists:
            print(f"  SKIP {table} — not in source")
            continue

        rows = src.execute(f"SELECT * FROM {table}").fetchall()
        if not rows:
            print(f"  SKIP {table} — empty")
            continue

        # Get column names
        cols = [desc[0] for desc in src.execute(f"SELECT * FROM {table} LIMIT 1").description]
        placeholders = ", ".join(["?"] * len(cols))
        col_names = ", ".join(cols)

        # Insert rows (skip duplicates)
        inserted = 0
        for row in rows:
            try:
                tgt.execute(
                    f"INSERT OR IGNORE INTO {table} ({col_names}) VALUES ({placeholders})",
                    tuple(row)
                )
                inserted += 1
            except sqlite3.Error as e:
                print(f"  WARN: {table} row skip: {e}")

        tgt.commit()
        total_rows += inserted
        print(f"  {table}: {inserted}/{len(rows)} rows migrated")

    # Migrate R-tree data
    exists = src.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
        (RTREE_TABLE,)
    ).fetchone()
    if exists:
        rows = src.execute(f"SELECT * FROM {RTREE_TABLE}").fetchall()
        if rows:
            cols = [desc[0] for desc in src.execute(f"SELECT * FROM {RTREE_TABLE} LIMIT 1").description]
            placeholders = ", ".join(["?"] * len(cols))
            col_names = ", ".join(cols)
            inserted = 0
            for row in rows:
                try:
                    tgt.execute(
                        f"INSERT OR REPLACE INTO {RTREE_TABLE} ({col_names}) VALUES ({placeholders})",
                        tuple(row)
                    )
                    inserted += 1
                except sqlite3.Error:
                    pass
            tgt.commit()
            total_rows += inserted
            print(f"  {RTREE_TABLE}: {inserted}/{len(rows)} rows migrated")
    else:
        print(f"  SKIP {RTREE_TABLE} — not in source")

    # Optionally drop migrated tables from source
    if drop_source:
        print("\n--- Dropping migrated tables from source ---")
        for table in [RTREE_TABLE] + TABLES:
            try:
                src.execute(f"DROP TABLE IF EXISTS {table}")
                print(f"  DROPPED {table}")
            except sqlite3.Error as e:
                print(f"  WARN: could not drop {table}: {e}")
        src.commit()

    src.close()
    tgt.close()

    print(f"\nDone. {total_rows} total rows migrated to {target_path}")


def main():
    parser = argparse.ArgumentParser(description="Migrate Minecraft tables from emma_sessions.db to minecraft.db")
    parser.add_argument("--source", default=None, help="Path to source emma_sessions.db")
    parser.add_argument("--target", default=None, help="Path to target minecraft.db")
    parser.add_argument("--drop-source", action="store_true", help="Drop migrated tables from source after copy")
    args = parser.parse_args()

    # Default source: look in EmmaAICoHost sibling directory
    if args.source is None:
        project_root = os.path.dirname(os.path.dirname(__file__))
        emma_root = os.path.join(os.path.dirname(project_root), "EmmaAICoHost")
        args.source = os.path.join(emma_root, "emma_sessions.db")

    # Default target: minecraft.db in this project root
    if args.target is None:
        project_root = os.path.dirname(os.path.dirname(__file__))
        args.target = os.path.join(project_root, "minecraft.db")

    print(f"Source: {args.source}")
    print(f"Target: {args.target}")
    print(f"Drop source tables: {args.drop_source}")
    print()

    migrate(args.source, args.target, args.drop_source)


if __name__ == "__main__":
    main()
