import json
from pathlib import Path

from live_photo_agent.foundation.memory import MemoryService
from live_photo_agent.models import AgentRequest, AgentResponse, ExecutionPlan, ToolCall, ToolName


def _build_response(intent: str, need_clarification: bool = False) -> AgentResponse:
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
    return AgentResponse(
        plan=plan,
        context={
            "summary": {"focus_asset_ids": ["1", "2", "3"]},
            "timeline": {"composition": "triptych_portrait"},
        },
        tool_results=[],
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
