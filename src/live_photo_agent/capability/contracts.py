from __future__ import annotations

from dataclasses import dataclass

from ..models import AgentRequest, ExecutionPlan, ToolCall, ToolName


@dataclass(frozen=True, slots=True)
class ToolContract:
    level: str
    purpose: str
    allowed_arguments: tuple[str, ...]
    output_fields: tuple[str, ...]
    preconditions: tuple[str, ...]
    side_effects: tuple[str, ...]
    failure_modes: tuple[str, ...]
    timeout_budget_ms: int
    quality_metrics: tuple[str, ...]
    idempotent: bool
    security_scope: str


TOOL_CONTRACTS: dict[ToolName, ToolContract] = {
    ToolName.SCAN_LIBRARY: ToolContract(
        level="L0",
        purpose="Scan current library and build base asset inventory.",
        allowed_arguments=("library_root",),
        output_fields=("asset_count", "asset_ids"),
        preconditions=("library_root exists",),
        side_effects=("context.assets overwritten",),
        failure_modes=("library_not_found", "permission_denied"),
        timeout_budget_ms=4000,
        quality_metrics=("asset_inventory_completeness",),
        idempotent=True,
        security_scope="local_library_read",
    ),
    ToolName.FILTER_SELECTED: ToolContract(
        level="L0",
        purpose="Filter explicitly selected asset IDs from current context assets.",
        allowed_arguments=("selected_asset_ids",),
        output_fields=("selected_count", "selected_ids"),
        preconditions=("context.assets exists",),
        side_effects=("context.selected_assets overwritten",),
        failure_modes=("selected_ids_empty",),
        timeout_budget_ms=500,
        quality_metrics=("selection_recall",),
        idempotent=True,
        security_scope="in_memory_asset_filter",
    ),
    ToolName.SEARCH_BY_TEXT: ToolContract(
        level="L1",
        purpose="Text retrieval over indexed asset metadata and tags.",
        allowed_arguments=("query",),
        output_fields=("match_count", "matched_ids"),
        preconditions=("context.assets exists", "query not empty"),
        side_effects=("context.matched_assets overwritten",),
        failure_modes=("empty_query", "no_match"),
        timeout_budget_ms=1200,
        quality_metrics=("retrieval_precision_at_k", "retrieval_recall_at_k"),
        idempotent=True,
        security_scope="in_memory_search",
    ),
    ToolName.DRAFT_EDIT_PLAN: ToolContract(
        level="L2",
        purpose="Build recommendation/edit plan for focus assets.",
        allowed_arguments=("style",),
        output_fields=("style", "asset_count", "suggestions"),
        preconditions=("focus assets available from selected/matched/assets",),
        side_effects=("context.edit_plan overwritten",),
        failure_modes=("no_focus_assets",),
        timeout_budget_ms=1500,
        quality_metrics=("plan_actionability",),
        idempotent=True,
        security_scope="planning_only",
    ),
    ToolName.SUMMARIZE_RESULTS: ToolContract(
        level="L1",
        purpose="Create user-facing summary from current focus assets.",
        allowed_arguments=("response_style",),
        output_fields=("response_style", "focus_asset_ids", "focus_asset_count"),
        preconditions=("focus assets available",),
        side_effects=("context.summary overwritten",),
        failure_modes=("no_focus_assets",),
        timeout_budget_ms=800,
        quality_metrics=("summary_clarity",),
        idempotent=True,
        security_scope="planning_only",
    ),
    ToolName.EXTRACT_KEY_FRAMES: ToolContract(
        level="L0",
        purpose="Extract representative frames from a motion clip for downstream scoring.",
        allowed_arguments=("asset_ids", "frame_interval_ms", "max_frames", "sampling_strategy"),
        output_fields=("frame_count", "frames_by_asset"),
        preconditions=("motion clips available for assets",),
        side_effects=("context.key_frames overwritten",),
        failure_modes=("missing_motion_clip", "decode_failed"),
        timeout_budget_ms=5000,
        quality_metrics=("frame_coverage",),
        idempotent=True,
        security_scope="local_media_read",
    ),
    ToolName.ESTIMATE_MOTION_SCORE: ToolContract(
        level="L0",
        purpose="Estimate motion quality score for candidate live photos.",
        allowed_arguments=("asset_ids",),
        output_fields=("motion_scores",),
        preconditions=("assets available",),
        side_effects=("context.motion_scores overwritten",),
        failure_modes=("asset_not_found",),
        timeout_budget_ms=1200,
        quality_metrics=("motion_score_consistency",),
        idempotent=True,
        security_scope="in_memory_analysis",
    ),
    ToolName.SUBJECT_SEGMENTATION: ToolContract(
        level="L0",
        purpose="Segment primary subject masks for visual prominence evaluation.",
        allowed_arguments=("asset_ids", "mode"),
        output_fields=("mask_count", "segmented_asset_ids"),
        preconditions=("frames or images available",),
        side_effects=("context.subject_masks overwritten",),
        failure_modes=("segmentation_failed",),
        timeout_budget_ms=3500,
        quality_metrics=("mask_quality",),
        idempotent=True,
        security_scope="local_media_read",
    ),
    ToolName.SELECT_COVER_FRAME: ToolContract(
        level="L0",
        purpose="Select best cover frame candidate for each asset.",
        allowed_arguments=("asset_ids", "strategy"),
        output_fields=("cover_frames",),
        preconditions=("key frames available or image exists",),
        side_effects=("context.cover_frames overwritten",),
        failure_modes=("no_frames_available",),
        timeout_budget_ms=1200,
        quality_metrics=("cover_ctr_proxy",),
        idempotent=True,
        security_scope="in_memory_analysis",
    ),
    ToolName.CLIP_TRIM: ToolContract(
        level="L0",
        purpose="Trim a clip to a target time range.",
        allowed_arguments=("asset_ids", "start_ms", "end_ms"),
        output_fields=("trimmed_segments",),
        preconditions=("motion clip available",),
        side_effects=("context.trimmed_segments overwritten",),
        failure_modes=("invalid_range", "decode_failed"),
        timeout_budget_ms=3000,
        quality_metrics=("segment_validity",),
        idempotent=True,
        security_scope="local_media_readwrite_temp",
    ),
    ToolName.CLIP_SPEED: ToolContract(
        level="L0",
        purpose="Apply speed adjustment to selected clip segments.",
        allowed_arguments=("asset_ids", "speed"),
        output_fields=("speed_adjusted_segments",),
        preconditions=("segments available",),
        side_effects=("context.speed_segments overwritten",),
        failure_modes=("invalid_speed",),
        timeout_budget_ms=2200,
        quality_metrics=("speed_smoothness",),
        idempotent=True,
        security_scope="local_media_readwrite_temp",
    ),
    ToolName.COLOR_ENHANCE: ToolContract(
        level="L0",
        purpose="Apply basic color enhancement for consistency.",
        allowed_arguments=("asset_ids", "profile"),
        output_fields=("enhanced_assets",),
        preconditions=("assets available",),
        side_effects=("context.color_enhanced overwritten",),
        failure_modes=("enhance_failed",),
        timeout_budget_ms=2500,
        quality_metrics=("color_consistency",),
        idempotent=True,
        security_scope="local_media_readwrite_temp",
    ),
    ToolName.STABILIZE_CLIP: ToolContract(
        level="L0",
        purpose="Apply stabilization on shaky motion clips.",
        allowed_arguments=("asset_ids",),
        output_fields=("stabilized_assets",),
        preconditions=("motion clip available",),
        side_effects=("context.stabilized_assets overwritten",),
        failure_modes=("stabilization_failed",),
        timeout_budget_ms=3200,
        quality_metrics=("jitter_reduction",),
        idempotent=True,
        security_scope="local_media_readwrite_temp",
    ),
    ToolName.CONCAT_CLIPS: ToolContract(
        level="L0",
        purpose="Compose clips either on timeline or spatial canvas (triptych defaults to vertical 3-up).",
        allowed_arguments=("asset_ids", "order", "layout", "canvas"),
        output_fields=("timeline_id", "segment_count"),
        preconditions=("segments available",),
        side_effects=("context.timeline overwritten",),
        failure_modes=("concat_failed", "invalid_order"),
        timeout_budget_ms=2600,
        quality_metrics=("timeline_integrity",),
        idempotent=True,
        security_scope="local_media_readwrite_temp",
    ),
    ToolName.ADD_TEXT_OVERLAY: ToolContract(
        level="L0",
        purpose="Overlay text title/caption onto timeline.",
        allowed_arguments=("text", "style"),
        output_fields=("overlay_applied",),
        preconditions=("timeline available",),
        side_effects=("context.overlay overwritten",),
        failure_modes=("invalid_text",),
        timeout_budget_ms=800,
        quality_metrics=("readability",),
        idempotent=True,
        security_scope="local_media_readwrite_temp",
    ),
    ToolName.MIX_AUDIO_BGM: ToolContract(
        level="L0",
        purpose="Mix background music with timeline audio.",
        allowed_arguments=("track_id", "gain_db"),
        output_fields=("audio_mix_applied",),
        preconditions=("timeline available",),
        side_effects=("context.audio_mix overwritten",),
        failure_modes=("track_not_found", "mix_failed"),
        timeout_budget_ms=1200,
        quality_metrics=("audio_balance",),
        idempotent=True,
        security_scope="local_media_readwrite_temp",
    ),
    ToolName.EXPORT_MP4: ToolContract(
        level="L0",
        purpose="Export preview mp4 and pack final live-photo jpg container when possible.",
        allowed_arguments=("output_name", "resolution"),
        output_fields=("output_path", "duration_ms"),
        preconditions=("timeline available",),
        side_effects=("writes output media file",),
        failure_modes=("encode_failed", "insufficient_disk"),
        timeout_budget_ms=6000,
        quality_metrics=("export_success_rate",),
        idempotent=False,
        security_scope="local_export_write",
    ),
}


