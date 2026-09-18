from __future__ import annotations

import logging
from time import perf_counter

from ..brain import QwenPlanner
from ..capability import CapabilityLayer, ToolRegistry
from ..config import settings
from ..foundation import LibraryService, MemoryService
from ..foundation.layout_resolver import LayoutResolver
from ..foundation.state import FoundationLayer
from ..models import AgentRequest, AgentResponse, ExecutionPlan, ToolCall, ToolName, ToolResult
from .flow import InputFlowLayer
from .input_preprocessor import InputPreprocessor
from .langgraph_runner import PlannerGraphRunner, PlannerUnavailableError
from .langsmith_trace import get_tracer

logger = logging.getLogger("live_photo_agent.execution")


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
        request_start = perf_counter()
        logger.info("[TIMER] execute start text=%r", str(request.text)[:80])
        request = self._apply_retry_feedback(request)

        tracer = get_tracer()
        with tracer.start_run(request) as trace:
            preprocess_start = perf_counter()
            prepared_input = self.input_preprocessor.prepare(request, self.library_service)
            preprocess_duration_ms = max(0, int((perf_counter() - preprocess_start) * 1000))
            runtime_request = prepared_input.request
            library_assets = prepared_input.assets
            library_summary = prepared_input.library_summary
            trace.add_node("preprocess", {
                "duration_ms": preprocess_duration_ms,
                "asset_count": len(library_assets),
            }, duration_ms=preprocess_duration_ms)

            memory_suggest_start = perf_counter()
            reusable_strategies = self.memory.suggest_reusable_sequences(runtime_request.text, limit=3)
            memory_suggest_duration_ms = max(0, int((perf_counter() - memory_suggest_start) * 1000))
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
                "layout_context": runtime_request.layout_context,
                "composition_template": LayoutResolver().resolve(
                    runtime_request.layout_context
                ).model_dump(mode="json"),
                "operation_log": runtime_request.operation_log,
                "reusable_strategies": reusable_strategies,
                "retry_of_run_id": request.retry_of_run_id,
                "conversation_history": runtime_request.conversation_history,
            }

            graph_run_start = perf_counter()
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
                logger.error(
                    "[TIMER] planner unavailable after %.1fms: %s",
                    (perf_counter() - graph_run_start) * 1000,
                    exc,
                )
                trace.add_node("planner_unavailable", {
                    "error": str(exc),
                    "duration_ms": max(0, int((perf_counter() - graph_run_start) * 1000)),
                }, error=str(exc))
                return self._build_planner_unavailable_response(
                    request=runtime_request,
                    library_assets=library_assets,
                    library_summary=library_summary,
                    message=str(exc),
                )
            graph_run_duration_ms = max(0, int((perf_counter() - graph_run_start) * 1000))
            logger.info(
                "[TIMER] graph_run (plan + execute tools) elapsed=%dms :: preprocess=%dms memory=%dms total=%dms",
                graph_run_duration_ms,
                preprocess_duration_ms,
                memory_suggest_duration_ms,
                max(0, int((perf_counter() - request_start) * 1000)),
            )

            timings = {
                "total_duration_ms": max(0, int((perf_counter() - request_start) * 1000)),
                "preprocess_duration_ms": preprocess_duration_ms,
                "memory_suggest_duration_ms": memory_suggest_duration_ms,
                "graph_run_duration_ms": graph_run_duration_ms,
                "execution": graph_result.execution_metrics,
            }
            plan = graph_result.plan
            graph_observability = {
                "run_id": graph_result.run_id,
                "route_reason": graph_result.route_reason,
                "trace": graph_result.graph_trace,
                "replay_snapshot": graph_result.replay_snapshot,
                "timings": timings,
            }

            trace.add_node("graph_run", {
                "run_id": graph_result.run_id,
                "route_reason": graph_result.route_reason,
                "plan_intent": plan.intent,
                "plan_tool_sequence": [c.tool.value for c in plan.tool_calls],
                "validation_errors": graph_result.replay_snapshot.get("validation_errors", []),
                "replan_attempts": graph_result.replay_snapshot.get("replan_attempts", 0),
                "execution_metrics": graph_result.execution_metrics,
                "graph_trace": graph_result.graph_trace,
                "duration_ms": graph_run_duration_ms,
            }, duration_ms=graph_run_duration_ms)

            if plan.need_clarification:
                trace.add_node("clarification", {
                    "questions": plan.clarification_questions,
                })
                return self._build_clarification_response(
                    runtime_request,
                    plan,
                    library_assets,
                    library_summary,
                    graph_observability,
                )

            context = graph_result.context
            context["graph_observability"] = graph_observability
            context["timings"] = timings
            context["library_summary"] = library_summary
            tool_results = graph_result.tool_results

            retry_start = perf_counter()
            tool_results = self._retry_on_empty_index(
                plan=plan,
                context=context,
                tool_results=tool_results,
                request=runtime_request,
            )
            retry_duration_ms = max(0, int((perf_counter() - retry_start) * 1000))
            if retry_duration_ms > 0:
                trace.add_node("retry_on_empty_index", {
                    "duration_ms": retry_duration_ms,
                    "tool_results": [
                        {"tool": tr.tool.value, "success": tr.success}
                        for tr in tool_results
                    ],
                }, duration_ms=retry_duration_ms)

            pipeline_trace = self.input_flow_layer.build_trace()
            foundation_state = self.foundation_layer.build_state(
                request=runtime_request,
                library_summary=library_summary,
                session_memory_size=self.memory.session_memory_size(),
                multimodal_memory_size=self.memory.multimodal_memory_size(),
            )

            # Cleanup residual subject_mattes that weren't consumed by overlay.
            self._cleanup_residual_temp_files(context)

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

            trace.final_output = response.final_response
            trace.add_node("response", {
                "final_response": response.final_response,
                "tool_results_count": len(tool_results),
                "success_count": sum(1 for tr in tool_results if tr.success),
            })
            return response

    def _cleanup_residual_temp_files(self, context: dict[str, object]) -> None:
        """Clean up residual temp files after execution.

        - subject_mattes that weren't consumed by overlay_subject_clip
        - key_frames temp dirs
        - decoded live photo temp dirs (if any leaked)
        """
        import shutil
        from pathlib import Path

        # Clean residual subject_mattes (only if overlay wasn't called)
        subject_mattes = context.get("subject_mattes", {})
        if isinstance(subject_mattes, dict):
            for asset_id, matte in subject_mattes.items():
                if not isinstance(matte, dict):
                    continue
                frames_dir = matte.get("frames_dir")
                if frames_dir:
                    p = Path(str(frames_dir))
                    if p.exists():
                        shutil.rmtree(p, ignore_errors=True)

        # Clean residual key_frames temp
        key_frames = context.get("key_frames", {})
        if isinstance(key_frames, dict):
            for asset_id, frames in key_frames.items():
                if not isinstance(frames, list):
                    continue
                for frame_path in frames:
                    p = Path(str(frame_path))
                    if p.exists() and ".agent_work" in str(p):
                        p.unlink(missing_ok=True)
                        # Try to remove parent dir if empty
                        try:
                            p.parent.rmdir()
                        except OSError:
                            pass

    def _retry_on_empty_index(
        self,
        plan: ExecutionPlan,
        context: dict[str, object],
        tool_results: list[ToolResult],
        request: AgentRequest,
    ) -> list[ToolResult]:
        """Retry smart_collage when it fails with empty_index.

        When the asset search index is empty, smart_collage returns
        error_code=no_matching_assets / error=empty_index. This method
        detects that, runs asset_summarize to build the index, then
        re-executes smart_collage once.
        """
        smart_result = next(
            (r for r in tool_results if r.tool == ToolName.SMART_COLLAGE and not r.success),
            None,
        )
        if smart_result is None:
            return tool_results

        payload = smart_result.payload or {}
        error_code = str(payload.get("error_code", ""))
        error = str(payload.get("error", ""))
        if error_code != "no_matching_assets" or error != "empty_index":
            return tool_results

        logger.info("[RETRY] smart_collage failed with empty_index, running asset_summarize...")

        summarize_call = ToolCall(
            tool=ToolName.ASSET_SUMMARIZE,
            reason="Build asset search index after smart_collage empty_index failure.",
            arguments={
                "library_root": str(request.library_root),
                "force_rebuild": True,
            },
        )
        summarize_result = self.tools.execute(summarize_call, context)
        if not summarize_result.success:
            logger.warning("[RETRY] asset_summarize failed: %s", summarize_result.payload)
            return tool_results

        logger.info(
            "[RETRY] asset_summarize succeeded, retrying smart_collage..."
        )

        smart_call = next(
            (call for call in plan.tool_calls if call.tool == ToolName.SMART_COLLAGE),
            None,
        )
        if smart_call is None:
            return tool_results

        retry_result = self.tools.execute(smart_call, context)

        updated_results: list[ToolResult] = []
        for r in tool_results:
            if r.tool == ToolName.SMART_COLLAGE:
                updated_results.append(retry_result)
            else:
                updated_results.append(r)
        return updated_results

    def _apply_retry_feedback(self, request: AgentRequest) -> AgentRequest:
        """Fold a human's rejection comment back into the request text.

        This closes the reject -> redo loop: the UI resubmits the same request
        with ``retry_feedback`` set to the rejection comment (and
        ``retry_of_run_id`` for traceability); the planner then sees the
        feedback as part of the goal text and can adjust the plan accordingly.
        """
        feedback = request.retry_feedback.strip()
        if not feedback:
            return request
        augmented_text = (
            f"{request.text}\n\n[human_feedback_retry]\n"
            f"上一次结果被人工打回，原因：{feedback}\n"
            "请依据反馈调整方案，不要重复相同的问题。"
        )
        return request.model_copy(update={"text": augmented_text})

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
        intent = plan.intent or ""

        # 对话类意图: 不需要工具, 用 planner 模型生成自然语言回复
        if intent in ("conversation", "chat", "answer_question") or not plan.tool_calls:
            user_text = str(context.get("request_text", ""))
            try:
                library_summary = {
                    "asset_count": context.get("library_summary", {}).get("asset_count", "未知"),
                    "motion_ready_asset_count": context.get("library_summary", {}).get("motion_ready_asset_count", "未知"),
                    "conversation_history": context.get("conversation_history", []),
                }
                response = self.planner.chat(user_text, library_summary)
                return response if response else f"我收到了你的消息:「{user_text}」。"
            except Exception as exc:
                logger.warning("Planner chat failed: %s", exc)
                return f"我收到了你的消息:「{user_text}」。如果你想做拼贴，试试输入「三拼小猫」。"

        # 工具执行后的回复
        summary = dict(context.get("summary", {}))
        matched_ids = summary.get("focus_asset_ids", [])
        matched_text = "、".join(str(asset_id) for asset_id in matched_ids) if matched_ids else "暂无明确候选"

        # 检查 tool_results 里有没有 final_video
        tool_results_raw = context.get("tool_results", [])
        final_video = None
        asset_count = None
        error_msg = None
        for tr in tool_results_raw:
            if isinstance(tr, dict):
                payload = tr.get("payload", {})
            else:
                payload = getattr(tr, "payload", {})
            if isinstance(payload, dict):
                if payload.get("final_video"):
                    final_video = payload["final_video"]
                if payload.get("asset_count") is not None:
                    asset_count = payload["asset_count"]
                if payload.get("message"):
                    error_msg = payload["message"]
                elif payload.get("error") and not payload.get("final_video"):
                    error_msg = payload["error"]

        if final_video:
            return f"拼贴完成！视频已生成: {final_video}"
        if error_msg:
            return error_msg
        if asset_count is not None:
            return f"素材库扫描完成，共 {asset_count} 张素材。"
        return (
            f"已完成意图 {intent} 的处理。"
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
