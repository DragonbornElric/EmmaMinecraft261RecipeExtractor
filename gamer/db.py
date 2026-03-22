"""
Database layer for the Minecraft GOAP subsystem.

Manages ``minecraft.db`` — an independent SQLite database holding build guides,
world state, POI registry, and related tables.  Completely decoupled from the
host project's database.
"""

from __future__ import annotations

import os
import sqlite3

from gamer import config as _cfg

_db_path: str | None = None


def get_db_path() -> str:
    """Return the resolved path to ``minecraft.db``."""
    global _db_path
    if _db_path is not None:
        return _db_path
    # Config can override; default is minecraft.db next to the package root.
    configured = _cfg.get("db_path")
    if configured:
        _db_path = os.path.abspath(configured)
    else:
        _db_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), "minecraft.db")
    return _db_path


def set_db_path(path: str) -> None:
    """Override the database path (call before init_db)."""
    global _db_path
    _db_path = os.path.abspath(path)


def get_connection(db_path: str | None = None) -> sqlite3.Connection:
    """Open a connection to minecraft.db with WAL mode and Row factory."""
    path = db_path or get_db_path()
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db(db_path: str | None = None) -> None:
    """Create all tables if they don't exist."""
    conn = get_connection(db_path)
    try:
        _create_tables(conn)
        conn.commit()
    finally:
        conn.close()


