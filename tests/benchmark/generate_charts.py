"""Generate visualization charts for fp16 vs int4 benchmark results.

Produces 4 charts:
  1. VRAM comparison bar chart
  2. Latency comparison (per-case scatter + p50/p99 lines)
  3. Intent match rate (per-case heatmap)
  4. Summary radar chart (success/latency/vram/intent_match/tool_match)

Output: tests/benchmark/benchmark_charts.png
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")  # no display
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

# Chinese font support
plt.rcParams["font.sans-serif"] = ["Noto Serif CJK SC", "AR PL UMing CN", "WenQuanYi Micro Hei", "SimHei", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False


def load_results(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def chart_vram(ax, data):
    """Chart 1: VRAM comparison."""
    fp16_vram = data["vram"]["fp16_mb"] / 1024  # GB
    int4_vram = data["vram"]["int4_mb"] / 1024
    rtx_5090 = 24.0  # GB total

    models = ["fp16", "int4 (NF4)", "RTX 5090 total"]
    values = [fp16_vram, int4_vram, rtx_5090]
    colors = ["#e74c3c", "#2ecc71", "#95a5a6"]

    bars = ax.bar(models, values, color=colors, width=0.5, edgecolor="black", linewidth=0.5)
    for bar, val in zip(bars, values):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3,
                f"{val:.1f}GB", ha="center", va="bottom", fontweight="bold", fontsize=11)

    # Show savings
    savings = (1 - int4_vram / fp16_vram) * 100
    ax.annotate(f"-{savings:.0f}%", xy=(1, int4_vram), xytext=(1.5, (fp16_vram + int4_vram) / 2),
                fontsize=14, fontweight="bold", color="#2ecc71",
                arrowprops=dict(arrowstyle="->", color="#2ecc71", lw=2))

    ax.set_ylabel("VRAM (GB)", fontsize=12)
    ax.set_title("GPU VRAM Usage", fontsize=14, fontweight="bold")
    ax.set_ylim(0, rtx_5090 + 3)
    ax.axhline(y=rtx_5090, color="#95a5a6", linestyle="--", alpha=0.5, label="RTX 5090 limit")

    # Show "can coexist with VLM" annotation
    vlm_vram = 17.0
    ax.axhspan(fp16_vram + vlm_vram, rtx_5090, color="#e74c3c", alpha=0.15)
    ax.text(0, rtx_5090 - 1, f"fp16+VLM={fp16_vram+vlm_vram:.1f}GB > 24GB (OOM!)",
            ha="center", fontsize=8, color="#e74c3c")
    ax.text(1, rtx_5090 - 1, f"int4+VLM={int4_vram+vlm_vram:.1f}GB < 24GB (OK)",
            ha="center", fontsize=8, color="#2ecc71")


def chart_latency(ax, data):
    """Chart 2: Per-case latency scatter."""
    cases = data["cases"]
    x = np.arange(len(cases))
    fp16_lat = [c["fp16_latency_ms"] for c in cases]
    int4_lat = [c["quant_latency_ms"] for c in cases]

    width = 0.35
    ax.bar(x - width / 2, fp16_lat, width, label="fp16", color="#3498db", alpha=0.8)
    ax.bar(x + width / 2, int4_lat, width, label="int4 (NF4)", color="#e67e22", alpha=0.8)

    # p50 lines
    fp16_p50 = data["fp16"]["p50_latency_ms"]
    int4_p50 = data["quantized"]["p50_latency_ms"]
    ax.axhline(y=fp16_p50, color="#3498db", linestyle="--", alpha=0.5, linewidth=1)
    ax.axhline(y=int4_p50, color="#e67e22", linestyle="--", alpha=0.5, linewidth=1)
    ax.text(len(cases) - 0.5, fp16_p50 + 50, f"fp16 p50={fp16_p50:.0f}ms",
            ha="right", fontsize=8, color="#3498db")
    ax.text(len(cases) - 0.5, int4_p50 + 50, f"int4 p50={int4_p50:.0f}ms",
            ha="right", fontsize=8, color="#e67e22")

    ax.set_xlabel("Case ID", fontsize=11)
    ax.set_ylabel("Latency (ms)", fontsize=11)
    ax.set_title("Per-Case Latency", fontsize=14, fontweight="bold")
    ax.set_xticks(x)
    ax.set_xticklabels([c["case_id"] for c in cases], rotation=45, fontsize=7)
    ax.legend(fontsize=9)


def chart_intent_match(ax, data):
    """Chart 3: Intent match heatmap."""
    cases = data["cases"]
    matches = [1 if c["intent_match"] else 0 for c in cases]
    tool_matches = [1 if c["tool_match"] else 0 for c in cases]

    x = np.arange(len(cases))
    width = 0.35
    ax.bar(x - width / 2, matches, width, label="Intent match", color="#2ecc71", alpha=0.8)
    ax.bar(x + width / 2, tool_matches, width, label="Tool match", color="#9b59b6", alpha=0.8)

    # Mark mismatches
    for i, (im, tm) in enumerate(zip(matches, tool_matches)):
        if not im:
            ax.text(i - width / 2, 0.5, "✗", ha="center", va="center", fontsize=12, color="white", fontweight="bold")
        if not tm:
            ax.text(i + width / 2, 0.5, "✗", ha="center", va="center", fontsize=12, color="white", fontweight="bold")

    intent_rate = data["intent_match_rate"] * 100
    tool_rate = data["tool_match_rate"] * 100
    ax.text(len(cases) - 0.5, 1.1, f"Intent: {intent_rate:.0f}%  Tool: {tool_rate:.0f}%",
            ha="right", fontsize=9, fontweight="bold")

    ax.set_xlabel("Case ID", fontsize=11)
    ax.set_ylabel("Match (0/1)", fontsize=11)
    ax.set_title("Intent & Tool Match (int4 vs fp16 ground truth)", fontsize=14, fontweight="bold")
    ax.set_xticks(x)
    ax.set_xticklabels([c["case_id"] for c in cases], rotation=45, fontsize=7)
    ax.set_yticks([0, 1])
    ax.set_yticklabels(["No", "Yes"])
    ax.legend(fontsize=9, loc="upper right")


def chart_summary(ax, data):
    """Chart 4: Summary radar chart."""
    categories = ["Success\nRate", "Avg\nLatency\n(inverse)", "VRAM\nEfficiency\n(inverse)", "Intent\nMatch", "Tool\nMatch"]

    fp16_vals = [
        data["fp16"]["success_rate"],
        1.0,  # baseline
        1.0,  # baseline
        1.0,  # baseline
        1.0,  # baseline
    ]
    # For int4, normalize: latency and vram are "inverse" (lower is better)
    fp16_lat = data["fp16"]["avg_latency_ms"]
    int4_lat = data["quantized"]["avg_latency_ms"]
    fp16_vram = data["vram"]["fp16_mb"]
    int4_vram = data["vram"]["int4_mb"]

    int4_vals = [
        data["quantized"]["success_rate"],
        min(1.0, fp16_lat / int4_lat),  # latency ratio (1.0 = same, <1 = slower)
        min(1.0, fp16_vram / int4_vram),  # vram ratio (1.0 = same, <1 = uses more)
        data["intent_match_rate"],
        data["tool_match_rate"],
    ]

    angles = np.linspace(0, 2 * np.pi, len(categories), endpoint=False).tolist()
    angles += angles[:1]
    fp16_vals += fp16_vals[:1]
    int4_vals += int4_vals[:1]

    ax = fig.add_subplot(2, 2, 4, projection="polar")
    ax.fill(angles, fp16_vals, alpha=0.2, color="#3498db", label="fp16 (baseline)")
    ax.plot(angles, fp16_vals, color="#3498db", linewidth=2)
    ax.fill(angles, int4_vals, alpha=0.2, color="#e67e22", label="int4 (NF4)")
    ax.plot(angles, int4_vals, color="#e67e22", linewidth=2)

    ax.set_xticks(angles[:-1])
    ax.set_xticklabels(categories, fontsize=9)
    ax.set_ylim(0, 1.1)
    ax.set_title("Overall Comparison (1.0 = baseline)", fontsize=13, fontweight="bold", pad=20)
    ax.legend(fontsize=9, loc="upper right", bbox_to_anchor=(1.3, 1.1))


def chart_mismatch_details(ax, data):
    """Chart 5: Mismatch case details table."""
    cases = data["cases"]
    mismatches = [c for c in cases if not c["intent_match"] or not c["tool_match"]]

    if not mismatches:
        ax.text(0.5, 0.5, "All 30 cases match!", ha="center", va="center",
                fontsize=16, fontweight="bold", color="#2ecc71")
        ax.axis("off")
        return

    col_labels = ["Case", "Input", "fp16 Intent", "int4 Intent", "Match"]
    cell_text = []
    cell_colors = []
    for c in mismatches:
        row = [
            c["case_id"],
            c["input"][:15],
            c["fp16_intent"][:20],
            c["quant_intent"][:20],
            "✗" if not c["intent_match"] else "tool",
        ]
        cell_text.append(row)
        cell_colors.append(["#ffcccc" if not c["intent_match"] else "#fff3cd"] * 5)

    table = ax.table(
        cellText=cell_text,
        colLabels=col_labels,
        cellLoc="center",
        loc="center",
        colColours=["#34495e"] * 5,
    )
    table.auto_set_font_size(False)
    table.set_fontsize(8)
    table.scale(1.2, 1.5)

    # Style header
    for j in range(5):
        table[0, j].set_text_props(color="white", fontweight="bold")
        table[0, j].set_facecolor("#34495e")

    # Color mismatch rows
    for i in range(len(mismatches)):
        for j in range(5):
            if not mismatches[i]["intent_match"]:
                table[i + 1, j].set_facecolor("#ffcccc")
            else:
                table[i + 1, j].set_facecolor("#fff3cd")

    ax.set_title("Mismatch Cases Detail", fontsize=14, fontweight="bold")
    ax.axis("off")


if __name__ == "__main__":
    results_path = sys.argv[1] if len(sys.argv) > 1 else "tests/benchmark/benchmark_results.json"
    output_path = sys.argv[2] if len(sys.argv) > 2 else "tests/benchmark/benchmark_charts.png"

    data = load_results(results_path)
    print(f"[chart] Loaded {len(data['cases'])} cases from {results_path}")

    fig = plt.figure(figsize=(18, 12))
    fig.suptitle("Live Photo Agent Planner: fp16 vs NF4 int4 Benchmark",
                 fontsize=16, fontweight="bold", y=0.98)

    # 4 charts in 2x2 grid + 1 detail table at bottom
    ax1 = fig.add_subplot(2, 3, 1)
    chart_vram(ax1, data)

    ax2 = fig.add_subplot(2, 3, 2)
    chart_latency(ax2, data)

    ax3 = fig.add_subplot(2, 3, 3)
    chart_intent_match(ax3, data)

    # Radar chart
    ax4 = fig.add_subplot(2, 3, 4, projection="polar")
    categories = ["Success", "Latency\n(inv)", "VRAM\n(inv)", "Intent\nMatch", "Tool\nMatch"]
    fp16_lat = data["fp16"]["avg_latency_ms"]
    int4_lat = data["quantized"]["avg_latency_ms"]
    fp16_vram = data["vram"]["fp16_mb"]
    int4_vram = data["vram"]["int4_mb"]

    fp16_vals = [1.0, 1.0, 1.0, 1.0, 1.0]
    int4_vals = [
        data["quantized"]["success_rate"],
        min(1.0, fp16_lat / int4_lat),
        min(1.0, fp16_vram / int4_vram),
        data["intent_match_rate"],
        data["tool_match_rate"],
    ]

    angles = np.linspace(0, 2 * np.pi, len(categories), endpoint=False).tolist()
    angles += angles[:1]
    fp16_vals += fp16_vals[:1]
    int4_vals += int4_vals[:1]

    ax4.fill(angles, fp16_vals, alpha=0.15, color="#3498db")
    ax4.plot(angles, fp16_vals, color="#3498db", linewidth=2, label="fp16 (baseline)")
    ax4.fill(angles, int4_vals, alpha=0.15, color="#e67e22")
    ax4.plot(angles, int4_vals, color="#e67e22", linewidth=2, label="int4 (NF4)")
    ax4.set_xticks(angles[:-1])
    ax4.set_xticklabels(categories, fontsize=8)
    ax4.set_ylim(0, 1.1)
    ax4.set_title("Overall (1.0=baseline)", fontsize=12, fontweight="bold", pad=15)
    ax4.legend(fontsize=8, loc="upper right", bbox_to_anchor=(1.3, 1.1))

    # Mismatch detail table
    ax5 = fig.add_subplot(2, 3, 5)
    chart_mismatch_details(ax5, data)

    # Summary stats text
    ax6 = fig.add_subplot(2, 3, 6)
    ax6.axis("off")
    summary_text = (
        f"BENCHMARK SUMMARY\n"
        f"{'─' * 40}\n"
        f"Model: Qwen2.5-7B-Instruct\n"
        f"Quantization: bitsandbytes NF4 int4\n"
        f"Test cases: 30\n"
        f"{'─' * 40}\n\n"
        f"VRAM:\n"
        f"  fp16: {data['vram']['fp16_mb']}MB ({data['vram']['fp16_mb']/1024:.1f}GB)\n"
        f"  int4: {data['vram']['int4_mb']}MB ({data['vram']['int4_mb']/1024:.1f}GB)\n"
        f"  Savings: {(1-data['vram']['int4_mb']/data['vram']['fp16_mb'])*100:.0f}%\n\n"
        f"Success Rate:\n"
        f"  fp16: {data['fp16']['success_rate']*100:.0f}%\n"
        f"  int4: {data['quantized']['success_rate']*100:.0f}%\n\n"
        f"Latency (avg):\n"
        f"  fp16: {data['fp16']['avg_latency_ms']:.0f}ms\n"
        f"  int4: {data['quantized']['avg_latency_ms']:.0f}ms\n"
        f"  Overhead: +{(data['quantized']['avg_latency_ms']/data['fp16']['avg_latency_ms']-1)*100:.0f}%\n\n"
        f"Intent Match: {data['intent_match_rate']*100:.0f}%\n"
        f"Tool Match: {data['tool_match_rate']*100:.0f}%\n"
    )
    ax6.text(0.05, 0.95, summary_text, transform=ax6.transAxes,
             fontsize=10, verticalalignment="top", fontfamily="monospace",
             bbox=dict(boxstyle="round", facecolor="#ecf0f1", alpha=0.8))

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(output, dpi=150, bbox_inches="tight", facecolor="white")
    print(f"[chart] Saved to {output}")
    plt.close()
