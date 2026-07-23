#!/usr/bin/env python3
"""Test VLM semantic analysis on live photo assets.

Accepts three input forms per file:
  1. Packed single-file live photo (.jpg containing embedded mp4)
  2. A pair automatically resolved by stem: foo.jpg + foo.mp4 in the same dir
  3. Standalone video (.mp4 / .mov) with no paired image

In all cases unpacking happens in a TemporaryDirectory that is deleted
immediately after the VLM call — no intermediate files are left on disk.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
CLI_DIR = ROOT / "live_photo_transfer_and_save"
for p in (SRC, CLI_DIR):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

from live_photo_agent.foundation import LibraryService
from live_photo_agent.foundation.vlm_semantics import VLMSemanticAnalyzer
from live_photo_agent.models import AssetPreprocessSummary, LivePhotoAsset

try:
    from live_photo_cli import is_live_photo_container  # type: ignore[import]
    _CODEC_AVAILABLE = True
except ImportError:
    _CODEC_AVAILABLE = False


def _resolve_asset(path: Path) -> LivePhotoAsset:
    """Build a LivePhotoAsset from any of the three input forms."""
    path = path.expanduser().resolve()

    # Form 1: packed single-file live photo (.jpg with embedded mp4)
    if _CODEC_AVAILABLE and path.suffix.lower() in {".jpg", ".jpeg"} and is_live_photo_container(path):
        return LivePhotoAsset(
            asset_id=path.stem,
            image_path=path,
            motion_path=None,  # VLMSemanticAnalyzer will unpack via _media_bytes
            metadata={"source": "packed_live_photo"},
        )

    # Form 2: split jpg+mp4 pair — look for a sibling mp4 with the same stem
    if path.suffix.lower() in {".jpg", ".jpeg"}:
        sibling_mp4 = path.with_suffix(".mp4")
        sibling_mov = path.with_suffix(".mov")
        motion = sibling_mp4 if sibling_mp4.exists() else (sibling_mov if sibling_mov.exists() else None)
        return LivePhotoAsset(
            asset_id=path.stem,
            image_path=path,
            motion_path=motion,
            metadata={"source": "split_live_photo"},
        )

    # Form 3: standalone video — image_path points to a non-existent placeholder
    return LivePhotoAsset(
        asset_id=path.stem,
        image_path=path.with_suffix(".jpg"),  # may not exist; _media_bytes handles it
        motion_path=path,
        metadata={"source": "video_only"},
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "paths",
        nargs="+",
        help="Live photo files or video files to analyze (packed .jpg, split .jpg/.mp4, or bare .mp4)",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    service = LibraryService()
    analyzer = VLMSemanticAnalyzer(media_ops=service.vlm_analyzer.media_ops)

    for raw in args.paths:
        path = Path(raw).expanduser().resolve()
        if not path.exists():
            print(f"[skip] {path} (not found)\n")
            continue

        asset = _resolve_asset(path)
        base_summary = AssetPreprocessSummary(
            media_format="livephoto" if (asset.motion_path or path.suffix.lower() in {".mp4", ".mov"}) else "photo"
        )
        result = analyzer.enrich_summary(asset=asset, base_summary=base_summary)
        print(json.dumps({"input": str(path), "asset_id": asset.asset_id, **result}, ensure_ascii=False, indent=2))
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
