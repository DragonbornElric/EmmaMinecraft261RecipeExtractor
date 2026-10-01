#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


HELPER_PATTERNS = [
    "mine",
    "simple",
    "smelt",
    "smith",
    "mob",
    "mobCook",
    "crop",
    "alias",
    "shapedRecipe2x2",
    "shapedRecipe3x3",
    "shapedRecipe2x2Block",
    "shapedRecipe3x3Block",
    "shapedRecipeSlab",
    "shapedRecipeStairs",
    "shapedRecipeWall",
    "tools",
    "armor",
    "woodTasks",
    "colorfulTasks",
]

NAME_HELPERS = {
    "mine",
    "simple",
    "smelt",
    "smith",
    "mob",
    "crop",
    "alias",
    "shapedRecipe2x2",
    "shapedRecipe3x3",
    "shapedRecipe2x2Block",
    "shapedRecipe3x3Block",
    "shapedRecipeSlab",
    "shapedRecipeStairs",
    "shapedRecipeWall",
}


@dataclass
class CatalogueSummary:
    path: Path
    total_lines: int
    static_block_lines: int
    helper_counts: dict[str, int]
    explicit_keys: set[str]
    derived_keys: set[str]


def strip_comments(code: str) -> str:
    result: list[str] = []
    i = 0
    in_line_comment = False
    in_block_comment = False
    in_string = False
    in_char = False
    escape = False

    while i < len(code):
        ch = code[i]
        nxt = code[i + 1] if i + 1 < len(code) else ""

        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
                result.append(ch)
            i += 1
            continue

        if in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                i += 2
            else:
                if ch == "\n":
                    result.append(ch)
                i += 1
            continue

        if in_string:
            result.append(ch)
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            i += 1
            continue

        if in_char:
            result.append(ch)
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == "'":
                in_char = False
            i += 1
            continue

        if ch == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue

        if ch == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue

        if ch == '"':
            in_string = True
            result.append(ch)
            i += 1
            continue

        if ch == "'":
            in_char = True
            result.append(ch)
            i += 1
            continue

        result.append(ch)
        i += 1

    return "".join(result)


def extract_static_block(text: str) -> str:
    marker = "static {"
    start = text.find(marker)
    if start == -1:
        raise ValueError("No static block found")

    brace_index = text.find("{", start)
    depth = 0
    in_string = False
    in_char = False
    escape = False

    for index in range(brace_index, len(text)):
        ch = text[index]
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            continue
        if in_char:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == "'":
                in_char = False
            continue
        if ch == '"':
            in_string = True
            continue
        if ch == "'":
            in_char = True
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[brace_index + 1 : index]

    raise ValueError("Unterminated static block")


