from __future__ import annotations

from ..models import PipelineStage, PipelineTrace


class InputFlowLayer:
    """Execution Layer: input -> intent -> context -> recommendation -> tool selection -> output."""

    def build_trace(self) -> list[PipelineTrace]:
        return [
            PipelineTrace(stage=PipelineStage.INPUT, detail="Received text request and normalized library/media inputs."),
            PipelineTrace(stage=PipelineStage.INTENT, detail="Planner interpreted user intent from text."),
            PipelineTrace(stage=PipelineStage.CONTEXT, detail="Prepared merged asset context from library and explicit media paths."),
            PipelineTrace(stage=PipelineStage.RECOMMEND, detail="Planner generated recommendation-focused execution plan."),
            PipelineTrace(stage=PipelineStage.TOOL_SELECTION, detail="Capability layer normalized tool order and arguments."),
            PipelineTrace(stage=PipelineStage.OUTPUT, detail="Executed tools and produced final response with review."),
        ]
