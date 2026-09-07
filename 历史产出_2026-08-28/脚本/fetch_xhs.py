#!/usr/bin/env python3
"""
小红书笔记完整抓取（opencli browser eval 版）：
- 输入：一个 xiaohongshu.com 详情页 URL（需带 xsec_token）
- 方式：用 opencli browser open + eval 读取已登录 Chrome 里的 window.__INITIAL_STATE__，
        直接从其中提取 Live Photo 视频 URL（stream.h264[0].masterUrl）和封面图 URL，
        再用 urllib 下载。不依赖 opencli download（它不处理 Live 视频）。
- 输出：
    * 元信息（标题、正文、tag、互动数、作者、相机机型识别）
    * 所有 Live Photo 视频 .mp4
    * 封面图 .jpg
    * 每个 Live 视频的抽帧（fps=5）
    * note.json（下游脚本兼容的结构）

前提：
  1. Chrome 已运行并登录小红书
  2. Browser Bridge 扩展已安装
  3. npm install -g @jackwener/opencli
  4. ffmpeg / ffprobe 在 PATH 中

用法：
  python fetch_xhs.py "https://www.xiaohongshu.com/explore/<id>?xsec_token=..."
"""
import base64
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

# Windows GBK 控制台打印 emoji（如标题里的 😋）会崩，统一转 UTF-8
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ==== 配置 ====
if len(sys.argv) >= 2:
    URL = sys.argv[1]
else:
    URL = (
        "https://www.xiaohongshu.com/discovery/item/6a2687100000000007029cc8"
        "?source=webshare&xhsshare=pc_web"
        "&xsec_token=ABvAKnysKZoeXC5CbrsMbyKi-fMhSgu4QBD6V3jhZgceU="
        "&xsec_source=pc_share"
    )

WORK_ROOT = Path(__file__).resolve().parent.parent / "数据/xhs_notes"
FPS = 5
FRAME_WIDTH = 768
BROWSER_SESSION = "xhs-fetch"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0 Safari/537.36"


# ==== opencli 调用基础 ====
def _opencli_argv() -> list[str]:
    """Windows 下绕开 cmd.exe，直接用 node 调用 opencli JS 入口。"""
    if sys.platform == "win32":
        import shutil
        node = shutil.which("node") or r"C:\Program Files\nodejs\node.EXE"
        entry = Path.home() / "AppData/Roaming/npm/node_modules/@jackwener/opencli/dist/src/main.js"
        if not entry.exists():
            pkg = Path.home() / "AppData/Roaming/npm/node_modules/@jackwener/opencli/package.json"
            if pkg.exists():
                d = json.loads(pkg.read_text("utf-8"))
                rel = list(d.get("bin", {}).values())[0] if isinstance(d.get("bin"), dict) else d.get("bin", "")
                entry = Path.home() / "AppData/Roaming/npm/node_modules/@jackwener/opencli" / rel
        return [node, str(entry)]
    import shutil
    return [shutil.which("opencli") or "opencli"]


def _run(cmd: list[str], timeout: int = 60) -> tuple[str, str, int]:
    r = subprocess.run(cmd, capture_output=True, shell=False, timeout=timeout)
    return (
        (r.stdout or b"").decode("utf-8").strip(),
        (r.stderr or b"").decode("utf-8", errors="replace").strip(),
        r.returncode,
    )


def browser(subcmd: list[str], timeout: int = 60) -> str:
    """执行 opencli browser <SESSION> <subcmd...>，返回 stdout。"""
    cmd = _opencli_argv() + ["browser", BROWSER_SESSION] + subcmd
    stdout, stderr, rc = _run(cmd, timeout=timeout)
    if rc != 0:
        raise RuntimeError(f"browser {' '.join(subcmd)} 失败: {stderr[:200]}")
    return stdout


def browser_eval(js: str, timeout: int = 30) -> str:
    """在浏览器 session 里执行 JS，返回结果字符串。"""
    return browser(["eval", js], timeout=timeout)


