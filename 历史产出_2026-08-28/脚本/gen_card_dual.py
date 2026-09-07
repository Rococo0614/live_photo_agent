#!/usr/bin/env python3
"""双通道分析卡片生成器。

对同一个 live 视频，用两种模型各出一份完整分析卡片：
- A：云端 qwen3-vl-plus，吃完整 mp4（原生视频）
- B：本地 qwen2.5vl:7b，吃 5 帧 jpg（抽帧）

产出：两份独立 markdown，方便人工比较完整交付质量。

用法：
    python gen_card_dual.py <note_id> <live_index>
例：
    python gen_card_dual.py 683aaed300000000120018b4 0
"""
import os
import sys
import time
import base64
import json
from pathlib import Path

# Windows GBK 控制台无法打印 emoji（🌐 等），强制 UTF-8 输出避免崩溃
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
from datetime import datetime
import dashscope
import requests

# ============ 参数解析 ============
if len(sys.argv) < 3:
    print("用法: python gen_card_dual.py <note_id> <live_index>")
    sys.exit(1)
NOTE_ID = sys.argv[1]
LIVE_IDX = int(sys.argv[2])

# ============ 路径 ============
BASE = Path(__file__).resolve().parent.parent
NOTE_DIR = BASE / "数据/xhs_notes" / NOTE_ID
OUT_DIR = BASE / "分析卡片"
OUT_DIR.mkdir(exist_ok=True)

# ============ 读帖子元数据 ============
note_json = NOTE_DIR / "note.json"
_note_data_full = {}
if note_json.exists():
    try:
        _note_data_full = json.load(open(note_json, encoding="utf-8"))
    except Exception:
        pass
# note.json 顶层是 {meta: {...}, media: [...]}
meta = _note_data_full.get("meta", {}) if isinstance(_note_data_full, dict) else {}
_media_list = _note_data_full.get("media", []) if isinstance(_note_data_full, dict) else []

# 可分析媒体：Live 视频或普通视频（创意玩法教程帖常为视频教程）
_media_list = [m for m in _media_list if m.get("is_live") or m.get("type") == "video"]
if LIVE_IDX >= len(_media_list):
    print(f"media_{LIVE_IDX:02d} 不存在，共 {len(_media_list)} 个可分析媒体")
    sys.exit(1)
_sel = _media_list[LIVE_IDX]
_video_local = _sel.get("video_local", "")
LIVE_NAME = Path(_video_local).stem if _video_local else f"live_{LIVE_IDX:02d}"
VIDEO = NOTE_DIR / "lives" / f"{LIVE_NAME}.mp4"
FRAMES = NOTE_DIR / "lives" / f"{LIVE_NAME}_frames"

if not VIDEO.exists() or not FRAMES.exists():
    print(f"文件缺失: {VIDEO} / {FRAMES}")
    sys.exit(1)
TITLE = meta.get("title", "")
DESC = meta.get("desc", "")[:300]
TAGS = meta.get("tags", [])
AUTHOR = meta.get("author", "")
INTERACT = meta.get("interact", {})

# ============ Key ============
ENV = Path.home() / ".env"
if not os.environ.get("DASHSCOPE_API_KEY") and ENV.exists():
    for l in ENV.read_text().splitlines():
        if l.startswith("DASHSCOPE_API_KEY="):
            os.environ["DASHSCOPE_API_KEY"] = l.split("=", 1)[1].strip()
API_KEY = os.environ["DASHSCOPE_API_KEY"]

