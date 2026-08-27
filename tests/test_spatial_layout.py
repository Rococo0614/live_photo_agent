from pathlib import Path
import sys
import types

import pytest

from live_photo_agent.capability.l0_atomic_tools import L0AtomicTools
from live_photo_agent.foundation.layout_resolver import LayoutResolver
from live_photo_agent.foundation.library import LibraryService
from live_photo_agent.models import (
    AgentRequest,
    CompositionTemplate,
    LayoutRole,
    LayoutSlot,
    ToolCall,
    ToolName,
)


def _make_asset(asset_id: str, tmp_path: Path) -> object:
    motion = tmp_path / f"{asset_id}.mp4"
    motion.write_bytes(b"video")
    from live_photo_agent.models import LivePhotoAsset

    return LivePhotoAsset(asset_id=asset_id, image_path=tmp_path / f"{asset_id}.jpg", motion_path=motion)


def _grid_layout_context(asset_ids: list[str], boxes: list[dict[str, int]]) -> list[dict[str, object]]:
    return [
        {
            "id": f"slot_{i}",
            "asset_id": aid,
            "grid_x": b["x"],
            "grid_y": b["y"],
            "grid_w": b["w"],
            "grid_h": b["h"],
            "z_index": i + 1,
        }
        for i, (aid, b) in enumerate(zip(asset_ids, boxes))
    ]


def test_layout_resolver_produces_edge_anchored_percentages() -> None:
    # GRID_LAYOUT is 120 x 160. A box at grid (0,0,60,80) should be the left
    # half / top half of the canvas, expressed as EDGE-anchored percentages.
    resolver = LayoutResolver()
    template = resolver.resolve(
        [{"asset_id": "a1", "grid_x": 0, "grid_y": 0, "grid_w": 60, "grid_h": 80}]
    )
    slot = template.slots[0]
    assert slot.left_pct == 0.0
    assert slot.top_pct == 0.0
    assert slot.width_pct == pytest.approx(50.0, abs=0.01)
    assert slot.height_pct == pytest.approx(50.0, abs=0.01)
    assert slot.grid_x == 0 and slot.grid_y == 0 and slot.grid_w == 60 and slot.grid_h == 80


def test_concat_clips_uses_composition_template_placements(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The user's grid drag must win over text-keyword guessing."""
    service = LibraryService()
    tools = L0AtomicTools(service)
    assets = [_make_asset(aid, tmp_path) for aid in ("a1", "a2")]

    layout_context = _grid_layout_context(
        ["a1", "a2"],
        [{"x": 0, "y": 0, "w": 60, "h": 160}, {"x": 60, "y": 0, "w": 60, "h": 160}],
    )
    composition_template = LayoutResolver().resolve(layout_context).model_dump(mode="json")

    captured: dict[str, object] = {}

    def _fake_compose(videos, output_path, *, canvas, layout, placements=None):
        captured["layout"] = layout
        captured["placements"] = placements
        captured["count"] = len(videos)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"spatial")
        return output_path

    monkeypatch.setattr(tools.media_ops, "compose_videos_spatial", _fake_compose)

    call = ToolCall(
        tool=ToolName.CONCAT_CLIPS,
        reason="concat",
        arguments={},
    )
    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": assets,
        # Text explicitly asks for timeline, but the grid template must win.
        "request_text": "串成一个时间轴视频",
        "layout_context": layout_context,
        "composition_template": composition_template,
    }

    result = tools.concat_clips(call, context)

    assert result.success is True
    assert captured["layout"] == "template"
    assert captured["count"] == 2
    placements = captured["placements"]
    assert placements is not None
    # a1: left half (edge anchored)
    assert placements[0]["left"] == pytest.approx(0.0, abs=0.01)
    assert placements[0]["width"] == pytest.approx(50.0, abs=0.01)
    # a2: right half (edge anchored, not centered)
    assert placements[1]["left"] == pytest.approx(50.0, abs=0.01)
    assert placements[1]["width"] == pytest.approx(50.0, abs=0.01)


