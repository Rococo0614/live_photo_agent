"""DeepEval regression test set for the planner + constraint pipeline.

This tests that the FULL pipeline (planner → normalize → validation)
produces plans that satisfy all constraints, for a set of typical user inputs.

Since calling the real LLM planner in CI is slow and flaky, we use a set of
"golden" expected plans (what a well-behaved planner should output) and verify
that normalize + validation accept them. We also test "adversarial" plans
(what a misbehaving planner might output) and verify that normalize + validation
catch them.

Run with:
    python -m pytest tests/test_deepeval_planner_regression.py -v

Or with the deepeval CLI:
    deepeval test run tests/test_deepeval_planner_regression.py
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from deepeval import assert_test
from deepeval.metrics import BaseMetric
from deepeval.test_case import LLMTestCase, ToolCall as DeepEvalToolCall

from live_photo_agent.capability.contracts import CapabilityLayer
from live_photo_agent.execution.langgraph_runner import PlannerGraphRunner
from live_photo_agent.models import AgentRequest, ExecutionPlan, ToolCall, ToolName


# ---------------------------------------------------------------------------
# Custom DeepEval Metrics
# ---------------------------------------------------------------------------

class ConstraintSatisfactionMetric(BaseMetric):
    """Metric: Does the normalized plan satisfy all hard constraints?"""

    def __init__(self) -> None:
        self.threshold = 1.0  # 100% satisfaction required

    def measure(self, test_case: LLMTestCase) -> float:
        plan = test_case.metadata["normalized_plan"]
        request = test_case.metadata["request"]
        runner = test_case.metadata["runner"]

        errors = runner._collect_validation_errors(request, plan)
        self.score = 1.0 if not errors else 0.0
        self.reason = f"Validation errors: {errors}" if errors else "All constraints satisfied."
        self.errors = errors
        return self.score

    async def a_measure(self, test_case: LLMTestCase) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return self.score >= self.threshold

    @property
    def __name__(self) -> str:
        return "ConstraintSatisfaction"


class ScanLibraryFirstMetric(BaseMetric):
    """Metric: Is scan_library the first tool in the normalized plan?"""

    def __init__(self) -> None:
        self.threshold = 1.0

    def measure(self, test_case: LLMTestCase) -> float:
        plan = test_case.metadata["normalized_plan"]
        if not plan.tool_calls:
            self.score = 1.0  # Empty plans (conversation) are fine.
            self.reason = "Empty plan, no scan_library needed."
            return self.score
        sequence = [c.tool for c in plan.tool_calls]
        if sequence[0] == ToolName.SCAN_LIBRARY:
            self.score = 1.0
            self.reason = "scan_library is first."
        else:
            self.score = 0.0
            self.reason = f"scan_library is not first: {[t.value for t in sequence]}"
        return self.score

    async def a_measure(self, test_case: LLMTestCase) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return self.score >= self.threshold

    @property
    def __name__(self) -> str:
        return "ScanLibraryFirst"


class L2MutualExclusionMetric(BaseMetric):
    """Metric: At most one L2 collage tool in the normalized plan."""

    def __init__(self) -> None:
        self.threshold = 1.0

    def measure(self, test_case: LLMTestCase) -> float:
        plan = test_case.metadata["normalized_plan"]
        l2_tools = {ToolName.SMART_COLLAGE, ToolName.TEMPLATE_COLLAGE, ToolName.LIVE_PHOTO_COLLAGE}
        sequence = [c.tool for c in plan.tool_calls]
        l2_present = [t for t in l2_tools if t in sequence]
        if len(l2_present) <= 1:
            self.score = 1.0
            self.reason = f"L2 tools: {l2_present or 'none'}"
        else:
            self.score = 0.0
            self.reason = f"Multiple L2 tools: {[t.value for t in l2_present]}"
        return self.score

    async def a_measure(self, test_case: LLMTestCase) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return self.score >= self.threshold

    @property
    def __name__(self) -> str:
        return "L2MutualExclusion"


class SmartCollageParamsMetric(BaseMetric):
    """Metric: smart_collage has valid query and k in [2,10]."""

    def __init__(self) -> None:
        self.threshold = 1.0

    def measure(self, test_case: LLMTestCase) -> float:
        plan = test_case.metadata["normalized_plan"]
        smart_calls = [c for c in plan.tool_calls if c.tool == ToolName.SMART_COLLAGE]
        if not smart_calls:
            self.score = 1.0
            self.reason = "No smart_collage in plan."
            return self.score
        smart = smart_calls[0]
        query = str(smart.arguments.get("query", "")).strip()
        k = int(smart.arguments.get("k", 3))
        issues = []
        if not query:
            issues.append("query is empty")
        if k < 2 or k > 10:
            issues.append(f"k={k} out of [2,10]")
        if issues:
            self.score = 0.0
            self.reason = "; ".join(issues)
        else:
            self.score = 1.0
            self.reason = f"query='{query}', k={k}"
        return self.score

    async def a_measure(self, test_case: LLMTestCase) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return self.score >= self.threshold

    @property
    def __name__(self) -> str:
        return "SmartCollageParams"


# ---------------------------------------------------------------------------
# Test cases: golden (should pass) and adversarial (should be caught)
# ---------------------------------------------------------------------------

GOLDEN_CASES = [
    {
        "name": "三拼小猫_smart_collage",
        "input": "三拼小猫",
        "intent": "smart_collage",
        "tool_calls": [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="search and collage",
                arguments={"query": "猫", "k": 3},
            ),
        ],
    },
    {
        "name": "左右拼_template_collage",
        "input": "把这三张左右拼一下",
        "intent": "template_collage",
        "tool_calls": [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.TEMPLATE_COLLAGE,
                reason="collage",
                arguments={"asset_paths": ["/a.mp4", "/b.mp4", "/c.mp4"], "layout_type": "horizontal"},
            ),
        ],
    },
    {
        "name": "导出视频_triptych",
        "input": "做一个三拼导出",
        "intent": "triptych_export",
        "tool_calls": [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={}),
            ToolCall(tool=ToolName.EXPORT_MP4, reason="export", arguments={}),
        ],
    },
    {
        "name": "聊天对话_conversation",
        "input": "你好",
        "intent": "conversation",
        "tool_calls": [],
    },
    {
        "name": "抠图叠加_overlay",
        "input": "把第一张的人物动态抠出来贴到后面三张拼接的画面上",
        "intent": "subject_overlay_composite",
        "tool_calls": [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(tool=ToolName.CONCAT_CLIPS, reason="build bg", arguments={}),
            ToolCall(tool=ToolName.EXTRACT_SUBJECT_MATTE, reason="extract fg", arguments={}),
            ToolCall(
                tool=ToolName.OVERLAY_SUBJECT_CLIP,
                reason="overlay",
                arguments={"foreground_asset_id": "1"},
            ),
        ],
    },
]

ADVERSARIAL_CASES = [
    {
        "name": "missing_scan_library",
        "input": "三拼小猫",
        "intent": "smart_collage",
        "tool_calls": [
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage without scan",
                arguments={"query": "猫", "k": 3},
            ),
        ],
        "should_pass_normalize": True,  # normalize will insert scan_library
        "should_pass_validation": True,
    },
    {
        "name": "k_out_of_range",
        "input": "三拼小猫",
        "intent": "smart_collage",
        "tool_calls": [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage",
                arguments={"query": "猫", "k": 100},
            ),
        ],
        "should_pass_normalize": True,  # normalize will clamp k to 10
        "should_pass_validation": True,
    },
    {
        "name": "empty_query",
        "input": "三拼小猫",
        "intent": "smart_collage",
        "tool_calls": [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage",
                arguments={"query": "", "k": 3},
            ),
        ],
        "should_pass_normalize": True,
        "should_pass_validation": False,  # validation should catch empty query
    },
    {
        "name": "two_l2_tools",
        "input": "三拼小猫",
        "intent": "collage",
        "tool_calls": [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="first collage",
                arguments={"query": "猫", "k": 3},
            ),
            ToolCall(
                tool=ToolName.TEMPLATE_COLLAGE,
                reason="second collage",
                arguments={"asset_paths": ["/a.mp4"]},
            ),
        ],
        "should_pass_normalize": True,  # normalize removes one L2 tool
        "should_pass_validation": True,  # after normalize, only one L2 tool
    },
    {
        "name": "conversation_with_tools",
        "input": "你好",
        "intent": "conversation",
        "tool_calls": [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
        ],
        "should_pass_normalize": True,
        "should_pass_validation": False,  # conversation + tools = contradiction
    },
    {
        "name": "export_without_concat",
        "input": "导出",
        "intent": "export",
        "tool_calls": [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(tool=ToolName.EXPORT_MP4, reason="export", arguments={}),
        ],
        "should_pass_normalize": True,  # normalize inserts concat
        "should_pass_validation": True,
    },
]


# ---------------------------------------------------------------------------
# Pytest fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def layer() -> CapabilityLayer:
    return CapabilityLayer()


@pytest.fixture(scope="module")
def runner() -> PlannerGraphRunner:
    return PlannerGraphRunner()


def _build_test_case(
    case: dict[str, Any],
    tmp_path: Path,
    layer: CapabilityLayer,
    runner: PlannerGraphRunner,
) -> LLMTestCase:
    """Build a DeepEval LLMTestCase from a golden/adversarial case."""
    request = AgentRequest(
        user_id="u1",
        text=case["input"],
        library_root=tmp_path,
        selected_asset_ids=[],
    )
    raw_plan = ExecutionPlan(
        user_goal=case["input"],
        intent=case["intent"],
        selected_asset_ids=[],
        required_context=[],
        tool_calls=case["tool_calls"],
    )
    normalized_plan = layer.normalize_plan(raw_plan, request)

    # Convert to DeepEval tool calls for display.
    deepeval_calls = [
        DeepEvalToolCall(name=c.tool.value, input=str(c.arguments))
        for c in normalized_plan.tool_calls
    ]

    return LLMTestCase(
        input=case["input"],
        actual_output=str([c.tool.value for c in normalized_plan.tool_calls]),
        tools_called=deepeval_calls,
        metadata={
            "request": request,
            "raw_plan": raw_plan,
            "normalized_plan": normalized_plan,
            "runner": runner,
            "case": case,
        },
    )


# ---------------------------------------------------------------------------
# Golden case tests (all metrics should pass)
# ---------------------------------------------------------------------------

class TestGoldenCases:
    """Well-behaved planner outputs should pass all constraints."""

    @pytest.mark.parametrize(
        "case",
        GOLDEN_CASES,
        ids=[c["name"] for c in GOLDEN_CASES],
    )
    def test_golden_case_satisfies_all_constraints(
        self, tmp_path: Path, layer, runner, case,
    ) -> None:
        test_case = _build_test_case(case, tmp_path, layer, runner)
        metrics = [
            ConstraintSatisfactionMetric(),
            ScanLibraryFirstMetric(),
            L2MutualExclusionMetric(),
            SmartCollageParamsMetric(),
        ]
        assert_test(test_case, metrics)


# ---------------------------------------------------------------------------
# Adversarial case tests (normalize should fix, validation should catch)
# ---------------------------------------------------------------------------

class TestAdversarialCases:
    """Misbehaving planner outputs should be handled correctly."""

    @pytest.mark.parametrize(
        "case",
        ADVERSARIAL_CASES,
        ids=[c["name"] for c in ADVERSARIAL_CASES],
    )
    def test_adversarial_case_handled_correctly(
        self, tmp_path: Path, layer, runner, case,
    ) -> None:
        test_case = _build_test_case(case, tmp_path, layer, runner)
        request = test_case.metadata["request"]
        normalized_plan = test_case.metadata["normalized_plan"]

        errors = runner._collect_validation_errors(request, normalized_plan)

        if case["should_pass_validation"]:
            assert not errors, (
                f"Case '{case['name']}' should pass validation but got errors: {errors}"
            )
        else:
            assert errors, (
                f"Case '{case['name']}' should fail validation but passed."
            )
