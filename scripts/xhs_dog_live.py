#!/usr/bin/env python3
"""狗 Live Photo 自动发现+抓取+筛选流水线 (Playwright 版)

阶段:
  P1 搜索: 搜索 "狗 live photo"，翻N页，收集100条帖子
  P2 封面: 下载封面图，提取元信息
  P3 筛选: Qwen2.5-VL 判断是否含狗
  P4 下载: 合格视频下载 (浏览器 fetch 绕过 CDN)

输出: data/xhs_dog_live/{note_id}/
"""

import base64, json, os, re, subprocess, sys, time, urllib.request
from pathlib import Path
from datetime import datetime

BASE = Path(__file__).resolve().parent.parent
DATA_ROOT = BASE / "data/xhs_dog_live"
COVERS_DIR = DATA_ROOT / "covers"
QUALIFIED_DIR = DATA_ROOT / "qualified"
RAW_DIR = DATA_ROOT / "raw"
STATE_FILE = DATA_ROOT / "state.json"

KEYWORD = "狗 live photo"
VLM_URL = "http://localhost:8100/v1/chat/completions"
VLM_MODEL = "/home/vivo/models/Qwen2.5-VL-7B-Instruct"
MAX_PAGES = 5
TARGET_QUALIFIED = 30
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0 Safari/537.36"
CHROME_DATA = DATA_ROOT / "chrome_data"  # persistent profile, cookies survive restarts
CHROME_EXE = "/opt/google/chrome/google-chrome"  # use system Chrome, not Playwright's bundled Chromium

for d in [DATA_ROOT, COVERS_DIR, QUALIFIED_DIR, RAW_DIR]:
    d.mkdir(parents=True, exist_ok=True)

# ── P1: 搜索 ──────────────────────────────────────────────
def _cleanup_singleton_lock():
    """Remove Chrome singleton lock files that prevent Playwright from restarting."""
    for f in ["SingletonLock", "lockfile"]:
        path = CHROME_DATA / f
        try:
            if path.exists():
                path.unlink()
        except Exception:
            pass


def search_and_collect():
    """翻页搜索，收集帖子列表"""
    from playwright.sync_api import sync_playwright

    print(f"[P1] 搜索: {KEYWORD} (最多 {MAX_PAGES} 页)")
    items = []
    seen = set()

    with sync_playwright() as p:
        CHROME_DATA.mkdir(parents=True, exist_ok=True)
        buffer = p.chromium.launch_persistent_context(
            str(CHROME_DATA), headless=False, args=["--no-sandbox"],
            executable_path=CHROME_EXE,
            user_agent=UA, viewport={"width": 1280, "height": 900},
        )
        page = buffer.new_page()

        encoded = urllib.parse.quote(KEYWORD)
        search_url = f"https://www.xiaohongshu.com/search_result?keyword={encoded}&sort=general&type=note&source=web_search_result_notes"
        page.goto(search_url, wait_until="load", timeout=60000)
        time.sleep(5)

        for pg in range(MAX_PAGES):
            print(f"  第 {pg+1}/{MAX_PAGES} 页 ...")
            try:
                feeds = page.evaluate("""() => {
                    var s = window.__INITIAL_STATE__;
                    if (!s || !s.search || !s.search.feeds) return [];
                    var f = s.search.feeds._rawValue || s.search.feeds._value || s.search.feeds;
                    if (!Array.isArray(f)) return [];
                    var result = [];
                    for (var i = 0; i < f.length; i++) {
                        var x = f[i];
                        var n = x.noteCard || {};
                        var ii = n.interactInfo || {};
                        var id = x.id || '';
                        var xsec = x.xsecToken || '';
                        if (id && id.length === 24 && xsec) {
                            result.push({
                                id: id, xsec: xsec,
                                col: Number(ii.collectedCount || 0),
                                like: Number(ii.likedCount || 0),
                                type: n.type || ''
                            });
                        }
                    }
                    return result;
                }""")
            except Exception as e:
                print(f"    eval error: {e}")
                feeds = []

            for item in feeds:
                if item["id"] not in seen:
                    seen.add(item["id"])
                    items.append(item)

            print(f"    本页 {len(feeds)} 条, 累计 {len(items)} 条")

            if len(items) >= 100:
                break

            # Scroll down to trigger next page load
            if pg < MAX_PAGES - 1:
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                time.sleep(4)

        buffer.close()

    items.sort(key=lambda x: -x["col"])
    print(f"[P1] 共收集 {len(items)} 条帖子")
    return items


