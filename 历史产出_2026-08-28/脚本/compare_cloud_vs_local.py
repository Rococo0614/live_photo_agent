#!/usr/bin/env python3
"""云 qwen3-vl-plus vs 本地 qwen2.5vl:7b 完整能力对比。

对同一批 Live 视频，用同一份完整 prompt：
- 云端：直接吃 mp4
- 本地：抽 5 帧 jpg（fps 均匀采样）
输出：耗时 / token / 字数 / 答案文本，供人工评估。

Prompt 设计原则：
- 不问诱导性问题（例如"是不是希区柯克"）
- 让模型先描述客观帧间变化，再归类到 4 种运镜类型之一
- 要求它给判断依据，暴露"真感知 vs 脑补"
"""
import os
import sys
import time
import base64
import json
from pathlib import Path
import dashscope
import requests

# Windows GBK 控制台无法打印 emoji，强制 UTF-8 输出避免崩溃
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ============ 配置 ============
NOTE_DIR = Path(__file__).resolve().parent.parent / "数据/xhs_notes/683aaed300000000120018b4"
LIVES_TO_TEST = ["live_00", "live_01", "live_02"]  # 前 3 个
CLOUD_MODEL = "qwen3-vl-plus"
LOCAL_MODEL = "qwen2.5vl:7b"
OLLAMA_URL = "http://localhost:11434/api/generate"

# ============ 加载 API Key ============
ENV_FILE = Path.home() / ".env"
if not os.environ.get("DASHSCOPE_API_KEY") and ENV_FILE.exists():
    for line in ENV_FILE.read_text().splitlines():
        if line.startswith("DASHSCOPE_API_KEY="):
            os.environ["DASHSCOPE_API_KEY"] = line.split("=", 1)[1].strip()
            break
API_KEY = os.environ.get("DASHSCOPE_API_KEY")
if not API_KEY:
    raise SystemExit("未找到 DASHSCOPE_API_KEY")

# ============ 完整版 Prompt（开放式，非诱导）============
PROMPT = """请以专业视觉分析师的视角，仔细观察这段 Live Photo 短视频，分条回答下列 7 项。禁止使用"漂亮/精致/温馨"等主观形容词，只描述客观事实。

【1. 画面主体与构图】
- 主要拍摄对象是什么（人物/物品/场景）？
- 构图形式是什么（单幅拍摄/多图拼贴/分屏/宫格/画中画/其他）？
- 主体在画面中的位置和占比？

【2. 帧间变化描述（客观事实层）】
- 视频从开始到结束，主体的大小是否发生了变化？（变大/变小/不变）
- 背景元素（例如墙面、建筑、地面）的**透视和大小**是否发生了变化？（背景变大/背景变小/背景不变/背景左右横移）
- 主体本身有没有做动作（挥手/转身/跳跃/表情变化）？
- 用 1-2 句话描述整段视频最显著的视觉变化。

【3. 镜头运动归类（在【2】的客观描述基础上，从下列 4 类中选 1 类，并给判断依据）】
- (A) 主体动、镜头不动（普通拍摄）：主体大小基本不变+背景不变+主体本身在做动作
- (B) 镜头推近/拉远（普通变焦）：主体明显变大或变小，背景也同步变大或变小
- (C) 希区柯克变焦（Dolly Zoom）：**主体大小基本不变，但背景剧烈缩放（背景变大或变小）**，这是这类运镜的唯一诊断特征
- (D) 镜头环绕主体旋转（360 环绕）：主体位置基本不变，但背景发生了左右方向的大幅位移或旋转
请明确回答归类结果，并引用【2】中的观察给判断依据。

【4. 可见文字 OCR】
逐字读出所有可辨识文字（含中英文/水印/字幕/招牌），无则写"无"。

【5. 技术痕迹】
逐条判断有/无：抠图叠加 / 多图拼合 / 贴纸图形 / 涂鸦手写 / 边框装饰 / AI 风格化痕迹。

【6. 素材门槛（复刻难度）】
- 需要几张什么样的素材？
- 需要特殊拍摄方式吗？（三脚架/多角度/手持稳定器）
- 需要专门道具吗？

【7. 整体玩法一句话总结】
用一句话概括这个 Live 玩法（例如："人物定格+镜头环绕 360°" 或 "多张同主题照片拼贴+打字机字幕动效"）。"""


