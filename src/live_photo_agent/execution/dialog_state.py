"""Dialog State Tracking (DST) for multi-turn conversation.

Maintains structured state across turns so the planner doesn't need to
"understand" conversation history — it receives pre-filled slots instead.

State flow:
  Turn 1: "三拼小猫" → intent=smart_collage, query=小猫
          → tool_results: search_results=[cat_1, cat_2, cat_3], template=T07
          → DST saves: last_asset_ids, last_template_id, last_query, last_intent

  Turn 2: "换个模板" → refine detector fires
          → DST injects: selected_asset_ids=[cat_1, cat_2, cat_3]
          → planner sees: "换个模板" + selected_asset_ids already filled
          → planner skips search, goes straight to template_collage
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any


@dataclass
class DialogState:
    """Structured dialog state, persisted across turns within a session."""

    last_intent: str = ""
    last_query: str = ""
    last_asset_ids: list[str] = field(default_factory=list)
    last_template_id: str = ""
    last_template_name: str = ""
    last_final_video: str = ""
    last_search_results: list[dict[str, Any]] = field(default_factory=list)
    turn_count: int = 0

    def update_from_results(self, plan_intent: str, tool_results: list[dict[str, Any]]) -> None:
        """Update state after a turn completes, extracting asset_ids and template from tool_results."""
        self.last_intent = plan_intent
        self.turn_count += 1

        for tr in tool_results:
            payload = tr.get("payload", {})
            if not isinstance(payload, dict):
                continue

            # Extract asset_ids from search_results
            search_results = payload.get("search_results", [])
            if search_results:
                self.last_search_results = search_results
                self.last_asset_ids = [r.get("asset_id", "") for r in search_results if r.get("asset_id")]

            # Extract query from selected_template or payload
            selected = payload.get("selected_template", {})
            if isinstance(selected, dict):
                self.last_template_id = selected.get("id", "")
                self.last_template_name = selected.get("name", "")

            # Extract final_video
            if payload.get("final_video"):
                self.last_final_video = payload["final_video"]

    def is_refine(self, text: str) -> bool:
        """Detect if the user wants to refine the previous result rather than start fresh."""
        if self.turn_count == 0 or not self.last_asset_ids:
            return False
        return is_refine_intent(text)

    def get_inherited_asset_ids(self) -> list[str]:
        """Return asset_ids from the previous turn (for refine intents)."""
        return list(self.last_asset_ids) if self.last_asset_ids else []

    def to_summary(self) -> dict[str, Any]:
        """Compact summary for injecting into planner context."""
        return {
            "last_intent": self.last_intent,
            "last_query": self.last_query,
            "last_asset_ids": self.last_asset_ids,
            "last_template_id": self.last_template_id,
            "last_template_name": self.last_template_name,
            "last_final_video": self.last_final_video,
            "turn_count": self.turn_count,
        }


# Refine intent patterns — user wants to modify previous result, not start fresh
_REFINE_PATTERNS = [
    # 换模板/换布局
    r"换.{0,4}(模板|布局|排版|样式|风格)",
    r"改.{0,4}(模板|布局|排版|样式|风格)",
    r"变.{0,4}(模板|布局|排版|样式|风格)",
    # 换一个/再来一个
    r"换.{0,2}(个|一)",
    r"再来.{0,2}(一|个)",
    r"重新",
    # 用这些/用刚才的
    r"用.{0,4}(这些|刚才|上面|之前|上次)",
    # 特定模板名
    r"用.{0,6}(模板|布局|排版)",
    # "不要这个" / "换掉"
    r"不要.{0,4}(这个|这种|这)",
    r"换掉",
    # "改成竖排/横排" 等
    r"改成?.{0,6}(竖|横|上下|左右|三格|四格)",
    # "第X个" (referring to a specific result)
    r"第[一二三四五六七八九十\d]+个",
]

_REFINE_REGEX = re.compile("|".join(_REFINE_PATTERNS))


def is_refine_intent(text: str) -> bool:
    """Check if the user's text indicates a refine/modify intent."""
    return bool(_REFINE_REGEX.search(text))
