"""Run dots.mocr via vLLM on all 13 test images, save JSONs + visualizations."""
import base64, json, io, os, sys, time, urllib.request
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

TEST_DIR = Path("/home/vivo/live_photo_agent/model_test_files/jpg_frames")
OUT_DIR = Path("/home/vivo/live_photo_agent/comparison_report")
VIZ_DIR = OUT_DIR / "visualizations"
JSON_DIR = OUT_DIR / "jsons"
API_URL = "http://localhost:8101/v1/chat/completions"
GRID_COLS = 120
GRID_ROWS = 160

PROMPT = """Please output the layout information from the PDF image, including each layout element's bbox, its category, and the corresponding text content within the bbox.

1. Bbox format: [x1, y1, x2, y2]
2. Layout Categories: ['Caption', 'Footnote', 'Formula', 'List-item', 'Page-footer', 'Page-header', 'Picture', 'Section-header', 'Table', 'Text', 'Title']
3. Text Extraction & Formatting Rules:
    - Picture: text field omitted
    - Formula: LaTeX
    - Table: HTML
    - All Others: Markdown
4. Constraints: original text, no translation. Sorted by human reading order.
5. Final Output: a single JSON object."""

def bbox_to_grid_slots(bboxes, img_w, img_h):
    slots = []
    for i, item in enumerate(bboxes):
        x1, y1, x2, y2 = item["bbox"]
        gx = max(0, round(x1 / img_w * GRID_COLS))
        gy = max(0, round(y1 / img_h * GRID_ROWS))
        gw = max(1, round((x2 - x1) / img_w * GRID_COLS))
        gh = max(1, round((y2 - y1) / img_h * GRID_ROWS))
        gx = min(gx, GRID_COLS - 1)
        gy = min(gy, GRID_ROWS - 1)
        gw = min(gw, GRID_COLS - gx)
        gh = min(gh, GRID_ROWS - gy)
        category = item.get("category", "Picture")
        is_fg = category.lower() in ("caption", "footnote", "formula", "list-item", "section-header", "text", "title")
        slots.append({
            "gx": gx, "gy": gy, "gw": gw, "gh": gh,
            "role": "foreground" if is_fg else "background",
            "pin_to_top": is_fg,
            "image_prompt": str(item.get("text", "")),
            "z_order": i,
        })
    return slots

def draw_grid_overlay(img, slots, output_path):
    draw = ImageDraw.Draw(img, "RGBA")
    colors = ["#c084fc", "#ffd700", "#00ff88", "#ff6b6b", "#4ecdc4", "#ff8c42", "#a29bfe", "#fd79a8"]
    for i, slot in enumerate(slots):
        color = colors[i % len(colors)]
        gx, gy, gw, gh = slot["gx"], slot["gy"], slot["gw"], slot["gh"]
        left = gx / GRID_COLS * img.width
        top = gy / GRID_ROWS * img.height
        right = (gx + gw) / GRID_COLS * img.width
        bottom = (gy + gh) / GRID_ROWS * img.height
        draw.rectangle([left, top, right, bottom], outline=color, width=4)
        draw.text((left + 4, top + 4), f"#{i+1}", fill=color)
    img.save(output_path, quality=85)

files = sorted(os.listdir(TEST_DIR))
print(f"Found {len(files)} test images\n")

for fname in files:
    path = TEST_DIR / fname
    with open(path, 'rb') as f:
        raw = f.read()
        b64 = base64.b64encode(raw).decode()

    img = Image.open(path).convert("RGB")
    img_w, img_h = img.size

    payload = {
        'model': '/home/vivo/models/dots-mocr',
        'temperature': 0,
        'max_tokens': 8192,
        'messages': [{'role': 'user', 'content': [
            {'type': 'image_url', 'image_url': {'url': f'data:image/jpeg;base64,{b64}'}},
            {'type': 'text', 'text': PROMPT}
        ]}]
    }

    print(f"=== {fname} ({img_w}x{img_h}) ===")
    try:
        data = json.dumps(payload).encode()
        req = urllib.request.Request(API_URL, data=data, headers={'Content-Type': 'application/json'})
        resp = urllib.request.urlopen(req, timeout=180)
        result = json.loads(resp.read())
        content = result['choices'][0]['message']['content']
        print(f"  Raw: {content[:300]}")

        parsed = json.loads(content)
        if isinstance(parsed, dict) and "layout" in parsed:
            bboxes = parsed["layout"]
        elif isinstance(parsed, list):
            bboxes = parsed
        else:
            bboxes = [parsed] if isinstance(parsed, dict) else []

        slots = bbox_to_grid_slots(bboxes, img_w, img_h)
        print(f"  -> {len(slots)} slots")

        json_path = JSON_DIR / f"{Path(fname).stem}_dots_mocr.json"
        json_path.write_text(json.dumps(slots, indent=2, ensure_ascii=False))

        viz_img = img.copy()
        draw_grid_overlay(viz_img, slots, VIZ_DIR / f"{Path(fname).stem}_dots_mocr.jpg")
        print(f"  Saved visualization")
    except Exception as e:
        print(f"  ERROR: {e}")

print("\nDone!")
