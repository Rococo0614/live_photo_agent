import json
from pathlib import Path

from live_photo_agent.foundation.memory import MemoryService
from live_photo_agent.models import AgentRequest, AgentResponse, ExecutionPlan, ToolCall, ToolName, ToolResult


def _build_response(
    intent: str,
    need_clarification: bool = False,
    tool_results: list[ToolResult] | None = None,
    run_id: str = "",
) -> AgentResponse:
    plan = ExecutionPlan(
        user_goal="goal",
        intent=intent,
        selected_asset_ids=[],
        required_context=[],
        need_clarification=need_clarification,
        tool_calls=[
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(tool=ToolName.SEARCH_BY_TEXT, reason="search", arguments={"query": "三拼"}),
            ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={}),
            ToolCall(tool=ToolName.EXPORT_MP4, reason="export", arguments={}),
        ],
    )
    context: dict[str, object] = {
        "summary": {"focus_asset_ids": ["1", "2", "3"]},
        "timeline": {"composition": "triptych_portrait"},
    }
    if run_id:
        context["graph_observability"] = {"run_id": run_id, "route_reason": "executed"}
    return AgentResponse(
        plan=plan,
        context=context,
        tool_results=tool_results or [],
        final_response="ok",
        memory_updates=[],
        review="",
    )


def test_memory_records_strategy_and_suggests(tmp_path: Path) -> None:
    memory_file = tmp_path / "memory.json"
    service = MemoryService(memory_file)
    request = AgentRequest(
        user_id="u1",
        text="帮我做三拼导出",
        library_root=tmp_path,
        selected_asset_ids=[],
    )
    response = _build_response("triptych_export", need_clarification=False)

    service.append(request, response)

    state = json.loads(memory_file.read_text(encoding="utf-8"))
    assert "strategy_memory" in state
    assert len(state["strategy_memory"]) == 1
    item = state["strategy_memory"][0]
    assert item["accept_count"] == 1
    assert item["run_count"] == 1

    hints = service.suggest_reusable_sequences("我要做一个三拼视频", limit=2)
    assert hints
    assert hints[0]["tool_sequence"][0] == "scan_library"
    assert hints[0]["concat_composition"] == "triptych_portrait"


def test_memory_does_not_count_clarification_as_accepted(tmp_path: Path) -> None:
    memory_file = tmp_path / "memory.json"
    service = MemoryService(memory_file)
    request = AgentRequest(
        user_id="u1",
        text="随便做点什么",
        library_root=tmp_path,
        selected_asset_ids=[],
    )
    response = _build_response("unclear_intent", need_clarification=True)

    service.append(request, response)

    state = json.loads(memory_file.read_text(encoding="utf-8"))
    item = state["strategy_memory"][0]
    assert item["accept_count"] == 0
    assert item["run_count"] == 1


def test_deliverable_run_defers_memory_until_human_feedback(tmp_path: Path) -> None:
    """Turns that actually executed tools must not touch strategy/asset memory

    until a human explicitly confirms acceptance via confirm_feedback.
    """
    memory_file = tmp_path / "memory.json"
    service = MemoryService(memory_file)
    request = AgentRequest(
        user_id="u1",
        text="帮我做三拼导出",
        library_root=tmp_path,
        selected_asset_ids=[],
    )
    tool_results = [ToolResult(tool=ToolName.SCAN_LIBRARY, success=True, payload={})]
    response = _build_response("triptych_export", tool_results=tool_results, run_id="run-1")

    updates = service.append(request, response)
    assert updates

    state = json.loads(memory_file.read_text(encoding="utf-8"))
    assert state.get("strategy_memory", []) == []
    assert state.get("multimodal_memory", []) == []
    session_entry = state["session_memory"][0]
    assert session_entry["accepted"] is None
    assert session_entry["requires_feedback"] is True
    assert session_entry["run_id"] == "run-1"

    result = service.confirm_feedback(accepted=True, run_id="run-1", comment="很满意")
    assert result["recorded"] is True
    assert result["committed_to_memory"] is True

    state = json.loads(memory_file.read_text(encoding="utf-8"))
    assert len(state["multimodal_memory"]) == 1
    assert len(state["strategy_memory"]) == 1
    strategy = state["strategy_memory"][0]
    assert strategy["run_count"] == 1
    assert strategy["accept_count"] == 1
    session_entry = state["session_memory"][0]
    assert session_entry["accepted"] is True
    assert session_entry["feedback_comment"] == "很满意"


def test_rejected_deliverable_does_not_land_in_asset_memory(tmp_path: Path) -> None:
    memory_file = tmp_path / "memory.json"
    service = MemoryService(memory_file)
    request = AgentRequest(
        user_id="u1",
        text="帮我做三拼导出",
        library_root=tmp_path,
        selected_asset_ids=[],
    )
    tool_results = [ToolResult(tool=ToolName.SCAN_LIBRARY, success=True, payload={})]
    response = _build_response("triptych_export", tool_results=tool_results, run_id="run-2")

    service.append(request, response)
    result = service.confirm_feedback(accepted=False, run_id="run-2", comment="素材选错了")
    assert result["committed_to_memory"] is False

    state = json.loads(memory_file.read_text(encoding="utf-8"))
    assert state.get("multimodal_memory", []) == []
    strategy = state["strategy_memory"][0]
    assert strategy["run_count"] == 1
    assert strategy["accept_count"] == 0

    # A retry that resubmits with feedback and is later accepted should now commit.
    retry_request = request.model_copy(update={"retry_feedback": "素材选错了", "retry_of_run_id": "run-2"})
    retry_response = _build_response("triptych_export", tool_results=tool_results, run_id="run-3")
    service.append(retry_request, retry_response)
    service.confirm_feedback(accepted=True, run_id="run-3", comment="这次可以")

    state = json.loads(memory_file.read_text(encoding="utf-8"))
    assert len(state["multimodal_memory"]) == 1
    strategy = state["strategy_memory"][0]
    assert strategy["run_count"] == 2
    assert strategy["accept_count"] == 1

