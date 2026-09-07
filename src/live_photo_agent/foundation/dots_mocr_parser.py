"""dots.mocr layout detection backend.

Uses dots.mocr (3B Qwen2.5-VL-based) for precise layout bbox detection,
then converts pixel bboxes to 120×160 grid coordinates in Python.

Official dots.mocr prompt "prompt_layout_all_en" is used for best model
alignment. The model outputs bboxes in its internal resized coordinate
space; we rescale them back to original image coordinates via image_grid_thw.
"""

from __future__ import annotations

import io
import json
import logging
import math
import re
from pathlib import Path
from typing import Any

from PIL import Image

_logger = logging.getLogger(__name__)

MODEL_PATH = Path("/home/vivo/models/dots-mocr")
_model: Any = None
_processor: Any = None

# Official dots.mocr prompt from dots_mocr/utils/prompts.py
_PROMPT_LAYOUT_ALL = (
    "Please output the layout information from the PDF image, "
    "including each layout element's bbox, its category, "
    "and the corresponding text content within the bbox.\n\n"
    "1. Bbox format: [x1, y1, x2, y2]\n\n"
    "2. Layout Categories: The possible categories are "
    "['Caption', 'Footnote', 'Formula', 'List-item', 'Page-footer', "
    "'Page-header', 'Picture', 'Section-header', 'Table', 'Text', 'Title'].\n\n"
    "3. Text Extraction & Formatting Rules:\n"
    "    - Picture: For the 'Picture' category, the text field should be omitted.\n"
    "    - Formula: Format its text as LaTeX.\n"
    "    - Table: Format its text as HTML.\n"
    "    - All Others (Text, Title, etc.): Format their text as Markdown.\n\n"
    "4. Constraints:\n"
    "    - The output text must be the original text from the image, with no translation.\n"
    "    - All layout elements must be sorted according to human reading order.\n\n"
    "5. Final Output: The entire output must be a single JSON object.\n"
)

# Map dots.mocr document categories to our layout role.
# Picture → background (main canvas tile filled with user content)
# Text/Title/Caption → foreground (overlaid on top, pin_to_top)
# Others → foreground (treated as decorative overlay)
_DOC_CATEGORY_TO_ROLE: dict[str, str] = {
    "Picture": "background",
    "Text": "foreground",
    "Title": "foreground",
    "Section-header": "foreground",
    "Caption": "foreground",
    "List-item": "foreground",
    "Page-header": "foreground",
    "Page-footer": "foreground",
    "Footnote": "foreground",
    "Formula": "foreground",
    "Table": "foreground",
}

# Model vision constants (from config.json)
_PATCH_SIZE = 14


def _load_model():
    """Lazy-load dots.mocr model (singleton)."""
    global _model, _processor
    if _model is not None:
        return _model, _processor

    import torch
    from transformers import AutoModelForCausalLM, AutoProcessor

    _logger.info("[DOTS_MOCR] Loading model from %s ...", MODEL_PATH)
    _model = AutoModelForCausalLM.from_pretrained(
        str(MODEL_PATH),
        torch_dtype=torch.bfloat16,
        device_map="auto",
        trust_remote_code=True,
    )
    _processor = AutoProcessor.from_pretrained(
        str(MODEL_PATH), trust_remote_code=True, use_fast=True,
    )
    _logger.info("[DOTS_MOCR] Model loaded OK")
    return _model, _processor


def _detect_layout_dots(image_bytes: bytes) -> list[dict[str, Any]]:
    """Run dots.mocr with prompt_layout_all_en, return list of {bbox, category, text}."""
    import torch

    model, processor = _load_model()

    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    orig_w, orig_h = image.size

    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image", "image": image},
                {"type": "text", "text": _PROMPT_LAYOUT_ALL},
            ],
        }
    ]

    text = processor.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=True,
    )
    inputs = processor(
        text=[text], images=[image], return_tensors="pt",
    ).to(model.device)

    inputs.pop("mm_token_type_ids", None)

    image_grid_thw = inputs.get("image_grid_thw")
    if image_grid_thw is not None:
        grid_hw = image_grid_thw[0].tolist()
        resized_h = grid_hw[1] * _PATCH_SIZE
        resized_w = grid_hw[2] * _PATCH_SIZE
    else:
        resized_h, resized_w = orig_h, orig_w

    _logger.info(
        "[DOTS_MOCR] orig=%dx%d resize=%dx%d inference ...",
        orig_w, orig_h, resized_w, resized_h,
    )

    with torch.no_grad():
        generated_ids = model.generate(**inputs, max_new_tokens=8192, temperature=0.0)
    generated_ids_trimmed = [
        out_ids[len(in_ids):]
        for in_ids, out_ids in zip(inputs.input_ids, generated_ids)
    ]
    response = processor.batch_decode(
        generated_ids_trimmed, skip_special_tokens=True, clean_up_tokenization_spaces=False,
    )[0]

    _logger.info("[DOTS_MOCR] Raw response (first 500 chars): %s", response[:500])

    return _parse_bbox_response(response, orig_w, orig_h, resized_w, resized_h)


