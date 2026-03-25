"""
Gamer mode: Beat Minecraft from nothing.

Sends a single composite goal (kill_dragon) to the Java GOAP engine.
GoalDecomposer on the Java side expands it into the full tech tree:
ender eyes, diamond gear, food, nether prep — all derived automatically
from the ItemRecipeRegistry dependency chain.

Tool breakage is handled naturally: decomposition runs against live
inventory every ~10 ticks, so a broken pickaxe re-creates the subgoal
chain without any Python-side intervention.

Usage:
    from gamer.gamer_mode import GamerMode
    gamer = GamerMode(client)
    gamer.activate()           # Sets kill_dragon goal + personality
    gamer.refresh(debug_state) # Switch personality by dimension (optional)
    gamer.deactivate()         # Clears dynamic goals
"""

from __future__ import annotations

import logging
import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from gamer.emmatone_client import EmmatoneClient

log = logging.getLogger(__name__)

# ── Personality presets by dimension ───────────────────────────────

PERSONALITY_OVERWORLD = {
    "safety": 1.0,
    "aggression": 0.6,
    "exploration": 1.2,
    "resource_hoarding": 1.5,
}

PERSONALITY_NETHER = {
    "safety": 1.4,
    "aggression": 0.8,
    "exploration": 0.8,
    "resource_hoarding": 1.0,
}

PERSONALITY_END = {
    "safety": 1.5,
    "aggression": 1.0,
    "exploration": 0.3,
    "resource_hoarding": 0.3,
}


class GamerMode:
    """Beat-the-game mode via single composite kill_dragon goal."""

    def __init__(self, client: EmmatoneClient) -> None:
        self.client = client
        self.active = False
        self.activated_at: float = 0
        self.last_refresh: float = 0
        self._dimension: str = "minecraft:overworld"
        self._goals_sent: int = 0

    # ── Public API ────────────────────────────────────────────────

    def activate(self) -> dict:
        """Activate gamer mode — Java sets kill_dragon goal + personality."""
        result = self.client.set_mode("gamer")
        self.active = True
        self.activated_at = time.time()
        self.last_refresh = time.time()
        self._goals_sent = result.get("goal_count", 0)
        log.info("GamerMode activated via set_mode('gamer')")
        return result

    def deactivate(self) -> dict:
        """Clear all gamer goals by switching to idle mode."""
        result = self.client.set_mode("idle")
        self.active = False
        log.info("GamerMode deactivated")
        return result

    def refresh(self, debug_state: dict | None = None) -> dict | None:
        """Periodic re-evaluation. Switches personality by dimension.

        Called every 30-60s by the consumer. Queries goap_debug if no
        state is provided. Returns the set_goap_goals result or None.
        """
        if not self.active:
            return None

        if debug_state is None:
            debug_state = self.client.goap_debug(include_world_state=True)

        data = debug_state.get("data", debug_state)
        world_state = data.get("world_state", data.get("last_world_state", {}))
        new_dim = world_state.get("dimension", self._dimension)

        # Re-send goals (Java decomposer handles dimension boosts internally)
        goals = self._build_all_goals()
        result = self.client.set_goap_goals(goals)
        self._goals_sent = len(goals)

        # Update personality if dimension changed
        if new_dim != self._dimension:
            self._dimension = new_dim
            if "nether" in new_dim:
                self.client.set_personality(PERSONALITY_NETHER)
                log.info("GamerMode: switched to nether personality")
            elif "the_end" in new_dim:
                self.client.set_personality(PERSONALITY_END)
                log.info("GamerMode: switched to end personality")
            else:
                self.client.set_personality(PERSONALITY_OVERWORLD)
                log.info("GamerMode: switched to overworld personality")

        self.last_refresh = time.time()
        return result

    @property
    def status(self) -> dict:
        """Current mode state for GUI display."""
        return {
            "mode": "gamer",
            "active": self.active,
            "activated_at": self.activated_at,
            "last_refresh": self.last_refresh,
            "dimension": self._dimension,
            "goals_sent": self._goals_sent,
        }

    # ── Goal builder ───────────────────────────────────────────────

    def _build_all_goals(self) -> list[dict]:
        """Single composite goal — GoalDecomposer handles the full tech tree."""
        return [
            {
                "id": "kill_dragon",
                "type": "kill_dragon",
                "priority": 15.0,
                "target": {},
            },
        ]
