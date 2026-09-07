"""Generate final 4-column comparison report."""
import json, os
from pathlib import Path

VIZ_DIR = Path("/home/vivo/live_photo_agent/comparison_report/visualizations")
JSON_DIR = Path("/home/vivo/live_photo_agent/comparison_report/jsons")
OUT = Path("/home/vivo/live_photo_agent/comparison_report/index.html")

BACKENDS = [
    ("cv", "CV (OpenCV)", "#ffd700"),
    ("vlm", "Qwen2.5-VL (7B)", "#4ecdc4"),
    ("dino", "DINO Tiny", "#ff8040"),
    ("dino_base", "DINO Base", "#ff3c3c"),
]

def slot_count(fname, backend):
    jp = JSON_DIR / f"{fname}_{backend}.json"
    if jp.exists():
        try:
            return len(json.loads(jp.read_text(encoding="utf-8")))
        except:
            return "?"
    return "N/A"

def viz_path(fname, backend):
    vp = VIZ_DIR / f"{fname}_{backend}.jpg"
    return vp.name if vp.exists() else None

def build_table(files, section_title):
    rows = []
    for fname in files:
        stem = Path(fname).stem
        cells = []
        for bk, _, _ in BACKENDS:
            vp = viz_path(stem, bk)
            cnt = slot_count(stem, bk)
            if vp:
                cells.append(f'<td><img src="visualizations/{vp}" width="220"><br><span style="color:#aaa">{cnt} slots</span></td>')
            else:
                cells.append(f'<td><span style="color:#666">N/A</span></td>')
        rows.append(f'<tr><td><strong>{fname}</strong><br><img src="originals/{stem}.jpg" width="220"></td>{"".join(cells)}</tr>')
    return f'<h2>{section_title}</h2><table><thead><tr><th>Original</th>{"".join(f"<th style=\"color:{c}\">{n}</th>" for _, n, c in BACKENDS)}</tr></thead><tbody>{"".join(rows)}</tbody></table>'

from pathlib import Path
TEST_DIR = Path("/home/vivo/live_photo_agent/model_test_files/jpg_frames")
TEMPLATE_DIR = Path("/home/vivo/live_photo_agent/data/template_examples")
jpg_files = sorted([f for f in os.listdir(TEST_DIR) if f.endswith(('.jpg','.png','.PNG','.jpeg'))])
tpl_files = sorted([f for f in os.listdir(TEMPLATE_DIR) if f.endswith(('.jpg','.png','.PNG','.jpeg'))])

html = f"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<title>Template Parser Comparison Report</title>
<style>
body {{ font-family: -apple-system, sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }}
h1 {{ color: #c084fc; }}
h2 {{ color: #4ecdc4; margin-top: 30px; }}
table {{ border-collapse: collapse; width: 100%; margin-bottom: 30px; }}
th {{ background: #16213e; padding: 10px 6px; position: sticky; top: 0; z-index: 10; font-size: 13px; }}
td {{ padding: 6px; border-bottom: 1px solid #333; vertical-align: top; font-size: 12px; }}
tr:hover {{ background: rgba(255,255,255,0.03); }}
img {{ display: block; margin: 4px auto; }}
.summary {{ margin: 20px 0; padding: 16px; background: #16213e; border-radius: 8px; font-size: 14px; }}
</style>
</head>
<body>
<h1>Template Parser Comparison Report</h1>
<div class="summary">
  <p>Test images: {len(jpg_files)} jpg_frames + {len(tpl_files)} template_examples | Backends: CV, Qwen2.5-VL, Grounding DINO Tiny, Grounding DINO Base</p>
  <p>Grid: 120x160 | Each cell shows grid overlay with slot count</p>
</div>
{build_table(jpg_files, 'Dataset 1: jpg_frames (video frames)')}
{build_table(tpl_files, 'Dataset 2: template_examples (design templates)')}
</body>
</html>"""

OUT.write_text(html, encoding="utf-8")
print(f"Report saved to {OUT} ({len(jpg_files)}+{len(tpl_files)} rows x 4 backends)")
