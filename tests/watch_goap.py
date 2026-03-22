"""
Live GOAP planner watcher — polls the agent_debug WebSocket command every second
and displays the full action scoring auction in the terminal.

Color-coded scores: green=winner, yellow=close, red=low, gray=non-viable.

Usage: python test/watch_goap.py
Press Ctrl+C to stop.
"""
import websocket
import json
import uuid
import time
import os
import sys

WS_URL = "ws://127.0.0.1:8765"
POLL_INTERVAL = 1.0  # seconds

# ANSI color codes
GREEN = "\033[92m"
YELLOW = "\033[93m"
RED = "\033[91m"
GRAY = "\033[90m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"


def query_agent_debug(ws, include_world_state=False):
    cmd_id = "goap_" + uuid.uuid4().hex[:6]
    ws.send(json.dumps({
        "id": cmd_id,
        "type": "command",
        "command": "agent_debug",
        "params": {
            "include_history": True,
            "include_world_state": include_world_state,
            "include_combat_log": 5,
        }
    }))
    # Read until we get our response (skip events)
    for _ in range(20):
        msg = json.loads(ws.recv())
        if msg.get("id") == cmd_id:
            return msg.get("data", {})
    return None


def score_color(score, best_score):
    """Color based on how close to the winning score."""
    if score <= 0:
        return GRAY
    if best_score > 0 and score >= best_score:
        return GREEN
    ratio = score / best_score if best_score > 0 else 0
    if ratio > 0.7:
        return YELLOW
    return RED


