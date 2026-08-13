from pathlib import Path

from live_photo_agent.brain import QwenPlanner
from live_photo_agent.config import settings
from live_photo_agent.models import ExecutionPlan, ToolCall, ToolName
from live_photo_agent.models import AgentRequest
from live_photo_agent.orchestrator import LivePhotoAgent


def test_execute_end_to_end(tmp_path: Path, monkeypatch) -> None:
    library_root = tmp_path / "library"
    library_root.mkdir()
    (library_root / "beach_sunset_01.jpg").write_bytes(b"image")
    (library_root / "beach_sunset_01.mov").write_bytes(b"video")
    (library_root / "city_walk_01.jpg").write_bytes(b"image")

    monkeypatch.setattr(settings, "qwen_endpoint", "http://planner.test/plan")
    monkeypatch.setattr(settings, "album_preprocess_index_file", tmp_path / ".album_preprocess_index.jsonl")
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)

    def fake_call_remote_planner(self, request: AgentRequest, library_summary: dict[str, object]) -> ExecutionPlan:
        return ExecutionPlan(
            user_goal=request.text,
            intent="orchestrate_live_photo_request",
            selected_asset_ids=request.selected_asset_ids,
            required_context=[
                f"asset_count={library_summary.get('asset_count', 0)}",
                f"selected_asset_count={library_summary.get('selected_asset_count', 0)}",
            ],
            tool_calls=[
                ToolCall(
                    tool=ToolName.SCAN_LIBRARY,
                    reason="Model starts from current library inventory.",
                    arguments={"library_root": str(request.library_root)},
                ),
                ToolCall(
                    tool=ToolName.SEARCH_BY_TEXT,
                    reason="Model narrows candidates from user goal.",
                    arguments={"query": request.text},
                ),
                ToolCall(
                    tool=ToolName.DRAFT_EDIT_PLAN,
                    reason="Model drafts curation plan for shortlisted assets.",
                    arguments={"style": "social_highlight"},
                ),
                ToolCall(
                    tool=ToolName.SUMMARIZE_RESULTS,
                    reason="Model finalizes user-facing summary.",
                    arguments={"response_style": "concise"},
                ),
            ],
        )

    monkeypatch.setattr(QwenPlanner, "_call_remote_planner", fake_call_remote_planner)

    agent = LivePhotoAgent()
    request = AgentRequest(
        user_id="demo-user",
        text="帮我找出海边日落的 live photo，并给我一个朋友圈精选建议",
        library_root=library_root,
        selected_asset_ids=[],
        guided_tool_names=[ToolName.SCAN_LIBRARY, ToolName.SEARCH_BY_TEXT, ToolName.SUMMARIZE_RESULTS],
    )

    response = agent.execute(request)

    assert response.plan.intent == "orchestrate_live_photo_request"
    assert response.tool_results
    assert [call.tool.value for call in response.plan.tool_calls] == [
        "scan_library",
        "search_by_text",
        "draft_edit_plan",
        "summarize_results",
    ]
    assert "beach_sunset_01" in response.final_response
    assert response.memory_updates
    timings = response.context.get("timings", {})
    assert isinstance(timings, dict)
    assert int(timings.get("total_duration_ms", -1)) >= 0
    assert int(timings.get("graph_run_duration_ms", -1)) >= 0
    execution = timings.get("execution", {})
    assert isinstance(execution, dict)
    tool_timings = execution.get("tool_timings", [])
    assert isinstance(tool_timings, list)
    assert len(tool_timings) == len(response.plan.tool_calls)