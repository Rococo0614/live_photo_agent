# Handoff — 小红书 Live Photo 玩法洞察流水线

> 生成时间：2026-08-14（MOSS）
> 用途：交接项目当前状态、目录结构、运行方式、已知问题与后续事项，供新 session / 协作者快速接手。

---

## 1. 项目一句话

对小红书 Live Photo 的**创意玩法教程**做情报洞察：自动发现热帖 → 抓取 → 云端视觉分析 → 产出分析卡片 + 可视化 HTML，反哺 vivo 影像产品知识库。

**产品方向（2026-08-13 调整）**：洞察目标从「Live 图素材」改为「Live 图的创意玩法教程」。两类都收：① 帖子本身含 Live 视频的教程帖；② 视频/图文教程帖（教怎么做创意效果，本身无 Live）。

---

## 2. 目录结构（工作区：`D:\My Documents\11085203\Documents\小红书收集`）

```
小红书收集/
├── 01_live玩法洞察/                 # 流水线根目录（迁移后的新位置）
│   ├── 脚本/                        # 17 个 .py（run_pipeline.py 编排器）
│   ├── 数据/xhs_notes/<note_id>/    # 抓取原始数据（note.json + lives/videos/covers/frames）
│   ├── 交付/                        # 可视化 HTML + Live/视频 mp4（22 个文件）
│   ├── 分析卡片/                    # A/B 双通道卡片 + 对比摘要 + 观察报告（39+ 个）
│   ├── 运行记录/                    # state.json / urls.json / <日期>.log
│   ├── 运行手册.md                  # 完整运行文档（本 handoff 的详细版）
│   ├── CHANGELOG.md
│   └── 交付.zip                     # 历史打包
├── .agents/skills/                  # 项目级 skill（4 个，workspace 专属）
└── handoff.md                       # 本文件
```

**⚠️ 迁移记录（2026-08-13）**：流水线整体从 `C:\Users\11085203\BlueCode\01_live玩法洞察\` **复制**到当前工作区。旧路径仍保留为备份但**已废弃**，勿再使用。4 个相关 skill 已从全局转为项目级（workspace），全局版本已移除。

---

## 3. 一键运行

```bash
# 全自动（自动搜索发现 → 抓取 → 云端分析 → 双通道卡片 → 生成 HTML）
python "D:\My Documents\11085203\Documents\小红书收集\01_live玩法洞察\脚本\run_pipeline.py"

# 指定一个帖子 URL 直接跑（跳过自动发现）
python 脚本/run_pipeline.py --url "https://www.xiaohongshu.com/explore/<id>?xsec_token=..."

