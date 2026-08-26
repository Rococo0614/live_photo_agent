from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from threading import Thread
from time import perf_counter
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

logger = logging.getLogger("live_photo_agent.planner")

from .capability.contracts import TOOL_CONTRACTS
from .config import settings
from .models import AgentRequest, ExecutionPlan, ToolName


@dataclass(slots=True)
class QwenMessage:
    role: str
    content: str


@dataclass(frozen=True, slots=True)
class ToolSpec:
    name: ToolName
    level: str
    purpose: str
    when_to_use: str
    arguments: dict[str, str]
    output_schema: dict[str, str]


@dataclass(slots=True)
class LocalHFPlannerRuntime:
    tokenizer: Any
    model: Any


def _resolve_hf_model_dir(raw_model_dir: Path) -> Path:
    """Resolve Hugging Face cache roots to a concrete snapshot directory."""
    if (raw_model_dir / "config.json").exists():
        return raw_model_dir

    snapshots_dir = raw_model_dir / "snapshots"
    if not snapshots_dir.exists() or not snapshots_dir.is_dir():
        raise RuntimeError(
            f"LPA_LOCAL_MODEL_DIR does not look like a model directory: {raw_model_dir}"
        )

    ref_main = raw_model_dir / "refs" / "main"
    if ref_main.exists():
        ref_value = ref_main.read_text(encoding="utf-8").strip()
        if ref_value:
            candidate = snapshots_dir / ref_value
            if candidate.exists() and candidate.is_dir() and (candidate / "config.json").exists():
                return candidate

    candidates = sorted(path for path in snapshots_dir.iterdir() if path.is_dir())
    for candidate in candidates:
        if (candidate / "config.json").exists():
            return candidate

    raise RuntimeError(
        f"No usable snapshot found under {snapshots_dir}. Expected config.json in at least one snapshot directory."
    )


def _ensure_torch_autocast_compat(torch_module: Any) -> None:
    """Patch torch.is_autocast_enabled for transformers versions expecting a device_type arg."""
    original = getattr(torch_module, "is_autocast_enabled", None)
    if original is None:
        return
    if getattr(original, "_lpa_device_type_compat", False):
        return

    def _compat_is_autocast_enabled(device_type: str | None = None) -> bool:
        try:
            if device_type is None:
                return bool(original())
            return bool(original(device_type))
        except TypeError:
            return bool(original())

    setattr(_compat_is_autocast_enabled, "_lpa_device_type_compat", True)
    torch_module.is_autocast_enabled = _compat_is_autocast_enabled


