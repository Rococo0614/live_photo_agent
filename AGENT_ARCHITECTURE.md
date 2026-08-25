# Live Photo Agent Current Execution Architecture

This document describes the current runtime architecture in code, with a focus on safe future extension and removal.

## 1. Runtime Entry

- API entry: `POST /agent/execute` in `src/live_photo_agent/api.py`
- Orchestrator class: `LivePhotoAgent` in `src/live_photo_agent/execution/agent.py`
- Request/response contracts: `AgentRequest`, `AgentResponse` in `src/live_photo_agent/models.py`

## 2. End-to-End Execution Flow

1. Receive request (`user_id`, `text`, `library_root`, optional `selected_asset_ids`, optional `input_image_paths` / `input_video_paths`).
2. Input preprocessor merges fixed album assets with explicit image/video inputs into one runtime asset view.
3. Planner (`QwenPlanner`) generates an `ExecutionPlan`.
4. If `need_clarification=true`, return clarification response immediately (no tool execution).
5. Capability normalization runs boundary checks only (argument allowlist filtering per tool contract).
6. Build execution context and execute tool calls in planner-provided order.
7. Build pipeline trace and foundation state.
8. Build final response, review, and memory updates.

## 2.1 Current File Map (By Responsibility)

- API and orchestration:
  - `src/live_photo_agent/api.py`
  - `src/live_photo_agent/orchestrator.py`
  - `src/live_photo_agent/execution/agent.py`
  - `src/live_photo_agent/execution/langgraph_runner.py`
  - `src/live_photo_agent/execution/input_preprocessor.py`
  - `src/live_photo_agent/execution/flow.py`
- Planner and contracts:
  - `src/live_photo_agent/brain.py`
  - `src/live_photo_agent/capability/contracts.py`
  - `src/live_photo_agent/capability/registry.py`
- Capability tools:
  - `src/live_photo_agent/capability/l0_atomic_tools.py`
  - `src/live_photo_agent/capability/l1_integrated_tools.py`
  - `src/live_photo_agent/capability/l2_vertical_tools.py`
- Foundation:
  - `src/live_photo_agent/foundation/library.py`
  - `src/live_photo_agent/foundation/media_ops.py`
  - `src/live_photo_agent/foundation/memory.py`
  - `src/live_photo_agent/foundation/state.py`
- Data models and config:
  - `src/live_photo_agent/models.py`
  - `src/live_photo_agent/config.py`
- Eval and quality scoring:
  - `tests/evals/run_planner_eval.py`
  - `tests/evals/planner_eval_set_50.jsonl`
  - `tests/evals/README.md`
- Tests:
  - `tests/test_orchestrator.py`
  - `tests/test_input_preprocessor.py`

## 3. Layer Responsibilities

### 3.1 Execution Layer

- Files:
  - `src/live_photo_agent/execution/agent.py`
  - `src/live_photo_agent/execution/flow.py`
- Responsibilities:
  - Coordinates planner, capability, and foundation components.
  - Normalizes multimodal input through preprocessor before planning.
  - Handles clarification branch.
  - Maintains runtime context and aggregates tool results.
  - Produces final user response and pipeline trace.

### 3.2 Capability Layer

- Files:
  - `src/live_photo_agent/capability/contracts.py`
  - `src/live_photo_agent/capability/registry.py`
  - `src/live_photo_agent/capability/l0_atomic_tools.py`
  - `src/live_photo_agent/capability/l1_integrated_tools.py`
  - `src/live_photo_agent/capability/l2_vertical_tools.py`
- Responsibilities:
  - Defines tool boundaries (`ToolContract`): allowed arguments, preconditions, side effects, failure modes, timeout budgets.
  - Exposes machine-readable `tool_catalog()` for the planner.
  - Dispatches tool calls via `ToolRegistry`.
  - Performs non-semantic normalization only: drops arguments that are not in contract allowlists.

### 3.3 Foundation Layer

- Files:
  - `src/live_photo_agent/foundation/library.py`
  - `src/live_photo_agent/foundation/media_ops.py`
  - `src/live_photo_agent/foundation/memory.py`
  - `src/live_photo_agent/foundation/state.py`
- Responsibilities:
  - Library scanning and asset pairing.
  - ffmpeg/ffprobe/opencv media operations.
  - Session and multimodal memory persistence.
  - Build foundation telemetry (`FoundationState`).

## 4. Planner and Tooling Boundary

- Planner implementation: `src/live_photo_agent/brain.py`
- Backends:
  - `endpoint` (HTTP planner)
  - `local_hf` (local transformers runtime)
  - `auto` (resolve by config)
- Planner input includes:
  - request payload
  - library summary
  - full tool catalog with boundaries (arguments, schemas, preconditions, failure modes, scopes)
- Planner output must match `ExecutionPlan` schema in `src/live_photo_agent/models.py`.

## 5. Data Contracts

- Tool identity enum: `ToolName`
- Planned step contract: `ToolCall { tool, reason, arguments }`
- Plan contract: `ExecutionPlan`
- Execution output contract: `ToolResult`
- Final envelope: `AgentResponse`

All contracts are centralized in `src/live_photo_agent/models.py`.

## 6. Current Tool Stack (By Level)

- L0 atomic tools:
  - scan/filter/extract/score/segment/cover/trim/speed/color/stabilize/concat/overlay/bgm/export
  - `extract_subject_matte` / `overlay_subject_clip`: cut a moving subject out of one motion clip
    (per-frame alpha matte via OpenCV background subtraction, no extra model dependency) and
    composite it onto the current `context.timeline` at a chosen anchor/scale.