def test_concat_clips_spatial_template_supports_more_than_three(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """A 4-tile grid must compose all 4 tiles, not be capped to 3."""
    service = LibraryService()
    tools = L0AtomicTools(service)
    assets = [_make_asset(f"a{i}", tmp_path) for i in range(4)]

    # 2x2 grid on 120x160 -> each tile 60x80 grid cells.
    boxes = [
        {"x": 0, "y": 0, "w": 60, "h": 80},
        {"x": 60, "y": 0, "w": 60, "h": 80},
        {"x": 0, "y": 80, "w": 60, "h": 80},
        {"x": 60, "y": 80, "w": 60, "h": 80},
    ]
    layout_context = _grid_layout_context([f"a{i}" for i in range(4)], boxes)
    composition_template = LayoutResolver().resolve(layout_context).model_dump(mode="json")

    captured: dict[str, object] = {}

    def _fake_compose(videos, output_path, *, canvas, layout, placements=None):
        captured["count"] = len(videos)
        captured["placements"] = placements
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"spatial")
        return output_path

    monkeypatch.setattr(tools.media_ops, "compose_videos_spatial", _fake_compose)

    call = ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={})
    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": assets,
        "layout_context": layout_context,
        "composition_template": composition_template,
    }

    result = tools.concat_clips(call, context)

    assert result.success is True
    assert captured["count"] == 4
    assert len(captured["placements"]) == 4
    assert result.payload.get("composed_count") == 4


