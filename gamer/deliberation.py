"""Pre-stream deliberation system.

The Orchestrator generates structured goal candidates with feasibility data;
the LLM selects among them and makes aesthetic decisions; the Orchestrator
finalizes by applying those decisions to the database.

This is the ONLY live LLM call in the gamer system outside of regular
narration through the agent loop.
"""

from __future__ import annotations

import json
import logging
import math
import time
from typing import Any

from gamer.emmatone_client import EmmatoneClient
from gamer.build_db import BuildDB

log = logging.getLogger(__name__)

# Maximum goals to present to the LLM at once
MAX_CANDIDATES = 8


class Deliberator:
    """Pre-stream deliberation: Orchestrator generates → LLM evaluates → Orchestrator finalizes."""

    def __init__(self, build_db: BuildDB, client: EmmatoneClient):
        self.build_db = build_db
        self.client = client

    # ── Step 1: Generate Candidates ───────────────────────────

    def generate_candidates(self) -> list[dict]:
        """Orchestrator generates goal candidates with feasibility data.

        For each goal with status 'future' or 'planned':
        - Bill of materials from the linked guide
        - Resource availability from world_state
        - Time estimate from block count + difficulty
        - Available substitutions per material
        - Feasibility score (0-1 based on how much material is available)

        Returns list of candidate dicts, sorted by feasibility desc.
        """
        candidates: list[dict] = []

        goals = self.build_db.get_goals_by_status("future") + \
                self.build_db.get_goals_by_status("planned")

        for goal in goals[:MAX_CANDIDATES]:
            candidate = self._evaluate_goal(goal)
            if candidate:
                candidates.append(candidate)

        # Sort by feasibility (highest first)
        candidates.sort(key=lambda c: c["feasibility"], reverse=True)
        return candidates

    def _evaluate_goal(self, goal: dict) -> dict | None:
        """Evaluate a single goal's feasibility."""
        guide_id = goal.get("guide_id")
        if not guide_id:
            # Goals without guides can still be selected — manual builds
            return {
                "goal_id": goal["id"],
                "goal_name": goal["name"],
                "functional_goal": goal.get("functional_goal", ""),
                "status": goal["status"],
                "priority_weight": goal.get("priority_weight", 0.5),
                "guide_name": None,
                "block_count": 0,
                "difficulty": "unknown",
                "time_estimate_minutes": 0,
                "feasibility": 0.3,
                "materials": {},
                "available_materials": {},
                "substitutions_available": {},
            }

        guide = self.build_db.get_guide(guide_id)
        if not guide:
            return None

        bom = self.build_db.get_guide_bill_of_materials(guide_id)

        # Check availability and substitutions for each material
        available_materials: dict[str, int] = {}
        substitutions_available: dict[str, list[str]] = {}
        total_blocks = 0
        blocks_available = 0

        for block_type, qty in bom.items():
            total_blocks += qty
            count = self.build_db.count_available(block_type)
            available_materials[block_type] = count
            blocks_available += min(count, qty)

            # List substitutions
            subs = self.build_db.get_substitutions(block_type)
            if subs:
                substitutions_available[block_type] = [
                    s["substitute_block"] for s in subs[:5]
                ]

        # Feasibility = fraction of materials available (including subs)
        feasibility = blocks_available / total_blocks if total_blocks > 0 else 0.0

        # Time estimate: ~2 seconds per block placed + gathering overhead
        difficulty = guide.get("difficulty", "medium")
        difficulty_multiplier = {"easy": 0.8, "medium": 1.0, "hard": 1.5, "expert": 2.0}
        base_time = total_blocks * 2  # seconds per block
        gather_time = max(0, (total_blocks - blocks_available) * 4)  # 4s per missing block
        total_seconds = (base_time + gather_time) * difficulty_multiplier.get(difficulty, 1.0)
        time_minutes = math.ceil(total_seconds / 60)

        return {
            "goal_id": goal["id"],
            "goal_name": goal["name"],
            "functional_goal": goal.get("functional_goal", ""),
            "status": goal["status"],
            "priority_weight": goal.get("priority_weight", 0.5),
            "guide_name": guide["name"],
            "block_count": total_blocks,
            "difficulty": difficulty,
            "time_estimate_minutes": time_minutes,
            "feasibility": round(feasibility, 2),
            "materials": {k: v for k, v in bom.items()},
            "available_materials": available_materials,
            "substitutions_available": substitutions_available,
        }

    # ── Step 2: LLM Deliberation ──────────────────────────────

    def run_deliberation(self, candidates: list[dict],
                         persona: str = "",
                         session_notes: str = "") -> dict:
        """Send candidates to LLM for aesthetic/priority selection.

        The LLM receives the candidate list, Emma's persona description,
        and notes from past sessions.  It returns structured JSON choosing
        which goals to pursue and aesthetic preferences.

        This is an Orchestrator-bounded call — the LLM can only select
        among what the Orchestrator offered; it cannot invent new goals.
        """
        prompt = self._build_deliberation_prompt(candidates, persona, session_notes)

        try:
            from brain_backends import api_chat

            messages = [
                {"role": "system", "content": (
                    "You are Emma's strategic planning module. You select "
                    "build goals and aesthetic preferences for a Minecraft "
                    "session. Respond ONLY with valid JSON — no commentary."
                )},
                {"role": "user", "content": prompt},
            ]

            result = api_chat(
                backend="openrouter",
                openrouter_model=None,  # uses configured default
                openai_model=None,
                messages=messages,
                max_tokens=600,
                temperature=0.4,
                purpose="deliberation",
            )

            # api_chat returns str for non-tool calls
            if isinstance(result, str):
                return self._parse_llm_response(result, candidates)
            elif isinstance(result, dict):
                content = result.get("content", "")
                return self._parse_llm_response(content, candidates)

        except Exception as exc:
            log.warning("Deliberation LLM call failed: %s — using defaults", exc)
            return self._default_selections(candidates)

    def _build_deliberation_prompt(self, candidates: list[dict],
                                   persona: str,
                                   session_notes: str) -> str:
        """Build the structured prompt for the LLM."""
        parts = []

        if persona:
            parts.append(f"Emma's personality: {persona}")
        if session_notes:
            parts.append(f"Notes from previous sessions: {session_notes}")

        parts.append(
            "Below are the available build goals for today's Minecraft session. "
            "For each candidate, feasibility shows how much material is already "
            "available (0-1). Select up to 3 goals to pursue, in priority order. "
            "For each selected goal, choose material preferences (use substitutions "
            "if you like a different look). Provide layout suggestions if relevant."
        )

        parts.append("\n--- CANDIDATES ---")
        for i, c in enumerate(candidates, 1):
            block = (
                f"\n{i}. {c['goal_name']}"
                f"\n   Purpose: {c.get('functional_goal', 'general')}"
                f"\n   Guide: {c.get('guide_name', 'none')}"
                f"\n   Blocks: {c['block_count']}, Difficulty: {c['difficulty']}"
                f"\n   Est. time: {c['time_estimate_minutes']} min"
                f"\n   Feasibility: {c['feasibility']}"
            )
            if c.get("substitutions_available"):
                subs_summary = "; ".join(
                    f"{k}: [{', '.join(v[:3])}]"
                    for k, v in c["substitutions_available"].items()
                    if v
                )
                if subs_summary:
                    block += f"\n   Available swaps: {subs_summary}"
            parts.append(block)

        parts.append(
            "\n--- RESPOND WITH JSON ---\n"
            "{\n"
            '  "selected_goals": [\n'
            '    {"goal_id": <id>, "priority": <1-3>, '
            '"aesthetic_notes": "<material/style preferences>", '
            '"substitutions": {"<original>": "<preferred_swap>"}, '
            '"layout_notes": "<where to build relative to base>"}\n'
            "  ],\n"
            '  "session_theme": "<overall build theme/mood>",\n'
            '  "reasoning": "<brief explanation>"\n'
            "}"
        )

        return "\n".join(parts)

    def _parse_llm_response(self, text: str,
                            candidates: list[dict]) -> dict:
        """Parse the LLM's JSON response, falling back to defaults."""
        # Extract JSON from response (may have markdown fences)
        text = text.strip()
        if text.startswith("```"):
            lines = text.split("\n")
            # Remove first and last fence lines
            json_lines = [
                l for l in lines
                if not l.strip().startswith("```")
            ]
            text = "\n".join(json_lines)

        try:
            data = json.loads(text)
        except json.JSONDecodeError:
            log.warning("Failed to parse deliberation JSON, using defaults")
            return self._default_selections(candidates)

        # Validate selected goals exist in candidates
        valid_ids = {c["goal_id"] for c in candidates}
        selected = data.get("selected_goals", [])
        validated = [s for s in selected if s.get("goal_id") in valid_ids]

        if not validated:
            return self._default_selections(candidates)

        return {
            "selected_goals": validated,
            "session_theme": data.get("session_theme", ""),
            "reasoning": data.get("reasoning", ""),
        }

    def _default_selections(self, candidates: list[dict]) -> dict:
        """Fallback: select top 3 by feasibility × priority_weight."""
        scored = sorted(
            candidates,
            key=lambda c: c["feasibility"] * c.get("priority_weight", 0.5),
            reverse=True,
        )
        selected = []
        for i, c in enumerate(scored[:3], 1):
            selected.append({
                "goal_id": c["goal_id"],
                "priority": i,
                "aesthetic_notes": "",
                "substitutions": {},
                "layout_notes": "",
            })
        return {
            "selected_goals": selected,
            "session_theme": "general building",
            "reasoning": "Auto-selected by feasibility score (LLM unavailable)",
        }

    # ── Step 2b: CLI Deliberation (Phase 43) ─────────────────

    def deliberate_via_cli(self, cli_session, candidates: list[dict],
                           session_notes: str = "") -> dict:
        """Run deliberation through the persistent CLI session.

        The CLI has full context: viewer preferences from the stream,
        terrain awareness, past build decisions, aesthetic history.
        Uses the session's memory instead of a one-shot LLM call.
        """
        prompt = self._format_candidates_for_cli(candidates, session_notes)
        response = cli_session.send(
            message=prompt,
            source="system",
            speaker_id="system",
            speaker_name="System",
        )

        if not response:
            log.warning("CLI deliberation returned no response — using defaults")
            return self._default_selections(candidates)

        return self._parse_llm_response(response, candidates)

    def _format_candidates_for_cli(self, candidates: list[dict],
                                   session_notes: str = "") -> str:
        """Format candidates for the CLI session's deliberation."""
        parts = [
            "[DELIBERATION] I need to plan today's Minecraft session. "
            "Here are the available build goals — pick up to 3 in priority "
            "order, with material preferences and placement ideas."
        ]

        if session_notes:
            parts.append(f"Notes from previous sessions: {session_notes}")

        for i, c in enumerate(candidates, 1):
            block = (
                f"\n{i}. {c['goal_name']}"
                f" — {c.get('functional_goal', 'general')}"
                f"\n   Guide: {c.get('guide_name', 'none')}"
                f", {c['block_count']} blocks"
                f", ~{c['time_estimate_minutes']} min"
                f", feasibility: {c['feasibility']}"
            )
            if c.get("substitutions_available"):
                subs_summary = "; ".join(
                    f"{k}: [{', '.join(v[:3])}]"
                    for k, v in c["substitutions_available"].items()
                    if v
                )
                if subs_summary:
                    block += f"\n   Swaps: {subs_summary}"
            parts.append(block)

        parts.append(
            "\nRespond with JSON:\n"
            '{"selected_goals": [{"goal_id": <id>, "priority": <1-3>, '
            '"aesthetic_notes": "...", "substitutions": {}, '
            '"layout_notes": "..."}], '
            '"session_theme": "...", "reasoning": "..."}'
        )

        return "\n".join(parts)

    # ── Step 3: Finalize ──────────────────────────────────────

    def finalize(self, decisions: dict):
        """Apply LLM decisions to the database.

        Updates build_goals with aesthetic_decisions, substitutions,
        priority_weight.  Moves statuses: future → planned → current.
        """
        selected = decisions.get("selected_goals", [])

        for sel in selected:
            goal_id = sel["goal_id"]
            priority = sel.get("priority", 3)

            # Map selection priority to weight (1=highest)
            weight = {1: 0.9, 2: 0.7, 3: 0.5}.get(priority, 0.5)
            self.build_db.adjust_priority(goal_id, weight)

            # Store aesthetic decisions
            aesthetic_notes = sel.get("aesthetic_notes", "")
            if aesthetic_notes:
                self.build_db.update_goal_aesthetics(goal_id, aesthetic_notes)

            # Store substitution preferences
            subs = sel.get("substitutions", {})
            if subs:
                self.build_db.update_goal_substitutions(
                    goal_id, json.dumps(subs)
                )

            # Advance status
            goal = self.build_db.get_goal(goal_id)
            if goal:
                if goal["status"] == "future":
                    self.build_db.update_goal_status(goal_id, "planned")
                elif goal["status"] == "planned" and priority == 1:
                    self.build_db.update_goal_status(goal_id, "current")

        log.info(
            "Deliberation finalized: %d goals selected, theme: %s",
            len(selected), decisions.get("session_theme", ""),
        )

    # ── Full Pipeline ─────────────────────────────────────────

    def deliberate(self, persona: str = "",
                   session_notes: str = "",
                   cli_session=None) -> dict:
        """Full pipeline: generate → LLM evaluate → finalize.

        If *cli_session* is provided and alive, uses the persistent CLI
        session for deliberation (Phase 43 enhanced path).  Otherwise
        falls back to the one-shot LLM call.

        Returns session plan summary for GUI display.
        """
        t0 = time.perf_counter()

        # Step 1: Generate
        candidates = self.generate_candidates()
        if not candidates:
            return {
                "status": "no_goals",
                "message": "No build goals available for deliberation.",
                "candidates": 0,
                "selected": 0,
                "elapsed_s": 0,
            }

        log.info("Deliberation: %d candidates generated", len(candidates))

        # Step 2: Selection — prefer CLI session if available
        if cli_session and cli_session.is_alive():
            log.info("Deliberation via persistent CLI session")
            decisions = self.deliberate_via_cli(
                cli_session, candidates, session_notes,
            )
        else:
            decisions = self.run_deliberation(candidates, persona, session_notes)

        # Step 3: Finalize
        self.finalize(decisions)

        elapsed = time.perf_counter() - t0

        return {
            "status": "ok",
            "candidates": len(candidates),
            "selected": len(decisions.get("selected_goals", [])),
            "session_theme": decisions.get("session_theme", ""),
            "reasoning": decisions.get("reasoning", ""),
            "goals": [
                {
                    "goal_id": s["goal_id"],
                    "priority": s.get("priority", 3),
                    "aesthetic_notes": s.get("aesthetic_notes", ""),
                }
                for s in decisions.get("selected_goals", [])
            ],
            "elapsed_s": round(elapsed, 1),
        }
