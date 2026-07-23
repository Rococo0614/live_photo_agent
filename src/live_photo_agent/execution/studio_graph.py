from __future__ import annotations

from pathlib import Path
from typing import TypedDict

from langgraph.graph import END, StateGraph

from ..models import AgentRequest
from .agent import LivePhotoAgent


class StudioState(TypedDict, total=False):
    user_id: str
    text: str
    library_root: str
    selected_asset_ids: list[str]
    guided_tool_names: list[str]
    input_image_paths: list[str]
    input_video_paths: list[str]
    agent_response: dict[str, object]
    graph_observability: dict[str, object]
    final_response: str
    error: str


def _execute_agent_node(state: StudioState) -> StudioState:
    try:
        request = AgentRequest(
            user_id=str(state.get("user_id", "studio-user")),
            text=str(state.get("text", "")),
            library_root=Path(str(state.get("library_root", "."))).expanduser().resolve(),
            selected_asset_ids=[str(item) for item in state.get("selected_asset_ids", [])],
            guided_tool_names=list(state.get("guided_tool_names", [])),
            input_image_paths=[Path(str(item)).expanduser().resolve() for item in state.get("input_image_paths", [])],
            input_video_paths=[Path(str(item)).expanduser().resolve() for item in state.get("input_video_paths", [])],
        )
        response = LivePhotoAgent().execute(request)
        payload = response.model_dump(mode="json")
        context = payload.get("context", {})
        graph_observability = context.get("graph_observability", {}) if isinstance(context, dict) else {}
        return {
            "agent_response": payload,
            "graph_observability": graph_observability if isinstance(graph_observability, dict) else {},
            "final_response": str(payload.get("final_response", "")),
            "error": "",
        }
    except Exception as exc:  # noqa: BLE001
        return {
            "agent_response": {},
            "graph_observability": {},
            "final_response": "",
            "error": str(exc),
        }


def _build_graph():
    graph_builder = StateGraph(StudioState)
    graph_builder.add_node("execute_agent", _execute_agent_node)
    graph_builder.set_entry_point("execute_agent")
    graph_builder.add_edge("execute_agent", END)
    return graph_builder.compile()


graph = _build_graph()

