"""Pure CV layout detection backend for social media cards.

Two strategies:
  A. Flat-color designs: K-means color quantization + connected components
  B. Collage/composite photos: row/column variance projection

Strategy B - Variance-based split detection:
  Collages have uniform-color borders between sub-images. These borders are
  rows/columns with LOW color variance, sandwiched between HIGH variance
  regions (textured sub-images). We detect these low-variance bands as
  split lines.

Pipeline:
  1. If unique_colors < 30k: K-means (strategy A)
  2. Otherwise: variance projection (strategy B)
  3. Convert to 120x160 grid coordinates
"""

from __future__ import annotations

import hashlib
import io
import logging
from typing import Any

import numpy as np
from PIL import Image

_logger = logging.getLogger(__name__)

DEFAULT_COLS = 120
DEFAULT_ROWS = 160

def _cv2():
    import cv2
    return cv2

def _pil_to_cv2(pil_image: Image.Image) -> np.ndarray:
    cv2 = _cv2()
    return cv2.cvtColor(np.array(pil_image), cv2.COLOR_RGB2BGR)

def _merge_overlapping(regions: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if len(regions) <= 1:
        return regions
    merged = []
    used = [False] * len(regions)
    for i, r1 in enumerate(regions):
        if used[i]:
            continue
        x1 = r1["x"]; y1 = r1["y"]; x2 = x1 + r1["w"]; y2 = y1 + r1["h"]
        for j in range(i + 1, len(regions)):
            if used[j]:
                continue
            r2 = regions[j]
            rx1 = r2["x"]; ry1 = r2["y"]; rx2 = rx1 + r2["w"]; ry2 = ry1 + r2["h"]
            if max(0, min(x2, rx2) - max(x1, rx1)) * max(0, min(y2, ry2) - max(y1, ry1)) > 0:
                x1 = min(x1, rx1); y1 = min(y1, ry1)
                x2 = max(x2, rx2); y2 = max(y2, ry2)
                used[j] = True
        merged.append({"x": x1, "y": y1, "w": x2 - x1, "h": y2 - y1, "area": (x2 - x1) * (y2 - y1)})
        used[i] = True
    return merged

def _merge_small_regions(regions, img_w, img_h, min_ratio=0.05):
    total = img_w * img_h
    big = [r for r in regions if r["w"] * r["h"] / total >= min_ratio]
    if not big:
        return [{"x": 0, "y": 0, "w": img_w, "h": img_h}]
    return big

def _kmeans_regions(cv_img, img_w, img_h):
    cv2 = _cv2()
    pixels = cv_img.reshape((-1, 3)).astype(np.float32)
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 1.0)
    _, labels, centers = cv2.kmeans(pixels, 6, None, criteria, 10, cv2.KMEANS_RANDOM_CENTERS)
    label_map = labels.reshape((img_h, img_w)).astype(np.uint8)
    min_area = int(img_w * img_h * 0.003)
    regions = []
    for label_id in np.unique(label_map):
        mask = (label_map == label_id).astype(np.uint8) * 255
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for c in contours:
            x, y, bw, bh = cv2.boundingRect(c)
            if bw * bh < min_area or bw < 10 or bh < 10:
                continue
            regions.append({"x": int(x), "y": int(y), "w": int(bw), "h": int(bh)})
    return regions

def _find_low_variance_splits(var_profile, size, min_gap_pct=0.08):
    """Find split positions where variance is low (uniform band) between high-variance regions."""
    if len(var_profile) < 3:
        return [0, size]

    min_gap = max(3, int(size * min_gap_pct))
    margin = int(size * 0.03)

    normalized = (var_profile - var_profile.min()) / (var_profile.max() - var_profile.min() + 1e-8)

    low_threshold = 0.15
    high_threshold = 0.5

    low_runs = []
    i = 0
    while i < len(normalized):
        if normalized[i] < low_threshold:
            start = i
            while i < len(normalized) and normalized[i] < low_threshold:
                i += 1
            end = i - 1
            if end - start >= 2 and margin < start < size - margin:
                mid = (start + end) // 2
                low_runs.append((mid, start, end))
        else:
            i += 1

    if len(low_runs) > 6:
        low_runs.sort(key=lambda x: normalized[x[0]])
        low_runs = low_runs[:6]

    low_runs.sort(key=lambda x: x[0])

    splits = [0]
    for mid, start, end in low_runs:
        if mid - splits[-1] >= min_gap:
            splits.append(mid)

    if size - splits[-1] >= min_gap:
        splits.append(size)
    else:
        splits[-1] = size

    return splits

