"""
Session Viewer — CLI timeline viewer for sessions emitted by the external
emma-gameplay-logger mod.

Displays a visual timeline of gameplay phases, event counts, and key stats.

Usage:
    python -m gamer.session_viewer <session_dir>
    python -m gamer.session_viewer <session_dir> --events       # Show event details
    python -m gamer.session_viewer <session_dir> --tick 1000    # Jump to tick
"""

import json
import sys
from collections import defaultdict
from pathlib import Path

from gamer.gameplay_analyzer import (
    load_session, enrich_events, detect_phases, extract_signals
)


# Phase label colors (ANSI)
PHASE_COLORS = {
    "building":    "\033[93m",   # yellow
    "mining":      "\033[36m",   # cyan
    "combat":      "\033[91m",   # red
    "survival":    "\033[92m",   # green
    "container":   "\033[95m",   # magenta
    "crafting":    "\033[94m",   # blue
    "exploration": "\033[97m",   # white
    "idle":        "\033[90m",   # gray
    "mixed":       "\033[37m",   # light gray
}
RESET = "\033[0m"
BOLD = "\033[1m"


def format_time(ticks: int) -> str:
    """Format ticks as mm:ss."""
    seconds = ticks // 20
    return f"{seconds // 60:02d}:{seconds % 60:02d}"


def print_header(session):
    """Print session header info."""
    meta = session.metadata
    player = meta.get("player", "unknown")
    duration_ticks = 0
    if session.states:
        duration_ticks = session.states[-1].tick - session.states[0].tick

    print(f"\n{BOLD}{'=' * 70}")
    print(f"  Session: {player}")
    print(f"  Duration: {format_time(duration_ticks)} ({duration_ticks} ticks)")
    print(f"  States: {len(session.states):,} snapshots | Events: {len(session.events):,}")
    if session.goap_events:
        print(f"  GOAP Events: {len(session.goap_events):,}")
    print(f"{'=' * 70}{RESET}\n")


def print_event_summary(session):
    """Print event type counts."""
    counts = defaultdict(int)
    for e in session.events:
        counts[e.event_type] += 1

    print(f"{BOLD}Event Summary:{RESET}")
    for event_type, count in sorted(counts.items(), key=lambda x: -x[1]):
        bar = "#" * min(count, 50)
        print(f"  {event_type:<20s} {count:>5d}  {bar}")
    print()


def print_timeline(session):
    """Print visual phase timeline."""
    if not session.phases:
        print("  No phases detected.")
        return

    start_tick = session.phases[0].start_tick
    end_tick = session.phases[-1].end_tick
    total_ticks = end_tick - start_tick or 1

    print(f"{BOLD}Phase Timeline:{RESET}")
    print(f"  {'Time':<8s} {'Phase':<14s} {'Duration':<10s} {'Events':<8s} Bar")
    print(f"  {'─' * 60}")

    for phase in session.phases:
        color = PHASE_COLORS.get(phase.label, "")
        duration = phase.end_tick - phase.start_tick
        width = max(1, int(40 * duration / total_ticks))
        bar = "█" * width

        time_str = format_time(phase.start_tick - start_tick)
        dur_str = format_time(duration)

        print(f"  {time_str:<8s} {color}{phase.label:<14s}{RESET} "
              f"{dur_str:<10s} {phase.event_count:<8d} {color}{bar}{RESET}")

    print()


def print_phase_distribution(session):
    """Print phase time distribution."""
    phase_times = defaultdict(int)
    for p in session.phases:
        phase_times[p.label] += p.end_tick - p.start_tick

    total = sum(phase_times.values()) or 1

    print(f"{BOLD}Phase Distribution:{RESET}")
    for label, ticks in sorted(phase_times.items(), key=lambda x: -x[1]):
        pct = 100 * ticks / total
        color = PHASE_COLORS.get(label, "")
        bar = "█" * int(pct / 2)
        print(f"  {color}{label:<14s}{RESET} {pct:5.1f}%  {color}{bar}{RESET}")
    print()


