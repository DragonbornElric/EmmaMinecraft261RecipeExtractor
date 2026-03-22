"""Unit tests for GamerMode (single composite kill_dragon goal)."""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from gamer.gamer_mode import GamerMode, PERSONALITY_OVERWORLD, PERSONALITY_NETHER, PERSONALITY_END


@pytest.fixture
def mock_client():
    client = MagicMock()
    client.set_goap_goals.return_value = {"status": "ok"}
    client.set_personality.return_value = {"status": "ok"}
    client.goap_debug.return_value = {"data": {"world_state": {"dimension": "minecraft:overworld"}}}
    return client


@pytest.fixture
def gamer(mock_client):
    return GamerMode(mock_client)


# ── Goal structure tests ──────────────────────────────────────────


class TestGoalGeneration:
    def test_builds_single_composite_goal(self, gamer):
        goals = gamer._build_all_goals()
        assert len(goals) == 1, f"Expected 1 composite goal, got {len(goals)}"

    def test_goal_is_kill_dragon(self, gamer):
        goal = gamer._build_all_goals()[0]
        assert goal["id"] == "kill_dragon"
        assert goal["type"] == "kill_dragon"

    def test_goal_has_required_fields(self, gamer):
        goal = gamer._build_all_goals()[0]
        assert "id" in goal
        assert "type" in goal
        assert "priority" in goal
        assert isinstance(goal["priority"], (int, float))
        assert "target" in goal

    def test_goal_has_high_priority(self, gamer):
        goal = gamer._build_all_goals()[0]
        assert goal["priority"] == 15.0

    def test_goal_target_is_empty(self, gamer):
        """Target is empty — GoalDecomposer on Java side handles expansion."""
        goal = gamer._build_all_goals()[0]
        assert goal["target"] == {}


# ── Activate / deactivate tests ──────────────────────────────────


class TestActivation:
    def test_activate_sets_goals(self, gamer, mock_client):
        gamer.activate()
        mock_client.set_goap_goals.assert_called_once()
        goals = mock_client.set_goap_goals.call_args[0][0]
        assert len(goals) == 1
        assert goals[0]["type"] == "kill_dragon"

    def test_activate_sets_personality(self, gamer, mock_client):
        gamer.activate()
        mock_client.set_personality.assert_called_once_with(PERSONALITY_OVERWORLD)

    def test_activate_marks_active(self, gamer):
        gamer.activate()
        assert gamer.active is True

    def test_deactivate_clears_goals(self, gamer, mock_client):
        gamer.activate()
        mock_client.set_goap_goals.reset_mock()
        gamer.deactivate()
        mock_client.set_goap_goals.assert_called_once_with([])
        assert gamer.active is False

    def test_deactivate_calls_cancel(self, gamer, mock_client):
        gamer.activate()
        gamer.deactivate()
        mock_client.cancel.assert_called_once()


# ── Refresh / dimension tests ─────────────────────────────────────


class TestRefresh:
    def test_refresh_when_inactive_returns_none(self, gamer):
        assert gamer.refresh() is None

    def test_refresh_resends_goals(self, gamer, mock_client):
        gamer.activate()
        mock_client.set_goap_goals.reset_mock()
        gamer.refresh({"data": {"world_state": {"dimension": "minecraft:overworld"}}})
        mock_client.set_goap_goals.assert_called_once()
        goals = mock_client.set_goap_goals.call_args[0][0]
        assert len(goals) == 1

    def test_refresh_switches_to_nether_personality(self, gamer, mock_client):
        gamer.activate()
        mock_client.set_personality.reset_mock()

        nether_state = {"data": {"world_state": {"dimension": "minecraft:the_nether"}}}
        gamer.refresh(nether_state)
        mock_client.set_personality.assert_called_once_with(PERSONALITY_NETHER)

    def test_refresh_switches_to_end_personality(self, gamer, mock_client):
        gamer.activate()
        mock_client.set_personality.reset_mock()

        end_state = {"data": {"world_state": {"dimension": "minecraft:the_end"}}}
        gamer.refresh(end_state)
        mock_client.set_personality.assert_called_once_with(PERSONALITY_END)

    def test_refresh_back_to_overworld_personality(self, gamer, mock_client):
        gamer.activate()
        # Go to nether first
        gamer.refresh({"data": {"world_state": {"dimension": "minecraft:the_nether"}}})
        mock_client.set_personality.reset_mock()
        # Back to overworld
        gamer.refresh({"data": {"world_state": {"dimension": "minecraft:overworld"}}})
        mock_client.set_personality.assert_called_once_with(PERSONALITY_OVERWORLD)

    def test_refresh_no_personality_change_same_dimension(self, gamer, mock_client):
        gamer.activate()
        mock_client.set_personality.reset_mock()
        # Same dimension as default (overworld)
        gamer.refresh({"data": {"world_state": {"dimension": "minecraft:overworld"}}})
        mock_client.set_personality.assert_not_called()


# ── Status property test ──────────────────────────────────────────


class TestStatus:
    def test_status_when_active(self, gamer):
        gamer.activate()
        s = gamer.status
        assert s["mode"] == "gamer"
        assert s["active"] is True
        assert s["goals_sent"] == 1

    def test_status_when_inactive(self, gamer):
        s = gamer.status
        assert s["active"] is False
        assert s["goals_sent"] == 0

    def test_status_has_dimension(self, gamer):
        s = gamer.status
        assert "dimension" in s


# ── Personality weight tests ──────────────────────────────────────


class TestPersonality:
    def test_overworld_personality_valid(self):
        for k, v in PERSONALITY_OVERWORLD.items():
            assert 0.0 <= v <= 2.0, f"Personality {k}={v} out of range"

    def test_nether_personality_valid(self):
        for k, v in PERSONALITY_NETHER.items():
            assert 0.0 <= v <= 2.0, f"Personality {k}={v} out of range"

    def test_end_personality_valid(self):
        for k, v in PERSONALITY_END.items():
            assert 0.0 <= v <= 2.0, f"Personality {k}={v} out of range"

    def test_all_personalities_have_same_keys(self):
        assert set(PERSONALITY_OVERWORLD.keys()) == set(PERSONALITY_NETHER.keys())
        assert set(PERSONALITY_OVERWORLD.keys()) == set(PERSONALITY_END.keys())