# ── P2: 封面下载 ──────────────────────────────────────────
def download_covers(items):
    """用 Playwright 打开详情页，下载封面图"""
    from playwright.sync_api import sync_playwright

    print(f"[P2] 下载封面图 (前 {min(len(items), 50)} 条) ...")
    limit = min(len(items), 50)
    saved = []

    with sync_playwright() as p:
        CHROME_DATA.mkdir(parents=True, exist_ok=True)
        buffer = p.chromium.launch_persistent_context(
            str(CHROME_DATA), headless=False, args=["--no-sandbox"],
            executable_path=CHROME_EXE,
            user_agent=UA, viewport={"width": 1280, "height": 900},
        )
        page = buffer.new_page()

        for i, item in enumerate(items[:limit]):
            note_id = item["id"]
            note_dir = DATA_ROOT / note_id
            cover_dest = note_dir / "cover.jpg"
            meta_dest = note_dir / "meta.json"

            if cover_dest.exists() and meta_dest.exists():
                print(f"  [{i+1}/{limit}] {note_id} already cached, skip")
                saved.append(item)
                continue

            url = f"https://www.xiaohongshu.com/explore/{note_id}?xsec_token={item['xsec']}&xsec_source=pc_search"
            print(f"  [{i+1}/{limit}] {note_id} ...", end=" ", flush=True)

            try:
                page.goto(url, wait_until="networkidle", timeout=120000)
                time.sleep(8)

                detail = page.evaluate("""() => {
                    var s = window.__INITIAL_STATE__;
                    if (!s || !s.note || !s.note.noteDetailMap) return JSON.stringify({e:1});
                    var ndm = s.note.noteDetailMap;
                    var keys = Object.keys(ndm).filter(function(k) { return k !== 'undefined'; });
                    if (!keys.length) return JSON.stringify({e:2});
                    var k = keys[0];
                    var nd = ndm[k].note || ndm[k];
                    if (!nd) return JSON.stringify({e:3});
                    var il = nd.imageList || [];
                    return JSON.stringify({
                        id: k, time: nd.time || 0, img: il.length,
                        live: il.filter(function(x) { return x.livePhoto; }).length,
                        type: nd.type || '', title: (nd.title || '').slice(0, 100),
                        tags: (nd.tagList || []).map(function(t) { return t.name || ''; }),
                        author: (nd.user || {}).nickname || '',
                        covers: il.map(function(x) { return x.urlDefault || x.url || ''; }).slice(0, 5),
                        hasLive: il.some(function(x) { return x.livePhoto; }),
                        videoUrls: il.filter(function(x) { return x.livePhoto && x.stream && x.stream.h264; })
                                    .map(function(x) { return x.stream.h264[0].masterUrl; })
                    });
                }""")
                detail = json.loads(detail)
                if detail.get("e"):
                    print(f"SKIP (code={detail['e']})")
                    continue

                note_dir.mkdir(parents=True, exist_ok=True)
                meta_dest.write_text(json.dumps(detail, ensure_ascii=False, indent=2), encoding="utf-8")

                # Download first cover image
                covers = detail.get("covers", [])
                if covers and covers[0]:
                    try:
                        req = urllib.request.Request(covers[0], headers={"User-Agent": UA, "Referer": "https://www.xiaohongshu.com/"})
                        with urllib.request.urlopen(req, timeout=60) as resp:
                            cover_dest.write_bytes(resp.read())
                    except Exception as e:
                        print(f"cover_dl_err: {e}")

                has_live = detail.get("hasLive", False)
                days = (time.time() * 1000 - detail.get("time", 0)) / 86400000 if detail.get("time") else 999
                title = detail.get("title", "")[:40].encode("ascii", "replace").decode("ascii")
                print(f"LIVE={has_live} {days:.0f}d | {title}")

                item["detail"] = {
                    "title": detail.get("title", ""),
                    "has_live": has_live,
                    "video_urls": detail.get("videoUrls", []),
                    "days_ago": round(days, 1),
                    "author": detail.get("author", ""),
                    "tags": detail.get("tags", []),
                }
                saved.append(item)

            except Exception as e:
                print(f"ERR: {e}")

            time.sleep(1)

        buffer.close()

    print(f"[P2] 共下载 {len(saved)} 条封面")
    return saved


# ── P3: VLM 筛选 ──────────────────────────────────────────
def vlm_filter(items):
    """用 Qwen2.5-VL 判断封面是否含狗"""
    print(f"[P3] VLM 筛选 (含狗检测) ...")
    qualified = []

    for i, item in enumerate(items):
        note_id = item["id"]
        cover_path = DATA_ROOT / note_id / "cover.jpg"
        if not cover_path.exists():
            continue

        with open(cover_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()

        payload = {
            "model": VLM_MODEL,
            "temperature": 0,
            "max_tokens": 50,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
                    {"type": "text", "text": "Is there a dog in this photo? Answer ONLY yes or no."}
                ]
            }]
        }

        try:
            data = json.dumps(payload).encode()
            req = urllib.request.Request(VLM_URL, data=data, headers={"Content-Type": "application/json"})
            resp = urllib.request.urlopen(req, timeout=30)
            result = json.loads(resp.read())
            answer = result["choices"][0]["message"]["content"].strip().lower()
            has_dog = "yes" in answer
            print(f"  [{i+1}/{len(items)}] {note_id}: {'YES' if has_dog else 'no'} ({answer[:20]})")
            if has_dog:
                item["has_dog"] = True
                qualified.append(item)
                if len(qualified) >= TARGET_QUALIFIED:
                    break
        except Exception as e:
            print(f"  [{i+1}/{len(items)}] {note_id}: VLM err: {e}")

    print(f"[P3] 筛选出 {len(qualified)} 条含狗帖子")
    return qualified


