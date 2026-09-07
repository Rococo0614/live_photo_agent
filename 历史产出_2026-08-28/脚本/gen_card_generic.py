#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通用玩法分析卡片 HTML 生成器（替代硬编码 gen_card.py）。

从 数据/xhs_notes/<note_id>/note.json 动态读取标题/互动/Live 视频，
按 playbook-card-viz skill 的莫兰迪规范生成交付 HTML。
视频与 HTML 同放 交付/ 目录，用相对路径引用，可直接分享。

用法：
  python gen_card_generic.py <note_id> [live_index=0]
"""
import json
import sys
from pathlib import Path

# Windows GBK 控制台打印含 emoji 的文件名/标题会崩，统一转 UTF-8
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

BASE = Path(__file__).resolve().parent.parent
NOTE_DIR = BASE / "数据/xhs_notes"
DELIVER = BASE / "交付"
DELIVER.mkdir(exist_ok=True)

if len(sys.argv) < 2:
    print("用法: python gen_card_generic.py <note_id> [live_index=0]")
    sys.exit(1)
NOTE_ID = sys.argv[1]
LIVE_IDX = int(sys.argv[2]) if len(sys.argv) > 2 else 0

note_json = NOTE_DIR / NOTE_ID / "note.json"
if not note_json.exists():
    print(f"未找到 {note_json}")
    sys.exit(1)
data = json.loads(note_json.read_text("utf-8"))
meta = data.get("meta", {})
media = data.get("media", [])
# 可分析媒体：Live 视频或普通视频（创意玩法教程帖常为视频教程）
lives = [m for m in media if m.get("is_live") or m.get("type") == "video"]

if LIVE_IDX >= len(lives):
    print(f"media_{LIVE_IDX:02d} 不存在，共 {len(lives)} 个可分析媒体")
    sys.exit(1)
live = lives[LIVE_IDX]
video_name = Path(live.get("video_local", "")).name or f"live_{LIVE_IDX:02d}.mp4"

title = meta.get("title") or NOTE_ID
inter = meta.get("interact", {})
liked = inter.get("liked", 0)
collected = inter.get("collected", 0)
comment = inter.get("comment", 0)
shared = inter.get("shared", 0)
author = (meta.get("author") or {}).get("nickname", "")
tags = meta.get("tags", [])
camera = meta.get("camera_detected", "")
desc = (meta.get("desc") or "")[:120]
url = meta.get("url", "")

# 收藏/点赞比
ratio = round(collected / liked * 100) if liked else 0

# ----- 安全文件名 -----
safe = title.replace("/", "_").replace("\\", "_").replace(" ", "") \
            .replace(":", "").replace("*", "").replace("?", "") \
            .replace("\"", "").replace("<", "").replace(">", "") \
            .replace("|", "")[:30]
out_name = f"{safe}_玩法分析卡片.html"
dest = DELIVER / out_name

lines = []
a = lines.append

a('''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>''' + title + ''' - 玩法分析卡片</title>
<style>
:root {
    --ink: #2E2A38; --ink2: #3D3844; --muted: #7A748A; --pale: #ADA8B8;
    --bg: #F8F6FA; --card-bg: rgba(255,255,255,.75); --border: rgba(180,160,200,.2);
    --grad-accent: linear-gradient(135deg,#7B6B8C,#5A7B8C);
}
* { margin:0; padding:0; box-sizing:border-box; }
body { font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif; background:var(--bg); color:var(--ink); line-height:1.7; }
.container { max-width:960px; margin:0 auto; padding:0 20px; }
@keyframes fadeUp { 0%{opacity:0;transform:translateY(24px);} 100%{opacity:1;transform:translateY(0);} }
.hv { opacity:0; } .hv.vis { animation:fadeUp .7s cubic-bezier(.16,1,.3,1) forwards; }
.sm { font-size:10px; letter-spacing:.12em; text-transform:uppercase; color:var(--pale); margin-bottom:8px; }
.ki { font-size:15px; color:var(--ink); line-height:1.8; margin-bottom:12px; }
.ki b { font-weight:600; color:var(--ink2); }
.vd { font-size:17px; font-weight:600; color:var(--ink2); margin:16px 0 8px; }
.st { font-size:12.5px; color:var(--muted); line-height:1.7; }
.card { background:var(--card-bg); border:1px solid var(--border); border-radius:16px; padding:32px; margin-bottom:24px; backdrop-filter:blur(12px); }
.hero { background:linear-gradient(135deg,#F2EDF5,#EAF0F5,#EEF3EC); border-radius:20px; padding:48px 40px; margin-bottom:32px; text-align:center; }
.hero h1 { font-size:26px; font-weight:700; color:var(--ink2); margin-bottom:12px; }
.hero .subtitle { font-size:15px; color:var(--ink); max-width:620px; margin:0 auto; line-height:1.8; }
.hero .meta-row { display:flex; justify-content:center; gap:24px; margin-top:20px; flex-wrap:wrap; }
.hero .meta-item { text-align:center; }
.hero .meta-num { font-size:22px; font-weight:700; color:var(--ink2); }
.hero .meta-label { font-size:11px; color:var(--muted); margin-top:2px; }
.video-wrap { width:100%; aspect-ratio:3/4; overflow:hidden; background:#2A2430; position:relative; border-radius:10px; max-width:320px; margin:0 auto; }
.video-wrap video { position:absolute; top:0; left:0; width:100%; height:100%; object-fit:contain; }
.data-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:16px; margin-top:16px; }
@media (max-width:680px){ .data-grid{grid-template-columns:repeat(2,1fr);} }
.data-card { text-align:center; padding:20px; background:var(--card-bg); border-radius:14px; border:1px solid var(--border); }
.data-card .dc-num { font-size:32px; font-weight:700; color:var(--ink2); }
.data-card .dc-label { font-size:11px; color:var(--muted); margin-top:4px; }
.tag-row { display:flex; flex-wrap:wrap; gap:8px; margin-top:16px; }
.tag-pill { padding:6px 16px; border-radius:20px; font-size:12px; background:rgba(180,160,200,.15); color:var(--ink2); }
.footer { text-align:center; padding:40px 20px; color:var(--pale); font-size:11px; }
.footer a { color:var(--muted); }
</style>
</head>
<body>
<div class="container">
''')

# ===== HERO =====
a('<div class="hero hv"><div class="sm">Playbook Analysis Card</div>')
a('<h1>' + title + '</h1>')
a('<div class="subtitle">' + (desc or '小红书 Live Photo 玩法洞察') + '</div>')
a('<div class="meta-row">')
a('<div class="meta-item"><div class="meta-num">' + str(len(lives)) + '</div><div class="meta-label">Live Photos</div></div>')
a('<div class="meta-item"><div class="meta-num">' + str(liked) + '</div><div class="meta-label">点赞</div></div>')
a('<div class="meta-item"><div class="meta-num">' + str(collected) + '</div><div class="meta-label">收藏</div></div>')
a('<div class="meta-item"><div class="meta-num">' + str(comment) + '</div><div class="meta-label">评论</div></div>')
a('<div class="meta-item"><div class="meta-num">' + str(shared) + '</div><div class="meta-label">分享</div></div>')
a('</div></div>')

# ===== 01 原帖 Live =====
a('<div class="card hv"><div class="sm">01 / 原帖 Live 展示</div>')
a('<div class="ki">原帖共 <b>' + str(len(lives)) + ' 张 Live Photo</b>，作者 <b>' + (author or '未知') + '</b>。' + ('机型识别：<b>' + camera + '</b>' if camera else '') + '</div>')
a('<div class="video-wrap"><video preload="metadata" controls playsinline><source src="' + video_name + '" type="video/mp4"></video></div>')
a('</div>')

# ===== 02 互动数据 =====
a('<div class="card hv" style="background:#FAFAF8;"><div class="sm">02 / 互动数据</div>')
a('<div class="data-grid">')
a('<div class="data-card"><div class="dc-num">' + str(collected) + '</div><div class="dc-label">收藏</div></div>')
a('<div class="data-card"><div class="dc-num">' + str(liked) + '</div><div class="dc-label">点赞</div></div>')
a('<div class="data-card"><div class="dc-num">' + str(comment) + '</div><div class="dc-label">评论</div></div>')
a('<div class="data-card"><div class="dc-num">' + str(ratio) + '%</div><div class="dc-label">收藏/点赞比</div></div>')
a('</div>')
a('<div class="ki" style="margin-top:16px;">收藏/点赞比 <b>' + str(ratio) + '%</b>' + ('，用户收藏意愿较强。' if ratio >= 20 else '，处于常规区间。') + '</div>')
a('</div>')

# ===== 03 标签 =====
a('<div class="card hv" style="background:linear-gradient(160deg,#F5F0F8,#EEF5F2);"><div class="sm">03 / 标签</div>')
a('<div class="tag-row">')
for t in tags:
    a('<span class="tag-pill">#' + t + '</span>')
if not tags:
    a('<span class="st">无标签</span>')
a('</div></div>')

# ===== Footer =====
a('<div class="footer">')
a('<p>Generated by MOSS · ' + str(Path(__file__).stat().st_mtime)[:10] + ' · 小红书 Live Photo 玩法洞察</p>')
a('<p>原帖: <a href="' + url + '">' + title + '</a></p>')
a('</div>')

a('<script>const o=new IntersectionObserver(es=>{es.forEach(e=>{if(e.isIntersecting)e.target.classList.add("vis");});},{threshold:0.08});document.querySelectorAll(".hv").forEach(el=>o.observe(el));</script>')
a('</div></body></html>')

html = '\n'.join(lines)
dest.write_text(html, encoding="utf-8")
print("DONE:", dest)
print("SIZE:", len(html))
