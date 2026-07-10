from __future__ import annotations

from ..config import settings
from ..models import AgentRequest, FoundationState


class FoundationLayer:
    """Foundation Layer: versioning, asset state, session memory, multimodal memory."""

    pipeline_version = "v0.4-three-layer-layout"

    def build_state(
        self,
        request: AgentRequest,
        library_summary: dict[str, object],
        session_memory_size: int,
        multimodal_memory_size: int,
    ) -> FoundationState:
        _ = request
        return FoundationState(
            pipeline_version=self.pipeline_version,
            planner_model=settings.qwen_model,
            asset_count=int(library_summary.get("asset_count", 0)),
            selected_asset_count=int(library_summary.get("selected_asset_count", 0)),
            session_memory_size=session_memory_size,
            multimodal_memory_size=multimodal_memory_size,
        )
