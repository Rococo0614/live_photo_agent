import json
from pathlib import Path

from live_photo_agent.config import settings
from live_photo_agent.foundation.library import LibraryService


def _embedded_live_photo_bytes() -> bytes:
    jpeg = b"\xff\xd8demo-jpeg\xff\xd9"
    mp4 = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isom"
    return jpeg + mp4


def test_scan_classifies_live_photo_and_static(tmp_path: Path, monkeypatch) -> None:
    catalog_file = tmp_path / ".album_catalog.json"
    operation_log_file = tmp_path / ".album_operations.jsonl"
    preprocess_index_file = tmp_path / ".album_preprocess_index.jsonl"
    monkeypatch.setattr(settings, "album_catalog_file", catalog_file)
    monkeypatch.setattr(settings, "album_operation_log_file", operation_log_file)
    monkeypatch.setattr(settings, "album_preprocess_index_file", preprocess_index_file)
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)

    library_root = tmp_path / "DCIM"
    camera = library_root / "Camera"
    camera.mkdir(parents=True)

    (camera / "with_pair.jpg").write_bytes(b"img")
    (camera / "with_pair.mp4").write_bytes(b"mov")
    (camera / "embedded_live.jpg").write_bytes(_embedded_live_photo_bytes())
    (camera / "normal.jpg").write_bytes(b"\xff\xd8plain\xff\xd9")

    service = LibraryService()
    assets = service.scan_live_photos(library_root)

    by_id = {asset.asset_id: asset for asset in assets}
    assert by_id["with_pair"].metadata["media_type"] == "live_photo"
    assert by_id["embedded_live"].metadata["media_type"] == "live_photo"
    assert by_id["embedded_live"].motion_path is not None
    assert by_id["embedded_live"].motion_path.exists()
    assert by_id["embedded_live"].metadata["motion_source"] == "embedded_cache"
    assert by_id["normal"].metadata["media_type"] == "image"
    assert by_id["with_pair"].preprocess_summary is not None
    assert by_id["with_pair"].preprocess_summary.media_format == "livephoto"

    state = json.loads(catalog_file.read_text(encoding="utf-8"))
    bucket = state["libraries"][str(library_root.resolve())]
    assert len(bucket["assets"]) == 3
    assert bucket["stats"]["live_photo_assets"] == 2
    assert bucket["stats"]["motion_ready_assets"] == 2

    preprocess_rows = [
        json.loads(line)
        for line in preprocess_index_file.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    assert len(preprocess_rows) == 3
    assert all(row["summary"]["media_format"] in {"livephoto", "photo"} for row in preprocess_rows)


def test_scan_enriches_summary_with_semantic_signals(tmp_path: Path, monkeypatch) -> None:
    catalog_file = tmp_path / ".album_catalog.json"
    operation_log_file = tmp_path / ".album_operations.jsonl"
    preprocess_index_file = tmp_path / ".album_preprocess_index.jsonl"
    monkeypatch.setattr(settings, "album_catalog_file", catalog_file)
    monkeypatch.setattr(settings, "album_operation_log_file", operation_log_file)
    monkeypatch.setattr(settings, "album_preprocess_index_file", preprocess_index_file)
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)
    monkeypatch.setattr(settings, "vlm_endpoint", "https://example.test/vlm")
    monkeypatch.setattr(settings, "vlm_backend", "endpoint")

    library_root = tmp_path / "DCIM"
    library_root.mkdir(parents=True)
    # Must be a live photo (jpg + mp4 pair) to trigger VLM.
    (library_root / "beach_sunset.jpg").write_bytes(b"\xff\xd8beach\xff\xd9")
    (library_root / "beach_sunset.mp4").write_bytes(b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isom")

    class _FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def read(self):
            return json.dumps(
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "summary": "海边日落的人物合影",
                                        "theme": "海边日落",
                                        "scene_tags": ["beach", "sunset"],
                                        "subject_tags": ["people", "family"],
                                        "motion_tags": ["still"],
                                        "audio_tags": ["no_motion"],
                                        "search_keywords": ["海边", "日落", "人物"],
                                    }
                                )
                            }
                        }
                    ]
                }
            ).encode("utf-8")

    monkeypatch.setattr(
        "live_photo_agent.foundation.vlm_semantics.urllib.request.urlopen",
        lambda req, timeout=None: _FakeResponse(),
    )

    service = LibraryService()
    assets = service.scan_live_photos(library_root)

    assert len(assets) == 1
    summary = assets[0].preprocess_summary
    assert summary is not None
    assert summary.content_summary == "海边日落的人物合影"
    assert summary.semantic_signals["summary"] == "海边日落的人物合影"
    assert "beach" in summary.coarse_semantics.get("scene", []) or "sunset" in summary.coarse_semantics.get("scene", [])