TOOL_SPECS: tuple[ToolSpec, ...] = (
    ToolSpec(
        name=ToolName.SCAN_LIBRARY,
        level="L0",
        purpose="Scan the library and build the current live photo inventory.",
        when_to_use="Use first when the request depends on the current asset set or global library context.",
        arguments={"library_root": "Optional path override for the library root."},
        output_schema={"asset_count": "int", "asset_ids": "string[]"},
    ),
    ToolSpec(
        name=ToolName.FILTER_SELECTED,
        level="L0",
        purpose="Prioritize and isolate assets explicitly selected by the user.",
        when_to_use="Use when selected_asset_ids are present in the request.",
        arguments={"selected_asset_ids": "List of selected asset IDs from the user request."},
        output_schema={"selected_count": "int", "selected_ids": "string[]"},
    ),
    ToolSpec(
        name=ToolName.SEARCH_BY_TEXT,
        level="L1",
        purpose="Narrow candidate assets using the user's request semantics.",
        when_to_use="Use when the request needs semantic retrieval or candidate narrowing.",
        arguments={"query": "Natural-language search query derived from the user goal."},
        output_schema={"match_count": "int", "matched_ids": "string[]"},
    ),
    ToolSpec(
        name=ToolName.DRAFT_EDIT_PLAN,
        level="L2",
        purpose="Produce a concrete curation or editing plan over the current focus assets.",
        when_to_use="Use when the request asks for sorting, highlighting, cover selection, curation, or edits.",
        arguments={"style": "Requested output style such as social_highlight or memory_story."},
        output_schema={"style": "string", "asset_count": "int", "suggestions": "string[]"},
    ),
    ToolSpec(
        name=ToolName.SUMMARIZE_RESULTS,
        level="L1",
        purpose="Generate the final user-facing summary from the execution context.",
        when_to_use="Use as the final step after retrieval or planning tools have prepared the focus assets.",
        arguments={"response_style": "Desired response style such as concise or detailed."},
        output_schema={"response_style": "string", "focus_asset_ids": "string[]", "focus_asset_count": "int"},
    ),
    ToolSpec(
        name=ToolName.EXTRACT_KEY_FRAMES,
        level="L0",
        purpose="Extract representative frames from live photo motion clips.",
        when_to_use="Use before visual scoring tasks that require frame-level candidates.",
        arguments={
            "asset_ids": "Optional list of target asset IDs.",
            "frame_interval_ms": "Frame extraction interval in milliseconds.",
            "max_frames": "Maximum frames per asset.",
            "sampling_strategy": "Sampling mode: uniform or auto_adaptive based on clip dynamics.",
        },
        output_schema={
            "frame_count": "int",
            "frames_by_asset": "object<string,string[]>",
            "frame_interval_ms": "int",
            "sampling_strategy": "string",
            "effective_settings": "object<string,object>",
        },
    ),
    ToolSpec(
        name=ToolName.ESTIMATE_MOTION_SCORE,
        level="L0",
        purpose="Estimate motion quality scores for candidate live photos.",
        when_to_use="Use when ranking candidates by dynamic quality.",
        arguments={"asset_ids": "Optional list of target asset IDs."},
        output_schema={"motion_scores": "object<string,float>"},
    ),
    ToolSpec(
        name=ToolName.SUBJECT_SEGMENTATION,
        level="L0",
        purpose="Generate subject segmentation masks for assets.",
        when_to_use="Use when composition requires foreground/background understanding.",
        arguments={
            "asset_ids": "Optional list of target asset IDs.",
            "mode": "Segmentation strategy, e.g. person_first.",
        },
        output_schema={
            "mask_count": "int",
            "segmented_asset_ids": "string[]",
            "mask_paths": "object<string,string>",
            "foreground_ratios": "object<string,float>",
        },
    ),
    ToolSpec(
        name=ToolName.SELECT_COVER_FRAME,
        level="L0",
        purpose="Select cover frames for selected assets.",
        when_to_use="Use when final output requires cover recommendation.",
        arguments={
            "asset_ids": "Optional list of target asset IDs.",
            "strategy": "Cover frame policy: auto_adaptive, sharpest, vibrant, first, middle, or last.",
        },
        output_schema={"cover_frames": "object<string,string>", "strategy": "string"},
    ),
    ToolSpec(
        name=ToolName.CLIP_TRIM,
        level="L0",
        purpose="Trim motion clip segments.",
        when_to_use="Use to normalize segment duration before concatenation.",
        arguments={
            "asset_ids": "Optional list of target asset IDs.",
            "start_ms": "Trim start offset in milliseconds.",
            "end_ms": "Trim end offset in milliseconds.",
        },
        output_schema={"trimmed_segments": "object<string,object>"},
    ),
    ToolSpec(
        name=ToolName.CLIP_SPEED,
        level="L0",
        purpose="Apply speed changes to clip segments.",
        when_to_use="Use when pacing needs adjustment before export.",
        arguments={
            "asset_ids": "Optional list of target asset IDs.",
            "speed": "Playback speed ratio.",
        },
        output_schema={"speed_adjusted_segments": "object<string,object>"},
    ),
    ToolSpec(
        name=ToolName.COLOR_ENHANCE,
        level="L0",
        purpose="Apply baseline color enhancement profile.",
        when_to_use="Use when clips need consistent visual tone.",
        arguments={
            "asset_ids": "Optional list of target asset IDs.",
            "profile": "Enhancement preset profile.",
        },
        output_schema={"enhanced_assets": "object<string,string>", "profile": "string"},
    ),
    ToolSpec(
        name=ToolName.STABILIZE_CLIP,
        level="L0",
        purpose="Stabilize shaky motion clips.",
        when_to_use="Use when motion instability harms output quality.",
        arguments={"asset_ids": "Optional list of target asset IDs."},
        output_schema={"stabilized_assets": "object<string,string>"},
    ),
    ToolSpec(
        name=ToolName.CONCAT_CLIPS,
        level="L0",
        purpose="Concatenate ordered clip segments into one timeline.",
        when_to_use="Use after clip-level transforms and before final overlays/export.",
        arguments={
            "asset_ids": "Optional list of target asset IDs.",
            "order": "Optional explicit concatenation order.",
            "layout": "Composition mode: timeline, horizontal, vertical, triptych_portrait, or triptych_landscape.",
            "canvas": "Canvas size for spatial composition, e.g. 1080x1920.",
        },
        output_schema={"timeline_id": "string", "segment_count": "int"},
    ),
    ToolSpec(
        name=ToolName.ADD_TEXT_OVERLAY,
        level="L0",
        purpose="Add text overlay to current timeline.",
        when_to_use="Use for social caption/title rendering into video.",
        arguments={
            "text": "Overlay text content.",
            "style": "Overlay style preset.",
        },
        output_schema={"overlay_applied": "bool"},
    ),
    ToolSpec(
        name=ToolName.MIX_AUDIO_BGM,
        level="L0",
        purpose="Mix background music with timeline audio.",
        when_to_use="Use when output needs music enrichment.",
        arguments={
            "track_id": "BGM track identifier.",
            "gain_db": "Track gain in decibel.",
        },
        output_schema={"audio_mix_applied": "bool"},
    ),
    ToolSpec(
        name=ToolName.EXPORT_MP4,
        level="L0",
        purpose="Export final timeline to mp4.",
        when_to_use="Use as final media render step.",
        arguments={
            "output_name": "Output file base name.",
            "resolution": "Target resolution, e.g. 1080x1920.",
        },
        output_schema={"output_path": "string(path)", "duration_ms": "int"},
    ),
    ToolSpec(
        name=ToolName.EXTRACT_SUBJECT_MATTE,
        level="L0",
        purpose=(
            "Cut out the moving subject across an entire motion clip (per-frame alpha matte), "
            "preserving its motion trajectory rather than a single static frame."
        ),
        when_to_use=(
            "Use when the request wants a subject/person cut out of one live photo so it can be "
            "pasted onto another composition (e.g. 'expression', 'cutout', 'paste the moving person')."
        ),
        arguments={
            "asset_ids": "Optional list of target asset IDs (typically the single source live photo).",
            "mode": "Background-subtraction backend: mog2 or knn.",
        },
        output_schema={
            "matte_count": "int",
            "matted_asset_ids": "string[]",
            "average_foreground_ratios": "object<string,float>",
        },
    ),
    ToolSpec(
        name=ToolName.OVERLAY_SUBJECT_CLIP,
        level="L0",
        purpose="Composite a previously extracted subject matte onto the current timeline/canvas.",
        when_to_use=(
            "Use after concat_clips has built the background composition and extract_subject_matte has "
            "produced the cutout, to paste the moving subject onto that composition."
        ),
        arguments={
            "foreground_asset_id": "Asset ID whose subject matte (from extract_subject_matte) to paste.",
            "anchor": "Position preset: top_left, top_right, bottom_left, bottom_right, or center.",
            "scale": "Foreground scale factor relative to its own frame size, e.g. 0.45.",
            "x_offset": "Extra horizontal pixel offset from the anchor position.",
            "y_offset": "Extra vertical pixel offset from the anchor position.",
            "fit_mode": "Duration alignment: loop (repeat foreground to fill background) or trim.",
        },
        output_schema={"overlay_applied": "bool", "output_path": "string(path)"},
    ),
)


