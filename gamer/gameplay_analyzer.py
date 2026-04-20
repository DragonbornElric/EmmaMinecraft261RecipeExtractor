"""
Gameplay Analyzer — Post-processor for session data emitted by the external
emma-gameplay-logger mod.

Reads JSONL session files (state_stream + event_stream), aligns them by tick,
detects gameplay phases automatically, extracts decision-inference signals,
and outputs GOAP calibration parameters.

Usage:
    python -m gamer.gameplay_analyzer <session_dir> [--output report.json]
    python -m gamer.gameplay_analyzer <session_dir> --compare <goap_session_dir>
"""

import json
import math
import os
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


# ── Data classes ──────────────────────────────────────────────────────────

@dataclass
class StateSnapshot:
    tick: int
    player: dict
    equipment: dict
    environment: dict
    raw: dict


@dataclass
class GameEvent:
    tick: int
    event_type: str
    raw: dict
    nearest_state: Optional[StateSnapshot] = None


@dataclass
class Phase:
    start_tick: int
    end_tick: int
    label: str
    event_count: int
    dominant_events: dict


@dataclass
class SessionData:
    metadata: dict
    states: list  # list[StateSnapshot]
    events: list  # list[GameEvent]
    phases: list  # list[Phase]
    signals: dict
    goap_events: list  # list[GameEvent] — GOAP decision events only


# ── Loading ───────────────────────────────────────────────────────────────

def load_session(session_dir: str) -> SessionData:
    """Load a session from its directory."""
    session_path = Path(session_dir)

    # Load metadata
    meta_path = session_path / "metadata.json"
    metadata = {}
    if meta_path.exists():
        with open(meta_path, "r") as f:
            metadata = json.load(f)

    # Load state stream
    states = []
    state_path = session_path / "state_stream.jsonl"
    if state_path.exists():
        with open(state_path, "r") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                raw = json.loads(line)
                states.append(StateSnapshot(
                    tick=raw.get("tick", 0),
                    player=raw.get("player", {}),
                    equipment=raw.get("equipment", {}),
                    environment=raw.get("environment", {}),
                    raw=raw,
                ))

    # Load event stream
    events = []
    goap_events = []
    event_path = session_path / "event_stream.jsonl"
    if event_path.exists():
        with open(event_path, "r") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                raw = json.loads(line)
                evt = GameEvent(
                    tick=raw.get("tick", 0),
                    event_type=raw.get("type", "unknown"),
                    raw=raw,
                )
                if evt.event_type.startswith("goap_"):
                    goap_events.append(evt)
                else:
                    events.append(evt)

    return SessionData(
        metadata=metadata,
        states=states,
        events=events,
        phases=[],
        signals={},
        goap_events=goap_events,
    )


# ── Phase 1: Event Enrichment ────────────────────────────────────────────

def enrich_events(session: SessionData):
    """Attach nearest state snapshot to each event by tick."""
    if not session.states:
        return

    state_ticks = [s.tick for s in session.states]

    for event in session.events + session.goap_events:
        idx = _bisect_nearest(state_ticks, event.tick)
        event.nearest_state = session.states[idx]


def _bisect_nearest(sorted_ticks: list, target: int) -> int:
    """Find index of nearest tick in sorted list."""
    lo, hi = 0, len(sorted_ticks) - 1
    if hi < 0:
        return 0
    if target <= sorted_ticks[0]:
        return 0
    if target >= sorted_ticks[-1]:
        return hi

    while lo < hi - 1:
        mid = (lo + hi) // 2
        if sorted_ticks[mid] <= target:
            lo = mid
        else:
            hi = mid

    if abs(sorted_ticks[lo] - target) <= abs(sorted_ticks[hi] - target):
        return lo
    return hi


# ── Phase 2: Automatic Phase Detection ───────────────────────────────────

PHASE_WINDOW_TICKS = 600    # 30 seconds at 20 tps
PHASE_STRIDE_TICKS = 300    # 15 second stride

PHASE_LABELS = {
    "building": {"block_placed"},
    "mining": {"block_broken"},
    "combat": {"attack", "damage_taken", "kill", "death"},
    "survival": {"eat_food", "drink_potion"},
    "container": {"screen_close"},
    "crafting": {"craft_item"},
    "exploration": set(),  # detected by movement, not events
}


