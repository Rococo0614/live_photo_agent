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


def test_offline_preprocess_indexer_build_and_incremental_reuse(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "album_preprocess_index_file", tmp_path / ".album_preprocess_index.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)

    library_root = tmp_path / "library"
    library_root.mkdir()
    _write_tiny_jpeg(library_root / "a1.jpg")
    _write_tiny_jpeg(library_root / "a2.jpg")

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


def test_offline_preprocess_indexer_force_rebuild(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "album_preprocess_index_file", tmp_path / ".album_preprocess_index.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)

    library_root = tmp_path / "library"
    library_root.mkdir()
    _write_tiny_jpeg(library_root / "b1.jpg")

    indexer = OfflinePreprocessIndexer(LibraryService())
    _ = indexer.build(library_root)
    forced = indexer.build(library_root, force_rebuild=True)
    assert forced.total_assets == 1
    assert forced.rebuilt_assets == 1
    assert forced.reused_assets == 0
