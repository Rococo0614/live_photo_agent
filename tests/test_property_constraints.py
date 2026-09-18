"""Property-based tests for constraint rules using Hypothesis.

These tests don't use fixed cases — instead, Hypothesis generates random
plans and verifies that the normalization + validation invariants always hold.
This catches edge cases that hand-written tests miss.
"""
from __future__ import annotations

from pathlib import Path

import pytest
from hypothesis import HealthCheck, given, settings, strategies as st

from live_photo_agent.capability.contracts import CapabilityLayer
from live_photo_agent.execution.langgraph_runner import PlannerGraphRunner
from live_photo_agent.models import (
    AgentRequest,
    ExecutionPlan,
    ToolCall,
    ToolName,
)

# ---------------------------------------------------------------------------
# Strategies: describe how to generate random ToolCall / ExecutionPlan values
# ---------------------------------------------------------------------------

# Only the tools that a planner would realistically emit (skip internal-only
# ones like filter_selected which depends on request state).
PLANNER_TOOLS = [
    ToolName.SCAN_LIBRARY,
    ToolName.SEARCH_BY_TEXT,
    ToolName.EXTRACT_KEY_FRAMES,
    ToolName.SELECT_COVER_FRAME,
    ToolName.CLIP_TRIM,
    ToolName.CLIP_SPEED,
    ToolName.COLOR_ENHANCE,
    ToolName.CONCAT_CLIPS,
    ToolName.ADD_TEXT_OVERLAY,
    ToolName.MIX_AUDIO_BGM,
    ToolName.EXPORT_MP4,
    ToolName.EXTRACT_SUBJECT_MATTE,
    ToolName.OVERLAY_SUBJECT_CLIP,
    ToolName.SMART_COLLAGE,
    ToolName.TEMPLATE_COLLAGE,
    ToolName.LIVE_PHOTO_COLLAGE,
    ToolName.SUMMARIZE_RESULTS,
    ToolName.ASSET_SUMMARIZE,
]

L2_TOOLS = [ToolName.SMART_COLLAGE, ToolName.TEMPLATE_COLLAGE, ToolName.LIVE_PHOTO_COLLAGE]

# Arguments that can appear on L2 collage tools.
L2_ARGUMENT_STRATEGY = st.fixed_dictionaries(
    {},
    optional={
        "query": st.text(min_size=0, max_size=20),
        "k": st.integers(min_value=-5, max_value=100),
        "asset_paths": st.lists(st.text(min_size=1, max_size=10), min_size=0, max_size=5),
        "layout_type": st.sampled_from(["vertical", "horizontal", "grid", "invalid", "auto"]),
        "output_dir": st.sampled_from([
            "",
            "output",
            "/home/vivo/live_photo_agent/data/live_photo/output",
            "/tmp/test_output",
            ".agent_work/custom",
        ]),
    },
)

GENERIC_ARGUMENT_STRATEGY = st.fixed_dictionaries(
    {},
    optional={
        "asset_ids": st.lists(st.text(min_size=1, max_size=5), min_size=0, max_size=3),
        "text": st.text(min_size=0, max_size=20),
        "library_root": st.text(min_size=0, max_size=20),
    },
)


@st.composite
def tool_call_strategy(draw):
    """Generate a random ToolCall with semi-appropriate arguments."""
    tool = draw(st.sampled_from(PLANNER_TOOLS))
    if tool in L2_TOOLS:
        args = draw(L2_ARGUMENT_STRATEGY)
    else:
        args = draw(GENERIC_ARGUMENT_STRATEGY)
    return ToolCall(tool=tool, reason="generated", arguments=args)


@st.composite
def plan_strategy(draw):
    """Generate a random ExecutionPlan with 0-8 tool calls."""
    num_calls = draw(st.integers(min_value=0, max_value=8))
    calls = [draw(tool_call_strategy()) for _ in range(num_calls)]
    intent = draw(st.sampled_from([
        "collage", "smart_collage", "triptych_export",
        "subject_overlay_composite", "conversation", "chat",
        "answer_question", "unknown",
    ]))
    return ExecutionPlan(
        user_goal="generated plan",
        intent=intent,
        selected_asset_ids=[],
        required_context=[],
        tool_calls=calls,
    )


# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------

def _make_request(tmp_path: Path) -> AgentRequest:
    return AgentRequest(
        user_id="u1",
        text="三拼小猫",
        library_root=tmp_path,
        selected_asset_ids=[],
    )


# ---------------------------------------------------------------------------
# Property tests
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def layer() -> CapabilityLayer:
    return CapabilityLayer()


@pytest.fixture(scope="module")
def runner() -> PlannerGraphRunner:
    return PlannerGraphRunner()


