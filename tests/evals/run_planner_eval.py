from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import torch
from transformers import AutoModel, AutoTokenizer

ROOT_DIR = Path(__file__).resolve().parents[2]
SRC_DIR = ROOT_DIR / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from live_photo_agent.brain import QwenPlanner
from live_photo_agent.capability.contracts import CapabilityLayer
from live_photo_agent.execution.langgraph_runner import PlannerGraphRunner
from live_photo_agent.models import AgentRequest, ExecutionPlan


def _mean_pool(last_hidden_state: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
    mask = attention_mask.unsqueeze(-1).expand(last_hidden_state.size()).float()
    summed = torch.sum(last_hidden_state * mask, dim=1)
    counts = torch.clamp(mask.sum(dim=1), min=1e-9)
    return summed / counts


def _stage_a_input(user_text: str, raw_intent: str) -> str:
    return (
        f"user_request: {user_text}\n"
        f"planner_intent: {raw_intent}\n"
        "task: map to the closest canonical intent label"
    )


class StageAIntentMapper:
    def __init__(
        self,
        cases: list[dict[str, Any]],
        model_dir: Path,
        batch_size: int,
    ) -> None:
        self.model_dir = model_dir
        self.batch_size = batch_size
        self.device = torch.device("cpu")
        self.tokenizer = AutoTokenizer.from_pretrained(str(model_dir), trust_remote_code=True)
        self.model = AutoModel.from_pretrained(str(model_dir), trust_remote_code=True).to(self.device)
        self.labels, self.centroids = self._build_centroids(cases)

    def _encode_texts(self, texts: list[str]) -> torch.Tensor:
        outputs: list[torch.Tensor] = []

        self.model.eval()
        with torch.no_grad():
            for start in range(0, len(texts), self.batch_size):
                batch = texts[start : start + self.batch_size]
                encoded = self.tokenizer(
                    batch,
                    padding=True,
                    truncation=True,
                    max_length=512,
                    return_tensors="pt",
                )
                encoded = {k: v.to(self.device) for k, v in encoded.items()}
                out = self.model(**encoded)
                pooled = _mean_pool(out.last_hidden_state, encoded["attention_mask"])
                pooled = torch.nn.functional.normalize(pooled, p=2, dim=1)
                outputs.append(pooled.cpu())

        return torch.cat(outputs, dim=0) if outputs else torch.empty((0, 0))

    def _build_centroids(self, cases: list[dict[str, Any]]) -> tuple[list[str], torch.Tensor]:
        grouped: dict[str, list[str]] = defaultdict(list)
        for row in cases:
            label = str(row.get("expected_intent", "")).strip()
            user_text = str(row.get("user_text", "")).strip()
            if label and user_text:
                grouped[label].append(user_text)

        if not grouped:
            raise ValueError("No labels found in dataset for Stage A intent mapping")

        labels = sorted(grouped.keys())

        # Add intent token as a lightweight anchor for each class.
        for label in labels:
            grouped[label].append(label.replace("_", " "))

        proto_texts: list[str] = []
        proto_owner: list[str] = []
        for label in labels:
            for text in grouped[label]:
                proto_texts.append(text)
                proto_owner.append(label)

        proto_emb = self._encode_texts(proto_texts)

        by_label: dict[str, list[torch.Tensor]] = defaultdict(list)
        for idx, owner in enumerate(proto_owner):
            by_label[owner].append(proto_emb[idx])

        centroids: list[torch.Tensor] = []
        for label in labels:
            stacked = torch.stack(by_label[label], dim=0)
            centroid = torch.nn.functional.normalize(stacked.mean(dim=0), p=2, dim=0)
            centroids.append(centroid)

        return labels, torch.stack(centroids, dim=0)

    def predict(self, user_text: str, raw_intent: str) -> tuple[str, float]:
        text = _stage_a_input(user_text, raw_intent)
        emb = self._encode_texts([text])[0]
        sims = emb @ self.centroids.T
        score, idx = torch.max(sims, dim=0)
        label = self.labels[int(idx.item())]
        return label, float(score.item())


@dataclass(slots=True)
class CaseResult:
    case_id: str
    category: str
    passed: bool
    checks: dict[str, bool]
    compliance_score: float
    quality_score: float
    final_score: float
    strict_passed: bool
    hard_gate_passed: bool
    quality_trusted: bool
    details: dict[str, Any]


@dataclass(slots=True)
class QualityConfig:
    mode: str
    endpoint: str | None
    model: str
    auth_token: str | None
    timeout_seconds: float
    weight: float


@dataclass(slots=True)
class ImageQualityConfig:
    model_name: str
    field_names: list[str]
    weight: float
    device: str


class ImageQualityEvaluator:
    """Optional image IQA scorer using pyiqa backends (musiq/clipiqa)."""

    def __init__(self, config: ImageQualityConfig) -> None:
        self.config = config
        self.enabled = config.model_name in {"musiq", "clipiqa"}
        self._metric: Any = None
        self._init_error: str | None = None

        if not self.enabled:
            return

        try:
            import pyiqa  # type: ignore

            self._metric = pyiqa.create_metric(config.model_name, device=config.device)
        except Exception as exc:  # noqa: BLE001
            self._init_error = f"image_iqa_init_failed: {exc}"
            self.enabled = False

    def evaluate(self, image_paths: list[str]) -> tuple[float | None, dict[str, Any], bool]:
        if not image_paths:
            return None, {"mode": "image_iqa", "reason": "no_image_paths"}, False

        if self._init_error:
            return None, {"mode": "image_iqa", "error": self._init_error}, False

        if self._metric is None:
            return None, {"mode": "image_iqa", "reason": "disabled"}, False

        raw_scores: list[float] = []
        valid_paths: list[str] = []
        for item in image_paths:
            path = Path(item)
            if not path.exists() or not path.is_file():
                continue
            try:
                raw = float(self._metric(str(path)).item())
                raw_scores.append(raw)
                valid_paths.append(str(path))
            except Exception:
                continue

        if not raw_scores:
            return None, {
                "mode": "image_iqa",
                "model": self.config.model_name,
                "reason": "no_valid_image_scores",
            }, False

        normalized_scores = [self._normalize_score(value) for value in raw_scores]
        score = sum(normalized_scores) / len(normalized_scores)
        return max(0.0, min(1.0, score)), {
            "mode": "image_iqa",
            "model": self.config.model_name,
            "raw_scores": [round(value, 6) for value in raw_scores],
            "normalized_scores": [round(value, 6) for value in normalized_scores],
            "scored_images": valid_paths,
        }, True

    def _normalize_score(self, raw: float) -> float:
        model = self.config.model_name
        if model == "musiq":
            # MUSIQ in pyiqa is usually around [0, 100].
            return max(0.0, min(1.0, raw / 100.0))
        # CLIPIQA is usually already close to [0, 1].
        return max(0.0, min(1.0, raw))


class QualityEvaluator:
    def __init__(self, config: QualityConfig) -> None:
        self.config = config

    def evaluate(
        self,
        case: dict[str, Any],
        plan: ExecutionPlan,
        checks: dict[str, bool],
        mapped_intent_score: float,
    ) -> tuple[float, dict[str, Any], bool]:
        mode = self.config.mode
        if mode == "off":
            score, details = self._heuristic_score(checks, mapped_intent_score)
            details["mode"] = "off_heuristic"
            return score, details, False

        if mode == "cloud":
            score, details = self._cloud_score(case, plan, checks, mapped_intent_score)
            if score is not None:
                return score, details, True
            fallback_score, fallback_details = self._heuristic_score(checks, mapped_intent_score)
            fallback_details["mode"] = "cloud_fallback_heuristic"
            fallback_details["fallback_reason"] = details.get("error", "cloud_unavailable")
            return fallback_score, fallback_details, False

        score, details = self._heuristic_score(checks, mapped_intent_score)
        details["mode"] = "heuristic"
        return score, details, False

    def _heuristic_score(self, checks: dict[str, bool], mapped_intent_score: float) -> tuple[float, dict[str, Any]]:
        quality = 0.0
        quality += 0.25 * float(checks.get("intent_match", False))
        quality += 0.20 * float(checks.get("clarification_match", False))
        quality += 0.20 * float(checks.get("tool_order_match", False))
        quality += 0.15 * float(checks.get("must_include_match", False))
        quality += 0.10 * float(checks.get("must_not_include_match", False))
        quality += 0.10 * max(0.0, min(1.0, mapped_intent_score))
        return max(0.0, min(1.0, quality)), {
            "mode": "heuristic",
            "dimensions": {
                "intent_alignment": float(checks.get("intent_match", False)),
                "clarification_correctness": float(checks.get("clarification_match", False)),
                "tool_order_validity": float(checks.get("tool_order_match", False)),
                "must_include_coverage": float(checks.get("must_include_match", False)),
                "boundary_safety": float(checks.get("must_not_include_match", False)),
                "mapped_intent_confidence": round(max(0.0, min(1.0, mapped_intent_score)), 6),
            },
            "rationale": "heuristic_proxy_score",
        }

    def _cloud_score(
        self,
        case: dict[str, Any],
        plan: ExecutionPlan,
        checks: dict[str, bool],
        mapped_intent_score: float,
    ) -> tuple[float | None, dict[str, Any]]:
        if not self.config.endpoint:
            return None, {"error": "missing_quality_endpoint"}
        if not self.config.auth_token:
            return None, {"error": "missing_quality_auth_token"}

        user_text = str(case.get("user_text", ""))
        expected_intent = str(case.get("expected_intent", ""))
        expected_need_clarification = bool(case.get("expected_need_clarification", False))
        actual_tools = [call.tool.value for call in plan.tool_calls]
        payload_obj = {
            "task": "Score planner quality for a live-photo assistant execution plan.",
            "rubric": {
                "goal_alignment": "Does plan satisfy user goal and expected intent semantics?",
                "clarification_quality": "Was clarification behavior appropriate to ambiguity/conflicts?",
                "tool_chain_quality": "Is tool chain coherent, efficient, and non-redundant for the goal?",
                "boundary_awareness": "Does plan stay within allowed capability boundaries?",
            },
            "input": {
                "user_text": user_text,
                "expected_intent": expected_intent,
                "expected_need_clarification": expected_need_clarification,
                "actual_intent": plan.intent,
                "actual_need_clarification": plan.need_clarification,
                "actual_tools": actual_tools,
                "compliance_checks": checks,
                "mapped_intent_score": round(mapped_intent_score, 6),
            },
            "output_schema": {
                "quality_score": "float between 0 and 1",
                "dimensions": {
                    "goal_alignment": "float 0..1",
                    "clarification_quality": "float 0..1",
                    "tool_chain_quality": "float 0..1",
                    "boundary_awareness": "float 0..1",
                },
                "rationale": "short string",
            },
        }

        body = {
            "model": self.config.model,
            "response_format": {"type": "json_object"},
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are a strict but practical evaluator for agent execution quality. "
                        "Return JSON only. Score based on usefulness and quality, not exact wording match."
                    ),
                },
                {
                    "role": "user",
                    "content": json.dumps(payload_obj, ensure_ascii=False),
                },
            ],
        }

        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.config.auth_token}",
        }

        request_obj = Request(
            url=self.config.endpoint,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers=headers,
            method="POST",
        )

        try:
            with urlopen(request_obj, timeout=self.config.timeout_seconds) as response:
                raw_text = response.read().decode("utf-8")
        except (HTTPError, URLError, TimeoutError) as exc:
            return None, {"error": f"quality_cloud_request_failed: {exc}"}

        try:
            decoded = json.loads(raw_text)
            content = ""
            choices = decoded.get("choices") if isinstance(decoded, dict) else None
            if isinstance(choices, list) and choices and isinstance(choices[0], dict):
                message = choices[0].get("message")
                if isinstance(message, dict):
                    content_raw = message.get("content")
                    if isinstance(content_raw, str):
                        content = content_raw
                    elif isinstance(content_raw, list):
                        text_parts = [
                            str(part.get("text", ""))
                            for part in content_raw
                            if isinstance(part, dict) and part.get("type") == "text"
                        ]
                        content = "\n".join(text_parts)
            if not content and isinstance(decoded, dict):
                candidate = decoded.get("output_text") or decoded.get("text") or decoded.get("content")
                if isinstance(candidate, str):
                    content = candidate

            if not content and isinstance(decoded, dict) and "quality_score" in decoded:
                score = float(decoded.get("quality_score", 0.0))
                score = max(0.0, min(1.0, score))
                return score, {
                    "mode": "cloud",
                    "dimensions": decoded.get("dimensions", {}),
                    "rationale": str(decoded.get("rationale", "")),
                }

            parsed = self._parse_json_text(content)
            score = float(parsed.get("quality_score", 0.0))
            score = max(0.0, min(1.0, score))
            return score, {
                "mode": "cloud",
                "dimensions": parsed.get("dimensions", {}),
                "rationale": str(parsed.get("rationale", "")),
            }
        except Exception as exc:  # noqa: BLE001
            return None, {"error": f"quality_cloud_parse_failed: {exc}"}

    def _parse_json_text(self, text: str) -> dict[str, Any]:
        stripped = text.strip()
        if not stripped:
            raise ValueError("empty quality evaluator response content")
        if stripped.startswith("```"):
            lines = [line for line in stripped.splitlines() if not line.startswith("```")]
            stripped = "\n".join(lines).strip()

        try:
            parsed = json.loads(stripped)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass

        start = stripped.find("{")
        if start == -1:
            raise ValueError("quality evaluator response has no JSON object")

        in_string = False
        escaped = False
        depth = 0
        end = -1
        for idx in range(start, len(stripped)):
            ch = stripped[idx]
            if in_string:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == '"':
                    in_string = False
                continue

            if ch == '"':
                in_string = True
                continue
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    end = idx
                    break

        if end == -1:
            raise ValueError("unable to isolate JSON object from quality response")

        candidate = stripped[start : end + 1]
        parsed = json.loads(candidate)
        if not isinstance(parsed, dict):
            raise ValueError("quality evaluator payload must be a JSON object")
        return parsed


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


