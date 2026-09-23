from __future__ import annotations

import hashlib
import json
import logging

_logger = logging.getLogger(__name__)
from pathlib import Path
from typing import Any

from .local_vlm import chat_with_images, parse_json_response

DEFAULT_GRID_COLS = 120
DEFAULT_GRID_ROWS = 160

CUSTOM_TEMPLATES_FILE = Path.cwd() / ".custom_templates.json"


def _image_hash(image_bytes: bytes) -> str:
    return hashlib.md5(image_bytes).hexdigest()[:12]


def _build_parse_system_prompt() -> str:
    return (
        "你是一个专业的图片版面分析助手。"
        "你的任务是分析图片中各个子图/区域的拼接布局，"
        "用百分比坐标描述每个区域的位置和大小。"
        "你只输出纯 JSON，不包含任何解释或 markdown。"
    )


def _build_parse_prompt(total_duration_s: float = 6.0, has_video: bool = False) -> str:
    temporal_schema = (
        f'\n如果输入包含动态段（总时长约 {total_duration_s:.2f} 秒），每个区域可以额外给出 '
        '"start_time_s" 和 "end_time_s"；无法判断时使用 0 和总时长。\n'
        if has_video
        else ""
    )
    return (
        "分析这张图片的版面布局。图片是由多张子图拼接而成的合成图。\n\n"
        "请输出一个 JSON 数组，描述每个子图的位置和大小：\n"
        '{"left_pct": 数字, "top_pct": 数字, "width_pct": 数字, "height_pct": 数字}\n'
        f"{temporal_schema}\n"
        "规则：\n"
        "1. 子图边缘对齐一条直线——划分比例是整数（如 1/3=33.3, 1/2=50, 1/4=25）\n"
        "2. 所有子图拼在一起应该正好覆盖整个画布，没有缝隙也没有重叠\n"
        "3. 相邻子图的边界必须对齐（如子图A的 right = 子图B的 left）\n\n"
        "示例：\n"
        "  三张图纵向拼接（三等分）：[\n"
        '    {"left_pct":0,"top_pct":0,"width_pct":100,"height_pct":33.3},\n'
        '    {"left_pct":0,"top_pct":33.3,"width_pct":100,"height_pct":33.4},\n'
        '    {"left_pct":0,"top_pct":66.7,"width_pct":100,"height_pct":33.3}\n'
        "  ]\n"
        "  2×2 四宫格：[\n"
        '    {"left_pct":0,"top_pct":0,"width_pct":50,"height_pct":50},\n'
        '    {"left_pct":50,"top_pct":0,"width_pct":50,"height_pct":50},\n'
        '    {"left_pct":0,"top_pct":50,"width_pct":50,"height_pct":50},\n'
        '    {"left_pct":50,"top_pct":50,"width_pct":50,"height_pct":50}\n'
        "  ]\n"
        "  单张完整图：[\n"
        '    {"left_pct":0,"top_pct":0,"width_pct":100,"height_pct":100}\n'
        "  ]\n\n"
        "只输出 JSON 数组，不要 markdown 代码块，不要解释。"
    )


