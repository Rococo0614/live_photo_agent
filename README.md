# Live Photo Agent

这是一个以 Qwen3-VL 8B 为核心大脑的 Live Photo Agent 最小骨架，目标是把以下流程串起来：

1. 后台扫描相册中的所有 Live Photo 与用户选中的 Live Photo
2. 接收用户文字输入，后续可扩展为语音转文字输入
3. 使用大模型做意图理解与执行规划
4. 准备相册上下文与候选素材上下文
5. 按规划挑选工具并执行
6. 生成结果、沉淀记忆、输出复盘

## 项目结构

- `src/live_photo_agent/api.py`: FastAPI 入口
- `src/live_photo_agent/orchestrator.py`: 主流程编排
- `src/live_photo_agent/brain.py`: Qwen 规划大脑适配层（支持 endpoint/local_hf 可插拔后端）
- `src/live_photo_agent/tools.py`: 工具注册与执行
- `src/live_photo_agent/library.py`: 相册扫描与素材索引
- `src/live_photo_agent/memory.py`: 记忆与复盘

## 运行

```bash
# Option A1: conda CPU profile (recommended default)
conda env create -f environment.cpu.yml
conda activate live-photo-agent-cpu

# Option A2: conda GPU profile (NVIDIA + CUDA 12.1 wheel)
conda env create -f environment.gpu.yml
conda activate live-photo-agent-gpu

# Option B: venv
python -m venv .venv
source .venv/bin/activate
pip install -e .[dev]
# local_hf planner backend requires:
pip install 'torch>=2.3,<3.0' 'transformers>=4.46,<5.0'

uvicorn live_photo_agent.api:app --reload
```

### Environment notes

- `environment.cpu.yml`: stable baseline for API + endpoint mode and local CPU inference.
- `environment.gpu.yml`: local GPU inference with PyTorch CUDA 12.1 wheel.
- For GPU profile, ensure `nvidia-smi` works before running local_hf backend.

## 示例请求

```bash
curl -X POST http://127.0.0.1:8000/agent/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "user_id": "demo-user",
    "text": "帮我找出海边日落的 live photo，并生成一个适合朋友圈的精选建议",
    "library_root": "./sample_library",
    "selected_asset_ids": []
  }'
```

## Planner backend (plug-and-play)

Planner supports two backends and can switch by environment variable:

- `endpoint`: call remote/local HTTP inference endpoint.
- `local_hf`: run local Hugging Face model directly in process.
- `auto` (default): prefer endpoint when `LPA_QWEN_ENDPOINT` is set, otherwise use `local_hf` when `LPA_LOCAL_MODEL_DIR` is set.

Endpoint mode example:

```bash
export LPA_PLANNER_BACKEND=endpoint
export LPA_QWEN_ENDPOINT='http://127.0.0.1:8000/v1/chat/completions'
export LPA_QWEN_MODEL='Qwen/Qwen3-4B-Instruct-2507'
```

Local HF mode example:

```bash
export LPA_PLANNER_BACKEND=local_hf
export LPA_LOCAL_MODEL_DIR='/home/vivo/live_photo_agent/models/qwen/Qwen--Qwen3-4B-Instruct-2507/snapshots/cdbee75f17c01a7cc42f958dc650907174af0554'
export LPA_LOCAL_DEVICE=cpu
export LPA_LOCAL_DTYPE=float32
```

Both modes share the same planner contract and execution flow.