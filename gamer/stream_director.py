"""StreamDirector — Orchestrator-controlled scene management (Phase 31/32).

Deterministic rules map Emmatone task-state changes to scene switches
and camera preset selections.  No LLM involvement in any visual production
decisions.  Works with any ``IStreamBackend`` implementation (FFmpeg,
OBS, or disabled) and the overlay server for Minecraft HUD overlays.

Scene transitions respect a minimum hold time (2 s) to prevent flickering.
During building, angles rotate automatically on a configurable interval.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Any

log = logging.getLogger(__name__)


# ── Transition map: task → (scene, camera_preset) ──────────────

TASK_SCENE_MAP: dict[str, tuple[str, str]] = {
    "building":    ("MC Build Cam",     "overhead"),
    "mining":      ("MC First Person",  "mine_angle"),
    "navigating":  ("MC Cinematic",     "side_track"),
    "combat":      ("MC First Person",  "combat_tight"),
    "idle":        ("MC Face Cam",      "front_face"),
    "gathering":   ("MC Cinematic",     "wide_overhead"),
    "exploring":   ("MC Cinematic",     "side_track"),
}

# Scenes that exist in OBS (created by setup_minecraft_scenes.py)
MC_SCENES = [
    "MC First Person",   # Emma's POV game capture
    "MC Cinematic",      # Camera account game capture
    "MC Composite",      # Camera + PiP Emma POV + Animaze overlay
    "MC Build Cam",      # Camera close-up + build progress overlay
    "MC Face Cam",       # Camera front-face + large Animaze avatar
]

# Scene name → overlay_scene value for mc_overlay.html (mirrors scene_defs.py)
SCENE_OVERLAY_MAP: dict[str, str] = {
    "MC First Person": "first_person",
    "MC Cinematic":    "cinematic",
    "MC Composite":    "composite",
    "MC Build Cam":    "build_cam",
    "MC Face Cam":     "face_cam",
}

# ── Build-angle rotation presets ───────────────────────────────

BUILD_ROTATION_PRESETS = ("overhead", "build_closeup")
DEFAULT_ROTATION_INTERVAL = 15.0   # seconds between angle changes
MIN_SCENE_HOLD = 2.0               # seconds — minimum before next switch
CELEBRATION_HOLD = 5.0             # seconds to hold celebration shot


class StreamDirector:
    """Orchestrator-controlled scene management.

    Transitions are deterministic rules based on task state — no LLM,
    no manual intervention.

    Requires:
        camera_bot: ``gamer.camera_bot.CameraBot`` instance
        backend: Any ``IStreamBackend`` (FFmpeg, OBS, NullBackend) — optional
    """

    def __init__(self):
        self.camera_bot = None          # set via attach_camera()
        self._backend = None            # IStreamBackend (OBS, FFmpeg, or None)
        self._obs = None                # legacy alias
        self._obs_connected = False

        self.current_scene: str = "MC Composite"
        self.current_task: str = "idle"
        self._last_switch_time: float = 0.0

        # Build-angle rotation
        self.rotation_interval: float = DEFAULT_ROTATION_INTERVAL
        self._rotation_active = False
        self._rotation_thread: threading.Thread | None = None
        self._rotation_idx: int = 0

        self._lock = threading.Lock()

    # ── Lifecycle ─────────────────────────────────────────────

    def attach_camera(self, camera_bot):
        """Bind the CameraBot instance for preset control."""
        self.camera_bot = camera_bot

    def attach_backend(self, backend):
        """Bind any IStreamBackend (FFmpeg, OBS, NullBackend)."""
        self._backend = backend
        self._obs = backend        # legacy alias for existing callers
        self._obs_connected = getattr(backend, "connected", False)
        if self._obs_connected:
            cur = backend.get_current_scene()
            if cur:
                self.current_scene = cur
            log.info("StreamDirector: backend ready, scene=%s", self.current_scene)

    def connect_obs(self, host: str = "localhost", port: int = 4455,
                    password: str = ""):
        """Connect to OBS WebSocket (legacy path)."""
        try:
            from OBS.obs_client import OBSClient
            obs = OBSClient(password=password, host=host, port=port)
            obs.connect(timeout=5)
            self.attach_backend(obs)
        except Exception as exc:
            log.warning("StreamDirector: OBS connect failed — %s", exc)
            self._backend = None
            self._obs = None
            self._obs_connected = False

    def disconnect(self):
        """Clean shutdown."""
        self._rotation_active = False
        if self._backend:
            try:
                self._backend.disconnect()
            except Exception:
                pass
            self._backend = None
            self._obs = None
            self._obs_connected = False

    @property
    def obs_connected(self) -> bool:
        return self._obs_connected and self._backend is not None

    @property
    def backend_connected(self) -> bool:
        """Whether any stream backend is connected."""
        return self._backend is not None and getattr(self._backend, 'connected', False)

    # ── Event handlers (called by Orchestrator) ───────────────

    def on_task_changed(self, task_type: str, context: dict | None = None):
        """Called by Orchestrator when Emmatone task type changes.

        Selects scene + camera preset based on task.
        Starts/stops build-angle rotation as appropriate.
        """
        self.current_task = task_type
        mapping = TASK_SCENE_MAP.get(task_type, ("MC Composite", "front_face"))
        scene, preset = mapping

        self._switch_scene(scene)
        if self.camera_bot:
            self.camera_bot.set_preset(preset)

        # Manage build-angle rotation
        if task_type == "building":
            self._start_rotation()
        else:
            self._stop_rotation()

    def on_goal_complete(self, goal_name: str):
        """Celebration shot — wide angle of completed build, hold 5 s."""
        if self.camera_bot:
            self.camera_bot.set_preset("celebration")
        self._switch_scene("MC Cinematic")

        def _revert():
            time.sleep(CELEBRATION_HOLD)
            self._switch_scene("MC Composite")
            if self.camera_bot:
                self.camera_bot.set_preset("front_face")

        threading.Thread(target=_revert, daemon=True, name="CelebRevert").start()

    def on_narration_start(self):
        """Switch to face cam during narration moments."""
        if self.camera_bot:
            self.camera_bot.set_preset("front_face")
        self._switch_scene("MC Face Cam")

    def on_narration_end(self):
        """Return to task-appropriate scene after narration."""
        mapping = TASK_SCENE_MAP.get(self.current_task, ("MC Composite", "front_face"))
        scene, preset = mapping
        self._switch_scene(scene)
        if self.camera_bot:
            self.camera_bot.set_preset(preset)

    def on_combat_start(self):
        """Snap to combat scene + camera."""
        if self.camera_bot:
            self.camera_bot.set_preset("combat_tight")
        self._switch_scene("MC First Person")

    def on_combat_end(self):
        """Return from combat to previous task scene."""
        self.on_narration_end()  # same logic — restore task scene

    # ── OBS scene switching ───────────────────────────────────

    def _switch_scene(self, scene_name: str):
        """Switch OBS scene, respecting minimum hold time."""
        now = time.time()
        with self._lock:
            if scene_name == self.current_scene:
                return
            if now - self._last_switch_time < MIN_SCENE_HOLD:
                return  # prevent rapid flickering

            if self._backend and self._obs_connected:
                try:
                    ok = self._backend.set_current_scene(scene_name)
                    if ok:
                        self.current_scene = scene_name
                        self._last_switch_time = now
                        log.info("StreamDirector: scene → %s", scene_name)
                        self._push_overlay_scene(scene_name)
                    else:
                        log.debug("StreamDirector: scene switch failed for %s", scene_name)
                except Exception as exc:
                    log.warning("StreamDirector: backend error — %s", exc)
            else:
                # No OBS connection — just track scene name for state queries
                self.current_scene = scene_name
                self._last_switch_time = now
                self._push_overlay_scene(scene_name)
                log.debug("StreamDirector: scene → %s (OBS offline)", scene_name)

    # ── Build-angle rotation ──────────────────────────────────

    def _start_rotation(self):
        """Start cycling between overhead and close-up during building."""
        if self._rotation_active:
            return
        self._rotation_active = True
        self._rotation_idx = 0
        self._rotation_thread = threading.Thread(
            target=self._rotation_loop, daemon=True, name="CamRotation",
        )
        self._rotation_thread.start()

    def _stop_rotation(self):
        self._rotation_active = False

    def _rotation_loop(self):
        """Alternate between build camera presets at fixed interval."""
        while self._rotation_active:
            time.sleep(self.rotation_interval)
            if not self._rotation_active or not self.camera_bot:
                break
            self._rotation_idx = (self._rotation_idx + 1) % len(BUILD_ROTATION_PRESETS)
            preset = BUILD_ROTATION_PRESETS[self._rotation_idx]
            self.camera_bot.set_preset(preset)
            log.debug("StreamDirector: build rotation → %s", preset)

    # ── Overlay push helpers ──────────────────────────────────

    def _push_overlay_scene(self, scene_name: str):
        """Push mc_scene_change to overlay so HUD adapts to the active scene."""
        overlay_scene = SCENE_OVERLAY_MAP.get(scene_name)
        if not overlay_scene:
            return
        try:
            from overlay.overlay_server import push_event
            push_event({
                "type": "mc_scene_change",
                "scene": overlay_scene,
                "scene_name": scene_name,
            })
        except Exception as exc:
            log.debug("StreamDirector: overlay scene push failed — %s", exc)

    def push_build_progress(self, goal_name: str, progress_pct: float,
                            block_type: str = "", placed: int = 0,
                            total: int = 0):
        """Push build progress to the Minecraft overlay via overlay_server."""
        try:
            from overlay.overlay_server import push_event
            push_event({
                "type": "mc_build_progress",
                "goal_name": goal_name,
                "progress": round(progress_pct, 1),
                "current_block": block_type,
                "placed": placed,
                "total": total,
            })
        except Exception as exc:
            log.debug("StreamDirector: overlay push failed — %s", exc)

    def push_goal_info(self, goal_name: str, status: str,
                       aesthetic_notes: str = ""):
        """Push current goal info to the overlay."""
        try:
            from overlay.overlay_server import push_event
            push_event({
                "type": "mc_goal_info",
                "goal_name": goal_name,
                "status": status,
                "aesthetic_notes": aesthetic_notes,
            })
        except Exception as exc:
            log.debug("StreamDirector: overlay push failed — %s", exc)

    def push_inventory_summary(self, items: list[dict]):
        """Push notable inventory items to overlay."""
        try:
            from overlay.overlay_server import push_event
            push_event({
                "type": "mc_inventory",
                "items": items[:12],  # top 12 notable items
            })
        except Exception as exc:
            log.debug("StreamDirector: overlay push failed — %s", exc)

    # ── State summary ─────────────────────────────────────────

    def get_state_summary(self) -> str:
        return (
            f"StreamDirector: scene={self.current_scene}, "
            f"task={self.current_task}, "
            f"obs={'connected' if self.obs_connected else 'offline'}, "
            f"rotation={'active' if self._rotation_active else 'off'}"
        )


# ── Module-level singleton ─────────────────────────────────────

_director: StreamDirector | None = None


def get_director() -> StreamDirector:
    """Get or create the module-level StreamDirector singleton."""
    global _director
    if _director is None:
        _director = StreamDirector()
    return _director


def shutdown_director():
    """Clean shutdown of the singleton."""
    global _director
    if _director:
        _director.disconnect()
        _director = None
