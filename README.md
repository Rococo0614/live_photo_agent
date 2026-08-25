# Live Photo Agent

这是一个面向 Live Photo 场景的 Agent 项目，核心目标是把以下流程串起来：

1. 后台扫描相册中的所有 Live Photo 与用户选中的 Live Photo
2. 接收用户文字输入，后续可扩展为语音转文字输入
3. 使用大模型做意图理解与执行规划
4. 准备相册上下文与候选素材上下文
5. 按规划挑选工具并执行
6. 生成结果、沉淀记忆、输出复盘

当前实现同时支持 planner 评测、真实工具执行、以及图像质量打分扩展（CLIPIQA/MUSIQ）。

## 项目结构

- `src/live_photo_agent/api.py`: FastAPI 入口
- `src/live_photo_agent/orchestrator.py`: 主流程编排
- `src/live_photo_agent/brain.py`: 规划大脑适配层（支持 endpoint/local_hf 可插拔后端）
- `src/live_photo_agent/tools.py`: 工具注册与执行
- `src/live_photo_agent/library.py`: 相册扫描与素材索引
- `src/live_photo_agent/memory.py`: 记忆与复盘

## 快速开始

### 1) 创建环境

先确认 `ffmpeg` 可用（L0 媒体工具依赖 `ffmpeg`/`ffprobe`，且必须带 `libx264`）：

```bash
ffmpeg -encoders | grep libx264   # 无输出则需先安装
# Ubuntu/Debian: sudo apt install ffmpeg
# 或装进 conda 环境: conda install -c conda-forge ffmpeg
```

```bash
# Option A1: conda GPU profile (NVIDIA, CUDA 12.8 wheel)
conda env create -f environment.gpu.yml
conda activate live-photo-agent

# Option A2: conda CPU profile (无显卡机器 / 仅调用云端 endpoint)
conda env create -f environment.cpu.yml
conda activate live-photo-agent-cpu

# Option B: venv
python -m venv .venv
source .venv/bin/activate
pip install -e .[dev]
# local_hf planner backend requires (GPU, CUDA 12.8):
pip install --extra-index-url https://download.pytorch.org/whl/cu128 'torch==2.9.1+cu128' 'torchvision==0.24.1+cu128'
# ...or CPU-only:
pip install --extra-index-url https://download.pytorch.org/whl/cpu 'torch==2.9.1+cpu' 'torchvision==0.24.1+cpu'
pip install 'transformers==4.57.6' 'accelerate>=1.0,<2.0'
# live photo pack mode requires:
pip install 'pyexiv2>=2.15,<3.0'

uvicorn live_photo_agent.api:app --reload
```

### 2) 环境说明

- `environment.gpu.yml`（env 名 `live-photo-agent`）: 本地 GPU 推理，CUDA 12.8 wheel。
- `environment.cpu.yml`（env 名 `live-photo-agent-cpu`）: 无显卡机器，CPU 推理与 endpoint 调用均可。
- 两个 profile 都固定了 `torch`/`torchvision` 兼容组合。
- **CUDA 版本下限**：`torch` 必须是 **cu128 及以上**。Blackwell 显卡（RTX 50xx）算力为 `sm_120`，
  任何 cu121 wheel 都不含该架构，跑 CUDA kernel 会直接报
  `no kernel image is available for execution on the device`。
- **transformers 版本下限**：加载 `Qwen/Qwen3-VL-8B`（见 `config.py` 的 `qwen_model`）
  需要 **>=4.57**，`qwen3_vl` 架构在 4.57.0 才合入，更早版本会 `KeyError: 'qwen3_vl'`。
- `ffmpeg` 不从 conda `defaults` 安装：该 channel 的构建剥离了 GPL 组件、不含 `libx264`，
  会导致 L0 的 trim/concat/export 失败。请用系统 ffmpeg 或 conda-forge 版本。
- IQA 评测依赖（`pip install -e .[iqa]`）与新版 `torch` 可能冲突，需要时再单独安装。
- For GPU profile, ensure `nvidia-smi` works before running local_hf backend.

当前分割相关说明：`media_ops.segment_subject` 使用 `cv2.grabCut`，
`extract_subject_matte_frames` 使用 MOG2/KNN 背景减除，**均为 CPU 实现**。
pip 版 `opencv-python-headless` 不带 CUDA，且 OpenCV 本身没有 CUDA 版 grabCut，
因此这两个算子无法通过装 GPU 版 OpenCV 加速。

### 3) 运行测试