# 只跑到某个阶段
python 脚本/run_pipeline.py --stage fetch    # discover/fetch/analyze/card/viz
```

---

## 4. 流水线五阶段

```
【P1 发现】              【P2 抓取】          【P3 分析】            【P4 卡片】             【P5 可视化】
search_xhs.py      ┐
quick_find.py      ├→ URL ─→ fetch_xhs.py ─→ note.json ─┬→ observe_batch.py(云端qwen) ─→ 观察报告md ─┐
fetch_and_search.py┘            +lives/*.mp4            │→ vision_batch.py(本地Ollama)                ├→ gen_card_generic.py → 交付/*.html
                                 +videos/*.mp4          └→ gen_card_dual.py(A云端+B本地) ─→ A/B卡片+对比┘
                                 +covers/*.jpg
                                 +frames/
```

- 统一接口：`数据/xhs_notes/<note_id>/note.json`，下游脚本全部兼容
- P1 双通道：先 `fetch_and_search.py`（一体式），未命中再回退 `quick_find.py`
- P3 云端：`observe_batch.py` 调 dashscope qwen-vl-max / qwen3-vl-plus
- P4 双通道：云端 A 卡片（原生视频）+ 本地 B 卡片（5 帧抽样）→ 对比摘要
- P5 可视化：`gen_card_generic.py` 从 note.json 动态读标题/互动/媒体生成莫兰迪 HTML

---

## 5. 去重 / 投票机制（编排器核心）

- **幂等**：已抓取过的 `note_id` 自动跳过，不重复烧账号/额度（状态存 `运行记录/state.json`）
- **URL 收集 + 去重投票**：所有发现的帖子 URL 记入 `运行记录/urls.json`（note_id → first_seen/last_seen/votes/status）
- 同一帖子再次被遇到（每天自动搜索都会返回当前收藏最高的热帖）→ **votes +1 并跳过重复抓取/分析**；新帖首次抓取算 1 票
- 判定"是否已抓取过"：`数据/xhs_notes/<id>/note.json` 是否存在
- **容错**：单阶段失败不中断整条链；本地 Ollama 不在时自动降级为纯云端（B 卡片占位）

### 当前票数一览（2026-08-14，来自 urls.json）

| note_id | 帖子 | votes | 状态 |
|---|---|---|---|
| 6a585a5b000000001c00c16f | 10张高清氛围感live实况图拿了留痕 | 6 | done |
| 6a746b5100000000240253b4 | 教大家如何在live照片中实现会动的安洁！ | 7 | done（已分析） |
| 6a784931000000002c0030ce | 让画面动起来居然如此简单…😋😋 | 2 | done（视频教程，已分析） |
| 6a75e726000000003302e461 | — | 2 | done |
| 6a758a3f000000003301fd60 | 邪修p图·全自动修图 | 2 | done（skipped=no_live） |
| 6a7a9e1200000000280319c0 | 珠海紫色晚霞 高清视频 | 1 | done（skipped=no_live） |

---

## 6. 定时调度

BlueCode 自动化面板任务 **「小红书Live玩法洞察-每日自动流水线」**（id `1db21bfa`）：
- **每天 11:00 运行**，sessionMode=continue，status=active
- prompt 已更新为**新路径**（2026-08-13 迁移时同步）
- 每天自动搜索：命中已分析热帖 → 投票 +1 跳过；发现新帖才继续抓取/分析
- 可在 BlueCode 左侧「自动化」面板暂停/修改/手动触发

---

## 7. 前置依赖

1. Chrome 已运行并**登录小红书**
2. `opencli` + Browser Bridge 扩展（`opencli doctor` 验证）
3. `ffmpeg` / `ffprobe` 在 PATH
4. `~/.env` 有 `DASHSCOPE_API_KEY`（云端分析）
5. 本地 Ollama（可选，`qwen2.5vl:7b`）——不在则自动降级纯云端

---

## 8. 项目级 Skill（workspace，`.agents/skills/`）

| Skill | 用途 |
|---|---|
| `live-playbook-insight` | 核心：Live 玩法分析全流程（含 24 条判断纪律、三方交叉验证、破圈四要素） |
| `playbook-card-viz` | 分析卡片 → 莫兰迪 HTML 可视化 |
| `china-platform-scraper` | 小红书/抖音/B站抓取范式（含 DLP 加密环境避坑） |
| `mp4-extractor` | Motion Photo / Live Photo 提取嵌入 MP4 |

**注意**：这些 skill 是 **workspace 级**，仅在当前工作区加载。开新 session 时工作区必须是 `D:\My Documents\11085203\Documents\小红书收集`，否则 skill 不生效。

---

## 9. 已知问题 / 观察

1. **自动发现空转风险**：搜索候选多超 7 天，`days<=7` 过滤后常无目标 → 每天自动跑可能空转（收集 URL + 投票后结束）。**已确认保持现状**：宁缺毋滥，新帖靠手动提供 URL 或等新帖出现。
2. **自动搜索命中无媒体帖**：08-13 连续命中「珠海紫色晚霞」「邪修p图」两条只有封面、无 Live/视频的素材帖，被 `has_media()` 保护跳过。若频繁发生，可在 `quick_find.py`/`fetch_and_search.py` 搜索阶段加"必须含媒体"预过滤。
3. **本地 Ollama 未运行**：P4 自动降级为纯云端（A 卡片 + B 占位）。想跑双通道需先 `ollama serve` + `ollama pull qwen2.5vl:7b`。
4. **`gen_card_generic.py` 只出第一个媒体**：每个帖子只出第一个 Live/视频的卡片，后续可扩展为全部。
5. **封面图 urllib 直连**：CDN 无需登录；若未来加鉴权需改走浏览器 fetch。

---

## 10. 历史修复记录（重点，避免重踩）

**2026-08-13（第二轮：创意玩法教程方向）**
- 搜索关键词改向：`live实况图 氛围感`（素材）→ `live图 创意玩法教程`（教程）
- `fetch_and_search.py` 契约 bug：一体式抓取后不输出 `FETCH_URL=`，已补 `FETCH_URL=` + `FRESH_NOTE_ID=`；`run_pipeline.py` 新增 `extract_fresh_note_id()`
- 重复热帖保护：一体式重抓但 `state` 已 `analyzed_at` → 仅投票不重分析
- 视频教程帖支持：`fetch_xhs.py` 下载 `video_XX.mp4` + 抽帧；`has_live()` 放宽为 `has_media()`（Live 或视频）
- GBK 编码崩溃（第三/四起）：`observe_video.py`、`gen_card_generic.py` 已加 `sys.stdout.reconfigure(encoding="utf-8", errors="replace")`
- `gen_card_dual.py` meta 解析 bug：`json.load(open)` 无 encoding 导致 A/B 卡片 title 一直为空 → 改 `encoding="utf-8"` + 正确取 `data["meta"]`

**2026-08-13（首轮）**
- `fetch_xhs.py` + `run_pipeline.py` GBK 编码崩溃（连锁）：标题含 emoji 时打印崩溃 → 两脚本均加 utf-8 防护
- 全流程回归通过（P1→P5 全链路正常）

**2026-08-11**
- `gen_card_dual.py` emoji 崩溃（`🌐` 触发 GBK 错误）
- `gen_card_dual.py` 本地降级：云端 A 独立写入，B 失败降级占位卡片
- 编排器无 Live 保护：新增 `has_live()` 检查，无 Live 帖记录 `skipped=no_live` 并跳过下游

---

## 11. 待办 / 建议

- [ ] 确认新副本稳定运行一段时间后，删除旧备份目录 `C:\Users\11085203\BlueCode\01_live玩法洞察`
- [ ] （可选）给 `quick_find.py`/`fetch_and_search.py` 加"必须含 Live/视频媒体"预过滤，减少空转
- [ ] （可选）`gen_card_generic.py` 扩展为输出全部媒体卡片
- [ ] （可选）启动本地 Ollama 恢复双通道对比
