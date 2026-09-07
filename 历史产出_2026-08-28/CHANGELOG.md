# fetch_xhs.py 改造说明

## 改造时间

2026-07-16

## 改造目标

将小红书笔记抓取从"裸 HTTP 请求"升级为"浏览器会话代理"，消除账号安全风险，同时保持下游脚本（`observe_batch.py`、`vision_batch.py` 等）零改动。

---

## 一、改造前 vs 改造后

### 改造前（macOS 原版）

```
fetch_xhs.py
  └─ urllib HTTP GET → 解析 window.__INITIAL_STATE__
     ├─ 元信息（标题/正文/tags/互动数/作者）
     ├─ 封面图（urllib 直接下载 CDN URL）
     └─ Live 视频（urllib 下载 stream.h264[0].masterUrl）
```

**问题**：

| 问题 | 说明 |
|------|------|
| **无登录态** | 直接 HTTP GET 小红书页面，`noteDetailMap` 大概率为空，需要手动维护 Cookie |
| **IP 特征明显** | Python urllib 的 TLS 指纹、无浏览器 headers，容易被风控系统识别为爬虫 |
| **封号风险高** | 高频请求 + 非浏览器特征 → 账号可能被限流或封禁 |
| **CDN 鉴权** | Live 视频的 `sns-video-qc.xhscdn.com` 域名需要浏览器 Cookie 才能下载，裸 HTTP 返回 403 |
| **硬编码 macOS 路径** | `/Users/11085097/Documents/bluecode/...`，无法跨平台 |

### 改造后（Windows opencli 版）

```
fetch_xhs.py
  ├─ opencli browser open <URL>         ← 用已登录 Chrome 打开页面
  ├─ opencli browser eval <JS>          ← 读 window.__INITIAL_STATE__
  │   ├─ 元信息（直接从 Vue 状态树取，100% 完整）
  │   ├─ 封面图 URL → urllib 下载（CDN 不需要登录）
  │   └─ Live 视频 URL → 浏览器 fetch() 下载（带 Cookie）
  └─ ffmpeg 抽帧（fps=5）
```

---

## 二、账号安全改进

这是本次改造最核心的价值。逐条说明：

### 2.1 请求全部走已登录 Chrome

- **之前**：Python urllib 裸发 HTTP，没有 Cookie，没有浏览器指纹
- **现在**：所有小红书域下的请求都由 Chrome 发起（opencli 通过 Browser Bridge 扩展控制浏览器），天然携带：
  - 登录 Cookie（`web_session`、`a1` 等）
  - 浏览器 TLS 指纹（JA3）
  - 正常的 User-Agent、Accept-Language、Sec-* 等 headers
  - WebSocket / EventSource 等长连接心跳

**效果**：在小红书服务端看来，这就是一个正常用户在 Chrome 里浏览笔记，不是爬虫。

### 2.2 无额外网络指纹

- **之前**：Python `urllib` 的 TLS 握手特征与浏览器完全不同，小红书的反爬系统（风控团队维护）可以轻易识别
- **现在**：opencli 不注入任何自定义 headers，不修改 TLS 参数，完全复用 Chrome 的原生网络栈

### 2.3 请求频率自然

- **之前**：Python 脚本可以瞬间并发 N 个请求，时间间隔完全一致（机器特征）
- **现在**：每个 Live 视频下载之间有 `time.sleep(0.3)` 间隔；视频下载本身受浏览器 fetch 带宽限制，速度与人工浏览一致

### 2.4 无 API 签名逆向

- **之前**：要正常工作需要逆向小红书的 API 签名算法（`x-s`、`x-t`、`x-s-common` 等），这些参数每次发版都可能变化
- **现在**：完全不碰 API 签名——浏览器自己处理所有请求签名，opencli 只读取页面已渲染好的 `window.__INITIAL_STATE__`

### 2.5 封号风险对比

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| 请求来源 | Python urllib | Chrome 浏览器 |
| Cookie | 无/手动维护 | 自动携带登录态 |
| TLS 指纹 | Python 特征 | Chrome 特征 |
| API 签名 | 需逆向 | 浏览器原生处理 |
| 频率控制 | 无 | sleep(0.3s) + 自然带宽 |
| 封号风险 | **高** | **极低**（与正常浏览无异） |