```bash
pytest
python tests/evals/run_planner_eval.py --max-cases 10
```

## 示例请求

```bash
curl -X POST http://127.0.0.1:8000/agent/execute \
  -H 'Content-Type: application/json' \
  -d '{
    "user_id": "demo-user",
    "text": "帮我找出海边日落的 live photo，并生成一个适合朋友圈的精选建议",
    "library_root": "./sample_library",
    "selected_asset_ids": [],
    "input_image_paths": [],
    "input_video_paths": []
  }'
```

说明：

1. `library_root` 是固定相册目录，系统会始终先扫描。
2. `input_image_paths` / `input_video_paths` 是可选显式输入，适合“临时新增素材”场景。
3. 未指定素材时，系统仍会基于固定相册目录完成候选构建与编排。

### 离线预处理索引构建（推荐先执行）

建议先离线构建一次预处理索引，让运行时优先消费稳定摘要：

```bash
python -m live_photo_agent.offline_preprocess_cli \
  --library-root ./data/live_photo_decoded
```

增量更新（默认）会复用未变化资产；强制全量重建可加 `--force`：

```bash
python -m live_photo_agent.offline_preprocess_cli \
  --library-root ./data/live_photo_decoded \
  --force
```

自动化场景可使用 `--json` 输出结构化报告。

预处理索引采用分层摘要：

1. `technical_signals`：文件指纹、图片尺寸/亮度/清晰度、视频时长/FPS/编码等标准信息。
2. `coarse_semantics` / `semantic_signals`：由 prompt-based VLM 语义分析器生成，包含场景、主体、动作、声音和可检索摘要；若配置 `LPA_VLM_ENDPOINT`，会把图片内容送给大模型并产出结构化语义。当前实现默认以端点推理为主，若端点不可用则保留基础摘要内容。
3. `provenance`：记录各层生产者和版本，支持按模型、Prompt 与资产指纹增量更新。

相册重新扫描只刷新技术层；资产指纹未变化时会保留已有语义层，文件变化后则使旧语义结果失效。运行时检索会优先使用这些语义字段来匹配用户需求。

## 相册前处理与底座管理（DCIM）

项目默认把手机导出的 `DCIM` 目录作为底座相册输入（可通过 `LPA_DEFAULT_LIBRARY_ROOT` 覆盖）。

当前行为：

1. 自动扫描 `library_root` 下所有图片/视频文件。
2. 对同 stem 的 `jpg + mp4/mov` 进行 live photo 配对。
3. 对 `.jpg/.jpeg` 进一步判断是否为“嵌入式 live photo 容器”（同为 jpg 后缀但含 mp4 payload）。
4. 按相对目录自动归档 `album_name`（例如 `Camera/2026_07`）。
5. 每次扫描同步相册台账，新增/更新/删除都会记录，不影响其他相册目录。

底座文件：

1. `LPA_ALBUM_CATALOG_FILE`（默认 `.album_catalog.json`）
2. `LPA_ALBUM_OPERATION_LOG_FILE`（默认 `.album_operations.jsonl`）

可配置环境变量：

```bash
export LPA_DEFAULT_LIBRARY_ROOT=/path/to/DCIM
export LPA_ALBUM_CATALOG_FILE=/path/to/.album_catalog.json
export LPA_ALBUM_OPERATION_LOG_FILE=/path/to/.album_operations.jsonl
```

## 可观测与可回放

LangGraph 运行会输出结构化轨迹并支持回放快照：

1. 每次 run 生成唯一 `run_id`。
2. 每个策略节点/执行节点写入 `trace`（时间、节点、事件、摘要）。
3. 同时保存 `replay_snapshot`（请求摘要、策略模式、plan 摘要、校验结果）。

默认日志文件：

1. `LPA_GRAPH_RUN_LOG_FILE`（默认 `.graph_runs.jsonl`）

可配置环境变量：

```bash
export LPA_GRAPH_OBSERVABILITY_ENABLED=true
export LPA_GRAPH_RUN_LOG_FILE=/path/to/.graph_runs.jsonl
export LPA_PLANNER_CLARIFICATION_POLICY=balanced
export LPA_PLANNER_TOOL_CONSTRAINT_POLICY=strict
```

在线返回中会包含 `context.graph_observability`，用于快速定位：

1. `run_id`
2. `route_reason`
3. `trace`
4. `replay_snapshot`

## Live Photo conversion CLI

If your source/target data format is single-file Live Photo (JPEG primary + embedded MP4), use:

