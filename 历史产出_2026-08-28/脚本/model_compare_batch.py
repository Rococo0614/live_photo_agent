#!/usr/bin/env python3
"""
多模型 × 多案例 视觉理解能力对比测试
- 自动检测已安装的模型（跳过未装的）
- 对 4 个测试案例（每个选 1 个代表性 Live）跑同题
- 输出对比矩阵 + 结构化 JSON
"""
import base64
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

WORK_ROOT = Path(__file__).resolve().parent.parent / "数据/xhs_notes"
OUT_DIR = Path(__file__).resolve().parent.parent / "分析卡片"
OUT_DIR.mkdir(parents=True, exist_ok=True)
OLLAMA_URL = "http://localhost:11434/api/generate"

# 候选模型（脚本会自动过滤未装的）
CANDIDATE_MODELS = [
    "qwen2.5vl:7b",
    "qwen3-vl:8b",
    "minicpm-v4.6:latest",
]

# 测试案例：note_id, 品牌标签, 代表性 live 索引（选变化最丰富的那个）
TEST_CASES = [
    ("6a2687100000000007029cc8", "OPPO 拼贴", 5),      # 12 张 Live，选中间的
    ("69fc0daa000000002301c10d", "华为 3D 运镜", 4),   # 9 张 Live，live_04 已确认有 3D 视差
    ("69a6cd5c0000000023039d81", "Ins 穿搭", 1),       # 5 张 Live，选 live_01
    ("683aaed300000000120018b4", "vivo 希区柯克", 0),  # 6 张 Live，选 live_00
]

PROMPT = """你是视觉观察员。以下是同一段 Live Photo 视频的 5 张时间顺序帧（0%/25%/50%/75%/100% 时间点）。请只做客观描述，禁用主观形容词。按下方 7 项分条回答，共不超过 500 字。

【1. 画面主体与构图】主要是什么（人/物/景）？构图形式（拼贴/分屏/宫格/单幅/其他）？主体位置？

【2. 可见文字】逐字读出所有文字（含手写/中英文/水印/字幕）。若无写"无"。

【3. 技术痕迹】逐条判断有/无：抠图叠加/多图拼合/贴纸图形/涂鸦手写/边框/AI风格化痕迹。

【4. 镜头运动/时序变化】（对比 5 帧，关键）
5 帧之间发生了什么变化？请具体判断以下类型：
  - 主体动、镜头不动（普通拍摄）
  - 主体静止、镜头环绕（子弹时间/环绕运镜）
  - 主体静止、镜头推拉（推近/拉远运镜，可能有希区柯克变焦）
  - 视角轻微视差（3D 空间照片/景深）
  - 画面切换/转场
  - 元素飘动/悬浮（贴纸/文字/装饰）
  - 无明显变化（纯静态）
说明你的判断依据（例如"背景建筑物角度改变但主体人物姿态不变"）。

【5. 素材门槛】复刻这一玩法大约需要几张什么样的照片？是否需要特殊拍摄（如运镜/固定机位/多角度）？是否需要专门道具？

【6. 视觉钩子】是否有 1 秒内抓眼球的视觉元素？具体是什么？可推广到什么其他题材场景？若无写"无"。

【7. 整体玩法总结】用一句话概括这个 Live 玩法（例如"多张同主题照片拼贴 + 打字机文字动效"或"人物定格后镜头环绕 360 度"）。
"""


def get_installed_models() -> set[str]:
    """从 ollama list 读取已安装模型"""
    try:
        r = subprocess.run(["ollama", "list"], capture_output=True, text=True, timeout=10)
        names = set()
        for line in r.stdout.splitlines()[1:]:
            parts = line.split()
            if parts:
                names.add(parts[0])
        return names
    except Exception:
        return set()


def call_model(model: str, images_b64: list[str], prompt: str) -> tuple[str, float]:
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
        with urllib.request.urlopen(req, timeout=600) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return data.get("response", "").strip(), time.time() - t0
    except Exception as e:
        return f"[ERROR] {e}", time.time() - t0


def sample_frames(frames_dir: Path, n: int = 5) -> list[Path]:
    all_frames = sorted(frames_dir.glob("frame_*.jpg"))
    if len(all_frames) <= n:
        return all_frames
    step = (len(all_frames) - 1) / (n - 1)
    return [all_frames[int(i * step)] for i in range(n)]


def main():
    installed = get_installed_models()
    active_models = [m for m in CANDIDATE_MODELS if m in installed]
    print(f"已安装模型: {installed}")
    print(f"参与测试的模型: {active_models}\n")

    if not active_models:
        print("没有可用模型")
        return

    all_results = []

    for note_id, brand, live_idx in TEST_CASES:
        frames_dir = WORK_ROOT / note_id / "lives" / f"live_{live_idx:02d}_frames"
        if not frames_dir.exists():
            print(f"跳过 {note_id} (目录不存在: {frames_dir})")
            continue

        frames = sample_frames(frames_dir, n=5)
        imgs_b64 = [base64.b64encode(f.read_bytes()).decode("utf-8") for f in frames]

        print("=" * 70)
        print(f"【案例】{brand}  (note_id={note_id[-8:]}  live_{live_idx:02d})")
        print(f"采样帧: {[f.name for f in frames]}")
        print("=" * 70)

        case = {
            "brand": brand,
            "note_id": note_id,
            "live_idx": live_idx,
            "frames": [f.name for f in frames],
            "model_outputs": {},
        }

        for model in active_models:
            print(f"\n--- 模型: {model} ---")
            resp, dt = call_model(model, imgs_b64, PROMPT)
            case["model_outputs"][model] = {
                "elapsed_s": round(dt, 1),
                "output_len": len(resp),
                "text": resp,
            }
            print(f"耗时 {dt:.1f}s | {len(resp)} 字\n{resp}\n")

        all_results.append(case)

    # 一次性写盘
    out_path = OUT_DIR / f"_model_compare_{time.strftime('%Y%m%d_%H%M')}.json"
    out_path.write_text(json.dumps({
        "run_time": time.strftime("%Y-%m-%d %H:%M:%S"),
        "active_models": active_models,
        "cases": all_results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    # 摘要
    print("\n" + "=" * 70)
    print("=== 总耗时汇总 ===")
    print(f"{'案例':<20}", end="")
    for m in active_models:
        print(f"{m:<25}", end="")
    print()
    for case in all_results:
        print(f"{case['brand']:<20}", end="")
        for m in active_models:
            r = case["model_outputs"].get(m, {})
            dt = r.get("elapsed_s", "-")
            print(f"{dt}s".ljust(25), end="")
        print()

    print(f"\n完整结果已写入: {out_path}")


if __name__ == "__main__":
    main()
