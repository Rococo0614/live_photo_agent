"""Batch run DINO + CV on all images (template_examples + jpg_frames), save JSONs + viz."""
import base64, json, io, os, sys, time, urllib.request
from pathlib import Path
from PIL import Image, ImageDraw

TEST_DIR = Path("/home/vivo/live_photo_agent/model_test_files/jpg_frames")
TEMPLATE_DIR = Path("/home/vivo/live_photo_agent/data/template_examples")
OUT_DIR = Path("/home/vivo/live_photo_agent/comparison_report")
JSON_DIR = OUT_DIR / "jsons"
VIZ_DIR = OUT_DIR / "visualizations"
ORIG_DIR = OUT_DIR / "originals"
API_URL = "http://127.0.0.1:8000/api/template/parse"
GRID_COLS = 120
GRID_ROWS = 160

BACKENDS = ["cv", "dino"]
COLORS = {"cv": (255, 215, 0), "dino": (255, 128, 0)}
FONT = None

def draw_grid(img, slots, backend):
    draw = ImageDraw.Draw(img, "RGBA")
    w, h = img.size
    color = COLORS.get(backend, (255, 255, 0))
    for s in slots:
        x1 = s["gx"] / GRID_COLS * w
        y1 = s["gy"] / GRID_ROWS * h
        x2 = (s["gx"] + s["gw"]) / GRID_COLS * w
        y2 = (s["gy"] + s["gh"]) / GRID_ROWS * h
        draw.rectangle([x1, y1, x2, y2], outline=color + (200,), width=3)
        draw.rectangle([x1, y1, x2, y2], fill=color + (40,))
    return img

def parse_image(image_bytes, fname, backend):
    b64 = base64.b64encode(image_bytes).decode()
    data = json.dumps({"image_base64": b64, "image_name": fname, "grid_cols": GRID_COLS, "grid_rows": GRID_ROWS, "backend": backend}).encode()
    req = urllib.request.Request(API_URL, data=data, headers={"Content-Type": "application/json"})
    resp = urllib.request.urlopen(req, timeout=120)
    return json.loads(resp.read())["template"]["slots"]

def process_dataset(dataset_dir, dataset_name):
    files = sorted([f for f in os.listdir(dataset_dir) if f.endswith(('.jpg','.png','.PNG','.jpeg'))])
    print(f"\n{'='*60}\n{dataset_name}: {len(files)} images\n{'='*60}")
    for fname in files:
        path = dataset_dir / fname
        stem = Path(fname).stem
        raw = path.read_bytes()
        img = Image.open(io.BytesIO(raw)).convert("RGB")
        print(f"  {fname} ({img.size})")
        # Save original
        orig = img.copy()
        orig.save(ORIG_DIR / f"{stem}.jpg", "JPEG", quality=90)
        for backend in BACKENDS:
            try:
                slots = parse_image(raw, fname, backend)
                summary = [f"({s['gx']},{s['gy']} {s['gw']}x{s['gh']})" for s in slots]
                print(f"    [{backend}] {len(slots)} slots: {', '.join(summary)}")
                # Save JSON
                JSON_DIR.mkdir(parents=True, exist_ok=True)
                json.dump(slots, open(JSON_DIR / f"{stem}_{backend}.json", "w"), indent=2)
                # Save viz
                VIZ_DIR.mkdir(parents=True, exist_ok=True)
                viz = draw_grid(img.copy(), slots, backend)
                viz.save(VIZ_DIR / f"{stem}_{backend}.jpg", "JPEG", quality=85)
            except Exception as e:
                print(f"    [{backend}] ERROR: {e}")

# Process both datasets
process_dataset(TEST_DIR, "jpg_frames")
process_dataset(TEMPLATE_DIR, "template_examples")
print("\nDone!")
