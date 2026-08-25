from pathlib import Path

from live_photo_agent.capability.contracts import CapabilityLayer
from live_photo_agent.models import AgentRequest, ExecutionPlan, ToolCall, ToolName


def test_normalize_plan_applies_sequence_guards(tmp_path: Path) -> None:
    layer = CapabilityLayer()
    request = AgentRequest(
        user_id="u1",
        text="做一个三拼导出",
        library_root=tmp_path,
        selected_asset_ids=[],
    )

    raw = ExecutionPlan(
        user_goal=request.text,
        intent="triptych_export",
        selected_asset_ids=[],
        required_context=[],
        tool_calls=[
            ToolCall(tool=ToolName.EXPORT_MP4, reason="export", arguments={"resolution": "1080x1920"}),
            ToolCall(tool=ToolName.SUMMARIZE_RESULTS, reason="summary", arguments={}),
            ToolCall(tool=ToolName.SELECT_COVER_FRAME, reason="cover", arguments={}),
            ToolCall(tool=ToolName.FILTER_SELECTED, reason="selected", arguments={"selected_asset_ids": []}),
        ],
    )

    normalized = layer.normalize_plan(raw, request)
    sequence = [call.tool for call in normalized.tool_calls]

    assert sequence[0] == ToolName.SCAN_LIBRARY
    assert ToolName.FILTER_SELECTED not in sequence
    assert ToolName.EXTRACT_KEY_FRAMES in sequence
    assert ToolName.CONCAT_CLIPS in sequence
    assert sequence[-1] == ToolName.SUMMARIZE_RESULTS
    assert sequence.index(ToolName.CONCAT_CLIPS) < sequence.index(ToolName.EXPORT_MP4)
    assert sequence.index(ToolName.EXTRACT_KEY_FRAMES) < sequence.index(ToolName.SELECT_COVER_FRAME)


def test_normalize_plan_overrides_concat_layout_from_explicit_user_direction(tmp_path: Path) -> None:
    layer = CapabilityLayer()
    request = AgentRequest(
        user_id="u1",
        text="帮我做一个三拼，左边一个右边两个",
        library_root=tmp_path,
        selected_asset_ids=[],
    )

    raw = ExecutionPlan(
        user_goal=request.text,
        intent="triptych_export",
        selected_asset_ids=[],
        required_context=[],
        tool_calls=[
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(tool=ToolName.CONCAT_CLIPS, reason="compose", arguments={"layout": "triptych_portrait"}),
            ToolCall(tool=ToolName.EXPORT_MP4, reason="export", arguments={"resolution": "1080x1920"}),
        ],
    )

    normalized = layer.normalize_plan(raw, request)
    concat_call = next(call for call in normalized.tool_calls if call.tool == ToolName.CONCAT_CLIPS)
    assert concat_call.arguments["layout"] == "triptych_landscape"


def test_normalize_plan_binds_concat_to_selected_layout_assets(tmp_path: Path) -> None:
    layer = CapabilityLayer()
    request = AgentRequest(
        user_id="u1",
        text="根据当前布局做空间拼接，严格使用坐标",
        library_root=tmp_path,
        selected_asset_ids=["140", "163", "176"],
        layout_context=[
            {"asset_id": "163", "order": 2, "left": 1},
            {"asset_id": "140", "order": 1, "left": 2},
            {"asset_id": "176", "order": 3, "left": 3},
        ],
    )
    raw = ExecutionPlan(
        user_goal=request.text,
        intent="orchestrate_live_photo_request",
        selected_asset_ids=[],
        required_context=[],
        tool_calls=[
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(tool=ToolName.CONCAT_CLIPS, reason="compose", arguments={}),
        ],
    )

    normalized = layer.normalize_plan(raw, request)
    concat_call = next(call for call in normalized.tool_calls if call.tool == ToolName.CONCAT_CLIPS)

    assert concat_call.arguments["asset_ids"] == ["140", "163", "176"]
    assert concat_call.arguments["order"] == ["140", "163", "176"]
    assert concat_call.arguments["layout"] == "triptych_portrait"
    assert concat_call.arguments["canvas"] == "1080x1920"


def test_normalize_plan_inserts_matte_and_concat_before_overlay_subject_clip(tmp_path: Path) -> None:
    layer = CapabilityLayer()
    request = AgentRequest(
        user_id="u1",
        text="把第一张的人物动态抠出来贴到后三张拼接的画面上",
        library_root=tmp_path,
        selected_asset_ids=[],
    )

    raw = ExecutionPlan(
        user_goal=request.text,
        intent="subject_overlay_composite",
        selected_asset_ids=[],
        required_context=[],
        tool_calls=[
            ToolCall(tool=ToolName.SCAN_LIBRARY, reason="scan", arguments={}),
            ToolCall(
                tool=ToolName.OVERLAY_SUBJECT_CLIP,
                reason="paste cutout onto composed background",
                arguments={"foreground_asset_id": "1"},
            ),
        ],
    )

    normalized = layer.normalize_plan(raw, request)
    sequence = [call.tool for call in normalized.tool_calls]

    assert ToolName.CONCAT_CLIPS in sequence
    assert ToolName.EXTRACT_SUBJECT_MATTE in sequence
    assert sequence.index(ToolName.CONCAT_CLIPS) < sequence.index(ToolName.OVERLAY_SUBJECT_CLIP)
    assert sequence.index(ToolName.EXTRACT_SUBJECT_MATTE) < sequence.index(ToolName.OVERLAY_SUBJECT_CLIP)
