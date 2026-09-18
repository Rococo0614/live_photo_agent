from pathlib import Path
from unittest.mock import MagicMock

from live_photo_agent.execution.agent import LivePhotoAgent
from live_photo_agent.models import (
    AgentRequest,
    ExecutionPlan,
    ToolCall,
    ToolName,
    ToolResult,
)


def test_retry_on_empty_index_triggers_summarize_and_retry(tmp_path: Path) -> None:
    agent = LivePhotoAgent()
    call_count = {"summarize": 0, "smart": 0}

    def mock_execute(call: ToolCall, context: dict) -> ToolResult:
        if call.tool == ToolName.ASSET_SUMMARIZE:
            call_count["summarize"] += 1
            return ToolResult(
                tool=call.tool,
                success=True,
                payload={"summarized_count": 5, "updated_index": "db"},
            )
        if call.tool == ToolName.SMART_COLLAGE:
            call_count["smart"] += 1
            return ToolResult(
                tool=call.tool,
                success=True,
                payload={"final_video": "/tmp/final.mp4"},
            )
        return ToolResult(tool=call.tool, success=True, payload={})

    agent.tools = MagicMock()
    agent.tools.execute = mock_execute

    plan = ExecutionPlan(
        user_goal="三拼小猫",
        intent="smart_collage",
        selected_asset_ids=[],
        required_context=[],
        tool_calls=[
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage",
                arguments={"query": "猫", "k": 3},
            ),
        ],
    )
    context: dict[str, object] = {}
    request = AgentRequest(
        user_id="u1",
        text="三拼小猫",
        library_root=tmp_path,
    )
    initial_results = [
        ToolResult(
            tool=ToolName.SMART_COLLAGE,
            success=False,
            payload={"error": "empty_index", "error_code": "no_matching_assets"},
        ),
    ]

    updated = agent._retry_on_empty_index(plan, context, initial_results, request)

    assert call_count["summarize"] == 1
    assert call_count["smart"] == 1
    assert updated[0].success is True
    assert updated[0].payload.get("final_video") == "/tmp/final.mp4"


def test_retry_on_empty_index_skips_when_not_empty_index(tmp_path: Path) -> None:
    agent = LivePhotoAgent()

    def mock_execute(call: ToolCall, context: dict) -> ToolResult:
        return ToolResult(tool=call.tool, success=True, payload={})

    agent.tools = MagicMock()
    agent.tools.execute = mock_execute

    plan = ExecutionPlan(
        user_goal="三拼小猫",
        intent="smart_collage",
        selected_asset_ids=[],
        required_context=[],
        tool_calls=[
            ToolCall(
                tool=ToolName.SMART_COLLAGE,
                reason="collage",
                arguments={"query": "猫", "k": 3},
            ),
        ],
    )
    context: dict[str, object] = {}
    request = AgentRequest(
        user_id="u1",
        text="三拼小猫",
        library_root=tmp_path,
    )
    initial_results = [
        ToolResult(
            tool=ToolName.SMART_COLLAGE,
            success=False,
            payload={"error": "no_results", "error_code": "no_matching_assets"},
        ),
    ]

    updated = agent._retry_on_empty_index(plan, context, initial_results, request)
    assert len(updated) == 1
    assert updated[0].payload.get("error") == "no_results"


def test_retry_on_empty_index_no_smart_collage_returns_unchanged(tmp_path: Path) -> None:
    agent = LivePhotoAgent()

    def mock_execute(call: ToolCall, context: dict) -> ToolResult:
        return ToolResult(tool=call.tool, success=True, payload={})

    agent.tools = MagicMock()
    agent.tools.execute = mock_execute

    plan = ExecutionPlan(
        user_goal="三拼小猫",
        intent="triptych_export",
        selected_asset_ids=[],
        required_context=[],
        tool_calls=[
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
        ],
    )
    context: dict[str, object] = {}
    request = AgentRequest(
        user_id="u1",
        text="三拼小猫",
        library_root=tmp_path,
    )
    initial_results = [
        ToolResult(
            tool=ToolName.SCAN_LIBRARY,
            success=True,
            payload={"asset_count": 5},
        ),
    ]

    updated = agent._retry_on_empty_index(plan, context, initial_results, request)
    assert len(updated) == 1
    assert updated[0].tool == ToolName.SCAN_LIBRARY
