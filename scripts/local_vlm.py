#!/usr/bin/env python3
"""Lightweight OpenAI-compatible API server for local multimodal models.

Uses transformers directly (no FlashInfer required), works on RTX 5090
Blackwell (SM 12.x) where vLLM/SGLang's FlashInfer dependency fails.

Start:  python scripts/local_vlm.py
"""

import io
import argparse
import base64
import json
import logging
import time
import uuid
from typing import Any

import torch
import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse
from PIL import Image
from transformers import AutoProcessor, Qwen2_5_VLForConditionalGeneration

app = FastAPI(title="local-vlm")
model = None
processor = None
device = "cuda"
model_path = None


def _extract_image_from_content(content: list[dict]) -> Image.Image | None:
    for block in content:
        if isinstance(block, dict) and block.get("type") == "image_url":
            url = block.get("image_url", {}).get("url", "")
            if url.startswith("data:image/"):
                header, b64 = url.split(",", 1)
                return Image.open(io.BytesIO(base64.b64decode(b64)))
    return None


def _build_messages(payload: dict) -> list[dict]:
    messages = payload.get("messages", [])
    result = []
    for msg in messages:
        role = msg.get("role", "user")
        content = msg.get("content")
        if isinstance(content, list):
            text_parts = []
            images = []
            for block in content:
                if isinstance(block, dict):
                    if block.get("type") == "text":
                        text_parts.append(block.get("text", ""))
                    elif block.get("type") == "image_url":
                        url = block.get("image_url", {}).get("url", "")
                        if url.startswith("data:image/"):
                            header, b64 = url.split(",", 1)
                            images.append(Image.open(io.BytesIO(base64.b64decode(b64))))
            result.append({"role": role, "content": "\n".join(text_parts), "images": images})
        else:
            result.append({"role": role, "content": str(content), "images": []})
    return result


def _chat_completion(payload: dict) -> dict:
    messages = payload.get("messages", [])
    temperature = payload.get("temperature", 0.7)
    max_tokens = payload.get("max_tokens", 2048)
    response_format = payload.get("response_format", {})

    use_json_mode = False
    if isinstance(response_format, dict) and response_format.get("type") == "json_object":
        use_json_mode = True

    conv = _build_messages(payload)
    all_images = []
    for msg in conv:
        for img in msg.get("images", []):
            all_images.append(img)

    _logger = logging.getLogger("local_vlm")
    _logger.info(
        "[local_vlm] messages=%d images=%d first_image_size=%s",
        len(conv), len(all_images), all_images[0].size if all_images else "N/A",
    )

    if all_images:
        qwen_messages = []
        for msg in conv:
            text = msg["content"]
            images = msg.get("images", [])
            content_list = []
            for img in images:
                content_list.append({"type": "image", "image": img})
            content_list.append({"type": "text", "text": text})
            qwen_messages.append({"role": msg["role"], "content": content_list})
        text = processor.apply_chat_template(qwen_messages, tokenize=False, add_generation_prompt=True)
        inputs = processor(text=[text], images=all_images, return_tensors="pt", padding=True).to(device)
    else:
        text_messages = [{"role": m["role"], "content": m["content"]} for m in conv]
        text = processor.apply_chat_template(text_messages, tokenize=False, add_generation_prompt=True)
        inputs = processor.tokenizer(text, return_tensors="pt", padding=True).to(device)

    generate_kwargs: dict[str, Any] = {
        "max_new_tokens": max_tokens,
        "temperature": temperature,
        "do_sample": temperature > 0,
        "top_p": 0.9,
        "eos_token_id": processor.tokenizer.eos_token_id,
        "pad_token_id": processor.tokenizer.pad_token_id if processor.tokenizer.pad_token_id else processor.tokenizer.eos_token_id,
    }

    with torch.no_grad():
        generated_ids = model.generate(**inputs, **generate_kwargs)

    generated_ids = generated_ids[:, inputs["input_ids"].shape[1]:]
    output_text = processor.batch_decode(generated_ids, skip_special_tokens=True, clean_up_tokenization_spaces=False)[0].strip()

    if use_json_mode and not output_text.startswith("{"):
        if "```" in output_text:
            if "```json" in output_text:
                output_text = output_text.split("```json")[-1].split("```")[0]
            elif output_text.count("```") >= 2:
                output_text = output_text.split("```")[1]
            output_text = output_text.strip()

    return {
        "id": f"chatcmpl-{uuid.uuid4().hex[:12]}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": model_path or "local-vlm",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": output_text},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }


@app.get("/health")
async def health():
    return {"status": "ok", "model": model_path or "local-vlm"}


@app.get("/v1/models")
async def list_models():
    return {"object": "list", "data": [{"id": model_path or "local-vlm", "object": "model"}]}


@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    payload = await request.json()
    result = _chat_completion(payload)
    return JSONResponse(content=result)


def main():
    global model, processor, device, model_path

    parser = argparse.ArgumentParser(description="Local VLM Server")
    parser.add_argument("--model", type=str, default="/home/vivo/models/Qwen2.5-VL-7B-Instruct",
                        help="Path to model directory")
    parser.add_argument("--port", type=int, default=8100, help="Server port")
    parser.add_argument("--host", type=str, default="127.0.0.1", help="Server host")
    parser.add_argument("--device", type=str, default="cuda", help="Device (cuda/cpu)")
    parser.add_argument("--dtype", type=str, default="auto", help="Model dtype")
    args = parser.parse_args()

    model_path = args.model
    device = args.device

    print(f"[local-vlm] Loading model from {model_path} ...")
    model = Qwen2_5_VLForConditionalGeneration.from_pretrained(
        model_path,
        torch_dtype=args.dtype if args.dtype != "auto" else "auto",
        device_map="auto",
        trust_remote_code=True,
    )
    processor = AutoProcessor.from_pretrained(model_path, trust_remote_code=True)
    print(f"[local-vlm] Model loaded. Starting server on {args.host}:{args.port}")

    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()