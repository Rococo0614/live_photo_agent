"""Tests for LangSmith tracer integration."""
from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from live_photo_agent.execution.langsmith_trace import (
    LangSmithTracer,
    PipelineTrace,
    get_tracer,
)
from live_photo_agent.models import AgentRequest, ExecutionPlan, ToolCall, ToolName


def test_tracer_writes_local_trace_file(tmp_path: Path, monkeypatch) -> None:
    """Tracer should always write to local JSONL even without LangSmith API key."""
    log_file = tmp_path / "trace.jsonl"
    monkeypatch.setenv("LIVE_PHOTO_AGENT_TRACE_LOG", str(log_file))
    monkeypatch.delenv("LANGSMITH_API_KEY", raising=False)

    tracer = LangSmithTracer()
    assert not tracer.enabled

    request = AgentRequest(
        user_id="u1",
        text="三拼小猫",
        library_root=tmp_path,
    )
    with tracer.start_run(request) as trace:
        trace.add_node("preprocess", {"asset_count": 5}, duration_ms=10)
        trace.add_node("graph_run", {"plan_intent": "smart_collage"}, duration_ms=100)
        trace.final_output = "拼贴完成"

    assert log_file.exists()
    lines = log_file.read_text(encoding="utf-8").strip().split("\n")
    assert len(lines) == 1
    entry = json.loads(lines[0])
    assert entry["user_input"] == "三拼小猫"
    assert entry["final_output"] == "拼贴完成"
    assert len(entry["nodes"]) == 2
    assert entry["nodes"][0]["name"] == "preprocess"
    assert entry["nodes"][1]["name"] == "graph_run"
    assert entry["total_duration_ms"] >= 0


def test_tracer_captures_error_node(tmp_path: Path, monkeypatch) -> None:
    """Tracer should capture an error node when an exception is raised."""
    log_file = tmp_path / "trace.jsonl"
    monkeypatch.setenv("LIVE_PHOTO_AGENT_TRACE_LOG", str(log_file))
    monkeypatch.delenv("LANGSMITH_API_KEY", raising=False)

    tracer = LangSmithTracer()
    request = AgentRequest(
        user_id="u1",
        text="test",
        library_root=tmp_path,
    )

    with pytest.raises(ValueError, match="boom"):
        with tracer.start_run(request) as trace:
            trace.add_node("preprocess", {})
            raise ValueError("boom")

    entry = json.loads(log_file.read_text(encoding="utf-8").strip())
    assert any(n["name"] == "error" for n in entry["nodes"])
    error_node = next(n for n in entry["nodes"] if n["name"] == "error")
    assert "boom" in error_node["error"]


def test_capture_plan_diff_identifies_added_and_removed(tmp_path: Path) -> None:
    """capture_plan_diff should show what normalize added/removed."""
    tracer = LangSmithTracer()
    raw_plan = ExecutionPlan(
        user_goal="三拼小猫",
        intent="smart_collage",
        selected_asset_ids=[],
        required_context=[],
        tool_calls=[
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage",
                arguments={"query": "猫", "k": 100},
            ),
        ],
    )
    normalized_plan = ExecutionPlan(
        user_goal="三拼小猫",
        intent="smart_collage",
        selected_asset_ids=[],
        required_context=[],
        tool_calls=[
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage",
                arguments={"query": "猫", "k": 10},
            ),
        ],
    )
    diff = tracer.capture_plan_diff(raw_plan, normalized_plan)
    # scan_library was added by normalize.
    added_tools = [n["tool"] for n in diff["added_by_normalize"]]
    assert "scan_library" in added_tools
    # smart_collage args changed (k: 100→10), so it appears as both removed and added.
    removed_tools = [n["tool"] for n in diff["removed_by_normalize"]]
    assert "smart_collage" in removed_tools
    assert "smart_collage" in added_tools


def test_get_tracer_returns_singleton() -> None:
    """get_tracer should return the same instance."""
    t1 = get_tracer()
    t2 = get_tracer()
    assert t1 is t2
