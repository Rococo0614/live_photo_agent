"""Ground truth collection script for planner benchmark.

Loads the fp16 (unquantized) model, runs all benchmark cases, and saves
the raw planner output as ground truth for later comparison with quantized models.

Usage:
    python tests/benchmark/collect_ground_truth.py [--model-dir PATH] [--output PATH]

Output: JSONL file with one line per case:
    {"case_id": "C01", "input": "你好", "intent": "conversation",
     "tool_calls": [...], "raw_output": "...", "latency_ms": 123, "vram_mb": 15200}
"""
from __future__ import annotations

import argparse
import gc
import json
import sys
import time
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

# Add project root to path
PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from live_photo_agent.brain import QwenPlanner
from live_photo_agent.models import AgentRequest


def load_model(model_dir: str, device: str = "cuda", dtype: str = "float16"):
    """Load model and tokenizer, return (model, tokenizer)."""
    torch_dtype = torch.float16 if dtype == "float16" else torch.float32

    print(f"[ground_truth] Loading model from {model_dir}...")
    t0 = time.time()
    tokenizer = AutoTokenizer.from_pretrained(model_dir, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        model_dir,
        dtype=torch_dtype,
        device_map=device,
        trust_remote_code=True,
    )
    elapsed = time.time() - t0
    vram_mb = torch.cuda.memory_allocated() // (1024 * 1024) if torch.cuda.is_available() else 0
    print(f"[ground_truth] Loaded in {elapsed:.1f}s, VRAM: {vram_mb}MB")
    return model, tokenizer


def run_case(planner: QwenPlanner, case: dict, library_root: str) -> dict:
    """Run a single benchmark case through the planner."""
    case_id = case["id"]
    text = case["input"]

    request = AgentRequest(
        user_id="benchmark",
        text=text,
        library_root=library_root,
    )

    library_summary = {
        "asset_count": 1009,
        "motion_ready_asset_count": 1004,
        "library_root": library_root,
    }

    t0 = time.time()
    try:
        plan = planner.create_plan(request, library_summary)
        latency_ms = int((time.time() - t0) * 1000)

        tool_calls = [
            {
                "tool": c.tool.value,
                "reason": c.reason,
                "arguments": dict(c.arguments),
            }
            for c in plan.tool_calls
        ]

        result = {
            "case_id": case_id,
            "input": text,
            "intent": plan.intent,
            "tool_calls": tool_calls,
            "need_clarification": plan.need_clarification,
            "latency_ms": latency_ms,
            "success": True,
            "error": None,
        }
    except Exception as exc:
        latency_ms = int((time.time() - t0) * 1000)
        result = {
            "case_id": case_id,
            "input": text,
            "intent": "",
            "tool_calls": [],
            "need_clarification": False,
            "latency_ms": latency_ms,
            "success": False,
            "error": str(exc),
        }

    return result


def main():
    parser = argparse.ArgumentParser(description="Collect ground truth from fp16 planner")
    parser.add_argument(
        "--model-dir",
        default="/home/vivo/models/Qwen2.5-7B-Instruct",
        help="Path to the model directory",
    )
    parser.add_argument(
        "--cases",
        default=str(Path(__file__).parent / "benchmark_cases.json"),
        help="Path to benchmark cases JSON",
    )
    parser.add_argument(
        "--output",
        default=str(Path(__file__).parent / "ground_truth_fp16.jsonl"),
        help="Output JSONL file path",
    )
    parser.add_argument(
        "--library-root",
        default="/home/vivo/live_photo_agent/data/live_photo",
        help="Library root path",
    )
    parser.add_argument(
        "--device",
        default="cuda",
        help="Device to load model on",
    )
    parser.add_argument(
        "--dtype",
        default="float16",
        help="Model dtype (float16, float32)",
    )
    args = parser.parse_args()

    # Load cases
    with open(args.cases, encoding="utf-8") as f:
        cases_data = json.load(f)
    cases = cases_data["cases"]
    print(f"[ground_truth] Loaded {len(cases)} cases from {args.cases}")

    # Load model
    model, tokenizer = load_model(args.model_dir, args.device, args.dtype)

    # Create planner and inject pre-loaded model as local runtime
    from live_photo_agent.brain import LocalHFPlannerRuntime
    planner = QwenPlanner()
    planner._local_runtime = LocalHFPlannerRuntime(
        tokenizer=tokenizer,
        model=model,
    )
    # Force planner to use local_hf backend
    planner._backend = "local_hf"

    # Run all cases
    results = []
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with output_path.open("w", encoding="utf-8") as f:
        for i, case in enumerate(cases):
            print(f"[ground_truth] ({i+1}/{len(cases)}) {case['id']}: {case['input'][:40]}...", end=" ")
            result = run_case(planner, case, args.library_root)
            results.append(result)
            f.write(json.dumps(result, ensure_ascii=False) + "\n")
            f.flush()

            status = "OK" if result["success"] else "FAIL"
            print(f"[{status}] intent={result['intent']} latency={result['latency_ms']}ms")

            # Print plan summary
            if result["success"]:
                tools = [tc["tool"] for tc in result["tool_calls"]]
                print(f"  tools: {tools}")

    # Summary
    print(f"\n[ground_truth] Done. {len(results)} cases written to {output_path}")
    success_count = sum(1 for r in results if r["success"])
    avg_latency = sum(r["latency_ms"] for r in results) // len(results) if results else 0
    print(f"  Success: {success_count}/{len(results)}")
    print(f"  Avg latency: {avg_latency}ms")

    # VRAM at end
    if torch.cuda.is_available():
        vram_mb = torch.cuda.memory_allocated() // (1024 * 1024)
        print(f"  VRAM after all cases: {vram_mb}MB")

    # Cleanup
    del model, tokenizer
    gc.collect()
    torch.cuda.empty_cache()


if __name__ == "__main__":
    main()