---

## 三、技术修改清单

### 3.1 路径全部相对化（12 个脚本）

所有脚本中的硬编码 macOS 绝对路径统一替换为：

```python
BASE = Path(__file__).resolve().parent.parent  # 01_live玩法洞察/
WORK_ROOT = BASE / "数据/xhs_notes"
```

涉及脚本：`fetch_xhs.py`、`observe_video.py`、`observe_batch.py`、`gen_card_dual.py`、`vision_batch.py`、`model_compare.py`、`model_compare_batch.py`、`compare_cloud_vs_local.py`、`test_dashscope_video.py`、`test_qwen3_video.py`、`test_video_input.py`、`inspect_xhs.py`

### 3.2 fetch_xhs.py 完全重写

核心变化：

```
旧：urllib.request.urlopen(URL) → re.search(r"__INITIAL_STATE__") → json.loads
新：opencli browser open + eval → 直接读 window.__INITIAL_STATE__ → json.loads
```

视频下载：

```
旧：urllib.request.urlopen(stream.h264[0].masterUrl)
新：浏览器内 fetch(url) → arrayBuffer → base64 → Python base64.b64decode → 写文件
```

### 3.3 Windows 兼容性修复

- `opencli.cmd` 无法直接通过 `subprocess.run(["opencli", ...])` 调用（需 `.cmd` 后缀 + 绕过 cmd.exe 的 `&` 解析）
- 解决方案：`subprocess.run(["node", "path/to/opencli/dist/src/main.js", ...])`，直接调用 Node.js 入口，完全绕开 cmd.exe
- 控制台输出移除所有非 GBK 字符（`✓` → `[OK]`，`✗` → `[FAIL]`）

---

## 四、前置依赖

使用前需一次性完成以下配置：

```bash
# 1. 安装 opencli（全局）
npm install -g @jackwener/opencli

# 2. Chrome 安装 Browser Bridge 扩展
#    Chrome Web Store 搜索 "OpenCLI" 或访问:
#    https://chromewebstore.google.com/detail/opencli/ildkmabpimmkaediidaifkhjpohdnifk

# 3. 确保 Chrome 已运行并登录小红书

# 4. 验证环境
opencli doctor
# 应显示: [OK] Daemon / [OK] Extension / [OK] Connectivity

# 5. ffmpeg 在 PATH 中（用于视频抽帧）
```

---

## 五、用法

```bash
cd C:\Users\11085203\BlueCode
python 01_live玩法洞察\脚本\fetch_xhs.py "https://www.xiaohongshu.com/explore/<note_id>?xsec_token=..."
```

输出目录结构：

```
数据/xhs_notes/<note_id>/
├── note.json          ← 元信息 + 媒体列表（下游脚本的输入）
├── covers/
│   ├── cover_00.jpg   ← 封面图
│   └── ...
├── lives/
│   ├── live_00.mp4         ← Live Photo 视频
│   ├── live_00_frames/     ← ffmpeg 抽帧（frame_001.jpg ...）
│   └── ...
```

---

## 六、下游兼容性

`note.json` 结构与原版完全一致，以下脚本无需任何修改即可运行：

- `observe_video.py` — 云端 qwen3-vl-plus 单视频视觉观察
- `observe_batch.py` — 批量云端观察
- `vision_batch.py` — 本地 Ollama 批量视觉分析
- `gen_card_dual.py` — 双通道分析卡片生成
- `model_compare.py` / `model_compare_batch.py` — 模型对比
- `playbook-card-viz` skill — 分析卡片 → HTML 可视化

---

## 七、已知局限

1. **封面图仍用 urllib 直接下载**：封面 CDN（`sns-*.xhscdn.com` 图片域名）目前不需要登录态，urllib 可正常下载。如果未来 CDN 加鉴权，改为 `browser_download()` 即可。
2. **浏览器 session 复用**：多次运行共用同一个 `xhs-fetch` session，不会重复打开页面。如需重置：`opencli browser xhs-fetch close`
3. **大文件限制**：`browser_download()` 用 base64 传数据，单文件建议不超过 50MB。Live Photo 通常 0.5-2MB，完全在范围内。
4. **仅测试了 Live Photo 图文帖**：普通视频帖（`type: "video"`）的下载逻辑保留在代码中但未经测试。
