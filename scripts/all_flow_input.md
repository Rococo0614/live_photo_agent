# Live Photo Agent — 完整测试指令手册

## 0. 环境准备（每次新终端执行一次）

```bash
conda activate live_photo_agent

export DASHSCOPE_API_KEY='sk-ws-H.EDDIMHE.XPJN.MEUCIFj-bi6j3oX3ba8uOeEP9ObU9oPqmb5VwpO6WgANKkm3AiEAvDesS6EN6P51MCfDeL3skveLQT2lgk7ls181Xvia8vk'
export DASHSCOPE_WORKSPACE_ID='ws-a6giruup05d0ztyb'

source scripts/use_endpoint_backend.sh \
  "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" \
  "$DASHSCOPE_API_KEY" \
  qwen-omni-turbo

# LangSmith 被动观察（主环境跑时自动上传 trace，在 smith.langchain.com 查看）
export LANGCHAIN_TRACING_V2=true
export LANGCHAIN_API_KEY='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'
export LANGCHAIN_PROJECT=live-photo-agent
```

> 启动服务后访问 https://smith.langchain.com → 项目 `live-photo-agent`，  
> 每次 `agent/execute` 调用都会自动出现一条 trace，可以看到完整执行链路。

---

## 1. 离线相册索引 + VLM 语义填充（无需启动服务）

### 1a. 初次建库（扫描 + 质量信号）
```bash
python -m live_photo_agent.offline_preprocess_cli build \
  --library-root data/live_photo
```

### 1b. VLM 语义富化（读取已有 JSONL，补充语义字段，跳过已完成的行）
```bash
python -m live_photo_agent.offline_preprocess_cli enrich-semantics
```

### 1c. 强制全量重做（忽略缓存和已有语义）
```bash
python -m live_photo_agent.offline_preprocess_cli enrich-semantics --force
```

### 1d. 只测试 5 个素材（调试 VLM prompt / 响应格式）
```bash
python scripts/test_vlm_videos.py \
  data/live_photo/1.jpg \
  data/live_photo/10.jpg \
  data/live_photo/100.jpg \
  data/live_photo/1000.jpg \
  data/live_photo/101.jpg
```

---

## 2. 启动 Agent 服务

```bash
uvicorn live_photo_agent.api:app --host 127.0.0.1 --port 8000
```

健康检查：
```bash
curl -s http://127.0.0.1:8000/health
```

---

## 3. 相册同步（触发扫描 + 增量 VLM 语义富化）

> 等价于 1a + 1b，但通过 HTTP 调用，适合集成到前端或自动化流程。

```bash
curl -s -X POST http://127.0.0.1:8000/api/album/sync \
  -H "Content-Type: application/json" \
  -d '{
    "library_root": "/home/vivo/live_photo_agent/data/live_photo",
    "force": false
  }' | python3 -m json.tool
```

强制全量重建：
```bash
curl -s -X POST http://127.0.0.1:8000/api/album/sync \
  -H "Content-Type: application/json" \
  -d '{
    "library_root": "/home/vivo/live_photo_agent/data/live_photo",
    "force": true
  }' | python3 -m json.tool
```

---

## 4. Agent 执行（用户意图 → 检索 → 编辑计划）

```bash
curl -s -X POST http://127.0.0.1:8000/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "test-user",
    "text": "帮我找出海边日落的 live photo，给我一个朋友圈精选建议",
    "library_root": "/home/vivo/live_photo_agent/data/live_photo",
    "selected_asset_ids": []
  }' | python3 -m json.tool
```

指定素材：
```bash
curl -s -X POST http://127.0.0.1:8000/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "test-user",
    "text": "把这几个 live photo 做成一个流畅的精彩集锦",
    "library_root": "/home/vivo/live_photo_agent/data/live_photo",
    "selected_asset_ids": ["101", "102", "103"]
  }' | python3 -m json.tool
```

---

## 5. 用户反馈 → 更新记忆库和策略库

> 在 `/agent/execute` 之后调用，告知 agent 本次结果是否被接受。
> agent 下次规划时会优先复用高接受率的工具序列。

接受（满意本次结果）：
```bash
curl -s -X POST http://127.0.0.1:8000/api/memory/feedback \
  -H "Content-Type: application/json" \
  -d '{
    "accepted": true,
    "asset_ids": ["101", "102"],
    "comment": "角度和动态都很好"
  }' | python3 -m json.tool
```

拒绝（不满意）：
```bash
curl -s -X POST http://127.0.0.1:8000/api/memory/feedback \
  -H "Content-Type: application/json" \
  -d '{
    "accepted": false,
    "asset_ids": [],
    "comment": "素材选错了，想要人物特写而不是风景"
  }' | python3 -m json.tool
```

---

## 6. 查看当前记忆库状态

```bash
python3 -c "
import json
from pathlib import Path
m = Path('.agent_memory.json')
if m.exists():
    state = json.loads(m.read_text())
    print('session_memory 条数:', len(state.get('session_memory', [])))
    print('strategy_memory 条数:', len(state.get('strategy_memory', [])))
    strategies = state.get('strategy_memory', [])
    for s in sorted(strategies, key=lambda x: x.get('accept_count',0), reverse=True)[:5]:
        print(f\"  intent={s['intent']} accept={s['accept_count']}/{s['run_count']} seq={s['tool_sequence']}\")
else:
    print('记忆库尚未初始化')
"
```

---

## 7. LangGraph Studio 可视化观察窗口（独立 lg_studio 环境）

### 7a. 填写 API Key（只需一次）
编辑项目根目录的 `.env` 文件，填入真实的密钥：
```
LPA_QWEN_AUTH_TOKEN=sk-ws-xxx...
LPA_QWEN_WORKSPACE_ID=ws-xxx...
```

### 7b. 启动 Studio 服务（在 lg_studio 环境中运行）
```bash
conda activate lg_studio
cd /home/vivo/live_photo_agent
/home/vivo/miniconda3/envs/lg_studio/bin/langgraph dev --config langgraph.json
```

启动后访问 **http://127.0.0.1:2024** 即可在浏览器中看到 Agent 执行状态图。

> Studio 和主开发环境共享同一份状态文件（`.album_preprocess_index.jsonl`、`.agent_memory.json`），  
> 因此主环境跑 `agent/execute` 时，Studio 中可实时看到状态变化。

---

## 8. 典型完整测试流程（顺序执行）

```bash
# Step 1: 建库 + 语义富化（只需跑一次，后续增量）
python -m live_photo_agent.offline_preprocess_cli enrich-semantics

# Step 2: 启动服务（另一个终端）
uvicorn live_photo_agent.api:app --host 127.0.0.1 --port 8000

# Step 3: 执行用户请求
curl -s -X POST http://127.0.0.1:8000/agent/execute \
  -H "Content-Type: application/json" \
  -d '{"user_id":"u1","text":"找出有运动动态的 live photo","library_root":"/home/vivo/live_photo_agent/data/live_photo","selected_asset_ids":[]}' \
  | python3 -m json.tool

# Step 4: 告知反馈
curl -s -X POST http://127.0.0.1:8000/api/memory/feedback \
  -H "Content-Type: application/json" \
  -d '{"accepted":true,"comment":"结果符合预期"}' \
  | python3 -m json.tool
```
