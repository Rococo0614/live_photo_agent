#!/usr/bin/env python3
"""解析 xhs_page.html 里的 INITIAL_STATE，摸清完整数据结构"""
import re
import sys
import json
from pathlib import Path

_default_html = Path(__file__).resolve().parent.parent.parent / "xhs_page.html"
html = open(sys.argv[1] if len(sys.argv) > 1 else _default_html, encoding="utf-8").read()

# 匹配 window.__INITIAL_STATE__ = {...};
m = re.search(r"window\.__INITIAL_STATE__\s*=\s*(\{.*?\})</script>", html, re.DOTALL)
if not m:
    print("未找到 INITIAL_STATE")
    exit(1)

raw = m.group(1).replace("undefined", "null")
try:
    data = json.loads(raw)
except Exception as e:
    print("JSON 解析失败:", e)
    print("片段:", raw[:500])
    exit(1)

print("=== 顶层 keys ===")
print(list(data.keys()))

note = data.get("note", {})
print("\n=== note 顶层 keys ===")
print(list(note.keys())[:20])

ndm = note.get("noteDetailMap", {})
print(f"\n=== noteDetailMap 有 {len(ndm)} 个 note ===")
print("keys:", list(ndm.keys())[:5])

if not ndm:
    exit(0)

first_id = list(ndm.keys())[0]
note_data = ndm[first_id]
print(f"\n=== note_data[{first_id}] 顶层 keys ===")
print(list(note_data.keys()))

nd = note_data.get("note") or note_data
print("\n=== note 详情 keys ===")
print(list(nd.keys())[:40])

print(f"\n标题: {nd.get('title', '')[:100]}")
print(f"正文: {(nd.get('desc') or '')[:400]}")
tags = nd.get("tagList", []) or []
print(f"tags ({len(tags)}): {[t.get('name') for t in tags][:20]}")
ii = nd.get("interactInfo", {}) or {}
print(f"互动: like={ii.get('likedCount')} collect={ii.get('collectedCount')} comment={ii.get('commentCount')} share={ii.get('shareCount')}")
user = nd.get("user", {}) or {}
print(f"作者: {user.get('nickname')}  user_id={user.get('userId')}")

imgs = nd.get("imageList", []) or []
print(f"\n=== imageList 共 {len(imgs)} 项 ===")
for i, im in enumerate(imgs):
    keys = list(im.keys())
    print(f"\n  [{i}] keys={keys}")
    # 特别关注 stream/liveVideo/livePhoto 字段
    for k, v in im.items():
        if any(kw in k.lower() for kw in ["stream", "video", "live"]):
            s = json.dumps(v, ensure_ascii=False)[:300]
            print(f"      {k}: {s}")
    # 静态图 URL
    for k in ("urlDefault", "urlPre", "url", "urlSizeLarge"):
        if k in im:
            print(f"      {k}: {str(im[k])[:120]}")
            break
