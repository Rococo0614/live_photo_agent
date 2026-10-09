"""Dialog State Tracking (DST) for multi-turn conversation.

Maintains structured state across turns and injects it into the LLM planner.
The LLM itself decides whether to reuse previous assets — no regex, no
hardcoded intent rules. Language variation is absorbed by the model.

State flow:
  Turn 1: "三拼小猫" → intent=smart_collage, query=小猫
          → tool_results: search_results=[cat_1, cat_2, cat_3], template=T07
          → DST saves: last_asset_ids, last_template_id, last_query, assignment

  Turn 2: "换个模板" (or any phrasing)
          → DST injects full summary into planner context
          → LLM sees previous assets + template and decides:
              * reuse assets, change template → template_id + assignment
              * reuse assets, replace one slot → replace_slot_index + replace_query
              * fresh request → plain query search
"""
from __future__ import annotations

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
    template_history: list[str] = field(default_factory=list)
    last_assignment: list[dict[str, Any]] = field(default_factory=list)
    last_recommendations: list[dict[str, Any]] = field(default_factory=list)
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
                if self.last_template_id and self.last_template_id not in self.template_history:
                    self.template_history.append(self.last_template_id)

            assignment = payload.get("assignment", [])
            if isinstance(assignment, list):
                self.last_assignment = [
                    dict(item) for item in assignment if isinstance(item, dict)
                ]

            recommendations = payload.get("recommendations", [])
            if isinstance(recommendations, list):
                self.last_recommendations = [
                    dict(item) for item in recommendations if isinstance(item, dict)
                ]

            # Extract final_video
            if payload.get("final_video"):
                self.last_final_video = payload["final_video"]

    def to_summary(self) -> dict[str, Any]:
        """Full summary for injecting into planner context.

        Includes everything the LLM needs to decide whether to reuse the
        previous result: assets, template, assignment, recommendations.
        """
        return {
            "last_intent": self.last_intent,
            "last_query": self.last_query,
            "last_asset_ids": self.last_asset_ids,
            "last_template_id": self.last_template_id,
            "last_template_name": self.last_template_name,
            "template_history": self.template_history,
            "last_assignment": self.last_assignment,
            "last_recommendations": self.last_recommendations,
            "last_final_video": self.last_final_video,
            "turn_count": self.turn_count,
        }
