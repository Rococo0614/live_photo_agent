from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from ..config import settings
from ..models import AssetPreprocessSummary, LivePhotoAsset

JPEG_EOI = b"\xff\xd9"
FTYP_MAGIC = b"ftyp"


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".heic", ".png"}
MOTION_EXTENSIONS = {".mov", ".mp4"}


class LibraryService:
    def __init__(self) -> None:
        self.catalog_file = settings.album_catalog_file
        self.operation_log_file = settings.album_operation_log_file
        self.preprocess_index_file = settings.album_preprocess_index_file

    def scan_live_photos(self, library_root: Path) -> list[LivePhotoAsset]:
        if not library_root.exists():
            return []

        library_root = library_root.resolve()

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
            motion_path = motion_by_stem.get(image_path.stem)
            embedded_live_photo = self._is_embedded_live_photo(image_path)
            motion_source = "sidecar" if motion_path is not None else "none"
            if motion_path is None and embedded_live_photo:
                extracted_motion_path = self._ensure_embedded_motion_path(image_path)
                if extracted_motion_path is not None:
                    motion_path = extracted_motion_path
                    motion_source = "embedded_cache"
            is_live_photo = (motion_path is not None) or embedded_live_photo
            album_name = self._album_name(image_path, library_root)
            tags = self._infer_tags(image_path)
            preprocess_summary = self._build_preprocess_summary(
                image_path=image_path,
                motion_path=motion_path,
                is_live_photo=is_live_photo,
                tags=tags,
                source="scan",
            )
            assets.append(
                LivePhotoAsset(
                    asset_id=image_path.stem,
                    image_path=image_path,
                    motion_path=motion_path,
                    tags=tags,
                    metadata={
                        "filename": image_path.name,
                        "has_motion": str(motion_path is not None).lower(),
                        "is_live_photo": str(is_live_photo).lower(),
                        "embedded_live_photo": str(embedded_live_photo).lower(),
                        "media_type": "live_photo" if is_live_photo else "image",
                        "album_name": album_name,
                        "motion_source": motion_source,
                    },
                    preprocess_summary=preprocess_summary,
                )
            )

        self._sync_album_catalog(library_root, assets)
        self._sync_preprocess_index(library_root, assets)

        return assets

    def load_preprocess_index(self, library_root: Path) -> dict[str, AssetPreprocessSummary]:
        if not self.preprocess_index_file.exists():
            return {}

        target_root = str(library_root.resolve())
        summaries: dict[str, AssetPreprocessSummary] = {}
        for raw_line in self.preprocess_index_file.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict):
                continue
            if str(row.get("library_root", "")) != target_root:
                continue
            asset_id = str(row.get("asset_id", "")).strip()
            if not asset_id:
                continue
            summary_raw = row.get("summary", {})
            if not isinstance(summary_raw, dict):
                continue
            try:
                summary = AssetPreprocessSummary.model_validate(summary_raw)
            except Exception:  # noqa: BLE001
                continue
            summaries[asset_id] = summary
        return summaries

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

    def _is_embedded_live_photo(self, image_path: Path) -> bool:
        if image_path.suffix.lower() not in {".jpg", ".jpeg"}:
            return False

        try:
            data = image_path.read_bytes()
            return self._locate_embedded_segments(data) is not None
        except Exception:  # noqa: BLE001
            return False

    def _ensure_embedded_motion_path(self, image_path: Path) -> Path | None:
        try:
            data = image_path.read_bytes()
            segments = self._locate_embedded_segments(data)
            if segments is None:
                return None
            _, mp4_start = segments
            mp4_bytes = data[mp4_start:]
            if len(mp4_bytes) < 16:
                return None

            image_hash = hashlib.sha1(str(image_path.resolve()).encode("utf-8")).hexdigest()[:12]
            cache_root = settings.workspace_dir / ".live_photo_motion_cache" / image_hash
            cache_root.mkdir(parents=True, exist_ok=True)
            motion_path = cache_root / f"{image_path.stem}.mp4"

            source_mtime_ns = image_path.stat().st_mtime_ns
            if motion_path.exists() and motion_path.stat().st_mtime_ns >= source_mtime_ns:
                return motion_path

            motion_path.write_bytes(mp4_bytes)
            return motion_path
        except Exception:  # noqa: BLE001
            return None

    def _locate_embedded_segments(self, data: bytes) -> tuple[int, int] | None:
        if len(data) < 16:
            return None
        if data[:2] != b"\xff\xd8":
            return None

        ftyp_offset = data.find(FTYP_MAGIC)
        if ftyp_offset == -1:
            return None

        candidate_start = max(ftyp_offset - 4, 0)
        mp4_start = ftyp_offset
        if candidate_start + 8 <= len(data) and data[candidate_start + 4 : candidate_start + 8] == FTYP_MAGIC:
            box_size = int.from_bytes(data[candidate_start : candidate_start + 4], byteorder="big", signed=False)
            if box_size >= 8:
                mp4_start = candidate_start

        if mp4_start <= 2:
            return None

        eoi_index = data.rfind(JPEG_EOI, 0, mp4_start)
        if eoi_index == -1:
            return None
        jpeg_end = eoi_index + len(JPEG_EOI)
        if mp4_start < jpeg_end:
            return None
        if len(data) - mp4_start < 16:
            return None
        return jpeg_end, mp4_start

    def _album_name(self, image_path: Path, library_root: Path) -> str:
        try:
            parent = image_path.parent.resolve().relative_to(library_root)
        except ValueError:
            return image_path.parent.name
        parent_text = parent.as_posix()
        return parent_text if parent_text else "."

    def _sync_album_catalog(self, library_root: Path, assets: list[LivePhotoAsset]) -> None:
        state = self._load_catalog_state()
        libraries = state.setdefault("libraries", {})
        library_key = str(library_root)
        bucket = libraries.setdefault(library_key, {"assets": {}, "last_scan_ts": ""})

        existing_assets = bucket.get("assets", {})
        if not isinstance(existing_assets, dict):
            existing_assets = {}

        current_records: dict[str, dict[str, object]] = {}
        operations: list[dict[str, object]] = []
        now_ts = self._utc_now_iso()

        for asset in assets:
            key = str(asset.image_path)
            record = {
                "asset_id": asset.asset_id,
                "album_name": str(asset.metadata.get("album_name", ".")),
                "image_path": str(asset.image_path),
                "motion_path": str(asset.motion_path) if asset.motion_path else None,
                "is_live_photo": str(asset.metadata.get("is_live_photo", "false")).lower() == "true",
                "embedded_live_photo": str(asset.metadata.get("embedded_live_photo", "false")).lower() == "true",
                "last_seen_ts": now_ts,
            }
            current_records[key] = record

            if key not in existing_assets:
                operations.append({"op": "added", "library_root": library_key, **record})
            elif existing_assets.get(key) != record:
                operations.append({"op": "updated", "library_root": library_key, **record})

        for removed_key, removed_record in existing_assets.items():
            if removed_key in current_records:
                continue
            operations.append(
                {
                    "op": "deleted",
                    "library_root": library_key,
                    "image_path": removed_key,
                    "asset_id": str(removed_record.get("asset_id", "")),
                    "album_name": str(removed_record.get("album_name", ".")),
                    "deleted_ts": now_ts,
                }
            )

        bucket["assets"] = current_records
        bucket["last_scan_ts"] = now_ts
        bucket["stats"] = self._build_bucket_stats(current_records)
        libraries[library_key] = bucket
        state["version"] = 1

        self.catalog_file.parent.mkdir(parents=True, exist_ok=True)
        self.catalog_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")

        if operations:
            self.operation_log_file.parent.mkdir(parents=True, exist_ok=True)
            with self.operation_log_file.open("a", encoding="utf-8") as fp:
                for row in operations:
                    fp.write(json.dumps({"ts": now_ts, **row}, ensure_ascii=False) + "\n")

    def _sync_preprocess_index(self, library_root: Path, assets: list[LivePhotoAsset]) -> None:
        target_root = str(library_root.resolve())
        retained_rows: list[str] = []
        if self.preprocess_index_file.exists():
            for raw_line in self.preprocess_index_file.read_text(encoding="utf-8").splitlines():
                line = raw_line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if not isinstance(row, dict):
                    continue
                if str(row.get("library_root", "")) == target_root:
                    continue
                retained_rows.append(json.dumps(row, ensure_ascii=False))

        now_ts = self._utc_now_iso()
        for asset in assets:
            summary = asset.preprocess_summary or self._build_preprocess_summary(
                image_path=asset.image_path,
                motion_path=asset.motion_path,
                is_live_photo=str(asset.metadata.get("is_live_photo", "false")) == "true",
                tags=asset.tags,
                source="scan",
            )
            row = {
                "library_root": target_root,
                "asset_id": asset.asset_id,
                "image_path": str(asset.image_path),
                "motion_path": str(asset.motion_path) if asset.motion_path else None,
                "summary": summary.model_copy(update={"updated_at": now_ts}).model_dump(mode="json"),
            }
            retained_rows.append(json.dumps(row, ensure_ascii=False))

        self.preprocess_index_file.parent.mkdir(parents=True, exist_ok=True)
        output = "\n".join(retained_rows)
        if output:
            output += "\n"
        self.preprocess_index_file.write_text(output, encoding="utf-8")

    def _load_catalog_state(self) -> dict[str, object]:
        if not self.catalog_file.exists():
            return {"version": 1, "libraries": {}}

        content = self.catalog_file.read_text(encoding="utf-8").strip()
        if not content:
            return {"version": 1, "libraries": {}}

        try:
            parsed = json.loads(content)
        except json.JSONDecodeError:
            return {"version": 1, "libraries": {}}

        if not isinstance(parsed, dict):
            return {"version": 1, "libraries": {}}
        libraries = parsed.get("libraries")
        if not isinstance(libraries, dict):
            parsed["libraries"] = {}
        return parsed

    def _utc_now_iso(self) -> str:
        return datetime.now(timezone.utc).isoformat(timespec="seconds")

    def _build_bucket_stats(self, records: dict[str, dict[str, object]]) -> dict[str, int]:
        total_assets = len(records)
        live_photo_assets = 0
        embedded_live_photo_assets = 0
        motion_ready_assets = 0

        for record in records.values():
            if bool(record.get("is_live_photo", False)):
                live_photo_assets += 1
            if bool(record.get("embedded_live_photo", False)):
                embedded_live_photo_assets += 1
            if record.get("motion_path"):
                motion_ready_assets += 1

        return {
            "total_assets": total_assets,
            "live_photo_assets": live_photo_assets,
            "embedded_live_photo_assets": embedded_live_photo_assets,
            "motion_ready_assets": motion_ready_assets,
        }

    def _build_preprocess_summary(
        self,
        image_path: Path,
        motion_path: Path | None,
        is_live_photo: bool,
        tags: list[str],
        source: str,
    ) -> AssetPreprocessSummary:
        capture_time = datetime.fromtimestamp(image_path.stat().st_mtime, tz=timezone.utc).isoformat(timespec="seconds")
        file_size_kb = round(image_path.stat().st_size / 1024.0, 2)
        content_summary = " ".join(tags[:4]).strip() or image_path.stem
        return AssetPreprocessSummary(
            media_format="livephoto" if is_live_photo else "photo",
            capture_time=capture_time,
            content_tags=tags,
            content_summary=content_summary,
            quality_signals={
                "image_file_size_kb": file_size_kb,
            },
            editability_signals={
                "has_motion": motion_path is not None,
            },
            source=source,
            updated_at=self._utc_now_iso(),
        )
