#!/usr/bin/env python3
"""搜索+抓取一体化：同一浏览器会话。用 browser open 导航，避免 eval 跳转超时"""
import base64, json, os, re, shutil, subprocess, sys, time, urllib.parse, urllib.request
from pathlib import Path

KEYWORD = "live图 创意玩法教程"
BASE = Path(__file__).resolve().parent.parent
WORK_ROOT = BASE / "数据/xhs_notes"
BROWSER = "xhs-fetch"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0 Safari/537.36"

node_exe = shutil.which("node") or r"C:\Program Files\nodejs\node.EXE"
entry_js = Path.home() / "AppData/Roaming/npm/node_modules/@jackwener/opencli/dist/src/main.js"
if not entry_js.exists():
    pkg = Path.home() / "AppData/Roaming/npm/node_modules/@jackwener/opencli/package.json"
    dd = json.loads(pkg.read_text("utf-8"))
    rel = list(dd.get("bin", {}).values())[0] if isinstance(dd.get("bin"), dict) else dd.get("bin", "")
    entry_js = Path.home() / "AppData/Roaming/npm/node_modules/@jackwener/opencli" / rel

def run(cmd, timeout=60):
    env = dict(os.environ)
    env["OPENCLI_CDP_ENDPOINT"] = "http://127.0.0.1:9999"
    r = subprocess.run(cmd, capture_output=True, shell=False, timeout=timeout, env=env)
    return (r.stdout or b"").decode("utf-8", errors="replace").strip(), (r.stderr or b"").decode("utf-8", errors="replace").strip(), r.returncode

def beval(js, timeout=120):
    stdout, stderr, rc = run([node_exe, str(entry_js), "browser", BROWSER, "eval", js], timeout)
    if rc: raise RuntimeError(f"eval failed: {stderr[:200]}")
    return stdout

def bopen(url, timeout=60):
    stdout, stderr, rc = run([node_exe, str(entry_js), "browser", BROWSER, "open", url], timeout)
    if rc: raise RuntimeError(f"open failed: {stderr[:200]}")

# Reset
run([node_exe, str(entry_js), "browser", BROWSER, "close"], timeout=15)
time.sleep(2)

# ===== Phase 1: Search =====
encoded = urllib.parse.quote(KEYWORD)
search_url = f"https://www.xiaohongshu.com/search_result?keyword={encoded}&sort=general&type=note&source=web_search_result_notes"
print(f">>> Opening search: {KEYWORD}")
bopen(search_url, timeout=60)
time.sleep(6)

feeds = json.loads(beval("""(() => {
  var s = window.__INITIAL_STATE__;
  if (!s || !s.search) return JSON.stringify([]);
  var f = s.search.feeds; if (!f) return JSON.stringify([]);
  var r = f._rawValue || f._value || f;
  if (!Array.isArray(r)) return JSON.stringify([]);
  return JSON.stringify(r.map(function(x) {
    var n = x.noteCard || x;
    var i = n.interactInfo || n.interact_info || {};
    return {id: n.noteId || n.note_id || x.id || '', xsec: x.xsecToken || '',
      col: Number(i.collectedCount || i.collected_count || 0),
      like: Number(i.likedCount || i.liked_count || 0), type: n.type || ''};
  }).filter(function(x) { return x.id.length === 24 && x.xsec; }));
})()""", timeout=15))
feeds.sort(key=lambda x: -x["col"])
print(f">>> {len(feeds)} feeds")

# ===== Phase 2: Check top 8 detail pages =====
DETAIL_JS = """(() => {
  var s = window.__INITIAL_STATE__;
  if (!s || !s.note || !s.note.noteDetailMap) return JSON.stringify({e:1});
  var ndm = s.note.noteDetailMap;
  var k = Object.keys(ndm);
  if (!k.length || k[0] === 'undefined') return JSON.stringify({e:2});
  var nd = ndm[k[0]].note || ndm[k[0]];
  if (!nd) return JSON.stringify({e:3});
  var il = nd.imageList || [];
  var lp = il.filter(function(x) { return x.livePhoto; });
  return JSON.stringify({id: k[0], time: nd.time || 0, img: il.length, live: lp.length, type: nd.type || '', title: (nd.title || '')});
})()"""