def _snap_to_grid(slots: list[dict[str, Any]], tolerance: float = 5.0) -> list[dict[str, Any]]:
    """Snap VLM percentage coordinates to a regular grid, detect dominant axis.

    VLM sometimes outputs both horizontal and vertical splits for the same image,
    creating overlapping regions. We detect the dominant split direction and
    collapse the weaker axis.
    """
    cols, rows = set(), set()
    cols.update([0.0, 100.0])
    rows.update([0.0, 100.0])
    for s in slots:
        left = float(s.get("left_pct", 0))
        top = float(s.get("top_pct", 0))
        w = float(s.get("width_pct", 100))
        h = float(s.get("height_pct", 100))
        cols.update([left, left + w])
        rows.update([top, top + h])

    def _cluster(values: set[float], tol: float) -> list[float]:
        sorted_vals = sorted(values)
        clusters = [[sorted_vals[0]]]
        for v in sorted_vals[1:]:
            if v - clusters[-1][-1] <= tol:
                clusters[-1].append(v)
            else:
                clusters.append([v])
        return [sum(c) / len(c) for c in clusters]

    grid_cols = _cluster(cols, tolerance)
    grid_rows = _cluster(rows, tolerance)

    n_cols = len(grid_cols) - 1
    n_rows = len(grid_rows) - 1

    if n_cols > 1 and n_rows > 1 and (n_cols > 3 or n_rows > 3):
        if n_cols >= n_rows:
            grid_rows = [0.0, 100.0]
        else:
            grid_cols = [0.0, 100.0]

    def _snap(val: float, grid: list[float]) -> float:
        return min(grid, key=lambda g: abs(g - val))

    snapped = []
    for s in slots:
        left = float(s.get("left_pct", 0))
        top = float(s.get("top_pct", 0))
        w = float(s.get("width_pct", 100))
        h = float(s.get("height_pct", 100))
        new_left = _snap(left, grid_cols)
        new_right = _snap(left + w, grid_cols)
        new_top = _snap(top, grid_rows)
        new_bottom = _snap(top + h, grid_rows)
        if new_right > new_left and new_bottom > new_top:
            snapped_slot = {
                "left_pct": new_left,
                "top_pct": new_top,
                "width_pct": new_right - new_left,
                "height_pct": new_bottom - new_top,
            }
            for key in ("start_time_s", "end_time_s"):
                if key in s:
                    snapped_slot[key] = s[key]
            snapped.append(snapped_slot)
    return snapped


def _normalize_slots(
    raw_slots: list[Any],
    grid_cols: int = DEFAULT_GRID_COLS,
    grid_rows: int = DEFAULT_GRID_ROWS,
    total_duration_s: float = 6.0,
) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    min_area = (grid_cols * grid_rows) * 0.02

    pct_slots = []
    for slot in raw_slots:
        if not isinstance(slot, dict):
            continue
        if "left_pct" in slot or "top_pct" in slot:
            pct_slots.append(slot)

    if pct_slots:
        data = _snap_to_grid(pct_slots)
    else:
        data = raw_slots

    for slot in data:
        if not isinstance(slot, dict):
            continue
        if "left_pct" in slot:
            left = float(slot.get("left_pct", 0))
            top = float(slot.get("top_pct", 0))
            w = float(slot.get("width_pct", 100))
            h = float(slot.get("height_pct", 100))
            gx = max(0, min(grid_cols - 1, round(left / 100 * grid_cols)))
            gy = max(0, min(grid_rows - 1, round(top / 100 * grid_rows)))
            gw = max(1, min(grid_cols - gx, round(w / 100 * grid_cols)))
            gh = max(1, min(grid_rows - gy, round(h / 100 * grid_rows)))
        else:
            gx = max(0, min(grid_cols, int(slot.get("gx", 0))))
            gy = max(0, min(grid_rows, int(slot.get("gy", 0))))
            gw = max(1, min(grid_cols - gx, int(slot.get("gw", grid_cols))))
            gh = max(1, min(grid_rows - gy, int(slot.get("gh", grid_rows))))
        if gw * gh < min_area:
            continue
        ca = slot.get("category", "")
        slot_role = "foreground" if ca.lower() in ("overlay", "decoration", "decorative", "watermark", "sticker") else "background"
        normalized.append({
            "gx": gx,
            "gy": gy,
            "gw": gw,
            "gh": gh,
            "role": slot_role if slot_role == "foreground" else "background",
            "pin_to_top": bool(slot.get("pin_to_top", False)) or slot_role == "foreground",
            "image_prompt": str(slot.get("image_prompt", "")),
            "start_time_s": max(0.0, float(slot.get("start_time_s", 0.0))),
            "end_time_s": min(
                max(0.0, float(slot.get("end_time_s", total_duration_s))),
                max(total_duration_s, 0.1),
            ),
        })

    # Deduplicate: if two slots share >90% overlap, keep the larger one
    deduped = []
    used = [False] * len(normalized)
    for i, s1 in enumerate(normalized):
        if used[i]:
            continue
        best = s1
        best_area = s1["gw"] * s1["gh"]
        for j, s2 in enumerate(normalized):
            if i == j or used[j]:
                continue
            ox = max(0, min(s1["gx"] + s1["gw"], s2["gx"] + s2["gw"]) - max(s1["gx"], s2["gx"]))
            oy = max(0, min(s1["gy"] + s1["gh"], s2["gy"] + s2["gh"]) - max(s1["gy"], s2["gy"]))
            overlap = ox * oy
            if overlap > best_area * 0.9:
                used[j] = True
                if s2["gw"] * s2["gh"] > best_area:
                    best = s2
                    best_area = s2["gw"] * s2["gh"]
        used[i] = True
        deduped.append(best)

    # Remove full-canvas slot if there are other meaningful slots
    if len(deduped) > 1:
        total = grid_cols * grid_rows
        full_canvas = [s for s in deduped if s["gw"] * s["gh"] >= total * 0.95]
        if len(full_canvas) < len(deduped):
            deduped = [s for s in deduped if s["gw"] * s["gh"] < total * 0.95]

    return deduped if deduped else [{
        "gx": 0,
        "gy": 0,
        "gw": grid_cols,
        "gh": grid_rows,
        "role": "background",
        "pin_to_top": False,
        "image_prompt": "",
        "start_time_s": 0.0,
        "end_time_s": total_duration_s,
    }]