def print_signals(session):
    """Print decision-inference signal summary."""
    signals = session.signals
    if not signals:
        return

    print(f"{BOLD}Decision Signals:{RESET}")

    interruptions = signals.get("action_interruptions", [])
    print(f"  Action interruptions: {len(interruptions)}")
    for intr in interruptions[:5]:  # Show first 5
        print(f"    {format_time(intr['tick'])}: {intr['from']} → "
              f"{intr['interrupting']} → resumed ({format_time(intr['duration_ticks'])} dur)")

    dwells = signals.get("dwell_times", [])
    print(f"  Planning pauses: {len(dwells)}")

    mistakes = signals.get("mistake_corrections", [])
    print(f"  Mistake corrections: {len(mistakes)}")

    eat_timing = signals.get("eat_timing", [])
    if eat_timing:
        hunger_vals = [t["hunger_before"] for t in eat_timing if t.get("hunger_before") is not None]
        if hunger_vals:
            avg = sum(hunger_vals) / len(hunger_vals)
            print(f"  Eat timing: {len(eat_timing)} meals, avg hunger at eat = {avg:.1f}")

    threat = signals.get("threat_responses", [])
    if threat:
        dists = [t["dist"] for t in threat if t.get("dist") is not None]
        if dists:
            avg = sum(dists) / len(dists)
            print(f"  Threat responses: {len(threat)}, avg engage dist = {avg:.1f}")

    pickups = signals.get("opportunity_pickups", [])
    if pickups:
        print(f"  Opportunity pickups: {len(pickups)}")

    print()


def print_annotations(session):
    """Print annotations if present."""
    annotations = [e for e in session.events if e.event_type == "annotation"]
    if not annotations:
        return

    start_tick = session.states[0].tick if session.states else 0

    print(f"{BOLD}Annotations:{RESET}")
    for ann in annotations:
        time_str = format_time(ann.tick - start_tick)
        text = ann.raw.get("text", "")
        print(f"  {time_str}  {text}")
    print()


def print_events_detail(session, around_tick: int = None):
    """Print detailed event list, optionally filtered around a tick."""
    start_tick = session.states[0].tick if session.states else 0
    events = session.events

    if around_tick is not None:
        window = 200  # 10 seconds
        events = [e for e in events if abs(e.tick - around_tick) <= window]
        print(f"{BOLD}Events around tick {around_tick} "
              f"({format_time(around_tick - start_tick)}):{RESET}")
    else:
        print(f"{BOLD}All Events ({len(events)}):{RESET}")

    for e in events[:200]:  # Cap at 200
        time_str = format_time(e.tick - start_tick)

        # Format key details based on event type
        details = ""
        if e.event_type == "block_placed":
            details = f"{e.raw.get('block', '?')} at ({e.raw.get('x')},{e.raw.get('y')},{e.raw.get('z')})"
        elif e.event_type == "block_broken":
            details = f"{e.raw.get('block', '?')} at ({e.raw.get('x')},{e.raw.get('y')},{e.raw.get('z')})"
        elif e.event_type == "attack":
            details = f"{e.raw.get('target', '?')} with {e.raw.get('weapon', '?')} dist={e.raw.get('dist', '?')}"
        elif e.event_type == "damage_taken":
            details = f"{e.raw.get('amount', '?')} from {e.raw.get('source', '?')}"
        elif e.event_type == "hotbar_select":
            details = f"{e.raw.get('from', '?')} → {e.raw.get('to', '?')}"
        elif e.event_type == "eat_food":
            details = f"{e.raw.get('item', '?')}"
        elif e.event_type == "annotation":
            details = e.raw.get("text", "")
        elif e.event_type == "death":
            details = e.raw.get("message", "")
        else:
            # Generic: show raw keys
            keys = [k for k in e.raw if k not in ("tick", "type")]
            details = ", ".join(f"{k}={e.raw[k]}" for k in keys[:4])

        print(f"  {time_str}  {e.event_type:<20s} {details}")

    if len(events) > 200:
        print(f"  ... ({len(events) - 200} more events)")
    print()


def main():
    if len(sys.argv) < 2:
        print("Usage: python -m gamer.session_viewer <session_dir> [--events] [--tick N]")
        sys.exit(1)

    session_dir = sys.argv[1]
    show_events = "--events" in sys.argv
    around_tick = None

    for i, arg in enumerate(sys.argv):
        if arg == "--tick" and i + 1 < len(sys.argv):
            around_tick = int(sys.argv[i + 1])

    # Load and analyze
    session = load_session(session_dir)
    enrich_events(session)
    detect_phases(session)
    extract_signals(session)

    # Display
    print_header(session)
    print_event_summary(session)
    print_timeline(session)
    print_phase_distribution(session)
    print_signals(session)
    print_annotations(session)

    if show_events or around_tick is not None:
        print_events_detail(session, around_tick)


if __name__ == "__main__":
    main()