# ==== 工具函数 ====
def parse_count(val) -> int:
    if not val:
        return 0
    s = str(val).strip()
    try:
        return int(s)
    except ValueError:
        pass
    try:
        s_clean = s.rstrip("+")
        if s_clean.endswith("万"):
            return int(float(s_clean[:-1]) * 10000)
        if s_clean.endswith("k") or s_clean.endswith("K"):
            return int(float(s_clean[:-1]) * 1000)
        return int(float(s_clean))
    except Exception:
        return 0


def detect_camera(text: str) -> str | None:
    if not text:
        return None
    L = r"(?:^|[^A-Za-z0-9])"
    R = r"(?![A-Za-z0-9])"
    patterns = [
        (rf"{L}(OPPO\s*Reno\s*\d+\s*(?:Pro\+?|Pro)?){R}", "OPPO"),
        (rf"{L}(OPPO\s*Find\s*[XN]\d+\s*(?:Pro\+?|Pro|Ultra)?){R}", "OPPO"),
        (rf"{L}(vivo\s*X\d+\s*(?:Pro\+?|Pro|Ultra)?){R}", "vivo"),
        (rf"{L}(iQOO\s*\d+\s*(?:Pro|Ultra)?){R}", "vivo"),
        (rf"{L}(Mate\s*\d+\s*(?:Pro\+?|Pro|RS)?){R}", "华为"),
        (rf"{L}(P\d+\s*(?:Pro\+?|Pro|Art)?){R}", "华为"),
        (rf"{L}(Pura\s*\d+\s*(?:Pro\+?|Pro|Ultra)?){R}", "华为"),
        (rf"{L}(小米\s*\d+\s*(?:Pro|Ultra)?){R}", "小米"),
        (rf"{L}(Redmi\s*K\d+\s*(?:Pro|Ultra)?){R}", "小米"),
        (rf"{L}(iPhone\s*\d+\s*(?:Pro\s*Max|Pro|Plus|mini)?){R}", "苹果"),
        (rf"{L}(Galaxy\s*S\d+\s*(?:Ultra|Plus|\+)?){R}", "三星"),
        (rf"{L}(Sony|索尼)\s*(a\d+[MRSC]?[ⅠⅡⅢⅣ0-9]*|α\d+){R}", "相机"),
        (rf"{L}(Canon|佳能)\s*(EOS\s*R\d+|R\d+){R}", "相机"),
        (rf"{L}(Fujifilm|富士)\s*(X-[A-Z0-9]+|X\d+){R}", "相机"),
    ]
    for pat, brand in patterns:
        m = re.search(pat, text, re.IGNORECASE)
        if m:
            model = m.group(1) if m.lastindex else m.group(0)
            return f"{brand} {model.strip()}"
    return None


def http_download(url: str, out_path: Path) -> int:
    """直接 HTTP 下载（用于封面图，CDN 不需要登录）。"""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": "https://www.xiaohongshu.com/"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    out_path.write_bytes(data)
    return len(data)


def browser_download(url: str, out_path: Path) -> int:
    """用浏览器 fetch（携带登录 Cookie）下载，解决 CDN 鉴权问题。
    以 base64 分块传回，最大支持约 50MB 的文件。
    """
    fetch_js = f"""
(async () => {{
  const resp = await fetch({json.dumps(url)});
  if (!resp.ok) return JSON.stringify({{error: resp.status}});
  const buf = await resp.arrayBuffer();
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}})()
"""
    b64 = browser_eval(fetch_js, timeout=120)
    if b64.startswith("{"):  # JSON error
        err = json.loads(b64)
        raise RuntimeError(f"browser fetch 失败: {err}")
    data = base64.b64decode(b64)
    out_path.write_bytes(data)
    return len(data)


def extract_frames(video_path: Path, out_dir: Path, fps: int = FPS) -> int:
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-i", str(video_path),
        "-vf", f"fps={fps},scale={FRAME_WIDTH}:-2",
        "-q:v", "2",
        str(out_dir / "frame_%03d.jpg"),
    ]
    subprocess.run(cmd, check=True)
    return len(list(out_dir.glob("frame_*.jpg")))


