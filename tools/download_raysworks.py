#!/usr/bin/env python3
"""
Download raysworks schematics from the Minecraft server via SCP,
then optionally import them into the build database.

Usage:
    python tools/download_raysworks.py                # download only
    python tools/download_raysworks.py --import        # download + import to DB
    python tools/download_raysworks.py --import-only   # skip download, just import existing files
"""

import os
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEST_DIR = PROJECT_ROOT / "java" / "schematics" / "raysworks"

SERVER_HOST = "192.168.0.225"
SERVER_USER = "emmaserver"
REMOTE_PATH = "/home/emmaserver/MinecraftWorld/data/raysworks/schematics/world_extract/"

SUPPORTED_EXTENSIONS = {".nbt", ".litematic", ".schem", ".schematic"}


def download():
    """Download schematics from server using SCP."""
    DEST_DIR.mkdir(parents=True, exist_ok=True)

    # scp -r copies the entire directory contents
    src = f"{SERVER_USER}@{SERVER_HOST}:{REMOTE_PATH}*"
    print(f"Downloading schematics from {SERVER_USER}@{SERVER_HOST}:{REMOTE_PATH}")
    print(f"Destination: {DEST_DIR}")
    print(f"You will be prompted for the password.\n")

    result = subprocess.run(
        ["scp", "-r", "-o", "StrictHostKeyChecking=no",
         f"{SERVER_USER}@{SERVER_HOST}:{REMOTE_PATH}.",
         str(DEST_DIR)],
    )

    if result.returncode == 0:
        count = sum(1 for f in DEST_DIR.iterdir()
                    if f.suffix.lower() in SUPPORTED_EXTENSIONS)
        print(f"\nDownloaded files to {DEST_DIR}")
        print(f"Supported schematic files: {count}")
    else:
        print(f"\nSCP exited with code {result.returncode}")
        sys.exit(1)


def import_to_db():
    """Import downloaded schematics into build database."""
    sys.path.insert(0, str(PROJECT_ROOT))
    from gamer.build_db import BuildDB
    from gamer.schematic_parser import batch_import

    if not DEST_DIR.exists():
        print(f"Directory not found: {DEST_DIR}")
        print("Run without --import-only first to download the files.")
        sys.exit(1)

    db = BuildDB()
    print(f"Importing schematics from {DEST_DIR} ...")
    result = batch_import(str(DEST_DIR), db)
    print(f"Imported: {result['imported']}, Skipped: {result['skipped']}, "
          f"Errors: {len(result['errors'])}")
    for err in result["errors"]:
        print(f"  ERROR: {err['file']}: {err['error']}")


if __name__ == "__main__":
    if "--import-only" in sys.argv:
        import_to_db()
    else:
        download()
        if "--import" in sys.argv:
            import_to_db()
