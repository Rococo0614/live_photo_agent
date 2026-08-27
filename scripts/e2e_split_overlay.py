#!/usr/bin/env python3
"""End-to-end test: 3 live photos spatially tiled + 1 asset split and overlaid.

Simulates the frontend payload: layout_context with 3 background slots
(live photos) and 1 foreground slot (asset with edit_rect for segmentation).
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from live_photo_agent.models import AgentRequest
from live_photo_agent.orchestrator import LivePhotoAgent

LIBRARY_ROOT = Path("/home/vivo/live_photo_agent/data")

# Asset IDs are image_path.stem (from library scan)
BG_IDS = ["1", "2", "11"]       # 3 live photos as background
FG_ID = "IMG_9259."              # 1 asset as foreground overlay

layout_context = [
    {
        "order": 1, "id": "slot_bg_1", "kind": "source_asset",
        "label": BG_IDS[0], "asset_id": BG_IDS[0],
        "grid_x": 0, "grid_y": 0, "grid_w": 40, "grid_h": 160,
        "z_index": 1, "foreground": False, "is_overlay": False,
        "canvas_preset": "xhs", "canvas_width": 1080, "canvas_height": 1440,
        "grid_cols": 120, "grid_rows": 160,
    },
    {
        "order": 2, "id": "slot_bg_2", "kind": "source_asset",
        "label": BG_IDS[1], "asset_id": BG_IDS[1],
        "grid_x": 40, "grid_y": 0, "grid_w": 40, "grid_h": 160,
        "z_index": 2, "foreground": False, "is_overlay": False,
        "canvas_preset": "xhs", "canvas_width": 1080, "canvas_height": 1440,
        "grid_cols": 120, "grid_rows": 160,
    },
    {
        "order": 3, "id": "slot_bg_3", "kind": "source_asset",
        "label": BG_IDS[2], "asset_id": BG_IDS[2],
        "grid_x": 80, "grid_y": 0, "grid_w": 40, "grid_h": 160,
        "z_index": 3, "foreground": False, "is_overlay": False,
        "canvas_preset": "xhs", "canvas_width": 1080, "canvas_height": 1440,
        "grid_cols": 120, "grid_rows": 160,
    },
    {
        "order": 4, "id": "slot_fg_overlay", "kind": "source_asset",
        "label": FG_ID, "asset_id": FG_ID,
        "grid_x": 30, "grid_y": 40, "grid_w": 60, "grid_h": 80,
        "z_index": 10, "foreground": True, "is_overlay": True,
        "canvas_preset": "xhs", "canvas_width": 1080, "canvas_height": 1440,
        "grid_cols": 120, "grid_rows": 160,
        "edit_rect": {"x": 0.2, "y": 0.2, "w": 0.6, "h": 0.6},
        "edit_prompt": "分割人像",
    },
]

intent_text = (
    "请根据当前布局生成最终 Live Photo：固定画布为 1080x1440（XHS 3:4），"
    "布局按 120x160 网格解析。必须做空间拼接（concat_clips 的空间布局模式），"
    "严格使用每个布局项的 grid_x/grid_y/grid_w/grid_h 与 z_index 确定画布上的位置、大小和层级。"
    "布局中有 1 个前景项（foreground/is_overlay），必须先用 extract_subject_matte 对其做主体分割，"
    "再用 overlay_subject_clip 叠加到拼接结果之上，按其布局位置定位。"
    "并把 1 个画布编辑产物叠加到拼接结果上。"
    "处理中间步骤：分割人像。请保留完整的意图识别、工具使用和中间处理记录。"
)

request = AgentRequest(
    user_id="e2e-test",
    text=intent_text,
    library_root=str(LIBRARY_ROOT),
    selected_asset_ids=BG_IDS + [FG_ID],
    guided_tool_names=["concat_clips", "extract_subject_matte", "overlay_subject_clip"],
    layout_context=layout_context,
    operation_log=[
        {"intent": "分割人像", "tool": "interactive_canvas_edit", "status": f"已保存到素材 {FG_ID}"},
    ],
)

agent = LivePhotoAgent()
print("=== Starting e2e execution ===")
response = agent.execute(request)

print("\n=== Plan ===")
print(f"Intent: {response.plan.intent}")
print(f"Tool calls: {len(response.plan.tool_calls)}")
for tc in response.plan.tool_calls:
    print(f"  - {tc.tool.value}: {tc.reason}")

print("\n=== Tool Results ===")
for tr in response.tool_results:
    print(f"  {tr.tool.value}: success={tr.success}")
    if tr.payload:
        for k, v in tr.payload.items():
            val_str = str(v)
            if len(val_str) > 120:
                val_str = val_str[:120] + "..."
            print(f"    {k}: {val_str}")

print("\n=== Final Response ===")
print(response.final_response[:500] if response.final_response else "(empty)")

print("\n=== Context keys ===")
for k in sorted(response.context.keys()):
    val = response.context[k]
    val_str = str(val)
    if len(val_str) > 200:
        val_str = val_str[:200] + "..."
    print(f"  {k}: {val_str}")

ct = response.context.get("composition_template")
if ct:
    print("\n=== Composition Template ===")
    print(json.dumps(ct, indent=2, ensure_ascii=False, default=str)[:800])

timeline = response.context.get("timeline")
if timeline:
    print("\n=== Timeline ===")
    print(json.dumps(timeline, indent=2, ensure_ascii=False, default=str)[:500])

print("\n=== DONE ===")