def detect_phases(session: SessionData):
    """Classify gameplay into phases using sliding windows."""
    if not session.events:
        return

    min_tick = session.events[0].tick
    max_tick = session.events[-1].tick

    phases = []
    window_start = min_tick

    while window_start < max_tick:
        window_end = window_start + PHASE_WINDOW_TICKS

        # Count events in this window by type
        type_counts = defaultdict(int)
        window_events = [
            e for e in session.events
            if window_start <= e.tick < window_end
        ]
        for e in window_events:
            type_counts[e.event_type] += 1

        # Calculate movement displacement from state stream
        displacement = _calc_displacement(session.states, window_start, window_end)

        # Classify
        label = _classify_window(type_counts, displacement, len(window_events))

        phases.append(Phase(
            start_tick=window_start,
            end_tick=window_end,
            label=label,
            event_count=len(window_events),
            dominant_events=dict(type_counts),
        ))

        window_start += PHASE_STRIDE_TICKS

    # Merge adjacent phases with the same label
    session.phases = _merge_phases(phases)


def _calc_displacement(states: list, start_tick: int, end_tick: int) -> float:
    """Calculate total XZ displacement between two ticks."""
    window_states = [s for s in states if start_tick <= s.tick < end_tick]
    if len(window_states) < 2:
        return 0.0

    total = 0.0
    for i in range(1, len(window_states)):
        prev = window_states[i - 1].player
        curr = window_states[i].player
        dx = curr.get("x", 0) - prev.get("x", 0)
        dz = curr.get("z", 0) - prev.get("z", 0)
        total += math.sqrt(dx * dx + dz * dz)
    return total


def _classify_window(type_counts: dict, displacement: float, total_events: int) -> str:
    """Classify a window into a phase label."""
    # Combat takes priority (urgent)
    combat_count = sum(type_counts.get(t, 0) for t in PHASE_LABELS["combat"])
    if combat_count >= 2:
        return "combat"

    # Survival (eating during another activity)
    survival_count = sum(type_counts.get(t, 0) for t in PHASE_LABELS["survival"])
    if survival_count >= 1 and total_events <= 3:
        return "survival"

    # Building
    placed = type_counts.get("block_placed", 0)
    if placed >= 3:
        return "building"

    # Mining
    broken = type_counts.get("block_broken", 0)
    if broken >= 3 and displacement > 5.0:
        return "mining"

    # Container management
    container_count = sum(type_counts.get(t, 0) for t in PHASE_LABELS["container"])
    if container_count >= 1:
        return "container"

    # Crafting
    craft_count = sum(type_counts.get(t, 0) for t in PHASE_LABELS["crafting"])
    if craft_count >= 1:
        return "crafting"

    # Exploration (high movement, low interaction)
    if displacement > 20.0 and total_events < 3:
        return "exploration"

    # Idle / unknown
    if total_events == 0:
        return "idle"

    return "mixed"


def _merge_phases(phases: list) -> list:
    """Merge adjacent windows with the same label."""
    if not phases:
        return []

    merged = [phases[0]]
    for p in phases[1:]:
        if p.label == merged[-1].label:
            merged[-1].end_tick = p.end_tick
            merged[-1].event_count += p.event_count
            for k, v in p.dominant_events.items():
                merged[-1].dominant_events[k] = merged[-1].dominant_events.get(k, 0) + v
        else:
            merged.append(p)
    return merged


# ── Phase 3: Decision-Inference Signals ──────────────────────────────────

def extract_signals(session: SessionData) -> dict:
    """Extract decision-inference signals from raw data."""
    signals = {}

    signals["action_interruptions"] = _detect_interruptions(session)
    signals["dwell_times"] = _detect_dwell_times(session)
    signals["priority_shifts"] = _detect_priority_shifts(session)
    signals["mistake_corrections"] = _detect_mistakes(session)
    signals["eat_timing"] = _detect_eat_timing(session)
    signals["threat_responses"] = _detect_threat_responses(session)
    signals["opportunity_pickups"] = _detect_opportunity_pickups(session)

    session.signals = signals
    return signals


