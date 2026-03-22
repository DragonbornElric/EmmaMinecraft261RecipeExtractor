"""
Module-level configuration store for the Minecraft GOAP subsystem.

Consumers (Emma or any other brain) call ``configure(config_dict)`` at startup
before using any gamer modules.  The dict should contain the ``minecraft``
section from the host's config — keys like ``player_port``, ``camera_port``,
``build``, ``panic_teleport``, etc.
"""

from __future__ import annotations

_config: dict = {}


def configure(minecraft_config: dict) -> None:
    """Set the runtime configuration.  Call once at startup."""
    global _config
    _config = dict(minecraft_config)  # shallow copy for safety


def get(key: str, default=None):
    """Top-level config key lookup."""
    return _config.get(key, default)


def get_nested(*keys, default=None):
    """Walk nested dicts, e.g. ``get_nested("build", "staging_radius", default=24)``."""
    val = _config
    for k in keys:
        if not isinstance(val, dict):
            return default
        val = val.get(k)
        if val is None:
            return default
    return val


def raw() -> dict:
    """Return the full config dict (read-only intent)."""
    return _config
