import json
from pathlib import Path

from live_photo_agent.config import settings
from live_photo_agent.foundation import LibraryService, OfflinePreprocessIndexer


def _write_tiny_jpeg(path: Path) -> None:
    path.write_bytes(b"\xff\xd8tiny-jpeg\xff\xd9")


def _read_rows(path: Path) -> list[dict[str, object]]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_tiny_video(path: Path) -> None:
    """Write a minimal valid-ish mp4 stub (not real video, but enough to register motion_path)."""
    path.write_bytes(b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isom")


def _fake_vlm_response() -> bytes:
    import json as _json
    return _json.dumps(
        {
            "choices": [
                {
                    "message": {
                        "content": _json.dumps(
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


class _FakeVLMResponse:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def read(self):
        return _fake_vlm_response()


def test_offline_preprocess_indexer_build_and_incremental_reuse(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "album_preprocess_index_file", tmp_path / ".album_preprocess_index.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)
    monkeypatch.setattr(settings, "vlm_endpoint", "https://example.test/vlm")
    monkeypatch.setattr(settings, "vlm_backend", "endpoint")
    monkeypatch.setattr(
        "live_photo_agent.foundation.vlm_semantics.urllib.request.urlopen",
        lambda req, timeout=None: _FakeVLMResponse(),
    )

    library_root = tmp_path / "library"
    library_root.mkdir()
    # Pair each jpeg with a motion clip so they register as live photos.
    _write_tiny_jpeg(library_root / "a1.jpg")
    _write_tiny_video(library_root / "a1.mp4")
    _write_tiny_jpeg(library_root / "a2.jpg")
    _write_tiny_video(library_root / "a2.mp4")

    indexer = OfflinePreprocessIndexer(LibraryService())
    first_report = indexer.build(library_root)
    assert first_report.total_assets == 2
    assert first_report.rebuilt_assets == 2
    assert first_report.reused_assets == 0

    second_report = indexer.build(library_root)
    assert second_report.total_assets == 2
    assert second_report.rebuilt_assets == 0
    assert second_report.reused_assets == 2

    rows = _read_rows(settings.album_preprocess_index_file)
    assert len(rows) == 2
    assert all(row["summary"]["source"] in {"offline_indexer", "offline_indexer_reuse"} for row in rows)
    assert all("asset_fingerprint" in row["summary"]["quality_signals"] for row in rows)
    assert all("asset_fingerprint" in row["summary"]["technical_signals"] for row in rows)
    assert all(row["summary"]["provenance"]["technical_version"] == "v2" for row in rows)
    # live photo assets get VLM semantic enrichment; non-live-photos do not.
    assert all("coarse_semantics" in row["summary"] for row in rows)
    assert all(row["summary"]["semantic_signals"].get("producer") == "endpoint" for row in rows)


def test_offline_preprocess_indexer_force_rebuild(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "album_preprocess_index_file", tmp_path / ".album_preprocess_index.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)
    monkeypatch.setattr(settings, "vlm_endpoint", "https://example.test/vlm")
    monkeypatch.setattr(settings, "vlm_backend", "endpoint")
    monkeypatch.setattr(
        "live_photo_agent.foundation.vlm_semantics.urllib.request.urlopen",
        lambda req, timeout=None: _FakeVLMResponse(),
    )

    library_root = tmp_path / "library"
    library_root.mkdir()
    _write_tiny_jpeg(library_root / "b1.jpg")
    _write_tiny_video(library_root / "b1.mp4")

    indexer = OfflinePreprocessIndexer(LibraryService())
    _ = indexer.build(library_root)
    forced = indexer.build(library_root, force_rebuild=True)
    assert forced.total_assets == 1
    assert forced.rebuilt_assets == 1
    assert forced.reused_assets == 0


def test_scan_preserves_valid_coarse_semantics(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "album_preprocess_index_file", tmp_path / ".album_preprocess_index.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)
    monkeypatch.setattr(settings, "vlm_endpoint", "https://example.test/vlm")
    monkeypatch.setattr(settings, "vlm_backend", "endpoint")

    library_root = tmp_path / "library"
    library_root.mkdir()
    image_path = library_root / "holiday.jpg"
    _write_tiny_jpeg(image_path)
    _write_tiny_video(library_root / "holiday.mp4")

    service = LibraryService()
    indexer = OfflinePreprocessIndexer(service)
    indexer.build(library_root)

    rows = _read_rows(settings.album_preprocess_index_file)
    rows[0]["summary"]["content_tags"] = ["海边", "日落"]
    rows[0]["summary"]["content_summary"] = "海边日落时的人物合影"
    rows[0]["summary"]["coarse_semantics"] = {
        "scene": ["beach", "sunset"],
        "summary": "海边日落时的人物合影",
    }
    rows[0]["summary"]["provenance"]["semantic_producer"] = "light_av_model"
    settings.album_preprocess_index_file.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )

    assets = service.scan_live_photos(library_root)

    assert assets[0].preprocess_summary is not None
    assert assets[0].preprocess_summary.content_tags == ["海边", "日落"]
    assert assets[0].preprocess_summary.coarse_semantics["scene"] == ["beach", "sunset"]
    assert assets[0].preprocess_summary.provenance["semantic_producer"] == "light_av_model"

    persisted = _read_rows(settings.album_preprocess_index_file)
    assert persisted[0]["summary"]["coarse_semantics"]["summary"] == "海边日落时的人物合影"


def test_scan_invalidates_semantics_when_asset_changes(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "album_preprocess_index_file", tmp_path / ".album_preprocess_index.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)
    monkeypatch.setattr(settings, "vlm_endpoint", "https://example.test/vlm")
    monkeypatch.setattr(settings, "vlm_backend", "endpoint")

    library_root = tmp_path / "library"
    library_root.mkdir()
    image_path = library_root / "changing.jpg"
    _write_tiny_jpeg(image_path)
    _write_tiny_video(library_root / "changing.mp4")

    service = LibraryService()
    OfflinePreprocessIndexer(service).build(library_root)
    rows = _read_rows(settings.album_preprocess_index_file)
    rows[0]["summary"]["coarse_semantics"] = {"summary": "stale"}
    settings.album_preprocess_index_file.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )

    image_path.write_bytes(b"\xff\xd8changed-jpeg-content\xff\xd9")
    assets = service.scan_live_photos(library_root)

    assert assets[0].preprocess_summary is not None
    assert assets[0].preprocess_summary.coarse_semantics == {}
