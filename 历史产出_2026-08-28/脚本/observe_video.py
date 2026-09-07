#!/usr/bin/env python3
"""云端视觉观察员（浏览器 fetch 版）。

通过 Chrome 浏览器发送请求到百炼 API（qwen-vl-max），
绕开公司防火墙对 Python 直连 SSL 的封锁。

架构（两步法，避免命令行 32K 限制）：
  1. Python 启动 localhost HTTP 服务器
  2. 浏览器 eval 1：从 localhost 拉取视频/帧 → base64 → 存入 window.__xhs_media
  3. 浏览器 eval 2：用 window.__xhs_media 调用百炼 API → 返回结果

用法:
    python observe_video.py <note_id> <live_index>
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime
from http.server import HTTPServer, SimpleHTTPRequestHandler

# Windows GBK 控制台打印含 emoji 的文件名/标题会崩，统一转 UTF-8
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass
from pathlib import Path

if len(sys.argv) < 3:
    print("用法: python observe_video.py <note_id> <live_index>")
    sys.exit(1)

NOTE_ID = sys.argv[1]
LIVE_IDX = int(sys.argv[2])

BASE = Path(__file__).resolve().parent.parent
NOTE_DIR = BASE / "数据/xhs_notes" / NOTE_ID
OUT_DIR = BASE / "分析卡片"
OUT_DIR.mkdir(exist_ok=True)

# 兼容两种命名
LIVE_NAME = f"live_{LIVE_IDX:02d}"
VIDEO = NOTE_DIR / "lives" / f"{LIVE_NAME}.mp4"
if not VIDEO.exists():
    VIDEO = NOTE_DIR / "lives" / f"video_{LIVE_IDX:02d}.mp4"
    if VIDEO.exists():
        LIVE_NAME = f"video_{LIVE_IDX:02d}"

if not VIDEO.exists():
    print(f"视频不存在: {NOTE_DIR / 'lives'} 下未找到 live_{LIVE_IDX:02d}.mp4")
    sys.exit(1)

# 读帖子元数据
meta = {}
note_json = NOTE_DIR / "note.json"
if note_json.exists():
    try:
        meta = json.loads(note_json.read_text("utf-8"))
    except Exception:
        pass

# 加载 API Key
ENV_FILE = Path.home() / ".env"
API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
if not API_KEY and ENV_FILE.exists():
    for line in ENV_FILE.read_text().splitlines():
        if line.startswith("DASHSCOPE_API_KEY="):
            API_KEY = line.split("=", 1)[1].strip()
if not API_KEY:
    print("ERROR: DASHSCOPE_API_KEY 未设置")
    sys.exit(1)

API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
BROWSER_SESSION = "xhs-fetch"

# ============ 纯观察员 Prompt ============
PROMPT = """你是一名视频视觉观察员。你的**唯一任务**是把这段视频里能看到的所有客观事实描述出来，供下游分析师使用。

【绝对纪律】
1. **禁止**给出任何分类、命名、标签、判断（例："这是希区柯克变焦"、"这是拼贴玩法"、"这是抠图" —— 都不允许）。
2. **禁止**评价好坏、打分、给建议。
3. **禁止**推测拍摄手法、后期方式、道具品牌、玩法归属。
4. **禁止**使用主观形容词（漂亮、精致、氛围感、有趣、时尚等）。
5. **只允许**描述"看到了什么、位置在哪、大小多少、如何变化"。若某个观察证据不足，就写"证据不足，无法判断"，不要猜。

【必须回答的观察维度】
请按下列结构自由描述你看到的一切，用中文，尽可能给数字（像素、比例、次数、方向）：

## 一、静态画面构成
- 画面里有哪些视觉元素？（人/物/场景/背景装饰/前景遮挡/文字/图形/边框/覆盖层……穷举，不要遗漏）
- 每个元素的位置、大小、颜色、材质、状态。
- 元素之间的空间关系（谁在前谁在后、谁遮挡谁）。