def _parse_bbox_response(
    response: str,
    orig_w: int,
    orig_h: int,
    resized_w: int,
    resized_h: int,
) -> list[dict[str, Any]]:
    """Parse dots.mocr JSON, rescale bboxes from resized coords to original."""
    cleaned = response.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned)

    items: list[dict[str, Any]] | None = None

    for start, end in [(cleaned.find("["), cleaned.rfind("]")),
                       (cleaned.find("{"), cleaned.rfind("}"))]:
        if start >= 0 and end > start:
            try:
                parsed = json.loads(cleaned[start:end + 1])
                if isinstance(parsed, list) and len(parsed) > 0:
                    items = parsed
                    break
                elif isinstance(parsed, dict) and isinstance(parsed.get("layout"), list):
                    items = parsed["layout"]
                    break
                elif isinstance(parsed, dict) and isinstance(parsed.get("elements"), list):
                    items = parsed["elements"]
                    break
            except json.JSONDecodeError:
                continue

    if items is None:
        try:
            items = json.loads(cleaned)
            items = items if isinstance(items, list) else [items]
        except json.JSONDecodeError:
            _logger.error("[DOTS_MOCR] Failed to parse JSON: %s", cleaned[:200])
            return []

    scale_x = orig_w / resized_w if resized_w != orig_w else 1.0
    scale_y = orig_h / resized_h if resized_h != orig_h else 1.0

    result = []
    for i, item in enumerate(items):
        if not isinstance(item, dict):
            continue
        bbox = item.get("bbox") or item.get("bounding_box") or item.get("box")
        if not bbox or len(bbox) < 4:
            continue
        x1 = float(bbox[0]) * scale_x
        y1 = float(bbox[1]) * scale_y
        x2 = float(bbox[2]) * scale_x
        y2 = float(bbox[3]) * scale_y
        category = str(item.get("category") or item.get("type") or "region")
        extracted_text = item.get("text", "")

        result.append({
            "bbox": [x1, y1, x2, y2],
            "category": category,
            "text": extracted_text,
            "z_order": i,
        })

    _logger.info("[DOTS_MOCR] Parsed %d layout regions (scale=%.3fx%.3f)",
                 len(result), scale_x, scale_y)
    return result


def bbox_to_grid_slots(
    bboxes: list[dict[str, Any]],
    img_width: int,
    img_height: int,
    grid_cols: int = 120,
    grid_rows: int = 160,
) -> list[dict[str, Any]]:
    """Convert pixel bboxes to 120×160 grid slot coordinates.

    Each slot gets:
      - gx, gy, gw, gh: grid coordinates
      - role: "background" or "foreground" (mapped from dots.mocr category)
      - pin_to_top: True for foreground slots
      - image_prompt: carried from the detected text (for future LLM use)
      - z_order: layer ordering
    """
    slots = []
    for item in bboxes:
        x1, y1, x2, y2 = item["bbox"]
        category = item.get("category", "region")
        z_order = item.get("z_order", 0)
        extracted_text = item.get("text", "")

        gx = max(0, round(x1 / img_width * grid_cols))
        gy = max(0, round(y1 / img_height * grid_rows))
        gw = max(1, round((x2 - x1) / img_width * grid_cols))
        gh = max(1, round((y2 - y1) / img_height * grid_rows))

        gx = min(gx, grid_cols - 1)
        gy = min(gy, grid_rows - 1)
        gw = min(gw, grid_cols - gx)
        gh = min(gh, grid_rows - gy)

        role = _DOC_CATEGORY_TO_ROLE.get(category, "foreground")
        is_foreground = role == "foreground"

        slots.append({
            "gx": gx,
            "gy": gy,
            "gw": gw,
            "gh": gh,
            "role": role,
            "pin_to_top": is_foreground,
            "image_prompt": str(extracted_text or ""),
            "z_order": z_order,
        })

    slots.sort(key=lambda s: s.get("z_order", 0))
    return slots


def parse_image_to_template_dots(
    image_bytes: bytes,
    image_name: str = "",
    grid_cols: int = 120,
    grid_rows: int = 160,
) -> dict[str, Any]:
    """Full pipeline: image -> dots.mocr bboxes -> grid slots -> template JSON."""
    import hashlib

    img_hash = hashlib.md5(image_bytes).hexdigest()[:12]
    template_id = f"custom_{img_hash}"

    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    img_w, img_h = image.size

    _logger.info(
        "[TEMPLATE_PARSE_DOTS] image=%s size=%dx%d bytes=%d",
        image_name, img_w, img_h, len(image_bytes),
    )

    bboxes = _detect_layout_dots(image_bytes)

    if not bboxes:
        _logger.warning("[TEMPLATE_PARSE_DOTS] No regions detected, falling back to full-canvas")
        bboxes = [{"bbox": [0, 0, img_w, img_h], "category": "Picture", "text": "", "z_order": 0}]

    slots = bbox_to_grid_slots(bboxes, img_w, img_h, grid_cols, grid_rows)

    return {
        "id": template_id,
        "name": f"自定义模板_{img_hash[:6]}",
        "style": "custom",
        "board": "xhs",
        "slots": slots,
    }