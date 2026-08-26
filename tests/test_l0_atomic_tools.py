from pathlib import Path
import shutil

import pytest

from live_photo_agent.capability.l0_atomic_tools import L0AtomicTools
from live_photo_agent.config import settings
from live_photo_agent.foundation.media_ops import MediaOpsError
from live_photo_agent.foundation.library import LibraryService
from live_photo_agent.models import LivePhotoAsset
from live_photo_agent.models import ToolCall, ToolName


def test_scan_library_reuses_preloaded_assets(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    library_root = tmp_path / "library"
    library_root.mkdir()
    (library_root / "a1.jpg").write_bytes(b"\xff\xd8a\xff\xd9")

    service = LibraryService()
    preloaded_assets = service.scan_live_photos(library_root)
    tools = L0AtomicTools(service)

    def _should_not_scan(_root: Path):
        raise AssertionError("scan_live_photos should not be called when preloaded assets are available")

    monkeypatch.setattr(service, "scan_live_photos", _should_not_scan)

    context: dict[str, object] = {
        "library_root": library_root,
        "assets": preloaded_assets,
    }
    call = ToolCall(tool=ToolName.SCAN_LIBRARY, reason="refresh context", arguments={})

    result = tools.scan_library(call, context)

    assert result.success is True
    assert result.payload.get("scan_source") == "preloaded"
    assert result.payload.get("asset_count") == len(preloaded_assets)


def test_scan_library_rescans_when_root_override(tmp_path: Path) -> None:
    library_a = tmp_path / "A"
    library_b = tmp_path / "B"
    library_a.mkdir()
    library_b.mkdir()
    (library_a / "a1.jpg").write_bytes(b"\xff\xd8a\xff\xd9")
    (library_b / "b1.jpg").write_bytes(b"\xff\xd8b\xff\xd9")

    service = LibraryService()
    tools = L0AtomicTools(service)

    context: dict[str, object] = {
        "library_root": library_a,
        "assets": service.scan_live_photos(library_a),
    }
    call = ToolCall(
        tool=ToolName.SCAN_LIBRARY,
        reason="switch root",
        arguments={"library_root": str(library_b)},
    )

    result = tools.scan_library(call, context)

    assert result.success is True
    assert result.payload.get("scan_source") == "rescanned"
    assert result.payload.get("asset_count") == 1
    assert str(context["library_root"]).endswith("B")


def test_concat_clips_falls_back_to_motion_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    motion_path = tmp_path / "a1.mp4"
    motion_path.write_bytes(b"video")
    asset = LivePhotoAsset(asset_id="a1", image_path=tmp_path / "a1.jpg", motion_path=motion_path)

    def _fake_concat(videos: list[Path], output_path: Path) -> Path:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"timeline")
        return output_path

    monkeypatch.setattr(tools.media_ops, "concat_videos", _fake_concat)

    call = ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={})
    context: dict[str, object] = {"library_root": tmp_path, "assets": [asset]}

    result = tools.concat_clips(call, context)

    assert result.success is True
    assert result.payload.get("segment_count") == 1
    timeline = dict(context.get("timeline", {}))
    assert Path(str(timeline.get("path", ""))).exists()


def test_concat_clips_uses_spatial_composition_for_triptych_request(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    motion_a = tmp_path / "a1.mp4"
    motion_b = tmp_path / "a2.mp4"
    motion_c = tmp_path / "a3.mp4"
    for path in (motion_a, motion_b, motion_c):
        path.write_bytes(b"video")

    assets = [
        LivePhotoAsset(asset_id="a1", image_path=tmp_path / "a1.jpg", motion_path=motion_a),
        LivePhotoAsset(asset_id="a2", image_path=tmp_path / "a2.jpg", motion_path=motion_b),
        LivePhotoAsset(asset_id="a3", image_path=tmp_path / "a3.jpg", motion_path=motion_c),
    ]

    called = {"spatial": False, "timeline": False}

    def _fake_compose(videos: list[Path], output_path: Path, *, canvas: str, layout: str) -> Path:
        _ = videos
        assert canvas == "1080x1920"
        assert layout == "triptych_landscape"
        called["spatial"] = True
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"spatial")
        return output_path

    def _fake_concat(videos: list[Path], output_path: Path) -> Path:
        _ = videos
        called["timeline"] = True
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"timeline")
        return output_path

    monkeypatch.setattr(tools.media_ops, "compose_videos_spatial", _fake_compose)
    monkeypatch.setattr(tools.media_ops, "concat_videos", _fake_concat)

    call = ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={})
    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": assets,
        "request_text": "做一个三拼，左边一个右边两个",
    }

    result = tools.concat_clips(call, context)

    assert result.success is True
    assert called["spatial"] is True
    assert called["timeline"] is False
    assert result.payload.get("composition") == "triptych_landscape"