def count_helpers(code: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for helper in HELPER_PATTERNS:
        counts[helper] = len(re.findall(rf"\b{re.escape(helper)}\s*\(", code))
    return counts


def extract_explicit_keys(code: str) -> set[str]:
    keys: set[str] = set()
    helper_group = "|".join(sorted(NAME_HELPERS, key=len, reverse=True))
    pattern = re.compile(rf"\b(?:{helper_group})\s*\(\s*\"([^\"]+)\"")
    for match in pattern.finditer(code):
        keys.add(match.group(1))
    return keys


def extract_string_args(argument_text: str) -> list[str]:
    return re.findall(r'"([^\"]+)"', argument_text)


def helper_calls(code: str, helper_name: str) -> Iterable[str]:
    pattern = re.compile(rf"\b{re.escape(helper_name)}\s*\(")
    for match in pattern.finditer(code):
        start = match.end() - 1
        depth = 0
        in_string = False
        in_char = False
        escape = False
        for index in range(start, len(code)):
            ch = code[index]
            if in_string:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == '"':
                    in_string = False
                continue
            if in_char:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == "'":
                    in_char = False
                continue
            if ch == '"':
                in_string = True
                continue
            if ch == "'":
                in_char = True
                continue
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    yield code[start + 1 : index]
                    break


def derive_helper_keys(code: str) -> set[str]:
    keys = extract_explicit_keys(code)

    for args in helper_calls(code, "mobCook"):
        string_args = extract_string_args(args)
        if not string_args:
            continue
        uncooked = string_args[0]
        keys.add(uncooked)
        if len(string_args) >= 2:
            cooked = string_args[1]
            keys.add(cooked)
        else:
            keys.add(f"cooked_{uncooked}")

    for args in helper_calls(code, "tools"):
        string_args = extract_string_args(args)
        if not string_args:
            continue
        material = string_args[0]
        for suffix in ("pickaxe", "shovel", "sword", "axe", "hoe"):
            keys.add(f"{material}_{suffix}")

    for args in helper_calls(code, "armor"):
        string_args = extract_string_args(args)
        if not string_args:
            continue
        material = string_args[0]
        for suffix in ("helmet", "chestplate", "leggings", "boots"):
            keys.add(f"{material}_{suffix}")

    return keys


def summarize_catalogue(path: Path) -> CatalogueSummary:
    text = path.read_text(encoding="utf-8")
    total_lines = len(text.splitlines())
    uncommented = strip_comments(text)
    static_block = strip_comments(extract_static_block(uncommented))
    static_block_lines = len([line for line in static_block.splitlines() if line.strip()])
    helper_counts = count_helpers(static_block)
    explicit_keys = extract_explicit_keys(static_block)
    derived_keys = derive_helper_keys(static_block)
    return CatalogueSummary(
        path=path,
        total_lines=total_lines,
        static_block_lines=static_block_lines,
        helper_counts=helper_counts,
        explicit_keys=explicit_keys,
        derived_keys=derived_keys,
    )


def diff_counts(left: dict[str, int], right: dict[str, int]) -> dict[str, dict[str, int]]:
    result: dict[str, dict[str, int]] = {}
    for key in sorted(set(left) | set(right)):
        result[key] = {
            "baseline": left.get(key, 0),
            "candidate": right.get(key, 0),
            "delta": right.get(key, 0) - left.get(key, 0),
        }
    return result


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compare a generated TaskCatalogue.java against a baseline TaskCatalogue.java and report coverage gaps."
    )
    parser.add_argument("--baseline", required=True, help="Path to the baseline TaskCatalogue.java")
    parser.add_argument("--candidate", required=True, help="Path to the generated candidate TaskCatalogue.java")
    parser.add_argument("--report", help="Optional path to write JSON output")
    parser.add_argument("--show-missing", type=int, default=50, help="Number of missing derived keys to print")
    args = parser.parse_args()

    baseline = summarize_catalogue(Path(args.baseline).resolve())
    candidate = summarize_catalogue(Path(args.candidate).resolve())

    missing_derived = sorted(baseline.derived_keys - candidate.derived_keys)
    extra_derived = sorted(candidate.derived_keys - baseline.derived_keys)
    missing_explicit = sorted(baseline.explicit_keys - candidate.explicit_keys)
    extra_explicit = sorted(candidate.explicit_keys - baseline.explicit_keys)

    report = {
        "baseline": {
            "path": str(baseline.path),
            "totalLines": baseline.total_lines,
            "staticBlockLines": baseline.static_block_lines,
            "helperCounts": baseline.helper_counts,
            "explicitKeyCount": len(baseline.explicit_keys),
            "derivedKeyCount": len(baseline.derived_keys),
        },
        "candidate": {
            "path": str(candidate.path),
            "totalLines": candidate.total_lines,
            "staticBlockLines": candidate.static_block_lines,
            "helperCounts": candidate.helper_counts,
            "explicitKeyCount": len(candidate.explicit_keys),
            "derivedKeyCount": len(candidate.derived_keys),
        },
        "helperCountDiff": diff_counts(baseline.helper_counts, candidate.helper_counts),
        "missingExplicitKeys": missing_explicit,
        "extraExplicitKeys": extra_explicit,
        "missingDerivedKeys": missing_derived,
        "extraDerivedKeys": extra_derived,
    }

    if args.report:
        report_path = Path(args.report).resolve()
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")

    print(f"Baseline lines: {baseline.total_lines}")
    print(f"Candidate lines: {candidate.total_lines}")
    print(f"Baseline derived keys: {len(baseline.derived_keys)}")
    print(f"Candidate derived keys: {len(candidate.derived_keys)}")
    print(f"Missing derived keys: {len(missing_derived)}")
    if missing_derived:
        preview = ", ".join(missing_derived[: args.show_missing])
        if len(missing_derived) > args.show_missing:
            preview += ", ..."
        print(f"Missing derived key preview: {preview}")
    print("Helper count diff:")
    for helper, stats in diff_counts(baseline.helper_counts, candidate.helper_counts).items():
        if stats["baseline"] == 0 and stats["candidate"] == 0:
            continue
        print(f"  {helper}: {stats['baseline']} -> {stats['candidate']} ({stats['delta']:+d})")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())