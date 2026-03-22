"""Synchronous WebSocket client for the Emmatone bridge mod (Phase 29).

Follows the animaze_bridge.py pattern: sync websocket-client library,
dedicated listener thread, callback dispatch.  The Orchestrator calls
command methods from its sync _process() loop; responses arrive on the
listener thread and are signalled via threading.Event.
"""

from __future__ import annotations

import json
import logging
import math
import threading
import time
import uuid
from collections.abc import Callable
from typing import Any

import websocket  # websocket-client (sync)

from gamer import config as _cfg
from gamer.build_db import BuildDB
from gamer.smelting_tracker import SmeltingTracker

log = logging.getLogger(__name__)

# ── Constants ──────────────────────────────────────────────────

DEFAULT_HOST = "localhost"
DEFAULT_PORT = 8765
RECONNECT_DELAY = 2.0


def _get_configured_port() -> int:
    """Read player_port from gamer config, fallback to DEFAULT_PORT."""
    return _cfg.get("player_port", DEFAULT_PORT)

# Priority thresholds for survival override
HEALTH_CRITICAL = 6.0
HUNGER_CRITICAL = 4.0

# Personality weights (encoded here, narrated by LLM)
SAFETY_PREFERENCE = 0.8
AESTHETIC_PREFERENCE = 0.6
EFFICIENCY_PREFERENCE = 0.7