def test_scan_records_livephoto_cover_frame_position(tmp_path: Path, monkeypatch) -> None:
    catalog_file = tmp_path / ".album_catalog.json"
    operation_log_file = tmp_path / ".album_operations.jsonl"
    preprocess_index_file = tmp_path / ".album_preprocess_index.jsonl"
    monkeypatch.setattr(settings, "album_catalog_file", catalog_file)
    monkeypatch.setattr(settings, "album_operation_log_file", operation_log_file)
    monkeypatch.setattr(settings, "album_preprocess_index_file", preprocess_index_file)
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)

    library_root = tmp_path / "DCIM"
    library_root.mkdir(parents=True)
    (library_root / "highlight.jpg").write_bytes(b"\xff\xd8cover\xff\xd9")
    (library_root / "highlight.mp4").write_bytes(b"mov")

    service = LibraryService()
    monkeypatch.setattr(
        service.media_ops,
        "locate_cover_frame",
        lambda video_path, image_path: {
            "cover_frame_index": 54,
            "cover_frame_timestamp_ms": 1800,
            "cover_frame_position_ratio": 0.36,
            "cover_frame_match_score": 0.97,
        },
    )
    assets = service.scan_live_photos(library_root)

    summary = assets[0].preprocess_summary
    assert summary is not None
    assert summary.editability_signals["cover_frame_timestamp_ms"] == 1800
    assert summary.editability_signals["cover_frame_position_ratio"] == 0.36
    assert summary.technical_signals["cover_frame_index"] == 54

    rows = [
        json.loads(line)
        for line in preprocess_index_file.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    assert rows[0]["summary"]["editability_signals"]["cover_frame_timestamp_ms"] == 1800


def test_search_assets_uses_semantic_content(tmp_path: Path, monkeypatch) -> None:
    catalog_file = tmp_path / ".album_catalog.json"
    operation_log_file = tmp_path / ".album_operations.jsonl"
    preprocess_index_file = tmp_path / ".album_preprocess_index.jsonl"
    monkeypatch.setattr(settings, "album_catalog_file", catalog_file)
    monkeypatch.setattr(settings, "album_operation_log_file", operation_log_file)
    monkeypatch.setattr(settings, "album_preprocess_index_file", preprocess_index_file)
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)
    monkeypatch.setattr(settings, "vlm_endpoint", "https://example.test/vlm")
    monkeypatch.setattr(settings, "vlm_backend", "endpoint")

    library_root = tmp_path / "DCIM"
    library_root.mkdir(parents=True)
    image_path = library_root / "beach_sunset.jpg"
    image_path.write_bytes(b"\xff\xd8beach\xff\xd9")
    (library_root / "beach_sunset.mp4").write_bytes(b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isom")

    class _FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def read(self):
            return json.dumps(
                {
                    "choices": [
                        {
                            "message": {
                                "content": json.dumps(
                                    {
                                        "summary": "海边日落的人物合影",
                                        "theme": "海边日落",
                                        "scene_tags": ["beach", "sunset"],
                                        "subject_tags": ["people", "family"],
                                        "motion_tags": ["still"],
                                        "audio_tags": ["no_motion"],
                                        "search_keywords": ["海边", "日落", "人物"],
                                    }
                                )
                            }
                        }
                    ]
                }
            ).encode("utf-8")

    monkeypatch.setattr(
        "live_photo_agent.foundation.vlm_semantics.urllib.request.urlopen",
        lambda req, timeout=None: _FakeResponse(),
    )

    service = LibraryService()
    assets = service.scan_live_photos(library_root)

    matches = service.search_assets(assets, "日落 海边")
    assert [asset.asset_id for asset in matches] == ["beach_sunset"]


def test_catalog_sync_removes_deleted_without_affecting_other_library(tmp_path: Path, monkeypatch) -> None:
    catalog_file = tmp_path / ".album_catalog.json"
    operation_log_file = tmp_path / ".album_operations.jsonl"
    preprocess_index_file = tmp_path / ".album_preprocess_index.jsonl"
    monkeypatch.setattr(settings, "album_catalog_file", catalog_file)
    monkeypatch.setattr(settings, "album_operation_log_file", operation_log_file)
    monkeypatch.setattr(settings, "album_preprocess_index_file", preprocess_index_file)
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)

    root_a = tmp_path / "DCIM_A"
    root_b = tmp_path / "DCIM_B"
    root_a.mkdir()
    root_b.mkdir()

    (root_a / "a1.jpg").write_bytes(b"\xff\xd8a\xff\xd9")
    (root_b / "b1.jpg").write_bytes(b"\xff\xd8b\xff\xd9")

    service = LibraryService()
    service.scan_live_photos(root_a)
    service.scan_live_photos(root_b)

    (root_a / "a1.jpg").unlink()
    service.scan_live_photos(root_a)

    state = json.loads(catalog_file.read_text(encoding="utf-8"))
    lib_a = state["libraries"][str(root_a.resolve())]["assets"]
    lib_b = state["libraries"][str(root_b.resolve())]["assets"]
    assert len(lib_a) == 0
    assert len(lib_b) == 1

    by_root: dict[str, list[dict[str, object]]] = {}
    for row in [
        json.loads(line)
        for line in preprocess_index_file.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]:
        by_root.setdefault(str(row.get("library_root", "")), []).append(row)
    assert str(root_a.resolve()) not in by_root
    assert len(by_root[str(root_b.resolve())]) == 1

    log_rows = [
        json.loads(line)
        for line in operation_log_file.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    assert any(row.get("op") == "deleted" and row.get("library_root") == str(root_a.resolve()) for row in log_rows)
