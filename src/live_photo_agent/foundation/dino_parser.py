"""Grounding DINO backend for sub-image region detection.

Uses zero-shot open-vocabulary detection to find "photo/picture/image"
regions in collage images. Post-processes overlapping boxes with NMS
and converts to 120x160 grid coordinates.

Supports both Tiny (~172M) and Base (~232M) variants.
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

TEXT_PROMPTS = "photo . picture . image . snapshot . photograph"

_MODEL_TINY = None
_PROCESSOR_TINY = None
_MODEL_BASE = None
_PROCESSOR_BASE = None


def _load_model(model_id: str):
    global _MODEL_TINY, _PROCESSOR_TINY, _MODEL_BASE, _PROCESSOR_BASE

    if model_id == "tiny":
        if _MODEL_TINY is not None:
            return _MODEL_TINY, _PROCESSOR_TINY
        var = "_MODEL_TINY"
        proc_var = "_PROCESSOR_TINY"
        hf_id = "IDEA-Research/grounding-dino-tiny"
    else:
        if _MODEL_BASE is not None:
            return _MODEL_BASE, _PROCESSOR_BASE
        var = "_MODEL_BASE"
        proc_var = "_PROCESSOR_BASE"
        hf_id = "IDEA-Research/grounding-dino-base"

    import torch
    from transformers import AutoProcessor, AutoModelForZeroShotObjectDetection

    _logger.info("[DINO] Loading %s (%s) ...", hf_id, model_id)
    processor = AutoProcessor.from_pretrained(hf_id)
    model = AutoModelForZeroShotObjectDetection.from_pretrained(
        hf_id, torch_dtype=torch.float32,
    ).to("cuda")
    _logger.info("[DINO] %s loaded OK", model_id)

    if model_id == "tiny":
        _MODEL_TINY, _PROCESSOR_TINY = model, processor
    else:
        _MODEL_BASE, _PROCESSOR_BASE = model, processor

    return model, processor


def _iou(box1: list[float], box2: list[float]) -> float:
    """Compute IoU between two boxes [x1, y1, x2, y2]."""
    x1 = max(box1[0], box2[0])
    y1 = max(box1[1], box2[1])
    x2 = min(box1[2], box2[2])
    y2 = min(box1[3], box2[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    area1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
    area2 = (box2[2] - box2[0]) * (box2[3] - box2[1])
    return inter / (area1 + area2 - inter + 1e-8)


def _nms(boxes: list[tuple[list[float], float]], iou_thresh: float = 0.5) -> list[tuple[list[float], float]]:
    """Non-maximum suppression on boxes with scores."""
    boxes = sorted(boxes, key=lambda x: -x[1])
    keep = []
    for box, score in boxes:
        if all(_iou(box, b) < iou_thresh for b, _ in keep):
            keep.append((box, score))
    return keep


def _deduplicate_boxes(
    boxes: list[list[float]], img_w: int, img_h: int,
) -> list[list[float]]:
    """Remove full-canvas boxes and boxes that are nearly identical to another."""
    if not boxes:
        return boxes

    total_area = img_w * img_h
    filtered = []
    for i, b1 in enumerate(boxes):
        area1 = (b1[2] - b1[0]) * (b1[3] - b1[1])
        if area1 > total_area * 0.90:
            continue
        dup = False
        for j, b2 in enumerate(boxes):
            if i == j:
                continue
            if _iou(b1, b2) > 0.95:
                dup = True
                break
        if not dup:
            filtered.append(b1)

    return filtered


def _detect_regions_dino(image_bytes: bytes, model_id: str = "tiny") -> list[dict[str, Any]]:
    """Run Grounding DINO, return list of {x, y, w, h} region dicts."""
    import torch

    model, processor = _load_model(model_id)
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    img_w, img_h = image.size

    _logger.info("[DINO] image=%dx%d inference ...", img_w, img_h)

    inputs = processor(images=image, text=TEXT_PROMPTS, return_tensors="pt").to(model.device)

    with torch.no_grad():
        outputs = model(**inputs)

    target_sizes = torch.tensor([image.size[::-1]])
    # Use lower thresholds for Base model to catch more sub-images
    thresh = 0.10 if model_id == "base" else 0.15
    results = processor.post_process_grounded_object_detection(
        outputs, target_sizes=target_sizes, threshold=thresh, text_threshold=thresh,
    )[0]

    if len(results["boxes"]) == 0:
        _logger.info("[DINO] No regions detected")
        return [{"x": 0, "y": 0, "w": img_w, "h": img_h}]

    raw_boxes = [(box.tolist(), score.item()) for box, score in zip(results["boxes"], results["scores"])]
    _logger.info("[DINO] %d raw detections", len(raw_boxes))

    nms_boxes = _nms(raw_boxes, iou_thresh=0.7)
    _logger.info("[DINO] %d after NMS", len(nms_boxes))

    boxes = [b for b, _ in nms_boxes]
    boxes = _deduplicate_boxes(boxes, img_w, img_h)
    _logger.info("[DINO] %d after dedup", len(boxes))

    if not boxes:
        return [{"x": 0, "y": 0, "w": img_w, "h": img_h}]

    total_area = img_w * img_h
    min_area = total_area * 0.01  # at least 1% of canvas

    regions = []
    for box in boxes:
        x1, y1, x2, y2 = [int(v) for v in box]
        area = (x2 - x1) * (y2 - y1)
        if area < min_area:
            continue
        regions.append({"x": x1, "y": y1, "w": x2 - x1, "h": y2 - y1})

    if not regions:
        return [{"x": 0, "y": 0, "w": img_w, "h": img_h}]

    return regions


def _regions_to_grid_slots(
    regions: list[dict[str, Any]], img_w: int, img_h: int,
    grid_cols: int, grid_rows: int,
) -> list[dict[str, Any]]:
    """Convert pixel regions to grid slots."""
    slots = []
    for i, r in enumerate(regions):
        gx = max(0, round(r["x"] / img_w * grid_cols))
        gy = max(0, round(r["y"] / img_h * grid_rows))
        gw = max(1, min(grid_cols - gx, round(r["w"] / img_w * grid_cols)))
        gh = max(1, min(grid_rows - gy, round(r["h"] / img_h * grid_rows)))

        is_full_width = r["w"] / img_w > 0.85
        role = "background" if is_full_width else "foreground"

        slots.append({
            "gx": gx, "gy": gy, "gw": gw, "gh": gh,
            "role": role,
            "pin_to_top": not is_full_width,
            "image_prompt": "",
            "z_order": i,
        })

    slots.sort(key=lambda s: s.get("z_order", 0))
    return slots


def parse_image_to_template_dino(
    image_bytes: bytes,
    image_name: str = "",
    grid_cols: int = DEFAULT_COLS,
    grid_rows: int = DEFAULT_ROWS,
) -> dict[str, Any]:
    """Full pipeline: image -> Grounding DINO Tiny -> regions -> grid -> template."""
    return _parse_pipeline(image_bytes, image_name, grid_cols, grid_rows, "tiny")


def parse_image_to_template_dino_base(
    image_bytes: bytes,
    image_name: str = "",
    grid_cols: int = DEFAULT_COLS,
    grid_rows: int = DEFAULT_ROWS,
) -> dict[str, Any]:
    """Full pipeline: image -> Grounding DINO Base -> regions -> grid -> template."""
    return _parse_pipeline(image_bytes, image_name, grid_cols, grid_rows, "base")


def _parse_pipeline(
    image_bytes: bytes,
    image_name: str,
    grid_cols: int,
    grid_rows: int,
    model_id: str,
) -> dict[str, Any]:
    import hashlib
    img_hash = hashlib.md5(image_bytes).hexdigest()[:12]
    template_id = f"custom_{img_hash}"

    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    img_w, img_h = image.size

    _logger.info(
        "[TEMPLATE_PARSE_DINO_%s] image=%s size=%dx%d bytes=%d",
        model_id.upper(), image_name, img_w, img_h, len(image_bytes),
    )

    regions = _detect_regions_dino(image_bytes, model_id)
    slots = _regions_to_grid_slots(regions, img_w, img_h, grid_cols, grid_rows)

    return {
        "id": template_id,
        "name": f"自定义模板_{img_hash[:6]}",
        "style": "custom",
        "board": "xhs",
        "slots": slots,
    }