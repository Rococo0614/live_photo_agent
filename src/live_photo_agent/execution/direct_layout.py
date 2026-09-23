"""Deterministic execution for the UI layout workflow.

The canvas is already a structured plan.  It must not be converted back into
natural language and sent to the general planner: doing that lets the planner
change positions, drop slots, or turn a spatial composition into a timeline.

This module only translates the small, supported set of media directives into
existing registered tools.  Layout coordinates and slot order always come
from ``AgentRequest.layout_context``.
"""
from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any

from ..capability.registry import ToolRegistry
from ..config import settings
from ..models import AgentRequest, ExecutionPlan, LivePhotoAsset, ToolCall, ToolName, ToolResult


@dataclass(slots=True)
class DirectLayoutResult:
    plan: ExecutionPlan
    context: dict[str, object]
    tool_results: list[ToolResult]
    warnings: list[str]


@dataclass(frozen=True, slots=True)
class _DirectiveRule:
    operation: str
    patterns: tuple[str, ...]


# This is a whitelist, not a second intent planner.  A phrase that does not
# map to one of these operations is reported as unsupported rather than being
# forwarded to a non-existent diffusion pipeline.
_DIRECTIVE_RULES = (
    _DirectiveRule("subject_overlay", (r"抠图", r"抠出", r"主体分割", r"叠加.*主体", r"cut.?out", r"overlay")),
    _DirectiveRule("stabilize_clip", (r"防抖", r"稳定", r"stabili[sz]e")),
    _DirectiveRule("color_enhance", (r"调色", r"暖色", r"冷色", r"饱和", r"鲜艳", r"color", r"warm", r"cool")),
    _DirectiveRule("clip_speed", (r"倍速", r"加速", r"减速", r"speed")),
    _DirectiveRule("clip_trim", (r"裁剪", r"截取", r"trim", r"cut")),
)


def parse_processing_directives(
    text: str,
    asset_ids: list[str],
    layout_context: list[dict[str, object]],
) -> tuple[list[dict[str, object]], list[str]]:
    """Convert supported natural-language effects into structured directives.

    The parser deliberately does not attempt to understand arbitrary editing
    prompts.  It only recognizes operations that have a concrete implementation
    in the current media layer.
    """
    lowered = str(text or "").strip().lower()
    directives: list[dict[str, object]] = []
    warnings: list[str] = []

    foreground_ids = [
        str(item.get("asset_id"))
        for item in layout_context
        if isinstance(item, dict)
        and item.get("asset_id")
        and (item.get("foreground") or item.get("is_overlay") or item.get("edit_rect"))
    ]

    for rule in _DIRECTIVE_RULES:
        if not any(re.search(pattern, lowered) for pattern in rule.patterns):
            continue

        target_ids = list(asset_ids)
        if rule.operation == "subject_overlay":
            target_ids = foreground_ids or target_ids[:1]
        directive: dict[str, object] = {"operation": rule.operation, "asset_ids": target_ids}

        if rule.operation == "color_enhance":
            if re.search(r"冷色|cool", lowered):
                directive["profile"] = "cool"
            elif re.search(r"暖色|warm", lowered):
                directive["profile"] = "warm"
            elif re.search(r"饱和|鲜艳|vivid", lowered):
                directive["profile"] = "vivid"
            else:
                directive["profile"] = "vivid"

        if rule.operation == "clip_speed":
            match = re.search(r"(\d+(?:\.\d+)?)\s*倍速", lowered)
            if match:
                directive["speed"] = float(match.group(1))
            elif re.search(r"减速", lowered):
                directive["speed"] = 0.8
            else:
                directive["speed"] = 1.2

        if rule.operation == "clip_trim":
            # The current UI has no range control.  Keep this operation
            # explicit but conservative until a start/end field is supplied.
            warnings.append("检测到裁剪需求，但当前版本需要明确的起止时间，暂未执行裁剪。")
            continue

        directives.append(directive)

    # Diffusion-style prompts are intentionally not silently executed.
    if re.search(r"扩散|生图|补全|填充|换衣服|改成.*背景|inpaint|diffusion", lowered):
        warnings.append("当前版本不支持任意视频扩散/生成式编辑，仅执行固定媒体操作。")

    return directives, warnings


