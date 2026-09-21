"""AWQ int4 quantization script for Qwen2.5-7B-Instruct.

Quantizes the fp16 model to int4 using Activation-aware Weight Quantization (AWQ).
This produces a model that uses ~4GB VRAM instead of ~15GB.

Usage:
    python tests/benchmark/quantize_awq.py
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

# Limit threads to avoid CPU OOM during calibration
os.environ["OMP_NUM_THREADS"] = "8"

def main():
    model_path = "/home/vivo/models/Qwen2.5-7B-Instruct"
    quant_path = "/home/vivo/models/Qwen2.5-7B-Instruct-AWQ"
    quant_config = {
        "zero_point": True,
        "q_group_size": 128,
        "w_bit": 4,
        "version": "GEMM",
    }

    print(f"[quantize] Source: {model_path}")
    print(f"[quantize] Output: {quant_path}")
    print(f"[quantize] Config: {quant_config}")

    from awq import AutoAWQForCausalLM
    from transformers import AutoTokenizer

    # Load model in fp16 (on CPU to avoid GPU OOM during calibration)
    print("[quantize] Loading model...")
    model = AutoAWQForCausalLM.from_pretrained(
        model_path,
        device_map="auto",
        trust_remote_code=True,
    )
    tokenizer = AutoTokenizer.from_pretrained(model_path, trust_remote_code=True)

    # Use representative calibration text — needs to be long enough to activate
    # all layers. Mix of Chinese (the planner's working language) and English.
    calib_data = [
        "你是一个实况照片智能助手，用户会输入一句话描述需求，你需要分析意图并生成工具调用计划。"
        "当用户说三拼小猫时，你应该使用smart_collage工具，query参数设为小猫，k参数设为3。"
        "当用户说左右拼时，你应该使用template_collage工具，layout参数设为horizontal。"
        "当用户说导出视频时，你应该使用concat_clips和export_mp4工具。"
        "当用户说你好时，intent应该设为conversation，不需要调用任何工具。"
        "请始终返回有效的JSON格式，包含user_goal、intent、tool_calls等字段。",
        "The live photo agent helps users create video collages from their photo library. "
        "When a user asks for a triptych collage of cats, the planner should select the "
        "smart_collage tool with query set to cats and k set to 3. "
        "The smart_collage tool handles the entire pipeline: semantic search via BGE "
        "embeddings, template matching, VLM visual harmony scoring, and final video "
        "composition. The planner should not decompose smart_collage into individual "
        "L0 tools like search_by_text or extract_key_frames.",
        "帮我把第一张照片里的人物动态抠出来，贴到后面三张拼接的画面上。这个需求需要使用"
        "extract_subject_matte工具来提取主体蒙版，concat_clips工具来构建背景时间线，"
        "overlay_subject_clip工具将抠出的主体叠加到背景上。intent应该设为"
        "subject_overlay_composite。请确保工具调用顺序正确。",
    ]

    print(f"[quantize] Calibration set: {len(calib_data)} samples")

    # Quantize
    print("[quantize] Starting AWQ quantization...")
    model.quantize(
        tokenizer,
        quant_config=quant_config,
        calib_data=calib_data,
    )

    # Save
    print(f"[quantize] Saving to {quant_path}...")
    Path(quant_path).mkdir(parents=True, exist_ok=True)
    model.save_quantized(quant_path)
    tokenizer.save_pretrained(quant_path)

    # Check output size
    total_size = 0
    for f in Path(quant_path).iterdir():
        if f.is_file():
            size = f.stat().st_size
            total_size += size
            print(f"  {f.name}: {size/1e9:.2f}GB")

    print(f"\n[quantize] Done! Total size: {total_size/1e9:.2f}GB")
    print(f"[quantize] Model saved to: {quant_path}")


if __name__ == "__main__":
    main()
