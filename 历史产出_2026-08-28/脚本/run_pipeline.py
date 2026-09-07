#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""小红书 Live Photo 玩法洞察 —— 全自动流水线编排器。

把现有 16 个脚本按「发现 → 抓取 → 分析 → 出卡片」串成一条可定时重复运行的链路。

阶段：
  P1 发现    search_xhs.py / quick_find.py / fetch_and_search.py  → 输出 FETCH_URL
  P2 抓取    fetch_xhs.py <url>                                   → 数据/xhs_notes/<id>/note.json
  P3 分析    云端 observe_batch.py <id>  (Ollama 在则叠加 vision_batch.py)
  P4 出卡片  gen_card_dual.py <id> <live>  (云端+本地双通道)
  P5 可视化  gen_card_generic.py <id> <live>  → 交付/<玩法名>_卡片.html

设计原则：
  - 幂等：已抓取过的 note_id 跳过（不重复烧账号/额度）
  - 容错：单阶段失败不中断整条链，出日志；Ollama 不在自动降级纯云端
  - 全程内存/子进程调用，不二次读自己写出的中间文件（DLP 安全）
  - 日志写 运行记录/<date>.log，状态写 运行记录/state.json

用法：
  python run_pipeline.py                 # 自动搜索发现 + 全流程
  python run_pipeline.py --url <url>     # 指定一个 URL 直接跑
  python run_pipeline.py --dry-run       # 只打印将要执行的步骤，不实际跑
  python run_pipeline.py --stage fetch   # 只跑到某个阶段
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time

# Windows GBK 控制台打印 emoji/替换字符会崩，统一转 UTF-8（与 gen_card_dual.py 同款防护）
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass
from datetime import datetime
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent
SCRIPTS = BASE / "脚本"
WORK_ROOT = BASE / "数据/xhs_notes"
OUT_DIR = BASE / "分析卡片"
DELIVER = BASE / "交付"
RUN_LOG = BASE / "运行记录"
STATE_FILE = RUN_LOG / "state.json"
URLS_FILE = RUN_LOG / "urls.json"
LOG_FILE = None  # set in main

# 阶段顺序
STAGES = ["discover", "fetch", "analyze", "card", "viz"]


# ============ 工具 ============
def log(msg: str):
    line = f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line)
    if LOG_FILE:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")


def run_script(name: str, args: list[str], timeout: int = 900) -> subprocess.CompletedProcess:
    """以子进程运行 脚本/<name>.py，捕获输出。"""
    cmd = [sys.executable, str(SCRIPTS / name)] + [str(a) for a in args]
    log(f"$ python {name} {' '.join(str(a) for a in args)}")
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout)


def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text("utf-8"))
        except Exception:
            pass
    return {"done_notes": {}, "runs": []}


def load_urls() -> dict:
    """URL 收集清单：note_id -> {url, first_seen, votes, last_seen, status}"""
    if URLS_FILE.exists():
        try:
            return json.loads(URLS_FILE.read_text("utf-8"))
        except Exception:
            pass
    return {}


def save_urls(urls: dict):
    URLS_FILE.parent.mkdir(parents=True, exist_ok=True)
    URLS_FILE.write_text(json.dumps(urls, ensure_ascii=False, indent=2), encoding="utf-8")


def record_url(url: str, urls: dict) -> str:
    """把发现的 URL 记入收集清单。返回 note_id。
    仅负责收集/更新 last_seen，不投票（投票统一在 stage_fetch 计票）。"""
    nid = note_id_from_url(url)
    if nid:
        if nid in urls:
            urls[nid]["last_seen"] = datetime.now().isoformat()
            log(f"[URL] {nid} 已收集过（更新时间），本次未计票")
        else:
            urls[nid] = {
                "url": url,
                "first_seen": datetime.now().isoformat(),
                "last_seen": datetime.now().isoformat(),
                "votes": 0,
                "status": "new",
            }
            log(f"[URL] 新增收集: {nid}")
        save_urls(urls)
    return nid