candidates = []
for i, item in enumerate(feeds[:8]):
    url = f"https://www.xiaohongshu.com/explore/{item['id']}?xsec_token={item['xsec']}&xsec_source=pc_search"
    print(f"  [{i}] {item['id']} col={item['col']}...", end=" ", flush=True)
    bopen(url, timeout=60)
    time.sleep(10)  # longer wait for page load
    try:
        d = json.loads(beval(DETAIL_JS, timeout=30))
        if d.get("e"): print(f"SKIP({d['e']})"); continue
        days = (time.time()*1000 - d["time"])/86400000 if d["time"] else 999
        has_live = d["live"] > 0
        is_video = d["type"] == "video"
        score = d["live"] * 100 + item["col"]  # prioritize Live Photo count
        t = d.get("title","")[:50].encode("ascii","replace").decode("ascii")
        print(f"{days:.0f}d LIVE={d['live']}/{d['img']} type={d['type']} score={score} | {t}")
        if days <= 7:
            d["col"] = item["col"]; d["like"] = item["like"]
            d["xsec"] = item["xsec"]; d["score"] = score
            candidates.append(d)
    except Exception as e:
        print(f"err: {e}")

if not candidates:
    print(">>> No within-7-days post found"); sys.exit(1)

# Prefer posts with Live Photo over video posts
candidates.sort(key=lambda x: (-x["live"], -x["col"]))
best = candidates[0]
print(f"\n>>> TARGET: {best['id']}")
print(f"    title: {best['title']}")
print(f"    col={best['col']} LIVE={best['live']}/{best['img']} type={best['type']}")

# ===== Phase 3: Fetch target =====
target_url = f"https://www.xiaohongshu.com/explore/{best['id']}?xsec_token={best['xsec']}&xsec_source=pc_search"
print(f"\n>>> Fetching: {best['id']}")
bopen(target_url, timeout=60)
time.sleep(6)

raw = beval("JSON.stringify(window.__INITIAL_STATE__?.note?.noteDetailMap || {})")
ndm = json.loads(raw)
note_id = list(ndm.keys())[0]
nd = ndm[note_id].get("note") or ndm[note_id]
print(f"    note_id: {note_id}")

title = (nd.get("title") or "").strip()
desc = (nd.get("desc") or "").strip()
tags = [t.get("name", "") for t in (nd.get("tagList") or [])]
ii = nd.get("interactInfo") or {}
user = nd.get("user") or {}

meta = {
    "url": target_url, "note_id": note_id, "title": title, "desc": desc, "tags": tags,
    "interact": {"liked": int(ii.get("likedCount",0) or 0), "collected": int(ii.get("collectedCount",0) or 0),
                 "comment": int(ii.get("commentCount",0) or 0), "shared": int(ii.get("shareCount",0) or 0)},
    "author": {"nickname": user.get("nickname"), "user_id": user.get("userId"), "avatar": user.get("avatar")},
    "ip_location": nd.get("ipLocation"), "post_time": nd.get("time"),
    "captured_at": time.strftime("%Y-%m-%d %H:%M:%S"),
}
print(f"    title: {title}")
print(f"    author: {user.get('nickname')}")

note_dir = WORK_ROOT / note_id
note_dir.mkdir(parents=True, exist_ok=True)
lives_dir = note_dir / "lives"; lives_dir.mkdir(exist_ok=True)
covers_dir = note_dir / "covers"; covers_dir.mkdir(exist_ok=True)

imgs = nd.get("imageList") or []
print(f"\n>>> {len(imgs)} images/Live")
media_records = []
live_idx = 0

