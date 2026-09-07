#!/usr/bin/env python3
"""测试本地模型能否直接吃 mp4 视频文件"""
import base64
import json
import sys
import time
import urllib.request
from pathlib import Path

VIDEO = Path(__file__).resolve().parent.parent / "数据/xhs_notes/69fc0daa000000002301c10d/lives/live_04.mp4"
OLLAMA_URL = "http://localhost:11434/api/generate"

# 华为 3D 那条，已知：定格 + 环绕运镜（子弹时间效果）
PROMPT = """请用中文描述这段视频里发生了什么。特别注意判断镜头运动方式：
- 是主体在动、镜头不动（普通拍摄）
- 还是主体静止、镜头在环绕（子弹时间/环绕运镜）
- 还是主体静止、镜头推拉（希区柯克变焦/推近拉远）
- 还是有视差感（3D 空间照片）
请给出判断依据。200 字内。"""


def test_model(model_name: str, prompt: str, video_path: Path) -> tuple[str, float, str]:
    """返回 (response, elapsed_seconds, error_or_empty)"""
    video_b64 = base64.b64encode(video_path.read_bytes()).decode("utf-8")
    payload = {
        "model": model_name,
        "prompt": prompt,
        "images": [video_b64],   # 试图作为 images 字段传入 mp4
        "stream": False,
        "options": {"temperature": 0.2, "num_predict": 400},
    }
    t0 = time.time()
    req = urllib.request.Request(
        OLLAMA_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        return data.get("response", ""), time.time() - t0, data.get("error", "")
    except Exception as e:
        return "", time.time() - t0, str(e)


def main():
    print(f"视频: {VIDEO.name}, {VIDEO.stat().st_size / 1024:.0f} KB")
    print(f"提示: {PROMPT[:60]}...\n")

    for model in ["minicpm-v4.6:latest", "qwen2.5vl:7b"]:
        print("=" * 60)
        print(f"【测试】{model}")
        print("=" * 60)
        resp, dt, err = test_model(model, PROMPT, VIDEO)
        print(f"耗时: {dt:.1f}s")
        if err:
            print(f"错误: {err}")
        if resp:
            print(f"响应 ({len(resp)}字):\n{resp}\n")
        else:
            print("(无响应)\n")


if __name__ == "__main__":
    main()