- L1 integrated tools:
  - search and summary aggregation
- L2 vertical tools:
  - higher-level edit plan drafting

Registry mapping is single-source in `src/live_photo_agent/capability/registry.py`.

## 7. Context and Side-Effect Model

- Shared mutable `context` dictionary is the runtime bus between tool calls.
- Each tool writes declared side effects (for example `context.key_frames`, `context.summary`).
- `MemoryService.append` always writes a `session_memory` entry immediately (audit trail).
  For turns that produced a real deliverable (tool_calls executed, no clarification),
  `multimodal_memory` and `strategy_memory` are **not** written yet — the entry is left
  pending (`accepted: null`, `requires_feedback: true`) until a human confirms via
  `POST /api/memory/feedback` (`MemoryService.confirm_feedback`, keyed by
  `graph_observability.run_id`). Only `accepted=true` commits into `multimodal_memory`;
  `strategy_memory` learns from both outcomes (`run_count` always increments,
  `accept_count` only on acceptance). Turns with no deliverable (clarification / planner
  unavailable) have nothing to review and are finalized immediately as before.
- Rejected turns are expected to be retried by resubmitting `/agent/execute` with
  `retry_feedback` (+ `retry_of_run_id`) set on `AgentRequest`; `LivePhotoAgent` folds the
  feedback into the request text before planning so the next plan can react to it.
- Local trace visualization (staged `pipeline_trace` + fine-grained `graph_observability.trace`)
  replaces LangSmith for in-process observability; recent runs are also available via
  `GET /api/runs/recent` (tailing `settings.graph_run_log_file`). LangSmith tracing in
  `api.py` remains an optional no-op auto-enable hook, not the primary observability path.

## 8. Clarification Behavior

- Trigger: planner sets `need_clarification=true`.
- Result:
  - No tools executed.
  - `clarification_questions` and `blocking_missing_info` returned in response context.
  - Foundation state and memory review are still generated.

## 9. Configuration Surface

- Source: `src/live_photo_agent/config.py`
- Environment prefix: `LPA_`
- Key config groups:
  - Planner backend/model settings
  - Endpoint auth/timeout
  - Local model dir/device/dtype
  - Workspace and memory file paths

## 10. Safe Change Playbooks

### 10.1 Add a New Tool

1. Add enum value in `ToolName` (`src/live_photo_agent/models.py`).
2. Implement handler in proper level file (`l0_atomic_tools.py`, `l1_integrated_tools.py`, or `l2_vertical_tools.py`).
3. Register handler in `ToolRegistry` (`src/live_photo_agent/capability/registry.py`).
4. Add `ToolContract` and schemas in `CapabilityLayer` (`src/live_photo_agent/capability/contracts.py`).
5. Add planner-facing `ToolSpec` in `src/live_photo_agent/brain.py`.
6. Add/adjust tests.

### 10.2 Remove a Tool

1. Remove registry mapping first.
2. Remove planner `ToolSpec` and contract entries.
3. Remove enum value from `ToolName` only after all references are gone.
4. Delete implementation and update tests/evals.

### 10.3 Change Tool Arguments

1. Update handler signature/argument reads.
2. Update `allowed_arguments` in `ToolContract`.
3. Update input schema in `CapabilityLayer` and `ToolSpec` argument docs.
4. Validate normalization does not drop required fields.

## 11. Invariants to Keep

- Planner never executes tools directly; execution goes through `ToolRegistry`.
- Tool argument boundary is enforced by contract allowlists.
- Clarification path must stay tool-free.
- API response remains `AgentResponse`-compatible.

## 12. Quick Maintenance Checklist

- Did you update `ToolName`, registry, contract, and planner spec consistently?
- Did you keep side effects documented in `ToolContract.side_effects`?
- Did you run at least `pytest -q tests/test_orchestrator.py`?
- Did you keep planner output compatible with `ExecutionPlan` schema?

## 13. Current Gaps and Priority

1. Multimodal direct input is now normalized into runtime assets, but audio and richer metadata are not ingested yet.
2. Tool capability is still mostly ffmpeg/opencv baseline; advanced creative operators are pending.
3. Result-layer IQA scoring has been introduced in eval, but production threshold policy still needs online validation.
4. `extract_subject_matte` uses classic background subtraction (MOG2/KNN) + largest-contour cleanup,
   not a learned matting model — it degrades on moving cameras, cluttered/dynamic backgrounds, or
   multiple moving subjects. `mediapipe`'s modern Python package (>=0.10.14) dropped the legacy
   `mp.solutions` API and requires downloading a `.tflite` model from `storage.googleapis.com` at
   runtime, which was unreachable in this sandbox; pinning `mediapipe==0.10.9` restores the legacy
   API but force-downgrades `protobuf` to 3.20.3, breaking `langgraph-api`/`grpcio`/`tensorboard` in
   this env — so no ML-based matting dependency was added. If a real deployment has network access to
   Google's model CDN and an isolated env, swapping in `mediapipe.tasks.python.vision.ImageSegmenter`
   (or another local model) behind the same `extract_subject_matte_frames` signature is a drop-in
   quality upgrade.

Recommended next implementation priority:

1. Album preprocessing hardening (time/range filters, dedup, EXIF and capture-time features).
2. Intent-to-tool templates for image gameplay recipes.
3. Execution-level result validation hooks (output existence, codec checks, IQA threshold trigger).