## 二、时序变化（逐元素）
对上面列举的**每一个元素**，分别描述它在视频从头到尾的变化：
- 位置有没有变？（向哪个方向、位移多少像素/百分比）
- 大小有没有变？（放大/缩小、比例是多少）
- 形状/颜色/透明度有没有变？
- 数量有没有变？（新出现的、消失的）
- 变化的时间节奏是什么？（匀速/加速/循环/突变/一次性）

## 三、人物/主体动作
如果画面里有人或动物，逐帧描述他们的动作细节：
- 身体各部位的位置变化（头/手/脚/躯干）
- 表情变化
- 视线方向

## 四、镜头视角信息
- 画面的取景框有没有变化？（画幅/比例/裁切）
- **重点**：请**优先量化以下几个背景锚点的像素尺寸变化**（不要只看单点位移，要看整体尺寸/占比）：
  - 远处最大建筑（如教堂/主体建筑）在画面中占据的**高度比例**从第一帧到最后一帧的变化（例：从占画面 40% → 55%）
  - 该建筑上标志性元素（如圆窗、门框、招牌）的**像素宽度**从第一帧到最后一帧的变化（例：从 120px → 180px）
  - 前景到背景的**透视深度**是否发生变化（例：石板路的消失点是否上移或下移）
- 从视频里能观察到的、镜头本身是否发生位移/旋转/焦距变化的证据。
- 只描述观察到的现象，不做归类。
- **警告**：如果你发现"远处建筑元素明显放大了很多但报告成'<1像素偏移'"，这是自相矛盾的，请重新核对。

## 五、可见文字（OCR）
逐字读出画面中所有可辨识的文字。无法辨识的用「■」代替。给出文字的位置（画面哪个区域）。

## 六、其他值得记录的观察
如果画面里还有前面五项没覆盖到的观察点，在这里补充。可能包括但不限于：光线变化、反光、阴影关系、局部模糊/清晰度差异、颜色偏移、像素级细节等。
- 只描述客观现象，不做原因推测（例：**只写**"某物体边缘有 1-2px 白色像素带"，**不写**"这是合成痕迹"）
- 如果没有需要补充的，写"未观察到"。