def _detect_interruptions(session: SessionData) -> list:
    """Detect activity switches mid-task (e.g., building → combat → building)."""
    interruptions = []
    if len(session.phases) < 3:
        return interruptions

    for i in range(1, len(session.phases) - 1):
        prev_phase = session.phases[i - 1]
        curr_phase = session.phases[i]
        next_phase = session.phases[i + 1]

        # An interruption is: same activity, then different, then back
        if prev_phase.label == next_phase.label and curr_phase.label != prev_phase.label:
            interruptions.append({
                "tick": curr_phase.start_tick,
                "from": prev_phase.label,
                "interrupting": curr_phase.label,
                "resumed_at": next_phase.start_tick,
                "duration_ticks": curr_phase.end_tick - curr_phase.start_tick,
            })

    return interruptions


def _detect_dwell_times(session: SessionData) -> list:
    """Detect stationary periods with no actions (planning pauses)."""
    dwells = []
    if not session.states or not session.events:
        return dwells

    # Build event tick set for fast lookup
    event_ticks = set(e.tick for e in session.events)

    # Scan state stream for stationary periods
    streak_start = None
    for i in range(1, len(session.states)):
        prev = session.states[i - 1]
        curr = session.states[i]

        dx = curr.player.get("x", 0) - prev.player.get("x", 0)
        dz = curr.player.get("z", 0) - prev.player.get("z", 0)
        speed = math.sqrt(dx * dx + dz * dz)

        # Check if any events happened between these ticks
        has_events = any(prev.tick <= t < curr.tick for t in event_ticks)

        if speed < 0.1 and not has_events:
            if streak_start is None:
                streak_start = prev.tick
        else:
            if streak_start is not None:
                duration = prev.tick - streak_start
                if duration >= 60:  # 3+ seconds
                    dwells.append({
                        "start_tick": streak_start,
                        "end_tick": prev.tick,
                        "duration_ticks": duration,
                    })
            streak_start = None

    return dwells


def _detect_priority_shifts(session: SessionData) -> list:
    """Detect phase transition sequences (build→fight→eat→build)."""
    shifts = []
    for i in range(1, len(session.phases)):
        shifts.append({
            "tick": session.phases[i].start_tick,
            "from": session.phases[i - 1].label,
            "to": session.phases[i].label,
        })
    return shifts


def _detect_mistakes(session: SessionData) -> list:
    """Detect place + break same block within N ticks (self-correction)."""
    mistakes = []
    CORRECTION_WINDOW = 200  # 10 seconds

    place_events = [e for e in session.events if e.event_type == "block_placed"]
    break_events = [e for e in session.events if e.event_type == "block_broken"]

    for place in place_events:
        px = place.raw.get("x")
        py = place.raw.get("y")
        pz = place.raw.get("z")
        if px is None:
            continue

        for brk in break_events:
            if brk.tick < place.tick:
                continue
            if brk.tick > place.tick + CORRECTION_WINDOW:
                break

            if (brk.raw.get("x") == px and
                    brk.raw.get("y") == py and
                    brk.raw.get("z") == pz):
                mistakes.append({
                    "placed_tick": place.tick,
                    "broken_tick": brk.tick,
                    "block": place.raw.get("block", "unknown"),
                    "delay_ticks": brk.tick - place.tick,
                    "x": px, "y": py, "z": pz,
                })
                break

    return mistakes


def _detect_eat_timing(session: SessionData) -> list:
    """Detect hunger level at food consumption — survival urgency curve."""
    timings = []
    eat_events = [e for e in session.events if e.event_type == "eat_food"]

    for eat in eat_events:
        state = eat.nearest_state
        timing = {
            "tick": eat.tick,
            "item": eat.raw.get("item", "unknown"),
            "hunger_before": eat.raw.get("hunger_before"),
            "health_before": eat.raw.get("health_before"),
        }
        if state:
            timing["was_in_combat"] = _has_nearby_hostile(state, max_dist=16)
        timings.append(timing)

    return timings


