from __future__ import annotations

from pathlib import Path

from ..models import LivePhotoAsset


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".heic", ".png"}
MOTION_EXTENSIONS = {".mov", ".mp4"}


class LibraryService:
    def scan_live_photos(self, library_root: Path) -> list[LivePhotoAsset]:
        if not library_root.exists():
            return []

        image_files = [
            path for path in library_root.rglob("*") if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
        ]
        motion_by_stem = {
            path.stem: path
            for path in library_root.rglob("*")
            if path.is_file() and path.suffix.lower() in MOTION_EXTENSIONS
        }

        assets: list[LivePhotoAsset] = []
        for image_path in image_files:
            tags = self._infer_tags(image_path)
            assets.append(
                LivePhotoAsset(
                    asset_id=image_path.stem,
                    image_path=image_path,
                    motion_path=motion_by_stem.get(image_path.stem),
                    tags=tags,
                    metadata={
                        "filename": image_path.name,
                        "has_motion": str(image_path.stem in motion_by_stem).lower(),
                    },
                )
            )

        return assets

    def select_assets(self, assets: list[LivePhotoAsset], selected_ids: list[str]) -> list[LivePhotoAsset]:
        if not selected_ids:
            return []
        selected_set = set(selected_ids)
        return [asset for asset in assets if asset.asset_id in selected_set]

    def search_assets(self, assets: list[LivePhotoAsset], query: str) -> list[LivePhotoAsset]:
        normalized_query = query.lower()
        results: list[LivePhotoAsset] = []
        for asset in assets:
            searchable = " ".join(
                [asset.asset_id, asset.image_path.name, *asset.tags, *asset.metadata.values()]
            ).lower()
            if any(term in searchable for term in normalized_query.split()):
                results.append(asset)
        return results

    def _infer_tags(self, image_path: Path) -> list[str]:
        stem = image_path.stem.lower().replace("_", " ").replace("-", " ")
        return [token for token in stem.split() if token]
