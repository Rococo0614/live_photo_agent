from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ROOT_DIR = Path(__file__).resolve().parents[2]
SRC_DIR = ROOT_DIR / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from live_photo_agent.brain import QwenPlanner
from live_photo_agent.capability.contracts import CapabilityLayer
from live_photo_agent.models import AgentRequest, ExecutionPlan


@dataclass(slots=True)
class CaseResult:
    case_id: str
    category: str
    passed: bool
    checks: dict[str, bool]
    details: dict[str, Any]


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        text = raw.strip()
        if not text:
            continue
        try:
            row = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSONL at line {line_no}: {exc}") from exc
        if not isinstance(row, dict):
            raise ValueError(f"Line {line_no} is not an object")
        cases.append(row)
    return cases


def evaluate_case(case: dict[str, Any], plan: ExecutionPlan) -> CaseResult:
    case_id = str(case.get("case_id", "unknown"))
    category = str(case.get("category", "unknown"))

    expected_intent = str(case.get("expected_intent", ""))
    expected_need_clarification = bool(case.get("expected_need_clarification", False))
    must_include = [str(item) for item in case.get("expected_tools_must_include", [])]
    must_not_include = [str(item) for item in case.get("expected_tools_must_not_include", [])]
    arg_constraints = case.get("expected_argument_constraints", {})
    if not isinstance(arg_constraints, dict):
        arg_constraints = {}

    actual_tools = [call.tool.value for call in plan.tool_calls]

    checks: dict[str, bool] = {
        "intent_match": (plan.intent == expected_intent),
        "clarification_match": (plan.need_clarification == expected_need_clarification),
        "must_include_match": all(tool in actual_tools for tool in must_include),
        "must_not_include_match": all(tool not in actual_tools for tool in must_not_include),
    }

    constraint_ok = True
    constraint_details: dict[str, dict[str, Any]] = {}
    for tool_name, required_keys_raw in arg_constraints.items():
        required_keys = [str(item) for item in required_keys_raw] if isinstance(required_keys_raw, list) else []
        matching_calls = [call for call in plan.tool_calls if call.tool.value == tool_name]

        if not matching_calls:
            constraint_ok = False
            constraint_details[str(tool_name)] = {
                "required_keys": required_keys,
                "matched": False,
                "reason": "tool_not_found_in_plan",
            }
            continue

        any_call_satisfies = any(all(key in call.arguments for key in required_keys) for call in matching_calls)
        if not any_call_satisfies:
            constraint_ok = False
            seen_arg_keys = [sorted(list(call.arguments.keys())) for call in matching_calls]
            constraint_details[str(tool_name)] = {
                "required_keys": required_keys,
                "matched": False,
                "seen_arg_keys": seen_arg_keys,
            }
        else:
            constraint_details[str(tool_name)] = {
                "required_keys": required_keys,
                "matched": True,
            }

    checks["argument_constraints_match"] = constraint_ok

    passed = all(checks.values())
    details = {
        "expected_intent": expected_intent,
        "actual_intent": plan.intent,
        "expected_need_clarification": expected_need_clarification,
        "actual_need_clarification": plan.need_clarification,
        "expected_tools_must_include": must_include,
        "expected_tools_must_not_include": must_not_include,
        "actual_tools": actual_tools,
        "argument_constraint_details": constraint_details,
        "clarification_questions": plan.clarification_questions,
        "blocking_missing_info": plan.blocking_missing_info,
    }

    return CaseResult(
        case_id=case_id,
        category=category,
        passed=passed,
        checks=checks,
        details=details,
    )