def test_concat_clips_falls_back_to_text_when_no_template(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Without a composition template, the legacy text-driven path still works."""
    service = LibraryService()
    tools = L0AtomicTools(service)
    assets = [_make_asset(aid, tmp_path) for aid in ("a1", "a2", "a3")]

    captured: dict[str, object] = {}

    def _fake_compose(videos, output_path, *, canvas, layout, placements=None):
        captured["layout"] = layout
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"spatial")
        return output_path

    monkeypatch.setattr(tools.media_ops, "compose_videos_spatial", _fake_compose)

    call = ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={})
    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": assets,
        "request_text": "做一个三拼，左边一个右边两个",
    }

    result = tools.concat_clips(call, context)

    assert result.success is True
    assert captured["layout"] == "triptych_landscape"
    assert result.payload.get("composition") == "triptych_landscape"


def test_media_ops_placement_uses_edge_anchored_coordinates(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Verify the ffmpeg filter graph positions tiles by LEFT/TOP edge."""
    from live_photo_agent.foundation.media_ops import MediaOps

    ops = MediaOps()
    captured_cmd: dict[str, list[str]] = {}

    def _fake_run(cmd):
        captured_cmd["cmd"] = list(cmd)

    monkeypatch.setattr(ops, "_run", _fake_run)

    v1 = tmp_path / "v1.mp4"
    v2 = tmp_path / "v2.mp4"
    v1.write_bytes(b"v")
    v2.write_bytes(b"v")
    out = tmp_path / "out.mp4"

    ops.compose_videos_spatial(
        [v1, v2],
        out,
        canvas="1000x2000",
        layout="template",
        placements=[
            {"left": 0.0, "top": 0.0, "width": 50.0, "height": 100.0},
            {"left": 50.0, "top": 0.0, "width": 50.0, "height": 100.0},
        ],
    )

    cmd = captured_cmd["cmd"]
    filter_index = cmd.index("-filter_complex")
    fc = cmd[filter_index + 1]
    # tile width = 1000*50% = 500; left tile x = 0, right tile x = 500.
    assert "overlay=0:0" in fc
    assert "overlay=500:0" in fc
    # Both inputs are fed (not capped to 3).
    assert cmd.count(str(v1)) == 1
    assert cmd.count(str(v2)) == 1


def test_end_to_end_grid_drives_spatial_layout(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Full chain: AgentRequest(layout_context) -> composition_template -> concat_clips."""
    service = LibraryService()
    tools = L0AtomicTools(service)
    assets = [_make_asset(aid, tmp_path) for aid in ("a1", "a2", "a3")]

    layout_context = _grid_layout_context(
        ["a1", "a2", "a3"],
        [
            {"x": 0, "y": 0, "w": 60, "h": 160},
            {"x": 60, "y": 0, "w": 60, "h": 80},
            {"x": 60, "y": 80, "w": 60, "h": 80},
        ],
    )
    request = AgentRequest(
            user_id="web-demo",
            text="按我框的布局来",
            library_root=str(tmp_path),
            selected_asset_ids=["a1", "a2", "a3"],
            layout_context=layout_context,
        )
    composition_template = LayoutResolver().resolve(request.layout_context).model_dump(mode="json")

    captured: dict[str, object] = {}

    def _fake_compose(videos, output_path, *, canvas, layout, placements=None):
        captured["layout"] = layout
        captured["placements"] = placements
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"spatial")
        return output_path

    monkeypatch.setattr(tools.media_ops, "compose_videos_spatial", _fake_compose)

    call = ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={})
    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": assets,
        "request_text": request.text,
        "layout_context": request.layout_context,
        "composition_template": composition_template,
    }

    result = tools.concat_clips(call, context)

    assert result.success is True
    assert captured["layout"] == "template"
    placements = captured["placements"]
    # a1 left half, a2 top-right quarter, a3 bottom-right quarter.
    assert placements[0]["left"] == pytest.approx(0.0, abs=0.01)
    assert placements[0]["width"] == pytest.approx(50.0, abs=0.01)
    assert placements[1]["left"] == pytest.approx(50.0, abs=0.01)
    assert placements[1]["top"] == pytest.approx(0.0, abs=0.01)
    assert placements[2]["left"] == pytest.approx(50.0, abs=0.01)
    assert placements[2]["top"] == pytest.approx(50.0, abs=0.01)


def test_compose_command_uses_edge_anchored_coordinates(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The generated ffmpeg overlay coordinates must be LEFT/TOP EDGE anchored,
    matching LayoutResolver / frontend grid semantics (not center)."""
    from live_photo_agent.foundation.media_ops import MediaOps

    ops = MediaOps()
    captured: dict[str, object] = {}

    def _fake_run(cmd):
        captured["cmd"] = list(cmd)

    monkeypatch.setattr(ops, "_run", _fake_run)
    monkeypatch.setattr(ops, "_ensure_binaries", lambda: None)

    v1 = tmp_path / "v1.mp4"
    v2 = tmp_path / "v2.mp4"
    v1.write_bytes(b"a")
    v2.write_bytes(b"b")

    # Canvas 1000x2000. Tile 1: left=0%, top=0%, 50%x50% -> x=0, y=0, 500x1000
    # Tile 2: left=50%, top=50%, 50%x50% -> x=500, y=1000, 500x1000
    ops.compose_videos_spatial(
        [v1, v2],
        tmp_path / "out.mp4",
        canvas="1000x2000",
        layout="template",
        placements=[
            {"left": 0.0, "top": 0.0, "width": 50.0, "height": 50.0},
            {"left": 50.0, "top": 50.0, "width": 50.0, "height": 50.0},
        ],
    )

    cmd = captured["cmd"]
    filter_graph = " ".join(cmd)
    # Edge-anchored overlay coordinates, not center (which would be -250/-500).
    assert "overlay=0:0" in filter_graph
    assert "overlay=500:1000" in filter_graph
    assert "overlay=250:500" not in filter_graph
    assert "overlay=-250:-500" not in filter_graph


def test_compose_supports_more_than_three_tiles(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Regression guard: spatial compose must not silently drop tiles beyond 3."""
    from live_photo_agent.foundation.media_ops import MediaOps

    ops = MediaOps()
    captured: dict[str, object] = {}

    def _fake_run(cmd):
        captured["cmd"] = list(cmd)

    monkeypatch.setattr(ops, "_run", _fake_run)
    monkeypatch.setattr(ops, "_ensure_binaries", lambda: None)

    paths = []
    for i in range(5):
        p = tmp_path / f"v{i}.mp4"
        p.write_bytes(bytes([i]))
        paths.append(p)

    placements = [
        {"left": (i * 20) % 100, "top": 0.0, "width": 20.0, "height": 100.0}
        for i in range(5)
    ]
    ops.compose_videos_spatial(
        paths, tmp_path / "out.mp4", canvas="1000x1000", layout="template", placements=placements
    )

    cmd = captured["cmd"]
    # All 5 inputs must be passed to ffmpeg (5 "-i" flags for our clips).
    input_flags = [arg for arg in cmd if arg == "-i"]
    clip_inputs = [arg for arg in cmd if str(arg).startswith(str(tmp_path) + "/v")]
    assert len(clip_inputs) == 5
    assert len(input_flags) >= 5


def _grid_layout_context_foreground(asset_ids, boxes, foreground_ids):
    items = []
    for aid, box, idx in zip(asset_ids, boxes, range(len(asset_ids))):
        item = {
            "order": idx + 1,
            "id": f"slot_{idx}",
            "asset_id": aid,
            "grid_x": box["x"],
            "grid_y": box["y"],
            "grid_w": box["w"],
            "grid_h": box["h"],
            "z_index": idx + 1,
            "foreground": aid in foreground_ids,
            "is_overlay": aid in foreground_ids,
        }
        items.append(item)
    return items


def test_overlay_subject_clip_uses_template_foreground_placement(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Foreground segmentation must be embedded at the framed canvas position
    (edge-anchored percentages), not forced into a center/anchor keyword."""
    service = LibraryService()
    tools = L0AtomicTools(service)

    # 3 background tiles + 1 foreground (the segmented subject) framed at the
    # bottom-right quarter of the canvas.
    layout_context = _grid_layout_context_foreground(
        ["bg1", "bg2", "bg3", "fg1"],
        [
            {"x": 0, "y": 0, "w": 40, "h": 160},
            {"x": 40, "y": 0, "w": 40, "h": 80},
            {"x": 40, "y": 80, "w": 40, "h": 80},
            {"x": 60, "y": 60, "w": 40, "h": 60},
        ],
        foreground_ids={"fg1"},
    )
    composition_template = LayoutResolver().resolve(layout_context).model_dump(mode="json")

    captured: dict[str, object] = {}

    def _fake_composite(background_path, foreground_frames_dir, foreground_fps, output_path, **kwargs):
        captured["placement"] = kwargs.get("placement")
        captured["anchor"] = kwargs.get("anchor")
        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"overlay")
        return output_path

    monkeypatch.setattr(tools.media_ops, "composite_foreground_over_background", _fake_composite)

    call = ToolCall(
        tool=ToolName.OVERLAY_SUBJECT_CLIP,
        reason="overlay",
        arguments={"foreground_asset_id": "fg1", "anchor": "center", "scale": 0.45},
    )
    context: dict[str, object] = {
        "library_root": tmp_path,
        "composition_template": composition_template,
        "timeline": {"timeline_id": "tl", "path": str(tmp_path / "bg.mp4")},
        "subject_mattes": {"fg1": {"frames_dir": str(tmp_path / "matte"), "fps": 15.0}},
    }

    result = tools.overlay_subject_clip(call, context)

    assert result.success is True
    # Template placement must win over the anchor keyword.
    assert captured["placement"] is not None
    assert captured["anchor"] == "center"  # anchor still passed, but placement overrides
    # Foreground framed at grid (60,60,40,60) on a 120x160 grid:
    # left = 60/120*100 = 50.0, top = 60/160*100 = 37.5,
    # width = 40/120*100 = 33.333, height = 60/160*100 = 37.5
    assert captured["placement"]["left"] == pytest.approx(50.0, abs=0.01)
    assert captured["placement"]["top"] == pytest.approx(37.5, abs=0.01)
    assert captured["placement"]["width"] == pytest.approx(33.333, abs=0.01)
    assert captured["placement"]["height"] == pytest.approx(37.5, abs=0.01)
    assert context["subject_overlay"]["used_template_placement"] is True


def test_overlay_subject_clip_falls_back_to_explicit_args(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """When no template slot matches, explicit left/top/width/height arguments
    (from the deterministic planner) must drive placement."""
    service = LibraryService()
    tools = L0AtomicTools(service)

    captured: dict[str, object] = {}

    def _fake_composite(background_path, foreground_frames_dir, foreground_fps, output_path, **kwargs):
        captured["placement"] = kwargs.get("placement")
        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"overlay")
        return output_path

    monkeypatch.setattr(tools.media_ops, "composite_foreground_over_background", _fake_composite)

    call = ToolCall(
        tool=ToolName.OVERLAY_SUBJECT_CLIP,
        reason="overlay",
        arguments={
            "foreground_asset_id": "fg1",
            "anchor": "center",
            "left": 25.0,
            "top": 25.0,
            "width": 50.0,
            "height": 50.0,
        },
    )
    # No composition_template -> explicit args must be used.
    context: dict[str, object] = {
        "library_root": tmp_path,
        "timeline": {"timeline_id": "tl", "path": str(tmp_path / "bg.mp4")},
        "subject_mattes": {"fg1": {"frames_dir": str(tmp_path / "matte"), "fps": 15.0}},
    }

    result = tools.overlay_subject_clip(call, context)

    assert result.success is True
    assert captured["placement"] == {"left": 25.0, "top": 25.0, "width": 50.0, "height": 50.0}
    assert context["subject_overlay"]["used_template_placement"] is True


def test_overlay_subject_clip_anchor_fallback_without_coords(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Without any spatial info, anchor-based placement is preserved (no regression)."""
    service = LibraryService()
    tools = L0AtomicTools(service)

    captured: dict[str, object] = {}

    def _fake_composite(background_path, foreground_frames_dir, foreground_fps, output_path, **kwargs):
        captured["placement"] = kwargs.get("placement")
        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"overlay")
        return output_path

    monkeypatch.setattr(tools.media_ops, "composite_foreground_over_background", _fake_composite)

    call = ToolCall(
        tool=ToolName.OVERLAY_SUBJECT_CLIP,
        reason="overlay",
        arguments={"foreground_asset_id": "fg1", "anchor": "bottom_right"},
    )
    context: dict[str, object] = {
        "library_root": tmp_path,
        "timeline": {"timeline_id": "tl", "path": str(tmp_path / "bg.mp4")},
        "subject_mattes": {"fg1": {"frames_dir": str(tmp_path / "matte"), "fps": 15.0}},
    }

    result = tools.overlay_subject_clip(call, context)

    assert result.success is True
    assert captured["placement"] is None
    assert context["subject_overlay"]["used_template_placement"] is False


def test_extract_subject_matte_passes_edit_rect_to_segmentation(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """A user-framed edit_rect must be forwarded to the still-cutout path."""
    service = LibraryService()
    tools = L0AtomicTools(service)

    asset = _make_asset("subj", tmp_path)
    captured: dict[str, object] = {}

    # Let the real _extract_subject_matte_from_still run, but stub the heavy
    # segmentation + image decode so the test needs no real image/opencv.
    monkeypatch.setattr(
        tools,
        "_normalize_edit_rect_to_pixels",
        staticmethod(lambda asset, rect: (128, 128, 256, 256) if rect else None),
    )

    def _fake_still(asset, out, edit_rect=None):
        captured["edit_rect"] = edit_rect
        return {
            "frames_dir": str(out), "frame_count": 1, "fps": 15.0, "width": 100, "height": 100,
            "average_foreground_ratio": 0.3, "source_type": "still_image",
        }
    monkeypatch.setattr(tools, "_extract_subject_matte_from_still", _fake_still)

    call = ToolCall(
        tool=ToolName.EXTRACT_SUBJECT_MATTE,
        reason="cutout",
        arguments={"asset_ids": ["subj"], "edit_rect": {"x": 0.25, "y": 0.25, "w": 0.5, "h": 0.5}},
    )
    context: dict[str, object] = {"library_root": tmp_path, "assets": [asset]}
    result = tools.extract_subject_matte(call, context)

    assert result.success is True
    # edit_rect must reach the still-cutout helper (which forwards it to grabCut).
    assert captured["edit_rect"] == {"x": 0.25, "y": 0.25, "w": 0.5, "h": 0.5}


def test_segment_subject_uses_provided_rect(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """segment_subject must honor an explicit rectangle instead of whole-frame."""
    from live_photo_agent.foundation.media_ops import MediaOps

    ops = MediaOps()
    grab_rects: list[object] = []

    class _FakeArray:
        def __init__(self, shape):
            self.shape = shape

        def __setitem__(self, key, value):
            pass

        def __getitem__(self, key):
            return _FakeArray(self.shape)

        def __eq__(self, other):
            return _FakeArray(self.shape)

        def __or__(self, other):
            return _FakeArray(self.shape)

        def astype(self, dtype):
            return _FakeArray(self.shape)

        def __mul__(self, other):
            return _FakeArray(self.shape)

        def mean(self, *args, **kwargs):
            return 0.3

    def _fake_imread(path, *args, **kwargs):
        return _FakeArray((400, 300, 3))

    def _fake_resize(img, size, *args, **kwargs):
        return _FakeArray((size[1], size[0], 3))

    def _fake_grabcut(img, mask, rect, bg, fg, n, mode):
        grab_rects.append(rect)
        mask[...] = 3

    def _fake_imwrite(path, img, *args, **kwargs):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_bytes(b"mask")
        return True

    fake_cv2 = types.SimpleNamespace(
        imread=_fake_imread,
        resize=_fake_resize,
        grabCut=_fake_grabcut,
        imwrite=_fake_imwrite,
        IMREAD_GRAYSCALE=0,
        IMREAD_COLOR=1,
        GC_INIT_WITH_RECT=0,
        INTER_NEAREST=0,
    )
    fake_np = types.SimpleNamespace(
        zeros=lambda shape, dtype: _FakeArray(shape),
        uint8="uint8",
        float64="float64",
    )
    monkeypatch.setitem(sys.modules, "cv2", fake_cv2)
    monkeypatch.setitem(sys.modules, "numpy", fake_np)

    out = tmp_path / "mask.png"
    ops.segment_subject(tmp_path / "src.jpg", out, mode="person_first", rect=(10, 20, 100, 80))

    assert grab_rects, "grabCut was not called"
    # The provided rect must be passed through verbatim to grabCut.
    assert grab_rects[0] == (10, 20, 100, 80)


def test_layout_resolver_honors_frontend_canvas_size() -> None:
    """The resolved canvas must come from the frontend preset, not the default."""
    layout_context = [
        {
            "id": "slot_0",
            "asset_id": "a1",
            "grid_x": 0,
            "grid_y": 0,
            "grid_w": 120,
            "grid_h": 160,
            "z_index": 1,
            "canvas_width": 1920,
            "canvas_height": 1080,
            "grid_cols": 120,
            "grid_rows": 160,
        }
    ]
    template = LayoutResolver().resolve(layout_context)
    assert template.canvas_width == 1920
    assert template.canvas_height == 1080
    # Full-canvas slot must still resolve to 100% under the non-default canvas.
    slot = template.slots[0]
    assert slot.left_pct == 0.0
    assert slot.top_pct == 0.0
    assert slot.width_pct == 100.0
    assert slot.height_pct == 100.0


def test_concat_clips_materializes_static_video_for_jpeg(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """A pure still (no motion clip) must be turned into a static video and
    composed into the canvas, instead of being silently dropped."""
    from live_photo_agent.models import LivePhotoAsset

    service = LibraryService()
    tools = L0AtomicTools(service)
    # Still asset with NO motion_path -> previously skipped by concat_clips.
    jpg = tmp_path / "still1.jpg"
    jpg.write_bytes(b"\xff\xd8still\xff\xd9")
    asset = LivePhotoAsset(asset_id="still1", image_path=jpg, motion_path=None)
    assets = [asset]

    layout_context = _grid_layout_context(["still1"], [{"x": 0, "y": 0, "w": 120, "h": 160}])
    composition_template = LayoutResolver().resolve(layout_context).model_dump(mode="json")

    composed: dict[str, object] = {}
    static_calls: list[object] = []

    def _fake_compose(videos, output_path, *, canvas, layout, placements=None):
        composed["video_count"] = len(videos)
        composed["placements"] = placements
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"spatial")
        return output_path

    def _fake_static(image_path, output_path, **kwargs):
        static_calls.append(image_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"static")
        return output_path

    monkeypatch.setattr(tools.media_ops, "compose_videos_spatial", _fake_compose)
    monkeypatch.setattr(tools.media_ops, "concat_videos", lambda v, o: (_ for _ in ()).throw(RuntimeError("should not concat")))
    monkeypatch.setattr(tools.media_ops, "image_to_static_video", _fake_static)

    call = ToolCall(tool=ToolName.CONCAT_CLIPS, reason="concat", arguments={})
    context: dict[str, object] = {
        "library_root": tmp_path,
        "assets": assets,
        "request_text": "把这张静态图拼进画布",
        "layout_context": layout_context,
        "composition_template": composition_template,
    }
    result = tools.concat_clips(call, context)

    assert result.success is True
    # The still was materialized into exactly one video and composed.
    assert composed.get("video_count") == 1
    assert composed.get("placements") is not None