def _create_tables(conn: sqlite3.Connection) -> None:
    """Schema for all Minecraft-specific tables."""
    conn.executescript("""
        -- Build Guides (Phase 28)
        CREATE TABLE IF NOT EXISTS build_guides (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            name          TEXT NOT NULL,
            description   TEXT,
            source        TEXT NOT NULL DEFAULT 'custom',
            source_format TEXT,
            dimensions    TEXT,
            block_count   INTEGER DEFAULT 0,
            difficulty    INTEGER DEFAULT 1,
            tags          TEXT DEFAULT '[]',
            created_at    REAL NOT NULL DEFAULT (strftime('%s','now'))
        );

        CREATE TABLE IF NOT EXISTS build_guide_blocks (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            guide_id        INTEGER NOT NULL REFERENCES build_guides(id) ON DELETE CASCADE,
            block_type      TEXT NOT NULL,
            block_state     TEXT DEFAULT '{}',
            offset_x        INTEGER NOT NULL,
            offset_y        INTEGER NOT NULL,
            offset_z        INTEGER NOT NULL,
            placement_order INTEGER NOT NULL DEFAULT 0,
            UNIQUE(guide_id, offset_x, offset_y, offset_z)
        );

        -- Block Substitutions
        CREATE TABLE IF NOT EXISTS block_substitutions (
            id               INTEGER PRIMARY KEY AUTOINCREMENT,
            original_block   TEXT NOT NULL,
            substitute_block TEXT NOT NULL,
            priority         INTEGER NOT NULL DEFAULT 10,
            context          TEXT DEFAULT 'any',
            reason           TEXT,
            UNIQUE(original_block, substitute_block, context)
        );

        -- Resource Methods
        CREATE TABLE IF NOT EXISTS block_resources (
            id                    INTEGER PRIMARY KEY AUTOINCREMENT,
            block_type            TEXT NOT NULL,
            method                TEXT NOT NULL,
            ingredients           TEXT DEFAULT '[]',
            output_qty            INTEGER DEFAULT 1,
            tool_required         TEXT,
            time_cost             REAL DEFAULT 0,
            resource_cost         REAL DEFAULT 0,
            prerequisite_build_id INTEGER REFERENCES build_guides(id),
            UNIQUE(block_type, method)
        );

        -- World State (discovered blocks)
        CREATE TABLE IF NOT EXISTS world_state (
            x             INTEGER NOT NULL,
            y             INTEGER NOT NULL,
            z             INTEGER NOT NULL,
            block_type    TEXT NOT NULL,
            state         TEXT NOT NULL DEFAULT 'discovered',
            discovered_at REAL NOT NULL DEFAULT (strftime('%s','now')),
            updated_at    REAL NOT NULL DEFAULT (strftime('%s','now')),
            PRIMARY KEY (x, y, z)
        );

        -- Build Goals
        CREATE TABLE IF NOT EXISTS build_goals (
            id                    INTEGER PRIMARY KEY AUTOINCREMENT,
            guide_id              INTEGER REFERENCES build_guides(id),
            name                  TEXT NOT NULL,
            status                TEXT NOT NULL DEFAULT 'future',
            location_x            INTEGER,
            location_y            INTEGER,
            location_z            INTEGER,
            functional_goal       TEXT,
            aesthetic_decisions   TEXT DEFAULT '{}',
            substitutions_applied TEXT DEFAULT '{}',
            priority_weight       REAL NOT NULL DEFAULT 0.5,
            stream_session        TEXT,
            started_at            REAL,
            completed_at          REAL,
            created_at            REAL NOT NULL DEFAULT (strftime('%s','now'))
        );

        -- Build Progress (per-block tracking)
        CREATE TABLE IF NOT EXISTS build_progress (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            goal_id      INTEGER NOT NULL REFERENCES build_goals(id) ON DELETE CASCADE,
            block_type   TEXT NOT NULL,
            placed_count INTEGER NOT NULL DEFAULT 0,
            total_count  INTEGER NOT NULL DEFAULT 0,
            last_updated REAL NOT NULL DEFAULT (strftime('%s','now')),
            UNIQUE(goal_id, block_type)
        );

        -- Container Cache (persistent container content tracking)
        CREATE TABLE IF NOT EXISTS container_cache (
            x           INTEGER NOT NULL,
            y           INTEGER NOT NULL,
            z           INTEGER NOT NULL,
            type        TEXT NOT NULL,
            items       TEXT NOT NULL DEFAULT '[]',
            total_slots INTEGER NOT NULL DEFAULT 27,
            empty_slots INTEGER NOT NULL DEFAULT 27,
            updated_at  REAL NOT NULL DEFAULT (strftime('%s','now')),
            PRIMARY KEY (x, y, z)
        );

        -- POI Registry (Phase 44)
        CREATE TABLE IF NOT EXISTS poi_registry (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            name        TEXT NOT NULL COLLATE NOCASE,
            x           INTEGER NOT NULL,
            y           INTEGER NOT NULL,
            z           INTEGER NOT NULL,
            poi_type    TEXT NOT NULL DEFAULT 'landmark',
            dimension   TEXT NOT NULL DEFAULT 'minecraft:overworld',
            source      TEXT NOT NULL DEFAULT 'manual',
            source_id   INTEGER,
            description TEXT,
            created_at  REAL NOT NULL DEFAULT (strftime('%s','now')),
            updated_at  REAL NOT NULL DEFAULT (strftime('%s','now')),
            UNIQUE(name, dimension)
        );
    """)

    # R-tree spatial index (can't be in executescript with IF NOT EXISTS
    # for virtual tables on older SQLite)
    try:
        conn.execute("""
            CREATE VIRTUAL TABLE IF NOT EXISTS world_state_rtree USING rtree(
                id,
                min_x, max_x,
                min_y, max_y,
                min_z, max_z
            )
        """)
    except sqlite3.OperationalError:
        pass  # already exists

    # Migration: add dimension column to poi_registry if missing
    cols = {row[1] for row in conn.execute("PRAGMA table_info(poi_registry)").fetchall()}
    if "dimension" not in cols:
        conn.execute(
            "ALTER TABLE poi_registry ADD COLUMN dimension TEXT NOT NULL DEFAULT 'minecraft:overworld'"
        )
        # Recreate unique index to include dimension
        try:
            conn.execute("DROP INDEX IF EXISTS sqlite_autoindex_poi_registry_1")
        except sqlite3.OperationalError:
            pass
        conn.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_poi_name_dim ON poi_registry(name, dimension)"
        )