# ============ 云端调用 ============
def call_cloud(video_path: Path):
    start = time.time()
    resp = dashscope.MultiModalConversation.call(
        api_key=API_KEY,
        model=CLOUD_MODEL,
        messages=[{
            "role": "user",
            "content": [
                {"video": f"file://{video_path.absolute()}"},
                {"text": PROMPT},
            ],
        }],
    )
    elapsed = time.time() - start
    if resp.status_code != 200:
        return {"ok": False, "elapsed": elapsed, "text": f"ERROR {resp.code}: {resp.message}", "tokens": 0}
    text = resp.output.choices[0].message.content[0]["text"]
    usage = resp.usage
    return {
        "ok": True,
        "elapsed": elapsed,
        "text": text,
        "tokens": usage.get("total_tokens", 0),
        "video_tokens": usage.get("video_tokens", 0),
    }


# ============ 本地调用（抽 5 帧）============
def call_local(frames_dir: Path):
    frame_files = sorted(frames_dir.glob("frame_*.jpg"))
    if len(frame_files) == 0:
        return {"ok": False, "elapsed": 0, "text": "无帧", "tokens": 0}
    # 均匀采样 5 帧
    n = len(frame_files)
    idx = [int(i * (n - 1) / 4) for i in range(5)] if n >= 5 else list(range(n))
    picked = [frame_files[i] for i in idx]

    images_b64 = [base64.b64encode(p.read_bytes()).decode() for p in picked]
    prompt_with_note = f"以下是从这段 Live Photo 视频按时间顺序均匀抽取的 5 帧图片。\n\n{PROMPT}"

    start = time.time()
    r = requests.post(OLLAMA_URL, json={
        "model": LOCAL_MODEL,
        "prompt": prompt_with_note,
        "images": images_b64,
        "stream": False,
    }, timeout=300)
    elapsed = time.time() - start
    if r.status_code != 200:
        return {"ok": False, "elapsed": elapsed, "text": f"HTTP {r.status_code}: {r.text[:200]}", "tokens": 0}
    data = r.json()
    return {
        "ok": True,
        "elapsed": elapsed,
        "text": data.get("response", ""),
        "tokens": data.get("eval_count", 0) + data.get("prompt_eval_count", 0),
        "frames_used": len(picked),
    }


# ============ 主流程 ============
results = []
for live_name in LIVES_TO_TEST:
    video_path = NOTE_DIR / "lives" / f"{live_name}.mp4"
    frames_dir = NOTE_DIR / "lives" / f"{live_name}_frames"
    if not video_path.exists() or not frames_dir.exists():
        print(f"跳过 {live_name}（文件缺失）")
        continue

    print(f"\n{'='*80}\n【{live_name}】  video={video_path.name}  frames={len(list(frames_dir.glob('*.jpg')))}\n{'='*80}")

    print(f"\n--- 🌐 云端 {CLOUD_MODEL} ---")
    cloud = call_cloud(video_path)
    print(f"耗时 {cloud['elapsed']:.1f}s | tokens {cloud['tokens']} (video {cloud.get('video_tokens','?')}) | 输出 {len(cloud['text'])} 字\n")
    print(cloud["text"])

    print(f"\n--- 💻 本地 {LOCAL_MODEL} ---")
    local = call_local(frames_dir)
    print(f"耗时 {local['elapsed']:.1f}s | tokens {local['tokens']} | 输出 {len(local['text'])} 字 | 帧数 {local.get('frames_used','?')}\n")
    print(local["text"])

    results.append({
        "live": live_name,
        "cloud": cloud,
        "local": local,
    })

# ============ 汇总 ============
print(f"\n\n{'='*80}\n=== 汇总 ===\n{'='*80}")
print(f"{'Live':<12} {'指标':<10} {'云端 qwen3-vl-plus':<30} {'本地 qwen2.5vl:7b':<30}")
for r in results:
    print(f"{r['live']:<12} {'耗时':<10} {r['cloud']['elapsed']:.1f}s{'':<24} {r['local']['elapsed']:.1f}s")
    print(f"{'':<12} {'字数':<10} {len(r['cloud']['text']):<30} {len(r['local']['text']):<30}")
    print(f"{'':<12} {'token':<10} {r['cloud']['tokens']:<30} {r['local']['tokens']:<30}")

total_cloud_tokens = sum(r["cloud"]["tokens"] for r in results)
print(f"\n本次云端总消耗: {total_cloud_tokens} tokens（约 ¥{total_cloud_tokens * 0.002 / 1000:.4f}）")
