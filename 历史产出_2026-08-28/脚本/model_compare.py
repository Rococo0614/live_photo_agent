#!/usr/bin/env python3
"""
三模型能力对比测试
- 对同一段 Live 视频的 5 帧代表性帧
- 用完全相同的 prompt 分别问 3 个模型
- 输出对比矩阵
"""
import base64
import json
import sys
import time
import urllib.request
from pathlib import Path

WORK_ROOT = Path(__file__).resolve().parent.parent / "数据/xhs_notes"
OLLAMA_URL = "http://localhost:11434/api/generate"

# 参与对比的模型（顺序=下载顺序）
MODELS = [
    "qwen2.5vl:7b",         # 当前基线
    "qwen3-vl:8b",          # 阿里最新一代
    "minicpm-v4.6:latest",  # 面壁最新，视频专项
]

# ==== 统一 prompt（同时问镜头 + 内容全维度）====
PROMPT_MULTI = """你是视觉观察员。以下是同一段 Live Photo 视频的 5 张时间顺序帧（0%/25%/50%/75%/100% 时间点）。请只做客观描述，禁用主观形容词。按下方 7 项分条回答，共不超过 500 字。

【1. 画面主体与构图】主要是什么（人/物/景）？构图形式（拼贴/分屏/宫格/单幅/其他）？主体位置？

【2. 可见文字】逐字读出所有文字（含手写/中英文/水印/字幕）。若无写"无"。

【3. 技术痕迹】逐条判断有/无：抠图叠加/多图拼合/贴纸图形/涂鸦手写/边框/AI风格化痕迹。

【4. 镜头运动/时序变化】（对比 5 帧，关键）
5 帧之间发生了什么变化？请具体判断以下类型：
  - 主体动、镜头不动（普通拍摄）
  - 主体静止、镜头环绕（子弹时间/环绕运镜）
  - 主体静止、镜头推拉（推近/拉远运镜）
  - 视角轻微视差（3D 空间照片/景深）
  - 画面切换/转场
  - 元素飘动/悬浮（贴纸/文字/装饰）
  - 无明显变化（纯静态）
说明你的判断依据（例如"背景建筑物角度改变但主体人物姿态不变"）。

【5. 素材门槛】复刻这一玩法大约需要几张什么样的照片？是否需要特殊拍摄（如运镜/固定机位/多角度）？是否需要专门道具？

【6. 视觉钩子】是否有 1 秒内抓眼球的视觉元素？具体是什么？可推广到什么其他题材场景？若无写"无"。

【7. 整体玩法总结】用一句话概括这个 Live 玩法（例如"多张同主题照片拼贴 + 打字机文字动效"或"人物定格后镜头环绕 360 度"）。
"""


def call_model(model: str, images_b64: list[str], prompt: str, timeout: int = 300) -> tuple[str, float]:
    t0 = time.time()
    payload = {
        "model": model,
        "prompt": prompt,
        "images": images_b64,
        "stream": False,
        "options": {"temperature": 0.2, "num_predict": 800},
    }
    req = urllib.request.Request(
        OLLAMA_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return data.get("response", "").strip(), time.time() - t0
    except Exception as e:
        return f"[ERROR] {e}", time.time() - t0


def load_b64(path: Path) -> str:
    return base64.b64encode(path.read_bytes()).decode("utf-8")


def sample_frames(frames_dir: Path, n: int = 5) -> list[Path]:
    all_frames = sorted(frames_dir.glob("frame_*.jpg"))
    if len(all_frames) <= n:
        return all_frames
    step = (len(all_frames) - 1) / (n - 1)
    return [all_frames[int(i * step)] for i in range(n)]


def main():
    # 命令行参数：note_id live_idx
    if len(sys.argv) < 3:
        # 默认华为 3D live 那条的 live_04
        note_id = "69fc0daa000000002301c10d"
        live_idx = 4
        print(f"未指定参数，使用默认: {note_id} live_{live_idx:02d}")
    else:
        note_id = sys.argv[1]
        live_idx = int(sys.argv[2])

    frames_dir = WORK_ROOT / note_id / "lives" / f"live_{live_idx:02d}_frames"
    if not frames_dir.exists():
        print(f"目录不存在: {frames_dir}")
        sys.exit(1)

    frames = sample_frames(frames_dir, n=5)
    print(f"目标: {frames_dir}")
    print(f"采样帧: {[f.name for f in frames]}\n")

    imgs_b64 = [load_b64(f) for f in frames]

    results = []
    for model in MODELS:
        print("=" * 60)
        print(f"【模型】{model}")
        print("=" * 60)
        resp, dt = call_model(model, imgs_b64, PROMPT_MULTI)
        print(f"耗时: {dt:.1f}s   输出 {len(resp)} 字")
        print(f"\n{resp}\n")
        results.append({"model": model, "elapsed_s": round(dt, 1), "output_len": len(resp), "text": resp})

    # 总结表
    print("\n" + "=" * 60)
    print("=== 对比总结 ===")
    print(f"{'模型':<25} {'耗时':>8} {'字数':>8}")
    for r in results:
        print(f"{r['model']:<25} {r['elapsed_s']:>7.1f}s {r['output_len']:>8}")

    # 落盘（JSON，纯文本，DLP 安全）
    out_path = WORK_ROOT / f"_compare_{note_id}_live{live_idx:02d}.json"
    out_path.write_text(json.dumps({
        "note_id": note_id,
        "live_idx": live_idx,
        "frames": [f.name for f in frames],
        "results": results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n结果已保存: {out_path}")


if __name__ == "__main__":
    main()
