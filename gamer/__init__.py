"""
emma-minecraft — Standalone Minecraft GOAP subsystem.

Usage by a consumer (e.g. Emma):

    import gamer
    gamer.configure({"player_port": 8765, "camera_port": 8766, ...})
    gamer.init_db()

    from gamer.emmatone_client import connect
    client = connect()
"""

from gamer.config import configure, get as get_config
from gamer.db import get_db_path, set_db_path, init_db
from gamer.gamer_mode import GamerMode
from gamer.hero_mode import HeroMode

__version__ = "0.1.0"
__all__ = [
    "configure", "get_config", "get_db_path", "set_db_path", "init_db",
    "GamerMode", "HeroMode",
]
