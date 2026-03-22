"""Test 3: Idle mode defense — does Emma fight back without hero mode?"""
from gamer.emmatone_client import EmmatoneClient
import time, json

bc = EmmatoneClient()
bc.connect()
time.sleep(3)

# Get status
s = bc.get_status()
pos = s["data"]["position"]
print(f"Emma at: {pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f}")
print(f"Health: {s['data']['health']}")

# Start idle mode (keeps task runner alive for defense chains)
print("\n=== Starting idle mode ===")
r = bc.emmaclef_task("idle")
print("Idle result:", json.dumps(r, indent=2))
time.sleep(2)

# Check status
print("\n=== EmmaClef Status (idle) ===")
r = bc.emmaclef_status()
print(json.dumps(r, indent=2))

# Give her a sword if she doesn't have one
bc.send_command("give Emma minecraft:iron_sword 1")
time.sleep(0.5)

# Spawn zombies right on top of her
print("\n=== Spawning zombies ===")
bc.send_command("summon minecraft:zombie ~ ~ ~2")
time.sleep(0.3)
bc.send_command("summon minecraft:zombie ~-2 ~ ~")
time.sleep(0.3)

# Watch for 12 seconds
print("\n=== Monitoring defense for 12 seconds ===")
for i in range(12):
    time.sleep(1)
    st = bc.get_status()
    d = st.get("data", {})
    hp = d.get("health", "?")
    armor = d.get("armor", 0)
    p = d.get("position", {})
    x, y, z = p.get("x", 0), p.get("y", 0), p.get("z", 0)
    pitch = p.get("pitch", 0)
    
    ac = bc.emmaclef_status()
    acd = ac.get("data", {})
    chains = acd.get("chains_active", [])
    task_str = acd.get("task", "none")
    chain_name = acd.get("chain", "none")
    
    print(f"  t={i+1:2d}s: HP={hp} chain={chain_name} chains={chains} pitch={pitch:.1f} pos=({x:.1f}, {y:.1f}, {z:.1f})")
    if task_str != "none":
        print(f"         task: {task_str}")

# Stop
print("\n=== Stopping ===")
bc.emmaclef_stop()
bc.disconnect()