def summarize(results: list[CaseResult]) -> dict[str, Any]:
    total = len(results)
    passed = sum(1 for result in results if result.passed)

    overall_checks: dict[str, float] = {}
    if results:
        keys = sorted(results[0].checks.keys())
        for key in keys:
            overall_checks[key] = sum(1 for result in results if result.checks.get(key, False)) / total

    by_category: dict[str, dict[str, Any]] = {}
    for result in results:
        bucket = by_category.setdefault(
            result.category,
            {
                "total": 0,
                "passed": 0,
                "checks": {k: 0 for k in result.checks.keys()},
            },
        )
        bucket["total"] += 1
        if result.passed:
            bucket["passed"] += 1
        for key, ok in result.checks.items():
            if ok:
                bucket["checks"][key] += 1

    for category, bucket in by_category.items():
        total_cat = bucket["total"]
        bucket["pass_rate"] = (bucket["passed"] / total_cat) if total_cat else 0.0
        bucket["check_rates"] = {
            key: (value / total_cat if total_cat else 0.0)
            for key, value in bucket["checks"].items()
        }
        del bucket["checks"]

    return {
        "total": total,
        "passed": passed,
        "pass_rate": (passed / total if total else 0.0),
        "overall_check_rates": overall_checks,
        "by_category": by_category,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate planner outputs against labeled eval set.")
    parser.add_argument(
        "--dataset",
        type=Path,
        default=ROOT_DIR / "tests" / "evals" / "planner_eval_set_50.jsonl",
        help="Path to planner eval jsonl dataset.",
    )
    parser.add_argument(
        "--library-root",
        type=Path,
        default=ROOT_DIR / "tests" / "_smoke_artifacts" / "library",
        help="Library root path supplied to planner request.",
    )
    parser.add_argument(
        "--asset-count",
        type=int,
        default=120,
        help="Synthetic asset count used in library_summary for planner requests.",
    )
    parser.add_argument(
        "--normalize",
        action="store_true",
        default=True,
        help="Apply capability normalize_plan before scoring (default: true).",
    )
    parser.add_argument(
        "--no-normalize",
        action="store_false",
        dest="normalize",
        help="Disable capability normalize_plan before scoring.",
    )
    parser.add_argument(
        "--max-cases",
        type=int,
        default=0,
        help="Limit number of cases for quick validation (0 means all).",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT_DIR / "tests" / "evals" / "planner_eval_report.json",
        help="Output path for aggregated report.",
    )
    parser.add_argument(
        "--failures-output",
        type=Path,
        default=ROOT_DIR / "tests" / "evals" / "planner_eval_failures.jsonl",
        help="Output path for failed case details.",
    )

    args = parser.parse_args()

    planner = QwenPlanner()
    print(json.dumps({**planner.runtime_info(), "normalize": args.normalize}, ensure_ascii=False))

    cases = load_jsonl(args.dataset)
    if args.max_cases > 0:
        cases = cases[: args.max_cases]

    capability = CapabilityLayer()

    results: list[CaseResult] = []
    failures: list[dict[str, Any]] = []

    for case in cases:
        selected_ids_raw = case.get("selected_asset_ids", [])
        selected_ids = [str(item) for item in selected_ids_raw] if isinstance(selected_ids_raw, list) else []

        request = AgentRequest(
            user_id="eval-user",
            text=str(case.get("user_text", "")),
            library_root=args.library_root,
            selected_asset_ids=selected_ids,
        )
        library_summary = {
            "asset_count": args.asset_count,
            "selected_asset_count": len(selected_ids),
        }

        try:
            plan = planner.create_plan(request, library_summary)
            if args.normalize:
                plan = capability.normalize_plan(plan, request)
            result = evaluate_case(case, plan)
        except Exception as exc:  # noqa: BLE001
            case_id = str(case.get("case_id", "unknown"))
            category = str(case.get("category", "unknown"))
            result = CaseResult(
                case_id=case_id,
                category=category,
                passed=False,
                checks={
                    "intent_match": False,
                    "clarification_match": False,
                    "must_include_match": False,
                    "must_not_include_match": False,
                    "argument_constraints_match": False,
                },
                details={
                    "error": str(exc),
                },
            )

        results.append(result)
        if not result.passed:
            failures.append(
                {
                    "case_id": result.case_id,
                    "category": result.category,
                    "checks": result.checks,
                    "details": result.details,
                }
            )

    report = summarize(results)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    args.failures_output.parent.mkdir(parents=True, exist_ok=True)
    with args.failures_output.open("w", encoding="utf-8") as fp:
        for row in failures:
            fp.write(json.dumps(row, ensure_ascii=False) + "\n")

    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"report_saved_to: {args.output}")
    print(f"failures_saved_to: {args.failures_output}")


if __name__ == "__main__":
    main()
