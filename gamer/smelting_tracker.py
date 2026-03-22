"""Tracks pending smelting jobs so Emma can load furnaces and walk away.

Jobs are ephemeral (minutes, not days) so we use a JSON file for persistence
rather than a DB table.  The tracker is owned by EmmatoneClient.
"""
from __future__ import annotations

import json
import logging
import threading
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Optional

log = logging.getLogger(__name__)

# Seconds per item by furnace type
_SMELT_TIMES: dict[str, float] = {
    "furnace": 10.0,
    "blast_furnace": 5.0,
    "smoker": 5.0,
}

_PERSIST_PATH = Path("smelting_jobs.json")


@dataclass
class SmeltingJob:
    job_id: str
    furnace_pos: tuple[int, int, int]
    furnace_type: str          # "furnace" | "blast_furnace" | "smoker"
    input_item: str            # e.g. "raw_iron"
    input_count: int
    fuel_item: str             # e.g. "coal"
    fuel_count: int
    output_item: str           # e.g. "iron_ingot"
    expected_output_count: int
    started_at: float          # time.time()
    estimated_done_at: float   # started_at + (input_count * seconds_per_item)
    status: str = "smelting"   # smelting | ready | collected | failed
    failure_reason: str = ""
    collected_at: Optional[float] = None


class SmeltingTracker:
    """Thread-safe tracker for pending smelting jobs."""

    def __init__(self, persist_path: Path | str = _PERSIST_PATH):
        self._jobs: dict[str, SmeltingJob] = {}
        self._lock = threading.Lock()
        self._persist_path = Path(persist_path)
        self._load()

    # ── Public API ───────────────────────────────────────────────────

    def add_job(
        self,
        furnace_pos: tuple[int, int, int],
        furnace_type: str,
        input_item: str,
        input_count: int,
        fuel_item: str,
        fuel_count: int,
        output_item: str,
        expected_output_count: int | None = None,
    ) -> SmeltingJob:
        """Register a new smelting job.  Returns the created job."""
        job_id = uuid.uuid4().hex[:8]
        now = time.time()
        secs_per_item = _SMELT_TIMES.get(furnace_type, 10.0)
        est_done = now + input_count * secs_per_item

        job = SmeltingJob(
            job_id=job_id,
            furnace_pos=tuple(furnace_pos),
            furnace_type=furnace_type,
            input_item=input_item,
            input_count=input_count,
            fuel_item=fuel_item,
            fuel_count=fuel_count,
            output_item=output_item,
            expected_output_count=expected_output_count or input_count,
            started_at=now,
            estimated_done_at=est_done,
        )
        with self._lock:
            self._jobs[job_id] = job
            self._save()
        log.info("Smelting job %s: %s x%d → %s (est %.0fs)",
                 job_id, input_item, input_count, output_item,
                 input_count * secs_per_item)
        return job

    def get_ready_jobs(self) -> list[SmeltingJob]:
        """Jobs whose timer has elapsed (auto-transitions smelting→ready)."""
        now = time.time()
        ready = []
        with self._lock:
            for job in self._jobs.values():
                if job.status == "smelting" and now >= job.estimated_done_at:
                    job.status = "ready"
                if job.status == "ready":
                    ready.append(job)
            if ready:
                self._save()
        return sorted(ready, key=lambda j: j.estimated_done_at)

    def get_active_jobs(self) -> list[SmeltingJob]:
        """Jobs still smelting (timer hasn't elapsed)."""
        now = time.time()
        with self._lock:
            return [
                j for j in self._jobs.values()
                if j.status == "smelting" and now < j.estimated_done_at
            ]

    def get_all_pending(self) -> list[SmeltingJob]:
        """All jobs that are smelting or ready (not collected/failed)."""
        with self._lock:
            return [
                j for j in self._jobs.values()
                if j.status in ("smelting", "ready")
            ]

    def mark_collected(self, job_id: str) -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job:
                job.status = "collected"
                job.collected_at = time.time()
                self._save()
                log.info("Smelting job %s collected: %s x%d",
                         job_id, job.output_item, job.expected_output_count)

    def mark_failed(self, job_id: str, reason: str = "") -> None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job:
                job.status = "failed"
                job.failure_reason = reason
                self._save()
                log.warning("Smelting job %s failed: %s", job_id, reason)

    def get_job(self, job_id: str) -> SmeltingJob | None:
        with self._lock:
            return self._jobs.get(job_id)

    def get_job_by_furnace(self, pos: tuple[int, int, int]) -> SmeltingJob | None:
        """Find active/ready job at a specific furnace position."""
        with self._lock:
            for job in self._jobs.values():
                if job.furnace_pos == tuple(pos) and job.status in ("smelting", "ready"):
                    return job
        return None

    def expire_stale(self, max_age: float = 3600.0) -> int:
        """Remove completed/failed jobs older than max_age seconds.  Returns count removed."""
        now = time.time()
        removed = 0
        with self._lock:
            to_remove = [
                jid for jid, j in self._jobs.items()
                if j.status in ("collected", "failed")
                and (j.collected_at or j.started_at) + max_age < now
            ]
            for jid in to_remove:
                del self._jobs[jid]
                removed += 1
            if removed:
                self._save()
        return removed

    def get_summary(self) -> str:
        """Formatted string for LLM context injection."""
        now = time.time()
        lines: list[str] = []

        ready = self.get_ready_jobs()
        active = self.get_active_jobs()

        if ready:
            lines.append(f"[SMELTING READY] {len(ready)} furnace(s) done — collect output!")
            for job in ready[:3]:
                x, y, z = job.furnace_pos
                lines.append(
                    f"  {job.output_item} x{job.expected_output_count} "
                    f"at ({x}, {y}, {z}) [job:{job.job_id}]"
                )

        for job in active:
            remaining = max(0, int(job.estimated_done_at - now))
            lines.append(
                f"Smelting: {job.input_item} x{job.input_count} → "
                f"{job.output_item}, ~{remaining}s remaining"
            )

        return "\n".join(lines)

    # ── Persistence ──────────────────────────────────────────────────

    def _save(self) -> None:
        """Write jobs to JSON file.  Caller must hold _lock."""
        try:
            data = {jid: asdict(j) for jid, j in self._jobs.items()}
            self._persist_path.write_text(json.dumps(data, indent=2))
        except Exception as exc:
            log.debug("Failed to save smelting jobs: %s", exc)

    def _load(self) -> None:
        """Load jobs from JSON file on startup."""
        if not self._persist_path.exists():
            return
        try:
            data = json.loads(self._persist_path.read_text())
            for jid, d in data.items():
                # Convert list back to tuple for furnace_pos
                d["furnace_pos"] = tuple(d["furnace_pos"])
                self._jobs[jid] = SmeltingJob(**d)
            log.info("Loaded %d smelting jobs from %s", len(self._jobs), self._persist_path)
        except Exception as exc:
            log.warning("Failed to load smelting jobs: %s", exc)