for i, im in enumerate(imgs):
    cover_url = im.get("urlDefault") or im.get("url") or ""
    stream = im.get("stream") or {}
    # Try h264 first, then EF4 (newer format), then any key with a masterUrl array
    h264 = stream.get("h264") or stream.get("EF4") or []
    if not h264:
        for k, v in stream.items():
            if isinstance(v, list) and len(v) > 0 and isinstance(v[0], dict) and "masterUrl" in v[0]:
                h264 = v
                break
    video_url = h264[0].get("masterUrl", "") if h264 else ""
    is_live = bool(im.get("livePhoto")) or bool(video_url)

    rec = {"index": i, "is_live": is_live, "width": im.get("width"), "height": im.get("height")}

    if cover_url:
        try:
            cp = covers_dir / f"cover_{i:02d}.jpg"
            req = urllib.request.Request(cover_url, headers={"User-Agent": UA, "Referer": "https://www.xiaohongshu.com/"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                cp.write_bytes(resp.read())
            rec["cover_local"] = str(cp); rec["cover_bytes"] = cp.stat().st_size
        except Exception as e:
            rec["cover_error"] = str(e)

    if video_url:
        vp = lives_dir / f"live_{live_idx:02d}.mp4"
        try:
            fetch_js = f"""
(async () => {{
  const resp = await fetch({json.dumps(video_url)});
  if (!resp.ok) return JSON.stringify({{error: resp.status}});
  const buf = await resp.arrayBuffer();
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (let j = 0; j < bytes.length; j++) bin += String.fromCharCode(bytes[j]);
  return btoa(bin);
}})()
"""
            b64 = beval(fetch_js, timeout=120)
            if b64.startswith("{"): raise RuntimeError(json.loads(b64).get("error", b64))
            vp.write_bytes(base64.b64decode(b64))
            dur = 0.0
            try:
                r = subprocess.run(["ffprobe","-v","quiet","-show_entries","format=duration","-of","default=nw=1:nk=1",str(vp)], capture_output=True, text=True, timeout=10)
                dur = float(r.stdout.strip())
            except: pass
            frames_dir = lives_dir / f"live_{live_idx:02d}_frames"
            frames_dir.mkdir(exist_ok=True)
            subprocess.run(["ffmpeg","-y","-loglevel","error","-i",str(vp),"-vf","fps=5,scale=768:-2","-q:v","2",str(frames_dir/"frame_%03d.jpg")], check=True)
            nf = len(list(frames_dir.glob("frame_*.jpg")))
            rec.update({"type":"live","video_url":video_url,"video_local":str(vp),"video_bytes":vp.stat().st_size,
                        "video_duration":round(dur,2),"frames_dir":str(frames_dir),"frames_count":nf})
            print(f"  [{i:02d}] LIVE {live_idx:02d}: {vp.stat().st_size/1024:.0f}KB {dur:.1f}s -> {nf} frames")
            live_idx += 1
        except Exception as e:
            rec["type"]="live"; rec["video_error"]=str(e)
            print(f"  [{i:02d}] LIVE err: {e}")
            live_idx += 1
    else:
        rec["type"]="cover"
        print(f"  [{i:02d}] static {rec.get('cover_bytes',0)//1024}KB")

    media_records.append(rec)
    time.sleep(0.3)

(note_dir / "note.json").write_text(json.dumps({"meta":meta,"media":media_records}, ensure_ascii=False, indent=2), encoding="utf-8")

live_count = sum(1 for r in media_records if r.get("is_live"))
total_frames = sum(r.get("frames_count",0) for r in media_records)
print(f"\n[OK] {note_id}  Live: {live_count}  Frames: {total_frames}  Static: {len(media_records)-live_count}")
print(f"\nNext: python observe_video.py {note_id} 0")
print(f"FETCH_URL={target_url}")
print(f"FRESH_NOTE_ID={note_id}")