【最后】
请**不要**输出任何总结、结论、玩法名、建议。任何这类内容将被视为违反纪律。"""


# ============ opencli 调用 ============
def _opencli_argv() -> list[str]:
    if sys.platform == "win32":
        node = shutil.which("node") or r"C:\Program Files\nodejs\node.EXE"
        entry = Path.home() / "AppData/Roaming/npm/node_modules/@jackwener/opencli/dist/src/main.js"
        if not entry.exists():
            pkg = Path.home() / "AppData/Roaming/npm/node_modules/@jackwener/opencli/package.json"
            if pkg.exists():
                d = json.loads(pkg.read_text("utf-8"))
                rel = list(d.get("bin", {}).values())[0] if isinstance(d.get("bin"), dict) else d.get("bin", "")
                entry = Path.home() / "AppData/Roaming/npm/node_modules/@jackwener/opencli" / rel
        return [node, str(entry)]
    return [shutil.which("opencli") or "opencli"]


def _run(cmd: list[str], timeout: int = 60) -> tuple[str, str, int]:
    env = dict(os.environ)
    env["OPENCLI_CDP_ENDPOINT"] = "http://127.0.0.1:9999"
    r = subprocess.run(cmd, capture_output=True, shell=False, timeout=timeout, env=env)
    return (
        (r.stdout or b"").decode("utf-8", errors="replace").strip(),
        (r.stderr or b"").decode("utf-8", errors="replace").strip(),
        r.returncode,
    )


def browser_eval(js: str, timeout: int = 120) -> str:
    """在浏览器 session 里执行 JS，返回结果字符串。"""
    cmd = _opencli_argv() + ["browser", BROWSER_SESSION, "eval", js]
    stdout, stderr, rc = _run(cmd, timeout=timeout)
    if rc != 0:
        raise RuntimeError(f"browser eval 失败: {stderr[:300]}")
    return stdout


# ============ 本地 HTTP 服务器 ============
def _find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class _QuietHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(VIDEO.parent), **kwargs)

    def log_message(self, format, *args):
        pass


def _start_file_server(port: int) -> HTTPServer:
    server = HTTPServer(("127.0.0.1", port), _QuietHandler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return server


# ============ 视频工具 ============
def get_video_duration(video_path: Path) -> float:
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(video_path)],
            capture_output=True, text=True, timeout=10,
        )
        return float(r.stdout.strip())
    except Exception:
        return -1.0


def extract_frames(video_path: Path, fps: int = 3) -> list[Path]:
    tmp_dir = Path(tempfile.mkdtemp(prefix="live_frames_"))
    subprocess.run(
        ["ffmpeg", "-i", str(video_path), "-vf", f"fps={fps}",
         str(tmp_dir / "frame_%03d.jpg"), "-y"],
        capture_output=True, timeout=30,
    )
    return sorted(tmp_dir.glob("frame_*.jpg"))


# ============ 浏览器 API 调用（两步法，避免命令行 32K 限制）============
def call_api_via_browser_video(model: str, prompt: str, timeout: int = 300) -> dict:
    """两步法：1) 浏览器从 localhost 拉视频转 base64 存全局变量
               2) 用全局变量调 API

    避免 base64 数据经过命令行（Windows 32K 限制）。
    """
    start = time.time()

    # 启动 HTTP 服务器
    port = _find_free_port()
    server = _start_file_server(port)
    local_url = f"http://127.0.0.1:{port}/{VIDEO.name}"

    # 导航到 localhost 页面（同源，允许 fetch）
    _run(_opencli_argv() + ["browser", BROWSER_SESSION, "open", f"http://127.0.0.1:{port}/"], timeout=30)

    try:
        # Step 1: 拉取视频并转 base64，存入 window.__xhs_media
        print(f"    [1/2] 从 localhost 拉取视频...")
        step1_js = f"""
(async () => {{
  const resp = await fetch('{local_url}');
  const buf = await resp.arrayBuffer();
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  window.__xhs_media = btoa(bin);
  return 'DONE ' + bytes.length;
}})()
"""
        result1 = browser_eval(step1_js, timeout=60)
        print(f"    {result1}")

        # Step 2: 用 base64 调 API
        print(f"    [2/2] 调用百炼 API...")
        prompt_escaped = json.dumps(prompt)
        step2_js = f"""