# ============ 完整版 Prompt ============
PROMPT = f"""你是 vivo 影像团队的资深产品分析师，正在做小红书 Live Photo 玩法情报库。请针对这段短视频，输出一份**产品化分析卡片**。

【笔记上下文（供参考，可结合信息推断玩法名）】
- 标题：{TITLE}
- 正文摘要：{DESC}
- 标签：{TAGS}
- 作者：{AUTHOR}
- 互动数据：{INTERACT}

【输出要求】禁止使用"漂亮/精致/温馨/氛围感"等主观形容词，只描述客观事实并给判断依据。严格按以下 9 项分条输出：

## 1. 玩法主体识别（一句话+定性）
用一句话概括这个 Live Photo 玩法的核心机制（例："人物微动 + 希区柯克变焦 + 装饰边框"）。

## 2. 帧间变化描述（客观事实）
- 主体的大小是否变化？（变大/变小/不变，给量化观察）
- 背景元素的透视和大小是否变化？（背景变大/变小/横移/不变，给具体参照物）
- 主体本身有没有做动作？（挥手/表情/姿态）
- 一句话总结最显著视觉变化。

## 3. 镜头运动归类（在【2】基础上选 1 项 + 依据）
- (A) 主体动镜头不动
- (B) 镜头推拉（主体和背景同步缩放）
- (C) 希区柯克变焦（主体尺寸恒定+背景剧烈缩放）
- (D) 镜头环绕（背景左右大幅位移）

## 4. 可见文字 OCR
逐字读出所有可辨识文字（含水印、招牌、字幕、EXIF 参数），无则"无"。

## 5. 技术痕迹
逐条判断有/无：抠图叠加 / 多图拼合 / 贴纸图形 / 涂鸦手写 / 边框装饰 / AI 风格化痕迹。

## 6. 素材门槛
- 需要几张什么样的素材？
- 是否需要特殊拍摄（稳定器/多角度/机内 AI 变焦）？
- 是否需要专门道具？

## 7. 破圈四要素评分（1-5 分）
按 vivo 团队的破圈框架打分并说明依据：
- **情绪价值/场景**（1-5）：这个玩法命中什么情绪？在什么场景用？
- **一眼好**（1-5）：3 秒内能不能抓住眼球？视觉锤是什么？
- **没见过**（1-5）：跟常规拍摄相比稀缺度多高？
- **我能用**（1-5）：素人拿到手机能不能复刻？门槛多高？

## 8. vivo 产品化对应（战略视角）
从"感知（主体识别/Live 抠图）+ 3D（重建）+ 互动（陀螺仪/触控）+ 编辑特效"四大原子能力出发，说明这个玩法涉及哪些能力、vivo 手机具备/欠缺什么。

## 9. 关键词入库（用于知识库检索）
用 5-8 个关键词标签概括这个玩法（例：#希区柯克变焦 #人像运镜 #欧式街拍 #背景压缩 #AI变焦运镜）。"""


# ============ 云端调用 ============
def call_cloud():
    print("\n===== 🌐 云端 qwen3-vl-plus（吃完整 mp4）=====")
    start = time.time()
    r = dashscope.MultiModalConversation.call(
        api_key=API_KEY,
        model="qwen3-vl-plus",
        messages=[{
            "role": "user",
            "content": [
                {"video": f"file://{VIDEO.absolute()}"},
                {"text": PROMPT},
            ],
        }],
    )
    elapsed = time.time() - start
    if r.status_code != 200:
        return {"ok": False, "text": f"ERROR: {r.code} - {r.message}", "elapsed": elapsed, "tokens": 0}
    text = r.output.choices[0].message.content[0]["text"]
    return {
        "ok": True,
        "text": text,
        "elapsed": elapsed,
        "tokens": r.usage.get("total_tokens", 0),
        "video_tokens": r.usage.get("video_tokens", 0),
    }


# ============ 本地调用 ============
def call_local():
    print("\n===== 💻 本地 qwen2.5vl:7b（吃 5 帧 jpg）=====")
    all_frames = sorted(FRAMES.glob("frame_*.jpg"))
    n = len(all_frames)
    idx = [int(i * (n - 1) / 4) for i in range(5)] if n >= 5 else list(range(n))
    picked = [all_frames[i] for i in idx]
    images_b64 = [base64.b64encode(p.read_bytes()).decode() for p in picked]

    prompt_local = f"下面是从这段 3 秒 Live Photo 视频均匀抽取的 5 帧图片（按时间顺序）。\n\n{PROMPT}"
    start = time.time()
    r = requests.post("http://localhost:11434/api/generate", json={
        "model": "qwen2.5vl:7b",
        "prompt": prompt_local,
        "images": images_b64,
        "stream": False,
        "options": {"num_predict": 3000},  # 放大 output token 上限，避免截断
    }, timeout=600)
    elapsed = time.time() - start
    if r.status_code != 200:
        return {"ok": False, "text": f"HTTP {r.status_code}", "elapsed": elapsed, "tokens": 0}
    d = r.json()
    return {
        "ok": True,
        "text": d.get("response", ""),
        "elapsed": elapsed,
        "tokens": d.get("eval_count", 0) + d.get("prompt_eval_count", 0),
        "frames": len(picked),
    }