def _detect_threat_responses(session: SessionData) -> list:
    """Detect distance + LOS + mob type at first combat engagement."""
    responses = []
    combat_events = [e for e in session.events if e.event_type in ("attack", "hotbar_select")]

    for evt in combat_events:
        state = evt.nearest_state
        if not state:
            continue

        # For hotbar_select, check if it was a weapon switch
        if evt.event_type == "hotbar_select":
            trigger = evt.raw.get("trigger", {})
            if not trigger.get("nearest_hostile"):
                continue
            responses.append({
                "tick": evt.tick,
                "type": "weapon_switch",
                "target": trigger.get("nearest_hostile"),
                "dist": trigger.get("dist"),
                "health": trigger.get("health"),
            })
        elif evt.event_type == "attack":
            responses.append({
                "tick": evt.tick,
                "type": "first_attack",
                "target": evt.raw.get("target", "unknown"),
                "dist": evt.raw.get("dist"),
                "weapon": evt.raw.get("weapon"),
            })

    return responses


def _detect_opportunity_pickups(session: SessionData) -> list:
    """Detect path deviations to collect resources."""
    # This requires comparing movement trajectory to pick up events
    # Simplified: detect item pickups that happened while in a non-mining phase
    pickups = []
    pickup_events = [e for e in session.events if e.event_type == "item_pickup"]

    for pickup in pickup_events:
        # Find which phase this occurred in
        phase = None
        for p in session.phases:
            if p.start_tick <= pickup.tick < p.end_tick:
                phase = p
                break

        if phase and phase.label not in ("mining", "container"):
            pickups.append({
                "tick": pickup.tick,
                "item": pickup.raw.get("item", "unknown"),
                "during_phase": phase.label if phase else "unknown",
            })

    return pickups


def _has_nearby_hostile(state: StateSnapshot, max_dist: float = 16) -> bool:
    """Check if there's a hostile entity within max_dist in the state snapshot."""
    entities = state.environment.get("nearby_entities", [])
    for e in entities:
        if e.get("targeting", False) and e.get("dist", 999) < max_dist:
            return True
    hazard = state.environment.get("nearest_hazard", {})
    if hazard.get("dist", 999) < max_dist:
        return True
    return False


# ── Phase 4: GOAP Parameter Extraction ───────────────────────────────────

def extract_goap_params(session: SessionData) -> dict:
    """Extract GOAP calibration parameters from human gameplay data."""
    params = {}

    # Eat timing → survival urgency curve
    eat_timings = session.signals.get("eat_timing", [])
    if eat_timings:
        hunger_levels = [t["hunger_before"] for t in eat_timings if t.get("hunger_before") is not None]
        health_levels = [t["health_before"] for t in eat_timings if t.get("health_before") is not None]
        if hunger_levels:
            params["eat_hunger_threshold"] = {
                "mean": sum(hunger_levels) / len(hunger_levels),
                "min": min(hunger_levels),
                "max": max(hunger_levels),
                "samples": len(hunger_levels),
            }
        if health_levels:
            params["eat_health_threshold"] = {
                "mean": sum(health_levels) / len(health_levels),
                "min": min(health_levels),
                "max": max(health_levels),
                "samples": len(health_levels),
            }

    # Threat response distances
    responses = session.signals.get("threat_responses", [])
    if responses:
        dists = [r["dist"] for r in responses if r.get("dist") is not None]
        if dists:
            params["threat_response_distance"] = {
                "mean": sum(dists) / len(dists),
                "min": min(dists),
                "max": max(dists),
                "samples": len(dists),
            }

    # Action interruption frequency
    interruptions = session.signals.get("action_interruptions", [])
    params["interruption_count"] = len(interruptions)
    if interruptions:
        durations = [i["duration_ticks"] for i in interruptions]
        params["interruption_duration"] = {
            "mean_ticks": sum(durations) / len(durations),
            "min_ticks": min(durations),
            "max_ticks": max(durations),
        }

    # Mistake correction rate
    mistakes = session.signals.get("mistake_corrections", [])
    total_placed = sum(1 for e in session.events if e.event_type == "block_placed")
    params["mistake_rate"] = {
        "corrections": len(mistakes),
        "total_placed": total_placed,
        "rate": len(mistakes) / max(total_placed, 1),
    }

    # Phase time distribution
    phase_times = defaultdict(int)
    for phase in session.phases:
        phase_times[phase.label] += phase.end_tick - phase.start_tick
    total_time = sum(phase_times.values()) or 1
    params["phase_distribution"] = {
        label: {"ticks": ticks, "pct": round(100 * ticks / total_time, 1)}
        for label, ticks in sorted(phase_times.items(), key=lambda x: -x[1])
    }

    # Dwell time (planning pauses)
    dwells = session.signals.get("dwell_times", [])
    if dwells:
        dwell_durs = [d["duration_ticks"] for d in dwells]
        params["planning_pauses"] = {
            "count": len(dwells),
            "mean_ticks": sum(dwell_durs) / len(dwell_durs),
            "total_ticks": sum(dwell_durs),
        }

    return params