def ffprobe_duration(video_path: Path) -> float:
    cmd = ["ffprobe", "-v", "error", "-show_entries", "format=duration",
           "-of", "default=nw=1:nk=1", str(video_path)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    try:
        return float(r.stdout.strip())
    except ValueError:
        return 0.0


# ==== 主流程 ====
def main():
    print(f">>> URL: {URL}")
    t0 = time.time()

    # 1. 用 opencli browser 打开页面，等待 INITIAL_STATE 就绪
    print(">>> 打开页面...")
    browser(["open", URL], timeout=30)
    time.sleep(3)  # 等 Vue/Pinia hydration 完成

    # 2. 从 INITIAL_STATE 读取 noteDetailMap
    print(">>> 读取 INITIAL_STATE...")
    raw = browser_eval("JSON.stringify(window.__INITIAL_STATE__?.note?.noteDetailMap || {})")
    ndm = json.loads(raw)
    if not ndm:
        raise RuntimeError("noteDetailMap 为空，页面可能未完全加载或需要登录")

    note_id = list(ndm.keys())[0]
    nd = ndm[note_id].get("note") or ndm[note_id]
    print(f"    note_id: {note_id}")

    # 3. 提取元信息
    title = (nd.get("title") or "").strip()
    desc = (nd.get("desc") or "").strip()
    tags = [t.get("name", "") for t in (nd.get("tagList") or [])]
    ii = nd.get("interactInfo") or {}
    user = nd.get("user") or {}
    camera = detect_camera(desc + " " + title)

    meta = {
        "url": URL,
        "note_id": note_id,
        "title": title,
        "desc": desc,
        "tags": tags,
        "interact": {
            "liked":     parse_count(ii.get("likedCount", 0)),
            "collected": parse_count(ii.get("collectedCount", 0)),
            "comment":   parse_count(ii.get("commentCount", 0)),
            "shared":    parse_count(ii.get("shareCount", 0)),
        },
        "author": {
            "nickname": user.get("nickname"),
            "user_id":  user.get("userId"),
            "avatar":   user.get("avatar"),
        },
        "camera_detected": camera,
        "ip_location": nd.get("ipLocation"),
        "post_time":   nd.get("time"),
        "captured_at": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    print(f"    标题: {title}")
    print(f"    作者: {user.get('nickname')}")
    print(f"    互动: 赞{meta['interact']['liked']} 藏{meta['interact']['collected']} "
          f"评{meta['interact']['comment']} 转{meta['interact']['shared']}")
    print(f"    tags({len(tags)}): {tags}")
    print(f"    机型: {camera or '(未识别)'}")

    # 4. 建目录
    note_dir = WORK_ROOT / note_id
    note_dir.mkdir(parents=True, exist_ok=True)
    lives_dir = note_dir / "lives"
    lives_dir.mkdir(exist_ok=True)
    covers_dir = note_dir / "covers"
    covers_dir.mkdir(exist_ok=True)

    # 5. 遍历 imageList，下载 Live 视频 + 封面图
    imgs = nd.get("imageList") or []
    note_type = nd.get("type") or ""
    print(f"\n>>> 共 {len(imgs)} 张图片/Live（帖子类型: {note_type}）")
    media_records = []
    live_idx = 0
    video_idx = 0

    for i, im in enumerate(imgs):
        # 封面图 URL
        cover_url = im.get("urlDefault") or im.get("url") or ""

        # Live 视频 URL（兼容 h264 / EF4 等格式，取任一带 masterUrl 的）
        stream = im.get("stream") or {}
        h264 = stream.get("h264") or stream.get("EF4") or []
        if not h264:
            for _k, _v in stream.items():
                if isinstance(_v, list) and len(_v) > 0 and isinstance(_v[0], dict) and "masterUrl" in _v[0]:
                    h264 = _v
                    break
        video_url = h264[0].get("masterUrl", "") if h264 else ""
        is_live = bool(im.get("livePhoto")) or bool(video_url)

        # 视频帖（type=video）：视频 URL 在 nd.video.media.stream 里，imageList 的 stream 为空
        is_video_post = False
        if not video_url and note_type == "video":
            nd_video = (nd.get("video") or {}).get("media") or {}
            nd_stream = nd_video.get("stream") or {}
            for _k in ("EF4", "EF6", "EF7", "EF5", "h264"):
                arr = nd_stream.get(_k) or []
                if arr and isinstance(arr[0], dict) and arr[0].get("masterUrl"):
                    video_url = arr[0]["masterUrl"]
                    is_video_post = True
                    break

        rec = {
            "index": i,
            "is_live": is_live,
            "width": im.get("width"),
            "height": im.get("height"),
        }

        # 下载封面
        if cover_url:
            try:
                cover_path = covers_dir / f"cover_{i:02d}.jpg"
                sz = http_download(cover_url, cover_path)
                rec["cover_local"] = str(cover_path)
                rec["cover_bytes"] = sz
            except Exception as e:
                rec["cover_error"] = str(e)
                print(f"  [{i:02d}] 封面下载失败: {e}")

        # 下载视频并抽帧（用浏览器 fetch，携带登录 Cookie 绕过 CDN 鉴权）
        if video_url:
            if is_video_post:
                v_path = lives_dir / f"video_{video_idx:02d}.mp4"
                v_kind = "VIDEO"
            else:
                v_path = lives_dir / f"live_{live_idx:02d}.mp4"
                v_kind = "LIVE"
            try:
                sz = browser_download(video_url, v_path)
                dur = ffprobe_duration(v_path)
                frames_dir = lives_dir / f"{v_path.stem}_frames"
                n_frames = extract_frames(v_path, frames_dir)
                rec.update({
                    "type": "video" if is_video_post else "live",
                    "is_live": is_live,
                    "video_url": video_url,
                    "video_local": str(v_path),
                    "video_bytes": sz,
                    "video_duration": round(dur, 2),
                    "frames_dir": str(frames_dir),
                    "frames_count": n_frames,
                })
                print(f"  [{i:02d}] {v_kind} {v_path.stem}: {sz/1024:.0f}KB  {dur:.1f}s -> {n_frames} frames")
                if is_video_post:
                    video_idx += 1
                else:
                    live_idx += 1
            except Exception as e:
                rec["type"] = "video" if is_video_post else "live"
                rec["video_error"] = str(e)
                print(f"  [{i:02d}] {v_kind} 下载/抽帧失败: {e}")
                if is_video_post:
                    video_idx += 1
                else:
                    live_idx += 1
        else:
            rec["type"] = "cover"
            print(f"  [{i:02d}] 静态图  {rec.get('cover_bytes', 0) // 1024}KB")

        media_records.append(rec)
        time.sleep(0.3)

    # 6. 写 note.json
    all_data = {"meta": meta, "media": media_records}
    (note_dir / "note.json").write_text(
        json.dumps(all_data, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    # 7. 摘要
    elapsed = time.time() - t0
    live_count  = sum(1 for r in media_records if r.get("is_live"))
    total_frames = sum(r.get("frames_count", 0) for r in media_records)
    print("\n" + "=" * 60)
    print(f"[OK] 完成  耗时 {elapsed:.1f}s")
    print(f"存储: {note_dir}")
    print(f"  Live 视频: {live_count} 个")
    print(f"  抽帧总数: {total_frames}")
    print(f"  静态图: {len(media_records) - live_count}")
    print(f"  note.json 已写入")
    print(f"\n下一步：")
    print(f"  python vision_batch.py {note_id}      # 视觉分析所有 Live")
    print(f"  python observe_batch.py {note_id}     # 云端 qwen3-vl-plus 观察")


if __name__ == "__main__":
    main()