# ── P4: 视频下载 ──────────────────────────────────────────
def download_videos(items):
    """用 Playwright 浏览器 fetch 下载 Live Photo 视频"""
    from playwright.sync_api import sync_playwright

    print(f"[P4] 下载视频 ({len(items)} 条) ...")

    with sync_playwright() as p:
        CHROME_DATA.mkdir(parents=True, exist_ok=True)
        buffer = p.chromium.launch_persistent_context(
            str(CHROME_DATA), headless=False, args=["--no-sandbox"],
            executable_path=CHROME_EXE,
            user_agent=UA, viewport={"width": 1280, "height": 900},
        )
        page = buffer.new_page()

        for i, item in enumerate(items):
            note_id = item["id"]
            note_dir = DATA_ROOT / note_id
            detail = item.get("detail", {})
            video_urls = detail.get("video_urls", [])

            if not video_urls:
                # Re-fetch detail from cache
                meta_path = note_dir / "meta.json"
                if meta_path.exists():
                    detail = json.loads(meta_path.read_text(encoding="utf-8"))
                    video_urls = detail.get("videoUrls", [])

            if not video_urls:
                print(f"  [{i+1}/{len(items)}] {note_id}: no video URLs")
                continue

            # Open the page to get cookies/session
            url = f"https://www.xiaohongshu.com/explore/{note_id}?xsec_token={item['xsec']}&xsec_source=pc_search"
            try:
                page.goto(url, wait_until="load", timeout=120000)
            except:
                pass
            time.sleep(3)

            for vi, vurl in enumerate(video_urls):
                vp = note_dir / f"live_{vi:02d}.mp4"
                if vp.exists():
                    print(f"  [{i+1}/{len(items)}] {note_id} live_{vi:02d}: cached")
                    continue

                try:
                    print(f"  [{i+1}/{len(items)}] {note_id} live_{vi:02d}: downloading ...", end=" ", flush=True)
                    b64_data = page.evaluate(f"""async () => {{
                        const resp = await fetch('{vurl}');
                        if (!resp.ok) return JSON.stringify({{error: resp.status}});
                        const buf = await resp.arrayBuffer();
                        const bytes = new Uint8Array(buf);
                        let bin = '';
                        for (let j = 0; j < bytes.length; j++) bin += String.fromCharCode(bytes[j]);
                        return btoa(bin);
                    }}""")
                    if b64_data.startswith("{"):
                        print(f"fetch err: {b64_data[:100]}")
                        continue
                    vp.write_bytes(base64.b64decode(b64_data))
                    size_kb = vp.stat().st_size / 1024
                    print(f"{size_kb:.0f}KB OK")

                    # Extract frames
                    frames_dir = note_dir / f"live_{vi:02d}_frames"
                    frames_dir.mkdir(exist_ok=True)
                    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(vp),
                                    "-vf", "fps=5,scale=768:-2", "-q:v", "2",
                                    str(frames_dir / "frame_%03d.jpg")], check=True)
                    nf = len(list(frames_dir.glob("frame_*.jpg")))
                    print(f"    -> {nf} frames extracted")

                except Exception as e:
                    print(f"ERR: {e}")

            time.sleep(1)

        buffer.close()

    print(f"[P4] 完成")


# ── Main ───────────────────────────────────────────────────
def main():
    import subprocess as sp
    print(f"=== 狗 Live Photo 流水线 {datetime.now().strftime('%Y-%m-%d %H:%M')} ===\n")

    # P1: Search
    items = search_and_collect()
    if not items:
        print("P1 failed: no items found"); return
    json.dump(items, (RAW_DIR / "search_results.json").open("w"), ensure_ascii=False, indent=2)

    # P2: Cover download
    items = download_covers(items)
    if not items:
        print("P2 failed: no covers downloaded"); return

    # P3: VLM filter
    qualified = vlm_filter(items)
    if not qualified:
        print("P3: no dog posts found"); return

    # P4: Video download
    download_videos(qualified)

    # Save final state
    state = {
        "date": datetime.now().isoformat(),
        "keyword": KEYWORD,
        "total_searched": len(items),
        "total_qualified": len(qualified),
        "qualified_ids": [q["id"] for q in qualified],
    }
    json.dump(state, STATE_FILE.open("w"), ensure_ascii=False, indent=2)

    print(f"\n=== 完成 ===")
    print(f"  搜索: {len(items)} 条")
    print(f"  合格: {len(qualified)} 条")
    print(f"  输出: {DATA_ROOT}")


if __name__ == "__main__":
    main()