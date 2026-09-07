#!/usr/bin/env python3
"""快速扫描多个搜索关键词，找一周内收藏最高的帖子（有视频或 Live Photo）"""
import json, subprocess, sys, shutil, time, urllib.parse
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

GET_FEEDS = """(() => {
  var s = window.__INITIAL_STATE__;
  if (!s || !s.search) return JSON.stringify([]);
  var f = s.search.feeds;
  if (!f) return JSON.stringify([]);
  var r = f._rawValue || f._value || f;
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
    id: k[0], time: nd.time || 0, img: il.length, live: lp.length,
    type: nd.type || '', title: (nd.title || ''),
  });
})()"""

keywords = [
    "live图 创意玩法教程",
    "live图 创意玩法",
    "实况照片 创意教程",
]

all_results = []

for kw in keywords:
    encoded = urllib.parse.quote(kw)
    url = f"https://www.xiaohongshu.com/search_result?keyword={encoded}&sort=general&type=note&source=web_search_result_notes"
    print(f"\n>>> Searching: {kw}")
    cmd = [node, str(entry), 'browser', 'xhs-fetch', 'open', url]
    rc = run(cmd, timeout=60)[2]
    if rc: print('FAILED'); continue
    time.sleep(6)

    feeds_raw = run([node, str(entry), 'browser', 'xhs-fetch', 'eval', GET_FEEDS], timeout=15)[0]
    feeds = json.loads(feeds_raw)
    feeds.sort(key=lambda x: -x['col'])
    print(f'  {len(feeds)} feeds')

    # Check top 3
    for i, item in enumerate(feeds[:3]):
        nid = item['id']
        detail_url = f"https://www.xiaohongshu.com/explore/{nid}?xsec_token={item['xsec']}&xsec_source=pc_search"
        print(f'  [{i}] {nid} col={item["col"]}...', end=' ', flush=True)
        run([node, str(entry), 'browser', 'xhs-fetch', 'eval',
             'location.href = ' + json.dumps(detail_url)], timeout=30)
        time.sleep(8)
        try:
            d = json.loads(run([node, str(entry), 'browser', 'xhs-fetch', 'eval', DETAIL_JS], timeout=15)[0])
            if d.get('e'): print(f'SKIP({d["e"]})'); continue
            days = (time.time()*1000 - d['time'])/86400000 if d['time'] else 999
            has_media = d['live'] > 0 or d['type'] == 'video'
            mark = ' *** HIT ***' if (days <= 7 and has_media) else ''
            t = d.get('title','')[:50].encode('ascii','replace').decode('ascii')
            print(f'{days:.0f}d LIVE={d["live"]}/{d["img"]} type={d["type"]}{mark} | {t}')
            if days <= 7 and has_media:
                d['col'] = item['col']; d['like'] = item['like']
                d['xsec'] = item['xsec']; d['kw'] = kw; d['days'] = days
                all_results.append(d)
        except: print('err')

print('\n' + '='*60)
print('RESULTS: within 7 days + has media (video or Live Photo)')
all_results.sort(key=lambda x: -x['col'])
for r in all_results:
    url = f"https://www.xiaohongshu.com/explore/{r['id']}?xsec_token={r['xsec']}&xsec_source=pc_search"
    t = r.get('title','')[:60].encode('ascii','replace').decode('ascii')
    print(f'  {r["id"]} col={r["col"]} like={r["like"]} {r["days"]:.0f}d LIVE={r["live"]}/{r["img"]} [{r.get("kw","")}]')
    print(f'    {t}')
    print(f'    {url}')

if all_results:
    best = all_results[0]
    print(f'\nBEST: {best["id"]}')
    print(f'FETCH_URL=https://www.xiaohongshu.com/explore/{best["id"]}?xsec_token={best["xsec"]}&xsec_source=pc_search')
else:
    print('\nNo results found.')