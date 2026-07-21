from __future__ import annotations

import traceback
from pathlib import Path

from live_photo_agent.config import settings
from live_photo_agent.models import AgentRequest, ToolName
from live_photo_agent.orchestrator import LivePhotoAgent


def main() -> None:
    settings.planner_backend = "local_hf"
    settings.local_model_dir = Path(
        "/home/vivo/live_photo_agent/models/qwen/Qwen--Qwen3-4B-Instruct-2507/snapshots/cdbee75f17c01a7cc42f958dc650907174af0554"
    )
    settings.local_device = "auto"
    settings.local_dtype = "auto"
    settings.local_max_new_tokens = 128
    settings.qwen_model = "Qwen/Qwen3-4B-Instruct-2507"

    agent = LivePhotoAgent()
    request = AgentRequest(
        user_id="local-model-smoke",
        text="pick candidates and summarize",
        library_root=Path("/home/vivo/live_photo_agent/data/repacked"),
        selected_asset_ids=[],
        guided_tool_names=[ToolName.SCAN_LIBRARY, ToolName.SEARCH_BY_TEXT, ToolName.SUMMARIZE_RESULTS],
        input_image_paths=[],
        input_video_paths=[],
    )

    try:
        response = agent.execute(request)
    except Exception:
        traceback.print_exc()
        return

    print("INTENT", response.plan.intent)
    print("NEED_CLARIFICATION", response.plan.need_clarification)
    print("TOOLS", [call.tool.value for call in response.plan.tool_calls])
    print("FINAL", response.final_response[:300])


if __name__ == "__main__":
    main()
