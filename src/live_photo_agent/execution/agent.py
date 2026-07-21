from __future__ import annotations

from ..brain import QwenPlanner
from ..capability import CapabilityLayer, ToolRegistry
from ..config import settings
from ..foundation import LibraryService, MemoryService
from ..foundation.state import FoundationLayer
from ..models import AgentRequest, AgentResponse, ExecutionPlan
from .flow import InputFlowLayer
from .input_preprocessor import InputPreprocessor
from .langgraph_runner import PlannerGraphRunner, PlannerUnavailableError


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
        self.input_preprocessor = InputPreprocessor()
        self.graph_runner = PlannerGraphRunner(max_replans=1)

    def execute(self, request: AgentRequest) -> AgentResponse:
        prepared_input = self.input_preprocessor.prepare(request, self.library_service)
        runtime_request = prepared_input.request
        library_assets = prepared_input.assets
        library_summary = prepared_input.library_summary
        reusable_strategies = self.memory.suggest_reusable_sequences(runtime_request.text, limit=3)
        if reusable_strategies:
            library_summary = {
                **library_summary,
                "reusable_strategies": reusable_strategies,
            }

        base_context: dict[str, object] = {
            "library_root": runtime_request.library_root,
            "request_text": runtime_request.text,
            "assets": library_assets,
            "tool_catalog": self.capability_layer.tool_catalog(),
            "preprocess": prepared_input.preprocess_info,
            "reusable_strategies": reusable_strategies,
        }

        try:
            graph_result = self.graph_runner.run(
                request=runtime_request,
                library_summary=library_summary,
                base_context=base_context,
                planner=self.planner,
                capability=self.capability_layer,
                tools=self.tools,
            )
        except PlannerUnavailableError as exc:
            return self._build_planner_unavailable_response(
                request=runtime_request,
                library_assets=library_assets,
                library_summary=library_summary,
                message=str(exc),
            )
        plan = graph_result.plan
        graph_observability = {
            "run_id": graph_result.run_id,
            "route_reason": graph_result.route_reason,
            "trace": graph_result.graph_trace,
            "replay_snapshot": graph_result.replay_snapshot,
        }

        if plan.need_clarification:
            return self._build_clarification_response(
                runtime_request,
                plan,
                library_assets,
                library_summary,
                graph_observability,
            )

        context = graph_result.context
        context["graph_observability"] = graph_observability
        tool_results = graph_result.tool_results
        pipeline_trace = self.input_flow_layer.build_trace()
        foundation_state = self.foundation_layer.build_state(
            request=runtime_request,
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
        response.memory_updates = self.memory.append(runtime_request, response)
        return response

    def _build_planner_unavailable_response(
        self,
        request: AgentRequest,
        library_assets: list[object],
        library_summary: dict[str, object],
        message: str,
    ) -> AgentResponse:
        pipeline_trace = self.input_flow_layer.build_trace()
        foundation_state = self.foundation_layer.build_state(
            request=request,
            library_summary=library_summary,
            session_memory_size=self.memory.session_memory_size(),
            multimodal_memory_size=self.memory.multimodal_memory_size(),
        )

        plan = ExecutionPlan(
            user_goal=request.text,
            intent="planner_unavailable",
            selected_asset_ids=list(request.selected_asset_ids),
            required_context=["planner_backend_config"],
            need_clarification=True,
            clarification_questions=[
                "请先配置规划后端：设置 LPA_QWEN_ENDPOINT（远端）或 LPA_LOCAL_MODEL_DIR（本地模型）后再重试。"
            ],
            blocking_missing_info=[message],
            tool_calls=[],
        )

        context = {
            "library_root": request.library_root,
            "request_text": request.text,
            "assets": library_assets,
            "tool_catalog": self.capability_layer.tool_catalog(),
            "planner_error": {
                "code": "planner_unavailable",
                "message": message,
            },
        }

        response = AgentResponse(
            plan=plan,
            context=self._serialize_context(context),
            tool_results=[],
            final_response=(
                "规划大脑当前不可用，未执行任何工具。请先配置 LPA_QWEN_ENDPOINT 或 LPA_LOCAL_MODEL_DIR 后再试。"
            ),
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
        graph_observability: dict[str, object],
    ) -> AgentResponse:
        pipeline_trace = self.input_flow_layer.build_trace()
        foundation_state = self.foundation_layer.build_state(
            request=request,
            library_summary=library_summary,
            session_memory_size=self.memory.session_memory_size(),
            multimodal_memory_size=self.memory.multimodal_memory_size(),
        )

        questions = [str(item) for item in plan.clarification_questions]
        if questions:
            final_response = "需要先澄清需求后再执行。\n" + "\n".join(f"- {question}" for question in questions)
        else:
            final_response = "需要先澄清需求后再执行。请补充关键信息后我再继续。"
        context = {
            "library_root": request.library_root,
            "request_text": request.text,
            "assets": library_assets,
            "tool_catalog": self.capability_layer.tool_catalog(),
            "clarification_questions": questions,
            "blocking_missing_info": plan.blocking_missing_info,
            "graph_observability": graph_observability,
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
