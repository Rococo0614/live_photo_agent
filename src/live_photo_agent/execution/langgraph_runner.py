from __future__ import annotations

from dataclasses import dataclass
import json
from datetime import datetime, timezone
from typing import Any, TypedDict
from uuid import uuid4

from ..brain import QwenPlanner
from ..capability.contracts import CapabilityLayer
from ..capability.registry import ToolRegistry
from ..config import settings
from ..models import AgentRequest, ExecutionPlan, ToolName, ToolResult


class PlannerUnavailableError(RuntimeError):
    """Raised when planner backend is unavailable and execution must not proceed."""


@dataclass(slots=True)
class GraphRunResult:
    run_id: str
    plan: ExecutionPlan
    context: dict[str, object]
    tool_results: list[ToolResult]
    route_reason: str
    graph_trace: list[dict[str, object]]
    replay_snapshot: dict[str, object]


class GraphState(TypedDict, total=False):
    run_id: str
    request: AgentRequest
    library_summary: dict[str, object]
    base_context: dict[str, object]
    plan: ExecutionPlan
    validation_errors: list[str]
    clarification_gate_reasons: list[str]
    replan_attempts: int
    replan_feedback: str
    context: dict[str, object]
    tool_results: list[ToolResult]
    route_reason: str
    policy_clarification_mode: str
    policy_tool_constraint_mode: str
    graph_trace: list[dict[str, object]]


