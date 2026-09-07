#!/usr/bin/env python3
"""DashScope qwen3-vl-plus 视频理解验证。

用 vivo 希区柯克 mp4 让它回答：镜头运动方式是什么。
如果它能明确说出"希区柯克变焦/dolly zoom/背景相对主体收缩"这类专业描述，
就证明云端 qwen3-vl 有真视频时序理解能力，值得走云 API 路径。
"""
import os
import time
from pathlib import Path
import dashscope

# 从 ~/.env 读 key（DLP 加密不影响，因为文件写入 1 分钟内可读；这里通过 shell env 更保险）
ENV_FILE = Path.home() / ".env"
if not os.environ.get("DASHSCOPE_API_KEY") and ENV_FILE.exists():
    for line in ENV_FILE.read_text().splitlines():
        if line.startswith("DASHSCOPE_API_KEY="):
            os.environ["DASHSCOPE_API_KEY"] = line.split("=", 1)[1].strip()
            break

api_key = os.environ.get("DASHSCOPE_API_KEY")
if not api_key:
    raise SystemExit("未找到 DASHSCOPE_API_KEY，请先执行: export DASHSCOPE_API_KEY=sk-xxx")

print(f"Key 已加载: {api_key[:8]}...{api_key[-4:]}")

VIDEO = Path(__file__).resolve().parent.parent / "数据/xhs_notes/683aaed300000000120018b4/lives/live_00.mp4"
print(f"视频: {VIDEO.name} ({VIDEO.stat().st_size/1024:.0f} KB)")

# DashScope 支持本地文件路径（file:// 前缀）
video_uri = f"file://{VIDEO.absolute()}"

PROMPT = """请仔细观看这段视频，用中文回答以下问题：

1. **镜头运动方式**：主体（人物）和背景的相对运动关系是什么？
   - 是"主体不动 + 背景放大/缩小"（希区柯克变焦/dolly zoom）？
   - 还是"镜头环绕主体旋转"（360 环绕）？
   - 还是"镜头推近/拉远"（普通变焦）？
   - 还是"主体在动、镜头固定"（普通拍摄）？
   请说出具体判断依据（比如主体大小是否变化、背景是否有透视变化）。

2. **画面主体**：主要拍的是什么？人物穿什么？在哪？

3. **可见文字**：读出所有可辨识的文字。

请分条回答，简洁精准。"""

print("\n===== 调用 qwen3-vl-plus =====")
start = time.time()

response = dashscope.MultiModalConversation.call(
    api_key=api_key,
    model="qwen3-vl-plus",
    messages=[
        {
            "role": "user",
            "content": [
                {"video": video_uri},
                {"text": PROMPT},
            ],
        }
    ],
)

elapsed = time.time() - start
print(f"耗时: {elapsed:.1f}s")
print(f"HTTP 状态: {response.status_code}")

if response.status_code == 200:
    text = response.output.choices[0].message.content[0]["text"]
    usage = response.usage
    print(f"\n=== 输出 ({len(text)} 字) ===")
    print(text)
    print(f"\n=== Token 消耗 ===")
    print(f"input:  {usage.get('input_tokens', '?')}")
    print(f"output: {usage.get('output_tokens', '?')}")
    print(f"total:  {usage.get('total_tokens', '?')}")
    if 'video_tokens' in usage:
        print(f"video:  {usage['video_tokens']}")
else:
    print(f"\n错误: {response.code} - {response.message}")
