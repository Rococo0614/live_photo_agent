from pathlib import Path

from live_photo_agent.config import settings
from live_photo_agent.execution.input_preprocessor import InputPreprocessor
from live_photo_agent.foundation.library import LibraryService
from live_photo_agent.models import AgentRequest


def test_prepare_merges_library_and_explicit_media(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "album_preprocess_index_file", tmp_path / ".album_preprocess_index.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)

    library_root = tmp_path / "library"
    library_root.mkdir()
    (library_root / "a1.jpg").write_bytes(b"img")
    (library_root / "a1.mov").write_bytes(b"mov")

    extra_dir = tmp_path / "extra"
    extra_dir.mkdir()
    (extra_dir / "trip.jpg").write_bytes(b"img")
    (extra_dir / "trip.mp4").write_bytes(b"mov")

    request = AgentRequest(
        user_id="u1",
        text="请从我的素材里选一个适合封面的",
        library_root=library_root,
        selected_asset_ids=[],
        input_image_paths=[extra_dir / "trip.jpg"],
        input_video_paths=[extra_dir / "trip.mp4"],
    )

    preprocessor = InputPreprocessor()
    prepared = preprocessor.prepare(request, LibraryService())

    asset_ids = {asset.asset_id for asset in prepared.assets}
    assert "a1" in asset_ids
    assert "trip" in asset_ids
    assert "trip" in prepared.request.selected_asset_ids
    assert prepared.library_summary["asset_count"] == 2
    assert prepared.library_summary["selected_asset_count"] == 1
    assert prepared.library_summary["preprocessed_asset_count"] == 2
    trip_asset = next(asset for asset in prepared.assets if asset.asset_id == "trip")
    assert trip_asset.preprocess_summary is not None
    assert trip_asset.preprocess_summary.source == "explicit_input"


def test_prepare_tracks_missing_and_unresolved_video_only(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "album_preprocess_index_file", tmp_path / ".album_preprocess_index.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)

    library_root = tmp_path / "library"
    library_root.mkdir()

    orphan_video = tmp_path / "orphan.mp4"
    orphan_video.write_bytes(b"mov")

    request = AgentRequest(
        user_id="u1",
        text="处理我的素材",
        library_root=library_root,
        selected_asset_ids=[],
        input_image_paths=[tmp_path / "missing.jpg"],
        input_video_paths=[orphan_video],
    )

    preprocessor = InputPreprocessor()
    prepared = preprocessor.prepare(request, LibraryService())

    assert prepared.library_summary["asset_count"] == 0
    assert prepared.preprocess_info["missing_image_paths"]
    assert "orphan" in prepared.preprocess_info["unresolved_video_only_asset_ids"]
