# EmmaMinecraft — Standalone Minecraft GOAP Subsystem

> **What is this?** A brain-agnostic Minecraft automation package. Provides WebSocket
> bridge to a Fabric mod (Emmatone pathfinder + GOAP agent), build system, POI registry,
> camera bot, and stream director. Any AI brain can plug into it via the Python API.

## Quick Start

```python
import gamer
gamer.configure({"player_port": 8765, "camera_port": 8766, "build": {...}})
gamer.init_db()

from gamer.emmatone_client import connect
client = connect()
client.goto(100, 64, 200)
```

## Installation

```bash
pip install -e C:\Users\Owner\EmmaMinecraft
```

Installed as editable package in the consumer's venv (e.g., Emma's `.venv/`).

## Architecture

```
Consumer (Emma / any brain)
    ↓ configure() + init_db()
    ↓ from gamer.emmatone_client import connect
    ↓
gamer package (Python)
    ↓ WebSocket ws://localhost:8765
    ↓
emma-pathfinder (Java Fabric mod)
    ├── Emmatone pathfinder (forked, unobfuscated/Mojang mappings)
    ├── WebSocket bridge (JSON protocol)
    └── GOAP agent (autonomous action scorer)
```

## Package Map

| File | Purpose |
|------|---------|
| `gamer/__init__.py` | Package entry: `configure()`, `init_db()` |
| `gamer/config.py` | Module-level config store |
| `gamer/db.py` | `minecraft.db` schema + connection factory |
| `gamer/emmatone_client.py` | Sync WebSocket bridge to Minecraft mod (~1,200 lines) |
| `gamer/build_db.py` | Build guide queries (guides, goals, substitutions, progress) |
| `gamer/build_tools.py` | High-level build orchestration (idempotent functions) |
| `gamer/block_catalog.py` | Block knowledge, variant lists, seed data |
| `gamer/camera_bot.py` | CameraBot spectator WebSocket client |
| `gamer/stream_director.py` | Scene/camera management (deterministic rules) |
| `gamer/deliberation.py` | Pre-stream goal planning (optional LLM call) |
| `gamer/poi.py` | POI registry + spatial math (compass directions) |
| `gamer/smelting_tracker.py` | Async furnace job tracker |
| `gamer/schematic_parser.py` | .schematic/.litematic/.schem import |
| `gamer/guide_to_litematic.py` | Build guide → .litematic converter |
| `gamer/gameplay_analyzer.py` | Gameplay logger JSONL session analysis |
| `gamer/session_viewer.py` | CLI timeline viewer |

## Java Mods (`java/`)

| Mod | Purpose |
|-----|---------|
| `emma-pathfinder/` | **Main mod** — Emmatone + WebSocket bridge + GOAP. Output: `emma-bridge-mod-0.2.0.jar` |
| `emma-gameplay-logger/` | Gameplay event logger. Output: `emma-gameplay-logger-0.1.0.jar` |
| `emma-endinv/` | Endless Inventory (RPG-style infinite storage). Output: `emma-endinv-1.2.0.jar` |
| `emma-recipe-extractor/` | One-shot recipe/drop/item data extraction (`/emma_extract`). Output: `emma-recipe-extractor-0.1.0.jar` |

Twitch integration now lives in a separate repository: `https://github.com/DragonbornElric/emmaminecraft261twitch`

### Build & Deploy

```bash
cd java && ./build_and_deploy.sh                  # all mods + deploy
cd java && ./build_and_deploy.sh --bridge          # bridge only
cd java && ./build_and_deploy.sh --endinv          # endless inventory only
cd java && ./build_and_deploy.sh --recipe-extractor # recipe extractor only (on-demand, server)
```

Manual: `cd java/emma-pathfinder && ./gradlew.bat build`

### Deployment Targets

- Emma: `%APPDATA%\PrismLauncher\instances\Emma\.minecraft\mods\` — bridge + endinv
- CameraBot: `%APPDATA%\PrismLauncher\instances\CameraBot\.minecraft\mods\` — bridge only
- Elric: `%APPDATA%\PrismLauncher\instances\Elric\.minecraft\mods\` — endinv only

## Database

- **File:** `minecraft.db` (SQLite, project root by default)
- **Tables:** `build_guides`, `build_guide_blocks`, `block_substitutions`, `block_resources`, `world_state`, `world_state_rtree`, `build_goals`, `build_progress`, `poi_registry`
- **Migration:** `python tools/migrate_db.py` (one-time, from `emma_sessions.db`)

## Config Keys

The config dict passed to `gamer.configure()` should contain:

```json
{
    "player_port": 8765,
    "camera_port": 8766,
    "auto_idle_delay_seconds": 5.0,
    "db_path": "path/to/minecraft.db",
    "panic_teleport": {
        "enabled": true,
        "threshold": 4.0,
        "safe_x": 0, "safe_y": 0, "safe_z": 0,
        "cooldown_ms": 60000
    },
    "build": {
        "staging_radius": 24,
        "gather_timeout_seconds": 300,
        "phase_max_blocks": 256,
        "keep_food_count": 16,
        "keep_tools_on_deposit": true,
        "keep_armor_on_deposit": true
    }
}
```

## GOAP System

- **Architecture:** `GoapTicker` runs in `END_CLIENT_TICK`. Each tick: update WorldState → run reflexes → score actions → execute winner.
- **Reflexes:** ShieldBlock, ForceField, MLGBucket, ToolEquip, PreEquipWeapon, AutoRespawn.
- **Scored Actions:** AttackEntity, FleeFrom, EatFood, MineBlock, CraftItem, SmeltItem, NavigateTo, PlaceTorch, StoreItems, EquipBestArmor, DeathRecovery, EnvironmentalHazard, CollectFood, ProjectileDodge, Unstuck.
- **Zero external AI dependencies** — all actions use MinecraftClient + Emmatone APIs directly.

## Code Review Rules

- **Treat code comments as potentially wrong.** Trace actual data flow to verify claims made in comments. Comments like "// resolve tag alternatives" may describe intent that the code doesn't actually implement correctly. Always read what the code *does*, not what the comment *says* it does.

## Key Conventions

- **Unobfuscated source** — MC 26.1 ships with real names; Yarn discontinued; all mods use `loom.officialMojangMappings()` (identity on 26.1)
- **MC 26.1**, Fabric Loader 0.18.4, Fabric Loom 1.15, Java 25
- Emmatone is bundled inside `emma-bridge-mod-0.2.0.jar` — no separate JAR
- WebSocket protocol: `{id, method, params}` → `{id, result/error}`; unsolicited `{method, params}` events
- **Never use `MinecraftClient.getInstance().execute()` for Emmatone calls** — deadlocks. Use tick queue pattern.

## Testing

```bash
python -m pytest tests/
```
