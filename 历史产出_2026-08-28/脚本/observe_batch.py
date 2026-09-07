#!/usr/bin/env python3
"""批量观察多个笔记的前 N 个 Live 视频。

用法:
    python observe_batch.py <note_id> [top_n=3]
或批量:
    python observe_batch.py --all
"""
import sys
import subprocess
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
SCRIPT = BASE / "脚本/observe_video.py"

# 预设的 3 个案例
CASES = [
    ("6a2687100000000007029cc8", "OPPO拼贴"),
    ("69fc0daa000000002301c10d", "华为3D运镜"),
    ("69a6cd5c0000000023039d81", "Ins穿搭"),
]

def run_one(note_id: str, live_idx: int):
    print(f"\n{'#'*80}")
    print(f"# {note_id}  live_{live_idx:02d}")
    print(f"{'#'*80}")
    subprocess.run(
        ["python", str(SCRIPT), note_id, str(live_idx)],
        check=False,
    )


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--all":
        top_n = 3
        for note_id, label in CASES:
            note_dir = BASE / "数据/xhs_notes" / note_id / "lives"
            if not note_dir.exists():
                print(f"⚠️ 跳过 {label}（数据目录不存在）")
                continue
            live_files = sorted(note_dir.glob("live_*.mp4"))
            for i in range(min(top_n, len(live_files))):
                run_one(note_id, i)
    else:
        note_id = sys.argv[1]
        top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 3
        for i in range(top_n):
            run_one(note_id, i)
