"""Quick test: spawn mobs near Emma, watch for combat response."""
from gamer.emmatone_client import EmmatoneClient
import time, json

bc = EmmatoneClient()
bc.connect()
time.sleep(3)

print("=== EmmaClef Status ===")
r = bc.emmaclef_status()
print(json.dumps(r, indent=2))

s = bc.get_status()
pos = s["data"]["position"]
print(f"\nEmma at: {pos['x']:.1f}, {pos['y']:.1f}, {pos['z']:.1f}")
print(f"Health: {s['data']['health']}, Hunger: {s['data']['hunger']}")

# Spawn 2 zombies near her
print("\n=== Spawning zombies ===")
r1 = bc.send_command("summon minecraft:zombie ~ ~ ~3")
print("Zombie 1:", r1.get("data", {}).get("sent"))
time.sleep(0.5)
r2 = bc.send_command("summon minecraft:zombie ~2 ~ ~")
print("Zombie 2:", r2.get("data", {}).get("sent"))

# Monitor for 10 seconds
print("\n=== Monitoring ===")
for i in range(10):
    time.sleep(1)
    st = bc.get_status()
    d = st.get("data", {})
    hp = d.get("health", "?")
    task = d.get("active_task", {})
    p = d.get("position", {})
    x = p.get("x", 0)
    y = p.get("y", 0)
    z = p.get("z", 0)
    pitch = p.get("pitch", 0)
    print(f"  t={i+1:2d}s: HP={hp}, pitch={pitch:.1f}, task={task}, pos=({x:.1f}, {y:.1f}, {z:.1f})")

# Check EmmaClef status after combat
print("\n=== Post-combat EmmaClef Status ===")
r = bc.emmaclef_status()
print(json.dumps(r, indent=2))

bc.disconnect()
