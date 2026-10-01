#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JSON = REPO_ROOT / "emma_extracted_recipes.json"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "generated"
DEFAULT_BASELINE_CANDIDATES = [
    Path(__file__).resolve().parents[2]
    / "Emmaclef"
    / "java"
    / "emmaclef"
    / "src"
    / "main"
    / "java"
    / "emma"
    / "emmaclef"
    / "TaskCatalogue.java",
]


def find_default_baseline() -> Path | None:
    for candidate in DEFAULT_BASELINE_CANDIDATES:
        if candidate.exists():
            return candidate
    return None


def load_extractor_version(path: Path) -> int:
    with path.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    return int(data.get("extractionMeta", {}).get("extractorVersion", 0))


def run_step(command: list[str], description: str) -> None:
    print(description)
    print(" ".join(command))
    subprocess.run(command, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run the full TaskCatalogue generation workflow: generate from extractor JSON, then compare against the real current TaskCatalogue.java."
    )
    parser.add_argument(
        "--file",
        default=str(DEFAULT_JSON),
        help="Path to the extractor JSON file.",
    )
    parser.add_argument(
        "--baseline",
        help="Path to the real current TaskCatalogue.java. Defaults to the detected Emmaclef sibling repo path.",
    )
    parser.add_argument(
        "--output-dir",
        default=str(DEFAULT_OUTPUT_DIR),
        help="Directory where generated outputs and reports will be written.",
    )
    parser.add_argument(
        "--name",
        default="task_catalogue_full_run",
        help="Base filename prefix for generated output and reports.",
    )
    parser.add_argument(
        "--show-missing",
        type=int,
        default=80,
        help="Number of missing derived keys to print from the comparator.",
    )
    args = parser.parse_args()

    source_json = Path(args.file).resolve()
    baseline = Path(args.baseline).resolve() if args.baseline else find_default_baseline()
    if baseline is None:
        raise SystemExit("Could not locate the real current TaskCatalogue.java. Pass --baseline explicitly.")
    if not baseline.exists():
        raise SystemExit(f"Baseline TaskCatalogue.java does not exist: {baseline}")

    output_dir = Path(args.output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    generated_java = output_dir / f"{args.name}.java"
    generation_report = output_dir / f"{args.name}.generation-report.json"
    comparison_report = output_dir / f"{args.name}.compare-report.json"

    extractor_version = load_extractor_version(source_json)
    print(f"Source JSON: {source_json}")
    print(f"Extractor version: {extractor_version}")
    print(f"Real current TaskCatalogue: {baseline}")
    print(f"Generated Java output: {generated_java}")

    if extractor_version < 3:
        raise SystemExit(
            "This JSON is extractorVersion < 3. Generate a fresh v3 export before running the full TaskCatalogue workflow."
        )

    generator_command = [
        sys.executable,
        str(REPO_ROOT / "tools" / "generate_task_catalogue.py"),
        "--file",
        str(source_json),
        "--template",
        str(baseline),
        "--output",
        str(generated_java),
        "--report",
        str(generation_report),
    ]

    compare_command = [
        sys.executable,
        str(REPO_ROOT / "tools" / "compare_task_catalogues.py"),
        "--baseline",
        str(baseline),
        "--candidate",
        str(generated_java),
        "--report",
        str(comparison_report),
        "--show-missing",
        str(args.show_missing),
    ]

    run_step(generator_command, "Running generator")
    run_step(compare_command, "Running comparator")

    print("Full run complete")
    print(f"Generated Java: {generated_java}")
    print(f"Generation report: {generation_report}")
    print(f"Comparison report: {comparison_report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())