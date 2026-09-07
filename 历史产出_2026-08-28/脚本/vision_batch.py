#!/usr/bin/env python3
"""
批量视觉理解（v2 简化版）：
- 读 note.json，对前 N 张 Live（默认 3）逐张跑本地视觉模型
- 只让模型做"客观帧描述"（不判断镜头运动 —— 本地模型能力盲区，由 Claude/人工在下游解决）
- 每个 Live 均匀采 3 帧，逐帧独立调 API（多图输入不稳定）
- 结果写回 note.json 的 media[i].vision

用法：
    python3 vision_batch.py <note_id>              # 前 3 张 Live，每张 3 帧
    python3 vision_batch.py <note_id> --limit 5    # 扩展到前 5 张（当帖子明确"多个玩法"）
    python3 vision_batch.py <note_id> --live 1 3 5 # 手动指定分析哪些 Live
"""
import base64
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

WORK_ROOT = Path(__file__).resolve().parent.parent / "数据/xhs_notes"
OLLAMA_URL = "http://localhost:11434/api/generate"
MODEL = "qwen2.5vl:7b"

# 简化后的 prompt：只做客观描述，不判断镜头运动
PROMPT = """你是视觉观察员。这是一张 Live Photo 视频中的静态帧。只做客观描述，禁用主观形容词（如"精美/绝美/生动"）。按下方 5 项分条回答，共不超过 200 字。

【1. 画面主体】主要是什么（人/物/景）？主体位置？
【2. 构图形式】单幅 / 拼贴 / 分屏 / 宫格 / 画中画？若是拼贴请说明布局。
【3. 可见文字】逐字读出所有文字（含手写/中英文/水印/字幕）。若无写"无"。
【4. 技术痕迹】逐条判断有/无：抠图叠加 / 多图拼合 / 贴纸图形 / 涂鸦手写 / 边框 / AI风格化痕迹。
【5. 色彩与风格】主色调？滤镜倾向（胶片/CCD/复古/冷调/暖调/高饱和/褪色/自然）？
"""

# 触发扩展抓取的关键词（帖子文案里出现这些词 = 该帖有多种玩法）
MULTI_STYLE_HINTS = [
    r"\d+\s*个玩法", r"\d+\s*种玩法", r"\d+\s*款", r"\d+\s*[条种个]创意",
    r"合集", r"多种", r"全都是", r"逐个", r"依次",
]


def describe_frame(image_path: Path) -> tuple[str, float]:
    img_b64 = base64.b64encode(image_path.read_bytes()).decode("utf-8")
    payload = {
        "model": MODEL,
        "prompt": PROMPT,
        "images": [img_b64],
        "stream": False,
        "options": {"temperature": 0.2, "num_predict": 300},
    }
    req = urllib.request.Request(
        OLLAMA_URL, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data.get("response", "").strip(), time.time() - t0


def sample_frames(frames_dir: Path, n: int = 3) -> list[Path]:
    all_frames = sorted(frames_dir.glob("frame_*.jpg"))
    if len(all_frames) <= n:
        return all_frames
    if n == 1:
        return [all_frames[len(all_frames) // 2]]
    step = (len(all_frames) - 1) / (n - 1)
    return [all_frames[int(i * step)] for i in range(n)]


def detect_multi_style(desc: str) -> bool:
    for pat in MULTI_STYLE_HINTS:
        if re.search(pat, desc):
            return True
    return False


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    note_id = sys.argv[1]
    args = sys.argv[2:]

    # 默认前 3 张 Live
    limit = 3
    live_indices = None
    if "--limit" in args:
        i = args.index("--limit")
        limit = int(args[i + 1])
    if "--live" in args:
        i = args.index("--live")
        # 后续参数直到下一个 -- 都算索引
        live_indices = []
        j = i + 1
        while j < len(args) and not args[j].startswith("--"):
            live_indices.append(int(args[j]))
            j += 1

    note_json = WORK_ROOT / note_id / "note.json"
    if not note_json.exists():
        print(f"未找到 {note_json}，请先跑 fetch_xhs.py")
        sys.exit(1)

    all_data = json.loads(note_json.read_text(encoding="utf-8"))
    media = all_data["media"]
    desc_text = all_data.get("meta", {}).get("desc", "")

    # 决定分析哪些 Live
    if live_indices is not None:
        targets = [(i, m) for i, m in enumerate(media) if i in live_indices and m.get("is_live") and m.get("frames_dir")]
        print(f"手动指定分析: {live_indices}")
    else:
        all_lives = [(i, m) for i, m in enumerate(media) if m.get("is_live") and m.get("frames_dir")]
        # 检查文案是否触发多样性扩展
        if detect_multi_style(desc_text) and limit == 3:
            print(f"文案检测到'多玩法'关键词 → 扩展分析全部 {len(all_lives)} 张 Live")
            targets = all_lives
        else:
            targets = all_lives[:limit]
            print(f"默认策略：分析前 {len(targets)} 张 Live（共 {len(all_lives)} 张，可用 --limit 扩展）")

    print(f"标题: {all_data['meta']['title']}")
    print(f"作者: {all_data['meta']['author']['nickname']}\n")

    t_total = time.time()

    for order, (idx, m) in enumerate(targets, 1):
        frames_dir = Path(m["frames_dir"])
        chosen = sample_frames(frames_dir, n=3)
        print(f">>> Live [{idx:02d}]  ({order}/{len(targets)})  采样 {len(chosen)} 帧")

        vision_records = []
        for j, fp in enumerate(chosen, 1):
            try:
                desc, dt = describe_frame(fp)
            except Exception as e:
                desc, dt = f"[ERROR] {e}", 0
            print(f"    [{j}/{len(chosen)}] {fp.name} ({dt:.1f}s)")
            vision_records.append({"frame": fp.name, "desc": desc, "time_s": round(dt, 1)})

        m["vision"] = {
            "model": MODEL,
            "sampled_frames": len(chosen),
            "records": vision_records,
        }

    # 一次性写回
    note_json.write_text(json.dumps(all_data, ensure_ascii=False, indent=2), encoding="utf-8")

    total = time.time() - t_total
    print(f"\n完成，总耗时 {total:.1f}s ({total/60:.1f} 分钟)")
    print(f"结果已写回: {note_json}")

    # 打印摘要（供复制给 Claude 做洞察分析）
    print("\n" + "=" * 60)
    print("=== FRAME_DESCRIPTIONS_START ===")
    for idx, m in enumerate(all_data["media"]):
        v = m.get("vision")
        if v:
            print(f"\n--- Live [{idx:02d}] ---")
            for r in v["records"]:
                print(f"[{r['frame']}]")
                print(r["desc"])
    print("\n=== FRAME_DESCRIPTIONS_END ===")


if __name__ == "__main__":
    main()
