from __future__ import annotations

from pathlib import Path

from .brain import QwenPlanner
from .capability import CapabilityLayer
from .config import settings
from .foundation.library import LibraryService
from .models import LivePhotoAsset


def resolve_library_root(library_root: str | Path | None) -> Path:
    if library_root is not None:
        candidate = Path(library_root).expanduser()
        if candidate.exists():
            return candidate.resolve()
    if settings.default_library_root.exists():
        return settings.default_library_root.resolve()
    if library_root is not None:
        return Path(library_root).expanduser().resolve()
    return settings.workspace_dir.resolve()


def build_ui_bootstrap(library_root: str | Path | None = None) -> dict[str, object]:
    resolved_root = resolve_library_root(library_root)
    library_service = LibraryService()
    assets = library_service.scan_live_photos(resolved_root)
    capability_layer = CapabilityLayer()
    planner_info = _planner_info_or_fallback()
    tool_catalog = capability_layer.tool_catalog()

    albums = _build_album_overview(assets)
    asset_rows = [_serialize_asset(asset) for asset in assets]
    summary = {
        "asset_count": len(asset_rows),
        "album_count": len(albums),
        "live_photo_count": sum(1 for asset in asset_rows if asset["metadata"].get("is_live_photo") == "true"),
        "motion_ready_count": sum(1 for asset in asset_rows if asset["motion_path"]),
    }

    return {
        "app_name": settings.app_name,
        "library_root": str(resolved_root),
        "planner": planner_info,
        "summary": summary,
        "albums": albums,
        "tool_catalog": tool_catalog,
        "assets": asset_rows,
    }


def load_index_html() -> str:
    return Path(__file__).with_name("ui.html").read_text(encoding="utf-8")


def _build_album_overview(assets: list[LivePhotoAsset]) -> list[dict[str, object]]:
    albums: dict[str, dict[str, object]] = {}
    for asset in assets:
        album_name = str(asset.metadata.get("album_name", "."))
        bucket = albums.setdefault(
            album_name,
            {
                "album_name": album_name,
                "asset_count": 0,
                "live_photo_count": 0,
                "motion_ready_count": 0,
                "asset_ids": [],
            },
        )
        bucket["asset_count"] = int(bucket["asset_count"]) + 1
        bucket["asset_ids"].append(asset.asset_id)
        if str(asset.metadata.get("is_live_photo", "false")).lower() == "true":
            bucket["live_photo_count"] = int(bucket["live_photo_count"]) + 1
        if asset.motion_path is not None:
            bucket["motion_ready_count"] = int(bucket["motion_ready_count"]) + 1

    return sorted(albums.values(), key=lambda item: (str(item["album_name"]),))


def _serialize_asset(asset: LivePhotoAsset) -> dict[str, object]:
    return asset.model_dump(mode="json")


def _planner_info_or_fallback() -> dict[str, object]:
    try:
        return QwenPlanner().runtime_info()
    except RuntimeError:
        return {
            "planner_backend": settings.planner_backend,
            "planner_model": settings.qwen_model,
            "planner_endpoint": settings.qwen_endpoint,
            "local_model_dir": str(settings.local_model_dir) if settings.local_model_dir else None,
            "status": "unconfigured",
        }