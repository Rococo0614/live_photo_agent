#!/usr/bin/env python3
"""测试 ollama 部署下的 qwen3-vl:8b 是否支持视频原生输入。

三种试法逐个测：
1. images 字段传 mp4 base64（qwen2.5vl 这条路是 400）
2. videos 字段传 mp4 base64（Qwen 官方推理协议里的字段名）
3. images 字段传 mp4 路径字符串（有些 ollama 版本支持文件路径）

任何一条 200 且返回非空内容都算成功。
"""
import base64
import json
import sys
from pathlib import Path
import requests

VIDEO = Path(__file__).resolve().parent.parent / "数据/xhs_notes/683aaed300000000120018b4/lives/live_00.mp4"
MODEL = "qwen3-vl:8b"
URL = "http://localhost:11434/api/generate"
PROMPT = "这个视频里镜头有没有做希区柯克变焦（dolly zoom）？如果有，描述人物和背景的相对变化。"

if not VIDEO.exists():
    print(f"视频不存在: {VIDEO}")
    sys.exit(1)

video_b64 = base64.b64encode(VIDEO.read_bytes()).decode()
size_mb = len(video_b64) / 1024 / 1024
print(f"视频 base64 大小: {size_mb:.1f} MB\n")


def try_call(payload_extra: dict, label: str):
    print(f"===== 尝试 {label} =====")
    payload = {"model": MODEL, "prompt": PROMPT, "stream": False, **payload_extra}
    try:
        r = requests.post(URL, json=payload, timeout=300)
        print(f"HTTP {r.status_code}")
        if r.status_code != 200:
            print(f"错误: {r.text[:400]}")
            return False
        data = r.json()
        resp = data.get("response", "")
        print(f"输出 {len(resp)} 字:\n{resp[:800]}\n")
        return len(resp.strip()) > 0
    except Exception as e:
        print(f"异常: {e}")
        return False


# 试法 1: images 字段丢 mp4 base64
try_call({"images": [video_b64]}, "A: images=[mp4_base64]")
print()

# 试法 2: videos 字段
try_call({"videos": [video_b64]}, "B: videos=[mp4_base64]")
print()

# 试法 3: images 传路径
try_call({"images": [str(VIDEO)]}, "C: images=[mp4_path]")