# ── Phase 5: GOAP vs Human Comparison ────────────────────────────────────

def compare_sessions(human: SessionData, goap: SessionData) -> dict:
    """Compare human and GOAP sessions to find divergence points."""
    comparison = {
        "phase_comparison": _compare_phases(human, goap),
        "eat_timing_comparison": _compare_eat_timing(human, goap),
        "threat_response_comparison": _compare_threat_responses(human, goap),
        "goap_decisions": _summarize_goap_decisions(goap),
    }
    return comparison


def _compare_phases(human: SessionData, goap: SessionData) -> dict:
    """Compare phase distributions between human and GOAP."""
    human_dist = defaultdict(int)
    goap_dist = defaultdict(int)

    for p in human.phases:
        human_dist[p.label] += p.end_tick - p.start_tick
    for p in goap.phases:
        goap_dist[p.label] += p.end_tick - p.start_tick

    all_labels = set(human_dist.keys()) | set(goap_dist.keys())
    human_total = sum(human_dist.values()) or 1
    goap_total = sum(goap_dist.values()) or 1

    result = {}
    for label in sorted(all_labels):
        result[label] = {
            "human_pct": round(100 * human_dist[label] / human_total, 1),
            "goap_pct": round(100 * goap_dist[label] / goap_total, 1),
        }
    return result


def _compare_eat_timing(human: SessionData, goap: SessionData) -> dict:
    """Compare when human vs GOAP decides to eat."""
    h_timings = human.signals.get("eat_timing", [])
    g_timings = goap.signals.get("eat_timing", [])

    h_hunger = [t["hunger_before"] for t in h_timings if t.get("hunger_before") is not None]
    g_hunger = [t["hunger_before"] for t in g_timings if t.get("hunger_before") is not None]

    return {
        "human_mean_hunger": sum(h_hunger) / len(h_hunger) if h_hunger else None,
        "goap_mean_hunger": sum(g_hunger) / len(g_hunger) if g_hunger else None,
        "human_samples": len(h_hunger),
        "goap_samples": len(g_hunger),
    }


def _compare_threat_responses(human: SessionData, goap: SessionData) -> dict:
    """Compare threat response distances."""
    h_resp = human.signals.get("threat_responses", [])
    g_resp = goap.signals.get("threat_responses", [])

    h_dists = [r["dist"] for r in h_resp if r.get("dist") is not None]
    g_dists = [r["dist"] for r in g_resp if r.get("dist") is not None]

    return {
        "human_mean_dist": sum(h_dists) / len(h_dists) if h_dists else None,
        "goap_mean_dist": sum(g_dists) / len(g_dists) if g_dists else None,
        "human_samples": len(h_dists),
        "goap_samples": len(g_dists),
    }


def _summarize_goap_decisions(goap: SessionData) -> dict:
    """Summarize GOAP decision events."""
    summary = defaultdict(int)
    for evt in goap.goap_events:
        summary[evt.event_type] += 1

    replans = [e for e in goap.goap_events if e.event_type == "goap_replan"]
    replan_reasons = defaultdict(int)
    for r in replans:
        reason = r.raw.get("reason", "unknown")
        replan_reasons[reason] += 1

    return {
        "event_counts": dict(summary),
        "replan_reasons": dict(replan_reasons),
    }


# ── Report Generation ────────────────────────────────────────────────────

