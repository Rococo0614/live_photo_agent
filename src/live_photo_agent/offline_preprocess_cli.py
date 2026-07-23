from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .foundation import LibraryService, OfflinePreprocessIndexer


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build/update offline preprocess index JSONL for a library root.",
    )
    subparsers = parser.add_subparsers(dest="command")

    # --- build subcommand ---
    build_cmd = subparsers.add_parser("build", help="Scan library and build/update the preprocess index.")
    build_cmd.add_argument("--library-root", type=Path, required=True, help="Library root path")
    build_cmd.add_argument("--force", action="store_true", help="Force rebuild all asset summaries")
    build_cmd.add_argument("--json", action="store_true", help="Print report as one-line JSON")

    # --- enrich-semantics subcommand ---
    enrich_cmd = subparsers.add_parser(
        "enrich-semantics",
        help="Walk existing JSONL rows and fill in VLM semantic signals where missing.",
    )
    enrich_cmd.add_argument(
        "--force",
        action="store_true",
        help="Re-run VLM even on rows that already have semantic_signals.",
    )
    enrich_cmd.add_argument("--json", action="store_true", help="Print report as one-line JSON")

    # Legacy flat flags (no subcommand): keep backward-compat with existing callers.
    parser.add_argument("--library-root", type=Path, help="Library root path")
    parser.add_argument("--force", action="store_true", help="Force rebuild")
    parser.add_argument("--json", action="store_true", help="Print report as one-line JSON")
    parser.add_argument(
        "--enrich-semantics",
        action="store_true",
        help="Walk existing index and fill in VLM semantic signals (alias for 'enrich-semantics' subcommand)",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    indexer = OfflinePreprocessIndexer(library_service=LibraryService())

    try:
        command = args.command
        if command is None:
            command = "enrich-semantics" if getattr(args, "enrich_semantics", False) else "build"

        if command == "enrich-semantics":
            result = indexer.enrich_semantics_pass(force=bool(args.force))
            if args.json:
                print(json.dumps(result, ensure_ascii=False))
            else:
                print(f"total:    {result['total']}")
                print(f"enriched: {result['enriched']}")
                print(f"skipped:  {result['skipped']}")
                print(f"failed:   {result['failed']}")
            return 0

        # command == "build"
        library_root_arg = getattr(args, "library_root", None)
        if library_root_arg is None:
            print("error: --library-root is required for 'build'", file=sys.stderr)
            return 1
        library_root = library_root_arg.expanduser().resolve()
        if not library_root.exists() or not library_root.is_dir():
            raise FileNotFoundError(f"library_root_not_found: {library_root}")

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
