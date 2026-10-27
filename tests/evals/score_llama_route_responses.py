#!/usr/bin/env python3
"""Score phone-side llama.cpp Route responses against a compact manifest.

The request runner deliberately lives on the phone so a transient ADB connection cannot
cancel generation. This host-side scorer consumes the saved OpenAI-compatible JSON files.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
from pathlib import Path
from typing import Any


ROUTE_RE = re.compile(r"^ROUTE=([A-Z_]+)(?:\|(.*))?$")
APP_ROUTE_RE = re.compile(
    r"^\s*(ROUTE|RETRY|RETRIEVE|COMPOSE|STYLE|TEMPORAL|LAYOUT|REPLACE|ANALYZE|PUBLISH)"
    r"(?:\s*=\s*(.*))?\s*$",
    re.IGNORECASE,
)


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * p)))
    return ordered[index]


def parse_final(text: str, tool_calls: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    if tool_calls:
        routes: list[dict[str, str]] = []
        invalid: list[str] = []
        if len(tool_calls) != 1:
            invalid.append(f"tool_call_count={len(tool_calls)}")
        for call in tool_calls:
            function = call.get("function") or {}
            if function.get("name") != "plan_media_route":
                invalid.append(f"unexpected_tool={function.get('name')}")
                continue
            arguments = function.get("arguments") or {}
            if isinstance(arguments, str):
                try:
                    arguments = json.loads(arguments)
                except json.JSONDecodeError:
                    invalid.append("invalid_tool_arguments")
                    continue
            for step in arguments.get("steps") or []:
                family = str(step.get("family") or "").strip().upper()
                instruction = str(step.get("instruction") or "").strip()
                if not family:
                    invalid.append("tool_step_without_family")
                    continue
                routes.append({"family": family, "instruction": instruction, "raw": json.dumps(step, ensure_ascii=False)})
        return {
            "lines": [],
            "routes": routes,
            "families": [route["family"] for route in routes],
            "conversation": None,
            "invalid_lines": invalid,
            "protocol_clean": not invalid and bool(routes) and not text.strip(),
        }
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    routes: list[dict[str, str]] = []
    invalid: list[str] = []
    conversation: str | None = None
    for line in lines:
        match = ROUTE_RE.fullmatch(line)
        if match:
            fields = [part.strip() for part in (match.group(2) or "").split("|")]
            routes.append(
                {
                    "family": match.group(1),
                    "instruction": fields[0] if fields and fields[0] else "",
                    "raw": line,
                }
            )
        elif line.startswith(("REPLY=", "QUESTION=")) and conversation is None:
            conversation = line
        else:
            invalid.append(line)
    protocol_clean = not invalid and bool(lines) and not (routes and conversation)
    return {
        "lines": lines,
        "routes": routes,
        "families": [route["family"] for route in routes],
        "conversation": conversation,
        "invalid_lines": invalid,
        "protocol_clean": protocol_clean,
    }


def family_has_literal_evidence(family: str, query: str) -> bool:
    lower = query.lower()
    terms = {
        "RETRIEVE": ("找", "搜索", "相册", "选择", "我的", "find", "search", "from album"),
        "COMPOSE": ("拼", "拼接", "拼图", "拼贴", "模板", "模版", "版式", "堆叠", "竖排", "上下排列", "collage", "compose", "stack", "transition", "转场"),
        "STYLE": ("滤镜", "调色", "颜色", "色调", "黑白", "灰度", "暖", "冷", "复古", "鲜艳", "提亮", "锐化", "降噪", "美颜", "虚化", "模糊", "动感", "光效", "贴纸", "边框", "水印", "filter", "style", "color", "tone", "bright", "sharpen", "denoise", "beauty", "blur", "sticker", "watermark"),
        "TEMPORAL": ("裁剪", "截取", "剪成", "trim", "倒放", "reverse", "变速", "加速", "慢放", "speed", "镜像", "翻转", "反转", "mirror", "flip", "转场", "transition", "缩放效果", "时间线", "换封面", "设置封面", "display frame"),
        "LAYOUT": ("交换", "换位置", "布局", "版式", "换模板", "换模版", "swap", "layout"),
        "REPLACE": ("替换素材", "换成", "换掉", "更换素材", "replace"),
        "ANALYZE": ("摘要", "识别", "内容", "主色", "配色", "色板", "取色", "xdr", "summary", "analyze", "palette", "dominant"),
    }
    return family in {"RETRIEVE", "PUBLISH"} or any(term in lower for term in terms.get(family, ()))


def parse_app_compatible(text: str, query: str) -> dict[str, Any]:
    routes: list[dict[str, str]] = []
    for line in text.splitlines():
        match = APP_ROUTE_RE.fullmatch(line)
        if not match:
            continue
        key = match.group(1).upper()
        parts = (match.group(2) or "").split("|")
        if key in {"ROUTE", "RETRY"}:
            family = parts[0].strip().upper() if parts else ""
            offset = 1
        else:
            family = key
            offset = 0
        if family not in {"RETRIEVE", "COMPOSE", "STYLE", "TEMPORAL", "LAYOUT", "REPLACE", "ANALYZE", "PUBLISH"}:
            continue
        if len(parts) - offset not in range(0, 4) or not family_has_literal_evidence(family, query):
            continue
        instruction = parts[offset].strip() if len(parts) > offset else ""
        if family == "RETRIEVE" and not instruction:
            continue
        route = {"family": family, "instruction": instruction, "raw": line.strip()}
        if not routes or (routes[-1]["family"], routes[-1]["instruction"]) != (family, instruction):
            routes.append(route)
    if routes:
        return {"routes": routes, "families": [route["family"] for route in routes], "conversation": None}
    conversation = next(
        (line.strip() for line in text.splitlines() if line.strip().startswith(("QUESTION=", "REPLY="))),
        None,
    )
    return {"routes": [], "families": [], "conversation": conversation}


def score_case(case: dict[str, Any], response_path: Path) -> dict[str, Any]:
    row: dict[str, Any] = {
        "case_id": case["case_id"],
        "query": case["query"],
        "response_path": str(response_path),
    }
    if not response_path.is_file():
        return {**row, "success": False, "error": "missing_response"}
    try:
        response = json.loads(response_path.read_text())
        choice = response["choices"][0]
        message = choice["message"]
    except (json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
        return {**row, "success": False, "error": f"invalid_response:{exc}"}

    final_text = str(message.get("content") or "").strip()
    reasoning = str(message.get("reasoning_content") or "").strip()
    tool_calls = message.get("tool_calls") or []
    parsed = parse_final(final_text, tool_calls)
    if case.get("output_mode") == "tool_call" and case.get("conversation") and not tool_calls:
        parsed = {
            "lines": [final_text] if final_text else [],
            "routes": [],
            "families": [],
            "conversation": final_text or None,
            "invalid_lines": [],
            "protocol_clean": bool(final_text),
        }
    families = parsed["families"]
    family_set = set(families)
    must = set(case.get("must") or [])
    forbid = set(case.get("forbid") or [])
    if case.get("conversation"):
        outcome_ok = bool(parsed["conversation"]) and not families
    else:
        outcome_ok = bool(families) and parsed["conversation"] is None
    family_ok = must <= family_set and not (forbid & family_set)
    retrieve = next(
        (route["instruction"] for route in parsed["routes"] if route["family"] == "RETRIEVE"),
        "",
    )
    subject = case.get("retrieval_subject")
    retrieval_ok = not subject or str(subject).lower() in retrieve.lower()
    no_duplicates = len(families) == len(set(families))
    semantic_success = bool(outcome_ok and family_ok and retrieval_ok and no_duplicates)
    success = bool(parsed["protocol_clean"] and semantic_success)
    app_parsed = (
        parsed
        if tool_calls or case.get("output_mode") == "tool_call"
        else parse_app_compatible(final_text, case["query"])
    )
    app_families = app_parsed["families"]
    app_family_set = set(app_families)
    app_retrieve = next(
        (route["instruction"] for route in app_parsed["routes"] if route["family"] == "RETRIEVE"),
        "",
    )
    app_outcome_ok = (
        bool(app_parsed["conversation"]) and not app_families
        if case.get("conversation")
        else bool(app_families) and app_parsed["conversation"] is None
    )
    app_success = bool(
        app_outcome_ok
        and must <= app_family_set
        and not (forbid & app_family_set)
        and (not subject or str(subject).lower() in app_retrieve.lower())
        and (case.get("conversation") or app_families[0] == "RETRIEVE")
    )
    usage = response.get("usage") or {}
    timings = response.get("timings") or {}
    return {
        **row,
        "success": success,
        "semantic_success": semantic_success,
        "app_compatible_success": app_success,
        "error": None,
        "finish_reason": choice.get("finish_reason"),
        "final_output": final_text,
        "tool_calls": tool_calls,
        "reasoning": reasoning,
        "reasoning_chars": len(reasoning),
        "reasoning_budget_forced": "思考预算已到" in reasoning,
        "families": families,
        "app_compatible_families": app_families,
        "retrieval": retrieve,
        "checks": {
            "protocol_clean": parsed["protocol_clean"],
            "outcome": outcome_ok,
            "must_include": must <= family_set,
            "must_not_include": not (forbid & family_set),
            "retrieval_subject": retrieval_ok,
            "no_duplicates": no_duplicates,
        },
        "invalid_lines": parsed["invalid_lines"],
        "prompt_tokens": usage.get("prompt_tokens"),
        "completion_tokens": usage.get("completion_tokens"),
        "cached_tokens": (usage.get("prompt_tokens_details") or {}).get("cached_tokens"),
        "prompt_ms": timings.get("prompt_ms"),
        "predicted_ms": timings.get("predicted_ms"),
        "predicted_tokens_per_second": timings.get("predicted_per_second"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--responses", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text())
    results = [
        score_case(case, args.responses / f"{case['case_id']}.json") for case in manifest
    ]
    scored = [row for row in results if row.get("error") is None]
    predicted_ms = [float(row["predicted_ms"]) for row in scored if row.get("predicted_ms")]
    completion_tokens = [
        float(row["completion_tokens"]) for row in scored if row.get("completion_tokens")
    ]
    report = {
        "summary": {
            "case_count": len(results),
            "scored_count": len(scored),
            "passed_count": sum(row["success"] for row in scored),
            "pass_rate": (
                sum(row["success"] for row in scored) / len(scored) if scored else None
            ),
            "semantic_passed_count": sum(row["semantic_success"] for row in scored),
            "semantic_pass_rate": (
                sum(row["semantic_success"] for row in scored) / len(scored) if scored else None
            ),
            "protocol_clean_count": sum(
                bool((row.get("checks") or {}).get("protocol_clean")) for row in scored
            ),
            "app_compatible_passed_count": sum(
                bool(row.get("app_compatible_success")) for row in scored
            ),
            "app_compatible_pass_rate": (
                sum(bool(row.get("app_compatible_success")) for row in scored) / len(scored)
                if scored else None
            ),
            "reasoning_present_count": sum(row.get("reasoning_chars", 0) > 0 for row in scored),
            "reasoning_budget_forced_count": sum(
                bool(row.get("reasoning_budget_forced")) for row in scored
            ),
            "completion_tokens": {
                "mean": statistics.mean(completion_tokens) if completion_tokens else None,
                "p50": percentile(completion_tokens, 0.50),
                "p95": percentile(completion_tokens, 0.95),
            },
            "generation_ms": {
                "mean": statistics.mean(predicted_ms) if predicted_ms else None,
                "p50": percentile(predicted_ms, 0.50),
                "p95": percentile(predicted_ms, 0.95),
            },
        },
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    return 0 if all(row.get("success") for row in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