def test_concat_clips_spatial_mode_caps_to_three_inputs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    assets = []
    for idx in range(5):
        motion = tmp_path / f"a{idx}.mp4"
        motion.write_bytes(b"video")
        assets.append(LivePhotoAsset(asset_id=f"a{idx}", image_path=tmp_path / f"a{idx}.jpg", motion_path=motion))

    capture: dict[str, object] = {}

    def _fake_compose(videos: list[Path], output_path: Path, *, canvas: str, layout: str) -> Path:
        capture["input_count"] = len(videos)
        capture["layout"] = layout
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"spatial")
        return output_path

    monkeypatch.setattr(tools.media_ops, "compose_videos_spatial", _fake_compose)

    call = ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={})
    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": assets,
        "request_text": "做一个拼贴，左边一个右边两个",
    }

    result = tools.concat_clips(call, context)

    assert result.success is True
    assert capture.get("input_count") == 3
    assert result.payload.get("candidate_count") == 5
    assert result.payload.get("composed_count") == 3


def test_concat_clips_uses_learned_layout_from_reusable_strategies(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    motion_a = tmp_path / "a1.mp4"
    motion_b = tmp_path / "a2.mp4"
    motion_c = tmp_path / "a3.mp4"
    for path in (motion_a, motion_b, motion_c):
        path.write_bytes(b"video")

    assets = [
        LivePhotoAsset(asset_id="a1", image_path=tmp_path / "a1.jpg", motion_path=motion_a),
        LivePhotoAsset(asset_id="a2", image_path=tmp_path / "a2.jpg", motion_path=motion_b),
        LivePhotoAsset(asset_id="a3", image_path=tmp_path / "a3.jpg", motion_path=motion_c),
    ]

    captured: dict[str, object] = {}

    def _fake_compose(videos: list[Path], output_path: Path, *, canvas: str, layout: str) -> Path:
        captured["layout"] = layout
        captured["count"] = len(videos)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"spatial")
        return output_path

    monkeypatch.setattr(tools.media_ops, "compose_videos_spatial", _fake_compose)

    call = ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={})
    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": assets,
        "request_text": "帮我做一个短视频",
        "reusable_strategies": [
            {
                "tool_sequence": ["scan_library", "concat_clips", "export_mp4"],
                "concat_composition": "triptych_portrait",
            }
        ],
    }

    result = tools.concat_clips(call, context)

    assert result.success is True
    assert captured.get("layout") == "triptych_portrait"
    assert captured.get("count") == 3


def test_concat_clips_respects_explicit_timeline_layout(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    motion_a = tmp_path / "a1.mp4"
    motion_b = tmp_path / "a2.mp4"
    for path in (motion_a, motion_b):
        path.write_bytes(b"video")

    assets = [
        LivePhotoAsset(asset_id="a1", image_path=tmp_path / "a1.jpg", motion_path=motion_a),
        LivePhotoAsset(asset_id="a2", image_path=tmp_path / "a2.jpg", motion_path=motion_b),
    ]

    called = {"spatial": False, "timeline": False}

    def _fake_compose(videos: list[Path], output_path: Path, *, canvas: str, layout: str) -> Path:
        _ = videos
        _ = output_path
        _ = canvas
        _ = layout
        called["spatial"] = True
        return output_path

    def _fake_concat(videos: list[Path], output_path: Path) -> Path:
        _ = videos
        called["timeline"] = True
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"timeline")
        return output_path

    monkeypatch.setattr(tools.media_ops, "compose_videos_spatial", _fake_compose)
    monkeypatch.setattr(tools.media_ops, "concat_videos", _fake_concat)

    call = ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={"layout": "timeline"})
    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": assets,
        "request_text": "做个三拼",
    }

    result = tools.concat_clips(call, context)

    assert result.success is True
    assert called["timeline"] is True
    assert called["spatial"] is False
    assert result.payload.get("composition") == "timeline"


