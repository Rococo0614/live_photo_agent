from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .foundation import LibraryService, OfflinePreprocessIndexer


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Build offline preprocess index JSONL for a library root.")
    parser.add_argument("--library-root", type=Path, required=True, help="Library root path")
    parser.add_argument("--force", action="store_true", help="Force rebuild all asset summaries")
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print report as one-line JSON for automation",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    try:
        library_root = args.library_root.expanduser().resolve()
        if not library_root.exists() or not library_root.is_dir():
            raise FileNotFoundError(f"library_root_not_found: {library_root}")

        indexer = OfflinePreprocessIndexer(library_service=LibraryService())
        report = indexer.build(library_root=library_root, force_rebuild=bool(args.force))
        if args.json:
            print(json.dumps(report.to_dict(), ensure_ascii=False))
        else:
            print(f"library_root: {report.library_root}")
            print(f"total_assets: {report.total_assets}")
            print(f"rebuilt_assets: {report.rebuilt_assets}")
            print(f"reused_assets: {report.reused_assets}")
            print(f"failed_assets: {report.failed_assets}")
        return 0
    except Exception as exc:  # noqa: BLE001
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
