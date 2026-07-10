from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

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
        },
        output_schema={
            "frame_count": "int",
            "frames_by_asset": "object<string,string[]>",
            "frame_interval_ms": "int",
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
            "strategy": "Cover frame policy, e.g. sharpest.",
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
)


class QwenPlanner:
    def __init__(self) -> None:
        self._local_runtime: LocalHFPlannerRuntime | None = None

    def create_plan(self, request: AgentRequest, library_summary: dict[str, object]) -> ExecutionPlan:
        backend = self._resolve_backend()
        if backend == "endpoint":
            return self._call_remote_planner(request, library_summary)
        if backend == "local_hf":
            return self._call_local_hf_planner(request, library_summary)
        raise RuntimeError(f"Unsupported planner backend: {backend}")

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
        if settings.qwen_auth_token:
            headers["Authorization"] = f"Bearer {settings.qwen_auth_token}"

        request_obj = Request(url=endpoint, data=body, headers=headers, method="POST")
        try:
            with urlopen(request_obj, timeout=settings.qwen_timeout_seconds) as response:
                raw_text = response.read().decode("utf-8")
        except HTTPError as exc:
            error_text = exc.read().decode("utf-8", errors="ignore") if exc.fp else ""
            raise RuntimeError(f"Planner endpoint HTTP {exc.code}: {error_text}") from exc
        except URLError as exc:
            raise RuntimeError(f"Planner endpoint unreachable: {exc.reason}") from exc

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

        with torch.no_grad():
            out = runtime.model.generate(
                **inputs,
                max_new_tokens=settings.local_max_new_tokens,
                do_sample=False,
            )

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

        tokenizer = AutoTokenizer.from_pretrained(str(Path(model_dir)), trust_remote_code=True)
        model_kwargs: dict[str, object] = {
            "trust_remote_code": True,
            "dtype": dtype_map[dtype_raw],
        }
        model_kwargs["device_map"] = "auto" if device_raw == "auto" else device_raw

        model = AutoModelForCausalLM.from_pretrained(str(Path(model_dir)), **model_kwargs)

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
        tool_choices = "|".join(spec.name.value for spec in TOOL_SPECS)
        tool_catalog = [
            {
                "tool": spec.name.value,
                "level": spec.level,
                "purpose": spec.purpose,
                "when_to_use": spec.when_to_use,
                "allowed_arguments": list(spec.arguments.keys()),
                "argument_help": spec.arguments,
                "output_schema": spec.output_schema,
            }
            for spec in TOOL_SPECS
        ]
        return {
            "model": settings.qwen_model,
            "response_format": "json",
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are the planning brain of a live photo agent. "
                        "Return only valid JSON that matches the ExecutionPlan schema. "
                        "Use only tools from tool_catalog. No prose."
                    ),
                },
                {
                    "role": "user",
                    "content": {
                        "request": {
                            "text": request.text,
                            "selected_asset_ids": request.selected_asset_ids,
                            "library_root": str(request.library_root),
                        },
                        "library_summary": library_summary,
                        "tool_catalog": tool_catalog,
                        "execution_rules": [
                            "Generate plan only from the user request and provided context.",
                            "If request is underspecified or ambiguous, set need_clarification=true and provide clarification_questions before any tool execution.",
                            "When selected_asset_ids is empty, do not include filter_selected.",
                            "When selected_asset_ids is non-empty, include filter_selected before summarize_results.",
                            "Only use tool arguments listed in tool_catalog.allowed_arguments.",
                            "Prefer minimal sufficient tool calls.",
                            "Start from scan_library.",
                            "The final step should be summarize_results.",
                        ],
                        "architecture_layers": {
                            "layer_1_flow": [
                                "input",
                                "intent",
                                "context",
                                "recommend",
                                "tool_selection",
                                "output",
                            ],
                            "layer_2_capability": ["L0", "L1", "L2"],
                            "layer_3_foundation": [
                                "version_management",
                                "live_photo_assets",
                                "session_memory",
                                "multimodal_memory",
                            ],
                        },
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
                },
            ],
        }

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
            }
            for spec in TOOL_SPECS
        ]
        messages = [
            QwenMessage(
                role="system",
                content=(
                    "You are the planning brain of a live photo agent. "
                    "Your job is to understand the user goal, determine required context, "
                    "arrange a fixed but adaptable execution pipeline, and return only JSON "
                    "matching the ExecutionPlan schema. Do not answer the user directly. "
                    "Do not invent tools outside the provided catalog."
                ),
            ),
            QwenMessage(
                role="user",
                content=json.dumps(
                    {
                        "text": request.text,
                        "selected_asset_ids": request.selected_asset_ids,
                        "library_summary": library_summary,
                        "tool_catalog": tool_catalog,
                        "execution_rules": [
                            "Start from the current library inventory.",
                            "Prioritize selected assets when provided.",
                            "When request is ambiguous, ask clarification questions first.",
                            "Use the user goal to drive retrieval and candidate narrowing.",
                            "Produce the smallest sufficient tool sequence.",
                            "Return valid JSON only.",
                        ],
                    },
                    ensure_ascii=False,
                ),
            ),
        ]
        return "\n".join(f"{message.role}: {message.content}" for message in messages)