def test_add_text_overlay_degrades_to_timeline_on_failure(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    timeline_path = tmp_path / "timeline.mp4"
    timeline_path.write_bytes(b"timeline")

    def _fake_overlay(_input: Path, _output: Path, text: str, style: str) -> Path:
        _ = text
        _ = style
        raise MediaOpsError("overlay_failed")

    monkeypatch.setattr(tools.media_ops, "add_text_overlay", _fake_overlay)

    call = ToolCall(tool=ToolName.ADD_TEXT_OVERLAY, reason="overlay", arguments={"text": "标题", "style": "minimal"})
    context: dict[str, object] = {"library_root": tmp_path, "timeline": {"path": str(timeline_path)}}

    result = tools.add_text_overlay(call, context)

    assert result.success is True
    assert result.payload.get("overlay_applied") is False
    assert result.payload.get("fallback_to_timeline") is True


def test_overlay_subject_clip_respects_arguments_and_cleans_temp_matte(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    timeline_path = tmp_path / "timeline.mp4"
    timeline_path.write_bytes(b"timeline")

    frames_dir = tmp_path / "subject_mattes" / "a1"
    frames_dir.mkdir(parents=True)
    (frames_dir / "frame_00000.png").write_bytes(b"png")

    captured: dict[str, object] = {}

    def _fake_composite(
        *,
        background_path: Path,
        foreground_frames_dir: Path,
        foreground_fps: float,
        output_path: Path,
        anchor: str,
        scale: float,
        x_offset: int,
        y_offset: int,
        fit_mode: str,
        placement: dict[str, float] | None = None,
    ) -> Path:
        _ = background_path
        _ = foreground_frames_dir
        _ = foreground_fps
        _ = scale
        _ = fit_mode
        captured["anchor"] = anchor
        captured["x_offset"] = x_offset
        captured["y_offset"] = y_offset
        captured["placement"] = placement
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"composited")
        return output_path

    monkeypatch.setattr(tools.media_ops, "composite_foreground_over_background", _fake_composite)

    call = ToolCall(
        tool=ToolName.OVERLAY_SUBJECT_CLIP,
        reason="overlay",
        arguments={"foreground_asset_id": "a1", "anchor": "center", "x_offset": 999, "y_offset": 888},
    )
    context: dict[str, object] = {
        "library_root": tmp_path,
        "timeline": {"path": str(timeline_path), "timeline_id": "t1"},
        "subject_mattes": {
            "a1": {
                "frames_dir": str(frames_dir),
                "fps": 25.0,
            }
        },
    }

    result = tools.overlay_subject_clip(call, context)

    assert result.success is True
    assert captured["anchor"] == "center"
    assert captured["x_offset"] == 999
    assert captured["y_offset"] == 888
    assert not frames_dir.exists()
    assert context["subject_mattes"] == {}


def test_extract_subject_matte_clears_stale_output_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    motion_path = tmp_path / "a1.mp4"
    motion_path.write_bytes(b"video")
    asset = LivePhotoAsset(asset_id="a1", image_path=tmp_path / "a1.jpg", motion_path=motion_path)

    stale_file = settings.workspace_dir.resolve() / ".agent_work" / "segmentation" / "subject_mattes" / "a1" / "stale.txt"
    stale_file.parent.mkdir(parents=True, exist_ok=True)
    stale_file.write_text("stale")

    def _fake_extract(video_path: Path, output_dir: Path, mode: str = "mog2") -> dict[str, object]:
        _ = video_path
        _ = mode
        output_dir.mkdir(parents=True, exist_ok=True)
        fresh = output_dir / "frame_00000.png"
        fresh.write_bytes(b"png")
        return {
            "frames_dir": str(output_dir),
            "frame_count": 1,
            "fps": 25.0,
            "width": 100,
            "height": 100,
            "average_foreground_ratio": 0.5,
        }

    monkeypatch.setattr(tools.media_ops, "extract_subject_matte_frames", _fake_extract)

    call = ToolCall(tool=ToolName.EXTRACT_SUBJECT_MATTE, reason="matte", arguments={"asset_ids": ["a1"]})
    context: dict[str, object] = {"library_root": tmp_path, "assets": [asset]}

    result = tools.extract_subject_matte(call, context)

    assert result.success is True
    assert not stale_file.exists()
    matte = context["subject_mattes"]["a1"]
    assert Path(str(matte["frames_dir"])).exists()
    shutil.rmtree(settings.workspace_dir.resolve() / ".agent_work" / "segmentation", ignore_errors=True)


def test_export_mp4_fallback_copy_on_render_error(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    timeline_path = tmp_path / "timeline.mp4"
    timeline_path.write_bytes(b"timeline")

    def _fake_export(_input: Path, _output: Path, resolution: str) -> Path:
        _ = resolution
        raise MediaOpsError("export_failed")

    def _fake_probe(path: Path) -> dict[str, object]:
        assert path.exists()
        return {"duration_ms": 1234}

    monkeypatch.setattr(tools.media_ops, "export_mp4", _fake_export)
    monkeypatch.setattr(tools.media_ops, "probe_video", _fake_probe)

    call = ToolCall(tool=ToolName.EXPORT_MP4, reason="export", arguments={"output_name": "o1", "resolution": "1080x1920"})
    context: dict[str, object] = {"library_root": tmp_path, "timeline": {"path": str(timeline_path)}}

    result = tools.export_mp4(call, context)

    assert result.success is True
    assert result.payload.get("degraded") is True
    output_path = Path(str(result.payload.get("output_path", "")))
    assert output_path.exists()


def test_export_mp4_packs_live_photo_when_possible(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    service = LibraryService()
    tools = L0AtomicTools(service)

    timeline_path = tmp_path / "timeline.mp4"
    timeline_path.write_bytes(b"timeline")

    def _fake_export(_input: Path, output: Path, resolution: str) -> Path:
        _ = resolution
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"video")
        return output

    def _fake_probe(_path: Path) -> dict[str, object]:
        return {"duration_ms": 1200}

    def _fake_cover(_video: Path, cover: Path, timestamp_ms: int | None = None) -> Path:
        _ = timestamp_ms
        cover.parent.mkdir(parents=True, exist_ok=True)
        cover.write_bytes(b"\xff\xd8cover\xff\xd9")
        return cover

    def _fake_pack(image_path: Path, video_path: Path, output_path: Path, presentation_timestamp_ms: int | None = None) -> Path:
        _ = image_path
        _ = video_path
        _ = presentation_timestamp_ms
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"motion_photo")
        return output_path

    def _fake_validate(_path: Path) -> dict[str, object]:
        return {
            "valid": True,
            "checks": {
                "jpeg_header": True,
                "xmp_motionphoto": True,
                "embedded_mp4": True,
                "embedded_video_stream": True,
                "embedded_audio_stream": True,
                "major_brand_mp42": True,
                "android_version_metadata": True,
            },
            "missing": [],
        }

    def _fake_normalize(video_path: Path, output_path: Path) -> Path:
        _ = video_path
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"normalized")
        return output_path

    monkeypatch.setattr(tools.media_ops, "export_mp4", _fake_export)
    monkeypatch.setattr(tools.media_ops, "probe_video", _fake_probe)
    monkeypatch.setattr(tools.media_ops, "extract_cover_jpeg", _fake_cover)
    monkeypatch.setattr(tools.media_ops, "normalize_motion_photo_video", _fake_normalize)
    monkeypatch.setattr(tools.media_ops, "pack_motion_photo_jpg", _fake_pack)
    monkeypatch.setattr(tools.media_ops, "validate_motion_photo_jpg", _fake_validate)

    call = ToolCall(tool=ToolName.EXPORT_MP4, reason="export", arguments={"output_name": "o2", "resolution": "1080x1920"})
    context: dict[str, object] = {"library_root": tmp_path, "timeline": {"path": str(timeline_path)}}

    result = tools.export_mp4(call, context)

    assert result.success is True
    live_photo = dict(result.payload.get("live_photo", {}))
    assert live_photo.get("status") == "ok"
    assert str(live_photo.get("output_path", "")).endswith("o2.jpg")
    assert dict(live_photo.get("validation", {})).get("valid") is True
