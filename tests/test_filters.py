import shutil
from pathlib import Path

import pytest

pytest.importorskip("cv2")

from live_photo_agent.capability.l0_atomic_tools import L0AtomicTools
from live_photo_agent.foundation.library import LibraryService
from live_photo_agent.models import LivePhotoAsset, ToolCall, ToolName


def test_extract_subject_matte_with_optimizations(tmp_path: Path) -> None:
    # create a simple test image
    import cv2
    import numpy as np

    img = (np.ones((200, 200, 3), dtype="uint8") * 255).copy()
    cv2.putText(img, "A", (50, 120), cv2.FONT_HERSHEY_SIMPLEX, 3, (0, 0, 0), 5)
    img_path = tmp_path / "s1.jpg"
    cv2.imwrite(str(img_path), img)

    service = LibraryService()
    tools = L0AtomicTools(service)

    asset = LivePhotoAsset(asset_id="s1", image_path=img_path)
    context = {
        "library_root": tmp_path,
        "assets": [asset],
        "selected_assets": [asset],
    }

    call = ToolCall(
        tool=ToolName.EXTRACT_SUBJECT_MATTE,
        reason="cut out moving subject",
        arguments={"asset_ids": ["s1"], "optimize_filters": {"mask": {"gauss": 7}, "foreground": {"denoise": True}}},
    )

    result = tools.extract_subject_matte(call, context)
    assert result.success is True
    assert "s1" in context.get("subject_mattes", {})
    md = context["subject_mattes"]["s1"]
    frames_dir = Path(md.get("frames_dir"))
    assert frames_dir.exists()
    files = list(frames_dir.glob("frame_*.png"))
    assert len(files) == md.get("frame_count")
