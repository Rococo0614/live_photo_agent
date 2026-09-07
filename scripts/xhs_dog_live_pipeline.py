#!/usr/bin/env python3
"""狗 Live Photo 自动发现+抓取+筛选流水线 (Linux 版)

阶段:
  P1 搜索: 搜索 "狗 live photo"，翻5页，收集100条帖子
  P2 封面: 下载封面图，提取元信息  
  P3 筛选: Qwen2.5-VL 判断是否含狗 + 画面质量
  P4 下载: 合格视频下载

输出: data/xhs_dog_live/
"""

import base64, json, os, re, shutil, subprocess, sys, time, urllib.request
from pathlib import Path
from datetime import datetime

# ── 配置 ──
KEYWORD = "狗 live photo"
BASE = Path(__file__).resolve().parent.parent
DATA_ROOT = BASE / "data/xhs_dog_live"
COVERS_DIR = DATA_ROOT / "covers"
QUALIFIED_DIR = DATA_ROOT / "qualified"
RAW_DIR = DATA_ROOT / "raw"
BROWSER = "xhs-dog-fetch"

NODE = shutil.which("node")
OPENCLI = shutil.which("opencli")
OPENCLI_ENTRY = Path.home() / ".npm-global/lib/node_modules/@jackwener/opencli/dist/src/main.js"

VLM_PORT = 8100
VLM_URL = f"http://localhost:{VLM_PORT}/v1/chat/completions"
VLM_MODEL = "/home/vivo/models/Qwen2.5-VL-7B-Instruct"

SEARCH_URL = "https://www.xiaohongshu.com/search_result"
MAX_PAGES = 5
ITEMS_PER_PAGE = 20
TARGET_TOTAL = 100
TARGET_QUALIFIED = 30

# ── 工具函数 ──
def _beval(js_code, timeout=30):
    """Run JavaScript in Chrome via opencli browser eval."""
    cmd = f'"{NODE}" "{OPENCLI_ENTRY}" browser {BROWSER} eval "{js_code}"'
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip()

def _bopen(url, timeout=30):
    """Open URL in browser via opencli."""
    cmd = f'"{NODE}" "{OPENCLI_ENTRY}" browser {BROWSER} open "{url}"'
    subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)

def _bclose():
    subprocess.run(f'"{NODE}" "{OPENCLI_ENTRY}" browser {BROWSER} close',
                   shell=True, capture_output=True, text=True, timeout=10)

def _b64decode_from_js(b64):
    """Decode base64 from JS fetch response."""
    return base64.b64decode(b64)

def _download_cover(cover_url, note_id):
    """Download cover image via browser fetch (bypasses CDN protection)."""
    js = f"""(async () => {{
        const resp = await fetch('{cover_url}');
        const buf = await resp.arrayBuffer();
        return btoa(String.fromCharCode(...new Uint8Array(buf)));
    }})()"""
    try:
        b64 = _beval(js, timeout=30)
        img_data = _b64decode_from_js(b64)
        path = COVERS_DIR / f"{note_id}.jpg"
        path.write_bytes(img_data)
        return str(path)
    except Exception as e:
        print(f"    [download_cover] error: {e}")
        return None

