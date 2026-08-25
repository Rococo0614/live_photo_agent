from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from datetime import datetime, timezone

from ..config import settings
from ..foundation.library import LibraryService
from ..foundation.media_ops import MediaOps, MediaOpsError
from ..foundation.vlm_semantics import VLMSemanticAnalyzer
from ..models import AgentRequest, AssetPreprocessSummary, LivePhotoAsset


@dataclass(slots=True)
class PreparedInput:
    request: AgentRequest
    assets: list[LivePhotoAsset]
    library_summary: dict[str, object]
    preprocess_info: dict[str, object]


class InputPreprocessor:
    """Normalize multimodal request inputs into unified library assets."""

    def __init__(self) -> None:
        self.media_ops = MediaOps()
        self.vlm_analyzer = VLMSemanticAnalyzer()

    def prepare(self, request: AgentRequest, library_service: LibraryService) -> PreparedInput:
        library_root = Path(request.library_root)
        if not library_root.exists() and settings.default_library_root.exists():
            library_root = settings.default_library_root

        runtime_request = request.model_copy(update={"library_root": library_root})
        library_assets = library_service.scan_live_photos(runtime_request.library_root)
        preprocess_index_by_id = library_service.load_preprocess_index(runtime_request.library_root)
        existing_by_id = {asset.asset_id: asset for asset in library_assets}

        explicit_images, missing_images = self._collect_existing_paths(request.input_image_paths)
        explicit_videos, missing_videos = self._collect_existing_paths(request.input_video_paths)

        explicit_image_by_id = {path.stem: path for path in explicit_images}
        explicit_video_by_id = {path.stem: path for path in explicit_videos}

        merged_by_id: dict[str, LivePhotoAsset] = dict(existing_by_id)
        resolved_explicit_asset_ids: set[str] = set()
        unresolved_video_only_ids: list[str] = []

        for asset_id, image_path in explicit_image_by_id.items():
            existing = existing_by_id.get(asset_id)
            motion_path = explicit_video_by_id.get(asset_id)
            if motion_path is None and existing is not None:
                motion_path = existing.motion_path

            tags = list(existing.tags) if existing is not None else self._infer_tags(image_path)
            metadata = dict(existing.metadata) if existing is not None else {}
            metadata.update(
                {
                    "filename": image_path.name,
                    "source": "explicit_input",
                    "has_motion": str(motion_path is not None).lower(),
                }
            )
            preprocess_summary = (
                existing.preprocess_summary
                if existing is not None and existing.preprocess_summary is not None
                else preprocess_index_by_id.get(asset_id)
            )
            if preprocess_summary is None:
                preprocess_summary = self._build_explicit_preprocess_summary(
                    image_path=image_path,
                    motion_path=motion_path,
                    tags=tags,
                )
            merged_by_id[asset_id] = LivePhotoAsset(
                asset_id=asset_id,
                image_path=image_path,
                motion_path=motion_path,
                tags=tags,
                metadata=metadata,
                preprocess_summary=preprocess_summary.model_copy(update={"source": "explicit_input"}),
            )
            resolved_explicit_asset_ids.add(asset_id)

        for asset_id, video_path in explicit_video_by_id.items():
            existing = merged_by_id.get(asset_id)
            if existing is None:
                unresolved_video_only_ids.append(asset_id)
                continue

            metadata = dict(existing.metadata)
            metadata["source"] = "explicit_input"
            metadata["has_motion"] = "true"
            merged_by_id[asset_id] = existing.model_copy(
                update={
                    "motion_path": video_path,
                    "metadata": metadata,
                }
            )
            resolved_explicit_asset_ids.add(asset_id)

        merged_assets = sorted(merged_by_id.values(), key=lambda item: item.asset_id)
        merged_assets_with_summary: list[LivePhotoAsset] = []
        preprocess_index_hit_count = 0
        generated_summary_count = 0
        for asset in merged_assets:
            summary = asset.preprocess_summary
            if summary is None:
                summary = preprocess_index_by_id.get(asset.asset_id)
                if summary is not None:
                    preprocess_index_hit_count += 1
            if summary is None:
                summary = self._build_explicit_preprocess_summary(
                    image_path=asset.image_path,
                    motion_path=asset.motion_path,
                    tags=asset.tags,
                )
                generated_summary_count += 1
            merged_assets_with_summary.append(asset.model_copy(update={"preprocess_summary": summary}))
        merged_assets = merged_assets_with_summary
        valid_asset_ids = {asset.asset_id for asset in merged_assets}

        effective_selected_ids = [asset_id for asset_id in request.selected_asset_ids if asset_id in valid_asset_ids]
        for asset_id in sorted(resolved_explicit_asset_ids):
            if asset_id not in effective_selected_ids:
                effective_selected_ids.append(asset_id)

        normalized_request = runtime_request.model_copy(update={"selected_asset_ids": effective_selected_ids})
        preprocess_info = {
            "library_asset_count": len(library_assets),
            "merged_asset_count": len(merged_assets),
            "selected_asset_count": len(effective_selected_ids),
            "live_photo_asset_count": len(
                [asset for asset in merged_assets if str(asset.metadata.get("is_live_photo", "false")) == "true"]
            ),
            "motion_ready_asset_count": len([asset for asset in merged_assets if asset.motion_path is not None]),
            "explicit_image_count": len(explicit_images),
            "explicit_video_count": len(explicit_videos),
            "missing_image_paths": [str(path) for path in missing_images],
            "missing_video_paths": [str(path) for path in missing_videos],
            "unresolved_video_only_asset_ids": unresolved_video_only_ids,
            "resolved_explicit_asset_ids": sorted(resolved_explicit_asset_ids),
            "preprocess_index_hit_count": preprocess_index_hit_count,
            "generated_summary_count": generated_summary_count,
        }

        library_summary = {
            "asset_count": len(merged_assets),
            "selected_asset_count": len(effective_selected_ids),
            "live_photo_asset_count": preprocess_info["live_photo_asset_count"],
            "motion_ready_asset_count": preprocess_info["motion_ready_asset_count"],
            "preprocessed_asset_count": len([asset for asset in merged_assets if asset.preprocess_summary is not None]),
        }
        return PreparedInput(
            request=normalized_request,
            assets=merged_assets,
            library_summary=library_summary,
            preprocess_info=preprocess_info,
        )

    def _collect_existing_paths(self, paths: list[Path]) -> tuple[list[Path], list[Path]]:
        existing: list[Path] = []
        missing: list[Path] = []
        for item in paths:
            path = Path(item).expanduser().resolve()
            if path.exists() and path.is_file():
                existing.append(path)
            else:
                missing.append(path)
        return existing, missing

    def _infer_tags(self, image_path: Path) -> list[str]:
        stem = image_path.stem.lower().replace("_", " ").replace("-", " ")
        return [token for token in stem.split() if token]

    def _build_explicit_preprocess_summary(
        self,
        image_path: Path,
        motion_path: Path | None,
        tags: list[str],
    ) -> AssetPreprocessSummary:
        capture_time = datetime.fromtimestamp(image_path.stat().st_mtime, tz=timezone.utc).isoformat(timespec="seconds")
        file_size_kb = round(image_path.stat().st_size / 1024.0, 2)
        content_summary = " ".join(tags[:4]).strip() or image_path.stem
        technical_signals = {
            "image_file_size_kb": file_size_kb,
            "image_extension": image_path.suffix.lower(),
            "has_motion": motion_path is not None,
        }
        editability_signals = {"has_motion": motion_path is not None}
        if motion_path is not None:
            try:
                cover_frame = self.media_ops.locate_cover_frame(
                    video_path=motion_path,
                    image_path=image_path,
                )
                technical_signals.update(cover_frame)
                editability_signals.update(cover_frame)
            except (MediaOpsError, ValueError, TypeError):
                pass
        base_summary = AssetPreprocessSummary(
            media_format="livephoto" if motion_path is not None else "photo",
            capture_time=capture_time,
            content_tags=tags,
            content_summary=content_summary,
            technical_signals=technical_signals,
            provenance={
                "technical_producer": "explicit_input",
                "technical_version": "v1",
            },
            quality_signals={"image_file_size_kb": file_size_kb},
            editability_signals=editability_signals,
            source="explicit_input",
            updated_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
        )
        try:
            semantic_enrichment = self.vlm_analyzer.enrich_summary(
                asset=LivePhotoAsset(
                    asset_id=image_path.stem,
                    image_path=image_path,
                    motion_path=motion_path,
                    tags=tags,
                ),
                base_summary=base_summary,
            )
        except Exception:  # noqa: BLE001
            return base_summary
        return base_summary.model_copy(
            update={
                "content_tags": semantic_enrichment["content_tags"],
                "content_summary": semantic_enrichment["content_summary"],
                "semantic_signals": semantic_enrichment["semantic_signals"],
                "coarse_semantics": semantic_enrichment["coarse_semantics"],
                "provenance": semantic_enrichment["provenance"],
            }
        )
