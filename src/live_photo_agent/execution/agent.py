from __future__ import annotations

from ..brain import QwenPlanner
from ..capability import CapabilityLayer, ToolRegistry
from ..config import settings
from ..foundation import LibraryService, MemoryService
from ..foundation.state import FoundationLayer
from ..models import AgentRequest, AgentResponse, ExecutionPlan, ToolResult
from .flow import InputFlowLayer


class LivePhotoAgent:
    """Execution Layer agent orchestrating planner, capability, and foundation."""

    def __init__(self) -> None:
        self.library_service = LibraryService()
        self.planner = QwenPlanner()
        self.tools = ToolRegistry(self.library_service)
        self.memory = MemoryService(settings.memory_file)
        self.capability_layer = CapabilityLayer()
        self.foundation_layer = FoundationLayer()
        self.input_flow_layer = InputFlowLayer()

    def execute(self, request: AgentRequest) -> AgentResponse:
        library_assets = self.library_service.scan_live_photos(request.library_root)
        library_summary = {
            "asset_count": len(library_assets),
            "selected_asset_count": len(request.selected_asset_ids),
        }

        plan = self.planner.create_plan(request, library_summary)

        if plan.need_clarification:
            return self._build_clarification_response(request, plan, library_assets, library_summary)

        plan = self.capability_layer.normalize_plan(plan, request)
        context: dict[str, object] = {
            "library_root": request.library_root,
            "request_text": request.text,
            "assets": library_assets,
            "tool_catalog": self.capability_layer.tool_catalog(),
        }
        tool_results = self._run_tools(plan, context)
        pipeline_trace = self.input_flow_layer.build_trace()
        foundation_state = self.foundation_layer.build_state(
            request=request,
            library_summary=library_summary,
            session_memory_size=self.memory.session_memory_size(),
            multimodal_memory_size=self.memory.multimodal_memory_size(),
        )

        response = AgentResponse(
            plan=plan,
            context=self._serialize_context(context),
            tool_results=tool_results,
            final_response=self._build_final_response(plan, context),
            memory_updates=[],
            review="",
            pipeline_trace=pipeline_trace,
            foundation_state=foundation_state,
        )
        response.review = self.memory.build_review(response)
        response.memory_updates = self.memory.append(request, response)
        return response

    def _build_clarification_response(
        self,
        request: AgentRequest,
        plan: ExecutionPlan,
        library_assets: list[object],
        library_summary: dict[str, object],
    ) -> AgentResponse:
        pipeline_trace = self.input_flow_layer.build_trace()
        foundation_state = self.foundation_layer.build_state(
            request=request,
            library_summary=library_summary,
            session_memory_size=self.memory.session_memory_size(),
            multimodal_memory_size=self.memory.multimodal_memory_size(),
        )

        questions = plan.clarification_questions or [
            "你希望最终输出是三拼视频、单个 live photo 精选，还是仅推荐候选？",
            "你更看重画面风格、人物主体，还是动态效果？",
        ]
        final_response = "需要先澄清需求后再执行。\n" + "\n".join(f"- {question}" for question in questions)
        context = {
            "library_root": request.library_root,
            "request_text": request.text,
            "assets": library_assets,
            "tool_catalog": self.capability_layer.tool_catalog(),
            "clarification_questions": questions,
            "blocking_missing_info": plan.blocking_missing_info,
        }

        response = AgentResponse(
            plan=plan,
            context=self._serialize_context(context),
            tool_results=[],
            final_response=final_response,
            memory_updates=[],
            review="",
            pipeline_trace=pipeline_trace,
            foundation_state=foundation_state,
        )
        response.review = self.memory.build_review(response)
        response.memory_updates = self.memory.append(request, response)
        return response

    def _run_tools(self, plan: ExecutionPlan, context: dict[str, object]) -> list[ToolResult]:
        results: list[ToolResult] = []
        for call in plan.tool_calls:
            results.append(self.tools.execute(call, context))
        return results

    def _build_final_response(self, plan: ExecutionPlan, context: dict[str, object]) -> str:
        summary = dict(context.get("summary", {}))
        matched_ids = summary.get("focus_asset_ids", [])
        matched_text = "、".join(str(asset_id) for asset_id in matched_ids) if matched_ids else "暂无明确候选"
        return (
            f"已完成意图 {plan.intent} 的处理。"
            f"当前优先素材: {matched_text}。"
            f"下一步可基于这些 live photo 继续做封面、精选或导出。"
        )

    def _serialize_context(self, context: dict[str, object]) -> dict[str, object]:
        serialized: dict[str, object] = {}
        for key, value in context.items():
            if key in {"assets", "selected_assets", "matched_assets"}:
                serialized[key] = [asset.model_dump(mode="json") for asset in value]
            else:
                serialized[key] = value
        return serialized
