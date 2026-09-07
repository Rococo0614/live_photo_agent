"""Generate comparison report HTML with all 3 backends."""
import json, os
from pathlib import Path

TEST_DIR = Path("/home/vivo/live_photo_agent/model_test_files/jpg_frames")
VIZ_DIR = Path("/home/vivo/live_photo_agent/comparison_report/visualizations")
JSON_DIR = Path("/home/vivo/live_photo_agent/comparison_report/jsons")
OUT = Path("/home/vivo/live_photo_agent/comparison_report/index.html")

BACKENDS = [
    ("cv", "CV (纯视觉)", "#ffd700"),
    ("vlm", "Qwen2.5-VL (7B)", "#4ecdc4"),
    ("dots_mocr", "dots.mocr (3B)", "#c084fc"),
]

def slot_count(fname, backend):
    jp = JSON_DIR / f"{Path(fname).stem}_{backend}.json"
    if jp.exists():
        return len(json.loads(jp.read_text(encoding="utf-8")))
    return "N/A"

def viz_path(fname, backend):
    vp = VIZ_DIR / f"{Path(fname).stem}_{backend}.jpg"
    return vp.name if vp.exists() else None

orig_path = OUT.parent / "originals"
files = sorted(os.listdir(TEST_DIR))

rows = []
for fname in files:
    stem = Path(fname).stem
    orig = f"originals/{fname}"
    cells = []
    for bk, _, _ in BACKENDS:
        vp = viz_path(fname, bk)
        cnt = slot_count(fname, bk)
        if vp:
            cells.append(f'<img src="visualizations/{vp}" width="280"><br><span style="color:#aaa">{cnt} slots</span>')
        else:
            cells.append(f'<span style="color:#666">N/A</span>')
    rows.append(f'<tr><td><strong>{fname}</strong><br><img src="{orig}" width="280"></td>{"".join(f"<td>{c}</td>" for c in cells)}</tr>')

html = f"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<title>Template Parser Comparison Report</title>
<style>
body {{ font-family: -apple-system, sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }}
h1 {{ color: #c084fc; }}
table {{ border-collapse: collapse; width: 100%; }}
th {{ background: #16213e; padding: 12px 8px; position: sticky; top: 0; z-index: 10; }}
td {{ padding: 8px; border-bottom: 1px solid #333; vertical-align: top; }}
tr:hover {{ background: rgba(255,255,255,0.03); }}
img {{ display: block; margin: 4px auto; }}
.summary {{ margin: 20px 0; padding: 16px; background: #16213e; border-radius: 8px; }}
</style>
</head>
<body>
<h1>Template Parser Comparison Report</h1>
<div class="summary">
  <p>Test images: {len(files)} | Backends: CV (pure OpenCV), Qwen2.5-VL (7B via vLLM), dots.mocr (3B via vLLM)</p>
  <p>Grid: 120x160 | Each cell shows grid overlay with slot count</p>
</div>
<table>
<thead>
<tr>
  <th>Original</th>
  {"".join(f'<th style="color:{c}">{name}</th>' for _, name, c in BACKENDS)}
</tr>
</thead>
<tbody>
{"".join(rows)}
</tbody>
</table>
</body>
</html>"""

OUT.write_text(html, encoding="utf-8")
print(f"Report saved to {OUT} ({len(files)} rows)")