(async () => {{
  const b64 = window.__xhs_media;
  const resp = await fetch('{API_URL}', {{
    method: 'POST',
    headers: {{
      'Authorization': 'Bearer {API_KEY}',
      'Content-Type': 'application/json'
    }},
    body: JSON.stringify({{
      model: '{model}',
      messages: [{{
        role: 'user',
        content: [
          {{ type: 'video_url', video_url: {{ url: 'data:video/mp4;base64,' + b64 }} }},
          {{ type: 'text', text: {prompt_escaped} }}
        ]
      }}]
    }})
  }});
  const text = await resp.text();
  window.__xhs_media = null;
  return JSON.stringify({{status: resp.status, body: text}});
}})()
"""
        result = browser_eval(step2_js, timeout=timeout)
        elapsed = time.time() - start

        resp = json.loads(result)
        if resp.get("status") != 200:
            return {"ok": False, "text": f"ERROR: HTTP {resp.get('status')} - {resp.get('body', '')[:500]}",
                    "elapsed": elapsed, "tokens": 0, "mode": "video"}

        body = json.loads(resp["body"])
        choice = body.get("choices", [{}])[0]
        msg = choice.get("message", {})
        text = msg.get("content", "")
        if isinstance(text, list):
            text = "".join(part.get("text", "") for part in text if isinstance(part, dict))

        usage = body.get("usage", {})
        return {
            "ok": True,
            "text": text,
            "elapsed": elapsed,
            "tokens": usage.get("total_tokens", 0),
            "video_tokens": usage.get("video_tokens", 0),
            "mode": "video",
        }
    finally:
        server.shutdown()


def call_api_via_browser_frames(frames: list[Path], duration: float, fps: int, model: str, prompt: str, timeout: int = 300) -> dict:
    """两步法抽帧模式：1) 浏览器从 localhost 拉取帧图片转 base64 存全局变量
                    2) 用全局变量调 API
    """
    start = time.time()

    # 把帧文件复制到视频所在目录
    served_dir = VIDEO.parent
    frame_names = []
    for i, f in enumerate(frames):
        dst = served_dir / f"_frame_{i:03d}.jpg"
        dst.write_bytes(f.read_bytes())
        frame_names.append(dst.name)

    port = _find_free_port()
    server = _start_file_server(port)

    # 导航到 localhost 页面（同源，允许 fetch）
    _run(_opencli_argv() + ["browser", BROWSER_SESSION, "open", f"http://127.0.0.1:{port}/"], timeout=30)

    try:
        # Step 1: 拉取所有帧图片并转 base64
        print(f"    [1/2] 从 localhost 拉取 {len(frames)} 帧...")
        frame_urls_json = json.dumps([f"http://127.0.0.1:{port}/{name}" for name in frame_names])
        step1_js = f"""
(async () => {{
  const urls = {frame_urls_json};
  const results = [];
  for (const url of urls) {{
    const resp = await fetch(url);
    const buf = await resp.arrayBuffer();
    const bytes = new Uint8Array(buf);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    results.push('data:image/jpeg;base64,' + btoa(bin));
  }}
  window.__xhs_media = results;
  return 'DONE ' + results.length + ' frames';
}})()
"""
        result1 = browser_eval(step1_js, timeout=60)
        print(f"    {result1}")

        # Step 2: 用 base64 图片数组调 API
        print(f"    [2/2] 调用百炼 API...")
        frame_intro = f"以下 {len(frames)} 张图片是一段 {duration:.2f} 秒短视频按 {fps}fps 抽出的连续帧。请把这些帧当作一段连续视频来观察，整体描述。"
        prompt_escaped = json.dumps(prompt)
        frame_intro_escaped = json.dumps(frame_intro)

        step2_js = f"""
