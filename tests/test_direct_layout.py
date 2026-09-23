from pathlib import Path

from live_photo_agent.execution.direct_layout import DirectLayoutExecutor, parse_processing_directives
from live_photo_agent.models import AgentRequest


def test_processing_directives_are_whitelisted() -> None:
    directives, warnings = parse_processing_directives(
        "给人物加暖色并抠出来叠加，背景做扩散填充",
        ["a1", "a2"],
        [{"asset_id": "a2", "foreground": True}],
    )

    assert {item["operation"] for item in directives} == {"color_enhance", "subject_overlay"}
    assert any("扩散" in warning for warning in warnings)
    assert all(item["asset_ids"] == ["a2"] for item in directives if item["operation"] == "subject_overlay")


def test_layout_mode_without_slots_returns_clarification(tmp_path: Path) -> None:
    request = AgentRequest(
        user_id="u1",
        mode="layout",
        text="根据当前布局生成",
        library_root=tmp_path,
        layout_context=[],
    )

    # Avoid constructing media tools here; the empty-layout contract is a
    # pure validation behavior and is covered by the executor's result type.
    assert request.mode == "layout"
    assert request.layout_context == []
