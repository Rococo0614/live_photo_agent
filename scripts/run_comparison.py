"""Run cv + vlm backends on all 13 test images, save JSONs + visualizations, generate HTML report."""
import base64, json, io, os, sys, time, urllib.request
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

TEST_DIR = Path("/home/vivo/live_photo_agent/model_test_files/jpg_frames")
OUT_DIR = Path("/home/vivo/live_photo_agent/comparison_report")
JSON_DIR = OUT_DIR / "jsons"
VIZ_DIR = OUT_DIR / "visualizations"
ORIG_DIR = OUT_DIR / "originals"
API_URL = "http://127.0.0.1:8000/api/template/parse"

BACKENDS = ["cv", "vlm"]
GRID_COLS = 120
GRID_ROWS = 160

SLOT_COLORS = [
    (255, 99, 132, 128),
    (54, 162, 235, 128),
    (255, 206, 86, 128),
    (75, 192, 192, 128),
    (153, 102, 255, 128),
    (255, 159, 64, 128),
    (199, 199, 199, 128),
    (83, 102, 255, 128),
    (255, 99, 255, 128),
]

def call_api(image_b64, image_name, backend):
    data = json.dumps({
        "image_base64": image_b64,
        "image_name": image_name,
        "grid_cols": GRID_COLS,
        "grid_rows": GRID_ROWS,
        "backend": backend
    }).encode()
    req = urllib.request.Request(API_URL, data=data, headers={"Content-Type": "application/json"})
    resp = urllib.request.urlopen(req, timeout=180)
    return json.loads(resp.read())

def draw_grid_overlay(image_path, slots, output_path):
    img = Image.open(image_path).convert("RGBA")
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 16)
    except:
        font = ImageFont.load_default()

    for i, slot in enumerate(slots):
        color = SLOT_COLORS[i % len(SLOT_COLORS)]
        gx, gy, gw, gh = slot["gx"], slot["gy"], slot["gw"], slot["gh"]

        x1 = int(gx / GRID_COLS * img.width)
        y1 = int(gy / GRID_ROWS * img.height)
        x2 = int((gx + gw) / GRID_COLS * img.width)
        y2 = int((gy + gh) / GRID_ROWS * img.height)

        draw.rectangle([x1, y1, x2, y2], fill=color, outline=(255, 255, 255, 200), width=2)

        label = f"#{i+1}"
        bbox = draw.textbbox((0, 0), label, font=font)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        lx = x1 + 4
        ly = y1 + 4
        draw.rectangle([lx, ly, lx + tw + 4, ly + th + 4], fill=(0, 0, 0, 180))
        draw.text((lx + 2, ly + 2), label, fill=(255, 255, 255, 255), font=font)

    img = Image.alpha_composite(img, overlay)
    img.convert("RGB").save(output_path, "JPEG", quality=85)

def main():
    images = sorted(TEST_DIR.glob("*.jpg"))
    print(f"Found {len(images)} test images")

    results = {}

    for img_path in images:
        name = img_path.stem
        print(f"\n=== {name} ===")
        img = Image.open(img_path)
        print(f"  Size: {img.size}")

        orig_path = ORIG_DIR / f"{name}.jpg"
        img.convert("RGB").save(orig_path, "JPEG", quality=85)

        with open(img_path, "rb") as f:
            raw = f.read()
            b64 = base64.b64encode(raw).decode()

        results[name] = {}

        for backend in BACKENDS:
            print(f"  [{backend}] parsing...")
            try:
                result = call_api(b64, name + ".jpg", backend)
                slots = result["template"]["slots"]
                print(f"    -> {len(slots)} slots")

                json_path = JSON_DIR / f"{name}_{backend}.json"
                json_path.write_text(json.dumps(result["template"], indent=2, ensure_ascii=False))

                viz_path = VIZ_DIR / f"{name}_{backend}.jpg"
                draw_grid_overlay(img_path, slots, viz_path)

                results[name][backend] = {
                    "slots": len(slots),
                    "ok": True,
                }
            except Exception as e:
                print(f"    -> ERROR: {e}")
                results[name][backend] = {"slots": 0, "ok": False, "error": str(e)}

    html = build_html_report(results)
    (OUT_DIR / "report.html").write_text(html, encoding="utf-8")
    print(f"\nReport saved to {OUT_DIR}/report.html")

def build_html_report(results):
    rows = []
    for img_name in sorted(results.keys()):
        row = f'<tr><td style="font-weight:bold">{img_name}</td>'
        for backend in BACKENDS:
            info = results[img_name][backend]
            if info["ok"]:
                viz_file = f"{img_name}_{backend}.jpg"
                url = f"visualizations/{viz_file}"
                row += f'<td style="text-align:center"><b>{info["slots"]} slots</b><br><a href="{url}" target="_blank"><img src="{url}" style="width:200px;border:1px solid #ccc" loading="lazy"></a></td>'
            else:
                row += f'<td style="text-align:center;color:red">ERROR<br><small>{info.get("error","?")[:80]}</small></td>'
        row += f'<td><a href="originals/{img_name}.jpg" target="_blank"><img src="originals/{img_name}.jpg" style="width:200px;border:1px solid #ccc" loading="lazy"></a></td>'
        row += '</tr>'
        rows.append(row)

    return f"""<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<title>Template Parser Comparison Report</title>
<style>
body {{ font-family: -apple-system, sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }}
table {{ border-collapse: collapse; width: 100%; }}
th {{ background: #16213e; padding: 12px 8px; position: sticky; top: 0; z-index: 10; }}
td {{ padding: 8px; border-bottom: 1px solid #333; vertical-align: top; }}
tr:hover {{ background: rgba(255,255,255,0.03); }}
img {{ display: block; margin: 4px auto; }}
h1 {{ color: #c084fc; }}
.summary {{ margin: 20px 0; padding: 16px; background: #16213e; border-radius: 8px; }}
.backend-header {{ color: #ffd700; }}
</style>
</head>
<body>
<h1>Template Parser Comparison Report</h1>
<div class="summary">
  <p>Test images: {len(results)} | Backends: {', '.join(BACKENDS)} | Grid: {GRID_COLS}×{GRID_ROWS}</p>
  <p>dots.mocr excluded — HF inference path broken (no flash_attn, prepare_inputs_for_generation bug)</p>
</div>
<table>
<thead>
<tr>
  <th>Image</th>
  <th class="backend-header">CV (纯视觉)</th>
  <th class="backend-header">Qwen2.5-VL (7B)</th>
  <th>Original</th>
</tr>
</thead>
<tbody>
{''.join(rows)}
</tbody>
</table>
</body>
</html>"""

if __name__ == "__main__":
    main()
