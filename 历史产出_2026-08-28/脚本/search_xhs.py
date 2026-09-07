#!/usr/bin/env python3
"""搜索小红书，找一周内收藏量最大的帖子，并输出 URL 给 fetch_xhs.py"""
import json, subprocess, sys, shutil, time
from pathlib import Path

node = shutil.which('node') or r'C:\Program Files\nodejs\node.EXE'
entry = Path.home() / 'AppData/Roaming/npm/node_modules/@jackwener/opencli/dist/src/main.js'
if not entry.exists():
    pkg = Path.home() / 'AppData/Roaming/npm/node_modules/@jackwener/opencli/package.json'
    d = json.loads(pkg.read_text('utf-8'))
    rel = list(d.get('bin', {}).values())[0] if isinstance(d.get('bin'), dict) else d.get('bin', '')
    entry = Path.home() / 'AppData/Roaming/npm/node_modules/@jackwener/opencli' / rel

def run(cmd, timeout=60):
    r = subprocess.run(cmd, capture_output=True, shell=False, timeout=timeout)
    return (r.stdout or b'').decode('utf-8', errors='replace').strip(), (r.stderr or b'').decode('utf-8', errors='replace').strip(), r.returncode

# Open search
search_url = 'https://www.xiaohongshu.com/search_result?keyword=%E5%AE%9E%E5%86%B5%E7%85%A7%E7%89%87+%E5%88%9B%E6%84%8F%E7%8E%A9%E6%B3%95&sort=general&type=note&source=web_search_result_notes'
print('Opening search...')
cmd = [node, str(entry), 'browser', 'xhs-fetch', 'open', search_url]
run(cmd, timeout=60)
time.sleep(6)

# Get feeds with xsec
get_feeds_js = """(() => {
  const s = window.__INITIAL_STATE__;
  if (!s || !s.search) return JSON.stringify([]);
  const f = s.search.feeds;
  if (!f) return JSON.stringify([]);
  const r = f._rawValue || f._value || f;
  if (!Array.isArray(r)) return JSON.stringify([]);
  return JSON.stringify(r.map(function(x) {
    var n = x.noteCard || x;
    var i = n.interactInfo || n.interact_info || {};
    return {
      id: n.noteId || n.note_id || x.id || '',
      xsec: x.xsecToken || '',
      title: (n.displayTitle || n.display_title || ''),
      col: Number(i.collectedCount || i.collected_count || 0),
      like: Number(i.likedCount || i.liked_count || 0),
      type: n.type || '',
    };
  }).filter(function(x) { return x.id.length === 24 && x.xsec; }));
})()"""

cmd = [node, str(entry), 'browser', 'xhs-fetch', 'eval', get_feeds_js]
stdout = run(cmd, timeout=15)[0]
feeds = json.loads(stdout)
feeds.sort(key=lambda x: -x['col'])
print(f'Got {len(feeds)} feeds')

# Check top 6 for within-7-days
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
  return JSON.stringify({
    id: k[0],
    time: nd.time || 0,
    img: il.length,
    live: lp.length,
    type: nd.type || '',
    title: (nd.title || ''),
  });
})()"""

best = None
for i, item in enumerate(feeds[:6]):
    nid = item['id']
    url = f"https://www.xiaohongshu.com/explore/{nid}?xsec_token={item['xsec']}&xsec_source=pc_search"
    print(f'[{i}] {nid} col={item["col"]}...', end=' ', flush=True)
    cmd = [node, str(entry), 'browser', 'xhs-fetch', 'open', url]
    rc = run(cmd, timeout=60)[2]
    if rc != 0:
        print('FAILED')
        time.sleep(8)
        continue
    time.sleep(8)

    cmd = [node, str(entry), 'browser', 'xhs-fetch', 'eval', DETAIL_JS]
    try:
        stdout = run(cmd, timeout=15)[0]
        d = json.loads(stdout)
        if d.get('e'):
            print(f'SKIP({d["e"]})')
            continue
        days_ago = (time.time() * 1000 - d['time']) / 86400000 if d['time'] else 999
        in_week = days_ago <= 7
        mark = ' *** TARGET ***' if in_week else ''
        t = d.get('title', '')[:50].encode('ascii', 'replace').decode('ascii')
        print(f'{days_ago:.0f}d LIVE={d["live"]}/{d["img"]} type={d["type"]}{mark} | {t}')
        if in_week and not best:
            d['col'] = item['col']
            d['like'] = item['like']
            d['xsec'] = item['xsec']
            d['url'] = url
            best = d
    except Exception as e:
        print(f'err: {e}')

if best:
    full_url = f"https://www.xiaohongshu.com/explore/{best['id']}?xsec_token={best['xsec']}&xsec_source=pc_search"
    print(f"\nTARGET: {best['id']}")
    print(f"  title: {best['title']}")
    print(f"  collected: {best['col']}  liked: {best['like']}")
    print(f"  type: {best['type']}  LIVE: {best['live']}/{best['img']}")
    print(f"  days: {best.get('days', 0):.0f}")
    print(f"  URL: {full_url}")
    # Output for fetch_xhs.py
    print(f"\nFETCH_URL={full_url}")
else:
    print("\nNo within-7-days post found in top 6")
    # Print all checked
    print("All top 6 checked. Consider widening search.")