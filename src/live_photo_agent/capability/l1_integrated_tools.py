from __future__ import annotations

from ..foundation.library import LibraryService
from ..models import LivePhotoAsset, ToolCall, ToolResult


class L1IntegratedTools:
    """L1 integrated tools: combine base context into reusable workflows."""

    def __init__(self, library_service: LibraryService) -> None:
        self.library_service = library_service

    def search_by_text(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        assets = self._get_assets(context)
        query = str(call.arguments.get("query", ""))
        matches = self.library_service.search_assets(assets, query)
        context["matched_assets"] = matches
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={"match_count": len(matches), "matched_ids": [asset.asset_id for asset in matches[:20]]},
        )

    def summarize_results(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._preferred_assets(context)
        summary = {
            "response_style": call.arguments.get("response_style", "concise"),
            "focus_asset_ids": [asset.asset_id for asset in focus_assets[:5]],
            "focus_asset_count": len(focus_assets),
        }
        context["summary"] = summary
        return ToolResult(tool=call.tool, success=True, payload=summary)

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