class DirectLayoutExecutor:
    """Execute a UI layout without invoking the general planner."""

    def __init__(self, tools: ToolRegistry) -> None:
        self.tools = tools

    def execute(
        self,
        request: AgentRequest,
        assets: list[LivePhotoAsset],
        preprocess_info: dict[str, object] | None = None,
    ) -> DirectLayoutResult:
        items = [item for item in request.layout_context if isinstance(item, dict) and item.get("asset_id")]
        asset_by_id = {asset.asset_id: asset for asset in assets}
        ordered_ids = [str(item["asset_id"]) for item in items if str(item["asset_id"]) in asset_by_id]
        warnings: list[str] = []

        context: dict[str, object] = {
            "library_root": request.library_root,
            "request_text": request.text,
            "assets": assets,
            "selected_assets": [asset_by_id[asset_id] for asset_id in ordered_ids],
            "selected_asset_ids": ordered_ids,
            "layout_context": items,
            "work_dir": settings.agent_work_dir / "layout",
            "operation_log": request.operation_log,
            "preprocess": preprocess_info or {},
        }

        if not ordered_ids:
            plan = ExecutionPlan(
                user_goal=request.text,
                intent="layout_generation",
                selected_asset_ids=[],
                required_context=["layout_context"],
                tool_calls=[],
                need_clarification=True,
                clarification_questions=["请先载入至少一个素材并放入布局。"],
            )
            return DirectLayoutResult(plan=plan, context=context, tool_results=[], warnings=warnings)

        parsed_directives, parse_warnings = parse_processing_directives(
            request.text,
            ordered_ids,
            items,
        )
        warnings.extend(parse_warnings)
        directives = list(request.processing_directives) or parsed_directives
        tool_calls: list[ToolCall] = []
        tool_results: list[ToolResult] = []

        # Run fixed per-asset operations before composition.  Each operation
        # writes a source map into context, and concat/template_collage picks
        # the newest map automatically.
        for directive in directives:
            operation = str(directive.get("operation", "")).strip().lower()
            asset_ids = directive.get("asset_ids")
            if not isinstance(asset_ids, list) or not asset_ids:
                asset_ids = ordered_ids
            args: dict[str, object] = {"asset_ids": [str(value) for value in asset_ids]}
            if operation == "color_enhance":
                args["profile"] = str(directive.get("profile", "vivid"))
                tool = ToolName.COLOR_ENHANCE
            elif operation == "clip_speed":
                args["speed"] = float(directive.get("speed", 1.0))
                tool = ToolName.CLIP_SPEED
            elif operation == "stabilize_clip":
                tool = ToolName.STABILIZE_CLIP
            elif operation == "subject_overlay":
                # Matte extraction is also added below for template foreground
                # slots.  Avoid running it twice for the same asset.
                tool = ToolName.EXTRACT_SUBJECT_MATTE
                args["mode"] = str(directive.get("mode", "mog2"))
            else:
                warnings.append(f"未执行不支持的素材操作：{operation}")
                continue
            result = self._run(tool, args, operation, context, tool_calls, tool_results)
            if not result.success:
                return self._result(request, ordered_ids, tool_calls, tool_results, context, warnings)

        foreground_items = [
            item
            for item in items
            if item.get("foreground") or item.get("is_overlay") or item.get("edit_rect")
        ]
        foreground_ids = {str(item["asset_id"]) for item in foreground_items if item.get("asset_id")}
        matted_ids = {
            str(asset_id)
            for result in tool_results
            if result.tool == ToolName.EXTRACT_SUBJECT_MATTE
            for asset_id in result.payload.get("matted_asset_ids", [])
        }
        for item in foreground_items:
            asset_id = str(item.get("asset_id", ""))
            if asset_id and asset_id not in matted_ids:
                result = self._run(
                    ToolName.EXTRACT_SUBJECT_MATTE,
                    {"asset_ids": [asset_id], "mode": "mog2"},
                    "foreground subject extraction",
                    context,
                    tool_calls,
                    tool_results,
                )
                if not result.success:
                    return self._result(request, ordered_ids, tool_calls, tool_results, context, warnings)

        background_items = [item for item in items if str(item.get("asset_id")) not in foreground_ids]
        if not background_items:
            background_items = items
            foreground_items = []
            foreground_ids = set()

        background_ids = [str(item["asset_id"]) for item in background_items]
        background_paths = [self._source_path(asset_by_id[asset_id], context) for asset_id in background_ids]
        background_paths = [path for path in background_paths if path]
        slots = [self._slot_payload(item) for item in background_items]
        total_duration = max((float(slot.get("end_time_s", 6.0)) for slot in slots), default=6.0)
        collage_args = {
            "asset_paths": background_paths,
            "output_dir": str(self._work_dir(context) / "layout_collage"),
            "canvas_width": self._canvas_value(items, "canvas_width", 1080),
            "canvas_height": self._canvas_value(items, "canvas_height", 1440),
            "layout_type": "grid",
            "template_id": str(items[0].get("template_id", "direct_layout")) if items else "direct_layout",
            "template_slots": slots,
            "total_duration_s": total_duration,
        }
        self._run(
            ToolName.TEMPLATE_COLLAGE,
            collage_args,
            "compose current UI layout",
            context,
            tool_calls,
            tool_results,
        )
        collage_result = tool_results[-1] if tool_results else None
        if collage_result is None or not collage_result.success:
            return self._result(request, ordered_ids, tool_calls, tool_results, context, warnings)

        context["timeline"] = {
            "timeline_id": "direct_layout",
            "path": collage_result.payload.get("final_video", ""),
            "composition": "spatial",
        }
        for item in foreground_items:
            asset_id = str(item.get("asset_id", ""))
            overlay_args = {
                "foreground_asset_id": asset_id,
                "anchor": str(item.get("anchor", "center")),
                "scale": float(item.get("scale", 0.45)),
                "x_offset": int(item.get("x_offset", 0)),
                "y_offset": int(item.get("y_offset", 0)),
                "fit_mode": str(item.get("fill_mode", "loop")),
                "placement": {
                    "left": float(item.get("left", 0.0)),
                    "top": float(item.get("top", 0.0)),
                    "width": float(item.get("width", 100.0)),
                    "height": float(item.get("height", 100.0)),
                },
            }
            self._run(
                ToolName.OVERLAY_SUBJECT_CLIP,
                overlay_args,
                "overlay foreground slot",
                context,
                tool_calls,
                tool_results,
            )

        plan = ExecutionPlan(
            user_goal=request.text,
            intent="layout_generation",
            selected_asset_ids=ordered_ids,
            required_context=["layout_context", "template"],
            tool_calls=tool_calls,
        )
        context["processing_warnings"] = warnings
        context["final_video"] = context.get("timeline", {}).get("path", "") if isinstance(context.get("timeline"), dict) else ""
        return DirectLayoutResult(plan=plan, context=context, tool_results=tool_results, warnings=warnings)

    def _run(
        self,
        tool: ToolName,
        arguments: dict[str, object],
        reason: str,
        context: dict[str, object],
        calls: list[ToolCall],
        results: list[ToolResult],
    ) -> ToolResult:
        call = ToolCall(tool=tool, reason=reason, arguments=arguments)
        calls.append(call)
        result = self.tools.execute(call, context)
        results.append(result)
        return result

    @staticmethod
    def _source_path(asset: LivePhotoAsset, context: dict[str, object]) -> str:
        maps = [
            context.get("stabilized_assets", {}),
            context.get("color_enhanced", {}),
            context.get("speed_segments", {}),
            context.get("trimmed_segments", {}),
        ]
        for mapping in maps:
            if not isinstance(mapping, dict):
                continue
            value = mapping.get(asset.asset_id)
            if isinstance(value, dict):
                value = value.get("path")
            if value:
                return str(value)
        return str(asset.motion_path or asset.image_path)

    @staticmethod
    def _slot_payload(item: dict[str, object]) -> dict[str, object]:
        return {
            "grid_x": int(item.get("grid_x", 0)),
            "grid_y": int(item.get("grid_y", 0)),
            "grid_w": int(item.get("grid_w", 120)),
            "grid_h": int(item.get("grid_h", 160)),
            "start_time_s": float(item.get("start_time_s", 0.0)),
            "end_time_s": float(item.get("end_time_s", 6.0)),
            "fill_mode": str(item.get("fill_mode", "freeze")),
            "pin_to_top": bool(item.get("pin_to_top", False)),
        }

    @staticmethod
    def _canvas_value(items: list[dict[str, object]], key: str, default: int) -> int:
        for item in items:
            value = item.get(key)
            if isinstance(value, (int, float)) and value > 0:
                return int(value)
        return default

    @staticmethod
    def _work_dir(context: dict[str, object]):
        from pathlib import Path

        value = context.get("work_dir")
        return Path(str(value)) if value else Path.cwd() / ".agent_work"

    def _result(
        self,
        request: AgentRequest,
        ordered_ids: list[str],
        calls: list[ToolCall],
        results: list[ToolResult],
        context: dict[str, object],
        warnings: list[str],
    ) -> DirectLayoutResult:
        plan = ExecutionPlan(
            user_goal=request.text,
            intent="layout_generation",
            selected_asset_ids=ordered_ids,
            required_context=["layout_context", "template"],
            tool_calls=calls,
        )
        context["processing_warnings"] = warnings
        return DirectLayoutResult(plan=plan, context=context, tool_results=results, warnings=warnings)
