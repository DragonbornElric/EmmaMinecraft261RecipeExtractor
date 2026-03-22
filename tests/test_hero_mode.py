"""Unit tests for HeroMode (thin wrapper over Java set_mode command)."""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from gamer.hero_mode import HeroMode, VALID_TIERS


@pytest.fixture
def mock_client():
    client = MagicMock()
    client.set_mode.return_value = {"status": "ok", "mode": "hero", "tier": "diamond", "goals_set": 25}
    client.set_goap_goals.return_value = {"status": "ok"}
    client.cancel.return_value = None
    return client


@pytest.fixture
def hero(mock_client):
    return HeroMode(mock_client)


# ── Tier validation ──────────────────────────────────────────────


class TestTierValidation:
    def test_default_tier_is_diamond(self, mock_client):
        hero = HeroMode(mock_client)
        assert hero.tier == "diamond"

    @pytest.mark.parametrize("tier", ["iron", "diamond", "netherite"])
    def test_valid_tiers_accepted(self, mock_client, tier):
        hero = HeroMode(mock_client, tier=tier)
        assert hero.tier == tier

    def test_invalid_tier_defaults_to_diamond(self, mock_client):
        hero = HeroMode(mock_client, tier="gold")
        assert hero.tier == "diamond"

    def test_valid_tiers_constant(self):
        assert set(VALID_TIERS) == {"iron", "diamond", "netherite"}


# ── Activation tests ─────────────────────────────────────────────


class TestActivation:
    def test_activate_sends_set_mode(self, hero, mock_client):
        hero.activate()
        mock_client.set_mode.assert_called_once_with("hero", "diamond")

    def test_activate_with_tier_override(self, hero, mock_client):
        hero.activate(tier="iron")
        mock_client.set_mode.assert_called_once_with("hero", "iron")
        assert hero.tier == "iron"

    def test_activate_with_netherite(self, hero, mock_client):
        hero.activate(tier="netherite")
        mock_client.set_mode.assert_called_once_with("hero", "netherite")
        assert hero.tier == "netherite"

    def test_activate_invalid_tier_keeps_current(self, hero, mock_client):
        hero.activate(tier="gold")
        mock_client.set_mode.assert_called_once_with("hero", "diamond")
        assert hero.tier == "diamond"

    def test_activate_marks_active(self, hero):
        hero.activate()
        assert hero.active is True
        assert hero.activated_at > 0

    def test_activate_stores_goal_count(self, hero):
        hero.activate()
        assert hero._goals_set == 25


# ── Deactivation tests ──────────────────────────────────────────


class TestDeactivation:
    def test_deactivate_cancels_and_clears(self, hero, mock_client):
        hero.activate()
        mock_client.reset_mock()
        hero.deactivate()
        mock_client.cancel.assert_called_once()
        mock_client.set_goap_goals.assert_called_once_with([])

    def test_deactivate_marks_inactive(self, hero):
        hero.activate()
        hero.deactivate()
        assert hero.active is False


# ── Status tests ─────────────────────────────────────────────────


class TestStatus:
    def test_status_when_active(self, hero):
        hero.activate()
        s = hero.status
        assert s["mode"] == "hero"
        assert s["active"] is True
        assert s["tier"] == "diamond"
        assert s["goals_set"] == 25

    def test_status_when_inactive(self, hero):
        s = hero.status
        assert s["active"] is False
        assert s["tier"] == "diamond"

    def test_status_reflects_tier_change(self, hero):
        hero.activate(tier="iron")
        assert hero.status["tier"] == "iron"
