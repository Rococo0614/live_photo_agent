from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from .library import LibraryService
from .media_ops import MediaOps, MediaOpsError
from ..models import AssetPreprocessSummary, LivePhotoAsset


@dataclass(slots=True)
class OfflinePreprocessReport:
    library_root: str
    total_assets: int
    rebuilt_assets: int
    reused_assets: int
    failed_assets: int

    def to_dict(self) -> dict[str, object]:
        return {
            "library_root": self.library_root,
            "total_assets": self.total_assets,
            "rebuilt_assets": self.rebuilt_assets,
            "reused_assets": self.reused_assets,
            "failed_assets": self.failed_assets,
        }


class OfflinePreprocessIndexer:
    """Build and maintain offline preprocess index JSONL with incremental updates."""

    INDEX_VERSION = "v1"

    def __init__(self, library_service: LibraryService, media_ops: MediaOps | None = None) -> None:
        self.library_service = library_service
        self.media_ops = media_ops or MediaOps()

    def build(self, library_root: Path, force_rebuild: bool = False) -> OfflinePreprocessReport:
        root = library_root.resolve()
        current_rows = self._read_index_rows()
        assets = self.library_service.scan_live_photos(root)
        target_root = str(root)

        existing_by_asset_id: dict[str, AssetPreprocessSummary] = {}
        retained_rows: list[dict[str, object]] = []
        for row in current_rows:
            row_root = str(row.get("library_root", ""))
            if row_root != target_root:
                retained_rows.append(row)
                continue
            asset_id = str(row.get("asset_id", "")).strip()
            if not asset_id:
                continue
            summary_raw = row.get("summary", {})
            if not isinstance(summary_raw, dict):
                continue
            try:
                existing_by_asset_id[asset_id] = AssetPreprocessSummary.model_validate(summary_raw)
            except Exception:  # noqa: BLE001
                continue

        rebuilt_assets = 0
        reused_assets = 0
        failed_assets = 0
        new_rows: list[dict[str, object]] = []
        for asset in sorted(assets, key=lambda item: item.asset_id):
            fingerprint = self._asset_fingerprint(asset)
            previous = existing_by_asset_id.get(asset.asset_id)
            previous_fingerprint = ""
            if previous is not None:
                previous_fingerprint = str(previous.quality_signals.get("asset_fingerprint", ""))

            if (
                not force_rebuild
                and previous is not None
                and previous.version == self.INDEX_VERSION
                and previous_fingerprint == fingerprint
            ):
                summary = previous.model_copy(update={"source": "offline_indexer_reuse"})
                reused_assets += 1
            else:
                try:
                    summary = self._enrich_summary(asset=asset, fingerprint=fingerprint)
                except Exception:  # noqa: BLE001
                    failed_assets += 1
                    summary = self._fallback_summary(asset=asset, fingerprint=fingerprint)
                rebuilt_assets += 1

            new_rows.append(
                {
                    "library_root": target_root,
                    "asset_id": asset.asset_id,
                    "image_path": str(asset.image_path),
                    "motion_path": str(asset.motion_path) if asset.motion_path else None,
                    "summary": summary.model_dump(mode="json"),
                }
            )

        final_rows = [*retained_rows, *new_rows]
        self._write_index_rows(final_rows)
        return OfflinePreprocessReport(
            library_root=target_root,
            total_assets=len(assets),
            rebuilt_assets=rebuilt_assets,
            reused_assets=reused_assets,
            failed_assets=failed_assets,
        )

    def _enrich_summary(self, asset: LivePhotoAsset, fingerprint: str) -> AssetPreprocessSummary:
        image_quality = self._extract_image_quality(asset.image_path)
        quality_signals = dict(asset.preprocess_summary.quality_signals if asset.preprocess_summary else {})
        quality_signals.update(image_quality)
        quality_signals["asset_fingerprint"] = fingerprint

        editability_signals = dict(asset.preprocess_summary.editability_signals if asset.preprocess_summary else {})
        has_motion = asset.motion_path is not None
        editability_signals["has_motion"] = has_motion
        if has_motion and asset.motion_path is not None and self.media_ops.ffmpeg_available():
            try:
                video_info = self.media_ops.probe_video(asset.motion_path)
                quality_signals["motion_duration_ms"] = int(video_info.get("duration_ms", 0))
                quality_signals["motion_fps"] = float(video_info.get("fps", 0.0))
                quality_signals["motion_codec"] = str(video_info.get("codec", "unknown"))
                editability_signals["motion_resolution"] = (
                    f"{int(video_info.get('width', 0))}x{int(video_info.get('height', 0))}"
                )
            except (MediaOpsError, ValueError, TypeError):
                pass

        base = asset.preprocess_summary or self._fallback_summary(asset=asset, fingerprint=fingerprint)
        return base.model_copy(
            update={
                "version": self.INDEX_VERSION,
                "source": "offline_indexer",
                "quality_signals": quality_signals,
                "editability_signals": editability_signals,
            }
        )

    def _fallback_summary(self, asset: LivePhotoAsset, fingerprint: str) -> AssetPreprocessSummary:
        fallback = asset.preprocess_summary or AssetPreprocessSummary(media_format="livephoto" if asset.motion_path else "photo")
        quality_signals = dict(fallback.quality_signals)
        quality_signals["asset_fingerprint"] = fingerprint
        return fallback.model_copy(
            update={
                "version": self.INDEX_VERSION,
                "source": "offline_indexer_fallback",
                "quality_signals": quality_signals,
            }
        )

    def _extract_image_quality(self, image_path: Path) -> dict[str, object]:
        try:
            import cv2
        except Exception:  # noqa: BLE001
            return {}

        image = cv2.imread(str(image_path))
        if image is None:
            return {}

        height, width = image.shape[:2]
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
        brightness = float(gray.mean())
        return {
            "image_width": int(width),
            "image_height": int(height),
            "image_sharpness": round(sharpness, 4),
            "image_brightness": round(brightness, 4),
        }

    def _asset_fingerprint(self, asset: LivePhotoAsset) -> str:
        parts: list[str] = []
        image_stat = asset.image_path.stat()
        parts.append(str(asset.image_path.resolve()))
        parts.append(str(image_stat.st_size))
        parts.append(str(image_stat.st_mtime_ns))
        if asset.motion_path is not None and asset.motion_path.exists():
            motion_stat = asset.motion_path.stat()
            parts.append(str(asset.motion_path.resolve()))
            parts.append(str(motion_stat.st_size))
            parts.append(str(motion_stat.st_mtime_ns))
        return "|".join(parts)

    def _read_index_rows(self) -> list[dict[str, object]]:
        path = self.library_service.preprocess_index_file
        if not path.exists():
            return []

        rows: list[dict[str, object]] = []
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line:
                continue
            try:
                parsed = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(parsed, dict):
                rows.append(parsed)
        return rows

    def _write_index_rows(self, rows: list[dict[str, object]]) -> None:
        rows_sorted = sorted(
            rows,
            key=lambda item: (
                str(item.get("library_root", "")),
                str(item.get("asset_id", "")),
            ),
        )
        payload = "\n".join(json.dumps(row, ensure_ascii=False) for row in rows_sorted)
        if payload:
            payload += "\n"
        path = self.library_service.preprocess_index_file
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(payload, encoding="utf-8")
