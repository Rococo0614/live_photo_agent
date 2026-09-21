"""Benchmark comparison script: fp16 vs int4 (NF4) planner.

Runs all 30 benchmark cases against both models and compares:
  1. JSON parse success rate
  2. Intent match rate (vs ground truth)
  3. Tool sequence match rate
  4. Latency (p50, p99)
  5. VRAM usage

Output: JSONL results + summary table + comparison chart

Usage:
    python tests/benchmark/run_benchmark.py --model-dir PATH --label int4_nf4
    python tests/benchmark/run_benchmark.py --compare ground_truth_fp16.jsonl int4_nf4.jsonl
"""
from __future__ import annotations

import argparse
import gc
import json
import statistics
import sys
import time
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from live_photo_agent.brain import QwenPlanner, LocalHFPlannerRuntime
from live_photo_agent.models import AgentRequest


def load_fp16_model(model_dir: str):
    """Load model in fp16."""
    tokenizer = AutoTokenizer.from_pretrained(model_dir, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        model_dir,
        dtype=torch.float16,
        device_map="auto",
        trust_remote_code=True,
    )
    return model, tokenizer


def load_nf4_model(model_dir: str):
    """Load model in NF4 int4 quantization."""
    quant_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_compute_dtype=torch.float16,
        bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4",
    )
    tokenizer = AutoTokenizer.from_pretrained(model_dir, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        model_dir,
        quantization_config=quant_config,
        device_map="auto",
        trust_remote_code=True,
    )
    return model, tokenizer


def run_case(planner: QwenPlanner, case: dict, library_root: str) -> dict:
    """Run a single benchmark case."""
    request = AgentRequest(
        user_id="benchmark",
        text=case["input"],
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
            {"tool": c.tool.value, "arguments": dict(c.arguments)}
            for c in plan.tool_calls
        ]

        return {
            "case_id": case["id"],
            "input": case["input"],
            "intent": plan.intent,
            "tool_calls": tool_calls,
            "latency_ms": latency_ms,
            "success": True,
            "error": None,
        }
    except Exception as exc:
        latency_ms = int((time.time() - t0) * 1000)
        return {
            "case_id": case["id"],
            "input": case["input"],
            "intent": "",
            "tool_calls": [],
            "latency_ms": latency_ms,
            "success": False,
            "error": str(exc)[:200],
        }


def run_all_cases(model, tokenizer, cases, library_root, label):
    """Run all cases with a loaded model, return results."""
    planner = QwenPlanner()
    planner._local_runtime = LocalHFPlannerRuntime(tokenizer=tokenizer, model=model)
    planner._backend = "local_hf"

    results = []
    for i, case in enumerate(cases):
        print(f"  [{label}] ({i+1}/{len(cases)}) {case['id']}: {case['input'][:30]}...", end=" ")
        result = run_case(planner, case, library_root)
        results.append(result)
        status = "OK" if result["success"] else "FAIL"
        print(f"[{status}] {result['intent']} {result['latency_ms']}ms")

    return results


def compare_results(fp16_results, quantized_results):
    """Compare two result sets and produce summary."""
    summary = {
        "fp16": {
            "success_rate": sum(1 for r in fp16_results if r["success"]) / len(fp16_results),
            "avg_latency_ms": statistics.mean(r["latency_ms"] for r in fp16_results if r["success"]),
            "p50_latency_ms": statistics.median(r["latency_ms"] for r in fp16_results if r["success"]),
            "p99_latency_ms": max(r["latency_ms"] for r in fp16_results if r["success"]) if any(r["success"] for r in fp16_results) else 0,
        },
        "quantized": {
            "success_rate": sum(1 for r in quantized_results if r["success"]) / len(quantized_results),
            "avg_latency_ms": statistics.mean(r["latency_ms"] for r in quantized_results if r["success"]) if any(r["success"] for r in quantized_results) else 0,
            "p50_latency_ms": statistics.median(r["latency_ms"] for r in quantized_results if r["success"]) if any(r["success"] for r in quantized_results) else 0,
            "p99_latency_ms": max(r["latency_ms"] for r in quantized_results if r["success"]) if any(r["success"] for r in quantized_results) else 0,
        },
    }

    # Intent match: how many cases have the same intent as fp16 ground truth
    intent_matches = 0
    tool_matches = 0
    for fp16, quant in zip(fp16_results, quantized_results):
        if not fp16["success"]:
            continue
        if quant["intent"] == fp16["intent"]:
            intent_matches += 1
        fp16_tools = [tc["tool"] for tc in fp16["tool_calls"]]
        quant_tools = [tc["tool"] for tc in quant["tool_calls"]]
        if fp16_tools == quant_tools:
            tool_matches += 1

    total = sum(1 for r in fp16_results if r["success"])
    summary["intent_match_rate"] = intent_matches / total if total else 0
    summary["tool_match_rate"] = tool_matches / total if total else 0

    # Per-case comparison
    summary["cases"] = []
    for fp16, quant in zip(fp16_results, quantized_results):
        summary["cases"].append({
            "case_id": fp16["case_id"],
            "input": fp16["input"],
            "fp16_intent": fp16["intent"],
            "quant_intent": quant["intent"],
            "intent_match": fp16["intent"] == quant["intent"],
            "fp16_tools": [tc["tool"] for tc in fp16["tool_calls"]],
            "quant_tools": [tc["tool"] for tc in quant["tool_calls"]],
            "tool_match": [tc["tool"] for tc in fp16["tool_calls"]] == [tc["tool"] for tc in quant["tool_calls"]],
            "fp16_latency_ms": fp16["latency_ms"],
            "quant_latency_ms": quant["latency_ms"],
            "fp16_success": fp16["success"],
            "quant_success": quant["success"],
        })

    return summary