def _scale_slots_to_target(
    slots: list[dict[str, Any]],
    src_cols: int,
    src_rows: int,
    dst_cols: int,
    dst_rows: int,
) -> list[dict[str, Any]]:
    if src_cols == dst_cols and src_rows == dst_rows:
        return slots
    x_scale = dst_cols / src_cols
    y_scale = dst_rows / src_rows
    result: list[dict[str, Any]] = []
    for slot in slots:
        s = dict(slot)
        s["gx"] = round(slot["gx"] * x_scale)
        s["gy"] = round(slot["gy"] * y_scale)
        s["gw"] = max(1, round(slot["gw"] * x_scale))
        s["gh"] = max(1, round(slot["gh"] * y_scale))
        result.append(s)
    return result


def parse_image_to_template(
    image_bytes: bytes,
    image_name: str = "",
    grid_cols: int = 3,
    grid_rows: int = 4,
    video_path: Path | None = None,
) -> dict[str, Any]:
    """Send image to local VLM and return a template JSON dict.

    The returned dict has the same shape as TEMPLATE_LIBRARY entries:
    { id, name, style, board, slots: [{ gx, gy, gw, gh, pin_to_top, image_prompt }] }
    """
    from PIL import Image
    import io

    img_hash = _image_hash(image_bytes)
    template_id = f"custom_{img_hash}"

    _logger.info(
        "[TEMPLATE_PARSE] image=%s bytes=%d cols=%d rows=%d",
        image_name, len(image_bytes), grid_cols, grid_rows,
    )

    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    video_frames: list[Any] = []
    total_duration_s = 6.0
    if video_path is not None and Path(video_path).exists():
        video_frames, total_duration_s = _extract_video_frames(Path(video_path))
    prompt = _build_parse_prompt(total_duration_s=total_duration_s, has_video=bool(video_frames))
    system_prompt = _build_parse_system_prompt()

    extracted = chat_with_images(
        images=[image, *video_frames],
        prompt=prompt,
        system_prompt=system_prompt,
        max_new_tokens=512,
    )
    _logger.info(
        "[TEMPLATE_PARSE] extracted_len=%d extracted_preview=%s",
        len(extracted or ""), (extracted or "")[:300],
    )
    if not extracted:
        raise RuntimeError("VLM returned empty response")

    template = parse_json_response(extracted)
    if template is None:
        raise RuntimeError(f"VLM response is not valid JSON: {extracted[:200]}")

    raw_slots: list[Any] = []
    template_name = f"自定义模板_{img_hash[:6]}"
    template_style = "custom"
    template_board = "xhs"

    if isinstance(template, list):
        raw_slots = template
    elif isinstance(template, dict):
        raw_slots = template.get("slots", [])
        template_name = str(template.get("name", template_name))
        template_style = str(template.get("style", template_style))
        template_board = str(template.get("board", template_board))

    slots = _normalize_slots(raw_slots, grid_cols, grid_rows, total_duration_s=total_duration_s)
    if not slots:
        slots = [{
            "gx": 0, "gy": 0, "gw": grid_cols, "gh": grid_rows,
            "pin_to_top": False, "image_prompt": "",
            "start_time_s": 0.0, "end_time_s": total_duration_s,
        }]
    slots = _scale_slots_to_target(slots, grid_cols, grid_rows, DEFAULT_GRID_COLS, DEFAULT_GRID_ROWS)

    return {
        "id": template_id,
        "name": template_name,
        "style": template_style,
        "board": template_board,
        "slots": slots,
        "total_duration_s": total_duration_s,
    }


