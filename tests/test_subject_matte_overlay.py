import shutil
import subprocess
from pathlib import Path

import pytest

from live_photo_agent.capability.l0_atomic_tools import L0AtomicTools
from live_photo_agent.foundation.library import LibraryService
from live_photo_agent.foundation.media_ops import MediaOps, MediaOpsError
from live_photo_agent.models import LivePhotoAsset, ToolCall, ToolName

FFMPEG_AVAILABLE = shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None

pytestmark = pytest.mark.skipif(not FFMPEG_AVAILABLE, reason="ffmpeg/ffprobe not available in this environment")


def _write_moving_pattern_video(path: Path, duration_s: float = 1.0, size: str = "64x64", fps: int = 8) -> None:
    """Generate a tiny real mp4 with a moving test pattern so background
    subtraction has something non-static to pick up as foreground."""
    path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg",
        "-y",
        "-f",
        "lavfi",
        "-i",
        f"testsrc2=size={size}:rate={fps}:duration={duration_s}",
        "-pix_fmt",
        "yuv420p",
        str(path),
    ]
    subprocess.run(cmd, check=True, capture_output=True)


def test_extract_subject_matte_frames_produces_alpha_png_sequence(tmp_path: Path) -> None:
    import cv2

    video_path = tmp_path / "source.mp4"
    _write_moving_pattern_video(video_path)

    media_ops = MediaOps()
    output_dir = tmp_path / "matte"
    result = media_ops.extract_subject_matte_frames(video_path, output_dir, mode="mog2")

    assert result["frame_count"] > 0
    assert result["fps"] > 0
    assert 0.0 <= result["average_foreground_ratio"] <= 1.0

    frame_files = sorted(Path(result["frames_dir"]).glob("frame_*.png"))
    assert len(frame_files) == result["frame_count"]

    sample = cv2.imread(str(frame_files[0]), cv2.IMREAD_UNCHANGED)
    assert sample is not None
    assert sample.shape[2] == 4  # BGRA: alpha channel present


def test_extract_subject_matte_frames_missing_video_raises(tmp_path: Path) -> None:
    media_ops = MediaOps()
    with pytest.raises(MediaOpsError):
        media_ops.extract_subject_matte_frames(tmp_path / "missing.mp4", tmp_path / "out", mode="mog2")


def test_composite_foreground_over_background_writes_playable_output(tmp_path: Path) -> None:
    media_ops = MediaOps()

    foreground_source = tmp_path / "fg_source.mp4"
    _write_moving_pattern_video(foreground_source, duration_s=1.0)
    matte = media_ops.extract_subject_matte_frames(foreground_source, tmp_path / "matte")

    background = tmp_path / "background.mp4"
    _write_moving_pattern_video(background, duration_s=2.0, size="128x128")

    output_path = tmp_path / "composited.mp4"
    result_path = media_ops.composite_foreground_over_background(
        background_path=background,
        foreground_frames_dir=Path(matte["frames_dir"]),
        foreground_fps=float(matte["fps"]),
        output_path=output_path,
        anchor="bottom_right",
        scale=0.4,
        fit_mode="loop",
    )

    assert result_path.exists()
    probe = media_ops.probe_video(result_path)
    assert probe["duration_ms"] > 0


def test_composite_foreground_over_background_missing_frames_raises(tmp_path: Path) -> None:
    media_ops = MediaOps()
    background = tmp_path / "background.mp4"
    _write_moving_pattern_video(background)

    with pytest.raises(MediaOpsError):
        media_ops.composite_foreground_over_background(
            background_path=background,
            foreground_frames_dir=tmp_path / "empty_frames",
            foreground_fps=8.0,
            output_path=tmp_path / "out.mp4",
        )


def test_l0_extract_and_overlay_subject_clip_end_to_end(tmp_path: Path) -> None:
    """Full L0 tool flow: matte one asset, concat build a fake timeline, overlay onto it."""
    service = LibraryService()
    tools = L0AtomicTools(service)

    subject_motion = tmp_path / "subject.mp4"
    _write_moving_pattern_video(subject_motion, duration_s=1.0)
    subject_asset = LivePhotoAsset(asset_id="subject", image_path=tmp_path / "subject.jpg", motion_path=subject_motion)

    background_video = tmp_path / "background_timeline.mp4"
    _write_moving_pattern_video(background_video, duration_s=2.0, size="128x128")

    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": [subject_asset],
        "selected_assets": [subject_asset],
        "timeline": {"timeline_id": "timeline_triptych_001", "path": str(background_video), "order": ["a", "b", "c"]},
    }

    matte_call = ToolCall(
        tool=ToolName.EXTRACT_SUBJECT_MATTE,
        reason="cut out moving subject",
        arguments={"asset_ids": ["subject"]},
    )
    matte_result = tools.extract_subject_matte(matte_call, context)
    assert matte_result.success is True
    assert matte_result.payload["matte_count"] == 1
    assert "subject" in context["subject_mattes"]

    overlay_call = ToolCall(
        tool=ToolName.OVERLAY_SUBJECT_CLIP,
        reason="paste cutout onto composed background",
        arguments={"foreground_asset_id": "subject", "anchor": "bottom_right", "scale": 0.4},
    )
    overlay_result = tools.overlay_subject_clip(overlay_call, context)

    assert overlay_result.success is True
    assert overlay_result.payload["overlay_applied"] is True
    output_path = Path(str(overlay_result.payload["output_path"]))
    assert output_path.exists()
    assert output_path != background_video

    timeline = dict(context["timeline"])
    assert timeline["path"] == str(output_path)
    assert timeline["subject_overlay_applied"] is True
    assert context["subject_overlay"]["foreground_asset_id"] == "subject"


def test_overlay_subject_clip_fails_without_matte(tmp_path: Path) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    background_video = tmp_path / "background.mp4"
    _write_moving_pattern_video(background_video)

    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": [],
        "timeline": {"timeline_id": "t1", "path": str(background_video)},
    }
    call = ToolCall(
        tool=ToolName.OVERLAY_SUBJECT_CLIP,
        reason="no matte available",
        arguments={"foreground_asset_id": "missing"},
    )
    result = tools.overlay_subject_clip(call, context)
    assert result.success is False
    assert result.payload["error_code"] == "overlay_subject_clip_failed"


def test_overlay_subject_clip_fails_without_timeline(tmp_path: Path) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": [],
        "subject_mattes": {"subject": {"frames_dir": str(tmp_path), "fps": 8.0}},
    }
    call = ToolCall(
        tool=ToolName.OVERLAY_SUBJECT_CLIP,
        reason="no timeline yet",
        arguments={"foreground_asset_id": "subject"},
    )
    result = tools.overlay_subject_clip(call, context)
    assert result.success is False
    assert result.payload["error"] == "no_timeline"