class QwenPlanner:
    def __init__(self) -> None:
        self._local_runtime: LocalHFPlannerRuntime | None = None

    def reset_runtime(self) -> None:
        self._local_runtime = None

    def create_plan(self, request: AgentRequest, library_summary: dict[str, object]) -> ExecutionPlan:
        backend = self._resolve_backend()
        start = perf_counter()
        logger.info("[PLANNER TIMER] create_plan start (backend=%s)", backend)
        try:
            if backend == "endpoint":
                plan = self._call_remote_planner(request, library_summary)
            elif backend == "local_hf":
                plan = self._call_local_hf_planner(request, library_summary)
            else:
                raise RuntimeError(f"Unsupported planner backend: {backend}")
        finally:
            elapsed_ms = (perf_counter() - start) * 1000
            logger.info(
                "[PLANNER TIMER] create_plan done (backend=%s) elapsed=%.1fms tool_count=%d",
                backend,
                elapsed_ms,
                len(getattr(plan, "tool_calls", [])) if "plan" in dir() else 0,
            )
        return plan

    def runtime_info(self) -> dict[str, object]:
        backend = self._resolve_backend()
        info: dict[str, object] = {
            "planner_backend": backend,
            "planner_model": settings.qwen_model,
        }
        if backend == "endpoint":
            info["planner_endpoint"] = settings.qwen_endpoint
        elif backend == "local_hf":
            info["local_model_dir"] = str(settings.local_model_dir) if settings.local_model_dir else None
            info["local_device"] = settings.local_device
            info["local_dtype"] = settings.local_dtype
        return info

    def _resolve_backend(self) -> str:
        backend = settings.planner_backend.strip().lower()
        if backend in {"endpoint", "local_hf"}:
            return backend
        if backend != "auto":
            raise RuntimeError("LPA_PLANNER_BACKEND must be one of: auto, endpoint, local_hf")
        if settings.qwen_endpoint:
            return "endpoint"
        if settings.local_model_dir:
            return "local_hf"
        raise RuntimeError(
            "Planner backend resolution failed. Set LPA_QWEN_ENDPOINT for endpoint mode or "
            "set LPA_LOCAL_MODEL_DIR for local_hf mode."
        )

    def _call_remote_planner(self, request: AgentRequest, library_summary: dict[str, object]) -> ExecutionPlan:
        endpoint = settings.qwen_endpoint
        if endpoint is None:
            raise RuntimeError("LPA_QWEN_ENDPOINT is missing")

        payload = self._build_planner_payload(request, library_summary)
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if settings.effective_qwen_auth_token:
            headers["Authorization"] = f"Bearer {settings.effective_qwen_auth_token}"
        if settings.qwen_workspace_id:
            # Different docs use different capitalization; include both for compatibility.
            headers["X-DashScope-WorkSpace"] = settings.qwen_workspace_id
            headers["X-DashScope-Workspace"] = settings.qwen_workspace_id

        request_obj = Request(url=endpoint, data=body, headers=headers, method="POST")
        net_start = perf_counter()
        try:
            with urlopen(request_obj, timeout=settings.planner_generation_timeout_seconds) as response:
                raw_text = response.read().decode("utf-8")
        except HTTPError as exc:
            error_text = exc.read().decode("utf-8", errors="ignore") if exc.fp else ""
            raise RuntimeError(f"Planner endpoint HTTP {exc.code}: {error_text}") from exc
        except URLError as exc:
            raise RuntimeError(f"Planner endpoint unreachable: {exc.reason}") from exc
        logger.info(
            "[PLANNER TIMER] remote network elapsed=%.1fms timeout_limit=%.1fs",
            (perf_counter() - net_start) * 1000,
            settings.planner_generation_timeout_seconds,
        )

        plan = self._parse_plan_response(raw_text)
        return ExecutionPlan.model_validate(plan)

    def _call_local_hf_planner(self, request: AgentRequest, library_summary: dict[str, object]) -> ExecutionPlan:
        runtime = self._get_or_create_local_runtime()
        local_messages = self._build_local_messages(request, library_summary)

        try:
            import torch
        except Exception as exc:  # noqa: BLE001
            raise RuntimeError("Local HF backend requires torch. Install torch in the active environment.") from exc

        prompt = runtime.tokenizer.apply_chat_template(local_messages, tokenize=False, add_generation_prompt=True)
        inputs = runtime.tokenizer(prompt, return_tensors="pt")
        if hasattr(runtime.model, "device") and runtime.model.device is not None:
            inputs = {k: v.to(runtime.model.device) for k, v in inputs.items()}

        box: dict[str, Any] = {}

        def _generate() -> None:
            with torch.no_grad():
                box["out"] = runtime.model.generate(
                    **inputs,
                    max_new_tokens=settings.local_max_new_tokens,
                    do_sample=False,
                )

        worker = Thread(target=_generate, name="local-planner-generate", daemon=True)
        worker.start()
        timeout = settings.planner_generation_timeout_seconds
        worker.join(timeout=timeout)
        if worker.is_alive():
            raise RuntimeError(
                f"Local HF planner generation exceeded planner_generation_timeout_seconds={timeout}s; aborted."
            )
        if "out" not in box:
            raise RuntimeError("Local HF planner generation produced no output.")

        out = box["out"]
        generated_tokens = out[:, inputs["input_ids"].shape[1] :]
        generated_text = runtime.tokenizer.decode(generated_tokens[0], skip_special_tokens=True).strip()
        plan_dict = self._parse_json_text(generated_text)
        return ExecutionPlan.model_validate(plan_dict)

    def _get_or_create_local_runtime(self) -> LocalHFPlannerRuntime:
        if self._local_runtime is not None:
            return self._local_runtime

        model_dir = settings.local_model_dir
        if model_dir is None:
            raise RuntimeError("LPA_LOCAL_MODEL_DIR is required when planner backend is local_hf")

        try:
            import torch
            from transformers import AutoModelForCausalLM, AutoTokenizer
        except Exception as exc:  # noqa: BLE001
            raise RuntimeError(
                "Local HF backend requires transformers and torch. Install dependencies in the active environment."
            ) from exc

        dtype_raw = settings.local_dtype.strip().lower()
        dtype_map: dict[str, Any] = {
            "float32": torch.float32,
            "float16": torch.float16,
            "bfloat16": torch.bfloat16,
            "auto": "auto",
        }
        if dtype_raw not in dtype_map:
            raise RuntimeError("LPA_LOCAL_DTYPE must be one of: float32, float16, bfloat16, auto")

        device_raw = settings.local_device.strip().lower()
        if device_raw not in {"cpu", "cuda", "mps", "auto"}:
            raise RuntimeError("LPA_LOCAL_DEVICE must be one of: cpu, cuda, mps, auto")

        model_path = _resolve_hf_model_dir(Path(model_dir))
        _ensure_torch_autocast_compat(torch)

        tokenizer = AutoTokenizer.from_pretrained(str(model_path), trust_remote_code=True)
        model_kwargs: dict[str, object] = {
            "trust_remote_code": True,
            "dtype": dtype_map[dtype_raw],
        }
        model_kwargs["device_map"] = "auto" if device_raw == "auto" else device_raw

        model = AutoModelForCausalLM.from_pretrained(str(model_path), **model_kwargs)

        # Avoid noisy warnings when do_sample=False.
        if hasattr(model, "generation_config"):
            model.generation_config.temperature = None
            model.generation_config.top_p = None
            model.generation_config.top_k = None

        self._local_runtime = LocalHFPlannerRuntime(tokenizer=tokenizer, model=model)
        return self._local_runtime

    def _build_local_messages(self, request: AgentRequest, library_summary: dict[str, object]) -> list[dict[str, str]]:
        payload = self._build_planner_payload(request, library_summary)
        raw_messages = payload.get("messages", [])
        local_messages: list[dict[str, str]] = []
        for msg in raw_messages:
            role = str(msg.get("role", "user")) if isinstance(msg, dict) else "user"
            content_raw = msg.get("content", "") if isinstance(msg, dict) else str(msg)
            if isinstance(content_raw, str):
                content = content_raw
            else:
                content = json.dumps(content_raw, ensure_ascii=False)
            local_messages.append({"role": role, "content": content})
        return local_messages

    def _build_planner_payload(self, request: AgentRequest, library_summary: dict[str, object]) -> dict[str, object]:
        from .foundation.layout_resolver import LayoutResolver

        tool_choices = "|".join(spec.name.value for spec in TOOL_SPECS)
        layout_assets = self._extract_layout_assets(request.layout_context)
        edit_directives = self._extract_edit_directives(request.layout_context)
        # Deterministic spatial layout: grid coords -> canvas percentages + z-order.
        # This is authoritative; the planner must use it instead of guessing geometry.
        composition_template = LayoutResolver().resolve(request.layout_context).model_dump(mode="json")
        # Slim tool catalog: the planner only needs tool name + purpose + argument
        # names to plan a valid tool chain. The remaining contract metadata
        # (preconditions, side_effects, failure_modes, security_scope, timings,
        # quality_metrics, full argument_help) is for the local execution engine
        # and UI, not the LLM — sending it inflates the prompt and is the main
        # cause of 45-67s endpoint latency. Keep it minimal.
        tool_catalog = [
            {
                "tool": spec.name.value,
                "purpose": spec.purpose,
                "allowed_arguments": list(spec.arguments.keys()),
            }
            for spec in TOOL_SPECS
        ]
        return {
            "model": settings.qwen_model,
            "response_format": {"type": "json_object"},
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are the planning brain of a live photo agent. "
                        "Return only valid JSON that matches the ExecutionPlan schema. "
                        "Use only tools from tool_catalog. "
                        "If guided_tool_names is non-empty, prefer those tools when they fit the request and explain any omission in tool_calls.reason. "
                        "Treat request.layout_assets and request.edit_directives as authoritative user-provided edit constraints. "
                        "If request.edit_directives is non-empty, you must preserve those edit intents in the planned reasoning and relevant tool arguments when applicable. "
                        "request.composition_template is the AUTHORITATIVE spatial layout, computed deterministically from the user's grid. "
                        "You MUST honor it: background slots (role=background) become a single compose_videos_spatial call whose placements match each slot's "
                        "left_pct/top_pct/width_pct/height_pct and z_index ordering; foreground slots (role=foreground) become subject_segmentation + overlay_subject_clip "
                        "using that slot's anchor/scale/x_offset/y_offset. Do NOT fall back to concat_clips for a spatial layout, and do NOT recompute coordinates yourself. "
                        "If library_summary.reusable_strategies is provided, treat it as prior successful candidates "
                        "and adapt only when it fits the current request; do not copy blindly. "
                        "Do not infer hidden rules or default workflow stages beyond tool boundaries. "
                        "Layered layout is expected, not an error: when layout_context items carry "
                        "foreground=true/is_overlay=true or distinct z_index, a full-canvas item overlapping "
                        "background tiles is an intentional foreground (e.g. subject segmentation) over a backdrop. "
                        "Do NOT raise need_clarification for such overlap; plan the foreground extraction/overlay directly. "
                        "Only ask for clarification when coordinates are genuinely missing or the spatial intent is truly ambiguous. "
                        "No prose."
                    ),
                },
                {
                    "role": "user",
                    "content": json.dumps(
                        {
                            "request": {
                                "text": request.text,
                                "selected_asset_ids": request.selected_asset_ids,
                                "guided_tool_names": [tool.value for tool in request.guided_tool_names],
                                "library_root": str(request.library_root),
                                "layout_assets": layout_assets,
                                "composition_template": composition_template,
                                "edit_directives": edit_directives,
                                "has_canvas_edits": bool(edit_directives),
                                "operation_log": request.operation_log,
                            },
                            "library_summary": library_summary,
                            "tool_catalog": tool_catalog,
                            "execution_plan_schema": {
                                "user_goal": "string",
                                "intent": "string",
                                "selected_asset_ids": ["string"],
                                "required_context": ["string"],
                                "need_clarification": "boolean(default=false)",
                                "clarification_questions": ["string"],
                                "blocking_missing_info": ["string"],
                                "tool_calls": [
                                    {
                                        "tool": tool_choices,
                                        "reason": "string",
                                        "arguments": {"key": "value"},
                                    }
                                ],
                            },
                        },
                        ensure_ascii=False,
                    ),
                },
            ],
        }

    def _extract_layout_assets(self, layout_context: list[dict[str, object]]) -> list[dict[str, object]]:
        assets: list[dict[str, object]] = []
        for item in layout_context:
            if not isinstance(item, dict):
                continue
            asset_id = item.get("asset_id")
            if not asset_id:
                continue
            assets.append(
                {
                    "asset_id": str(asset_id),
                    "id": str(item.get("id", "")),
                    "label": str(item.get("label", "")),
                    "order": int(item.get("order", 0)) if isinstance(item.get("order"), int) else item.get("order"),
                    "grid": {
                        "x": item.get("grid_x"),
                        "y": item.get("grid_y"),
                        "w": item.get("grid_w"),
                        "h": item.get("grid_h"),
                    },
                    "z_index": item.get("z_index"),
                }
            )
        return assets

    def _extract_edit_directives(self, layout_context: list[dict[str, object]]) -> list[dict[str, object]]:
        directives: list[dict[str, object]] = []
        for item in layout_context:
            if not isinstance(item, dict):
                continue
            edit_rect = item.get("edit_rect")
            edit_prompt = item.get("edit_prompt")
            if not edit_rect and not edit_prompt:
                continue
            directives.append(
                {
                    "asset_id": str(item.get("asset_id", "")),
                    "layout_id": str(item.get("id", "")),
                    "label": str(item.get("label", "")),
                    "edit_prompt": str(edit_prompt or "").strip(),
                    "edit_rect": edit_rect if isinstance(edit_rect, dict) else None,
                    "foreground": bool(item.get("foreground") or item.get("is_overlay")),
                }
            )
        return directives

    def _parse_plan_response(self, response_text: str) -> dict[str, object]:
        decoded = json.loads(response_text)
        if isinstance(decoded, dict):
            if "plan" in decoded and isinstance(decoded["plan"], dict):
                return dict(decoded["plan"])
            choices = decoded.get("choices")
            if isinstance(choices, list) and choices:
                first_choice = choices[0]
                if isinstance(first_choice, dict):
                    message = first_choice.get("message")
                    if isinstance(message, dict):
                        content = message.get("content")
                        if isinstance(content, str):
                            return self._parse_json_text(content)
                        if isinstance(content, list):
                            text_parts = [
                                str(part.get("text", ""))
                                for part in content
                                if isinstance(part, dict) and part.get("type") == "text"
                            ]
                            if text_parts:
                                return self._parse_json_text("\n".join(text_parts))
            for key in ("output_text", "text", "content"):
                candidate = decoded.get(key)
                if isinstance(candidate, str):
                    return self._parse_json_text(candidate)
            if "tool_calls" in decoded:
                return dict(decoded)
        raise RuntimeError("Planner response does not contain a valid ExecutionPlan payload")

    def _parse_json_text(self, text: str) -> dict[str, object]:
        stripped = text.strip()
        if stripped.startswith("```"):
            lines = [line for line in stripped.splitlines() if not line.startswith("```")]
            stripped = "\n".join(lines).strip()
        try:
            parsed = json.loads(stripped)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass

        start = stripped.find("{")
        end = stripped.rfind("}")
        if start == -1 or end == -1 or start >= end:
            raise RuntimeError("Planner text output is not valid JSON")
        parsed = json.loads(stripped[start : end + 1])
        if not isinstance(parsed, dict):
            raise RuntimeError("Planner JSON payload must be an object")
        return parsed

    def preview_prompt(self, request: AgentRequest, library_summary: dict[str, object]) -> str:
        tool_catalog = [
            {
                "tool": spec.name.value,
                "level": spec.level,
                "purpose": spec.purpose,
                "when_to_use": spec.when_to_use,
                "allowed_arguments": list(spec.arguments.keys()),
                "argument_help": spec.arguments,
                "output_schema": spec.output_schema,
                "preconditions": list(contract.preconditions),
                "side_effects": list(contract.side_effects),
                "failure_modes": list(contract.failure_modes),
                "idempotent": contract.idempotent,
                "security_scope": contract.security_scope,
                "timeout_budget_ms": contract.timeout_budget_ms,
                "quality_metrics": list(contract.quality_metrics),
            }
            for spec in TOOL_SPECS
            for contract in [TOOL_CONTRACTS[spec.name]]
        ]
        messages = [
            QwenMessage(
                role="system",
                content=(
                    "You are the planning brain of a live photo agent. "
                    "Your job is to understand the user goal and return only JSON matching "
                    "the ExecutionPlan schema. Do not answer the user directly. "
                    "Do not invent tools outside the provided catalog or hidden workflow rules. "
                    "If guided_tool_names is non-empty, prefer those tools when they fit the request."
                ),
            ),
            QwenMessage(
                role="user",
                content=json.dumps(
                    {
                        "text": request.text,
                        "selected_asset_ids": request.selected_asset_ids,
                        "guided_tool_names": [tool.value for tool in request.guided_tool_names],
                        "layout_context": request.layout_context,
                        "layout_assets": self._extract_layout_assets(request.layout_context),
                        "edit_directives": self._extract_edit_directives(request.layout_context),
                        "operation_log": request.operation_log,
                        "library_summary": library_summary,
                        "tool_catalog": tool_catalog,
                    },
                    ensure_ascii=False,
                ),
            ),
        ]
        return "\n".join(f"{message.role}: {message.content}" for message in messages)