class CapabilityLayer:
    """Capability Layer: tool contracts and planner output normalization."""

    def tool_catalog(self) -> list[dict[str, object]]:
        return [
            {
                "tool": tool.value,
                "level": contract.level,
                "purpose": contract.purpose,
                "allowed_arguments": list(contract.allowed_arguments),
                "input_schema": self._input_schema(tool),
                "output_schema": self._output_schema(tool),
                "output_fields": list(contract.output_fields),
                "preconditions": list(contract.preconditions),
                "side_effects": list(contract.side_effects),
                "failure_modes": list(contract.failure_modes),
                "timeout_budget_ms": contract.timeout_budget_ms,
                "quality_metrics": list(contract.quality_metrics),
                "idempotent": contract.idempotent,
                "security_scope": contract.security_scope,
            }
            for tool, contract in TOOL_CONTRACTS.items()
        ]

    def _input_schema(self, tool: ToolName) -> dict[str, str]:
        schemas: dict[ToolName, dict[str, str]] = {
            ToolName.SCAN_LIBRARY: {"library_root": "string(path)"},
            ToolName.FILTER_SELECTED: {"selected_asset_ids": "string[]"},
            ToolName.SEARCH_BY_TEXT: {"query": "string"},
            ToolName.DRAFT_EDIT_PLAN: {"style": "string"},
            ToolName.SUMMARIZE_RESULTS: {"response_style": "string"},
            ToolName.EXTRACT_KEY_FRAMES: {
                "asset_ids": "string[]?",
                "frame_interval_ms": "int(default=400)",
                "max_frames": "int(default=8)",
                "sampling_strategy": "string(default=uniform, options=uniform|auto_adaptive|adaptive)",
            },
            ToolName.ESTIMATE_MOTION_SCORE: {"asset_ids": "string[]?"},
            ToolName.SUBJECT_SEGMENTATION: {
                "asset_ids": "string[]?",
                "mode": "string(default=person_first)",
            },
            ToolName.SELECT_COVER_FRAME: {
                "asset_ids": "string[]?",
                "strategy": "string(default=auto_adaptive, options=auto_adaptive|sharpest|vibrant|first|middle|last)",
            },
            ToolName.CLIP_TRIM: {
                "asset_ids": "string[]?",
                "start_ms": "int(default=0)",
                "end_ms": "int(default=1500)",
            },
            ToolName.CLIP_SPEED: {
                "asset_ids": "string[]?",
                "speed": "float(default=1.0)",
            },
            ToolName.COLOR_ENHANCE: {
                "asset_ids": "string[]?",
                "profile": "string(default=vivid)",
            },
            ToolName.STABILIZE_CLIP: {"asset_ids": "string[]?"},
            ToolName.CONCAT_CLIPS: {
                "asset_ids": "string[]?",
                "order": "string[]?",
                "layout": "string(default=auto, options=auto|timeline|horizontal|vertical|triptych_portrait|triptych_landscape)",
                "canvas": "string(default=1080x1920)",
            },
            ToolName.ADD_TEXT_OVERLAY: {
                "text": "string",
                "style": "string(default=minimal)",
            },
            ToolName.MIX_AUDIO_BGM: {
                "track_id": "string(default=default_track)",
                "gain_db": "float(default=-8.0)",
            },
            ToolName.EXPORT_MP4: {
                "output_name": "string(default=triptych_output)",
                "resolution": "string(default=1080x1920)",
            },
        }
        return schemas.get(tool, {})

    def _output_schema(self, tool: ToolName) -> dict[str, str]:
        schemas: dict[ToolName, dict[str, str]] = {
            ToolName.SCAN_LIBRARY: {"asset_count": "int", "asset_ids": "string[]"},
            ToolName.FILTER_SELECTED: {"selected_count": "int", "selected_ids": "string[]"},
            ToolName.SEARCH_BY_TEXT: {"match_count": "int", "matched_ids": "string[]"},
            ToolName.DRAFT_EDIT_PLAN: {"style": "string", "asset_count": "int", "suggestions": "string[]"},
            ToolName.SUMMARIZE_RESULTS: {
                "response_style": "string",
                "focus_asset_ids": "string[]",
                "focus_asset_count": "int",
            },
            ToolName.EXTRACT_KEY_FRAMES: {
                "frame_count": "int",
                "frames_by_asset": "object<string,string[]>",
                "frame_interval_ms": "int",
                "sampling_strategy": "string",
                "effective_settings": "object<string,object>",
            },
            ToolName.ESTIMATE_MOTION_SCORE: {"motion_scores": "object<string,float>"},
            ToolName.SUBJECT_SEGMENTATION: {
                "mask_count": "int",
                "segmented_asset_ids": "string[]",
                "mode": "string",
                "mask_paths": "object<string,string>",
                "foreground_ratios": "object<string,float>",
            },
            ToolName.SELECT_COVER_FRAME: {"cover_frames": "object<string,string>", "strategy": "string"},
            ToolName.CLIP_TRIM: {"trimmed_segments": "object<string,object>"},
            ToolName.CLIP_SPEED: {"speed_adjusted_segments": "object<string,object>"},
            ToolName.COLOR_ENHANCE: {"enhanced_assets": "object<string,string>", "profile": "string"},
            ToolName.STABILIZE_CLIP: {"stabilized_assets": "object<string,string>"},
            ToolName.CONCAT_CLIPS: {"timeline_id": "string", "segment_count": "int"},
            ToolName.ADD_TEXT_OVERLAY: {"overlay_applied": "bool"},
            ToolName.MIX_AUDIO_BGM: {"audio_mix_applied": "bool"},
            ToolName.EXPORT_MP4: {"output_path": "string(path)", "duration_ms": "int"},
        }
        return schemas.get(tool, {})

    def normalize_plan(self, plan: ExecutionPlan, request: AgentRequest) -> ExecutionPlan:
        calls = [self._normalize_call(call) for call in plan.tool_calls]
        calls = self._apply_sequence_guards(calls, request)
        calls = self._apply_request_layout_constraints(calls, request)

        return ExecutionPlan(
            user_goal=plan.user_goal,
            intent=plan.intent,
            selected_asset_ids=plan.selected_asset_ids,
            required_context=[str(item) for item in plan.required_context],
            tool_calls=calls,
            need_clarification=plan.need_clarification,
            clarification_questions=[str(item) for item in plan.clarification_questions],
            blocking_missing_info=[str(item) for item in plan.blocking_missing_info],
        )

    def _apply_sequence_guards(self, calls: list[ToolCall], request: AgentRequest) -> list[ToolCall]:
        if not calls:
            return []

        normalized = list(calls)

        # Guard 1: scan_library should be first so all later tools have asset context.
        scan_index = next((idx for idx, call in enumerate(normalized) if call.tool == ToolName.SCAN_LIBRARY), -1)
        if scan_index == -1:
            normalized.insert(
                0,
                ToolCall(
                    tool=ToolName.SCAN_LIBRARY,
                    reason="Ensure asset inventory context is initialized before other tools.",
                    arguments={"library_root": str(request.library_root)},
                ),
            )
        elif scan_index > 0:
            scan_call = normalized.pop(scan_index)
            normalized.insert(0, scan_call)

        # Guard 2: remove filter_selected when request has no selected ids.
        if not request.selected_asset_ids:
            normalized = [call for call in normalized if call.tool != ToolName.FILTER_SELECTED]

        sequence = [call.tool for call in normalized]

        # Guard 3: select_cover_frame depends on key frame extraction.
        if ToolName.SELECT_COVER_FRAME in sequence and ToolName.EXTRACT_KEY_FRAMES not in sequence:
            cover_idx = sequence.index(ToolName.SELECT_COVER_FRAME)
            normalized.insert(
                cover_idx,
                ToolCall(
                    tool=ToolName.EXTRACT_KEY_FRAMES,
                    reason="Provide frame candidates required by select_cover_frame.",
                    arguments={},
                ),
            )
            sequence = [call.tool for call in normalized]

        # Guard 4: export_mp4 depends on concat_clips.
        if ToolName.EXPORT_MP4 in sequence and ToolName.CONCAT_CLIPS not in sequence:
            export_idx = sequence.index(ToolName.EXPORT_MP4)
            normalized.insert(
                export_idx,
                ToolCall(
                    tool=ToolName.CONCAT_CLIPS,
                    reason="Build timeline before export_mp4.",
                    arguments={},
                ),
            )
            sequence = [call.tool for call in normalized]

        # Guard 5: summarize_results should be the final reporting step.
        summarize_index = next((idx for idx, call in enumerate(normalized) if call.tool == ToolName.SUMMARIZE_RESULTS), -1)
        if summarize_index != -1 and summarize_index != len(normalized) - 1:
            summarize_call = normalized.pop(summarize_index)
            normalized.append(summarize_call)

        return normalized

    def _apply_request_layout_constraints(self, calls: list[ToolCall], request: AgentRequest) -> list[ToolCall]:
        direction_layout = self._requested_concat_layout(request.text)
        if not direction_layout:
            return calls

        constrained: list[ToolCall] = []
        for call in calls:
            if call.tool != ToolName.CONCAT_CLIPS:
                constrained.append(call)
                continue
            updated_args = dict(call.arguments)
            updated_args["layout"] = direction_layout
            constrained.append(
                ToolCall(
                    tool=call.tool,
                    reason=call.reason,
                    arguments=updated_args,
                )
            )
        return constrained

    def _requested_concat_layout(self, text: str) -> str:
        lowered = text.lower()
        horizontal_markers = ["左右", "左边", "右边", "横", "horizontal", "left", "right"]
        vertical_markers = ["上下", "上面", "下面", "上中下", "竖", "vertical", "top", "bottom"]

        if any(marker in lowered for marker in horizontal_markers):
            return "triptych_landscape"
        if any(marker in lowered for marker in vertical_markers):
            return "vertical"
        return ""

    def _normalize_call(self, call: ToolCall) -> ToolCall:
        contract = TOOL_CONTRACTS[call.tool]
        args = {
            key: value
            for key, value in call.arguments.items()
            if key in contract.allowed_arguments
        }

        return ToolCall(tool=call.tool, reason=call.reason, arguments=args)
