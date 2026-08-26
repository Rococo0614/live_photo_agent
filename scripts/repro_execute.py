from __future__ import annotations

import json
import sys
import time
from pathlib import Path

sys.path.insert(0, "src")

from live_photo_agent.config import settings
from live_photo_agent.execution.agent import LivePhotoAgent
from live_photo_agent.models import AgentRequest
from live_photo_agent.foundation.library import LibraryService

LIB_ROOT = Path("/home/vivo/live_photo_agent/data")

def log(msg: str) -> None:
    print(msg, flush=True)

def main() -> int:
    t0 = time.perf_counter()
    lib = LibraryService()
    assets = lib.scan_live_photos(LIB_ROOT)
    live = [a for a in assets if a.metadata.get("is_live_photo") == "true"]
    jpgs = [a for a in assets if a.image_path.suffix.lower() in {".jpg", ".jpeg"}]
    log(f"[repro] scanned {len(assets)} assets, live={len(live)}, jpgs={len(jpgs)}")
    if len(live) < 3 or not jpgs:
        log("need >=3 live + >=1 jpg")
        return 1

    bg_ids = [a.asset_id for a in live[:3]]
    fg_id = jpgs[0].asset_id
    log(f"[repro] bg_ids={bg_ids} fg_id={fg_id}")

    layout = [
        {"order": 1, "id": "s1", "asset_id": bg_ids[0], "grid_x": 0, "grid_y": 0, "grid_w": 120, "grid_h": 53, "z_index": 0},
        {"order": 2, "id": "s2", "asset_id": bg_ids[1], "grid_x": 0, "grid_y": 53, "grid_w": 120, "grid_h": 54, "z_index": 1},
        {"order": 3, "id": "s3", "asset_id": bg_ids[2], "grid_x": 0, "grid_y": 107, "grid_w": 120, "grid_h": 53, "z_index": 2},
        {"order": 4, "id": "s4", "asset_id": fg_id, "foreground": True, "is_overlay": True, "z_index": 3,
         "grid_x": 40, "grid_y": 40, "grid_w": 40, "grid_h": 60, "anchor": "center", "scale": 0.45},
    ]
    text = (
        "请根据当前布局生成最终 Live Photo：固定画布为 1080x1440，布局按 120x160 网格解析。"
        "必须做空间拼接，严格使用每个布局项的 grid 与 z_index 确定位置大小和层级，禁止退化为串行拼接。"
        "布局中有 1 个前景项，必须先用 subject_segmentation 分割，再用 overlay_subject_clip 叠加。"
    )
    req = AgentRequest(
        user_id="repro",
        text=text,
        library_root=str(LIB_ROOT),
        selected_asset_ids=bg_ids + [fg_id],
        layout_context=layout,
    )

    agent = LivePhotoAgent()
    log(f"[repro] calling agent.execute at +{(time.perf_counter()-t0)*1000:.0f}ms")
    resp = agent.execute(req)
    log(f"[repro] execute returned at +{(time.perf_counter()-t0)*1000:.0f}ms")
    log(f"[repro] route_reason / plan intent: {resp.plan.intent}")
    log(f"[repro] tool_calls: {[(c.tool.value, c.arguments) for c in resp.plan.tool_calls]}")
    for r in resp.tool_results:
        log(f"[repro]   {r.tool.value}: success={r.success} payload={json.dumps(r.payload, ensure_ascii=False)[:300]}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
