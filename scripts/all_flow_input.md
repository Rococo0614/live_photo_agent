# Live Photo Agent — 完整测试指令手册

## 0. 环境准备（每次新终端执行一次）

```bash
cd live_photo_agent


conda activate live-photo-agent

export DASHSCOPE_API_KEY='sk-ws-H.EDDIMHE.XPJN.MEUCIFj-bi6j3oX3ba8uOeEP9ObU9oPqmb5VwpO6WgANKkm3AiEAvDesS6EN6P51MCfDeL3skveLQT2lgk7ls181Xvia8vk'
export DASHSCOPE_WORKSPACE_ID='ws-a6giruup05d0ztyb'

export LANGCHAIN_TRACING_V2=true
export LANGCHAIN_API_KEY='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'
export LANGCHAIN_PROJECT=live-photo-agent

source scripts/use_endpoint_backend.sh \
  "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" \
  "$DASHSCOPE_API_KEY" \
  qwen-omni-turbo

/home/vivo/live_photo_agent/scripts/server_start.sh
/home/vivo/live_photo_agent/scripts/server_stop.sh
```

> 启动服务后访问 https://smith.langchain.com → 项目 `live-photo-agent`，  
> 每次 `agent/execute` 调用都会自动出现一条 trace，可以看到完整执行链路。
>
> **这几行 LangSmith 相关的 export 现在是可选的。** 项目已经内置了不依赖外部服务的本地
> 可观测面板：浏览器打开 `http://127.0.0.1:8000/` 就能看到「流程阶段」「规划与执行细粒度轨迹」
> 「人工反馈闭环」三个面板；也可以用 `GET /api/runs/recent` 拉取最近的运行记录（见第 5 节）。
> 不想配 LangSmith 的话可以跳过 `LANGCHAIN_*` 这三行，其余步骤完全不受影响。


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

## 5. 人工反馈闭环 → 打回重来 → 才计入记忆

> **重要变化**：现在只要 `/agent/execute` 产生了真实的工具执行结果（非澄清），
> 这条记录会先以「待确认」状态写入 `session_memory`（`accepted: null`），
> **`multimodal_memory`（资产记忆）和 `strategy_memory`（策略记忆）都不会立即写入**，
> 必须显式调用 `/api/memory/feedback` 并带上 `run_id` 才会真正落地。
> 打回（`accepted:false`）不会污染资产记忆，只会给 `strategy_memory` 记一次「被拒绝」；
> 想要修正结果，应该带着打回原因重新调用 `/agent/execute`（见 5b），直到人工点「通过」为止。

### 5a. 执行一次请求并取出 run_id

```bash
RESPONSE=$(curl -s -X POST http://127.0.0.1:8000/agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "test-user",
    "text": "找出有运动动态的 live photo",
    "library_root": "/home/vivo/live_photo_agent/data/live_photo",
    "selected_asset_ids": []
  }')
echo "$RESPONSE" | python3 -m json.tool
RUN_ID=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin)['context']['graph_observability']['run_id'])")
echo "run_id=$RUN_ID"
```

验证这一轮确实处于「待确认」状态（`accepted` 应为 `null`）：
```bash
python3 -c "
import json
state = json.loads(open('.agent_memory.json').read())
entry = state['session_memory'][-1]
print('run_id:', entry.get('run_id'))
print('accepted:', entry.get('accepted'))
print('requires_feedback:', entry.get('requires_feedback'))
"
```

### 5b. 打回（不满意）→ 带反馈重新生成 → 通过

打回，只记录反馈，不写入资产记忆：
```bash
curl -s -X POST http://127.0.0.1:8000/api/memory/feedback \
  -H "Content-Type: application/json" \
  -d "{\"run_id\": \"$RUN_ID\", \"accepted\": false, \"comment\": \"素材选错了，想要人物特写而不是风景\"}" \
  | python3 -m json.tool
# 期望看到 committed_to_memory: false
```

带着打回原因重新调用 `/agent/execute`（`retry_feedback` 会被拼进 planner 看到的文本里）：
```bash
RETRY=$(curl -s -X POST http://127.0.0.1:8000/agent/execute \
  -H "Content-Type: application/json" \
  -d "{\"user_id\":\"test-user\",\"text\":\"找出有运动动态的 live photo\",\"library_root\":\"/home/vivo/live_photo_agent/data/live_photo\",\"selected_asset_ids\":[],\"retry_feedback\":\"素材选错了，想要人物特写而不是风景\",\"retry_of_run_id\":\"$RUN_ID\"}")
echo "$RETRY" | python3 -m json.tool
NEW_RUN_ID=$(echo "$RETRY" | python3 -c "import json,sys; print(json.load(sys.stdin)['context']['graph_observability']['run_id'])")
echo "new_run_id=$NEW_RUN_ID"
```