# ============ 生成卡片 markdown ============
def make_card(source: str, result: dict) -> str:
    today = datetime.now().strftime("%Y-%m-%d")
    header = f"""---
generated_at: {datetime.now().isoformat()}
note_id: {NOTE_ID}
live: {LIVE_NAME}
source_model: {source}
elapsed_sec: {result['elapsed']:.1f}
tokens: {result['tokens']}
video_file: {VIDEO.name}
note_title: {TITLE}
note_tags: {TAGS}
---

# 【{source}】{TITLE or NOTE_ID} - {LIVE_NAME}

**耗时**: {result['elapsed']:.1f}s | **Tokens**: {result['tokens']} | **状态**: {"✅ 成功" if result['ok'] else "❌ 失败"}

---

"""
    return header + result["text"]


# ============ 主流程 ============
# 云端 A 通道（独立可用，失败也不影响后续写文件）
try:
    cloud = call_cloud()
    print(f"耗时 {cloud['elapsed']:.1f}s | {cloud['tokens']} tokens | {len(cloud['text'])} 字")
except Exception as e:
    print(f"云端调用异常: {e}")
    cloud = {"ok": False, "text": f"ERROR: {e}", "elapsed": 0, "tokens": 0}

# 本地 B 通道（容错：Ollama 不在/失败时降级为占位，不阻断写文件）
try:
    local = call_local()
    print(f"耗时 {local['elapsed']:.1f}s | {local['tokens']} tokens | {len(local['text'])} 字")
except Exception as e:
    print(f"本地调用异常（降级为纯云端）: {e}")
    local = {"ok": False, "text": f"本地 qwen2.5vl:7b 不可用（Ollama 未运行）：{e}",
             "elapsed": 0, "tokens": 0}

# 写文件
today = datetime.now().strftime("%Y-%m-%d")
safe_title = (TITLE or NOTE_ID).replace("/", "_").replace(" ", "")[:30]

cloud_file = OUT_DIR / f"{today}_{safe_title}_{LIVE_NAME}_A云端qwen3vlplus.md"
local_file = OUT_DIR / f"{today}_{safe_title}_{LIVE_NAME}_B本地qwen25vl.md"

cloud_file.write_text(make_card("云端 qwen3-vl-plus (原生视频输入)", cloud), encoding="utf-8")
local_file.write_text(make_card("本地 qwen2.5vl:7b (5帧抽样输入)", local), encoding="utf-8")

# 对比摘要文件
summary = OUT_DIR / f"{today}_{safe_title}_{LIVE_NAME}_对比摘要.md"
summary.write_text(f"""# 双通道分析对比 - {TITLE or NOTE_ID} / {LIVE_NAME}

生成时间: {datetime.now().isoformat()}
视频: {VIDEO.name}

## 指标对比

| 维度 | 云端 qwen3-vl-plus | 本地 qwen2.5vl:7b |
|---|---|---|
| 输入方式 | 原生 mp4 视频 | 5 帧 jpg 抽样 |
| 耗时 | {cloud['elapsed']:.1f}s | {local['elapsed']:.1f}s |
| 输出字数 | {len(cloud['text'])} | {len(local['text'])} |
| Token | {cloud['tokens']} | {local['tokens']} |
| 完整性 | {"✅" if cloud['ok'] else "❌"} | {"✅" if local['ok'] else "❌"} |

## 云端结果
详见 `{cloud_file.name}`

## 本地结果
详见 `{local_file.name}`
""", encoding="utf-8")

print(f"\n✅ 已生成 3 个文件:")
print(f"  {cloud_file.name}")
print(f"  {local_file.name}")
print(f"  {summary.name}")
