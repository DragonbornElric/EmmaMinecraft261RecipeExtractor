"""Test: Build Wooden House 2 shelter using Emmatone's BuilderProcess.

Emma already has materials in inventory from previous run.
Just start the build at her current position.
"""
from gamer.emmatone_client import EmmatoneClient
from gamer.build_db import BuildDB
import time, json

GIVE_MATERIALS = False  # Set True if Emma needs materials

bc = EmmatoneClient()
bc.connect()
time.sleep(3)

bdb = BuildDB()

# Get current position for build origin
s = bc.get_status()
pos = s["data"]["position"]
ox = int(pos["x"]) + 5  # offset a few blocks so she's not standing in it
oy = int(pos["y"])
oz = int(pos["z"]) + 5
print(f"Emma at: {pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f}")
print(f"Build origin: ({ox}, {oy}, {oz})")

if GIVE_MATERIALS:
    print("\n=== Giving materials ===")
    bom = bdb.get_guide_bill_of_materials(38)
    for block_type, qty in bom.items():
        if block_type == "minecraft:64":
            continue
        item_name = block_type
        remaining = qty + 10
        while remaining > 0:
            give_qty = min(remaining, 64)
            cmd = f"give Emma {item_name} {give_qty}"
            bc.send_command(cmd)
            remaining -= give_qty
            time.sleep(0.15)
        print(f"  Gave {qty}+ {item_name}")
    time.sleep(1)

# Check inventory briefly
print("\n=== Inventory check ===")
inv = bc.get_inventory()
slots = inv.get("data", {}).get("slots", [])
filled = [s for s in slots if s.get("item") and s["item"] != "minecraft:air"]
print(f"  {len(filled)} slots filled")

# Start the build
print(f"\n=== Starting build: Wooden House 2 at ({ox}, {oy}, {oz}) ===")
guide_id = 38
result = bc.build(guide_id, ox, oy, oz)
print(f"Build result: {json.dumps(result, indent=2)}")

# Monitor for 60 seconds
print("\n=== Monitoring build for 60 seconds ===")
for i in range(60):
    time.sleep(1)
    st = bc.get_status()
    d = st.get("data", {})
    hp = d.get("health", "?")
    task = d.get("active_task", {})
    p = d.get("position", {})
    x, y, z = p.get("x", 0), p.get("y", 0), p.get("z", 0)
    emmatone = d.get("emmatone", {})
    pathing = emmatone.get("is_pathing", False)
    
    if i % 5 == 0:  # print every 5 seconds
        print(f"  t={i+1:3d}s: HP={hp} pathing={pathing} task={task} pos=({x:.1f}, {y:.1f}, {z:.1f})")

print("\n=== Final status ===")
s = bc.get_status()
print(json.dumps(s.get("data", {}), indent=2))

bc.disconnect()
