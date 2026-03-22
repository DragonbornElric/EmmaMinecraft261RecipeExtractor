"""Test 2: Start EmmaClef hero mode, then spawn mobs to test combat."""
from gamer.emmatone_client import EmmatoneClient
import time, json

bc = EmmatoneClient()
bc.connect()
time.sleep(3)

# First, get her back to a good spot and give her a weapon
print("=== Getting Emma ready ===")
s = bc.get_status()
pos = s["data"]["position"]
print(f"Emma at: {pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f}")

# Give her a sword and some armor
bc.send_command("give Emma minecraft:iron_sword 1")
time.sleep(0.3)
bc.send_command("give Emma minecraft:iron_chestplate 1")
time.sleep(0.3)
bc.send_command("give Emma minecraft:iron_leggings 1")
time.sleep(0.3)
bc.send_command("give Emma minecraft:iron_boots 1")
time.sleep(0.3)
bc.send_command("give Emma minecraft:iron_helmet 1")
time.sleep(0.3)

# Check inventory
print("\n=== Inventory check ===")
inv = bc._send_command("inventory", {}, timeout=5.0)
slots = inv.get("data", {}).get("slots", [])
for s in slots:
    if s.get("item") and s["item"] != "minecraft:air":
        print(f"  Slot {s.get('slot', '?')}: {s['item']} x{s.get('count', 1)}")

# Start EmmaClef hero mode (survival tasks)
print("\n=== Starting EmmaClef hero mode ===")
r = bc.emmaclef_task("hero")
print("Hero result:", json.dumps(r, indent=2))
time.sleep(3)

# Check status - chains should now be active
print("\n=== EmmaClef Status (hero running) ===")
r = bc.emmaclef_status()
print(json.dumps(r, indent=2))

# Now spawn some zombies
print("\n=== Spawning zombies ===")
bc.send_command("summon minecraft:zombie ~ ~ ~4")
time.sleep(0.3)
bc.send_command("summon minecraft:zombie ~3 ~ ~2")
time.sleep(0.3)

# Monitor
print("\n=== Monitoring combat for 15 seconds ===")
for i in range(15):
    time.sleep(1)
    st = bc.get_status()
    d = st.get("data", {})
    hp = d.get("health", "?")
    hunger = d.get("hunger", "?")
    armor = d.get("armor", "?")
    task = d.get("active_task", {})
    p = d.get("position", {})
    x, y, z = p.get("x", 0), p.get("y", 0), p.get("z", 0)
    pitch = p.get("pitch", 0)
    
    ac = bc.emmaclef_status()
    chains = ac.get("data", {}).get("chains_active", [])
    
    print(f"  t={i+1:2d}s: HP={hp} armor={armor} pitch={pitch:.1f} chains={chains} pos=({x:.1f}, {y:.1f}, {z:.1f})")

# Stop hero mode
print("\n=== Stopping hero mode ===")
bc.emmaclef_stop()
time.sleep(1)

# Final status
print("\n=== Final EmmaClef Status ===")
r = bc.emmaclef_status()
print(json.dumps(r, indent=2))

bc.disconnect()
