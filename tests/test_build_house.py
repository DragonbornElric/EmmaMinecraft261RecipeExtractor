"""Test: Build Cabin (guide_id=11) — give all missing materials and build.

Queries the DB for what guide 11 needs, checks Emma's inventory via WebSocket,
gives her every deficit item, then starts the build.
"""
from gamer.emmatone_client import EmmatoneClient
from gamer.build_db import BuildDB
import time, json, sqlite3

bc = EmmatoneClient()
bc.connect()
time.sleep(3)

bdb = BuildDB()

GUIDE_ID = 11  # Cabin (litematic, 946 blocks after dirt removal, 18x15x11)
BUILD_ORIGIN = (392, 118, 71)

# ── Get current position ──
s = bc.get_status()
pos = s["data"]["position"]
print(f"Emma at: {pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f}")

# ── Figure out what she needs ──
# Block type → item name mapping for blocks whose item form differs
BLOCK_TO_ITEM = {
    "spruce_wall_sign": "spruce_sign",
    # snow layers block → item is just "snow" which is correct already
}

conn = sqlite3.connect("emma_sessions.db")
rows = conn.execute("""
    SELECT block_type, COUNT(*) as cnt
    FROM build_guide_blocks
    WHERE guide_id = ?
    GROUP BY block_type
    ORDER BY cnt DESC
""", (GUIDE_ID,)).fetchall()
conn.close()

# Aggregate needs by item name (merge wall_sign into sign, etc.)
needs = {}
for bt, cnt in rows:
    name = bt.replace("minecraft:", "")
    if name == "dirt":
        continue
    item_name = BLOCK_TO_ITEM.get(name, name)
    needs[item_name] = needs.get(item_name, 0) + cnt

# ── Check current inventory via bridge ──
print("\n=== Checking inventory ===")
inv_slots = bc.inventory  # property, returns list[dict]
have = {}
for slot in inv_slots:
    if slot and isinstance(slot, dict) and slot.get("item"):
        name = slot["item"].replace("minecraft:", "")
        have[name] = have.get(name, 0) + slot.get("count", 1)

# ── Calculate deficits and give ──
print("\n=== Giving missing materials ===")
given_count = 0
for item_name, needed in sorted(needs.items(), key=lambda x: -x[1]):
    on_hand = have.get(item_name, 0)
    deficit = needed - on_hand
    if deficit <= 0:
        print(f"  {item_name}: need {needed}, have {on_hand} ✓")
        continue
    # Give in chunks of 64 (MC stacks)
    remaining = deficit
    while remaining > 0:
        batch = min(remaining, 64)
        cmd = f"give @p {item_name} {batch}"
        bc.send_command(cmd)
        time.sleep(0.15)
        remaining -= batch
    given_count += 1
    print(f"  {item_name}: need {needed}, have {on_hand} → gave {deficit}")

# Always ensure she has tools, armor, food, scaffolding
print("\n=== Giving tools, armor, food ===")
essentials = [
    "give @p diamond_pickaxe 1",
    "give @p diamond_axe 1",
    "give @p diamond_shovel 1",
    "give @p diamond_sword 1",
    "give @p shield 1",
    "give @p diamond_helmet 1",
    "give @p diamond_chestplate 1",
    "give @p diamond_leggings 1",
    "give @p diamond_boots 1",
    "give @p cooked_beef 64",
    "give @p dirt 64",
]
for cmd in essentials:
    bc.send_command(cmd)
    time.sleep(0.15)
    print(f"  /{cmd}")

time.sleep(1)

# Equip armor
print("  Equipping diamond armor...")
bc.emmaclef_task("equip", "diamond")
time.sleep(3)

# ── Teleport her to the build site (in case she respawned far away) ──
ox, oy, oz = BUILD_ORIGIN
#tp_cmd = f"tp @p {ox} {oy + 2} {oz}"
#bc.send_command(tp_cmd)
#print(f"\n  Teleported to build site: /{tp_cmd}")
#time.sleep(2)

# ── Start the build ──
print(f"\n=== Starting Cabin build (guide_id={GUIDE_ID}) ===")
guide = bdb.get_guide(GUIDE_ID)
blocks = bdb.get_guide_blocks(GUIDE_ID)
print(f"Guide: {guide['name']}, {len(blocks)} blocks")

bc._cancel_idle_timer()
bc._idle_suppressed = True

result = bc.build(guide_id=GUIDE_ID, origin_x=ox, origin_y=oy, origin_z=oz)
print(f"Build result: {json.dumps(result, indent=2)}")

# ── Monitor for 45 minutes ──
print(f"\n=== Monitoring (2700s max) ===")
prev_task = ""
for i in range(2700):
    time.sleep(1)
    try:
        st = bc.get_status()
        d = st.get("data", {})
        task = d.get("active_task", {})
        p = d.get("position", {})
        bar = d.get("emmatone", {})
        hp = d.get("health", -1)
        hunger = d.get("hunger", -1)

        task_str = f"{task.get('task_type','?')}:{task.get('task_name','?')}"
        if task_str != prev_task or i % 10 == 0:
            print(f"  t={i+1:4d}s: task={task_str:40s} pathing={str(bar.get('is_pathing','')):5s} "
                  f"pos=({p.get('x',0):.0f},{p.get('y',0):.0f},{p.get('z',0):.0f}) hp={hp} hunger={hunger}")
            prev_task = task_str

        if "idle" in task_str and i > 10:
            print(f"  Build finished (idle at t={i+1}s)")
            break
    except Exception as e:
        print(f"  t={i+1:4d}s: Error: {e}")

# Re-enable auto-idle
bc._idle_suppressed = False

print("\n=== Done ===")
bc.disconnect()