def _variance_regions(cv_img, img_w, img_h):
    """Detect regions via row/column color variance for collage images."""
    cv2 = _cv2()

    target_w = 200
    scale = target_w / img_w
    target_h = max(30, int(img_h * scale))
    small = cv2.resize(cv_img, (target_w, target_h))
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)

    row_var = gray.std(axis=1)
    row_var = cv2.GaussianBlur(row_var.reshape(-1, 1), (5, 1), 0).flatten()

    col_var = gray.std(axis=0)
    col_var = cv2.GaussianBlur(col_var.reshape(-1, 1), (5, 1), 0).flatten()

    row_splits = _find_low_variance_splits(row_var, target_h, min_gap_pct=0.08)
    col_splits = _find_low_variance_splits(col_var, target_w, min_gap_pct=0.08)

    if len(row_splits) > 4:
        row_splits = [0, target_h]
    if len(col_splits) > 4:
        col_splits = [0, target_w]

    _logger.info("[CV_PARSER] variance splits: rows=%s cols=%s", row_splits, col_splits)

    inv_scale = 1.0 / scale
    regions = []
    for i in range(len(row_splits) - 1):
        for j in range(len(col_splits) - 1):
            x1 = int(col_splits[j] * inv_scale)
            x2 = int(col_splits[j+1] * inv_scale)
            y1 = int(row_splits[i] * inv_scale)
            y2 = int(row_splits[i+1] * inv_scale)
            if (x2 - x1) * (y2 - y1) > 100:
                regions.append({"x": x1, "y": y1, "w": x2 - x1, "h": y2 - y1})

    return regions

def _detect_layout_cv(image_bytes):
    cv2 = _cv2()
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    img_w, img_h = image.size
    cv_img = _pil_to_cv2(image)
    unique_colors = len(np.unique(cv_img.reshape(-1, 3), axis=0))
    if unique_colors < 30000:
        _logger.info("[CV_PARSER] %dx%d unique=%d -> K-means", img_w, img_h, unique_colors)
        raw_regions = _kmeans_regions(cv_img, img_w, img_h)
    else:
        _logger.info("[CV_PARSER] %dx%d unique=%d -> variance", img_w, img_h, unique_colors)
        raw_regions = _variance_regions(cv_img, img_w, img_h)
    _logger.info("[CV_PARSER] %d raw regions", len(raw_regions))
    if not raw_regions:
        return [{"x": 0, "y": 0, "w": img_w, "h": img_h}]
    total_area = img_w * img_h
    filtered = [r for r in raw_regions if (r["w"] * r["h"]) / total_area < 0.9]
    if not filtered:
        return [{"x": 0, "y": 0, "w": img_w, "h": img_h}]
    filtered.sort(key=lambda r: (r["y"], r["x"]))
    merged = _merge_overlapping(filtered)
    _logger.info("[CV_PARSER] %d regions after merge", len(merged))
    merged = _merge_small_regions(merged, img_w, img_h, min_ratio=0.05)
    _logger.info("[CV_PARSER] %d regions after size filter", len(merged))
    return merged

def _regions_to_slots(regions, img_w, img_h, grid_cols, grid_rows):
    slots = []
    for i, r in enumerate(regions):
        gx = max(0, min(round(r["x"] / img_w * grid_cols), grid_cols - 1))
        gy = max(0, min(round(r["y"] / img_h * grid_rows), grid_rows - 1))
        gw = max(1, min(round(r["w"] / img_w * grid_cols), grid_cols - gx))
        gh = max(1, min(round(r["h"] / img_h * grid_rows), grid_rows - gy))
        is_foreground = r["w"] < img_w * 0.9
        slots.append({
            "gx": gx, "gy": gy, "gw": gw, "gh": gh,
            "role": "foreground" if is_foreground else "background",
            "pin_to_top": is_foreground,
            "image_prompt": "",
            "z_order": i,
        })
    slots.sort(key=lambda s: s.get("z_order", 0))
    return slots

def parse_image_to_template_cv(image_bytes, image_name="", grid_cols=DEFAULT_COLS, grid_rows=DEFAULT_ROWS):
    img_hash = hashlib.md5(image_bytes).hexdigest()[:12]
    template_id = f"custom_{img_hash}"
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    img_w, img_h = image.size
    _logger.info("[TEMPLATE_PARSE_CV] image=%s size=%dx%d bytes=%d", image_name, img_w, img_h, len(image_bytes))
    regions = _detect_layout_cv(image_bytes)
    slots = _regions_to_slots(regions, img_w, img_h, grid_cols, grid_rows)
    return {
        "id": template_id,
        "name": f"自定义模板_{img_hash[:6]}",
        "style": "custom",
        "board": "xhs",
        "slots": slots,
    }