class PlannerGraphRunner:
    """Minimal graph-style planner control flow with bounded replan and validation gate."""

    def __init__(self, max_replans: int = 1) -> None:
        self.max_replans = max_replans

    def run(
        self,
        request: AgentRequest,
        library_summary: dict[str, object],
        base_context: dict[str, object],
        planner: QwenPlanner,
        capability: CapabilityLayer,
        tools: ToolRegistry,
    ) -> GraphRunResult:
        self._ensure_planner_ready(planner)
        initial_state: GraphState = {
            "run_id": str(uuid4()),
            "request": request,
            "library_summary": dict(library_summary),
            "base_context": dict(base_context),
            "replan_attempts": 0,
            "replan_feedback": "",
            "validation_errors": [],
            "clarification_gate_reasons": [],
            "tool_results": [],
            "route_reason": "init",
            "policy_clarification_mode": settings.planner_clarification_policy,
            "policy_tool_constraint_mode": settings.planner_tool_constraint_policy,
            "graph_trace": [],
        }
        self._record_trace(initial_state, "run", "start", {"max_replans": self.max_replans})
        try:
            from langgraph.graph import END, StateGraph

            result = self._run_langgraph(
                initial_state=initial_state,
                planner=planner,
                capability=capability,
                tools=tools,
                StateGraph=StateGraph,
                END=END,
            )
        except Exception:  # noqa: BLE001
            self._record_trace(initial_state, "run", "langgraph_unavailable_fallback", {})
            result = self._run_fallback(
                initial_state=initial_state,
                planner=planner,
                capability=capability,
                tools=tools,
            )

        self._persist_run_record(result)
        return result

    def _ensure_planner_ready(self, planner: QwenPlanner) -> None:
        try:
            planner.runtime_info()
        except Exception as exc:  # noqa: BLE001
            raise PlannerUnavailableError(
                "Planner backend is not available. Configure LPA_QWEN_ENDPOINT or LPA_LOCAL_MODEL_DIR before execution."
            ) from exc

    def _run_langgraph(
        self,
        initial_state: GraphState,
        planner: QwenPlanner,
        capability: CapabilityLayer,
        tools: ToolRegistry,
        StateGraph: Any,
        END: Any,
    ) -> GraphRunResult:
        input_graph = self._build_input_subgraph(StateGraph=StateGraph, END=END)
        planning_graph = self._build_planning_subgraph(
            StateGraph=StateGraph,
            END=END,
            planner=planner,
            capability=capability,
        )
        execution_graph = self._build_execution_subgraph(
            StateGraph=StateGraph,
            END=END,
            tools=tools,
        )

        state_after_input = input_graph.invoke(initial_state)
        state_after_planning = planning_graph.invoke(state_after_input)

        plan = state_after_planning["plan"]
        if plan.need_clarification:
            trace = self._extract_trace(state_after_planning, initial_state)
            replay = self._build_replay_snapshot(state_after_planning)
            return GraphRunResult(
                run_id=str(state_after_planning.get("run_id", initial_state["run_id"])),
                plan=plan,
                context=dict(state_after_planning.get("base_context", initial_state["base_context"])),
                tool_results=[],
                route_reason=str(state_after_planning.get("route_reason", "clarification")),
                graph_trace=trace,
                replay_snapshot=replay,
            )

        final_state = execution_graph.invoke(state_after_planning)

        plan = final_state["plan"]

        context = final_state.get("context")
        if not isinstance(context, dict):
            context = dict(initial_state["base_context"])

        tool_results_raw = final_state.get("tool_results", [])
        tool_results = [item for item in tool_results_raw if isinstance(item, ToolResult)]

        return GraphRunResult(
            run_id=str(final_state.get("run_id", initial_state["run_id"])),
            plan=plan,
            context=context,
            tool_results=tool_results,
            route_reason=str(final_state.get("route_reason", "executed")),
            graph_trace=self._extract_trace(final_state, initial_state),
            replay_snapshot=self._build_replay_snapshot(final_state),
        )

    def _build_input_subgraph(self, StateGraph: Any, END: Any) -> Any:
        input_graph = StateGraph(dict)

        def prepare_input_node(state: GraphState) -> GraphState:
            base_context = dict(state.get("base_context", {}))
            preprocess = dict(base_context.get("preprocess", {}))
            preprocess["graph_state_ready"] = True
            base_context["preprocess"] = preprocess
            trace = self._record_trace(state, "input.prepare", "prepared", {"preprocess_keys": sorted(preprocess.keys())})
            return {
                "base_context": base_context,
                "route_reason": "input_prepared",
                "graph_trace": trace,
            }

        input_graph.add_node("prepare_input", prepare_input_node)
        input_graph.set_entry_point("prepare_input")
        input_graph.add_edge("prepare_input", END)
        return input_graph.compile()

    def _build_planning_subgraph(
        self,
        StateGraph: Any,
        END: Any,
        planner: QwenPlanner,
        capability: CapabilityLayer,
    ) -> Any:
        planning_graph = StateGraph(dict)

        def plan_node(state: GraphState) -> GraphState:
            request = state["request"]
            library_summary = state["library_summary"]
            replan_feedback = str(state.get("replan_feedback", "")).strip()
            planned_request = request
            if replan_feedback:
                planned_request = request.model_copy(
                    update={
                        "text": (
                            f"{request.text}\n\n"
                            f"[validator_feedback]\n"
                            f"{replan_feedback}\n"
                            "Please revise the plan to satisfy the constraints above."
                        )
                    }
                )

            plan = planner.create_plan(planned_request, library_summary)
            plan = capability.normalize_plan(plan, request)
            trace = self._record_trace(
                state,
                "planning.plan",
                "planned",
                {
                    "intent": plan.intent,
                    "need_clarification": plan.need_clarification,
                    "tool_count": len(plan.tool_calls),
                },
            )
            return {"plan": plan, "route_reason": "planned", "graph_trace": trace}

        def clarification_policy_node(state: GraphState) -> GraphState:
            request = state["request"]
            plan = state["plan"]
            mode = str(state.get("policy_clarification_mode", "balanced")).strip().lower()

            if mode == "off":
                gate_reasons: list[str] = []
            else:
                gate_reasons = self._collect_clarification_gate_reasons(request, plan)

            if mode == "strict" and gate_reasons and not plan.need_clarification:
                plan = plan.model_copy(
                    update={
                        "need_clarification": True,
                        "blocking_missing_info": list(dict.fromkeys(gate_reasons)),
                    }
                )

            trace = self._record_trace(
                state,
                "planning.clarification_policy",
                "evaluated",
                {
                    "mode": mode,
                    "need_clarification": plan.need_clarification,
                    "reason_count": len(gate_reasons),
                },
            )

            return {
                "plan": plan,
                "clarification_gate_reasons": gate_reasons,
                "route_reason": f"clarification_policy_{mode}",
                "graph_trace": trace,
            }

        def constraint_policy_node(state: GraphState) -> GraphState:
            request = state["request"]
            plan = state["plan"]
            mode = str(state.get("policy_tool_constraint_mode", "strict")).strip().lower()
            errors = self._collect_validation_errors_with_policy(request, plan, mode)
            trace = self._record_trace(
                state,
                "planning.constraint_policy",
                "evaluated",
                {"mode": mode, "error_count": len(errors)},
            )
            return {
                "validation_errors": errors,
                "route_reason": f"constraint_policy_{mode}",
                "graph_trace": trace,
            }

        def validate_node(state: GraphState) -> GraphState:
            plan = state["plan"]
            errors = [str(item) for item in state.get("validation_errors", [])]
            gate_reasons = [str(item) for item in state.get("clarification_gate_reasons", [])]

            if plan.need_clarification:
                trace = self._record_trace(
                    state,
                    "planning.validate",
                    "clarification",
                    {"error_count": len(errors), "gate_reason_count": len(gate_reasons)},
                )
                return {
                    "validation_errors": errors,
                    "clarification_gate_reasons": gate_reasons,
                    "plan": plan,
                    "route_reason": "planner_clarification",
                    "graph_trace": trace,
                }

            combined_reasons = errors + gate_reasons
            if combined_reasons and int(state.get("replan_attempts", 0)) >= self.max_replans:
                plan = plan.model_copy(
                    update={
                        "need_clarification": True,
                        # Gate only decides whether clarification is required.
                        # Question wording should be produced by planner model itself.
                        "blocking_missing_info": combined_reasons,
                    }
                )
                route_reason = "forced_clarification_after_max_replans"
            elif combined_reasons:
                route_reason = "validation_requires_replan"
            else:
                route_reason = "validation_passed"
            return {
                "validation_errors": errors,
                "clarification_gate_reasons": gate_reasons,
                "plan": plan,
                "route_reason": route_reason,
                "graph_trace": self._record_trace(
                    state,
                    "planning.validate",
                    route_reason,
                    {"error_count": len(errors), "gate_reason_count": len(gate_reasons)},
                ),
            }

        def replan_node(state: GraphState) -> GraphState:
            errors = [str(item) for item in state.get("validation_errors", [])]
            gate_reasons = [str(item) for item in state.get("clarification_gate_reasons", [])]
            attempts = int(state.get("replan_attempts", 0)) + 1
            feedback_parts = errors + gate_reasons + [
                "If clarification is required, set need_clarification=true and provide clarification_questions.",
            ]
            return {
                "replan_attempts": attempts,
                "replan_feedback": " | ".join(feedback_parts),
                "route_reason": "replanning",
                "graph_trace": self._record_trace(
                    state,
                    "planning.replan",
                    "replanning",
                    {"attempts": attempts, "feedback_count": len(feedback_parts)},
                ),
            }

        def route_after_validate(state: GraphState) -> str:
            plan = state["plan"]
            if plan.need_clarification:
                return "end"

            errors = [str(item) for item in state.get("validation_errors", [])]
            gate_reasons = [str(item) for item in state.get("clarification_gate_reasons", [])]
            attempts = int(state.get("replan_attempts", 0))
            if (errors or gate_reasons) and attempts < self.max_replans:
                return "replan"
            return "done"

        planning_graph.add_node("plan", plan_node)
        planning_graph.add_node("clarification_policy", clarification_policy_node)
        planning_graph.add_node("constraint_policy", constraint_policy_node)
        planning_graph.add_node("validate", validate_node)
        planning_graph.add_node("replan", replan_node)
        planning_graph.set_entry_point("plan")
        planning_graph.add_edge("plan", "clarification_policy")
        planning_graph.add_edge("clarification_policy", "constraint_policy")
        planning_graph.add_edge("constraint_policy", "validate")
        planning_graph.add_conditional_edges(
            "validate",
            route_after_validate,
            {
                "replan": "replan",
                "done": END,
                "end": END,
            },
        )
        planning_graph.add_edge("replan", "plan")
        return planning_graph.compile()

    def _collect_validation_errors_with_policy(
        self,
        request: AgentRequest,
        plan: ExecutionPlan,
        mode: str,
    ) -> list[str]:
        normalized_mode = mode.strip().lower()
        if normalized_mode == "off":
            return []
        if normalized_mode == "relaxed":
            return self._collect_validation_errors_relaxed(request, plan)
        return self._collect_validation_errors(request, plan)

    def _collect_validation_errors_relaxed(self, request: AgentRequest, plan: ExecutionPlan) -> list[str]:
        calls = plan.tool_calls
        if not calls:
            return ["未生成可执行工具链，请补充可执行步骤或改为澄清问题。"]

        sequence = [call.tool for call in calls]
        errors: list[str] = []

        if ToolName.FILTER_SELECTED in sequence and not request.selected_asset_ids:
            errors.append("请求未提供 selected_asset_ids，不能执行 filter_selected。")

        if ToolName.EXPORT_MP4 in sequence and ToolName.CONCAT_CLIPS not in sequence:
            errors.append("export_mp4 前应包含 concat_clips。")

        return errors

    def _build_execution_subgraph(self, StateGraph: Any, END: Any, tools: ToolRegistry) -> Any:
        execution_graph = StateGraph(dict)

        def execute_node(state: GraphState) -> GraphState:
            plan = state["plan"]
            context = dict(state["base_context"])
            tool_results = [tools.execute(call, context) for call in plan.tool_calls]
            trace = self._record_trace(
                state,
                "execution.execute",
                "executed",
                {
                    "tool_count": len(plan.tool_calls),
                    "success_count": len([item for item in tool_results if item.success]),
                },
            )
            return {
                "context": context,
                "tool_results": tool_results,
                "route_reason": "executed",
                "graph_trace": trace,
            }

        execution_graph.add_node("execute", execute_node)
        execution_graph.set_entry_point("execute")
        execution_graph.add_edge("execute", END)
        return execution_graph.compile()

    def _run_fallback(
        self,
        initial_state: GraphState,
        planner: QwenPlanner,
        capability: CapabilityLayer,
        tools: ToolRegistry,
    ) -> GraphRunResult:
        state = dict(initial_state)

        while True:
            request = state["request"]
            library_summary = state["library_summary"]
            base_context = state["base_context"]
            replan_feedback = str(state.get("replan_feedback", ""))
            attempts = int(state.get("replan_attempts", 0))

            planned_request = request
            if replan_feedback:
                planned_request = request.model_copy(
                    update={
                        "text": (
                            f"{request.text}\n\n"
                            f"[validator_feedback]\n"
                            f"{replan_feedback}\n"
                            "Please revise the plan to satisfy the constraints above."
                        )
                    }
                )

            plan = planner.create_plan(planned_request, library_summary)
            plan = capability.normalize_plan(plan, request)
            errors = self._collect_validation_errors(request, plan)
            gate_reasons = self._collect_clarification_gate_reasons(request, plan)
            state["plan"] = plan
            state["validation_errors"] = errors
            state["clarification_gate_reasons"] = gate_reasons

            if plan.need_clarification:
                trace = self._record_trace(state, "fallback", "clarification", {"attempts": attempts})
                state["graph_trace"] = trace
                return GraphRunResult(
                    run_id=str(state.get("run_id", "fallback")),
                    plan=plan,
                    context=dict(base_context),
                    tool_results=[],
                    route_reason="fallback_clarification",
                    graph_trace=self._extract_trace(state, initial_state),
                    replay_snapshot=self._build_replay_snapshot(state),
                )

            combined_reasons = errors + gate_reasons
            if combined_reasons and attempts >= self.max_replans:
                plan = plan.model_copy(
                    update={
                        "need_clarification": True,
                        "blocking_missing_info": combined_reasons,
                    }
                )
                trace = self._record_trace(
                    state,
                    "fallback",
                    "forced_clarification_after_max_replans",
                    {"attempts": attempts, "reason_count": len(combined_reasons)},
                )
                state["graph_trace"] = trace
                return GraphRunResult(
                    run_id=str(state.get("run_id", "fallback")),
                    plan=plan,
                    context=dict(base_context),
                    tool_results=[],
                    route_reason="fallback_forced_clarification",
                    graph_trace=self._extract_trace(state, initial_state),
                    replay_snapshot=self._build_replay_snapshot(state),
                )

            if combined_reasons:
                state["replan_attempts"] = attempts + 1
                state["replan_feedback"] = " | ".join(
                    combined_reasons
                    + [
                        "If clarification is required, set need_clarification=true and provide clarification_questions.",
                    ]
                )
                state["graph_trace"] = self._record_trace(
                    state,
                    "fallback",
                    "replanning",
                    {"attempts": int(state["replan_attempts"]), "reason_count": len(combined_reasons)},
                )
                continue

            context = dict(base_context)
            tool_results = [tools.execute(call, context) for call in plan.tool_calls]
            state["graph_trace"] = self._record_trace(
                state,
                "fallback",
                "executed",
                {
                    "tool_count": len(plan.tool_calls),
                    "success_count": len([item for item in tool_results if item.success]),
                },
            )
            state["context"] = context
            state["tool_results"] = tool_results
            state["route_reason"] = "fallback_executed"
            return GraphRunResult(
                run_id=str(state.get("run_id", "fallback")),
                plan=plan,
                context=context,
                tool_results=tool_results,
                route_reason="fallback_executed",
                graph_trace=self._extract_trace(state, initial_state),
                replay_snapshot=self._build_replay_snapshot(state),
            )

    def _record_trace(
        self,
        state: GraphState,
        node: str,
        event: str,
        details: dict[str, object],
    ) -> list[dict[str, object]]:
        trace = list(state.get("graph_trace", []))
        trace.append(
            {
                "ts": self._utc_now_iso(),
                "node": node,
                "event": event,
                "details": details,
            }
        )
        return trace

    def _extract_trace(self, state: GraphState, initial_state: GraphState) -> list[dict[str, object]]:
        trace = state.get("graph_trace")
        if isinstance(trace, list):
            return [item for item in trace if isinstance(item, dict)]
        fallback_trace = initial_state.get("graph_trace", [])
        return [item for item in fallback_trace if isinstance(item, dict)]

    def _build_replay_snapshot(self, state: GraphState) -> dict[str, object]:
        request = state.get("request")
        plan = state.get("plan")
        snapshot: dict[str, object] = {
            "run_id": str(state.get("run_id", "")),
            "route_reason": str(state.get("route_reason", "")),
            "replan_attempts": int(state.get("replan_attempts", 0)),
            "policy_clarification_mode": str(state.get("policy_clarification_mode", "")),
            "policy_tool_constraint_mode": str(state.get("policy_tool_constraint_mode", "")),
            "validation_errors": [str(item) for item in state.get("validation_errors", [])],
            "clarification_gate_reasons": [str(item) for item in state.get("clarification_gate_reasons", [])],
            "tool_result_count": len([item for item in state.get("tool_results", []) if isinstance(item, ToolResult)]),
        }
        if isinstance(request, AgentRequest):
            snapshot["request"] = {
                "user_id": request.user_id,
                "text": request.text,
                "library_root": str(request.library_root),
                "selected_asset_ids": list(request.selected_asset_ids),
            }
        if isinstance(plan, ExecutionPlan):
            snapshot["plan"] = {
                "intent": plan.intent,
                "need_clarification": plan.need_clarification,
                "tool_sequence": [call.tool.value for call in plan.tool_calls],
            }
        return snapshot

    def _persist_run_record(self, result: GraphRunResult) -> None:
        if not settings.graph_observability_enabled:
            return

        log_file = settings.graph_run_log_file
        payload = {
            "ts": self._utc_now_iso(),
            "run_id": result.run_id,
            "route_reason": result.route_reason,
            "plan_intent": result.plan.intent,
            "need_clarification": result.plan.need_clarification,
            "tool_sequence": [call.tool.value for call in result.plan.tool_calls],
            "tool_result_count": len(result.tool_results),
            "trace": result.graph_trace,
            "replay_snapshot": result.replay_snapshot,
        }

        log_file.parent.mkdir(parents=True, exist_ok=True)
        with log_file.open("a", encoding="utf-8") as fp:
            fp.write(json.dumps(payload, ensure_ascii=False) + "\n")

    def _utc_now_iso(self) -> str:
        return datetime.now(timezone.utc).isoformat(timespec="seconds")

    def _collect_validation_errors(self, request: AgentRequest, plan: ExecutionPlan) -> list[str]:
        calls = plan.tool_calls
        if not calls:
            return ["未生成可执行工具链，请补充可执行步骤或改为澄清问题。"]

        sequence = [call.tool for call in calls]
        errors: list[str] = []

        if sequence[0] != ToolName.SCAN_LIBRARY:
            errors.append("工具链第一步应为 scan_library 以建立资产上下文。")

        if ToolName.FILTER_SELECTED in sequence and not request.selected_asset_ids:
            errors.append("请求未提供 selected_asset_ids，不能执行 filter_selected。")

        if ToolName.SELECT_COVER_FRAME in sequence and ToolName.EXTRACT_KEY_FRAMES not in sequence:
            errors.append("select_cover_frame 之前应先执行 extract_key_frames。")

        if ToolName.EXPORT_MP4 in sequence and ToolName.CONCAT_CLIPS not in sequence:
            errors.append("export_mp4 前应包含 concat_clips。")

        if ToolName.SUMMARIZE_RESULTS in sequence and sequence[-1] != ToolName.SUMMARIZE_RESULTS:
            errors.append("summarize_results 应位于工具链末尾。")

        if ToolName.CONCAT_CLIPS in sequence:
            concat_idx = sequence.index(ToolName.CONCAT_CLIPS)
            for tool in (ToolName.ADD_TEXT_OVERLAY, ToolName.MIX_AUDIO_BGM, ToolName.EXPORT_MP4):
                if tool in sequence and sequence.index(tool) < concat_idx:
                    errors.append(f"{tool.value} 应在 concat_clips 之后执行。")

        return errors

    def _collect_clarification_gate_reasons(self, request: AgentRequest, plan: ExecutionPlan) -> list[str]:
        text = request.text.strip().lower()
        intent_text = plan.intent.strip().lower()
        reasons: list[str] = []

        # Planner intent markers that explicitly indicate ambiguity/conflict/missing constraints.
        intent_markers = [
            "ambiguous",
            "ambiguity",
            "underspecified",
            "missing",
            "conflict",
            "reference",
            "scope",
            "constraint",
            "unclear",
            "unknown",
            "不明确",
            "模糊",
            "缺失",
            "冲突",
            "参考",
            "范围",
        ]
        if any(marker in intent_text for marker in intent_markers):
            reasons.append("规划意图显示需求存在歧义或约束冲突，需要先澄清。")

        # Conflict and contradiction patterns.
        conflict_markers = [
            "但", "同时", "又", "并且", "但是", "同时要", "既要", "又要",
            "without", "but", "while", "at the same time",
        ]
        if any(marker in text for marker in conflict_markers):
            if ("不" in text and "要" in text) or ("不要" in text and "生成" in text):
                reasons.append("需求可能包含冲突约束，需要先确认优先级。")

        # Missing selection scope while requiring selected-only operations.
        selected_scope_markers = ["选中", "selected", "指定", "这几张", "这些"]
        if any(marker in text for marker in selected_scope_markers) and not request.selected_asset_ids:
            reasons.append("请求指向选中素材，但未提供 selected_asset_ids。")

        # Underspecified output form.
        output_ambiguous_markers = [
            "发朋友圈",
            "分享",
            "社交",
            "好看",
            "高级感",
            "氛围感",
            "电影感",
            "cinematic",
            "social media",
            "social-friendly",
            "visually appealing",
            "restrained aesthetics",
            "highest quality",
        ]
        if any(marker in text for marker in output_ambiguous_markers):
            if all(token not in text for token in ["视频", "mp4", "导出", "封面", "推荐", "summary", "summarize"]):
                reasons.append("输出形态不明确，需要先确认产物形式。")

        # Reference-dependent requests without explicit reference.
        reference_markers = ["按之前", "上次", "同款", "复刻", "参考", "previous", "same as", "reproduce"]
        if any(marker in text for marker in reference_markers):
            reasons.append("请求依赖参考样例或历史结果，当前缺少可定位的参考信息。")

        # Open-ended request with weak plan (only scanning) should be clarified first.
        sequence = [call.tool for call in plan.tool_calls]
        ambiguous_request_markers = [
            "可能",
            "或者",
            "或",
            "等",
            "根据用户",
            "today",
            "today's",
            "immediate",
            "马上",
            "立刻",
            "short film",
            "create_short_film",
            "auto_curation",
        ]
        if sequence == [ToolName.SCAN_LIBRARY] and any(marker in (text + " " + intent_text) for marker in ambiguous_request_markers):
            reasons.append("当前计划仅扫描库，无法覆盖开放式需求，需先澄清目标与约束。")

        # If model already decided clarification, keep gate passive.
        if plan.need_clarification:
            return []

        # Deduplicate while preserving order.
        return list(dict.fromkeys(reasons))