class EmmatoneClient:
    """Sync WebSocket client for the Phase 29 Minecraft bridge mod.

    All command methods block until a response arrives or timeout elapses.
    Event handlers run on the listener thread — keep them fast.
    """

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT,
                 build_db: BuildDB | None = None):
        self.url = f"ws://{host}:{port}"
        self.ws: websocket.WebSocketApp | None = None
        self.connected = False
        self.build_db = build_db or BuildDB()

        self._event_handlers: dict[str, list[Callable]] = {}
        self._pending: dict[str, dict] = {}  # cmd_id → {event, result}
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None

        # Last known state (updated by events)
        self._health: float = 20.0
        self._hunger: float = 20.0
        self._position: dict[str, float] = {"x": 0, "y": 0, "z": 0}
        self._active_task: str | None = None
        self._active_goal_id: int | None = None
        self._recent_events: list[dict] = []  # ring buffer, max 20
        self._inventory: list[dict] = []
        self._selected_slot: int = 0
        self._last_death: dict | None = None  # persists after respawn
        self._player_stats: dict | None = None  # Mojang statistics cache
        self._last_armor_tier: str | None = None  # Phase 37 avatar sync
        self._idle_timer: threading.Timer | None = None  # cancellable auto-idle
        self._idle_suppressed: bool = False  # suppress auto-idle during builds
        self._idle_suppressed_at: float | None = None  # timestamp for safety timeout
        self._smelting_tracker = SmeltingTracker()
        self._container_cache: dict[tuple[int, int, int], dict] = {}  # (x,y,z) → event data

    # ── Connection ────────────────────────────────────────────

    def connect(self, reconnect: bool = True):
        """Start WebSocket connection on a daemon thread.

        Args:
            reconnect: If True, auto-reconnect on disconnect (default for
                       brain usage).  If False, fail immediately on error
                       (use for manual GUI connect).
        """
        if self._thread and self._thread.is_alive():
            log.debug("EmmatoneClient already connected")
            return

        self.ws = websocket.WebSocketApp(
            self.url,
            on_open=self._on_open,
            on_message=self._on_message,
            on_close=self._on_close,
            on_error=self._on_error,
        )
        kwargs = {"reconnect": RECONNECT_DELAY} if reconnect else {}
        self._thread = threading.Thread(
            target=self.ws.run_forever,
            kwargs=kwargs,
            daemon=True,
            name="EmmatoneWS",
        )
        self._thread.start()
        log.info("EmmatoneClient connecting to %s", self.url)

    def disconnect(self):
        """Close connection cleanly."""
        if self.ws:
            self.ws.close()
        self.connected = False
        log.info("EmmatoneClient disconnected")

    def wait_for_connection(self, timeout: float = 10.0) -> bool:
        """Block until connected or timeout.  Returns True if connected."""
        deadline = time.monotonic() + timeout
        while not self.connected and time.monotonic() < deadline:
            time.sleep(0.1)
        return self.connected

    # ── Commands (called from sync Orchestrator) ──────────────

    def goto(self, x: int, y: int | None, z: int, timeout: float = 30.0) -> dict:
        """Navigate to coordinates.  y=None uses GoalXZ (any Y level)."""
        params = {"x": int(x), "z": int(z)}
        if y is not None:
            params["y"] = int(y)
        return self._send_command("goto", params, timeout)

    def attack(self, radius: int = 5, timeout: float = 5.0) -> dict:
        """Swing at the nearest hostile mob within radius."""
        return self._send_command("attack", {"radius": radius}, timeout)

    def mine(self, block_type: str, quantity: int = 1,
             timeout: float = 30.0) -> dict:
        """Mine N blocks of type.  Returns response with task_id."""
        return self._send_command("mine", {
            "block_type": block_type, "quantity": quantity,
        }, timeout)

    def farm(self, range: int = 100, x: int = None, y: int = None,
             z: int = None, timeout: float = 5.0) -> dict:
        """Start Emmatone's FarmProcess to harvest/replant crops.

        If x/y/z given, farms around that center; otherwise around player.
        """
        params = {"range": range}
        if x is not None and y is not None and z is not None:
            params.update({"x": int(x), "y": int(y), "z": int(z)})
        return self._send_command("farm", params, timeout)

    def create_farm(self, radius: int = 5, x: int = None, y: int = None,
                    z: int = None, timeout: float = 5.0) -> dict:
        """Till dirt/grass and plant seeds to create a new farm plot.

        If x/y/z given, farms at that center; otherwise centers on player.
        """
        params = {"radius": int(radius)}
        if x is not None and y is not None and z is not None:
            params.update({"x": int(x), "y": int(y), "z": int(z)})
        return self._send_command("create_farm", params, timeout)

    def build(self, guide_id: int, origin_x: int, origin_y: int,
              origin_z: int, goal_id: int | None = None,
              timeout: float = 30.0) -> dict:
        """Build from a guide at origin.  Fetches block data from BuildDB.

        If *goal_id* is provided, diffs guide blocks against world_state
        so only un-placed blocks are sent to Emmatone.  This makes resume
        reliable — no dependence on Emmatone's in-memory state.
        """
        blocks = self.build_db.get_guide_blocks(guide_id)

        # Get guide name for display
        guide = self.build_db.get_guide(guide_id)
        build_name = guide["name"] if guide and "name" in guide else f"guide_{guide_id}"

        if goal_id is not None:
            blocks = self._filter_placed_blocks(
                blocks, origin_x, origin_y, origin_z, goal_id,
            )
            if not blocks:
                return {"status": "already_complete", "remaining": 0}

        remaining = len(blocks)

        # Map Python BuildDB field names to Java GuideSchematicAdapter format:
        #   block_type → type, offset_x/y/z → x/y/z, block_state → state (string)
        mapped_blocks = []
        for b in blocks:
            entry = {
                "type": b["block_type"],
                "x": b["offset_x"],
                "y": b["offset_y"],
                "z": b["offset_z"],
            }
            # Convert block_state dict to "key=val,key=val" string
            bs = b.get("block_state")
            if bs and isinstance(bs, dict) and bs:
                entry["state"] = ",".join(f"{k}={v}" for k, v in bs.items())
            mapped_blocks.append(entry)

        # Normalize negative offsets — Java's schematic adapter uses 0-based
        # arrays and crashes on negative coordinates. Shift all block positions so
        # the minimum x/y/z is 0, adjusting origin to compensate (world position
        # = origin + offset stays identical).
        if mapped_blocks:
            min_x = min(b["x"] for b in mapped_blocks)
            min_y = min(b["y"] for b in mapped_blocks)
            min_z = min(b["z"] for b in mapped_blocks)
            if min_x < 0 or min_y < 0 or min_z < 0:
                shift_x = -min_x if min_x < 0 else 0
                shift_y = -min_y if min_y < 0 else 0
                shift_z = -min_z if min_z < 0 else 0
                for b in mapped_blocks:
                    b["x"] += shift_x
                    b["y"] += shift_y
                    b["z"] += shift_z
                origin_x -= shift_x
                origin_y -= shift_y
                origin_z -= shift_z
                log.info("Normalized negative offsets: shift=(%d,%d,%d), new origin=(%d,%d,%d)",
                         shift_x, shift_y, shift_z, origin_x, origin_y, origin_z)

        # Cancel pending auto-idle and suppress future auto-idle during build
        self._cancel_idle_timer()
        self._idle_suppressed = True
        self._idle_suppressed_at = time.time()

        result = self._send_command("build", {
            "guide_id": guide_id,
            "name": build_name,
            "origin": {"x": origin_x, "y": origin_y, "z": origin_z},
            "blocks": mapped_blocks,
        }, timeout)
        if isinstance(result, dict):
            result["remaining"] = remaining
            # Propagate Java error details
            if result.get("status") == "error" and "error" in result:
                result["reason"] = result["error"]
        return result

    def _filter_placed_blocks(
        self,
        blocks: list[dict],
        origin_x: int, origin_y: int, origin_z: int,
        goal_id: int,
    ) -> list[dict]:
        """Remove blocks already placed in world_state.

        Accounts for substitutions: if the guide says oak_planks but the
        goal applied a spruce_planks substitution, a spruce_planks block
        at that position counts as placed.
        """
        # Build substitution lookup from goal record
        goal = self.build_db.get_goal(goal_id)
        sub_map: dict[str, str] = {}  # original → substitute
        if goal and goal.get("substitutions_applied"):
            subs = goal["substitutions_applied"]
            # Handle both list-of-dicts and dict formats
            if isinstance(subs, list):
                for s in subs:
                    sub_map[s["original"]] = s["substitute"]
            elif isinstance(subs, dict):
                sub_map = subs

        # Fetch placed blocks from world_state in the build area
        # Compute proper bounding box (handles negative offsets from overhangs)
        all_x = [b.get("offset_x", 0) for b in blocks]
        all_y = [b.get("offset_y", 0) for b in blocks]
        all_z = [b.get("offset_z", 0) for b in blocks]
        # World-space bounding box with 2-block buffer
        buf = 2
        wx_min = origin_x + min(all_x) - buf
        wx_max = origin_x + max(all_x) + buf
        wy_min = origin_y + min(all_y) - buf
        wy_max = origin_y + max(all_y) + buf
        wz_min = origin_z + min(all_z) - buf
        wz_max = origin_z + max(all_z) + buf
        # Convert to center+radius for get_blocks_in_radius
        cx = (wx_min + wx_max) // 2
        cy = (wy_min + wy_max) // 2
        cz = (wz_min + wz_max) // 2
        radius = max(wx_max - cx, cx - wx_min,
                     wy_max - cy, cy - wy_min,
                     wz_max - cz, cz - wz_min)
        placed = self.build_db.get_blocks_in_radius(cx, cy, cz, radius)

        # Index placed blocks by world position
        placed_at: dict[tuple[int, int, int], str] = {}
        for p in placed:
            if p.get("state") == "placed":
                placed_at[(p["x"], p["y"], p["z"])] = p["block_type"]

        remaining: list[dict] = []
        for block in blocks:
            wx = origin_x + block["offset_x"]
            wy = origin_y + block["offset_y"]
            wz = origin_z + block["offset_z"]

            world_block = placed_at.get((wx, wy, wz))
            if world_block is None:
                remaining.append(block)
                continue

            # Check if what's placed matches expected type (or substitution)
            expected = block["block_type"]
            substituted = sub_map.get(expected, expected)
            if world_block != expected and world_block != substituted:
                remaining.append(block)  # wrong block — needs replacing

        # Re-sequence placement_order to maintain bottom-up ordering
        remaining.sort(key=lambda b: (b["offset_y"], b["offset_z"], b["offset_x"]))
        for i, b in enumerate(remaining):
            b["placement_order"] = i

        log.info(
            "Build resume: %d/%d blocks remaining (goal %d)",
            len(remaining), len(remaining) + len(placed_at), goal_id,
        )
        return remaining

    # ── Litematica Printer (Phase 49) ─────────────────────────

    def litematica_load(self, file: str, origin_x: int, origin_y: int,
                        origin_z: int, timeout: float = 10.0) -> dict:
        """Load a .litematic schematic and create placement at origin."""
        return self._send_command("litematica", {
            "action": "load",
            "file": file,
            "origin": {"x": int(origin_x), "y": int(origin_y), "z": int(origin_z)},
        }, timeout)

    def litematica_unload(self, timeout: float = 5.0) -> dict:
        """Remove current schematic placement."""
        return self._send_command("litematica", {"action": "unload"}, timeout)

    def litematica_printer(self, enabled: bool = True,
                           timeout: float = 5.0) -> dict:
        """Toggle the Litematica Printer on/off."""
        return self._send_command("litematica", {
            "action": "printer", "enabled": enabled,
        }, timeout)

    def litematica_status(self, timeout: float = 5.0) -> dict:
        """Query Litematica/Printer state and progress."""
        return self._send_command("litematica", {"action": "status"}, timeout)

    # ── Task Control ───────────────────────────────────────────

    def cancel(self, timeout: float = 5.0) -> dict:
        """Cancel current Emmatone task."""
        return self._send_command("cancel", {}, timeout)

    def respawn(self, timeout: float = 5.0) -> dict:
        """Click the respawn button after death."""
        return self._send_command("respawn", {}, timeout)

    def _schedule_idle(self, delay: float | None = None):
        """Schedule auto-idle after a delay, cancelling any pending timer."""
        if delay is None:
            delay = _cfg.get("auto_idle_delay_seconds", 5.0)
        if self._idle_timer is not None:
            self._idle_timer.cancel()
        self._idle_timer = threading.Timer(delay, self._enter_idle)
        self._idle_timer.daemon = True
        self._idle_timer.start()

    def _cancel_idle_timer(self):
        """Cancel any pending auto-idle timer."""
        if self._idle_timer is not None:
            self._idle_timer.cancel()
            self._idle_timer = None

    def _enter_idle(self):
        """Start EmmaClef idle mode — keeps MobDefenseChain + FoodChain active.

        Called automatically on connect, after task completion, and after respawn
        so Emma always defends herself even between explicit tasks.
        Skips if a non-idle task is already running (e.g. build in progress).
        """
        self._idle_timer = None
        self._active_task = None  # now safe to show idle — delay has elapsed
        if not self.connected:
            return
        if self._idle_suppressed:
            # Safety timeout: clear stuck suppression after 5 minutes
            if (self._idle_suppressed_at
                    and time.time() - self._idle_suppressed_at > 300):
                log.warning("Auto-idle suppression expired (>5 min) — clearing")
                self._idle_suppressed = False
                self._idle_suppressed_at = None
            else:
                log.warning("Skipping auto-idle — suppressed (build in progress)")
                return
        # Expire stale smelting jobs (collected/failed older than 1 hour)
        self._smelting_tracker.expire_stale()

        # Before idling, collect any ready smelting output
        try:
            ready_jobs = self._smelting_tracker.get_ready_jobs()
            if ready_jobs:
                job = ready_jobs[0]
                log.info("Auto-collecting smelting output before idle: %s (%s x%d)",
                         job.job_id, job.output_item, job.expected_output_count)
                result = self.collect_smelting_output(job.job_id)
                log.info("Smelting collection result: %s", result.get("status"))
                # Re-schedule idle after collection finishes
                self._schedule_idle(delay=2.0)
                return
        except Exception as exc:
            log.warning("Smelting auto-collect failed: %s", exc)

        # Java-side UserTaskChain.runTask() → setTask() handles replacing
        # active tasks safely, so we no longer gate on TaskRegistry status
        # (which could be stale after chain preemption).
        try:
            result = self.emmaclef_task("idle")
            log.info("Auto-idle started: %s", result.get("data", {}).get("task_id", "?"))
        except Exception as exc:
            log.warning("Auto-idle failed: %s", exc)

    def get_status(self, timeout: float = 5.0) -> dict:
        """Get current bridge mod / Emmatone state."""
        return self._send_command("status", {}, timeout)

    def goap_debug(self, include_world_state: bool = False,
                  timeout: float = 5.0) -> dict:
        """Get GOAP planner debug state: auction, goals, personality, switch history."""
        params: dict = {"include_history": True, "include_combat_log": 5}
        if include_world_state:
            params["include_world_state"] = True
        return self._send_command("agent_debug", params, timeout)

    def set_goap_goals(self, goals: list[dict], enabled: bool | None = None,
                       timeout: float = 5.0) -> dict:
        """Set GOAP goals from Python (full replace). Each goal: {id, type, priority, target?}.
        Pass enabled=True/False to toggle the GOAP ticker on/off."""
        params: dict = {"goals": goals}
        if enabled is not None:
            params["enabled"] = enabled
        return self._send_command("set_goals", params, timeout)

    def add_goal(self, goal_id: str, goal_type: str = "have_item",
                 priority: float = 10.0, target: dict | None = None,
                 timeout: float = 5.0) -> dict:
        """Add a single goal without replacing existing ones (upsert by ID)."""
        params = {"id": goal_id, "type": goal_type, "priority": priority}
        if target is not None:
            params["target"] = target
        return self._send_command("add_goal", params, timeout)

    def remove_goal(self, goal_id: str, timeout: float = 5.0) -> dict:
        """Remove a single goal by ID without affecting other goals."""
        return self._send_command("remove_goal", {"id": goal_id}, timeout)

    def set_personality(self, weights: dict, timeout: float = 5.0) -> dict:
        """Set GOAP personality weights. Keys: safety, aggression, exploration, resource_hoarding."""
        return self._send_command("set_personality", weights, timeout)

    def set_mode(self, mode: str, tier: str = "diamond", timeout: float = 5.0) -> dict:
        """Activate a named GOAP mode. Tiers: iron, diamond, netherite."""
        return self._send_command("set_mode", {"mode": mode, "tier": tier}, timeout)

    # ── Portal commands ──────────────────────────────────────────

    def save_portal(self, name: str, x: int, y: int, z: int,
                    dimension: str, portal_type: str = "nether_portal",
                    timeout: float = 5.0) -> dict:
        """Save a portal location to the Java PortalRegistry."""
        return self._send_command("save_portal", {
            "name": name, "type": portal_type, "dimension": dimension,
            "x": x, "y": y, "z": z,
        }, timeout)

    def get_portals(self, dimension: str | None = None,
                    timeout: float = 5.0) -> dict:
        """Get saved portals from the Java PortalRegistry."""
        params = {"dimension": dimension} if dimension else {}
        return self._send_command("get_portals", params, timeout)

    def scan_area(self, x: int, y: int, z: int, radius: int = 32,
                  include_light: bool = False, include_biome: bool = False,
                  timeout: float = 10.0) -> dict:
        """Scan blocks in radius.  Bridge mod filters uninteresting ones."""
        params: dict = {"x": x, "y": y, "z": z, "radius": radius}
        if include_light:
            params["include_light"] = True
        if include_biome:
            params["include_biome"] = True
        return self._send_command("scan_area", params, timeout)

    def clear_area(self, x: int, y: int, z: int, radius: int = 5,
                   timeout: float = 30.0) -> dict:
        """Clear all blocks in radius around coordinates."""
        return self._send_command("clear_area", {
            "x": x, "y": y, "z": z, "radius": radius,
        }, timeout)

    def get_inventory(self, timeout: float = 5.0) -> dict:
        """Get current inventory contents."""
        return self._send_command("inventory", {}, timeout)

    def look_at(self, x: int, y: int, z: int, timeout: float = 5.0) -> dict:
        """Turn to face coordinates."""
        return self._send_command("look_at", {"x": x, "y": y, "z": z}, timeout)

    # ── Terrain awareness commands (Phase 43) ─────────────────

    def get_heightmap(self, cx: int, cz: int, radius: int = 20,
                      step: int = 1, timeout: float = 10.0) -> dict:
        """Get 2D surface heightmap around a center point.

        Returns {heights: [[y,...]], min_y, max_y, avg_y, width, depth}.
        """
        return self._send_command("heightmap", {
            "cx": cx, "cz": cz, "radius": radius, "step": step,
        }, timeout)

    def get_biome(self, x: int, z: int, radius: int | None = None,
                  include_temperature: bool = False,
                  timeout: float = 5.0) -> dict:
        """Get biome at a point, or biome survey across a radius.

        Single point: {biome: "minecraft:plains"}
        With radius:  {biomes: {...}, dominant: "...", sample_count: N}
        """
        params: dict = {"x": x, "z": z}
        if radius is not None:
            params["radius"] = radius
        if include_temperature:
            params["include_temperature"] = True
        return self._send_command("biome", params, timeout)

    def get_world_info(self, timeout: float = 5.0) -> dict:
        """Get world time, weather, dimension, light level.

        Returns {time_of_day, day_count, is_daytime, weather, difficulty,
                 dimension, light_level, moon_phase}.
        """
        return self._send_command("world_info", {}, timeout)

    def get_targeted_block(self, timeout: float = 2.0) -> dict:
        """Get what the crosshair is pointing at (block or entity).

        Returns {type: "block"|"entity"|"miss", block?, x?, y?, z?, ...}.
        """
        return self._send_command("get_targeted_block", {}, timeout)

    def get_surface_map(self, cx: int, cz: int, radius: int = 20,
                        step: int = 1, include_light: bool = False,
                        include_biome: bool = False,
                        timeout: float = 10.0) -> dict:
        """Get 2D surface block-type map around a center point.

        Returns {blocks: [[{b, y, s?}, ...], ...], width, depth, ...}.
        Each cell has block ID ("b"), Y level ("y"), and optional state ("s").
        """
        params: dict = {"cx": cx, "cz": cz, "radius": radius, "step": step}
        if include_light:
            params["include_light"] = True
        if include_biome:
            params["include_biome"] = True
        return self._send_command("surface_map", params, timeout)

    def get_world_scan(self, cx: int, cz: int, radius: int = 20,
                       mode: str = "surface", y_min: int | None = None,
                       y_max: int | None = None, detail: str = "positions",
                       include_entities: bool = False,
                       include_light: bool = False,
                       include_biome: bool = False,
                       timeout: float = 30.0) -> dict:
        """Full 3D world scan — raw (all non-air) or surface (exposed blocks).

        Returns {blocks: [{b, x, y, z, s?}, ...], block_count, ...}.
        """
        params: dict = {
            "cx": cx, "cz": cz, "radius": radius,
            "mode": mode, "detail": detail,
            "include_entities": include_entities,
        }
        if y_min is not None:
            params["y_min"] = y_min
        if y_max is not None:
            params["y_max"] = y_max
        if include_light:
            params["include_light"] = True
        if include_biome:
            params["include_biome"] = True
        return self._send_command("world_scan", params, timeout)

    # ── Event Handling ────────────────────────────────────────

    def on(self, event: str, handler: Callable):
        """Register handler for an event type (e.g., 'block_placed').
        Handlers are called on the listener thread — keep them fast."""
        self._event_handlers.setdefault(event, []).append(handler)

    # ── State accessors ───────────────────────────────────────

    @property
    def health(self) -> float:
        return self._health

    @property
    def hunger(self) -> float:
        return self._hunger

    @property
    def position(self) -> dict[str, float]:
        return dict(self._position)

    @property
    def active_task(self) -> str | None:
        return self._active_task

    @property
    def active_goal_id(self) -> int | None:
        return self._active_goal_id

    @active_goal_id.setter
    def active_goal_id(self, value: int | None):
        self._active_goal_id = value

    @property
    def recent_events(self) -> list[dict]:
        return list(self._recent_events[-20:])

    @property
    def inventory(self) -> list[dict]:
        return list(self._inventory)

    # ── Compound operations ───────────────────────────────────

    def gather_resources(self, block_type: str, quantity: int) -> dict:
        """Gather resources, resolving crafting dependencies if needed.

        Starts the first mining task and returns immediately with the plan.
        Emmatone executes asynchronously — use minecraft_status to check progress.
        """
        # Ensure block_type has minecraft: prefix for Emmatone
        if ":" not in block_type:
            block_type = f"minecraft:{block_type}"

        deps = self.build_db.resolve_dependencies(block_type, quantity)
        plan: list[str] = []
        first_mine_started = False

        for dep in deps:
            dep_block = dep["block"]
            dep_qty = dep["qty"]
            method = dep["method"]

            # Ensure namespace prefix
            if ":" not in dep_block:
                dep_block = f"minecraft:{dep_block}"

            if method == "mine":
                if not first_mine_started:
                    # Start the first mining task, don't wait for all of them
                    result = self.mine(dep_block, dep_qty, timeout=10.0)
                    status = result.get("status", "unknown")
                    if status == "error":
                        reason = result.get("error") or result.get("reason", "unknown")
                        plan.append(f"Mining {dep_qty}x {dep_block}: FAILED ({reason})")
                        log.warning("Mine command failed for %s x%d: %s", dep_block, dep_qty, reason)
                    else:
                        plan.append(f"Mining {dep_qty}x {dep_block}: started")
                        first_mine_started = True
                else:
                    plan.append(f"Queue: mine {dep_qty}x {dep_block}")
            elif method == "craft":
                plan.append(f"Queue: craft {dep_qty}x {dep_block}")
            elif method == "smelt":
                plan.append(f"Queue: smelt {dep_qty}x {dep_block}")
            else:
                plan.append(
                    f"CANNOT mine {dep_qty}x {dep_block} — not a raw block. "
                    f"Use emmaclef_task('get', '{dep_block.removeprefix('minecraft:')} {dep_qty}') instead."
                )

        # If nothing was actually started, flag it clearly
        if not first_mine_started:
            return {
                "status": "no_mineable",
                "plan": plan,
                "total_steps": len(deps),
                "hint": "This item cannot be mined directly. Use emmaclef_task with 'get' for crafted items, mob drops, or tools.",
            }

        return {"status": "ok", "plan": plan, "total_steps": len(deps)}

    def start_build_goal(self, goal_id: int) -> dict:
        """Start or resume a build goal.

        1. Look up goal + guide
        2. Get bill of materials
        3. Check availability + apply substitutions
        4. Resolve dependencies for missing materials
        5. Queue gathering then building

        Returns status summary for narration context.
        """
        goal = self.build_db.get_goal(goal_id)
        if not goal:
            return {"status": "error", "reason": "goal_not_found"}

        guide_id = goal["guide_id"]
        if not guide_id:
            return {"status": "error", "reason": "no_guide_linked"}

        guide = self.build_db.get_guide(guide_id)
        if not guide:
            return {"status": "error", "reason": "guide_not_found"}

        # Move to current if planned
        if goal["status"] in ("planned", "future"):
            self.build_db.update_goal_status(goal_id, "current")

        self._active_goal_id = goal_id

        # Get bill of materials and apply substitutions
        bom = self.build_db.get_guide_bill_of_materials(guide_id)
        available_blocks = self.build_db.get_available_block_types()
        substitutions_applied: list[dict] = []
        final_materials: dict[str, int] = {}

        for block_type, qty in bom.items():
            # Check if we have the preferred block
            available = self.build_db.count_available(block_type)
            if available >= qty:
                final_materials[block_type] = qty
            else:
                # Try substitution
                sub_block = self.build_db.get_best_available_substitute(
                    block_type, available_blocks
                )
                if sub_block:
                    substitutions_applied.append({
                        "original": block_type,
                        "substitute": sub_block,
                        "reason": "preferred unavailable",
                        "quantity": qty,
                    })
                    final_materials[sub_block] = final_materials.get(sub_block, 0) + qty
                else:
                    # Use original, gather what we can
                    final_materials[block_type] = qty

        # Log substitutions
        if substitutions_applied:
            self.build_db.update_goal_substitutions(
                goal_id, json.dumps(substitutions_applied)
            )

        # Resolve dependencies for all materials
        all_deps: list[dict] = []
        for block_type, qty in final_materials.items():
            deps = self.build_db.resolve_dependencies(block_type, qty)
            all_deps.extend(deps)

        # Initialize progress tracking (INSERT OR IGNORE — safe on resume)
        self.build_db.init_progress_from_guide(goal_id, guide_id)

        # Issue the build command — diff against world_state for resume
        loc_x = goal.get("location_x", 0) or 0
        loc_y = goal.get("location_y", 0) or 0
        loc_z = goal.get("location_z", 0) or 0

        build_result = self.build(
            guide_id, loc_x, loc_y, loc_z,
            goal_id=goal_id,
        )

        remaining = build_result.get("remaining", guide["block_count"])

        return {
            "status": "ok",
            "goal_name": goal["name"],
            "guide_name": guide["name"],
            "block_count": guide["block_count"],
            "remaining_blocks": remaining,
            "substitutions": substitutions_applied,
            "dependencies": len(all_deps),
            "materials": final_materials,
        }

    # ── Priority adjustment (survival override) ──────────────

    def adjust_priorities(self, health: float, hunger: float):
        """Adjust goal priorities based on survival state.

        Deterministic personality — NOT an LLM decision:
        - health < 6: boost safety goals to 1.0, suppress building to 0.1
        - hunger < 4: boost food/farm goals to 0.9
        - both stable: restore normal priority weights from goal defaults
        """
        self._health = health
        self._hunger = hunger

        goals = self.build_db.get_active_goals()

        for g in goals:
            func = (g.get("functional_goal") or "").lower()

            if health < HEALTH_CRITICAL:
                if "shelter" in func or "safety" in func or "defense" in func:
                    self.build_db.adjust_priority(g["id"], 1.0)
                else:
                    self.build_db.adjust_priority(g["id"], 0.1)
            elif hunger < HUNGER_CRITICAL:
                if "farm" in func or "food" in func:
                    self.build_db.adjust_priority(g["id"], 0.9)
                else:
                    self.build_db.adjust_priority(g["id"], max(0.3, g.get("priority_weight", 0.5)))
            else:
                # Restore defaults based on functional goal
                default = 0.5
                if "shelter" in func:
                    default = SAFETY_PREFERENCE
                elif "aesthetic" in func or "decoration" in func:
                    default = AESTHETIC_PREFERENCE
                elif "efficiency" in func or "farm" in func:
                    default = EFFICIENCY_PREFERENCE
                self.build_db.adjust_priority(g["id"], default)

    def handle_failure(self, task_id: str, reason: str):
        """Handle a failed Emmatone task.  Re-plan or abort."""
        log.warning("Emmatone task %s failed: %s", task_id, reason)
        self._push_event("task_failed", {"task_id": task_id, "reason": reason})

        # If current goal, mark as paused so deliberation can re-plan
        if self._active_goal_id:
            try:
                self.build_db.update_goal_status(self._active_goal_id, "paused")
            except Exception as exc:
                log.debug("Failed to pause goal: %s", exc)

    def update_inventory_cache(self, slots: list[dict]):
        """Cache inventory from bridge mod event."""
        self._inventory = slots

    @property
    def inventory_counts(self) -> dict[str, int]:
        """Aggregated inventory: {item_name: total_count}.
        Uses cached slots from inventory_changed events."""
        counts: dict[str, int] = {}
        for slot in self._inventory:
            if slot and isinstance(slot, dict) and slot.get("item"):
                name = slot["item"].replace("minecraft:", "")
                counts[name] = counts.get(name, 0) + slot.get("count", 1)
        return counts

    @property
    def inventory_free_slots(self) -> int:
        """Number of empty main inventory slots (0-35, excludes armor/offhand)."""
        used = sum(1 for s in self._inventory
                   if s and isinstance(s, dict) and s.get("item")
                   and s.get("slot", 99) < 36)
        return max(0, 36 - used)

    # ── Armor tier detection (Phase 37) ───────────────────────

    # Minecraft armor slots: 36=feet, 37=legs, 38=chest, 39=head
    _ARMOR_SLOTS = {36, 37, 38, 39}
    _ARMOR_TIERS = ("netherite", "diamond", "iron", "gold", "chainmail", "leather")

    def _check_armor_tier_change(self):
        """Detect equipped armor tier and notify Animaze bridge.

        Uses majority vote across the 4 armor slots. On tier change,
        calls animaze_bridge.switch_armor() (fire-and-forget).
        """
        tier_counts: dict[str, int] = {}
        for slot in self._inventory:
            if not slot or not isinstance(slot, dict):
                continue
            if slot.get("slot") not in self._ARMOR_SLOTS:
                continue
            item = (slot.get("item") or "").replace("minecraft:", "")
            for t in self._ARMOR_TIERS:
                if t in item:
                    tier_counts[t] = tier_counts.get(t, 0) + 1
                    break

        # Pick tier with most pieces; "none" if no armor at all
        if tier_counts:
            tier = max(tier_counts, key=tier_counts.get)
        else:
            tier = "none"

        if tier != self._last_armor_tier:
            self._last_armor_tier = tier
            try:
                from animaze_bridge import switch_armor
                switch_armor(tier)
            except ImportError:
                pass

    def has_materials(self, bom: dict[str, int]) -> dict:
        """Check inventory against a bill of materials.

        Args:
            bom: {item_name: needed_count}

        Returns:
            {"met": {item: count}, "missing": {item: count},
             "pct_ready": float (0.0-1.0)}
        """
        counts = self.inventory_counts
        met, missing = {}, {}
        total_needed = total_have = 0
        for item, needed in bom.items():
            name = item.replace("minecraft:", "")
            have = counts.get(name, 0)
            if have >= needed:
                met[name] = needed
            else:
                met[name] = have
                missing[name] = needed - have
            total_needed += needed
            total_have += min(have, needed)
        pct = total_have / total_needed if total_needed > 0 else 1.0
        return {"met": met, "missing": missing, "pct_ready": pct}

    # ── Smelting (async "load and leave" pattern) ─────────────

    @property
    def smelting_tracker(self) -> SmeltingTracker:
        return self._smelting_tracker

    def furnace_status(self, timeout: float = 5.0) -> dict:
        """Read the currently open furnace's slots + cook/burn timers."""
        return self._send_command("furnace_status", {}, timeout)

    def smelt_and_leave(
        self,
        furnace_pos: tuple[int, int, int],
        input_item: str,
        input_count: int,
        fuel_item: str = "coal",
        fuel_count: int | None = None,
        output_item: str = "",
        furnace_type: str = "furnace",
    ) -> dict:
        """Load a furnace with materials + fuel, then walk away.

        Orchestrates: goto → interact_block → find item in inventory →
        quick_move to furnace slots → close_screen → register job.

        Returns: {"status": "ok", "job": {...}} or {"status": "error", ...}
        """
        fx, fy, fz = furnace_pos

        # 1. Navigate to furnace
        goto_result = self.goto(fx, fy, fz, timeout=30.0)
        if goto_result.get("error"):
            return {"status": "error", "reason": f"goto failed: {goto_result}"}

        # Wait briefly for arrival
        time.sleep(0.5)

        # 2. Open furnace
        interact = self.interact_block(fx, fy, fz)
        if not interact.get("data", {}).get("interacted"):
            return {"status": "error", "reason": f"interact_block failed: {interact}"}

        # 3. Wait for screen to open, then read it
        time.sleep(0.3)
        screen = self.read_screen()
        screen_data = screen.get("data", {})
        screen_type = screen_data.get("screen_type", "")
        if "furnace" not in screen_type and "smoker" not in screen_type and "blast" not in screen_type:
            self.close_screen()
            return {"status": "error", "reason": f"not a furnace screen: {screen_type}"}

        # 4. Find input material in player inventory and quick_move to furnace
        slots = screen_data.get("slots", [])
        transferred_input = 0
        transferred_fuel = 0

        # Furnace screen layout: 0=input, 1=fuel, 2=output
        # Player inventory starts at slot 3 in furnace screen handler
        for slot_info in slots:
            slot_idx = slot_info.get("slot", -1)
            item = slot_info.get("item", "")
            count = slot_info.get("count", 0)
            if slot_idx < 3:
                continue  # Skip furnace slots

            # Transfer input material
            if input_item in item and transferred_input < input_count:
                self.click_slot(slot_idx, action="quick_move")
                transferred_input += count
                time.sleep(0.1)

            # Transfer fuel
            if fuel_item in item and transferred_fuel < (fuel_count or input_count):
                self.click_slot(slot_idx, action="quick_move")
                transferred_fuel += count
                time.sleep(0.1)

        # 5. Close screen
        self.close_screen()

        if transferred_input == 0:
            return {"status": "error", "reason": f"no {input_item} found in inventory"}

        # Auto-calculate fuel if not specified
        if fuel_count is None:
            # Coal smelts 8 items per unit
            fuel_count = max(1, (transferred_input + 7) // 8)

        # 6. Register smelting job
        actual_input = min(transferred_input, input_count)
        job = self._smelting_tracker.add_job(
            furnace_pos=furnace_pos,
            furnace_type=furnace_type,
            input_item=input_item,
            input_count=actual_input,
            fuel_item=fuel_item,
            fuel_count=transferred_fuel,
            output_item=output_item or input_item.replace("raw_", ""),
            expected_output_count=actual_input,
        )

        return {
            "status": "ok",
            "job_id": job.job_id,
            "input_transferred": transferred_input,
            "fuel_transferred": transferred_fuel,
            "estimated_done_seconds": int(job.estimated_done_at - job.started_at),
        }

    def collect_smelting_output(self, job_id: str | None = None) -> dict:
        """Go to a furnace and collect finished smelting output.

        If job_id is None, collects the oldest ready job.
        Returns: {"status": "ok", "collected": {...}} or {"status": "error", ...}
        """
        # Find job
        if job_id:
            job = self._smelting_tracker.get_job(job_id)
        else:
            ready = self._smelting_tracker.get_ready_jobs()
            job = ready[0] if ready else None

        if not job:
            return {"status": "error", "reason": "no ready smelting jobs"}

        fx, fy, fz = job.furnace_pos

        # 1. Navigate to furnace
        goto_result = self.goto(fx, fy, fz, timeout=30.0)
        if goto_result.get("error"):
            self._smelting_tracker.mark_failed(job.job_id, "goto_failed")
            return {"status": "error", "reason": f"goto failed: {goto_result}"}

        time.sleep(0.5)

        # 2. Open furnace
        interact = self.interact_block(fx, fy, fz)
        if not interact.get("data", {}).get("interacted"):
            self._smelting_tracker.mark_failed(job.job_id, "furnace_missing")
            return {"status": "error", "reason": "could not interact with furnace"}

        time.sleep(0.3)

        # 3. Read screen to check output
        screen = self.read_screen()
        screen_data = screen.get("data", {})
        screen_type = screen_data.get("screen_type", "")
        if "furnace" not in screen_type and "smoker" not in screen_type and "blast" not in screen_type:
            self.close_screen()
            self._smelting_tracker.mark_failed(job.job_id, "not_a_furnace")
            return {"status": "error", "reason": f"not a furnace screen: {screen_type}"}

        # 4. Quick-move output slot (slot 2) to inventory
        output_slot = 2
        slots = screen_data.get("slots", [])
        output_found = any(s.get("slot") == output_slot for s in slots)

        if output_found:
            self.click_slot(output_slot, action="quick_move")
            time.sleep(0.1)

        # Also grab any remaining input (slot 0) if present
        input_found = any(s.get("slot") == 0 for s in slots)
        if input_found:
            self.click_slot(0, action="quick_move")
            time.sleep(0.1)

        # 5. Close screen
        self.close_screen()

        # 6. Mark collected
        self._smelting_tracker.mark_collected(job.job_id)

        return {
            "status": "ok",
            "job_id": job.job_id,
            "output_item": job.output_item,
            "expected_count": job.expected_output_count,
            "output_was_present": output_found,
        }

    # ── State summary (for narration context) ─────────────────

    def _get_active_build_context(self) -> str | None:
        """Build rich context for active build goals (Phase 47).

        Returns a formatted string like:
        [ACTIVE BUILD: Oak Cabin (goal #12)]
        Location: (200, 64, -150)
        Placed: 340/500 blocks (68%) — structural done, finishing pending
        """
        # Check for any current goal — not just the active one
        try:
            current_goals = self.build_db.get_goals_by_status("current")
            if not current_goals:
                return None

            goal = current_goals[0]
            goal_id = goal["id"]
            guide_id = goal.get("guide_id")
            if not guide_id:
                return None

            guide = self.build_db.get_guide(guide_id)
            if not guide:
                return None

            # Get progress
            summary = self.build_db.get_progress_summary(goal_id)

            # Classify blocks for structural/deferred status
            classified = self.build_db.classify_guide_blocks(guide_id)
            structural_total = len(classified["structural"])
            deferred_total = len(classified["deferred"])

            loc_x = goal.get("location_x", 0) or 0
            loc_y = goal.get("location_y", 0) or 0
            loc_z = goal.get("location_z", 0) or 0

            placed = summary.get("placed_blocks", 0)
            total = summary.get("total_blocks", 0)
            pct = summary.get("percent", 0)

            lines = [
                f"[ACTIVE BUILD: {goal['name']} (goal #{goal_id})]",
                f"Location: ({loc_x}, {loc_y}, {loc_z})",
                f"Progress: {placed}/{total} blocks ({pct:.0f}%)",
                f"Structure: {structural_total} structural + {deferred_total} deferred blocks",
            ]

            # Hint at next step
            if pct < 1:
                lines.append("Next: Check build_bom for materials, then build_structural")
            elif placed < structural_total:
                lines.append("Next: Run build_structural to continue placing blocks")
            elif deferred_total > 0:
                lines.append("Next: Run build_finishing for doors, torches, ladders")
            else:
                lines.append("Next: Run build_inspect to verify completion")

            if goal.get("substitutions_applied"):
                subs = goal["substitutions_applied"]
                if isinstance(subs, dict) and subs:
                    lines.append(f"Substitutions: {subs}")

            return "\n".join(lines)
        except Exception:
            return None

    def get_state_summary(self) -> str:
        """Formatted state string for LLM context injection."""
        parts = ["[MINECRAFT STATE]"]

        # Active goal — enhanced with Phase 47 build context
        active_build = self._get_active_build_context()
        if active_build:
            parts.append(active_build)
        elif self._active_goal_id:
            try:
                goal = self.build_db.get_goal(self._active_goal_id)
                if goal:
                    summary = self.build_db.get_progress_summary(self._active_goal_id)
                    parts.append(
                        f"Building: {goal['name']} — {summary.get('percent', 0):.0f}% "
                        f"({summary.get('placed_blocks', 0)}/{summary.get('total_blocks', 0)} blocks)"
                    )
                    if goal.get("substitutions_applied"):
                        parts.append(f"Substitutions: {goal['substitutions_applied']}")
            except Exception:
                pass

        # Active task
        parts.append(f"Task: {self._active_task or 'idle'}")

        # Position — landmark-relative if POIs exist
        try:
            from gamer.poi import POIRegistry, _COMPASS_NAMES
            poi_reg = POIRegistry()
            all_pois = poi_reg.get_all_pois()
            px, py, pz = self._position['x'], self._position['y'], self._position['z']
            if all_pois:
                best_poi = min(
                    all_pois,
                    key=lambda p: POIRegistry.horizontal_distance(px, pz, p["x"], p["z"]),
                )
                best_dist = POIRegistry.horizontal_distance(px, pz, best_poi["x"], best_poi["z"])
                if best_dist < 5:
                    parts.append(f"Position: at {best_poi['name']}")
                elif best_dist < 200:
                    # Direction FROM poi TO us
                    dx = px - best_poi["x"]
                    dz = pz - best_poi["z"]
                    compass = POIRegistry.compass_direction(dx, dz)
                    compass_name = _COMPASS_NAMES.get(compass, compass)
                    parts.append(
                        f"Position: {int(best_dist)} blocks {compass_name} of {best_poi['name']}"
                    )
                else:
                    parts.append(f"Position: ({px:.0f}, {py:.0f}, {pz:.0f})")
            else:
                parts.append(f"Position: ({px:.0f}, {py:.0f}, {pz:.0f})")
        except Exception:
            parts.append(
                f"Position: ({self._position['x']:.0f}, {self._position['y']:.0f}, "
                f"{self._position['z']:.0f})"
            )

        # Vitals
        parts.append(f"Health: {self._health:.0f}/20, Hunger: {self._hunger:.0f}/20")

        # Extended vitals from bridge status (XP + danger flags) — cheap call
        try:
            status = self.get_status(timeout=2.0)
            if status and "error" not in status:
                xp = status.get("xp_level")
                if xp is not None and xp > 0:
                    parts.append(f"XP: {xp}")
                # Danger flags — only show when active
                dangers = []
                if status.get("on_fire"):
                    dangers.append("ON FIRE")
                if status.get("in_lava"):
                    dangers.append("IN LAVA")
                if status.get("in_water"):
                    dangers.append("UNDERWATER")
                if status.get("frozen_ticks", 0) > 0:
                    dangers.append("FREEZING")
                if status.get("air", 300) < status.get("max_air", 300):
                    dangers.append(f"AIR: {status['air']}/{status['max_air']}")
                if dangers:
                    parts.append(f"⚠ {', '.join(dangers)}")
        except Exception:
            pass

        # Auto torch status — only show when enabled
        try:
            torch_info = self.torch_status(timeout=2.0)
            if torch_info and torch_info.get("enabled"):
                active_str = "active" if torch_info.get("active") else "standby"
                parts.append(
                    f"AutoTorch: {active_str}, {torch_info.get('torch_count', 0)} torches"
                )
        except Exception:
            pass

        # World environment snapshot (light + time) — cheap call
        try:
            winfo = self.get_world_info(timeout=2.0)
            if winfo and "error" not in winfo:
                env_bits = []
                if "light_level" in winfo:
                    env_bits.append(f"Light: {winfo['light_level']}")
                if "time_of_day" in winfo:
                    tod = int(winfo["time_of_day"])
                    if tod < 1000:
                        period = "dawn"
                    elif tod < 6000:
                        period = "morning"
                    elif tod < 12000:
                        period = "afternoon"
                    elif tod < 13000:
                        period = "dusk"
                    else:
                        period = "night"
                    env_bits.append(f"Time: {period}")
                if winfo.get("weather") and winfo["weather"] != "clear":
                    env_bits.append(f"Weather: {winfo['weather']}")
                if env_bits:
                    parts.append(", ".join(env_bits))
        except Exception:
            pass

        # Last death (show for 15 minutes — matches GOAP recovery timeout)
        if self._last_death:
            elapsed = time.time() - self._last_death["time"]
            if elapsed < 900:
                pos_str = ""
                if "x" in self._last_death and self._last_death["x"] is not None:
                    pos_str = (f" at {int(self._last_death['x'])}, "
                               f"{int(self._last_death['y'])}, "
                               f"{int(self._last_death['z'])}")
                parts.append(
                    f"Last death: killed by {self._last_death['cause']}"
                    f"{pos_str} "
                    f"({self._last_death['damage']:.0f} dmg, {int(elapsed)}s ago)"
                )

        # Recent events (last 5) with details
        if self._recent_events:
            evts = self._recent_events[-5:]
            evt_strs = []
            for e in evts:
                name = e.get("event", "?")
                if name == "damage_taken":
                    src = e.get("attacker_type", "")
                    if src.startswith("minecraft:"):
                        src = src[len("minecraft:"):]
                    amt = e.get("amount", 0)
                    fatal = " (fatal)" if e.get("fatal") else ""
                    evt_strs.append(f"damage from {src or 'unknown'} ({amt:.0f}{fatal})")
                elif name == "chat_message":
                    evt_strs.append(f"chat: {e.get('message', '?')[:40]}")
                elif name in ("task_complete", "task_failed"):
                    evt_strs.append(e.get("task_type", name))
                else:
                    evt_strs.append(name)
            parts.append(f"Recent: {', '.join(evt_strs)}")

        # Inventory highlights
        if self._inventory:
            # Build slot lookup
            by_slot = {s.get("slot"): s for s in self._inventory if s.get("item")}

            def _name(s):
                return s.get("item", "?").replace("minecraft:", "") if s else None

            # Held item (selected hotbar slot)
            held = _name(by_slot.get(self._selected_slot))
            if held:
                parts.append(f"Holding: {held} (slot {self._selected_slot})")

            # Armor
            armor_names = {39: "head", 38: "chest", 37: "legs", 36: "feet"}
            armor_parts = []
            for slot_id, label in armor_names.items():
                name = _name(by_slot.get(slot_id))
                if name:
                    armor_parts.append(f"{label}={name}")
            if armor_parts:
                parts.append(f"Armor: {', '.join(armor_parts)}")

            # Offhand
            offhand = _name(by_slot.get(40))
            if offhand:
                parts.append(f"Offhand: {offhand}")

            # Aggregated main inventory + hotbar (skip armor/offhand)
            notable = []
            for s in self._inventory:
                slot_num = s.get("slot", 99)
                if slot_num > 35:
                    continue
                count = s.get("count", 0)
                if count <= 0:
                    continue
                item_id = _name(s)
                notable.append(f"{count}x {item_id}")
            if notable:
                parts.append(f"Inventory: {', '.join(notable)}")

        # Pending smelting jobs
        smelting_summary = self._smelting_tracker.get_summary()
        if smelting_summary:
            parts.append(smelting_summary)

        return " | ".join(parts)

    # ── Internal ──────────────────────────────────────────────

    def _send_command(self, command: str, params: dict,
                      timeout: float) -> dict:
        """Send command and block until response arrives or timeout."""
        if not self.connected:
            return {"status": "error", "reason": "not_connected"}

        cmd_id = f"cmd_{uuid.uuid4().hex[:8]}"
        event = threading.Event()
        with self._lock:
            self._pending[cmd_id] = {"event": event, "result": None}

        msg = json.dumps({
            "id": cmd_id,
            "type": "command",
            "command": command,
            "params": params,
        })
        try:
            self.ws.send(msg)
        except Exception as exc:
            with self._lock:
                self._pending.pop(cmd_id, None)
            return {"status": "error", "reason": str(exc)}

        if not event.wait(timeout=timeout):
            with self._lock:
                self._pending.pop(cmd_id, None)
            return {"status": "error", "reason": "timeout"}

        with self._lock:
            return self._pending.pop(cmd_id, {}).get("result", {})

    def _on_open(self, ws):
        self.connected = True
        log.info("EmmatoneClient connected to %s", self.url)
        # Sync POI registry from build goals on connect
        try:
            from gamer.poi import POIRegistry
            POIRegistry().sync_from_build_goals()
            log.info("POI registry synced from build goals")
        except Exception as exc:
            log.debug("POI sync on connect (non-critical): %s", exc)
        # Auto-start idle mode so MobDefenseChain + FoodChain are always active
        self._idle_suppressed = False
        self._idle_suppressed_at = None
        self._schedule_idle(delay=2.0)
        # Auto-configure panic teleport from Python config
        try:
            pt_cfg = _cfg.get("panic_teleport", {})
            if pt_cfg.get("enabled"):
                self.configure_panic_teleport(
                    enabled=True,
                    threshold=pt_cfg.get("threshold", 4.0),
                    safe_x=pt_cfg.get("safe_x", 0),
                    safe_y=pt_cfg.get("safe_y", 0),
                    safe_z=pt_cfg.get("safe_z", 0),
                    cooldown_ms=pt_cfg.get("cooldown_ms", 60000),
                )
                log.info("Panic teleport configured: threshold=%.1f, dest=(%.0f, %.0f, %.0f)",
                         pt_cfg.get("threshold", 4.0),
                         pt_cfg.get("safe_x", 0), pt_cfg.get("safe_y", 0),
                         pt_cfg.get("safe_z", 0))
        except Exception as exc:
            log.debug("Panic teleport auto-config (non-critical): %s", exc)

    def _on_close(self, ws, close_status_code, close_msg):
        self.connected = False
        log.info("EmmatoneClient disconnected (code=%s)", close_status_code)

    def _on_error(self, ws, error):
        was_connected = self.connected
        self.connected = False
        if was_connected:
            log.warning("EmmatoneClient error: %s", error)

    def _on_message(self, ws, message):
        """Route incoming messages to pending commands or event handlers."""
        try:
            data = json.loads(message)
        except json.JSONDecodeError:
            return

        msg_type = data.get("type", "")

        if msg_type == "response":
            cmd_id = data.get("id")
            with self._lock:
                pending = self._pending.get(cmd_id)
            if pending:
                pending["result"] = data
                pending["event"].set()

        elif msg_type == "event":
            event_name = data.get("event", "")
            event_data = data.get("data", {})

            # Update internal state from events
            self._handle_state_event(event_name, event_data)

            # Dispatch to registered handlers
            handlers = self._event_handlers.get(event_name, [])
            for handler in handlers:
                try:
                    handler(event_data)
                except Exception as exc:
                    log.debug("Event handler error (%s): %s", event_name, exc)

    def _handle_state_event(self, event_name: str, data: dict):
        """Update cached state from bridge mod events."""
        if event_name in ("position", "position_update"):
            self._position = {
                "x": data.get("x", 0),
                "y": data.get("y", 0),
                "z": data.get("z", 0),
            }
        elif event_name == "damage_taken":
            if data.get("fatal"):
                attacker = data.get("attacker_type", "unknown")
                if attacker.startswith("minecraft:"):
                    attacker = attacker[len("minecraft:"):]
                self._last_death = {
                    "cause": attacker,
                    "damage": data.get("amount", 0),
                    "time": time.time(),
                }
                log.info("Death recorded: killed by %s (%.0f dmg)",
                         attacker, data.get("amount", 0))
        elif event_name == "health_changed":
            prev_health = self._health
            self._health = data.get("health", self._health)
            self._hunger = data.get("hunger", self._hunger)
            if self._health <= 0 and prev_health > 0:
                log.info("Player died (health %.0f → 0) — awaiting respawn command", prev_health)
        elif event_name == "task_started":
            self._active_task = data.get("task", "unknown")
            self._cancel_idle_timer()  # new sub-task: cancel pending idle
        elif event_name in ("task_complete", "task_failed", "task_stopped"):
            completed_task = data.get("task_type", "")
            reason = data.get("reason", "")
            # Chain preemption is NOT a real failure — MobDefenseChain,
            # FoodChain, etc. temporarily take priority, then the task
            # resumes via interruptedByChain recovery.  Do NOT schedule
            # idle or clear suppression, as that would replace the build.
            if reason == "chain_preempted":
                log.info("Ignoring chain_preempted — task will resume: %s", completed_task)
                return
            # DON'T clear _active_task immediately — keep it populated
            # during sub-task gaps so state checks don't show "idle".
            # _enter_idle() clears it after the delay expires.
            # Un-suppress idle: always clear on failure/stop (task is dead),
            # on completion only when a build finishes normally.
            if event_name in ("task_failed", "task_stopped") or "build" in completed_task:
                self._idle_suppressed = False
                self._idle_suppressed_at = None
            # Re-enter idle so defense chains stay active between tasks
            # BUT don't re-idle if:
            #  1. The completed task was idle itself (being replaced by a real task)
            #  2. A build or other long-running task is currently active
            if "idle" not in completed_task:
                self._schedule_idle()  # uses config auto_idle_delay_seconds
        elif event_name == "inventory_changed":
            self._inventory = data.get("slots", [])
            self._selected_slot = data.get("selected_slot", 0)
            self._check_armor_tier_change()
        elif event_name == "container_contents":
            self._handle_container_contents(data)
        elif event_name == "block_placed":
            # Update build progress if we have an active goal
            if self._active_goal_id:
                try:
                    block_type = data.get("block_type", "")
                    if block_type:
                        self.build_db.increment_progress(
                            self._active_goal_id, block_type
                        )
                except Exception as exc:
                    log.debug("Progress update failed: %s", exc)

            # Update world state
            try:
                self.build_db.update_world_block(
                    data.get("x", 0), data.get("y", 0), data.get("z", 0),
                    data.get("block_type", ""), "placed",
                )
            except Exception as exc:
                log.debug("World state update failed: %s", exc)

        elif event_name == "block_broken":
            try:
                self.build_db.update_world_block(
                    data.get("x", 0), data.get("y", 0), data.get("z", 0),
                    "minecraft:air", "broken",
                )
            except Exception as exc:
                log.debug("World state update failed: %s", exc)

        elif event_name == "torch_out":
            log.info("AutoTorch: out of torches!")
        elif event_name == "panic_teleport":
            log.warning("PANIC TELEPORT triggered! health=%.1f, threshold=%.1f, "
                        "teleported to (%.0f, %.0f, %.0f)",
                        data.get("health", 0), data.get("threshold", 0),
                        data.get("safe_x", 0), data.get("safe_y", 0),
                        data.get("safe_z", 0))
            # Clear active task — we're now idle at safe room
            self._active_task = None
            self._idle_suppressed = False
            self._idle_suppressed_at = None

        elif event_name == "death_postmortem":
            self._persist_death_postmortem(data)
            # Enrich _last_death with position/dimension from postmortem
            ps = data.get("player_state", {})
            if self._last_death:
                self._last_death.update({
                    "x": ps.get("x"),
                    "y": ps.get("y"),
                    "z": ps.get("z"),
                    "dimension": ps.get("dimension", "minecraft:overworld"),
                    "hostile_count": data.get("hostile_count", 0),
                    "death_message": data.get("death_message", ""),
                })

        elif event_name == "player_stats":
            self._player_stats = data
            try:
                from overlay.overlay_server import push_event, update_state
                push_event({"type": "mc_player_stats", **data})
                update_state({"mc_player_stats": data})
            except Exception:
                pass

        # Store in recent events ring buffer
        self._push_event(event_name, data)

    def _push_event(self, event_name: str, data: dict):
        """Add event to recent events, cap at 20."""
        self._recent_events.append({
            "event": event_name,
            "time": time.time(),
            **data,
        })
        if len(self._recent_events) > 20:
            self._recent_events = self._recent_events[-20:]

    def _persist_death_postmortem(self, data: dict):
        """Append death post-mortem to logs/death_postmortems.jsonl."""
        import json
        from pathlib import Path
        try:
            log_dir = Path("logs")
            log_dir.mkdir(exist_ok=True)
            log_file = log_dir / "death_postmortems.jsonl"
            with open(log_file, "a", encoding="utf-8") as f:
                f.write(json.dumps(data, ensure_ascii=False) + "\n")

            death_msg = data.get("death_message", "Unknown cause")
            hostile_count = data.get("hostile_count", 0)
            combat_entries = len(data.get("combat_log", []))
            log.info("Death post-mortem saved: %s (%d hostiles nearby, %d combat log entries)",
                     death_msg, hostile_count, combat_entries)
        except Exception as exc:
            log.warning("Failed to persist death post-mortem: %s", exc)

    # ── EmmaClef Task System ──────────────────────────────────

    def emmaclef_task(self, task: str, args: str = "") -> dict:
        """DEPRECATED: EmmaClef was removed. Redirects to GOAP goals.
        Supported redirections:
            emmaclef_task("get", "item_name count") -> set_goap_goals with have_item
            emmaclef_task("food") -> set_goap_goals with stay_fed boost
            emmaclef_task("stop") / emmaclef_task("idle") -> cancel + clear goals
            emmaclef_task("hero") -> set_goap_goals with combat priority
            emmaclef_task("equip") -> no-op (GOAP handles armor automatically)
        """
        if task == "stop" or task == "idle":
            self.cancel()
            return self.set_goap_goals([])
        if task == "equip":
            return {"status": "ok", "note": "GOAP handles armor automatically"}
        if task == "food":
            return self.set_goap_goals([
                {"id": "stay_fed", "type": "stay_fed", "priority": 8.0}
            ])
        if task == "hero":
            return self.set_goap_goals([
                {"id": "hero_mode", "type": "combat", "priority": 10.0}
            ])
        if task == "get" and args:
            parts = args.strip().split()
            item = parts[0]
            count = int(parts[1]) if len(parts) > 1 else 1
            return self.request_item(item, count)
        # Fallback: try as raw command (will likely fail)
        params = {"task": task}
        if args:
            params["args"] = args
        return self._send_command("emmaclef", params, timeout=5.0)

    def request_item(self, item: str, count: int = 1,
                     priority: float = 10.0) -> dict:
        """Request an item via GOAP have_item goal.
        Adds a goal (without replacing existing ones) and lets the GOAP
        planner autonomously mine/craft/smelt to acquire the item.
        """
        item_id = item if ":" in item else f"minecraft:{item}"
        clean = item.replace("minecraft:", "")
        goal_id = f"get_{clean}_{count}"
        return self.add_goal(goal_id, "have_item", priority,
                             {"item": item_id, "count": count})

    def emmaclef_stop(self) -> dict:
        """Stop current tasks — cancel pathfinding and clear GOAP goals."""
        self.cancel()
        return self.set_goap_goals([])

    def emmaclef_status(self) -> dict:
        """Get GOAP planner state (replaces EmmaClef status)."""
        return self.goap_debug()

    # ── Named Base System ─────────────────────────────────────

    def set_base(self, name: str, x: int, y: int, z: int,
                 radius: int = 10) -> dict:
        """Register a named base at the given coordinates.

        Examples:
            set_base("main", 100, 64, 200)
            set_base("forge", -50, 70, 300, radius=15)
        """
        return self.emmaclef_task("setbase", f"{name} {x} {y} {z} {radius}")

    def list_bases(self) -> dict:
        """List all registered named bases."""
        return self.emmaclef_task("listbases")

    def clear_base(self, name: str) -> dict:
        """Remove a named base."""
        return self.emmaclef_task("clearbase", name)

    def protect_base(self, name: str, enabled: bool = True) -> dict:
        """Toggle block-breaking protection for a named base."""
        return self.emmaclef_task("protectbase",
                                 f"{name} {'on' if enabled else 'off'}")

    def get_from_base(self, item: str, count: int = 1,
                      base: str = "free") -> dict:
        """Get items, optionally routing to a named base.

        Examples:
            get_from_base("iron_ingot", 5, "main")   # smelt at main base
            get_from_base("iron_ingot", 5)            # free mode (temp container)
            get_from_base("diamond_pickaxe", 1, "forge")
        """
        return self.emmaclef_task("get", f"{base} {item} {count}")

    # ── Chat / Server Commands ────────────────────────────────

    def send_chat(self, message: str, timeout: float = 5.0) -> dict:
        """Send a chat message in-game."""
        return self._send_command("chat", {"message": message}, timeout)

    def send_command(self, command: str, timeout: float = 5.0) -> dict:
        """Send a slash command (without leading /).

        Examples:
            send_command("time set day")
            send_command("summon zombie ~ ~ ~")
            send_command("gamemode creative")
            send_command("weather clear")
        """
        return self._send_command("chat", {"command": command}, timeout)

    # ── Panic Teleport ─────────────────────────────────────────

    def configure_panic_teleport(self, enabled: bool = True,
                                  threshold: float = 4.0,
                                  safe_x: float = 0, safe_y: float = 0,
                                  safe_z: float = 0,
                                  cooldown_ms: int = 60000,
                                  timeout: float = 5.0) -> dict:
        """Configure the panic teleport safety system."""
        result = self._send_command("panic_teleport", {
            "action": "configure",
            "threshold": threshold,
            "safe_x": safe_x, "safe_y": safe_y, "safe_z": safe_z,
            "cooldown_ms": cooldown_ms,
        }, timeout)
        if enabled:
            self.enable_panic_teleport(timeout)
        return result

    def enable_panic_teleport(self, timeout: float = 5.0) -> dict:
        """Enable the panic teleport safety system."""
        return self._send_command("panic_teleport", {"action": "enable"}, timeout)

    def disable_panic_teleport(self, timeout: float = 5.0) -> dict:
        """Disable the panic teleport safety system."""
        return self._send_command("panic_teleport", {"action": "disable"}, timeout)

    def force_panic_teleport(self, timeout: float = 5.0) -> dict:
        """Force-trigger panic teleport immediately (GUI Panic! button)."""
        return self._send_command("panic_teleport", {"action": "trigger"}, timeout)

    def panic_teleport_status(self, timeout: float = 5.0) -> dict:
        """Get current panic teleport configuration and state."""
        return self._send_command("panic_teleport", {"action": "status"}, timeout)

    # ── Container Cache (persistent container tracking) ─────────

    def _handle_container_contents(self, data: dict):
        """Handle container_contents event — persist to DB and in-memory cache."""
        pos = data.get("pos", [0, 0, 0])
        key = (pos[0], pos[1], pos[2])
        self._container_cache[key] = data

        # Persist to SQLite
        try:
            from gamer.db import get_connection
            conn = get_connection()
            try:
                conn.execute(
                    """INSERT OR REPLACE INTO container_cache
                       (x, y, z, type, items, total_slots, empty_slots, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, strftime('%s','now'))""",
                    (pos[0], pos[1], pos[2],
                     data.get("type", "chest"),
                     json.dumps(data.get("items", [])),
                     data.get("total_slots", 27),
                     data.get("empty_slots", 27)),
                )
                conn.commit()
            finally:
                conn.close()
            log.debug("Container cache persisted: %s at %s (%d items)",
                      data.get("type"), key, len(data.get("items", [])))
        except Exception as exc:
            log.warning("Failed to persist container cache: %s", exc)

    def _get_container_totals_from_db(self, item_names: list[str]) -> dict[str, int]:
        """Query container_cache DB for item totals across all cached containers."""
        totals: dict[str, int] = {}
        try:
            from gamer.db import get_connection
            conn = get_connection()
            try:
                rows = conn.execute("SELECT items FROM container_cache").fetchall()
                for row in rows:
                    items = json.loads(row["items"])
                    for slot in items:
                        item_id = slot.get("item", "")
                        count = slot.get("count", 0)
                        # Match with or without minecraft: prefix
                        for name in item_names:
                            full_name = name if ":" in name else f"minecraft:{name}"
                            if item_id == full_name:
                                totals[name] = totals.get(name, 0) + count
            finally:
                conn.close()
        except Exception as exc:
            log.warning("Failed to query container cache DB: %s", exc)
        return totals

    # ── Storage Commands (Phase 47) ────────────────────────────

    def storage_scan(self, radius: int = 32, timeout: float = 5.0) -> list[dict]:
        """Scan containers within radius. Enriches with DB cache for previously opened containers."""
        result = self._send_command("storage", {
            "action": "scan", "radius": radius
        }, timeout=timeout)
        containers = result.get("data", {}).get("containers", [])

        # Enrich containers not known by Java in-memory cache with DB data
        try:
            from gamer.db import get_connection
            conn = get_connection()
            try:
                for container in containers:
                    if not container.get("items_known", False):
                        x, y, z = container["x"], container["y"], container["z"]
                        row = conn.execute(
                            "SELECT items, total_slots, empty_slots FROM container_cache WHERE x=? AND y=? AND z=?",
                            (x, y, z)
                        ).fetchone()
                        if row:
                            container["items"] = json.loads(row["items"])
                            container["items_known"] = True
                            container["total_slots"] = row["total_slots"]
                            container["empty_slots"] = row["empty_slots"]
                            container["items_source"] = "db_cache"
            finally:
                conn.close()
        except Exception as exc:
            log.debug("DB enrichment for storage_scan failed: %s", exc)

        return containers

    def storage_deposit(self, pos: tuple[int, int, int],
                        items: dict[str, int], timeout: float = 5.0) -> dict:
        """Start EmmaClef task to deposit items into container at pos.

        Args:
            pos: (x, y, z) of the container
            items: {item_name: count} e.g. {"oak_planks": 64, "stone_bricks": 128}

        Returns immediately with task_id. Use emmaclef_status() to check completion.
        """
        item_list = [{"item": name, "count": count} for name, count in items.items()]
        return self._send_command("storage", {
            "action": "deposit",
            "pos": list(pos),
            "items": item_list
        }, timeout=timeout)

    def storage_withdraw(self, pos: tuple[int, int, int],
                         items: dict[str, int], timeout: float = 5.0) -> dict:
        """Start EmmaClef task to withdraw items from container at pos.

        Args:
            pos: (x, y, z) of the container
            items: {item_name: count} e.g. {"oak_planks": 64}

        Returns immediately with task_id. Use emmaclef_status() to check completion.
        """
        item_list = [{"item": name, "count": count} for name, count in items.items()]
        return self._send_command("storage", {
            "action": "withdraw",
            "pos": list(pos),
            "items": item_list
        }, timeout=timeout)

    def storage_deposit_nearby(self, items: dict[str, int],
                               timeout: float = 5.0) -> dict:
        """Deposit items into nearest container. Creates chest if none found.

        Args:
            items: {item_name: count} — items to deposit

        Returns immediately with task_id.
        """
        item_list = [{"item": name, "count": count} for name, count in items.items()]
        return self._send_command("storage", {
            "action": "deposit_nearby",
            "items": item_list
        }, timeout=timeout)

    def storage_total(self, item_names: list[str],
                      timeout: float = 5.0) -> dict[str, dict]:
        """Query total counts for items across inventory + overflow + all cached containers.

        Merges Java in-memory cache with persistent DB cache for cross-session recall.

        Args:
            item_names: list of item names (e.g. ["oak_planks", "stone_bricks"])

        Returns:
            {item_name: {"inventory": int, "containers": int, "overflow": int, "total": int}}
        """
        result = self._send_command("storage", {
            "action": "total",
            "items": item_names
        }, timeout=timeout)
        totals = result.get("data", {}).get("totals", {})

        # Enrich container counts with DB cache (catches containers from previous sessions)
        db_totals = self._get_container_totals_from_db(item_names)
        for name, db_count in db_totals.items():
            if name in totals:
                java_count = totals[name].get("containers", 0)
                # Use the higher of Java cache vs DB cache
                # (Java cache is more current for this session's containers)
                if db_count > java_count:
                    totals[name]["containers"] = db_count
                    totals[name]["total"] = (
                        totals[name].get("inventory", 0)
                        + totals[name].get("overflow", 0)
                        + db_count
                    )

        return totals

    def storage_base_inventory(self, base: str,
                               timeout: float = 5.0) -> dict:
        """Get inventory of all containers at a named base.

        Queries the POI registry for the base location, then finds all cached
        containers within that base's radius from the container_cache DB table.

        Args:
            base: name of the base (e.g. "main")

        Returns:
            {"base": str, "containers": [...], "total_items": {item: count}, "count": int}
        """
        try:
            from gamer.db import get_connection
            conn = get_connection()
            try:
                # Look up base location from poi_registry
                poi = conn.execute(
                    "SELECT x, y, z FROM poi_registry WHERE name = ? COLLATE NOCASE AND poi_type = 'base'",
                    (base,)
                ).fetchone()
                if not poi:
                    return {"error": f"Base '{base}' not found in POI registry", "containers": [], "count": 0}

                bx, by, bz = poi["x"], poi["y"], poi["z"]
                radius = 32  # default base radius

                # Find all cached containers within radius
                rows = conn.execute(
                    """SELECT x, y, z, type, items, total_slots, empty_slots, updated_at
                       FROM container_cache
                       WHERE abs(x - ?) <= ? AND abs(y - ?) <= 8 AND abs(z - ?) <= ?""",
                    (bx, radius, by, bz, radius)
                ).fetchall()

                containers = []
                total_items: dict[str, int] = {}
                for row in rows:
                    items = json.loads(row["items"])
                    container = {
                        "x": row["x"], "y": row["y"], "z": row["z"],
                        "type": row["type"],
                        "items": items,
                        "total_slots": row["total_slots"],
                        "empty_slots": row["empty_slots"],
                    }
                    containers.append(container)
                    for slot in items:
                        item_id = slot.get("item", "")
                        count = slot.get("count", 0)
                        total_items[item_id] = total_items.get(item_id, 0) + count

                return {
                    "base": base,
                    "center": {"x": bx, "y": by, "z": bz},
                    "containers": containers,
                    "total_items": total_items,
                    "count": len(containers),
                }
            finally:
                conn.close()
        except Exception as exc:
            log.warning("storage_base_inventory failed: %s", exc)
            return {"error": str(exc), "containers": [], "count": 0}

    def storage_save_cache(self, timeout: float = 5.0) -> dict:
        """Manually save container cache to disk."""
        result = self._send_command("storage", {
            "action": "save_cache"
        }, timeout=timeout)
        return result.get("data", {})

    # ── Overflow inventory management ─────────────────────────

    def overflow_trash(self, items: dict[str, int],
                       timeout: float = 5.0) -> dict:
        """Permanently destroy items from inventory. No drop entity, instant.

        Args:
            items: {item_name: count} e.g. {"cobblestone": 256, "dirt": 128}
        """
        item_list = [{"item": name, "count": count} for name, count in items.items()]
        result = self._send_command("overflow", {
            "action": "trash", "items": item_list
        }, timeout=timeout)
        return result.get("data", {})

    def overflow_deposit(self, items: dict[str, int],
                         timeout: float = 5.0) -> dict:
        """Move items from inventory to virtual overflow (retrievable later).

        Only works for generic stackable items (no tools/enchanted/armor).
        Args:
            items: {item_name: count}
        """
        item_list = [{"item": name, "count": count} for name, count in items.items()]
        result = self._send_command("overflow", {
            "action": "deposit", "items": item_list
        }, timeout=timeout)
        return result.get("data", {})

    def overflow_withdraw(self, items: dict[str, int],
                          timeout: float = 5.0) -> dict:
        """Retrieve items from virtual overflow back to inventory.

        Args:
            items: {item_name: count}
        """
        item_list = [{"item": name, "count": count} for name, count in items.items()]
        result = self._send_command("overflow", {
            "action": "withdraw", "items": item_list
        }, timeout=timeout)
        return result.get("data", {})

    def overflow_status(self, timeout: float = 5.0) -> dict:
        """Get contents of the virtual overflow inventory.

        Returns:
            {items: [{item, count}], used_slots, free_slots, capacity}
        """
        result = self._send_command("overflow", {
            "action": "status"
        }, timeout=timeout)
        return result.get("data", {})

    # Default junk items to trash when clearing inventory.
    DEFAULT_JUNK_ITEMS = [
        "minecraft:cobblestone", "minecraft:dirt", "minecraft:gravel",
        "minecraft:diorite", "minecraft:andesite", "minecraft:granite",
        "minecraft:tuff", "minecraft:cobbled_deepslate", "minecraft:netherrack",
        "minecraft:sand", "minecraft:red_sand", "minecraft:clay_ball",
    ]

    def overflow_clear_junk(self, junk_items: list[str] | None = None,
                            timeout: float = 5.0) -> dict:
        """Auto-trash junk items from inventory.

        Args:
            junk_items: Items to consider junk. Defaults to DEFAULT_JUNK_ITEMS.
        Returns:
            {cleared: [{item, count}], total_cleared, inventory_free}
        """
        items = junk_items or self.DEFAULT_JUNK_ITEMS
        result = self._send_command("overflow", {
            "action": "clear_junk", "junk_items": items,
        }, timeout=timeout)
        return result.get("data", {})

    def overflow_free_slots(self, target: int = 5,
                            timeout: float = 5.0) -> dict:
        """Proactively free N inventory slots (trash junk first, then overflow).

        Args:
            target: desired number of free inventory slots
        Returns:
            {slots_freed, inventory_free, method}
        """
        result = self._send_command("overflow", {
            "action": "free_slots", "target": target
        }, timeout=timeout)
        return result.get("data", {})

    def full_inventory(self, timeout: float = 5.0) -> dict:
        """Combined view of player inventory + overflow.

        Returns:
            {items: {name: {inventory, overflow, total}},
             inventory_slots: {used, free, capacity},
             overflow_slots: {used, free, capacity},
             selected_slot: int}
        """
        inv = self.get_inventory(timeout)
        inv_data = inv.get("data", inv)  # unwrap envelope if present
        slots = inv_data.get("slots", [])

        # Aggregate inventory counts + track slot numbers
        inv_counts: dict[str, int] = {}
        inv_slots: dict[str, list[int]] = {}
        for s in slots:
            item = s.get("item")
            if not item:
                continue
            name = item.replace("minecraft:", "")
            inv_counts[name] = inv_counts.get(name, 0) + s.get("count", 1)
            inv_slots.setdefault(name, []).append(s.get("slot", -1))

        # Get overflow (may fail if mod not loaded)
        overflow_counts: dict[str, int] = {}
        overflow_data: dict = {}
        try:
            overflow_data = self.overflow_status(timeout)
            for entry in overflow_data.get("items", []):
                overflow_counts[entry["item"]] = entry["count"]
        except Exception:
            pass

        # Merge totals
        all_items = sorted(set(inv_counts) | set(overflow_counts))
        items = {}
        for name in all_items:
            items[name] = {
                "inventory": inv_counts.get(name, 0),
                "overflow": overflow_counts.get(name, 0),
                "total": inv_counts.get(name, 0) + overflow_counts.get(name, 0),
                "slots": inv_slots.get(name, []),
            }

        used_main = sum(1 for s in slots
                        if s.get("item") and s.get("slot", 99) < 36)

        return {
            "items": items,
            "inventory_slots": {
                "used": used_main, "free": 36 - used_main, "capacity": 36,
            },
            "overflow_slots": {
                "used": overflow_data.get("used_slots", 0),
                "free": overflow_data.get("free_slots", 0),
                "capacity": overflow_data.get("capacity", 0),
            },
            "selected_slot": inv_data.get("selected_slot", 0),
        }

    # ── Auto Torch Placement (Phase 58a) ────────────────────

    def torch_toggle(self, timeout: float = 5.0) -> dict:
        """Toggle auto torch placement (also toggles active — Java-side mirrors this)."""
        return self._send_command("torch", {"action": "toggle"}, timeout)

    def torch_enable(self, enabled: bool = True, timeout: float = 5.0) -> dict:
        """Enable or disable auto torch placement (also sets active — Java-side mirrors this)."""
        action = "enable" if enabled else "disable"
        return self._send_command("torch", {"action": action}, timeout)

    def torch_set_active(self, active: bool, timeout: float = 5.0) -> dict:
        """Set the task active flag (for standalone testing)."""
        return self._send_command("torch", {"active": active}, timeout)

    def torch_status(self, timeout: float = 5.0) -> dict:
        """Get current auto torch placer state."""
        return self._send_command("torch", {"action": "status"}, timeout)

    def torch_set_threshold(self, threshold: int, timeout: float = 5.0) -> dict:
        """Set light threshold for torch placement."""
        return self._send_command("torch", {
            "action": "set_threshold", "threshold": threshold
        }, timeout)

    # ── Atomic action primitives (Phase 48) ───────────────────

    def use_item(self, hand: str = "main", duration_ticks: int = 0,
                 timeout: float = 5.0) -> dict:
        """Use held item (eat, drink, throw, shield, etc.)."""
        params: dict = {"hand": hand}
        if duration_ticks > 0:
            params["duration_ticks"] = duration_ticks
        return self._send_command("use_item", params, timeout)

    def interact_entity(self, entity_type: str, radius: int = 5,
                        hand: str = "main", timeout: float = 5.0) -> dict:
        """Right-click nearest entity matching type filter."""
        return self._send_command("interact_entity", {
            "type": entity_type, "radius": radius, "hand": hand,
        }, timeout)

    def interact_block(self, x: int, y: int, z: int, hand: str = "main",
                       face: str = "up", timeout: float = 5.0) -> dict:
        """Right-click block at coordinates (open GUI, press button, etc.)."""
        return self._send_command("interact_block", {
            "x": x, "y": y, "z": z, "hand": hand, "face": face,
        }, timeout)

    def place_block(self, x: int, y: int, z: int, face: str = "up",
                    facing: str = None, timeout: float = 5.0) -> dict:
        """Place block from main hand at target position.

        Args:
            facing: Optional cardinal direction (north/south/east/west) to
                    temporarily set player yaw before placement, so directional
                    blocks (beds, stairs, pistons) face correctly.
        """
        params = {"x": x, "y": y, "z": z, "face": face}
        if facing:
            params["facing"] = facing
        return self._send_command("place_block", params, timeout)

    def break_block(self, x: int, y: int, z: int,
                    timeout: float = 5.0) -> dict:
        """Break single block at coordinates."""
        return self._send_command("break_block", {
            "x": x, "y": y, "z": z,
        }, timeout)

    def set_slot(self, slot: int | None = None, item: str | None = None,
                 timeout: float = 5.0) -> dict:
        """Select hotbar slot by index (0-8) or item name search."""
        params: dict = {}
        if slot is not None:
            params["slot"] = slot
        if item is not None:
            params["item"] = item
        return self._send_command("set_slot", params, timeout)

    def move_item(self, from_slot: int, to_slot: int,
                  timeout: float = 5.0) -> dict:
        """Move item between inventory slots."""
        return self._send_command("move_item", {
            "from_slot": from_slot, "to_slot": to_slot,
        }, timeout)

    def drop_item(self, slot: int | None = None, all: bool = False,
                  timeout: float = 5.0) -> dict:
        """Drop item from slot or selected hand."""
        params: dict = {"all": all}
        if slot is not None:
            params["slot"] = slot
        return self._send_command("drop_item", params, timeout)

    def swap_hands(self, timeout: float = 5.0) -> dict:
        """Swap main hand and off hand items."""
        return self._send_command("swap_hands", {}, timeout)

    def read_screen(self, timeout: float = 5.0) -> dict:
        """Read currently open screen (type, slots, trades)."""
        return self._send_command("read_screen", {}, timeout)

    def click_slot(self, slot: int, button: int = 0,
                   action: str = "PICKUP", timeout: float = 5.0) -> dict:
        """Click slot in open screen (craft, trade, etc.)."""
        return self._send_command("click_slot", {
            "slot": slot, "button": button, "action": action,
        }, timeout)

    def close_screen(self, timeout: float = 5.0) -> dict:
        """Close currently open screen/GUI."""
        return self._send_command("close_screen", {}, timeout)

    def get_entities(self, radius: int = 32, type: str | None = None,
                     limit: int = 50, timeout: float = 5.0) -> dict:
        """Get nearby entities with full data (health, equipment, effects, trades)."""
        params: dict = {"radius": radius, "limit": limit}
        if type is not None:
            params["type"] = type
        return self._send_command("get_entities", params, timeout)

    def get_effects(self, timeout: float = 5.0) -> dict:
        """Get player's active status effects."""
        return self._send_command("get_effects", {}, timeout)

    def mount(self, radius: int = 5, timeout: float = 5.0) -> dict:
        """Mount nearest rideable entity."""
        return self._send_command("mount", {"radius": radius}, timeout)

    def dismount(self, timeout: float = 5.0) -> dict:
        """Dismount current vehicle."""
        return self._send_command("dismount", {}, timeout)


# ── Module-level singleton ─────────────────────────────────────

_client: EmmatoneClient | None = None


def get_client() -> EmmatoneClient:
    """Get or create the singleton EmmatoneClient."""
    global _client
    if _client is None:
        _client = EmmatoneClient()
    return _client


def connect(host: str = DEFAULT_HOST, port: int | None = None) -> EmmatoneClient:
    """Connect (or reconnect) the singleton client."""
    global _client
    if port is None:
        port = _get_configured_port()
    if _client is not None:
        _client.disconnect()
    _client = EmmatoneClient(host=host, port=port)
    _client.connect()
    return _client


def disconnect():
    """Disconnect the singleton client."""
    global _client
    if _client is not None:
        _client.disconnect()
        _client = None