def evaluate_case(
    case: dict[str, Any],
    plan: ExecutionPlan,
    mapper: StageAIntentMapper,
    quality_evaluator: QualityEvaluator,
    image_quality_evaluator: ImageQualityEvaluator,
    image_quality_weight: float,
    text_quality_weight: float,
    image_paths: list[str],
    compliance_weight: float,
    quality_weight: float,
    final_pass_threshold: float,
) -> CaseResult:
    case_id = str(case.get("case_id", "unknown"))
    category = str(case.get("category", "unknown"))

    expected_intent = str(case.get("expected_intent", ""))
    expected_need_clarification = bool(case.get("expected_need_clarification", False))
    must_include = [str(item) for item in case.get("expected_tools_must_include", [])]
    must_not_include = [str(item) for item in case.get("expected_tools_must_not_include", [])]
    arg_constraints = case.get("expected_argument_constraints", {})
    if not isinstance(arg_constraints, dict):
        arg_constraints = {}
    expected_tool_order = [str(item) for item in case.get("expected_tool_order", [])]
    expected_tool_order_mode = str(case.get("expected_tool_order_mode", "subsequence")).lower()
    if expected_tool_order_mode not in {"subsequence", "exact"}:
        expected_tool_order_mode = "subsequence"

    mapped_intent, mapped_score = mapper.predict(str(case.get("user_text", "")), plan.intent)

    actual_tools = [call.tool.value for call in plan.tool_calls]

    strict_intent_match = (plan.intent == expected_intent)
    mapped_intent_match = (mapped_intent == expected_intent)

    checks: dict[str, bool] = {
        "intent_match": mapped_intent_match,
        "clarification_match": (plan.need_clarification == expected_need_clarification),
        "must_include_match": all(tool in actual_tools for tool in must_include),
        "must_not_include_match": all(tool not in actual_tools for tool in must_not_include),
        "tool_order_match": _match_tool_order(actual_tools, expected_tool_order, expected_tool_order_mode),
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

    strict_passed = all(checks.values())
    compliance_score = sum(1.0 for ok in checks.values() if ok) / max(len(checks), 1)
    text_quality_score, text_quality_details, text_quality_trusted = quality_evaluator.evaluate(case, plan, checks, mapped_score)
    image_quality_score, image_quality_details, image_quality_trusted = image_quality_evaluator.evaluate(image_paths)

    if image_quality_score is None:
        quality_score = text_quality_score
    else:
        quality_score = (text_quality_weight * text_quality_score) + (image_quality_weight * image_quality_score)

    quality_trusted = bool(text_quality_trusted or image_quality_trusted)
    final_score = (compliance_weight * compliance_score) + (quality_weight * quality_score)

    hard_gate_reasons: list[str] = []
    if not checks.get("must_not_include_match", False):
        hard_gate_reasons.append("must_not_include_mismatch")
    if not checks.get("argument_constraints_match", False):
        hard_gate_reasons.append("argument_constraints_mismatch")
    if expected_need_clarification and not checks.get("clarification_match", False):
        hard_gate_reasons.append("clarification_required_but_not_triggered")
    if expected_tool_order_mode == "exact" and not checks.get("tool_order_match", False):
        hard_gate_reasons.append("strict_exact_order_mismatch")

    hard_gate_ok = len(hard_gate_reasons) == 0
    passed = hard_gate_ok and (final_score >= final_pass_threshold)
    details = {
        "expected_intent": expected_intent,
        "actual_intent": plan.intent,
        "mapped_intent": mapped_intent,
        "mapped_intent_score": round(mapped_score, 6),
        "strict_intent_match": strict_intent_match,
        "expected_need_clarification": expected_need_clarification,
        "actual_need_clarification": plan.need_clarification,
        "expected_tools_must_include": must_include,
        "expected_tools_must_not_include": must_not_include,
        "actual_tools": actual_tools,
        "expected_tool_order": expected_tool_order,
        "expected_tool_order_mode": expected_tool_order_mode,
        "argument_constraint_details": constraint_details,
        "clarification_questions": plan.clarification_questions,
        "blocking_missing_info": plan.blocking_missing_info,
        "hard_gate_reasons": hard_gate_reasons,
        "hard_gate_passed": hard_gate_ok,
        "quality_trusted": quality_trusted,
        "quality_eval": {
            "combined_quality_score": round(quality_score, 6),
            "text_component": {
                "weight": text_quality_weight,
                "score": round(text_quality_score, 6),
                "trusted": text_quality_trusted,
                "details": text_quality_details,
            },
            "image_component": {
                "weight": image_quality_weight,
                "score": (round(image_quality_score, 6) if image_quality_score is not None else None),
                "trusted": image_quality_trusted,
                "details": image_quality_details,
                "paths": image_paths,
            },
        },
    }

    return CaseResult(
        case_id=case_id,
        category=category,
        passed=passed,
        checks=checks,
        compliance_score=round(compliance_score, 6),
        quality_score=round(quality_score, 6),
        final_score=round(final_score, 6),
        strict_passed=strict_passed,
        hard_gate_passed=hard_gate_ok,
        quality_trusted=quality_trusted,
        details=details,
    )


def _match_tool_order(actual: list[str], expected: list[str], mode: str) -> bool:
    if mode == "exact":
        return actual == expected
    if not expected:
        return True
    cursor = 0
    for tool in actual:
        if tool == expected[cursor]:
            cursor += 1
            if cursor == len(expected):
                return True
    return False


def _collect_case_image_paths(case: dict[str, Any], field_names: list[str]) -> list[str]:
    paths: list[str] = []
    for field in field_names:
        value = case.get(field)
        if isinstance(value, str) and value.strip():
            paths.append(value.strip())
            continue
        if isinstance(value, list):
            for item in value:
                if isinstance(item, str) and item.strip():
                    paths.append(item.strip())

    # preserve order while deduplicating
    seen: set[str] = set()
    unique: list[str] = []
    for path in paths:
        if path in seen:
            continue
        seen.add(path)
        unique.append(path)
    return unique


def summarize(results: list[CaseResult]) -> dict[str, Any]:
    total = len(results)
    passed = sum(1 for result in results if result.passed)
    strict_passed = sum(1 for result in results if result.strict_passed)
    hard_gate_passed = sum(1 for result in results if result.hard_gate_passed)
    quality_trusted_cases = sum(1 for result in results if result.quality_trusted)
    compliance_avg = (sum(result.compliance_score for result in results) / total) if total else 0.0
    quality_avg = (sum(result.quality_score for result in results) / total) if total else 0.0
    final_avg = (sum(result.final_score for result in results) / total) if total else 0.0
    attempts_total = sum(int(result.details.get("attempt_count", 1)) for result in results)
    result_only_passed = sum(1 for result in results if bool(result.details.get("result_only_passed", False)))

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
                "strict_passed": 0,
                "hard_gate_passed": 0,
                "quality_trusted_cases": 0,
                "compliance_score_sum": 0.0,
                "quality_score_sum": 0.0,
                "final_score_sum": 0.0,
                "checks": {k: 0 for k in result.checks.keys()},
            },
        )
        bucket["total"] += 1
        if result.passed:
            bucket["passed"] += 1
        if result.strict_passed:
            bucket["strict_passed"] += 1
        if result.hard_gate_passed:
            bucket["hard_gate_passed"] += 1
        if result.quality_trusted:
            bucket["quality_trusted_cases"] += 1
        bucket["compliance_score_sum"] += result.compliance_score
        bucket["quality_score_sum"] += result.quality_score
        bucket["final_score_sum"] += result.final_score
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
        bucket["strict_pass_rate"] = (bucket["strict_passed"] / total_cat) if total_cat else 0.0
        bucket["hard_gate_pass_rate"] = (bucket["hard_gate_passed"] / total_cat) if total_cat else 0.0
        bucket["quality_trusted_rate"] = (bucket["quality_trusted_cases"] / total_cat) if total_cat else 0.0
        bucket["compliance_score_avg"] = (bucket["compliance_score_sum"] / total_cat) if total_cat else 0.0
        bucket["quality_score_avg"] = (bucket["quality_score_sum"] / total_cat) if total_cat else 0.0
        bucket["final_score_avg"] = (bucket["final_score_sum"] / total_cat) if total_cat else 0.0
        del bucket["checks"]
        del bucket["compliance_score_sum"]
        del bucket["quality_score_sum"]
        del bucket["final_score_sum"]

    return {
        "total": total,
        "passed": passed,
        "pass_rate": (passed / total if total else 0.0),
        "strict_passed": strict_passed,
        "strict_pass_rate": (strict_passed / total if total else 0.0),
        "hard_gate_passed": hard_gate_passed,
        "hard_gate_pass_rate": (hard_gate_passed / total if total else 0.0),
        "quality_trusted_cases": quality_trusted_cases,
        "quality_untrusted_cases": total - quality_trusted_cases,
        "quality_trusted_rate": (quality_trusted_cases / total if total else 0.0),
        "compliance_score_avg": compliance_avg,
        "quality_score_avg": quality_avg,
        "final_score_avg": final_avg,
        "avg_attempt_count": (attempts_total / total if total else 0.0),
        "result_only_passed": result_only_passed,
        "result_only_pass_rate": (result_only_passed / total if total else 0.0),
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
    parser.add_argument(
        "--intent-model-dir",
        type=Path,
        default=ROOT_DIR / "models" / "bge" / "bge-small-zh-v1.5",
        help="Local embedding model directory for Stage A intent mapping.",
    )
    parser.add_argument(
        "--intent-batch-size",
        type=int,
        default=16,
        help="Batch size used by Stage A intent mapper.",
    )
    parser.add_argument(
        "--quality-mode",
        choices=["off", "heuristic", "cloud"],
        default="cloud",
        help="Quality scoring mode. cloud uses endpoint placeholder evaluator; fallback is heuristic.",
    )
    parser.add_argument(
        "--quality-endpoint",
        type=str,
        default=(os.getenv("LPA_QUALITY_ENDPOINT") or os.getenv("LPA_QWEN_ENDPOINT")),
        help="Cloud quality evaluator endpoint. Default: LPA_QUALITY_ENDPOINT or LPA_QWEN_ENDPOINT.",
    )
    parser.add_argument(
        "--quality-model",
        type=str,
        default=(os.getenv("LPA_QUALITY_MODEL") or os.getenv("LPA_QWEN_MODEL") or "qwen3-omni-flash"),
        help="Cloud quality evaluator model name.",
    )
    parser.add_argument(
        "--quality-auth-token",
        type=str,
        default=(os.getenv("LPA_QUALITY_AUTH_TOKEN") or os.getenv("LPA_QWEN_AUTH_TOKEN")),
        help="Auth token for cloud quality evaluator.",
    )
    parser.add_argument(
        "--quality-timeout-seconds",
        type=float,
        default=30.0,
        help="Timeout for cloud quality evaluator calls.",
    )
    parser.add_argument(
        "--compliance-weight",
        type=float,
        default=0.4,
        help="Weight of compliance score in final score.",
    )
    parser.add_argument(
        "--quality-weight",
        type=float,
        default=0.6,
        help="Weight of quality score in final score.",
    )
    parser.add_argument(
        "--quality-image-model",
        choices=["none", "musiq", "clipiqa"],
        default="none",
        help="Optional image IQA model for JPEG output scoring.",
    )
    parser.add_argument(
        "--quality-image-fields",
        type=str,
        default="result_jpeg_path,output_jpeg_path,jpeg_path",
        help="Comma separated dataset fields containing JPEG output paths for image IQA.",
    )
    parser.add_argument(
        "--quality-image-weight",
        type=float,
        default=0.5,
        help="Weight of image IQA component inside quality score when available.",
    )
    parser.add_argument(
        "--quality-text-weight",
        type=float,
        default=0.5,
        help="Weight of text/planning component inside quality score when image IQA is available.",
    )
    parser.add_argument(
        "--quality-image-device",
        type=str,
        default="cpu",
        help="Device for image IQA model, e.g. cpu or cuda.",
    )
    parser.add_argument(
        "--final-pass-threshold",
        type=float,
        default=0.7,
        help="Final score threshold for pass/fail.",
    )
    parser.add_argument(
        "--result-only-pass",
        action="store_true",
        help="Use quality score only as pass criterion (ignore compliance/final score pass gate).",
    )
    parser.add_argument(
        "--result-score-threshold",
        type=float,
        default=0.75,
        help="Quality score threshold used in result-only pass mode and retry stopping condition.",
    )
    parser.add_argument(
        "--max-quality-retries",
        type=int,
        default=0,
        help="Retry planner generation when quality score is below result-score-threshold.",
    )

    args = parser.parse_args()

    planner = QwenPlanner()
    print(
        json.dumps(
            {
                **planner.runtime_info(),
                "normalize": args.normalize,
                "quality_mode": args.quality_mode,
                "quality_model": args.quality_model,
                "quality_image_model": args.quality_image_model,
                "compliance_weight": args.compliance_weight,
                "quality_weight": args.quality_weight,
                "final_pass_threshold": args.final_pass_threshold,
                "result_only_pass": args.result_only_pass,
                "result_score_threshold": args.result_score_threshold,
                "max_quality_retries": args.max_quality_retries,
            },
            ensure_ascii=False,
        )
    )

    total_weight = args.compliance_weight + args.quality_weight
    if total_weight <= 0:
        raise ValueError("compliance_weight + quality_weight must be > 0")
    compliance_weight = args.compliance_weight / total_weight
    quality_weight = args.quality_weight / total_weight
    max_quality_retries = max(0, args.max_quality_retries)
    result_score_threshold = max(0.0, min(1.0, args.result_score_threshold))
    image_quality_weight = max(0.0, args.quality_image_weight)
    text_quality_weight = max(0.0, args.quality_text_weight)
    quality_component_total = image_quality_weight + text_quality_weight
    if quality_component_total <= 0:
        image_quality_weight = 0.0
        text_quality_weight = 1.0
    else:
        image_quality_weight = image_quality_weight / quality_component_total
        text_quality_weight = text_quality_weight / quality_component_total

    image_field_names = [item.strip() for item in args.quality_image_fields.split(",") if item.strip()]

    cases = load_jsonl(args.dataset)
    if args.max_cases > 0:
        cases = cases[: args.max_cases]

    capability = CapabilityLayer()
    clarification_gate = PlannerGraphRunner(max_replans=1)
    quality_evaluator = QualityEvaluator(
        QualityConfig(
            mode=args.quality_mode,
            endpoint=args.quality_endpoint,
            model=args.quality_model,
            auth_token=args.quality_auth_token,
            timeout_seconds=args.quality_timeout_seconds,
            weight=quality_weight,
        )
    )
    image_quality_evaluator = ImageQualityEvaluator(
        ImageQualityConfig(
            model_name=args.quality_image_model,
            field_names=image_field_names,
            weight=image_quality_weight,
            device=args.quality_image_device,
        )
    )
    mapper = StageAIntentMapper(
        cases=cases,
        model_dir=args.intent_model_dir,
        batch_size=args.intent_batch_size,
    )

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
            best_result: CaseResult | None = None
            attempts: list[dict[str, Any]] = []

            for attempt in range(max_quality_retries + 1):
                plan = planner.create_plan(request, library_summary)
                if args.normalize:
                    plan = capability.normalize_plan(plan, request)
                plan = clarification_gate.apply_clarification_gate(request, plan)
                image_paths = _collect_case_image_paths(case, image_field_names)
                candidate = evaluate_case(
                    case,
                    plan,
                    mapper,
                    quality_evaluator=quality_evaluator,
                    image_quality_evaluator=image_quality_evaluator,
                    image_quality_weight=image_quality_weight,
                    text_quality_weight=text_quality_weight,
                    image_paths=image_paths,
                    compliance_weight=compliance_weight,
                    quality_weight=quality_weight,
                    final_pass_threshold=args.final_pass_threshold,
                )

                attempts.append(
                    {
                        "attempt": attempt + 1,
                        "quality_score": candidate.quality_score,
                        "final_score": candidate.final_score,
                        "hard_gate_passed": candidate.hard_gate_passed,
                        "quality_trusted": candidate.quality_trusted,
                    }
                )

                if best_result is None or candidate.quality_score > best_result.quality_score:
                    best_result = candidate

                if candidate.quality_score >= result_score_threshold:
                    break

            if best_result is None:
                raise RuntimeError("quality retry loop produced no evaluation result")

            result = best_result
            result.details["attempts"] = attempts
            result.details["attempt_count"] = len(attempts)
            result.details["result_score_threshold"] = result_score_threshold

            if args.result_only_pass:
                result_only_passed = result.quality_score >= result_score_threshold
                result.details["pass_mode"] = "result_only"
                result.details["result_only_passed"] = result_only_passed
                result.passed = result_only_passed
            else:
                result.details["pass_mode"] = "hybrid"
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
                    "tool_order_match": False,
                    "argument_constraints_match": False,
                },
                compliance_score=0.0,
                quality_score=0.0,
                final_score=0.0,
                strict_passed=False,
                hard_gate_passed=False,
                quality_trusted=False,
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
                    "passed": result.passed,
                    "strict_passed": result.strict_passed,
                    "compliance_score": result.compliance_score,
                    "quality_score": result.quality_score,
                    "final_score": result.final_score,
                    "hard_gate_passed": result.hard_gate_passed,
                    "quality_trusted": result.quality_trusted,
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