```bash
# unpack single-file motion photo into jpg + mp4
python src/live_photo_agent/capability/live_photo_cli.py unpack \
  /path/to/input_motion_photo.jpg \
  --out-dir /path/to/output_dir

# pack jpg + mp4 back into a single-file motion photo
python src/live_photo_agent/capability/live_photo_cli.py pack \
  --image /path/to/frame.jpg \
  --video /path/to/motion.mp4 \
  --output /path/to/final_motion_photo.jpg

# optional: normalize legacy data folders into a 3-folder layout
python src/live_photo_agent/capability/live_photo_cli.py normalize-layout \
  --data-root ./data \
  --apply
```

Notes:

- Use `live_photo_cli.py` as the single conversion entrypoint for unpack/pack/layout tasks.
- Internal processing in this project still uses split `jpg + mp4` for tool compatibility.
- `pack` mode requires `pyexiv2` (included in both conda environment files).
- Recommended data layout is exactly:
  - `data/live_photo` (source single-file live photos)
  - `data/live_photo_decoded` (decoded jpg/mp4 pairs)
  - `data/repacked` (repacked outputs)

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

## IQA 质量评估（开源默认可用）

项目已接入图像质量评估通道，支持以下模型：

1. `clipiqa`
2. `musiq`

在 CPU 场景下建议优先使用 `clipiqa`（推理更快）。

评测脚本示例：

```bash
python tests/evals/run_planner_eval.py \
  --quality-mode cloud \
  --quality-image-model clipiqa \
  --quality-image-fields result_jpeg_path,output_jpeg_path,jpeg_path \
  --quality-image-weight 0.6 \
  --quality-text-weight 0.4 \
  --result-only-pass \
  --result-score-threshold 0.75 \
  --max-quality-retries 2
```

如果你要仅验证 IQA 模型是否可用：

```bash
python - <<'PY'
import pyiqa
metric = pyiqa.create_metric('clipiqa', device='cpu')
print('clipiqa ready')
PY
```

### Backend switch scripts

You can also switch backend with scripts:

```bash
# endpoint backend (use source so env vars persist)
source scripts/use_endpoint_backend.sh \
  https://your-endpoint.example.com/v1/chat/completions \
  YOUR_API_KEY \
  Qwen/Qwen3-4B-Instruct-2507 \
  YOUR_WORKSPACE_ID

# local_hf backend
source scripts/use_local_backend.sh \
  /home/vivo/live_photo_agent/models/qwen/Qwen--Qwen3-4B-Instruct-2507/snapshots/cdbee75f17c01a7cc42f958dc650907174af0554 \
  cpu \
  float32 \
  Qwen/Qwen3-4B-Instruct-2507
```

### One-command endpoint vs local eval compare

```bash
scripts/run_eval_compare.sh \
  --endpoint-url https://your-endpoint.example.com/v1/chat/completions \
  --endpoint-api-key YOUR_API_KEY \
  --workspace-id YOUR_WORKSPACE_ID \
  --local-model-dir /home/vivo/live_photo_agent/models/qwen/Qwen--Qwen3-4B-Instruct-2507/snapshots/cdbee75f17c01a7cc42f958dc650907174af0554 \
  --max-cases 100
```

Generated outputs (default under `tests/evals`):

- `report_endpoint.json`
- `report_local.json`
- `compare_summary.json`

### Endpoint auth health check (no curl required)

If your terminal reports command-not-found errors (for example `127` with `curl`), use this script based on Python stdlib:

```bash
scripts/check_endpoint_auth.sh
```

It reads by default from:

- `LPA_QWEN_ENDPOINT`
- `LPA_QWEN_AUTH_TOKEN` (fallback `DASHSCOPE_API_KEY`)
- `LPA_QWEN_MODEL`

You can also override explicitly:

```bash
scripts/check_endpoint_auth.sh \
  --endpoint-url https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \
  --api-key "$DASHSCOPE_API_KEY" \
  --workspace-id "$LPA_QWEN_WORKSPACE_ID" \
  --model qwen3-omni-flash
```

For international DashScope accounts, use your region-specific endpoint and pass workspace id when required.

## 开源发布建议

1. 不要在仓库中提交真实 API Key、Workspace ID 或任何凭据。
2. 建议仅通过环境变量注入密钥，示例值使用占位符。
3. 如果仓库已有历史密钥泄露，发布前先轮转密钥并清理历史。
4. 为 PR 设置最小检查：`pytest` 与 `tests/evals/run_planner_eval.py --max-cases 10`。