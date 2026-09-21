"""Local VLM runtime: 本地视觉语言模型统一调用入口.

所有需要 VLM 的模块 (vlm_scorer, vlm_semantics, template_parser, asset_summarize)
都通过这里加载和调用，避免重复加载模型、避免任何云端 endpoint 调用。

模型: Qwen2.5-VL-7B-Instruct (默认)，通过 LPA_VLM_MODEL_DIR 配置。
按需加载，使用完毕后由调用方释放 (release_runtime)。
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..config import settings


@dataclass(slots=True)
class LocalVLMRuntime:
    model: Any
    processor: Any


_runtime: LocalVLMRuntime | None = None


def get_runtime() -> LocalVLMRuntime:
    """获取或创建本地 VLM runtime (单例，按需加载)。"""
    global _runtime
    if _runtime is not None:
        return _runtime

    model_dir = settings.vlm_model_dir
    if model_dir is None:
        raise RuntimeError(
            "LPA_VLM_MODEL_DIR is required for local VLM. "
            "Set it to the path of a local Qwen2.5-VL model directory."
        )

    try:
        import torch
        from transformers import AutoProcessor, AutoModelForImageTextToText
    except ImportError as exc:
        raise RuntimeError(
            "Local VLM requires transformers and torch. Install dependencies in the active environment."
        ) from exc

    dtype_raw = settings.vlm_dtype.strip().lower()
    dtype_map = {
        "float32": torch.float32,
        "float16": torch.float16,
        "bfloat16": torch.bfloat16,
    }
    dtype = dtype_map.get(dtype_raw, torch.float16)

    model_path = Path(model_dir)
    print(f"  [local_vlm] Loading VLM from {model_path} (dtype={dtype_raw})...")

    processor = AutoProcessor.from_pretrained(str(model_path), trust_remote_code=True)
    model = AutoModelForImageTextToText.from_pretrained(
        str(model_path),
        dtype=dtype,
        device_map=settings.vlm_device,
        trust_remote_code=True,
    )

    _runtime = LocalVLMRuntime(model=model, processor=processor)
    print(f"  [local_vlm] Model loaded. GPU memory: {torch.cuda.memory_allocated()/1e9:.1f}GB")
    return _runtime


def release_runtime() -> None:
    """释放 VLM runtime，回收显存。"""
    global _runtime
    if _runtime is None:
        return
    try:
        import gc
        import torch
        del _runtime.model
        del _runtime.processor
        gc.collect()
        torch.cuda.empty_cache()
        torch.cuda.ipc_collect()
    except Exception:
        pass
    _runtime = None
    print("  [local_vlm] Runtime released.")


def chat_text(prompt: str, system_prompt: str = "", max_new_tokens: int | None = None) -> str:
    """纯文本对话 (无图像)。用于 vlm_scorer 等不需要视觉的场景。"""
    runtime = get_runtime()
    import torch

    messages = []
    if system_prompt:
        messages.append({"role": "system", "content": system_prompt})
    messages.append({"role": "user", "content": prompt})

    text = runtime.processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = runtime.processor(text=text, return_tensors="pt")
    inputs = {k: v.to(runtime.model.device) if hasattr(v, "to") else v for k, v in inputs.items()}

    with torch.no_grad():
        output = runtime.model.generate(
            **inputs,
            max_new_tokens=max_new_tokens or settings.vlm_max_new_tokens,
            do_sample=False,
        )

    generated = output[:, inputs["input_ids"].shape[1]:]
    return runtime.processor.decode(generated[0], skip_special_tokens=True).strip()


def chat_with_images(
    images: list,
    prompt: str,
    system_prompt: str = "",
    max_new_tokens: int | None = None,
) -> str:
    """多图对话: 发送多张 PIL Image + 文本 prompt，返回 VLM 文本响应。"""
    runtime = get_runtime()
    import torch

    content: list[dict[str, Any]] = []
    for img in images:
        content.append({"type": "image", "image": img})
    content.append({"type": "text", "text": prompt})

    messages: list[dict[str, Any]] = []
    if system_prompt:
        messages.append({"role": "system", "content": [{"type": "text", "text": system_prompt}]})
    messages.append({"role": "user", "content": content})

    text = runtime.processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = runtime.processor(text=text, images=images, return_tensors="pt", padding=True)
    inputs = {
        k: v.to(runtime.model.device) if hasattr(v, "to") else v
        for k, v in inputs.items()
    }

    with torch.no_grad():
        output = runtime.model.generate(
            **inputs,
            max_new_tokens=max_new_tokens or settings.vlm_max_new_tokens,
            do_sample=False,
        )

    generated = output[:, inputs["input_ids"].shape[1]:]
    return runtime.processor.decode(generated[0], skip_special_tokens=True).strip()


def parse_json_response(text: str) -> dict[str, Any] | None:
    """从 VLM 文本响应中提取 JSON dict。

    处理 ```json 代码块、前后缀文字等情况。
    """
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned)

    try:
        parsed = json.loads(cleaned)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass

    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start != -1 and end != -1 and start < end:
        try:
            parsed = json.loads(cleaned[start: end + 1])
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass

    return None