满意了，通过（此时才会写入 `multimodal_memory` 并给 `strategy_memory` 记一次「接受」）：
```bash
curl -s -X POST http://127.0.0.1:8000/api/memory/feedback \
  -H "Content-Type: application/json" \
  -d "{\"run_id\": \"$NEW_RUN_ID\", \"accepted\": true, \"asset_ids\": [\"101\", \"102\"], \"comment\": \"这次符合预期\"}" \
  | python3 -m json.tool
# 期望看到 committed_to_memory: true
```

> 不传 `run_id` 也可以（会回退到「最近一条未确认的记录」），但强烈建议始终带上 `run_id`，
> 避免多轮并发时确认错记录。

### 5c. 查看本地运行轨迹（LangSmith 的本地替代）

```bash
curl -s "http://127.0.0.1:8000/api/runs/recent?limit=5" | python3 -m json.tool
```

每条记录包含 `run_id`、`route_reason`、`plan_intent`、`tool_sequence`、细粒度 `trace`（规划/校验/重规划/执行各节点事件）和 `replay_snapshot`。

### 5d. 用浏览器 UI 测试整套闭环（推荐，最直观）

1. 打开 `http://127.0.0.1:8000/`。
2. 勾选几个素材，输入需求，点击「开始生成」。
3. 依次查看「大脑规划 · 意图理解」「流程阶段」「规划与执行细粒度轨迹」三个面板。
4. 在「人工反馈闭环」面板：
   - 不满意 → 填写打回原因 → 点「❌ 打回」→ 再点「🔁 根据反馈重新生成」→ 重复直到满意。
   - 满意 → 点「✅ 通过（计入记忆）」，状态徽章会变成「已通过 · 已计入策略/资产记忆」。

---

## 6. 查看当前记忆库状态

```bash
python3 -c "
import json
from pathlib import Path
m = Path('.agent_memory.json')
if m.exists():
    state = json.loads(m.read_text())
    session = state.get('session_memory', [])
    pending = [e for e in session if e.get('requires_feedback') and not e.get('_feedback_recorded')]
    print('session_memory 条数:', len(session))
    print('  其中待人工反馈确认:', len(pending), [e.get('run_id') for e in pending])
    print('multimodal_memory 条数（仅通过后才会写入）:', len(state.get('multimodal_memory', [])))
    print('strategy_memory 条数:', len(state.get('strategy_memory', [])))
    strategies = state.get('strategy_memory', [])
    for s in sorted(strategies, key=lambda x: x.get('accept_count',0), reverse=True)[:5]:
        print(f\"  intent={s['intent']} accept={s['accept_count']}/{s['run_count']} seq={s['tool_sequence']}\")
else:
    print('记忆库尚未初始化')
"
```

---

## 7. 典型完整测试流程（顺序执行）

```bash
# Step 1: 建库 + 语义富化（只需跑一次，后续增量）
python -m live_photo_agent.offline_preprocess_cli enrich-semantics

# Step 2: 启动服务（另一个终端）
uvicorn live_photo_agent.api:app --host 127.0.0.1 --port 8000

# Step 3: 执行用户请求，取出 run_id
RESPONSE=$(curl -s -X POST http://127.0.0.1:8000/agent/execute \
  -H "Content-Type: application/json" \
  -d '{"user_id":"u1","text":"找出有运动动态的 live photo","library_root":"/home/vivo/live_photo_agent/data/live_photo","selected_asset_ids":[]}')
echo "$RESPONSE" | python3 -m json.tool
RUN_ID=$(echo "$RESPONSE" | python3 -c "import json,sys; print(json.load(sys.stdin)['context']['graph_observability']['run_id'])")

# Step 4: 告知反馈（带上 run_id，只有 accepted=true 才会计入策略/资产记忆）
curl -s -X POST http://127.0.0.1:8000/api/memory/feedback \
  -H "Content-Type: application/json" \
  -d "{\"run_id\": \"$RUN_ID\", \"accepted\": true, \"comment\": \"结果符合预期\"}" \
  | python3 -m json.tool
```

---

## 8. 自动化测试（记忆闭环相关）

```bash
conda run -n live_photo_agent python -m pytest tests/test_memory_strategy.py tests/test_orchestrator.py tests/test_ui.py -v
```

`tests/test_memory_strategy.py` 里 `test_deliverable_run_defers_memory_until_human_feedback` 和
`test_rejected_deliverable_does_not_land_in_asset_memory` 直接覆盖了第 5 节描述的闭环行为
（待确认 → 拒绝不落地 → 带反馈重试 → 通过后才计入 `multimodal_memory`/`strategy_memory`）。