# ── P1: 搜索 ──
def search_xhs():
    """搜索并返回100条帖子信息列表。"""
    print(f"[P1] 搜索关键词: {KEYWORD}")
    all_notes = []
    seen_ids = set()

    for page in range(1, MAX_PAGES + 1):
        url = f"{SEARCH_URL}?keyword={urllib.request.quote(KEYWORD)}&page={page}"
        print(f"  第 {page} 页: {url}")

        try:
            _bopen(url)
            time.sleep(3)

            feeds_raw = _beval("JSON.stringify(window.__INITIAL_STATE__.search.feeds || [])", timeout=15)
            feeds = json.loads(feeds_raw)

            for feed in feeds:
                nid = feed.get("noteId") or feed.get("id")
                if not nid or nid in seen_ids:
                    continue
                seen_ids.add(nid)

                note = {
                    "note_id": nid,
                    "title": feed.get("title", ""),
                    "author": feed.get("user", {}).get("nickname", ""),
                    "likes": feed.get("likes", 0),
                    "collected": feed.get("collectedCount", 0),
                    "cover_url": feed.get("cover", {}).get("url", ""),
                    "note_url": f"https://www.xiaohongshu.com/explore/{nid}",
                }
                all_notes.append(note)

            print(f"    本页 {len(feeds)} 条, 累计 {len(all_notes)} 条")

        except Exception as e:
            print(f"    第 {page} 页失败: {e}")
            break

        if len(all_notes) >= TARGET_TOTAL:
            break

    now = datetime.now().strftime("%Y-%m-%d_%H%M%S")
    raw_path = RAW_DIR / f"search_{now}.json"
    json.dump(all_notes, open(raw_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"[P1] 完成: {len(all_notes)} 条, 保存到 {raw_path}")
    return all_notes

# ── P2: 下载封面 ──
def download_covers(notes):
    """批量下载封面图。"""
    print(f"[P2] 下载封面: {len(notes)} 条")
    downloaded = []
    for i, note in enumerate(notes):
        nid = note["note_id"]
        cover_url = note.get("cover_url", "")
        if not cover_url:
            continue
        print(f"  [{i+1}/{len(notes)}] {nid}")
        path = _download_cover(cover_url, nid)
        if path:
            note["cover_path"] = path
            downloaded.append(note)
        time.sleep(0.5)
    print(f"[P2] 完成: {len(downloaded)} 张封面")
    return downloaded

# ── P3: VLM 筛选 ──
def check_dog_image(cover_path):
    """用 Qwen2.5-VL 判断封面图是否包含狗。"""
    try:
        with open(cover_path, "rb") as f:
            img_b64 = base64.b64encode(f.read()).decode()

        payload = {
            "model": VLM_MODEL,
            "temperature": 0,
            "max_tokens": 10,
            "messages": [
                {"role": "system", "content": "只回答 yes 或 no。"},
                {"role": "user", "content": [
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{img_b64}"}},
                    {"type": "text", "text": "Is there a dog in this image? Answer yes or no only."}
                ]}
            ]
        }
        data = json.dumps(payload).encode()
        req = urllib.request.Request(VLM_URL, data=data, headers={"Content-Type": "application/json"})
        resp = urllib.request.urlopen(req, timeout=30)
        result = json.loads(resp.read())
        answer = result["choices"][0]["message"]["content"].strip().lower()
        return "yes" in answer
    except Exception as e:
        print(f"    [VLM] error: {e}")
        return False

def filter_by_vlm(notes):
    """用 VLM 筛选含狗的封面。"""
    print(f"[P3] VLM 筛选: {len(notes)} 张封面")
    qualified = []
    for i, note in enumerate(notes):
        cover_path = note.get("cover_path", "")
        if not cover_path:
            continue
        print(f"  [{i+1}/{len(notes)}] {note['note_id']} ...", end=" ", flush=True)
        if check_dog_image(cover_path):
            print("YES")
            qualified.append(note)
        else:
            print("no")
        if len(qualified) >= TARGET_QUALIFIED:
            break
    print(f"[P3] 完成: {len(qualified)} 条合格")
    return qualified

# ── P4: 下载视频 ──
def download_video(note):
    """下载单个 Live Photo 视频。"""
    nid = note["note_id"]
    try:
        _bopen(note["note_url"])
        time.sleep(2)

        detail_js = """(() => {
            var nd = window.__INITIAL_STATE__.note.noteDetailMap;
            var key = Object.keys(nd)[0];
            var il = nd[key].note.imageList || [];
            var live = il.filter(function(x) { return x.livePhoto; });
            if (live.length === 0) return JSON.stringify({live: false});
            var s = live[0].stream;
            return JSON.stringify({
                live: true,
                videoUrl: s.h264 ? s.h264[0].masterUrl : "",
                width: live[0].width,
                height: live[0].height
            });
        })()"""
        detail = json.loads(_beval(detail_js, timeout=15))

        if not detail.get("live"):
            print(f"    [{nid}] 不是 Live Photo")
            return None

        video_url = detail.get("videoUrl", "")
        if not video_url:
            return None

        js = f"""(async () => {{
            const resp = await fetch('{video_url}');
            const buf = await resp.arrayBuffer();
            return btoa(String.fromCharCode(...new Uint8Array(buf)));
        }})()"""
        b64 = _beval(js, timeout=60)
        video_data = _b64decode_from_js(b64)

        out_dir = QUALIFIED_DIR / nid
        out_dir.mkdir(exist_ok=True)
        video_path = out_dir / "video.mp4"
        video_path.write_bytes(video_data)

        note["video_path"] = str(video_path)
        note["video_size"] = len(video_data)
        return note
    except Exception as e:
        print(f"    [{nid}] 下载失败: {e}")
        return None

def download_videos(notes):
    """批量下载合格视频。"""
    print(f"[P4] 下载视频: {len(notes)} 条")
    done = []
    for i, note in enumerate(notes):
        print(f"  [{i+1}/{len(notes)}] {note['note_id']}")
        result = download_video(note)
        if result:
            done.append(result)
        time.sleep(1)
    print(f"[P4] 完成: {len(done)} 个视频")
    return done

# ── 主流程 ──
def main():
    print(f"╔══════════════════════════════════════╗")
    print(f"║  狗 Live Photo 自动抓取流水线       ║")
    print(f"║  {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}                     ║")
    print(f"╚══════════════════════════════════════╝")

    for d in [DATA_ROOT, COVERS_DIR, QUALIFIED_DIR, RAW_DIR]:
        d.mkdir(parents=True, exist_ok=True)

    # P1: 搜索
    notes = search_xhs()
    if not notes:
        print("[P1] 搜索无结果，退出")
        return

    # P2: 下载封面
    notes = download_covers(notes)

    # P3: VLM 筛选
    qualified = filter_by_vlm(notes)

    if not qualified:
        print("[P3] 没有符合条件的内容，退出")
        _bclose()
        return

    # P4: 下载视频
    final = download_videos(qualified)
    _bclose()

    # 日报
    report = {
        "date": datetime.now().isoformat(),
        "keyword": KEYWORD,
        "searched": len(notes),
        "covers_downloaded": len(notes),
        "vlm_qualified": len(qualified),
        "videos_downloaded": len(final),
        "qualified": final,
    }
    report_path = DATA_ROOT / f"daily_{datetime.now().strftime('%Y-%m-%d')}.json"
    json.dump(report, open(report_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"\n日报: {report_path}")
    print(f"搜索 {len(notes)} → 封面 {len(notes)} → 合格 {len(qualified)} → 视频 {len(final)}")

if __name__ == "__main__":
    main()