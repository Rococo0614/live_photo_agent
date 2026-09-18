from pathlib import Path

from live_photo_agent.execution.langgraph_runner import PlannerGraphRunner
from live_photo_agent.models import AgentRequest, ExecutionPlan, ToolCall, ToolName


def _make_request(tmp_path: Path, **kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {
        "user_id": "u1",
        "text": "三拼小猫",
        "library_root": tmp_path,
        "selected_asset_ids": [],
    }
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


def _make_plan(tool_calls: list[ToolCall], intent: str = "collage") -> ExecutionPlan:
    return ExecutionPlan(
        user_goal="test",
        intent=intent,
        selected_asset_ids=[],
        required_context=[],
        tool_calls=tool_calls,
    )


def test_validation_conversation_intent_with_tool_calls_is_error(tmp_path: Path) -> None:
    runner = PlannerGraphRunner()
    request = _make_request(tmp_path)
    plan = _make_plan(
        [ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={})],
        intent="conversation",
    )
    errors = runner._collect_validation_errors(request, plan)
    assert any("conversation" in e for e in errors)


def test_validation_conversation_intent_no_tool_calls_is_ok(tmp_path: Path) -> None:
    runner = PlannerGraphRunner()
    request = _make_request(tmp_path)
    plan = _make_plan([], intent="conversation")
    errors = runner._collect_validation_errors(request, plan)
    assert errors == []


def test_validation_l2_mutual_exclusion_detected(tmp_path: Path) -> None:
    runner = PlannerGraphRunner()
    request = _make_request(tmp_path)
    plan = _make_plan(
        [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage1",
                arguments={"query": "猫", "k": 3},
            ),
            ToolCall(
                tool=ToolName.TEMPLATE_COLLAGE,
                reason="collage2",
                arguments={"asset_paths": ["/a.mp4"]},
            ),
        ]
    )
    errors = runner._collect_validation_errors(request, plan)
    assert any("互斥" in e for e in errors)


def test_validation_smart_collage_empty_query_is_error(tmp_path: Path) -> None:
    runner = PlannerGraphRunner()
    request = _make_request(tmp_path)
    plan = _make_plan(
        [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage",
                arguments={"query": "", "k": 3},
            ),
        ]
    )
    errors = runner._collect_validation_errors(request, plan)
    assert any("smart_collage" in e and "query" in e for e in errors)


def test_validation_relaxed_also_checks_conversation_contradiction(tmp_path: Path) -> None:
    runner = PlannerGraphRunner()
    request = _make_request(tmp_path)
    plan = _make_plan(
        [ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={})],
        intent="chat",
    )
    errors = runner._collect_validation_errors_relaxed(request, plan)
    assert any("conversation" in e for e in errors)


def test_validation_relaxed_l2_mutual_exclusion(tmp_path: Path) -> None:
    runner = PlannerGraphRunner()
    request = _make_request(tmp_path)
    plan = _make_plan(
        [
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage1",
                arguments={"query": "猫", "k": 3},
            ),
            ToolCall(
                tool=ToolName.LIVE_PHOTO_COLLAGE,
                reason="collage2",
                arguments={"asset_paths": ["/a.mp4"]},
            ),
        ]
    )
    errors = runner._collect_validation_errors_relaxed(request, plan)
    assert any("互斥" in e for e in errors)
