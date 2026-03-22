"""CameraBot — preset-switching client for the spectator camera (Phase 31+).

Sends preset commands to the CameraBot bridge mod instance.  High-frequency
position tracking is handled entirely in Java (CameraTracker connects directly
to Emma's bridge mod).  Python only sends rare set_camera_preset commands for
scene/task transitions.

The camera account connects via a second bridge-mod WebSocket on port 8766.
"""

from __future__ import annotations

import json
import logging
import threading

import websocket  # websocket-client (sync)

log = logging.getLogger(__name__)

# ── Constants ──────────────────────────────────────────────────

DEFAULT_HOST = "localhost"
DEFAULT_CAM_PORT = 8766          # separate from bridge mod on 8765
RECONNECT_DELAY = 3.0

# Available presets (must match CameraPresets.java)
PRESET_NAMES = [
    "overhead", "build_closeup", "combat_tight", "side_track",
    "wide_overhead", "mine_angle", "front_face", "celebration",
]


# ── CameraBot ──────────────────────────────────────────────────

class CameraBot:
    """Thin WebSocket client for sending preset commands to the CameraBot bridge.

    Position tracking is done Java-to-Java.  This class only handles:
    - WebSocket connection lifecycle
    - Sending set_camera_preset commands
    - State queries for diagnostics
    """

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_CAM_PORT):
        self.url = f"ws://{host}:{port}"
        self.ws: websocket.WebSocketApp | None = None
        self.connected = False
        self._connect_event = threading.Event()

        self._thread: threading.Thread | None = None

        # Track current preset name for diagnostics
        self.current_preset_name: str = "front_face"

    # ── Connection ────────────────────────────────────────────

    def connect(self):
        """Start WebSocket connection to camera bridge on a daemon thread."""
        if self._thread and self._thread.is_alive():
            log.debug("CameraBot already connected")
            return

        self._connect_event.clear()
        self.ws = websocket.WebSocketApp(
            self.url,
            on_open=self._on_open,
            on_message=self._on_message,
            on_close=self._on_close,
            on_error=self._on_error,
        )
        self._thread = threading.Thread(
            target=self.ws.run_forever,
            kwargs={"reconnect": RECONNECT_DELAY},
            daemon=True,
            name="CameraBotWS",
        )
        self._thread.start()
        log.info("CameraBot connecting to %s", self.url)

    def disconnect(self):
        """Close connection cleanly."""
        if self.ws:
            self.ws.close()
        self.connected = False
        self._connect_event.clear()
        log.info("CameraBot disconnected")

    def wait_for_connection(self, timeout: float = 3.0) -> bool:
        """Block until connected or timeout.  Returns True if connected."""
        return self._connect_event.wait(timeout=timeout)

    # ── Preset control ────────────────────────────────────────

    def set_preset(self, preset_name: str):
        """Send set_camera_preset command to the bridge mod."""
        if preset_name not in PRESET_NAMES:
            log.warning("Unknown camera preset: %s", preset_name)
            return
        if not self.connected or not self.ws:
            log.debug("CameraBot not connected — skipping preset change")
            return

        self.current_preset_name = preset_name
        msg = json.dumps({
            "type": "command",
            "command": "set_camera_preset",
            "params": {"preset": preset_name},
        })
        try:
            self.ws.send(msg)
            log.info("CameraBot preset → %s", preset_name)
        except Exception:
            log.debug("CameraBot send failed — connection lost?")

    def get_preset_name(self) -> str:
        return self.current_preset_name

    def configure_camera(self, *, tp_distance: float | None = None,
                         lerp_speed: float | None = None):
        """Send configure_camera command to adjust runtime camera settings."""
        if not self.connected or not self.ws:
            log.debug("CameraBot not connected — skipping configure_camera")
            return
        params: dict = {}
        if tp_distance is not None:
            params["tp_distance"] = tp_distance
        if lerp_speed is not None:
            params["lerp_speed"] = lerp_speed
        if not params:
            return
        msg = json.dumps({
            "type": "command",
            "command": "configure_camera",
            "params": params,
        })
        try:
            self.ws.send(msg)
            log.info("CameraBot configure_camera → %s", params)
        except Exception:
            log.debug("CameraBot send failed — connection lost?")

    # ── WebSocket callbacks ───────────────────────────────────

    def _on_open(self, ws):
        self.connected = True
        self._connect_event.set()
        log.info("CameraBot connected")

    def _on_close(self, ws, code, msg):
        self.connected = False
        self._connect_event.clear()
        log.info("CameraBot disconnected (%s)", code)

    def _on_error(self, ws, error):
        self.connected = False
        self._connect_event.clear()
        log.debug("CameraBot error: %s", error)

    def _on_message(self, ws, message):
        """Handle incoming messages from camera bridge (acks, etc)."""
        try:
            data = json.loads(message)
        except json.JSONDecodeError:
            return
        log.debug("CameraBot recv: %s", data.get("type", "?"))

    # ── State summary ─────────────────────────────────────────

    def get_state_summary(self) -> str:
        """Formatted summary for diagnostics."""
        return (
            f"Camera: preset={self.current_preset_name}, "
            f"connected={self.connected}"
        )


# ── Module-level singleton ─────────────────────────────────────

_camera: CameraBot | None = None


def get_camera() -> CameraBot:
    """Get or create the module-level CameraBot singleton."""
    global _camera
    if _camera is None:
        _camera = CameraBot()
    return _camera


def _get_configured_cam_port() -> int:
    """Read camera_port from gamer config, fallback to DEFAULT_CAM_PORT."""
    from gamer import config as _cfg
    return _cfg.get("camera_port", DEFAULT_CAM_PORT)


def connect_camera(host: str = DEFAULT_HOST, port: int | None = None):
    """Connect the camera singleton."""
    if port is None:
        port = _get_configured_cam_port()
    cam = get_camera()
    cam.url = f"ws://{host}:{port}"
    cam.connect()


def disconnect_camera():
    """Disconnect the camera singleton."""
    global _camera
    if _camera:
        _camera.disconnect()
