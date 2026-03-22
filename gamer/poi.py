"""POI registry and spatial translation layer for Minecraft.

Converts between raw XYZ coordinates and human-readable landmark
references with compass directions and block distances.  Emma never
sees raw coordinates — she reasons in landmarks like "the bakery is
15 blocks east of the village wall."

Tables live in minecraft.db, managed by gamer.db.
"""

import logging
import math
import sqlite3
import time
from typing import Optional

from gamer.db import get_db_path

log = logging.getLogger(__name__)

# Minecraft compass: +X = East, -X = West, +Z = South, -Z = North
_COMPASS_NAMES = {
    "N": "north", "NE": "northeast", "E": "east", "SE": "southeast",
    "S": "south", "SW": "southwest", "W": "west", "NW": "northwest",
}


class POIRegistry:
    """Query layer and spatial math for the Minecraft POI system."""

    def __init__(self, db_path: str | None = None):
        self.db_path = db_path or get_db_path()

    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        return conn

    # ── CRUD ───────────────────────────────────────────────────────

    def upsert_poi(
        self,
        name: str,
        x: int, y: int, z: int,
        poi_type: str = "landmark",
        dimension: str = "minecraft:overworld",
        source: str = "manual",
        source_id: int | None = None,
        description: str | None = None,
    ) -> int:
        """Insert or update a POI by name+dimension.  Returns the POI id."""
        now = time.time()
        conn = self._conn()
        c = conn.cursor()
        c.execute("""
            INSERT INTO poi_registry
                (name, x, y, z, poi_type, dimension, source, source_id,
                 description, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(name, dimension) DO UPDATE SET
                x=excluded.x, y=excluded.y, z=excluded.z,
                poi_type=excluded.poi_type, source=excluded.source,
                source_id=excluded.source_id,
                description=excluded.description,
                updated_at=excluded.updated_at
        """, (name, x, y, z, poi_type, dimension, source, source_id,
              description, now, now))
        poi_id = c.lastrowid
        conn.commit()
        conn.close()
        return poi_id

    def delete_poi(self, name: str) -> bool:
        """Delete a POI by name.  Returns True if deleted."""
        conn = self._conn()
        c = conn.cursor()
        c.execute(
            "DELETE FROM poi_registry WHERE name = ? COLLATE NOCASE",
            (name,),
        )
        deleted = c.rowcount > 0
        conn.commit()
        conn.close()
        return deleted

    def get_poi(self, name: str) -> Optional[dict]:
        """Look up a single POI by name (exact then fuzzy)."""
        conn = self._conn()
        row = conn.execute(
            "SELECT * FROM poi_registry WHERE name = ? COLLATE NOCASE",
            (name,),
        ).fetchone()
        if not row:
            row = conn.execute(
                "SELECT * FROM poi_registry WHERE name LIKE ? COLLATE NOCASE",
                (f"%{name}%",),
            ).fetchone()
        conn.close()
        return dict(row) if row else None

    def get_all_pois(self) -> list[dict]:
        """Return all registered POIs."""
        conn = self._conn()
        rows = conn.execute(
            "SELECT * FROM poi_registry ORDER BY name COLLATE NOCASE",
        ).fetchall()
        conn.close()
        return [dict(r) for r in rows]

    def resolve_name(self, name: str) -> Optional[tuple[int, int, int]]:
        """Resolve a POI name to (x, y, z) or None."""
        poi = self.get_poi(name)
        if poi:
            return (poi["x"], poi["y"], poi["z"])
        return None

    # ── Spatial math ───────────────────────────────────────────────

    @staticmethod
    def compass_direction(dx: float, dz: float) -> str:
        """Return 8-way compass label from delta-x, delta-z.

        Minecraft: +X = East, +Z = South.
        Uses atan2(dz, dx) mapped to 8 sectors of 45 degrees each.
        """
        if abs(dx) < 1 and abs(dz) < 1:
            return "here"

        # atan2 with MC coords: angle 0 = East (+X), pi/2 = South (+Z)
        deg = math.degrees(math.atan2(dz, dx)) % 360

        # Sector centers: E=0, SE=45, S=90, SW=135, W=180, NW=225, N=270, NE=315
        sectors = [
            ("E", 0), ("SE", 45), ("S", 90), ("SW", 135),
            ("W", 180), ("NW", 225), ("N", 270), ("NE", 315),
        ]
        best = "E"
        best_diff = 360.0
        for label, center in sectors:
            diff = abs(deg - center)
            if diff > 180:
                diff = 360 - diff
            if diff < best_diff:
                best_diff = diff
                best = label
        return best

    @staticmethod
    def horizontal_distance(
        x1: float, z1: float, x2: float, z2: float,
    ) -> float:
        """2D horizontal distance in blocks (ignoring Y)."""
        return math.sqrt((x2 - x1) ** 2 + (z2 - z1) ** 2)

    @staticmethod
    def format_relative(
        name: str,
        dx: float, dz: float,
        dist: float,
        dy: float | None = None,
    ) -> str:
        """Format a POI as relative text from the viewer's position.

        ``dx, dz`` are FROM the viewer TO the POI.  ``dist`` is the
        horizontal distance.

        Examples:
            "Oak Cabin -- 47 blocks east"
            "Village Wall -- 12 blocks northwest, 5 blocks above"
            "Town Square -- right here"
        """
        if dist < 3:
            return f"{name} -- right here"

        compass = POIRegistry.compass_direction(dx, dz)
        compass_full = _COMPASS_NAMES.get(compass, compass)
        text = f"{name} -- {int(dist)} blocks {compass_full}"

        if dy is not None and abs(dy) >= 3:
            vert = "above" if dy > 0 else "below"
            text += f", {int(abs(dy))} blocks {vert}"

        return text

    @staticmethod
    def format_relative_with_coords(
        name: str,
        poi_x: int, poi_y: int, poi_z: int,
        dx: float, dz: float,
        dist: float,
        dy: float | None = None,
    ) -> str:
        """Format a POI with both coordinates and relative description.

        LLMs reason better with actual numbers than word problems.
        """
        if dist < 3:
            return f"{name} at ({poi_x}, {poi_y}, {poi_z}) -- right here"

        compass = POIRegistry.compass_direction(dx, dz)
        compass_full = _COMPASS_NAMES.get(compass, compass)
        return (
            f"{name} at ({poi_x}, {poi_y}, {poi_z}) "
            f"-- {int(dist)} blocks {compass_full}"
        )

    # ── City overview ──────────────────────────────────────────────

    def city_overview(
        self,
        cx: float, cy: float, cz: float,
        max_distance: float | None = None,
    ) -> str:
        """Return all POIs as relative-language text from a center point.

        Output never contains raw XYZ — only landmarks, compass
        directions, and block distances.  Sorted by distance (nearest
        first).
        """
        pois = self.get_all_pois()
        if not pois:
            return "No known locations registered yet."

        entries: list[tuple[float, str]] = []

        for poi in pois:
            dx = poi["x"] - cx
            dz = poi["z"] - cz
            dy = poi["y"] - cy
            dist = self.horizontal_distance(cx, cz, poi["x"], poi["z"])

            if max_distance and dist > max_distance:
                continue

            line = self.format_relative_with_coords(
                poi["name"], poi["x"], poi["y"], poi["z"],
                dx, dz, dist, dy,
            )

            if poi.get("poi_type") and poi["poi_type"] != "landmark":
                line += f" [{poi['poi_type']}]"
            if poi.get("description"):
                line += f" ({poi['description']})"

            entries.append((dist, line))

        if not entries:
            return "No known locations within range."

        entries.sort(key=lambda e: e[0])

        lines = [f"[CITY OVERVIEW] Your position: ({int(cx)}, {int(cy)}, {int(cz)})"]
        for _, line in entries:
            lines.append(f"  - {line}")
        return "\n".join(lines)

    # ── Portal helpers ─────────────────────────────────────────────

    def save_portal(
        self,
        name: str,
        x: int, y: int, z: int,
        dimension: str,
        portal_type: str = "nether_portal",
    ) -> int:
        """Save a portal as a POI.  Returns the POI id."""
        return self.upsert_poi(
            name=name, x=x, y=y, z=z,
            poi_type=portal_type,
            dimension=dimension,
            source="portal",
        )

    def get_portals(self, dimension: str | None = None) -> list[dict]:
        """Return portal POIs, optionally filtered by dimension."""
        conn = self._conn()
        if dimension:
            rows = conn.execute(
                "SELECT * FROM poi_registry "
                "WHERE poi_type IN ('nether_portal', 'end_portal') "
                "AND dimension = ? ORDER BY name COLLATE NOCASE",
                (dimension,),
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM poi_registry "
                "WHERE poi_type IN ('nether_portal', 'end_portal') "
                "ORDER BY name COLLATE NOCASE",
            ).fetchall()
        conn.close()
        return [dict(r) for r in rows]

    def get_nearest_portal(
        self,
        x: float, z: float,
        dimension: str,
        portal_type: str = "nether_portal",
    ) -> Optional[dict]:
        """Return the nearest portal of the given type in dimension, or None."""
        conn = self._conn()
        rows = conn.execute(
            "SELECT * FROM poi_registry "
            "WHERE poi_type = ? AND dimension = ?",
            (portal_type, dimension),
        ).fetchall()
        conn.close()

        if not rows:
            return None

        best = None
        best_dist = float("inf")
        for row in rows:
            poi = dict(row)
            dist = self.horizontal_distance(x, z, poi["x"], poi["z"])
            if dist < best_dist:
                best_dist = dist
                best = poi
        return best

    # ── Auto-sync from build goals ─────────────────────────────────

    def sync_from_build_goals(self):
        """Scan build_goals and upsert POIs for any goal with location set.

        Idempotent — called on bridge connect.
        """
        conn = self._conn()
        rows = conn.execute(
            "SELECT id, name, location_x, location_y, location_z "
            "FROM build_goals "
            "WHERE location_x IS NOT NULL "
            "AND location_y IS NOT NULL "
            "AND location_z IS NOT NULL",
        ).fetchall()
        conn.close()

        for row in rows:
            goal = dict(row)
            self.sync_single_goal(
                goal["id"], goal["name"],
                goal["location_x"], goal["location_y"], goal["location_z"],
            )

    def sync_single_goal(
        self,
        goal_id: int, name: str,
        x: int | None, y: int | None, z: int | None,
    ):
        """Sync a single build goal to the POI registry."""
        if x is None or y is None or z is None:
            return

        poi_name = name
        if poi_name.lower().startswith("build: "):
            poi_name = poi_name[7:]

        self.upsert_poi(
            name=poi_name,
            x=x, y=y, z=z,
            poi_type="building",
            source="build_goal",
            source_id=goal_id,
        )
