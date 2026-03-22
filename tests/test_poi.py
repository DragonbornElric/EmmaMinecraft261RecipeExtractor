"""Unit tests for POI registry and portal helpers."""

from __future__ import annotations

import os
import tempfile

import pytest

from gamer.db import init_db
from gamer.poi import POIRegistry


@pytest.fixture
def poi_db(tmp_path):
    """Create a temporary database with schema and return its path."""
    db_path = str(tmp_path / "test_minecraft.db")
    init_db(db_path)
    return db_path


@pytest.fixture
def registry(poi_db):
    return POIRegistry(db_path=poi_db)


# ── Basic CRUD ────────────────────────────────────────────────────


class TestPOICrud:
    def test_upsert_and_get(self, registry):
        registry.upsert_poi("Test Base", 100, 64, 200)
        poi = registry.get_poi("Test Base")
        assert poi is not None
        assert poi["x"] == 100
        assert poi["y"] == 64
        assert poi["z"] == 200

    def test_upsert_with_dimension(self, registry):
        registry.upsert_poi("Nether Hub", 10, 80, 20, dimension="minecraft:the_nether")
        poi = registry.get_poi("Nether Hub")
        assert poi is not None
        assert poi["dimension"] == "minecraft:the_nether"

    def test_default_dimension_is_overworld(self, registry):
        registry.upsert_poi("Surface Base", 0, 64, 0)
        poi = registry.get_poi("Surface Base")
        assert poi["dimension"] == "minecraft:overworld"

    def test_same_name_different_dimensions(self, registry):
        registry.upsert_poi("Portal", 100, 64, 200, dimension="minecraft:overworld")
        registry.upsert_poi("Portal", 12, 80, 25, dimension="minecraft:the_nether")
        pois = registry.get_all_pois()
        portal_pois = [p for p in pois if p["name"] == "Portal"]
        assert len(portal_pois) == 2

    def test_delete(self, registry):
        registry.upsert_poi("Temp", 0, 0, 0)
        assert registry.delete_poi("Temp")
        assert registry.get_poi("Temp") is None

    def test_get_all(self, registry):
        registry.upsert_poi("A", 0, 0, 0)
        registry.upsert_poi("B", 10, 10, 10)
        pois = registry.get_all_pois()
        assert len(pois) == 2

    def test_resolve_name(self, registry):
        registry.upsert_poi("Farm", 50, 64, 50)
        coords = registry.resolve_name("Farm")
        assert coords == (50, 64, 50)

    def test_resolve_name_missing(self, registry):
        assert registry.resolve_name("nonexistent") is None


# ── Portal helpers ────────────────────────────────────────────────


class TestPortalHelpers:
    def test_save_portal(self, registry):
        pid = registry.save_portal("Base Portal", 100, 64, 200, "minecraft:overworld")
        assert pid > 0
        poi = registry.get_poi("Base Portal")
        assert poi["poi_type"] == "nether_portal"

    def test_save_portal_custom_type(self, registry):
        registry.save_portal("Stronghold", 500, 30, 500, "minecraft:overworld", portal_type="end_portal")
        poi = registry.get_poi("Stronghold")
        assert poi["poi_type"] == "end_portal"

    def test_get_portals_all(self, registry):
        registry.save_portal("P1", 0, 64, 0, "minecraft:overworld")
        registry.save_portal("P2", 10, 80, 10, "minecraft:the_nether")
        portals = registry.get_portals()
        assert len(portals) == 2

    def test_get_portals_by_dimension(self, registry):
        registry.save_portal("P1", 0, 64, 0, "minecraft:overworld")
        registry.save_portal("P2", 10, 80, 10, "minecraft:the_nether")
        ow = registry.get_portals(dimension="minecraft:overworld")
        assert len(ow) == 1
        assert ow[0]["name"] == "P1"

    def test_get_nearest_portal(self, registry):
        registry.save_portal("Near", 10, 64, 10, "minecraft:overworld")
        registry.save_portal("Far", 1000, 64, 1000, "minecraft:overworld")
        nearest = registry.get_nearest_portal(0, 0, "minecraft:overworld")
        assert nearest is not None
        assert nearest["name"] == "Near"

    def test_get_nearest_portal_none(self, registry):
        result = registry.get_nearest_portal(0, 0, "minecraft:overworld")
        assert result is None

    def test_get_nearest_portal_filters_type(self, registry):
        registry.save_portal("NP", 10, 64, 10, "minecraft:overworld", portal_type="nether_portal")
        registry.save_portal("EP", 5, 30, 5, "minecraft:overworld", portal_type="end_portal")
        nearest = registry.get_nearest_portal(0, 0, "minecraft:overworld", portal_type="end_portal")
        assert nearest["name"] == "EP"


# ── Spatial math ──────────────────────────────────────────────────


class TestSpatialMath:
    def test_compass_east(self):
        assert POIRegistry.compass_direction(100, 0) == "E"

    def test_compass_north(self):
        assert POIRegistry.compass_direction(0, -100) == "N"

    def test_compass_south(self):
        assert POIRegistry.compass_direction(0, 100) == "S"

    def test_compass_here(self):
        assert POIRegistry.compass_direction(0, 0) == "here"

    def test_horizontal_distance(self):
        d = POIRegistry.horizontal_distance(0, 0, 3, 4)
        assert d == pytest.approx(5.0)

    def test_format_relative_close(self):
        text = POIRegistry.format_relative("Base", 1, 1, 1.0)
        assert "right here" in text

    def test_format_relative_with_direction(self):
        text = POIRegistry.format_relative("Farm", 100, 0, 100.0)
        assert "east" in text
        assert "100" in text


# ── City overview ─────────────────────────────────────────────────


class TestCityOverview:
    def test_overview_empty(self, registry):
        text = registry.city_overview(0, 64, 0)
        assert "No known locations" in text

    def test_overview_with_pois(self, registry):
        registry.upsert_poi("Farm", 100, 64, 0)
        registry.upsert_poi("Mine", 0, 30, 100)
        text = registry.city_overview(0, 64, 0)
        assert "Farm" in text
        assert "Mine" in text

    def test_overview_max_distance(self, registry):
        registry.upsert_poi("Near", 10, 64, 0)
        registry.upsert_poi("Far", 10000, 64, 0)
        text = registry.city_overview(0, 64, 0, max_distance=100)
        assert "Near" in text
        assert "Far" not in text