def display_goap(data):
    os.system("cls" if os.name == "nt" else "clear")

    if not data.get("goap_enabled"):
        print(f"{BOLD}GOAP PLANNER{RESET}  |  {RED}DISABLED{RESET}")
        print(f"Enable with: goap_enabled: true in emma_bridge.json")
        print(f"\n[{time.strftime('%H:%M:%S')}] Polling every {POLL_INTERVAL}s  |  Ctrl+C to stop")
        return

    tick = data.get("tick", 0)
    active = data.get("active_action", "none")
    hysteresis = data.get("hysteresis_bonus", 1.5)

    print(f"{BOLD}{'=' * 74}{RESET}")
    print(f"  {BOLD}GOAP PLANNER{RESET}  |  Tick: {tick}  |  Active: {GREEN}{BOLD}{active}{RESET}  |  Hysteresis: +{hysteresis}")
    print(f"{BOLD}{'=' * 74}{RESET}")

    # ── Action Auction ──
    auction = data.get("auction", [])
    best_score = auction[0]["score"] if auction else 0

    print(f"\n  {BOLD}ACTION AUCTION{RESET} ({len(auction)} actions)")
    print(f"  {'Action':<24} {'Score':>8} {'Raw':>8} {'Status':<10} Details")
    print(f"  {'-' * 68}")

    for a in auction:
        name = a.get("action", "?")
        score = a.get("score", 0)
        raw = a.get("raw_score", 0)
        is_active = a.get("is_active", False)
        color = score_color(score, best_score)

        status = ""
        if is_active:
            status = f"{GREEN}ACTIVE{RESET}"
        elif score <= 0:
            status = f"{GRAY}n/a{RESET}"
        else:
            status = f"{color}viable{RESET}"

        # Extract key breakdown info
        details = ""
        bd = a.get("breakdown")
        if bd:
            parts = []
            if "collateral" in bd and bd["collateral"] > 0:
                parts.append(f"coll={bd['collateral']:.1f}")
            if "personality_mult" in bd and bd["personality_mult"] != 1.0:
                parts.append(f"pers={bd['personality_mult']:.1f}")
            if "success_rate" in bd and bd["success_rate"] < 1.0:
                parts.append(f"succ={bd['success_rate']:.2f}")
            if "primary_goal" in bd:
                parts.append(f"goal={bd['primary_goal']}")
            details = "  ".join(parts)

        print(f"  {color}{name:<24} {score:>8.2f} {raw:>8.2f}{RESET} {status:<10} {details}")

    # ── Goals ──
    goals = data.get("goals", [])
    if goals:
        user = [g for g in goals if not g.get("is_derived")]
        derived = [g for g in goals if g.get("is_derived")]
        print(f"\n  {BOLD}GOALS{RESET} ({len(user)} user + {len(derived)} derived)")
        print(f"  {'Goal':<24} {'Type':<18} {'Pri':>5} {'Score':>7}  {'Parent'}")
        print(f"  {'-' * 78}")
        for g in goals:
            gid = g.get("id", "?")
            gtype = g.get("type", "?")
            pri = g.get("priority", 0)
            gscore = g.get("score", 0)
            parent = g.get("parent_goal", "")
            is_derived = g.get("is_derived", False)
            color = GREEN if gscore > pri * 0.5 else YELLOW if gscore > 0 else GRAY
            prefix = "  " if is_derived else ""
            marker = f"{GRAY}└ " if is_derived else "  "
            print(f"{marker}{color}{prefix}{gid:<22} {gtype:<18} {pri:>5.1f} {gscore:>7.2f}{RESET}  {GRAY}{parent}{RESET}")

    # ── Personality ──
    personality = data.get("personality", {})
    if personality:
        parts = [f"{k}={v:.1f}" for k, v in personality.items()]
        print(f"\n  {BOLD}PERSONALITY{RESET}  {CYAN}{'  '.join(parts)}{RESET}")

    # ── World State Diff ──
    diff = data.get("world_state_diff", {})
    if diff:
        parts = [f"{k}: {v}" for k, v in diff.items()]
        print(f"\n  {BOLD}STATE CHANGES{RESET}  {YELLOW}{'  |  '.join(parts)}{RESET}")

    # ── Combat Log (last few) ──
    combat = data.get("combat_log", [])
    if combat:
        print(f"\n  {BOLD}COMBAT LOG{RESET} (last {len(combat)})")
        for entry in combat[-5:]:
            etype = entry.get("type", "?")
            ts = entry.get("timestamp_ms", 0)
            edata = entry.get("data", {})
            # Compact summary
            summary = ""
            if etype == "damage_taken":
                summary = f"dmg={edata.get('amount', '?')} src={edata.get('source_type', '?')}"
            elif etype == "damage_dealt":
                summary = f"hit {edata.get('target_type', '?')} hp={edata.get('target_health', '?')}"
            elif etype == "defense_decision":
                summary = f"action={edata.get('action', '?')} danger={edata.get('dangerousness', '?'):.1f}" if isinstance(edata.get('dangerousness'), (int, float)) else f"action={edata.get('action', '?')}"
            elif etype == "heal":
                summary = f"hp={edata.get('new_health', '?')}"
            elif etype == "rotation_snap":
                summary = f"dyaw={edata.get('delta_yaw', '?'):.0f} dpitch={edata.get('delta_pitch', '?'):.0f}" if isinstance(edata.get('delta_yaw'), (int, float)) else str(edata)
            else:
                summary = str(edata)[:60]

            color = RED if etype == "damage_taken" else YELLOW if etype == "defense_decision" else GRAY
            print(f"    {color}{etype:<20} {summary}{RESET}")

    # ── Switch History ──
    history = data.get("switch_history", [])
    if history:
        print(f"\n  {BOLD}SWITCH HISTORY{RESET} (last {len(history)})")
        for h in history[-5:]:
            fr = h.get("from", "?")
            to = h.get("to", "?")
            fs = h.get("from_score", 0)
            ts_score = h.get("to_score", 0)
            reason = h.get("reason", "?")
            print(f"    {CYAN}{fr}{RESET} -> {GREEN}{to}{RESET}  ({fs:.1f} -> {ts_score:.1f})  [{reason}]")

    # ── Strategy Knowledge ──
    knowledge = data.get("strategy_knowledge", {})
    rates = knowledge.get("success_rates", {})
    if rates:
        parts = [f"{k}={v:.2f}" for k, v in rates.items()]
        print(f"\n  {BOLD}SUCCESS RATES{RESET}  {', '.join(parts)}")

    print(f"\n  [{time.strftime('%H:%M:%S')}] Polling every {POLL_INTERVAL}s  |  Ctrl+C to stop")


def main():
    # Enable ANSI on Windows
    if os.name == "nt":
        os.system("")

    print("Connecting to bridge WebSocket...")
    ws = websocket.create_connection(WS_URL, timeout=5)
    ws.recv()  # discard hello
    print("Connected. Watching GOAP planner...\n")

    # Check for --world-state flag
    include_ws = "--world-state" in sys.argv

    try:
        while True:
            data = query_agent_debug(ws, include_world_state=include_ws)
            if data:
                display_goap(data)
            time.sleep(POLL_INTERVAL)
    except KeyboardInterrupt:
        print("\nStopped.")
    except (ConnectionAbortedError, ConnectionResetError, BrokenPipeError, websocket.WebSocketConnectionClosedException):
        print("\nConnection lost (Minecraft closed?).")
    finally:
        ws.close()


if __name__ == "__main__":
    main()
