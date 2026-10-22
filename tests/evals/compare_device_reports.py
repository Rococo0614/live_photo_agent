"""Print a compact, apples-to-apples comparison of on-device planner reports.

Usage:
    python tests/evals/compare_device_reports.py \
      baseline=tests/evals/device_eval_report_smart_removal.json \
      p1=tests/evals/device_eval_report_p1.json

Turn latency is recomputed from per-case intent + plan latency so reports created before the
`turn` aggregate was added remain comparable.
"""

from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path
from typing import Any


def percentile(values: list[int], p: float) -> int | None:
    if not values:
        return None
    values = sorted(values)
    return values[min(len(values) - 1, int(round(p * (len(values) - 1))))]


def load(label_path: str) -> dict[str, Any]:
    if "=" not in label_path:
        raise SystemExit(f"argument must be LABEL=PATH: {label_path}")
    label, path = label_path.split("=", 1)
    report = json.loads(Path(path).read_text(encoding="utf-8"))
    summary = report["summary"]
    turns: list[int] = []
    strategies: set[str] = set()
    reasoning_turns = 0
    for result in report.get("results", []):
        metrics = result.get("metrics") or {}
        latency = metrics.get("latency_ms") or {}
        values = [latency.get("intent"), latency.get("plan")]
        total = sum(int(v) for v in values if isinstance(v, (int, float)))
        if total:
            turns.append(total)
        strategy = metrics.get("planner_strategy")
        if strategy:
            strategies.add(str(strategy))
        chars = metrics.get("reasoning_chars") or {}
        if (chars.get("intent") or 0) + (chars.get("plan") or 0) > 0:
            reasoning_turns += 1
    return {
        "label": label,
        "n": summary.get("scored", 0),
        "errors": summary.get("errors", 0),
        "pass": summary.get("pass_rate"),
        "brain": summary.get("brain_pass_rate"),
        "swallowed": (summary.get("chat_swallowed_edit") or {}).get("rate"),
        "fallback": (summary.get("keyword_fallback") or {}).get("rate"),
        "clean": (summary.get("protocol") or {}).get("protocol_clean_rate"),
        "turn_p50": percentile(turns, 0.5),
        "turn_p95": percentile(turns, 0.95),
        "turn_mean": round(statistics.mean(turns)) if turns else None,
        "reasoning": reasoning_turns,
        "strategies": ",".join(sorted(strategies)) or "legacy",
    }


def show(value: Any) -> str:
    if isinstance(value, float):
        return f"{value:.3f}"
    return "—" if value is None else str(value)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    rows = [load(arg) for arg in sys.argv[1:]]
    columns = (
        "label", "strategies", "n", "errors", "pass", "brain", "swallowed", "fallback",
        "clean", "turn_p50", "turn_p95", "turn_mean", "reasoning",
    )
    print("| " + " | ".join(columns) + " |")
    print("|" + "|".join("---" for _ in columns) + "|")
    for row in rows:
        print("| " + " | ".join(show(row[column]) for column in columns) + " |")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
