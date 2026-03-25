#!/usr/bin/env python3
"""
Query layer for Emma's gamer build database.

All gamer components access build guides, goals, substitutions, resources,
world state, and progress through this module — no raw SQL elsewhere.

Tables live in minecraft.db, managed by gamer.db.
"""

import json
import sqlite3
import time
from typing import Optional

from gamer.db import get_db_path


class BuildDB:
    """Query layer for all gamer build tables."""

    def __init__(self, db_path: str | None = None):
        self.db_path = db_path or get_db_path()

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        return conn

    # ── Build Guides ──────────────────────────────────────────

    def add_guide(self, name: str, description: str = None,
                  source: str = "custom", source_format: str = None,
                  dimensions: dict = None, block_count: int = 0,
                  difficulty: int = 1, tags: list = None) -> int:
        """Insert a build guide, return its id."""
        conn = self._conn()
        c = conn.cursor()
        c.execute("""INSERT INTO build_guides
            (name, description, source, source_format, dimensions,
             block_count, difficulty, tags)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (name, description, source, source_format,
             json.dumps(dimensions) if dimensions else None,
             block_count, difficulty,
             json.dumps(tags) if tags else "[]"))
        guide_id = c.lastrowid
        conn.commit()
        conn.close()
        return guide_id

    def add_guide_blocks(self, guide_id: int, blocks: list[dict]):
        """Bulk insert blocks for a guide.
        Each dict: {block_type, block_state, offset_x, offset_y, offset_z, placement_order}
        """
        conn = self._conn()
        c = conn.cursor()
        c.executemany("""INSERT OR IGNORE INTO build_guide_blocks
            (guide_id, block_type, block_state, offset_x, offset_y, offset_z, placement_order)
            VALUES (?, ?, ?, ?, ?, ?, ?)""",
            [(guide_id,
              b["block_type"],
              json.dumps(b.get("block_state", {})),
              b["offset_x"], b["offset_y"], b["offset_z"],
              b.get("placement_order", 0))
             for b in blocks])
        # Update block_count on the guide
        count = c.execute("SELECT COUNT(*) FROM build_guide_blocks WHERE guide_id=?",
                          (guide_id,)).fetchone()[0]
        c.execute("UPDATE build_guides SET block_count=? WHERE id=?", (count, guide_id))
        conn.commit()
        conn.close()

    def get_guide(self, guide_id: int) -> Optional[dict]:
        conn = self._conn()
        row = conn.execute("SELECT * FROM build_guides WHERE id=?",
                           (guide_id,)).fetchone()
        conn.close()
        if row is None:
            return None
        d = dict(row)
        d["dimensions"] = json.loads(d["dimensions"]) if d["dimensions"] else None
        d["tags"] = json.loads(d["tags"]) if d["tags"] else []
        return d

    def get_guide_blocks(self, guide_id: int) -> list[dict]:
        conn = self._conn()
        rows = conn.execute(
            "SELECT * FROM build_guide_blocks WHERE guide_id=? ORDER BY placement_order",
            (guide_id,)).fetchall()
        conn.close()
        result = []
        for r in rows:
            d = dict(r)
            d["block_state"] = json.loads(d["block_state"]) if d["block_state"] else {}
            result.append(d)
        return result

    def search_guides(self, query: str = "", tags: list[str] = None) -> list[dict]:
        conn = self._conn()
        sql = "SELECT * FROM build_guides WHERE 1=1"
        params = []
        if query:
            sql += " AND (name LIKE ? OR description LIKE ?)"
            params += [f"%{query}%", f"%{query}%"]
        if tags:
            for tag in tags:
                sql += " AND tags LIKE ?"
                params.append(f'%"{tag}"%')
        sql += " ORDER BY created_at DESC"
        rows = conn.execute(sql, params).fetchall()
        conn.close()
        result = []
        for r in rows:
            d = dict(r)
            d["dimensions"] = json.loads(d["dimensions"]) if d["dimensions"] else None
            d["tags"] = json.loads(d["tags"]) if d["tags"] else []
            result.append(d)
        return result

    def get_guide_bill_of_materials(self, guide_id: int) -> dict[str, int]:
        """Return {block_type: total_quantity} for a build guide."""
        conn = self._conn()
        rows = conn.execute(
            "SELECT block_type, COUNT(*) as qty FROM build_guide_blocks "
            "WHERE guide_id=? GROUP BY block_type", (guide_id,)).fetchall()
        conn.close()
        return {r["block_type"]: r["qty"] for r in rows}

    def guide_exists(self, source: str, dimensions: dict) -> bool:
        """Check if a guide with the same source file and dimensions already exists."""
        conn = self._conn()
        row = conn.execute(
            "SELECT id FROM build_guides WHERE source=? AND dimensions=?",
            (source, json.dumps(dimensions))).fetchone()
        conn.close()
        return row is not None

    def guide_exists_by_source(self, source: str) -> bool:
        """Check if a guide with the same source filename already exists (fast, no parse needed)."""
        conn = self._conn()
        row = conn.execute(
            "SELECT id FROM build_guides WHERE source=?", (source,)).fetchone()
        conn.close()
        return row is not None

    # ── Substitutions ─────────────────────────────────────────

    def add_substitution(self, original: str, substitute: str,
                         priority: int = 10, context: str = "any",
                         reason: str = None):
        conn = self._conn()
        conn.execute("""INSERT OR IGNORE INTO block_substitutions
            (original_block, substitute_block, priority, context, reason)
            VALUES (?, ?, ?, ?, ?)""",
            (original, substitute, priority, context, reason))
        conn.commit()
        conn.close()

    def get_substitutions(self, block_type: str, context: str = "any") -> list[dict]:
        """Return substitutes ordered by priority (lowest first)."""
        conn = self._conn()
        rows = conn.execute(
            "SELECT * FROM block_substitutions "
            "WHERE original_block=? AND (context=? OR context='any') "
            "ORDER BY priority", (block_type, context)).fetchall()
        conn.close()
        return [dict(r) for r in rows]

    def get_best_available_substitute(
        self, block_type: str, available_blocks: set[str],
        context: str = "any"
    ) -> Optional[str]:
        """Walk substitution table, return first block in available_blocks."""
        for sub in self.get_substitutions(block_type, context):
            if sub["substitute_block"] in available_blocks:
                return sub["substitute_block"]
        return None

    # ── Resources ─────────────────────────────────────────────

    def add_resource(self, block_type: str, method: str,
                     ingredients: list = None, output_qty: int = 1,
                     tool_required: str = None,
                     time_cost: float = 0, resource_cost: float = 0,
                     prerequisite_build_id: int = None):
        conn = self._conn()
        conn.execute("""INSERT OR IGNORE INTO block_resources
            (block_type, method, ingredients, output_qty, tool_required,
             time_cost, resource_cost, prerequisite_build_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (block_type, method,
             json.dumps(ingredients) if ingredients else "[]",
             output_qty, tool_required, time_cost, resource_cost,
             prerequisite_build_id))
        conn.commit()
        conn.close()

    def get_resource_methods(self, block_type: str) -> list[dict]:
        conn = self._conn()
        rows = conn.execute(
            "SELECT * FROM block_resources WHERE block_type=? ORDER BY resource_cost",
            (block_type,)).fetchall()
        conn.close()
        result = []
        for r in rows:
            d = dict(r)
            d["ingredients"] = json.loads(d["ingredients"]) if d["ingredients"] else []
            result.append(d)
        return result

    def resolve_dependencies(self, block_type: str, quantity: int = 1) -> list[dict]:
        """Recursive dependency resolution. Returns ordered task list:
        [{"block": "minecraft:oak_log", "qty": 4, "method": "mine"}, ...]
        """
        tasks = []
        visited = set()
        self._resolve_recursive(block_type, quantity, tasks, visited)
        return tasks

    def _resolve_recursive(self, block_type: str, quantity: int,
                           tasks: list, visited: set):
        if block_type in visited:
            return
        visited.add(block_type)

        methods = self.get_resource_methods(block_type)
        if not methods:
            tasks.append({"block": block_type, "qty": quantity, "method": "unknown"})
            return

        best = methods[0]  # lowest resource_cost
        if best["method"] == "craft" and best["ingredients"]:
            import math
            output_qty = best.get("output_qty", 1) or 1
            crafts_needed = math.ceil(quantity / output_qty)
            for ing in best["ingredients"]:
                ing_qty = ing.get("qty", 1) * crafts_needed
                self._resolve_recursive(ing["block"], ing_qty, tasks, visited)
        elif best["method"] == "build" and best.get("prerequisite_build_id"):
            tasks.append({
                "block": block_type, "qty": quantity,
                "method": "build",
                "prerequisite_build_id": best["prerequisite_build_id"]
            })
            return

        tasks.append({
            "block": block_type, "qty": quantity,
            "method": best["method"]
        })

    # ── World State ───────────────────────────────────────────

    def update_world_block(self, x: int, y: int, z: int,
                           block_type: str, state: str = "discovered"):
        now = time.time()
        conn = self._conn()
        c = conn.cursor()
        c.execute("""INSERT INTO world_state (x, y, z, block_type, state, discovered_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(x, y, z) DO UPDATE SET
                block_type=excluded.block_type,
                state=excluded.state,
                updated_at=excluded.updated_at""",
            (x, y, z, block_type, state, now, now))
        # Update R-tree
        rowid = c.execute("SELECT rowid FROM world_state WHERE x=? AND y=? AND z=?",
                          (x, y, z)).fetchone()[0]
        c.execute("INSERT OR REPLACE INTO world_state_rtree (id, min_x, max_x, min_y, max_y, min_z, max_z) "
                  "VALUES (?, ?, ?, ?, ?, ?, ?)",
                  (rowid, x, x, y, y, z, z))
        conn.commit()
        conn.close()

    def update_world_blocks_batch(self, blocks: list[dict]):
        """Batch insert/update world_state. Each dict: {x, y, z, block_type, state}."""
        now = time.time()
        conn = self._conn()
        c = conn.cursor()
        c.execute("BEGIN")
        for b in blocks:
            c.execute("""INSERT INTO world_state (x, y, z, block_type, state, discovered_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(x, y, z) DO UPDATE SET
                    block_type=excluded.block_type,
                    state=excluded.state,
                    updated_at=excluded.updated_at""",
                (b["x"], b["y"], b["z"], b["block_type"],
                 b.get("state", "discovered"), now, now))
            rowid = c.execute("SELECT rowid FROM world_state WHERE x=? AND y=? AND z=?",
                              (b["x"], b["y"], b["z"])).fetchone()[0]
            c.execute("INSERT OR REPLACE INTO world_state_rtree "
                      "(id, min_x, max_x, min_y, max_y, min_z, max_z) "
                      "VALUES (?, ?, ?, ?, ?, ?, ?)",
                      (rowid, b["x"], b["x"], b["y"], b["y"], b["z"], b["z"]))
        conn.commit()
        conn.close()

    def get_blocks_in_radius(self, cx: int, cy: int, cz: int,
                             radius: int) -> list[dict]:
        conn = self._conn()
        rows = conn.execute(
            "SELECT ws.* FROM world_state ws "
            "JOIN world_state_rtree rt ON ws.rowid = rt.id "
            "WHERE rt.min_x >= ? AND rt.max_x <= ? "
            "AND rt.min_y >= ? AND rt.max_y <= ? "
            "AND rt.min_z >= ? AND rt.max_z <= ?",
            (cx - radius, cx + radius,
             cy - radius, cy + radius,
             cz - radius, cz + radius)).fetchall()
        conn.close()
        return [dict(r) for r in rows]

    def count_available(self, block_type: str) -> int:
        conn = self._conn()
        row = conn.execute(
            "SELECT COUNT(*) FROM world_state WHERE block_type=? AND state='discovered'",
            (block_type,)).fetchone()
        conn.close()
        return row[0]

    def get_available_block_types(self) -> set[str]:
        conn = self._conn()
        rows = conn.execute(
            "SELECT DISTINCT block_type FROM world_state WHERE state='discovered'"
        ).fetchall()
        conn.close()
        return {r[0] for r in rows}

    # ── Build Goals ───────────────────────────────────────────

    def create_goal(self, name: str, guide_id: int = None, **kwargs) -> int:
        conn = self._conn()
        c = conn.cursor()
        cols = ["name"]
        vals = [name]
        if guide_id is not None:
            cols.append("guide_id")
            vals.append(guide_id)
        for k in ("status", "location_x", "location_y", "location_z",
                   "functional_goal", "stream_session", "priority_weight"):
            if k in kwargs:
                cols.append(k)
                vals.append(kwargs[k])
        for k in ("aesthetic_decisions", "substitutions_applied"):
            if k in kwargs:
                cols.append(k)
                vals.append(json.dumps(kwargs[k]))
        placeholders = ", ".join(["?"] * len(vals))
        sql = f"INSERT INTO build_goals ({', '.join(cols)}) VALUES ({placeholders})"
        c.execute(sql, vals)
        goal_id = c.lastrowid
        conn.commit()
        conn.close()

        # Auto-sync to POI registry if location is set
        loc_x = kwargs.get("location_x")
        loc_y = kwargs.get("location_y")
        loc_z = kwargs.get("location_z")
        if loc_x is not None and loc_y is not None and loc_z is not None:
            try:
                from gamer.poi import POIRegistry
                POIRegistry(self.db_path).sync_single_goal(
                    goal_id, name, loc_x, loc_y, loc_z,
                )
            except Exception:
                pass  # non-critical

        return goal_id

    def update_goal_status(self, goal_id: int, status: str):
        conn = self._conn()
        now = time.time()
        extra = ""
        if status == "current":
            extra = ", started_at=?"
        elif status in ("complete", "abandoned"):
            extra = ", completed_at=?"
        sql = f"UPDATE build_goals SET status=?{extra} WHERE id=?"
        params = [status]
        if extra:
            params.append(now)
        params.append(goal_id)
        conn.execute(sql, params)
        conn.commit()
        conn.close()

    def get_active_goals(self) -> list[dict]:
        conn = self._conn()
        rows = conn.execute(
            "SELECT * FROM build_goals WHERE status IN ('planned','current') "
            "ORDER BY priority_weight DESC").fetchall()
        conn.close()
        return [self._parse_goal(r) for r in rows]

    def get_goal(self, goal_id: int) -> Optional[dict]:
        conn = self._conn()
        row = conn.execute("SELECT * FROM build_goals WHERE id=?",
                           (goal_id,)).fetchone()
        conn.close()
        return self._parse_goal(row) if row else None

    def get_goals_by_status(self, status: str) -> list[dict]:
        conn = self._conn()
        rows = conn.execute(
            "SELECT * FROM build_goals WHERE status=? ORDER BY priority_weight DESC",
            (status,)).fetchall()
        conn.close()
        return [self._parse_goal(r) for r in rows]

    def update_goal_substitutions(self, goal_id: int, subs: dict):
        conn = self._conn()
        conn.execute("UPDATE build_goals SET substitutions_applied=? WHERE id=?",
                     (json.dumps(subs), goal_id))
        conn.commit()
        conn.close()

    def update_goal_aesthetics(self, goal_id: int, decisions: dict):
        conn = self._conn()
        conn.execute("UPDATE build_goals SET aesthetic_decisions=? WHERE id=?",
                     (json.dumps(decisions), goal_id))
        conn.commit()
        conn.close()

    def adjust_priority(self, goal_id: int, new_weight: float):
        conn = self._conn()
        conn.execute("UPDATE build_goals SET priority_weight=? WHERE id=?",
                     (max(0.0, min(1.0, new_weight)), goal_id))
        conn.commit()
        conn.close()

    def query_goal_info(self, query: str) -> list[dict]:
        """Fuzzy search across goal name, functional_goal, aesthetic_decisions."""
        conn = self._conn()
        rows = conn.execute(
            "SELECT * FROM build_goals WHERE "
            "name LIKE ? OR functional_goal LIKE ? OR aesthetic_decisions LIKE ? "
            "ORDER BY created_at DESC",
            (f"%{query}%", f"%{query}%", f"%{query}%")).fetchall()
        conn.close()
        return [self._parse_goal(r) for r in rows]

    def _parse_goal(self, row) -> dict:
        d = dict(row)
        for k in ("aesthetic_decisions", "substitutions_applied"):
            d[k] = json.loads(d[k]) if d.get(k) else {}
        return d

    # ── Build Progress ────────────────────────────────────────

    def init_progress_from_guide(self, goal_id: int, guide_id: int):
        """Create build_progress rows from a guide's bill of materials."""
        bom = self.get_guide_bill_of_materials(guide_id)
        conn = self._conn()
        for block_type, total in bom.items():
            conn.execute("""INSERT OR IGNORE INTO build_progress
                (goal_id, block_type, placed_count, total_count)
                VALUES (?, ?, 0, ?)""", (goal_id, block_type, total))
        conn.commit()
        conn.close()

    def update_progress(self, goal_id: int, block_type: str, placed_count: int):
        now = time.time()
        conn = self._conn()
        conn.execute("""UPDATE build_progress SET placed_count=?, last_updated=?
            WHERE goal_id=? AND block_type=?""",
            (placed_count, now, goal_id, block_type))
        conn.commit()
        conn.close()

    def increment_progress(self, goal_id: int, block_type: str, delta: int = 1):
        now = time.time()
        conn = self._conn()
        conn.execute("""UPDATE build_progress SET placed_count = placed_count + ?,
            last_updated=? WHERE goal_id=? AND block_type=?""",
            (delta, now, goal_id, block_type))
        conn.commit()
        conn.close()

    def get_progress(self, goal_id: int) -> list[dict]:
        conn = self._conn()
        rows = conn.execute(
            "SELECT * FROM build_progress WHERE goal_id=? ORDER BY block_type",
            (goal_id,)).fetchall()
        conn.close()
        return [dict(r) for r in rows]

    def get_progress_summary(self, goal_id: int) -> dict:
        """Return {'total_blocks': N, 'placed_blocks': M, 'percent': float}"""
        conn = self._conn()
        row = conn.execute(
            "SELECT COALESCE(SUM(total_count),0) as total, "
            "COALESCE(SUM(placed_count),0) as placed "
            "FROM build_progress WHERE goal_id=?", (goal_id,)).fetchone()
        conn.close()
        if row is None:
            return {"total_blocks": 0, "placed_blocks": 0, "percent": 0.0}
        total = row["total"]
        placed = row["placed"]
        pct = (placed / total * 100.0) if total > 0 else 0.0
        return {"total_blocks": total, "placed_blocks": placed, "percent": round(pct, 1)}

    # ── Phase 47: Block Classification & Phase Planning ───────

    # Deferred Block Registry — mirrors Java BuildSchematicTask categories
    # Category 1: Attachment-dependent (need wall/floor/ceiling to exist first)
    # Category 2: Interactable (right-click opens UI or toggles state)
    # Category 3: Non-placeable / special (excluded entirely)
    # Category 4: State-sensitive (placed normally with buildIgnoreDirection)

    DEFERRED_BLOCKS: set[str] = {
        # Category 1: Attachment-dependent
        "ladder", "torch", "wall_torch", "soul_torch", "soul_wall_torch",
        "redstone_torch", "redstone_wall_torch",
        "stone_button", "oak_button", "spruce_button", "birch_button",
        "jungle_button", "acacia_button", "dark_oak_button", "mangrove_button",
        "cherry_button", "bamboo_button", "crimson_button", "warped_button",
        "polished_blackstone_button",
        "oak_sign", "spruce_sign", "birch_sign", "jungle_sign", "acacia_sign",
        "dark_oak_sign", "mangrove_sign", "cherry_sign", "bamboo_sign",
        "crimson_sign", "warped_sign",
        "oak_wall_sign", "spruce_wall_sign", "birch_wall_sign",
        "jungle_wall_sign", "acacia_wall_sign", "dark_oak_wall_sign",
        "mangrove_wall_sign", "cherry_wall_sign", "bamboo_wall_sign",
        "crimson_wall_sign", "warped_wall_sign",
        "oak_hanging_sign", "spruce_hanging_sign", "birch_hanging_sign",
        "jungle_hanging_sign", "acacia_hanging_sign", "dark_oak_hanging_sign",
        "mangrove_hanging_sign", "cherry_hanging_sign", "bamboo_hanging_sign",
        "crimson_hanging_sign", "warped_hanging_sign",
        "oak_wall_hanging_sign", "spruce_wall_hanging_sign",
        "birch_wall_hanging_sign", "jungle_wall_hanging_sign",
        "acacia_wall_hanging_sign", "dark_oak_wall_hanging_sign",
        "mangrove_wall_hanging_sign", "cherry_wall_hanging_sign",
        "bamboo_wall_hanging_sign", "crimson_wall_hanging_sign",
        "warped_wall_hanging_sign",
        "lever", "lantern", "soul_lantern", "vine",
        "stone_pressure_plate", "oak_pressure_plate", "spruce_pressure_plate",
        "birch_pressure_plate", "jungle_pressure_plate", "acacia_pressure_plate",
        "dark_oak_pressure_plate", "mangrove_pressure_plate",
        "cherry_pressure_plate", "bamboo_pressure_plate",
        "crimson_pressure_plate", "warped_pressure_plate",
        "heavy_weighted_pressure_plate", "light_weighted_pressure_plate",
        "polished_blackstone_pressure_plate",
        "red_carpet", "white_carpet", "orange_carpet", "magenta_carpet",
        "light_blue_carpet", "yellow_carpet", "lime_carpet", "pink_carpet",
        "gray_carpet", "light_gray_carpet", "cyan_carpet", "purple_carpet",
        "blue_carpet", "brown_carpet", "green_carpet", "black_carpet",
        "moss_carpet", "pale_moss_carpet",
        "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
        "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
        "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose",
        "torchflower", "sunflower", "lilac", "rose_bush", "peony",
        "tall_grass", "large_fern", "short_grass", "fern",
        "dead_bush", "sweet_berry_bush",
        "potted_dandelion", "potted_poppy", "potted_blue_orchid",
        "potted_allium", "potted_azure_bluet", "potted_red_tulip",
        "potted_orange_tulip", "potted_white_tulip", "potted_pink_tulip",
        "potted_oxeye_daisy", "potted_cornflower", "potted_lily_of_the_valley",
        "potted_torchflower", "potted_oak_sapling", "potted_spruce_sapling",
        "potted_birch_sapling", "potted_jungle_sapling", "potted_acacia_sapling",
        "potted_dark_oak_sapling", "potted_cherry_sapling",
        "sugar_cane", "cactus", "snow",
        "dead_tube_coral_wall_fan", "dead_brain_coral_wall_fan",
        "dead_bubble_coral_wall_fan", "dead_fire_coral_wall_fan",
        "dead_horn_coral_wall_fan",
        "scaffolding", "chain", "lightning_rod", "lily_pad",
        "spore_blossom", "kelp", "kelp_plant", "sea_pickle",
        "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
        "acacia_sapling", "dark_oak_sapling", "cherry_sapling",
        "wheat", "carrots", "potatoes", "beetroots",
        "candle", "white_candle", "orange_candle", "magenta_candle",
        "light_blue_candle", "yellow_candle", "lime_candle", "pink_candle",
        "gray_candle", "light_gray_candle", "cyan_candle", "purple_candle",
        "blue_candle", "brown_candle", "green_candle", "red_candle",
        "black_candle",
        # Category 2: Interactable
        "oak_door", "spruce_door", "birch_door", "jungle_door", "acacia_door",
        "dark_oak_door", "mangrove_door", "cherry_door", "bamboo_door",
        "crimson_door", "warped_door", "iron_door",
        "oak_trapdoor", "spruce_trapdoor", "birch_trapdoor", "jungle_trapdoor",
        "acacia_trapdoor", "dark_oak_trapdoor", "mangrove_trapdoor",
        "cherry_trapdoor", "bamboo_trapdoor", "crimson_trapdoor",
        "warped_trapdoor", "iron_trapdoor",
        "oak_fence_gate", "spruce_fence_gate", "birch_fence_gate",
        "jungle_fence_gate", "acacia_fence_gate", "dark_oak_fence_gate",
        "mangrove_fence_gate", "cherry_fence_gate", "bamboo_fence_gate",
        "crimson_fence_gate", "warped_fence_gate",
        "chest", "trapped_chest", "ender_chest",
        "barrel", "shulker_box",
        "hopper", "furnace", "smoker", "blast_furnace",
        "crafting_table", "cartography_table", "loom", "stonecutter",
        "grindstone", "anvil", "enchanting_table", "brewing_stand",
        "lectern", "crafter", "dispenser", "dropper",
        "note_block", "repeater", "comparator", "jukebox",
        "campfire", "soul_campfire", "composter", "cauldron",
        "water_cauldron", "lava_cauldron", "powder_snow_cauldron",
        "red_bed", "orange_bed", "yellow_bed", "lime_bed", "green_bed",
        "cyan_bed", "light_blue_bed", "blue_bed", "purple_bed",
        "magenta_bed", "pink_bed", "white_bed", "light_gray_bed",
        "gray_bed", "black_bed", "brown_bed",
        "cake", "target",
    }

    EXCLUDED_BLOCKS: set[str] = {
        # Category 3: Non-placeable / special
        "piston_head", "moving_piston", "bubble_column", "fire", "soul_fire",
        "nether_portal", "end_portal", "end_gateway",
        "powder_snow", "farmland", "redstone_wire",
        "frosted_ice", "tall_seagrass", "seagrass",
        "cave_air", "void_air",
    }

    @classmethod
    def classify_block(cls, block_type: str) -> str:
        """Classify a block as 'structural', 'deferred', or 'excluded'.

        Strips 'minecraft:' prefix for matching.
        """
        clean = block_type.replace("minecraft:", "")
        if clean in cls.EXCLUDED_BLOCKS:
            return "excluded"
        if clean in cls.DEFERRED_BLOCKS:
            return "deferred"
        # Negative Bedrock IDs
        if clean.startswith("-") or clean.lstrip("-").isdigit():
            return "excluded"
        return "structural"

    def classify_guide_blocks(self, guide_id: int) -> dict[str, list[dict]]:
        """Classify all guide blocks into structural, deferred, excluded.

        Returns: {
            'structural': [block_dicts...],
            'deferred': [block_dicts...],
            'excluded': [block_dicts...],
        }
        """
        blocks = self.get_guide_blocks(guide_id)
        result = {"structural": [], "deferred": [], "excluded": []}
        for b in blocks:
            cat = self.classify_block(b["block_type"])
            result[cat].append(b)
        return result

    def get_guide_blocks_ordered(self, guide_id: int) -> list[dict]:
        """All blocks sorted by build order: Y ascending, then Z, then X.

        This ensures bottom-up construction with left-to-right sweep.
        """
        blocks = self.get_guide_blocks(guide_id)
        blocks.sort(key=lambda b: (b["offset_y"], b["offset_z"], b["offset_x"]))
        for i, b in enumerate(blocks):
            b["placement_order"] = i
        return blocks

    def get_blocks_for_y_range(
        self,
        guide_id: int,
        y_min: int,
        y_max: int,
    ) -> list[dict]:
        """Return all guide blocks (structural + deferred) within a Y-range.

        Excludes 'excluded' blocks (fire, portals, cave_air, etc.).
        Sorted by placement order (Y, Z, X).
        """
        classified = self.classify_guide_blocks(guide_id)
        result = []
        for b in classified["structural"] + classified["deferred"]:
            if y_min <= b["offset_y"] <= y_max:
                result.append(b)
        result.sort(key=lambda b: (b["offset_y"], b["offset_z"], b["offset_x"]))
        return result

    def split_into_phases(
        self,
        guide_id: int,
        max_blocks_per_phase: int = 256,
        layer_height: int = 2,
    ) -> list[dict]:
        """Split structural blocks into build phases by Y-layer groups.

        Each phase contains blocks within a Y-layer band of `layer_height`.
        Phases are further split if they exceed `max_blocks_per_phase`.
        Also reports deferred block counts for the same Y-range.

        Returns: list of phase dicts:
            [{
                'index': int,
                'phase_type': 'structural',
                'y_min': int, 'y_max': int,
                'block_count': int,
                'bom': {block_type: count},
                'block_indices': [placement_order...],
                'deferred_count': int,
                'deferred_bom': {block_type: count},
                'total_block_count': int,
            }, ...]
        """
        classified = self.classify_guide_blocks(guide_id)
        structural = classified["structural"]
        deferred = classified["deferred"]
        structural.sort(key=lambda b: (b["offset_y"], b["offset_z"], b["offset_x"]))

        if not structural:
            return []

        # Group by Y-layer band
        min_y = structural[0]["offset_y"]
        max_y = structural[-1]["offset_y"]

        phases = []
        phase_idx = 0

        for band_start in range(min_y, max_y + 1, layer_height):
            band_end = band_start + layer_height - 1
            band_blocks = [
                b for b in structural
                if band_start <= b["offset_y"] <= band_end
            ]
            if not band_blocks:
                continue

            # Deferred blocks in the same Y-range
            band_deferred = [
                b for b in deferred
                if band_start <= b["offset_y"] <= band_end
            ]
            d_bom: dict[str, int] = {}
            for b in band_deferred:
                bt = b["block_type"]
                d_bom[bt] = d_bom.get(bt, 0) + 1

            # Split large bands into sub-phases
            for chunk_start in range(0, len(band_blocks), max_blocks_per_phase):
                chunk = band_blocks[chunk_start:chunk_start + max_blocks_per_phase]
                bom: dict[str, int] = {}
                for b in chunk:
                    bt = b["block_type"]
                    bom[bt] = bom.get(bt, 0) + 1

                phases.append({
                    "index": phase_idx,
                    "phase_type": "structural",
                    "y_min": band_start,
                    "y_max": band_end,
                    "block_count": len(chunk),
                    "bom": bom,
                    "block_indices": [b["placement_order"] for b in chunk],
                    "deferred_count": len(band_deferred),
                    "deferred_bom": d_bom,
                    "total_block_count": len(chunk) + len(band_deferred),
                })
                phase_idx += 1

        return phases

    def get_deferred_bom(self, guide_id: int) -> dict[str, int]:
        """Return BOM for deferred (finishing) blocks only."""
        classified = self.classify_guide_blocks(guide_id)
        bom: dict[str, int] = {}
        for b in classified["deferred"]:
            bt = b["block_type"]
            bom[bt] = bom.get(bt, 0) + 1
        return bom

    def get_structural_bom(self, guide_id: int) -> dict[str, int]:
        """Return BOM for structural blocks only."""
        classified = self.classify_guide_blocks(guide_id)
        bom: dict[str, int] = {}
        for b in classified["structural"]:
            bt = b["block_type"]
            bom[bt] = bom.get(bt, 0) + 1
        return bom