def generate_report(session: SessionData, goap_params: dict) -> dict:
    """Generate a full analysis report."""
    duration_ticks = 0
    if session.states:
        duration_ticks = session.states[-1].tick - session.states[0].tick

    report = {
        "session": {
            "player": session.metadata.get("player", "unknown"),
            "duration_ticks": duration_ticks,
            "duration_minutes": round(duration_ticks / 20 / 60, 1),
            "total_states": len(session.states),
            "total_events": len(session.events),
            "total_goap_events": len(session.goap_events),
        },
        "event_summary": _count_event_types(session.events),
        "phases": [
            {
                "label": p.label,
                "start_tick": p.start_tick,
                "end_tick": p.end_tick,
                "duration_ticks": p.end_tick - p.start_tick,
                "event_count": p.event_count,
            }
            for p in session.phases
        ],
        "signals": {
            "action_interruptions": len(session.signals.get("action_interruptions", [])),
            "dwell_times": len(session.signals.get("dwell_times", [])),
            "priority_shifts": len(session.signals.get("priority_shifts", [])),
            "mistake_corrections": len(session.signals.get("mistake_corrections", [])),
            "eat_timing_samples": len(session.signals.get("eat_timing", [])),
            "threat_responses": len(session.signals.get("threat_responses", [])),
            "opportunity_pickups": len(session.signals.get("opportunity_pickups", [])),
        },
        "goap_params": goap_params,
    }

    # Include annotations if present
    annotations = [e for e in session.events if e.event_type == "annotation"]
    if annotations:
        report["annotations"] = [
            {"tick": a.tick, "text": a.raw.get("text", "")}
            for a in annotations
        ]

    return report


def _count_event_types(events: list) -> dict:
    """Count events by type."""
    counts = defaultdict(int)
    for e in events:
        counts[e.event_type] += 1
    return dict(sorted(counts.items(), key=lambda x: -x[1]))


# ── Main ──────────────────────────────────────────────────────────────────

def analyze(session_dir: str, compare_dir: str = None) -> dict:
    """Run full analysis pipeline on a session."""
    print(f"Loading session from: {session_dir}")
    session = load_session(session_dir)
    print(f"  States: {len(session.states)}, Events: {len(session.events)}, "
          f"GOAP events: {len(session.goap_events)}")

    # Phase 1: Enrich events with nearest state
    print("Phase 1: Enriching events with state context...")
    enrich_events(session)

    # Phase 2: Detect gameplay phases
    print("Phase 2: Detecting gameplay phases...")
    detect_phases(session)
    print(f"  Detected {len(session.phases)} phases")

    # Phase 3: Extract decision signals
    print("Phase 3: Extracting decision-inference signals...")
    signals = extract_signals(session)
    for name, data in signals.items():
        if isinstance(data, list):
            print(f"  {name}: {len(data)} instances")

    # Phase 4: Extract GOAP parameters
    print("Phase 4: Extracting GOAP calibration parameters...")
    goap_params = extract_goap_params(session)

    # Generate report
    report = generate_report(session, goap_params)

    # Phase 5: Compare with GOAP session if provided
    if compare_dir:
        print(f"\nPhase 5: Comparing with GOAP session: {compare_dir}")
        goap_session = load_session(compare_dir)
        enrich_events(goap_session)
        detect_phases(goap_session)
        extract_signals(goap_session)
        report["comparison"] = compare_sessions(session, goap_session)

    return report


def main():
    if len(sys.argv) < 2:
        print("Usage: python -m gamer.gameplay_analyzer <session_dir> [--output report.json] [--compare <goap_dir>]")
        sys.exit(1)

    session_dir = sys.argv[1]
    output_file = None
    compare_dir = None

    i = 2
    while i < len(sys.argv):
        if sys.argv[i] == "--output" and i + 1 < len(sys.argv):
            output_file = sys.argv[i + 1]
            i += 2
        elif sys.argv[i] == "--compare" and i + 1 < len(sys.argv):
            compare_dir = sys.argv[i + 1]
            i += 2
        else:
            i += 1

    report = analyze(session_dir, compare_dir)

    if output_file:
        with open(output_file, "w") as f:
            json.dump(report, f, indent=2)
        print(f"\nReport written to: {output_file}")
    else:
        print("\n" + json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