def save_state(state: dict):
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def ollama_alive(timeout: int = 3) -> bool:
    """检测本地 Ollama 是否在跑（决定是否启用本地通道）。"""
    try:
        import urllib.request
        with urllib.request.urlopen("http://localhost:11434/api/tags", timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False


def extract_fetch_url(stdout: str) -> str | None:
    """从脚本输出里解析 FETCH_URL=..."""
    m = re.search(r"FETCH_URL=(\S+)", stdout)
    return m.group(1) if m else None


def extract_fresh_note_id(stdout: str) -> str | None:
    """从脚本输出里解析 FRESH_NOTE_ID=...（fetch_and_search 一体式刚抓的新帖 id）。"""
    m = re.search(r"FRESH_NOTE_ID=(\S+)", stdout)
    return m.group(1) if m else None


def note_id_from_url(url: str) -> str | None:
    m = re.search(r"/(?:explore|discovery/item)/([0-9a-z]+)", url)
    return m.group(1) if m else None


# ============ 阶段实现 ============
def stage_discover(mode: str, url: str | None, urls: dict) -> tuple[str | None, str | None]:
    """P1 发现：返回 (目标 URL, fresh_note_id)。
    优先用给定 url；否则自动搜索。
    fresh_note_id：fetch_and_search 一体式刚抓的新帖 id（非 None 表示该帖已抓取，无需再跑 P2 抓取）。
    发现的 URL 一律记入收集清单（record_url），重复帖自动投票。"""
    if url:
        record_url(url, urls)
        log(f"[P1] 使用给定 URL: {url}")
        return url, None
    # 自动搜索：优先 fetch_and_search（一体式，最稳），失败退回 quick_find
    for name in ("fetch_and_search.py", "quick_find.py"):
        if not (SCRIPTS / name).exists():
            continue
        log(f"[P1] 运行 {name} 自动搜索发现...")
        try:
            r = run_script(name, [], timeout=1800)
        except subprocess.TimeoutExpired:
            log(f"[P1] {name} 超时，尝试下一个")
            continue
        u = extract_fetch_url(r.stdout)
        if u:
            fresh = extract_fresh_note_id(r.stdout)
            log(f"[P1] 发现目标: {u}" + (f"（一体式新抓: {fresh}）" if fresh else ""))
            record_url(u, urls)
            return u, fresh
        log(f"[P1] {name} 未找到目标（{r.returncode}）")
    log("[P1] 自动搜索未发现新帖子")
    return None, None


def stage_fetch(url: str, state: dict, urls: dict, fresh_note_id: str | None = None) -> tuple[str | None, bool]:
    """P2 抓取：返回 (note_id, is_new)。is_new=True 表示本次真正新抓取。
    已抓取过（note.json 已存在）→ 投票 +1 并跳过，不重复抓取。
    fresh_note_id：fetch_and_search 一体式刚抓的新帖 id，直接视为新帖（跳过「已抓取过」误判）。"""
    nid = note_id_from_url(url)
    note_dir = WORK_ROOT / nid if nid else None
    # 一体式刚抓的新帖：note.json 已由 fetch_and_search 写入，视为新帖
    if fresh_note_id and nid == fresh_note_id:
        if nid:
            urls[nid] = _bump_vote(urls, nid, url, status="done")
            # 若该帖已完整分析过（analyzed_at 存在）→ 重复热帖，仅投票不重分析
            prev = state.get("done_notes", {}).get(nid, {})
            if prev.get("analyzed_at"):
                log(f"[P2] {nid} 一体式重抓但已分析过，投票 +1（共 {urls[nid]['votes']} 票），跳过重复分析")
                return nid, False
            state["done_notes"][nid] = {"fetched_at": datetime.now().isoformat(), "url": url}
            log(f"[P2] {nid} 一体式新抓，投票 +1（共 {urls[nid]['votes']} 票），跳过重复抓取")
        return nid, True
    if note_dir and note_dir.exists() and (note_dir / "note.json").exists():
        # 已抓取过：投票 + 跳过，不重复抓取
        if nid:
            urls[nid] = _bump_vote(urls, nid, url, status="done")
            log(f"[P2] {nid} 已抓取过，投票 +1（共 {urls[nid]['votes']} 票），跳过本次抓取")
        return nid, False
    r = run_script("fetch_xhs.py", [url], timeout=1800)
    # 抓取成功判断：note.json 是否生成
    if nid and (WORK_ROOT / nid / "note.json").exists():
        log(f"[P2] 抓取完成: {nid}")
        state["done_notes"][nid] = {"fetched_at": datetime.now().isoformat(), "url": url}
        urls[nid] = _bump_vote(urls, nid, url, status="done")
        save_urls(urls)
        return nid, True
    log(f"[P2] 抓取失败: {r.returncode}\n{r.stdout[-500:]}\n{r.stderr[-500:]}")
    return None, False


def _bump_vote(urls: dict, nid: str, url: str, status: str) -> dict:
    """计票：遇到一次 votes+1。返回更新后的该条记录。"""
    now = datetime.now().isoformat()
    if nid in urls:
        urls[nid]["votes"] = urls[nid].get("votes", 0) + 1
        urls[nid]["last_seen"] = now
        urls[nid]["status"] = status
    else:
        urls[nid] = {"url": url, "first_seen": now, "last_seen": now,
                     "votes": 1, "status": status}
    save_urls(urls)
    return urls[nid]


def has_media(nid: str) -> bool:
    """检查该 note 是否含可分析媒体（Live 视频或普通视频）。
    创意玩法教程帖常为视频/图文教程（无 Live），同样值得分析。"""
    note_json = WORK_ROOT / nid / "note.json"
    if not note_json.exists():
        return False
    try:
        data = json.loads(note_json.read_text("utf-8"))
        return any(m.get("is_live") or m.get("type") == "video"
                   for m in data.get("media", []))
    except Exception:
        return False


def stage_analyze(nid: str, state: dict):
    """P3 分析：云端观察（必跑）+ 本地 Ollama（可选）。"""
    # 云端观察报告
    r = run_script("observe_batch.py", [nid, "3"], timeout=3600)
    log(f"[P3] 云端观察完成 rc={r.returncode}")
    # 本地 Ollama（可选）
    if ollama_alive():
        log("[P3] 检测到本地 Ollama，叠加本地视觉分析...")
        run_script("vision_batch.py", [nid], timeout=3600)
    else:
        log("[P3] 本地 Ollama 未运行，跳过本地通道（降级为纯云端）")
    state["done_notes"][nid]["analyzed_at"] = datetime.now().isoformat()


def stage_card(nid: str, state: dict):
    """P4 出分析卡片：对前 N 个可分析媒体（Live/视频）跑双通道。Ollama 不在则只剩云端。"""
    note_dir = WORK_ROOT / nid
    if not (note_dir / "note.json").exists():
        log(f"[P4] {nid} 无 note.json，跳过")
        return
    data = json.loads((note_dir / "note.json").read_text("utf-8"))
    lives = [m for m in data.get("media", []) if m.get("is_live") or m.get("type") == "video"]
    if not lives:
        log(f"[P4] {nid} 无 Live/视频，跳过")
        return
    top_n = min(3, len(lives))
    for i in range(top_n):
        r = run_script("gen_card_dual.py", [nid, str(i)], timeout=1800)
        if r.returncode != 0:
            log(f"[P4] live_{i:02d} 分析失败 rc={r.returncode}")
    state["done_notes"][nid]["card_at"] = datetime.now().isoformat()


def stage_viz(nid: str, state: dict):
    """P5 可视化：生成交付 HTML。"""
    note_dir = WORK_ROOT / nid
    if not (note_dir / "note.json").exists():
        return
    data = json.loads((note_dir / "note.json").read_text("utf-8"))
    lives = [m for m in data.get("media", []) if m.get("is_live") or m.get("type") == "video"]
    if not lives:
        return
    # 用第一个可分析媒体出卡片（后续可扩展为全部）
    r = run_script("gen_card_generic.py", [nid, "0"], timeout=600)
    log(f"[P5] HTML 生成 rc={r.returncode}")
    state["done_notes"][nid]["viz_at"] = datetime.now().isoformat()


# ============ 主流程 ============
def main():
    global LOG_FILE
    ap = argparse.ArgumentParser(description="小红书 Live 玩法洞察全自动流水线")
    ap.add_argument("--url", help="指定一个帖子 URL（跳过自动发现）")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不执行")
    ap.add_argument("--stage", choices=STAGES, default="viz",
                    help="跑到哪个阶段为止（默认跑完）")
    args = ap.parse_args()

    RUN_LOG.mkdir(parents=True, exist_ok=True)
    today = datetime.now().strftime("%Y-%m-%d")
    LOG_FILE = RUN_LOG / f"{today}.log"
    state = load_state()
    urls = load_urls()

    log("=" * 60)
    log(f"小红书 Live 玩法洞察流水线启动  {datetime.now().isoformat()}")
    log(f"  dry_run={args.dry_run}  up_to_stage={args.stage}")

    # P1 发现
    if args.stage == "discover":
        url, _fresh = stage_discover("auto", args.url, urls)
        log(f"发现结果: {url}")
        save_urls(urls)
        return
    url, fresh_note_id = stage_discover("auto", args.url, urls)
    if not url:
        log("无目标帖子，本次结束")
        save_state(state); save_urls(urls)
        return

    # P2 抓取（含去重投票：已抓取过则投票+1并跳过）
    if args.stage == "fetch":
        nid, _ = stage_fetch(url, state, urls, fresh_note_id); save_state(state); save_urls(urls); return
    nid, is_new = stage_fetch(url, state, urls, fresh_note_id)
    if not nid:
        log("抓取失败，终止")
        save_state(state); save_urls(urls); return
    save_urls(urls)

    # 去重：若该帖已抓取过（重复热帖，每天都会被搜到），投票后直接结束，不重复分析
    if not is_new:
        log(f"[去重] {nid} 已抓取过，仅投票，跳过本次下游分析")
        save_state(state); save_urls(urls)
        return

    # 无媒体保护：该帖没有 Live/视频（仅封面/图文）则不适合玩法分析，记录状态并结束
    if not has_media(nid):
        log(f"[跳过] {nid} 无 Live/视频（仅封面/图文），跳过下游分析")
        if nid in state.get("done_notes", {}):
            state["done_notes"][nid]["skipped"] = "no_media"
        save_state(state); save_urls(urls)
        return

    # P3 分析
    if args.stage == "analyze":
        stage_analyze(nid, state); save_state(state); return
    stage_analyze(nid, state)

    # P4 卡片
    if args.stage == "card":
        stage_card(nid, state); save_state(state); return
    stage_card(nid, state)

    # P5 可视化
    stage_viz(nid, state)

    save_state(state)
    log("=" * 60)
    log("流水线完成")
    log(f"状态: {STATE_FILE}")
    log(f"日志: {LOG_FILE}")


if __name__ == "__main__":
    main()
