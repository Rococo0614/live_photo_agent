#!/usr/bin/env python3
"""Generate synthetic few-shot examples from TEMPLATE_LIBRARY.

Each template is rendered as a colored-block layout image (1080×1440 xhs board),
then screenshot by headless Chrome. The output is paired image + ground-truth
JSON, suitable for few-shot prompting or LoRA fine-tuning.

Output: data/template_examples/
  ├── m16_image_led_cover.png / .json
  ├── m07_field_ledger.png / .json
  └── ...
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

# ── TEMPLATE_LIBRARY (mirrored from ui.html) ──────────────────────────────
TEMPLATE_LIBRARY = [
    {"id": "m16_image_led_cover", "name": "M16 全图封面", "style": "editorial", "board": "xhs",
     "slots": [{"gx": 0, "gy": 0, "gw": 120, "gh": 160}]},
    {"id": "m07_field_ledger", "name": "M07 上图下文", "style": "editorial", "board": "xhs",
     "slots": [{"gx": 6, "gy": 6, "gw": 108, "gh": 66}, {"gx": 6, "gy": 78, "gw": 108, "gh": 76}]},
    {"id": "m14_vertical_pipeline", "name": "M14 三段流程", "style": "editorial", "board": "xhs",
     "slots": [{"gx": 8, "gy": 8, "gw": 104, "gh": 44}, {"gx": 8, "gy": 58, "gw": 104, "gh": 44}, {"gx": 8, "gy": 108, "gw": 104, "gh": 44}]},
    {"id": "s09_kpi_tower", "name": "S09 KPI 三栏", "style": "swiss", "board": "xhs",
     "slots": [{"gx": 6, "gy": 12, "gw": 34, "gh": 136}, {"gx": 43, "gy": 12, "gw": 34, "gh": 136}, {"gx": 80, "gy": 12, "gw": 34, "gh": 136}]},
    {"id": "s10_hbar_chart", "name": "S10 条形四段", "style": "swiss", "board": "xhs",
     "slots": [{"gx": 8, "gy": 14, "gw": 104, "gh": 24}, {"gx": 8, "gy": 46, "gw": 104, "gh": 24}, {"gx": 8, "gy": 78, "gw": 104, "gh": 24}, {"gx": 8, "gy": 110, "gw": 104, "gh": 24}]},
    {"id": "live_single_focus", "name": "Live 单视频", "style": "live", "board": "xhs",
     "slots": [{"gx": 5, "gy": 10, "gw": 110, "gh": 138}]},
    {"id": "live_two_stack", "name": "Live 二宫格上下", "style": "live", "board": "xhs",
     "slots": [{"gx": 5, "gy": 10, "gw": 110, "gh": 64}, {"gx": 5, "gy": 86, "gw": 110, "gh": 64}]},
    {"id": "live_three_stack", "name": "Live 三宫格上下", "style": "live", "board": "xhs",
     "slots": [{"gx": 5, "gy": 8, "gw": 110, "gh": 46}, {"gx": 5, "gy": 58, "gw": 110, "gh": 46}, {"gx": 5, "gy": 108, "gw": 110, "gh": 46}]},
    {"id": "live_four_grid", "name": "Live 四宫格", "style": "live", "board": "xhs",
     "slots": [{"gx": 6, "gy": 10, "gw": 52, "gh": 66}, {"gx": 62, "gy": 10, "gw": 52, "gh": 66}, {"gx": 6, "gy": 84, "gw": 52, "gh": 66}, {"gx": 62, "gy": 84, "gw": 52, "gh": 66}]},
    {"id": "matrix_three_by_two", "name": "3x2 材料拼板", "style": "swiss", "board": "xhs",
     "slots": [{"gx": 6, "gy": 16, "gw": 34, "gh": 34}, {"gx": 43, "gy": 16, "gw": 34, "gh": 34}, {"gx": 80, "gy": 16, "gw": 34, "gh": 34}, {"gx": 6, "gy": 56, "gw": 34, "gh": 34}, {"gx": 43, "gy": 56, "gw": 34, "gh": 34}, {"gx": 80, "gy": 56, "gw": 34, "gh": 34}]},
    {"id": "live_overlay_top", "name": "Live 重叠置顶", "style": "live", "board": "xhs",
     "slots": [{"gx": 0, "gy": 0, "gw": 120, "gh": 160}, {"gx": 10, "gy": 90, "gw": 60, "gh": 50, "pin_to_top": True, "image_prompt": "抠出人物主体并叠加到背景视频上方"}]},
    {"id": "live_prompt_enhance", "name": "Live 带处理提示", "style": "live", "board": "xhs",
     "slots": [{"gx": 5, "gy": 10, "gw": 110, "gh": 64, "image_prompt": "增强色彩饱和度，暖色调风格"}, {"gx": 5, "gy": 86, "gw": 110, "gh": 64, "image_prompt": "增加动态模糊效果"}]},
]

COLORS = ["#4A90D9", "#E85D75", "#50C878", "#F5A623", "#9B59B6", "#1ABC9C",
          "#E74C3C", "#3498DB", "#2ECC71", "#F39C12", "#8E44AD", "#16A085"]

GRID_COLS = 120
GRID_ROWS = 160
CANVAS_W = 1080
CANVAS_H = 1440


def _build_html(template: dict) -> str:
    name = template["name"]
    slots = template["slots"]

    divs = []
    for i, slot in enumerate(slots):
        color = COLORS[i % len(COLORS)]
        left = round(slot["gx"] / GRID_COLS * 100, 2)
        top = round(slot["gy"] / GRID_ROWS * 100, 2)
        width = round(slot["gw"] / GRID_COLS * 100, 2)
        height = round(slot["gh"] / GRID_ROWS * 100, 2)
        z = i + 1
        pinned = slot.get("pin_to_top", False)
        if pinned:
            z = 100 + i
        divs.append(
            f'<div style="position:absolute;left:{left}%;top:{top}%;width:{width}%;'
            f'height:{height}%;background:{color};border:3px solid white;'
            f'border-radius:12px;z-index:{z};display:flex;align-items:center;'
            f'justify-content:center;font-size:28px;color:white;font-weight:700;'
            f'font-family:Inter,Noto Sans SC,sans-serif;text-shadow:0 2px 4px rgba(0,0,0,.3);">'
            f'区域 {i + 1}</div>'
        )

    return f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
* {{ margin:0; padding:0; box-sizing:border-box; }}
body {{ width:{CANVAS_W}px; height:{CANVAS_H}px; overflow:hidden; background:#f0f0f0; position:relative; }}
</style></head><body>
<div style="position:absolute;top:10px;left:10px;z-index:999;background:rgba(0,0,0,.6);color:white;
padding:4px 12px;border-radius:6px;font-size:14px;font-family:monospace;">{name}</div>
{''.join(divs)}
</body></html>"""


