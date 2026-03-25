"""
Hero mode: Hunt hostile mobs continuously with tiered equipment.

Sends a single ``set_mode`` command to the Java GOAP engine which owns
all goal/tier/personality logic.  Python is a thin wrapper.

Tiers:
    iron      — iron tools + armor (+ wooden/stone bootstrap)
    diamond   — iron baseline + diamond upgrades (default)
    netherite — full chain through netherite

Usage:
    from gamer.hero_mode import HeroMode
    hero = HeroMode(client)
    hero.activate()                # diamond tier (default)
    hero.activate(tier="iron")     # iron tier
    hero.activate(tier="netherite")
    hero.deactivate()
"""

from __future__ import annotations

import logging
import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from gamer.emmatone_client import EmmatoneClient

log = logging.getLogger(__name__)

VALID_TIERS = ("iron", "diamond", "netherite")


class HeroMode:
    """Continuous hostile-hunting mode with tier-selectable equipment goals."""

    def __init__(self, client: EmmatoneClient, tier: str = "diamond") -> None:
        self.client = client
        self.tier = tier if tier in VALID_TIERS else "diamond"
        self.active = False
        self.activated_at: float = 0
        self._goals_set: int = 0

    # ── Public API ────────────────────────────────────────────────

    def activate(self, tier: str | None = None) -> dict:
        """Activate hero mode at the given tier (or the stored default)."""
        if tier and tier in VALID_TIERS:
            self.tier = tier
        result = self.client.set_mode("hero", self.tier)
        self.active = True
        self.activated_at = time.time()
        self._goals_set = result.get("goals_set", 0) if isinstance(result, dict) else 0
        log.info("HeroMode activated: tier=%s, goals=%d", self.tier, self._goals_set)
        return result

    def deactivate(self) -> dict:
        """Clear all hero goals by switching to idle mode."""
        result = self.client.set_mode("idle")
        self.active = False
        log.info("HeroMode deactivated")
        return result

    @property
    def status(self) -> dict:
        """Current mode state for GUI display."""
        return {
            "mode": "hero",
            "active": self.active,
            "tier": self.tier,
            "activated_at": self.activated_at,
            "goals_set": self._goals_set,
        }