def _extract_video_frames(video_path: Path, max_frames: int = 4) -> tuple[list[Any], float]:
    """Extract a few representative frames and duration for coarse timing hints."""
    from PIL import Image
    import cv2

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        return [], 6.0
    fps = float(cap.get(cv2.CAP_PROP_FPS) or 30.0)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    duration = total / max(fps, 1.0) if total > 0 else 6.0
    frames: list[Any] = []
    indices = [int(total * (i + 0.5) / max_frames) for i in range(min(max_frames, total))]
    for index in indices:
        cap.set(cv2.CAP_PROP_POS_FRAMES, index)
        ok, frame = cap.read()
        if not ok:
            continue
        frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        height, width = frame.shape[:2]
        scale = 448 / max(height, width)
        if scale < 1.0:
            frame = cv2.resize(frame, (int(width * scale), int(height * scale)))
        frames.append(Image.fromarray(frame))
    cap.release()
    return frames, max(duration, 0.1)


def load_custom_templates() -> list[dict[str, Any]]:
    """Load all custom templates from the JSON file."""
    if not CUSTOM_TEMPLATES_FILE.exists():
        return []
    try:
        data = json.loads(CUSTOM_TEMPLATES_FILE.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return []
    if isinstance(data, list):
        return data
    if isinstance(data, dict) and isinstance(data.get("templates"), list):
        return data["templates"]
    return []


def save_custom_template(template: dict[str, Any]) -> dict[str, Any]:
    """Save or update a custom template. Returns the saved template."""
    templates = load_custom_templates()

    tid = template.get("id", "")
    if not tid:
        raise ValueError("Template must have an id")

    existing_idx: int | None = None
    for i, t in enumerate(templates):
        if isinstance(t, dict) and t.get("id") == tid:
            existing_idx = i
            break

    if existing_idx is not None:
        templates[existing_idx] = template
    else:
        templates.append(template)

    CUSTOM_TEMPLATES_FILE.write_text(
        json.dumps(templates, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return template


def delete_custom_template(template_id: str) -> bool:
    """Delete a custom template by id. Returns True if deleted."""
    templates = load_custom_templates()
    filtered = [t for t in templates if isinstance(t, dict) and t.get("id") != template_id]
    if len(filtered) == len(templates):
        return False
    CUSTOM_TEMPLATES_FILE.write_text(
        json.dumps(filtered, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return True


def save_all_custom_templates(templates: list[dict[str, Any]]) -> None:
    """Overwrite all custom templates (e.g. after batch delete)."""
    CUSTOM_TEMPLATES_FILE.write_text(
        json.dumps(templates, ensure_ascii=False, indent=2), encoding="utf-8"
    )