(async () => {{
  const frames = window.__xhs_media;
  const content = [
    {{ type: 'text', text: {frame_intro_escaped} }}
  ];
  for (const dataUrl of frames) {{
    content.push({{ type: 'image_url', image_url: {{ url: dataUrl }} }});
  }}
  content.push({{ type: 'text', text: {prompt_escaped} }});

  const resp = await fetch('{API_URL}', {{
    method: 'POST',
    headers: {{
      'Authorization': 'Bearer {API_KEY}',
      'Content-Type': 'application/json'
    }},
    body: JSON.stringify({{
      model: '{model}',
      messages: [{{ role: 'user', content: content }}]
    }})
  }});
  const text = await resp.text();
  window.__xhs_media = null;
  return JSON.stringify({{status: resp.status, body: text}});
}})()
"""
        result = browser_eval(step2_js, timeout=timeout)
        elapsed = time.time() - start

        # 清理临时帧
        for name in frame_names:
            (served_dir / name).unlink(missing_ok=True)
        for f in frames:
            f.unlink(missing_ok=True)
        if frames:
            frames[0].parent.rmdir()

        resp = json.loads(result)
        if resp.get("status") != 200:
            return {"ok": False, "text": f"ERROR: HTTP {resp.get('status')} - {resp.get('body', '')[:500]}",
                    "elapsed": elapsed, "tokens": 0, "mode": "frames"}

        body = json.loads(resp["body"])
        choice = body.get("choices", [{}])[0]
        msg = choice.get("message", {})
        text = msg.get("content", "")
        if isinstance(text, list):
            text = "".join(part.get("text", "") for part in text if isinstance(part, dict))

        usage = body.get("usage", {})
        return {
            "ok": True,
            "text": text,
            "elapsed": elapsed,
            "tokens": usage.get("total_tokens", 0),
            "mode": "frames",
            "frame_count": len(frames),
            "fps": fps,
        }
    finally:
        server.shutdown()
        for name in frame_names:
            (served_dir / name).unlink(missing_ok=True)


# ============ 主流程 ============
duration = get_video_duration(VIDEO)
FRAME_FPS = 3

if duration < 0:
    print(f"[WARN] 无法获取视频时长，尝试直接上传视频模式...")
    result = call_api_via_browser_video("qwen-vl-max", PROMPT)
elif duration < 2.0:
    print(f"[TIMER] 视频时长 {duration:.2f}s < 2s，启用抽帧兜底方案（{FRAME_FPS}fps）")
    frames = extract_frames(VIDEO, fps=FRAME_FPS)
    if not frames:
        result = {"ok": False, "text": "ERROR: ffmpeg 抽帧失败",
                  "elapsed": 0, "tokens": 0, "mode": "frames"}
    else:
        result = call_api_via_browser_frames(frames, duration, FRAME_FPS, "qwen-vl-max", PROMPT)
else:
    print(f"[TIMER] 视频时长 {duration:.2f}s >= 2s，使用视频直传模式")
    result = call_api_via_browser_video("qwen-vl-max", PROMPT)

print(f"耗时 {result['elapsed']:.1f}s | tokens {result['tokens']} | 输出 {len(result['text'])} 字\n")

# 保存观察报告（先写文件，避免 print 时的 GBK 编码错误导致报告丢失）
today = datetime.now().strftime("%Y-%m-%d")
safe_title = (meta.get("meta", {}).get("title") or NOTE_ID).replace("/", "_").replace(" ", "").replace("|", "").replace("\\", "").replace(":", "").replace("*", "").replace("?", "").replace("\"", "").replace("<", "").replace(">", "")[:30]
out = OUT_DIR / f"{today}_{safe_title}_{LIVE_NAME}_观察报告.md"

mode_label = result.get("mode", "video")
if mode_label == "frames":
    model_name = "qwen-vl-max"
    type_label = "视频观察报告（抽帧模式）"
    extra_fields = f"frame_extraction: {result.get('fps', FRAME_FPS)}fps, {result.get('frame_count', 0)} frames\nvideo_duration: {duration:.2f}s"
else:
    model_name = "qwen-vl-max"
    type_label = "视频观察报告"
    extra_fields = f"video_duration: {duration:.2f}s" if duration >= 0 else "video_duration: unknown"

header = f"""---
generated_at: {datetime.now().isoformat()}
type: {type_label}
note_id: {NOTE_ID}
live: {LIVE_NAME}
video_file: {VIDEO.name}
model: {model_name}
role: 纯视觉观察员（禁止归类/评分/命名）
elapsed_sec: {result['elapsed']:.1f}
tokens: {result['tokens']}
{extra_fields}
note_title: {meta.get('meta', {}).get('title', '')}
note_desc: {(meta.get('meta', {}).get('desc', '') or '')[:200]}
note_tags: {meta.get('meta', {}).get('tags', [])}
---

# {type_label} - {LIVE_NAME}

"""
out.write_text(header + result["text"], encoding="utf-8")
print(f"\n[OK] 观察报告已保存: {out.name}")

# 打印报告摘要（GBK 安全）
print("=" * 80)
try:
    print(result["text"])
except UnicodeEncodeError:
    # GBK 控制台无法打印某些字符，用 ASCII safe 版本
    safe_text = result["text"].encode("ascii", errors="replace").decode("ascii")
    print(safe_text)
print("=" * 80)