class TestNormalizeInvariants:
    """Properties that must hold for ANY plan after normalize_plan."""

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_scan_library_is_first_when_present(self, tmp_path: Path, layer, plan):
        """If scan_library appears anywhere, it must be first after normalize."""
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        sequence = [c.tool for c in normalized.tool_calls]
        if ToolName.SCAN_LIBRARY in sequence:
            assert sequence[0] == ToolName.SCAN_LIBRARY

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_l2_tools_mutually_exclusive(self, tmp_path: Path, layer, plan):
        """At most one L2 collage tool after normalize."""
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        sequence = [c.tool for c in normalized.tool_calls]
        l2_present = [t for t in L2_TOOLS if t in sequence]
        assert len(l2_present) <= 1

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_smart_collage_k_in_range(self, tmp_path: Path, layer, plan):
        """smart_collage k must be in [2, 10] after normalize."""
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        for call in normalized.tool_calls:
            if call.tool == ToolName.SMART_COLLAGE:
                k = call.arguments.get("k", 3)
                assert 2 <= int(k) <= 10

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_output_dir_under_agent_work(self, tmp_path: Path, layer, plan):
        """All L2 tool output_dir must point to .agent_work after normalize."""
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        for call in normalized.tool_calls:
            if call.tool in L2_TOOLS or call.tool == ToolName.ASSET_SUMMARIZE:
                output_dir = str(call.arguments.get("output_dir", ""))
                assert ".agent_work" in output_dir, (
                    f"output_dir={output_dir} not under .agent_work"
                )

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_summarize_results_is_last(self, tmp_path: Path, layer, plan):
        """summarize_results must be the last call after normalize."""
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        sequence = [c.tool for c in normalized.tool_calls]
        if ToolName.SUMMARIZE_RESULTS in sequence:
            assert sequence[-1] == ToolName.SUMMARIZE_RESULTS

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_export_mp4_preceded_by_concat(self, tmp_path: Path, layer, plan):
        """export_mp4 must be preceded by concat_clips after normalize."""
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        sequence = [c.tool for c in normalized.tool_calls]
        if ToolName.EXPORT_MP4 in sequence:
            assert ToolName.CONCAT_CLIPS in sequence
            assert sequence.index(ToolName.CONCAT_CLIPS) < sequence.index(ToolName.EXPORT_MP4)

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_overlay_preceded_by_concat_and_matte(self, tmp_path: Path, layer, plan):
        """overlay_subject_clip must be preceded by both concat and matte."""
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        sequence = [c.tool for c in normalized.tool_calls]
        if ToolName.OVERLAY_SUBJECT_CLIP in sequence:
            assert ToolName.CONCAT_CLIPS in sequence
            assert ToolName.EXTRACT_SUBJECT_MATTE in sequence
            assert sequence.index(ToolName.CONCAT_CLIPS) < sequence.index(ToolName.OVERLAY_SUBJECT_CLIP)
            assert sequence.index(ToolName.EXTRACT_SUBJECT_MATTE) < sequence.index(ToolName.OVERLAY_SUBJECT_CLIP)

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_no_duplicate_tools_after_normalize(self, tmp_path: Path, layer, plan):
        """No tool should appear more than once after normalize (except none)."""
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        sequence = [c.tool for c in normalized.tool_calls]
        # L0 tools can legitimately appear once. Duplicates indicate a bug.
        from collections import Counter
        counts = Counter(sequence)
        for tool, count in counts.items():
            assert count <= 1, f"Tool {tool} appears {count} times after normalize"


class TestValidationInvariants:
    """Properties that must hold for validation gate."""

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_conversation_intent_with_tools_is_error(self, tmp_path: Path, runner, plan):
        """conversation/chat/answer_question intent + tool_calls → validation error."""
        if not plan.tool_calls:
            return
        if plan.intent not in ("conversation", "chat", "answer_question"):
            return
        errors = runner._collect_validation_errors(_make_request(tmp_path), plan)
        assert any("conversation" in e for e in errors)

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_multiple_l2_tools_is_error(self, tmp_path: Path, layer, runner, plan):
        """Two or more L2 tools in a plan → validation error after normalize."""
        # Normalize first (as the real pipeline does), then validate.
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        sequence = [c.tool for c in normalized.tool_calls]
        l2_present = [t for t in L2_TOOLS if t in sequence]
        if len(l2_present) <= 1:
            return
        errors = runner._collect_validation_errors(_make_request(tmp_path), normalized)
        assert any("互斥" in e for e in errors)

    @given(plan=plan_strategy())
    @settings(max_examples=200, deadline=2000,
               suppress_health_check=[HealthCheck.function_scoped_fixture])
    def test_smart_collage_empty_query_is_error(self, tmp_path: Path, layer, runner, plan):
        """smart_collage with empty query → validation error after normalize."""
        # Skip if intent is conversation/chat (conversation error masks query error).
        if plan.intent in ("conversation", "chat", "answer_question"):
            return
        smart_calls = [c for c in plan.tool_calls if c.tool == ToolName.SMART_COLLAGE]
        if not smart_calls:
            return
        if str(smart_calls[0].arguments.get("query", "")).strip():
            return
        # Skip if another L2 tool appears before smart_collage (normalize will
        # remove smart_collage via mutual exclusion, so query check won't fire).
        l2_before_smart = [
            c for c in plan.tool_calls
            if c.tool in L2_TOOLS and plan.tool_calls.index(c) < plan.tool_calls.index(smart_calls[0])
        ]
        if l2_before_smart:
            return
        # Normalize first (as the real pipeline does), then validate.
        normalized = layer.normalize_plan(plan, _make_request(tmp_path))
        normalized.intent = "smart_collage"  # Force non-conversation intent.
        if not normalized.tool_calls or normalized.tool_calls[0].tool != ToolName.SCAN_LIBRARY:
            return
        # If smart_collage was removed by L2 mutual exclusion, skip.
        if ToolName.SMART_COLLAGE not in [c.tool for c in normalized.tool_calls]:
            return
        errors = runner._collect_validation_errors(_make_request(tmp_path), normalized)
        assert any("smart_collage" in e and "query" in e for e in errors)
