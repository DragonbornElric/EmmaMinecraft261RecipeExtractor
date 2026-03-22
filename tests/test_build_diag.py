"""Diagnostic: Test building Wooden House 2 with exact materials.

This test:
1. Clears inventory first to avoid overflow
2. Gives Emma survival gear + exact materials for Wooden House 2
3. Suppresses auto-idle to prevent race condition
4. Builds at an offset from her position
5. Monitors task status, pathing, and builder state

Run AFTER restarting Minecraft with the updated mod.

Wooden House 2 (guide_id=38) material list:
  57x oak_log, 46x oak_stairs, 42x oak_planks, 30x glass_pane,
  28x oak_fence, 18x smooth_stone_slab, 15x torch, 8x glass,
  5x chest, 4x ladder, 2x glowstone, 1x stone_button, 1x crafting_table
  (2x minecraft:64 are numeric IDs — skipped by adapter)
Total: 257 usable blocks, 13 material types, 14 inventory slots
"""
from gamer.emmatone_client import EmmatoneClient
import time, json

bc = EmmatoneClient()
bc.connect()
time.sleep(3)

# Get current position
s = bc.get_status()
pos = s["data"]["position"]
print(f"Emma at: {pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f}")

# Clear inventory first, then give exact materials
print("\n=== Clearing inventory & giving exact materials ===")
give_cmds = [
    # Clear everything first
    "clear @p",
    # Survival gear (5 slots: sword, pickaxe, food + 4 armor worn)
    "give @p diamond_sword 1",
    "give @p diamond_shovel 1",
    "give @p diamond_pickaxe 1",
    "give @p diamond_axe 1",
    "give @p diamond_helmet 1",
    "give @p diamond_chestplate 1",
    "give @p diamond_leggings 1",
    "give @p diamond_boots 1",
    # Exact building materials (13 slots)
    "give @p oak_log 57",
    "give @p oak_stairs 46",
    "give @p oak_planks 42",
    "give @p glass_pane 30",
    "give @p oak_fence 28",
    "give @p smooth_stone_slab 18",
    "give @p torch 15",
    "give @p glass 8",
    "give @p chest 5",
    "give @p ladder 4",
    "give @p oak_door 1",
    "give @p glowstone 2",
    "give @p stone_button 1",
    "give @p crafting_table 1",
]
for cmd in give_cmds:
    bc.send_command(cmd)
    time.sleep(0.3)
    print(f"  /{ cmd}")
time.sleep(1)

# Equip diamond armor via EmmaClef task (not a /command)
print("  @equip diamond (EmmaClef task)")
bc.emmaclef_task("equip", "diamond")
time.sleep(3)  # Give time for armor equip

# Build origin: 20 blocks east
ox = int(pos["x"]) + 20
oy = int(pos["y"])
oz = int(pos["z"])
print(f"\nBuild origin: ({ox}, {oy}, {oz})")

# Load Wooden House 2 blocks from BuildDB
import sqlite3
conn = sqlite3.connect("emma_sessions.db")
rows = conn.execute(
    "SELECT block_type, block_state, offset_x, offset_y, offset_z "
    "FROM build_guide_blocks WHERE guide_id = 38 ORDER BY placement_order"
).fetchall()
conn.close()

house_blocks = []
for r in rows:
    block = {"type": r[0], "x": r[2], "y": r[3], "z": r[4]}
    if r[1] and r[1] != "{}":
        block["state"] = r[1]
    house_blocks.append(block)
print(f"Loaded {len(house_blocks)} blocks from BuildDB (Wooden House 2)")

# Suppress auto-idle BEFORE sending build command
bc._cancel_idle_timer()
bc._idle_suppressed = True

print("\n=== Starting Wooden House 2 build ===")
result = bc._send_command("build", {
    "name": "Wooden House 2",
    "origin": {"x": ox, "y": oy, "z": oz},
    "blocks": house_blocks,
}, 10)
print(f"Build result: {json.dumps(result, indent=2)}")

# Monitor for 10 minutes (clearing + building takes longer)
print("\n=== Monitoring 600s ===")
prev_task = ""
for i in range(600):
    time.sleep(1)
    try:
        st = bc.get_status()
        d = st.get("data", {})
        task = d.get("active_task", {})
        p = d.get("position", {})
        bar = d.get("emmatone", {})
        hp = d.get("health", -1)
        
        task_str = f"{task.get('task_type','?')}:{task.get('task_name','?')}"
        if task_str != prev_task or i % 10 == 0:
            print(f"  t={i+1:3d}s: task={task_str:40s} pathing={str(bar.get('is_pathing','')):5s} "
                  f"pos=({p.get('x',0):.1f}, {p.get('y',0):.1f}, {p.get('z',0):.1f}) hp={hp}")
            prev_task = task_str
        # Stop monitoring if build finished (task changed to idle)
        if 'idle' in task_str.lower() and i > 5:
            print(f"  Build finished or stopped at t={i+1}s")
            break
    except Exception as e:
        print(f"  t={i+1:3d}s: Error: {e}")

# Re-enable auto-idle
bc._idle_suppressed = False

print("\n=== Done ===")
bc.disconnect()