def print_summary_table(summary):
    """Print a formatted comparison table."""
    print("\n" + "=" * 80)
    print(f"{'Metric':<30} {'fp16':>15} {'int4_nf4':>15} {'Delta':>15}")
    print("=" * 80)

    fp = summary["fp16"]
    qp = summary["quantized"]

    def fmt_pct(v):
        return f"{v*100:.1f}%"

    def fmt_ms(v):
        return f"{v:.0f}ms"

    def delta(a, b):
        if a == 0:
            return "N/A"
        d = (b - a) / a * 100
        return f"{d:+.1f}%"

    print(f"{'Success rate':<30} {fmt_pct(fp['success_rate']):>15} {fmt_pct(qp['success_rate']):>15} {'':>15}")
    print(f"{'Avg latency':<30} {fmt_ms(fp['avg_latency_ms']):>15} {fmt_ms(qp['avg_latency_ms']):>15} {delta(fp['avg_latency_ms'], qp['avg_latency_ms']):>15}")
    print(f"{'P50 latency':<30} {fmt_ms(fp['p50_latency_ms']):>15} {fmt_ms(qp['p50_latency_ms']):>15} {delta(fp['p50_latency_ms'], qp['p50_latency_ms']):>15}")
    print(f"{'P99 latency':<30} {fmt_ms(fp['p99_latency_ms']):>15} {fmt_ms(qp['p99_latency_ms']):>15} {delta(fp['p99_latency_ms'], qp['p99_latency_ms']):>15}")
    print(f"{'Intent match rate':<30} {'':>15} {fmt_pct(summary['intent_match_rate']):>15} {'':>15}")
    print(f"{'Tool match rate':<30} {'':>15} {fmt_pct(summary['tool_match_rate']):>15} {'':>15}")
    print("=" * 80)

    # Per-case details
    print(f"\n{'Case':<6} {'Input':<25} {'Intent Match':<12} {'Tool Match':<12} {'fp16 ms':>8} {'int4 ms':>8}")
    print("-" * 80)
    for c in summary["cases"]:
        intent_mark = "✓" if c["intent_match"] else "✗"
        tool_mark = "✓" if c["tool_match"] else "✗"
        print(f"{c['case_id']:<6} {c['input'][:25]:<25} {intent_mark:<12} {tool_mark:<12} {c['fp16_latency_ms']:>8} {c['quant_latency_ms']:>8}")


def main():
    parser = argparse.ArgumentParser(description="Benchmark comparison: fp16 vs int4")
    parser.add_argument("--model-dir", default="/home/vivo/models/Qwen2.5-7B-Instruct")
    parser.add_argument("--cases", default=str(Path(__file__).parent / "benchmark_cases.json"))
    parser.add_argument("--ground-truth", default=str(Path(__file__).parent / "ground_truth_fp16.jsonl"))
    parser.add_argument("--output", default=str(Path(__file__).parent / "benchmark_results.json"))
    parser.add_argument("--library-root", default="/home/vivo/live_photo_agent/data/live_photo")
    args = parser.parse_args()

    # Load cases
    with open(args.cases, encoding="utf-8") as f:
        cases = json.load(f)["cases"]
    print(f"[benchmark] Loaded {len(cases)} cases")

    # Load ground truth (fp16 results)
    with open(args.ground_truth, encoding="utf-8") as f:
        fp16_results = [json.loads(line) for line in f]
    print(f"[benchmark] Loaded {len(fp16_results)} ground truth results")

    # Load int4 model and run
    print(f"\n[benchmark] Loading NF4 int4 model...")
    model, tokenizer = load_nf4_model(args.model_dir)
    vram_after_load = torch.cuda.memory_allocated() // (1024 * 1024)
    print(f"[benchmark] VRAM after load: {vram_after_load}MB")

    print(f"\n[benchmark] Running {len(cases)} cases on int4 model...")
    int4_results = run_all_cases(model, tokenizer, cases, args.library_root, "int4")

    # Cleanup
    del model, tokenizer
    gc.collect()
    torch.cuda.empty_cache()

    # Compare
    summary = compare_results(fp16_results, int4_results)
    summary["vram"] = {
        "fp16_mb": 14558,  # measured earlier
        "int4_mb": vram_after_load,
    }

    # Save results
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    # Print table
    print_summary_table(summary)
    print(f"\n[benchmark] Results saved to {output_path}")


if __name__ == "__main__":
    main()
