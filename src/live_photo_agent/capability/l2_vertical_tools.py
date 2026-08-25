from __future__ import annotations

from ..models import LivePhotoAsset, ToolCall, ToolResult


class L2VerticalTools:
    """L2 vertical tools: domain-specific workflows for live photo scenarios."""

    def draft_edit_plan(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._preferred_assets(context)
        draft = {
            "style": call.arguments.get("style", "social_highlight"),
            "asset_count": len(focus_assets),
            "suggestions": [
                "优先选择有动态片段且标签最贴近用户描述的素材",
                "为每个候选 live photo 生成封面建议和一句文案",
                "如果素材数量过多，先筛成 3 到 5 个精选候选",
            ],
        }
        context["edit_plan"] = draft
        return ToolResult(tool=call.tool, success=True, payload=draft)

    def _get_assets(self, context: dict[str, object]) -> list[LivePhotoAsset]:
        return list(context.get("assets", []))

    def _preferred_assets(self, context: dict[str, object]) -> list[LivePhotoAsset]:
        selected = list(context.get("selected_assets", []))
        if selected:
            return selected
        matched = list(context.get("matched_assets", []))
        if matched:
            return matched
        return self._get_assets(context)