def main():
    output_dir = Path(__file__).resolve().parent.parent / "data" / "template_examples"
    output_dir.mkdir(parents=True, exist_ok=True)

    chrome = "google-chrome"
    for tpl in TEMPLATE_LIBRARY:
        tid = tpl["id"]
        print(f"[gen] {tid} ({tpl['name']})")

        # Write HTML
        html_path = output_dir / f"{tid}.html"
        html_path.write_text(_build_html(tpl), encoding="utf-8")

        # Screenshot
        png_path = output_dir / f"{tid}.png"
        subprocess.run([
            chrome, "--headless=new", "--disable-gpu", "--no-sandbox",
            f"--window-size={CANVAS_W},{CANVAS_H}",
            f"--screenshot={png_path}",
            f"file://{html_path}",
        ], check=True, capture_output=True)

        # Write ground-truth JSON (slots only, in the format VLM should output)
        ground_truth = {
            "id": tid,
            "name": tpl["name"],
            "style": tpl["style"],
            "board": tpl["board"],
            "slots": tpl["slots"],
        }
        json_path = output_dir / f"{tid}.json"
        json_path.write_text(json.dumps(ground_truth, ensure_ascii=False, indent=2), encoding="utf-8")

        # Clean up HTML
        html_path.unlink()

    print(f"\nDone. {len(TEMPLATE_LIBRARY)} examples saved to {output_dir}/")


if __name__ == "__main__":
    main()