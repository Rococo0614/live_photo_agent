#!/usr/bin/env python3
"""Download ~100 short CC0 videos from Pixabay as the test asset library.

Covers diverse scenes: pets, nature, food, city, sports, people, etc.
Downloads small resolution (960x540) videos, 5-15s each, ~500KB-2MB each.

Usage:
    export PIXABAY_API_KEY="your-key-here"
    python scripts/download_test_videos.py
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.request
import urllib.parse
from pathlib import Path

API_KEY = os.environ.get("PIXABAY_API_KEY", "")
OUTPUT_DIR = Path("data/live_photo")
TARGET_COUNT = 100

QUERIES = [
    ("cat", 8), ("dog", 8), ("kitten", 4), ("puppy", 4),
    ("nature", 5), ("forest", 4), ("ocean", 4), ("mountain", 4), ("sunset", 4),
    ("flower", 4), ("beach", 4), ("waterfall", 3),
    ("food", 5), ("coffee", 3), ("fruit", 3), ("cooking", 3),
    ("city", 5), ("street", 4), ("traffic", 3), ("building", 3),
    ("people", 4), ("woman", 3), ("man", 3), ("child", 3),
    ("sports", 4), ("running", 3), ("cycling", 3), ("swimming", 2),
    ("bird", 4), ("horse", 3), ("fish", 3), ("butterfly", 2),
    ("rain", 3), ("snow", 3), ("clouds", 3), ("river", 3),
    ("garden", 3), ("tree", 3), ("sky", 3), ("night", 3),
    ("music", 2), ("dance", 2), ("art", 2), ("book", 2),
]


def search_videos(query: str, per_page: int = 5, page: int = 1) -> list[dict]:
    params = {
        "key": API_KEY,
        "q": query,
        "per_page": str(per_page),
        "page": str(page),
        "video_type": "small",
        "order": "popular",
    }
    url = f"https://pixabay.com/api/videos/?{urllib.parse.urlencode(params)}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return data.get("hits", [])
    except Exception as e:
        print(f"  [search] failed for '{query}': {e}")
        return []


def download_video(url: str, dest: Path, timeout: int = 60) -> bool:
    if dest.exists() and dest.stat().st_size > 1000:
        return True
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = resp.read()
        if len(data) < 1000:
            print(f"  [download] too small: {dest.name} ({len(data)} bytes)")
            return False
        dest.write_bytes(data)
        return True
    except Exception as e:
        print(f"  [download] failed {dest.name}: {e}")
        if dest.exists():
            dest.unlink()
        return False


def main() -> int:
    if not API_KEY:
        print("ERROR: set PIXABAY_API_KEY env var first")
        return 2

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    downloaded = 0
    seen_ids: set[int] = set()
    manifest: list[dict] = []

    for query, count in QUERIES:
        if downloaded >= TARGET_COUNT:
            break
        print(f"\n[query] '{query}' (want {count})")
        per_page = min(count, 5)
        hits = search_videos(query, per_page=per_page)
        if not hits:
            continue

        for hit in hits:
            if downloaded >= TARGET_COUNT:
                break
            vid_id = hit.get("id")
            if vid_id in seen_ids:
                continue
            seen_ids.add(vid_id)

            videos = hit.get("videos", {})
            small = videos.get("small") or videos.get("medium") or videos.get("tiny")
            if not small:
                continue

            url = small.get("url")
            if not url:
                continue

            duration = hit.get("duration", 0)
            tags = hit.get("tags", query)
            filename = f"{query}_{vid_id}.mp4"
            dest = OUTPUT_DIR / filename

            print(f"  [{downloaded+1}/{TARGET_COUNT}] {filename} ({duration}s, {tags[:40]})", end="... ", flush=True)
            if download_video(url, dest):
                downloaded += 1
                manifest.append({
                    "filename": filename,
                    "asset_id": dest.stem,
                    "query": query,
                    "tags": tags,
                    "duration_s": duration,
                    "width": small.get("width"),
                    "height": small.get("height"),
                    "source_url": url,
                    "pixabay_id": vid_id,
                })
                print(f"OK ({dest.stat().st_size // 1024}KB)")
            else:
                print("SKIP")
            time.sleep(0.3)

    manifest_path = OUTPUT_DIR.parent / "download_manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"\n=== Done ===")
    print(f"Downloaded: {downloaded}/{TARGET_COUNT}")
    print(f"Output dir: {OUTPUT_DIR.resolve()}")
    print(f"Manifest:   {manifest_path.resolve()}")
    total_size = sum(f.stat().st_size for f in OUTPUT_DIR.glob("*.mp4"))
    print(f"Total size: {total_size / (1024*1024):.1f} MB")

    return 0 if downloaded > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
