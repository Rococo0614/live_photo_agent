import logging
import os
from pathlib import Path

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
from fastapi import FastAPI
from fastapi import HTTPException
from pydantic import BaseModel, Field
from fastapi.responses import HTMLResponse
from fastapi.responses import FileResponse

from .config import settings
from .foundation import LibraryService, OfflinePreprocessIndexer
from .foundation.memory import MemoryService
from .models import AgentRequest, AgentResponse
from .orchestrator import LivePhotoAgent
from .ui import build_ui_bootstrap, load_index_html

# ---------------------------------------------------------------------------
# LangSmith tracing — auto-enabled when LANGCHAIN_TRACING_V2=true is set
# (via .env or shell). No-op if the env var is absent or langsmith not installed.
# ---------------------------------------------------------------------------
if os.environ.get("LANGCHAIN_TRACING_V2", "").lower() == "true":
    try:
        from langsmith import Client as _LangSmithClient  # noqa: F401
        _project = os.environ.get("LANGCHAIN_PROJECT", "live-photo-agent")
        print(f"[LangSmith] tracing enabled → project: {_project}")
    except ImportError:
        print("[LangSmith] langsmith package not installed, tracing skipped")


app = FastAPI(title=settings.app_name)
agent = LivePhotoAgent()


class PlannerConfigUpdate(BaseModel):
    planner_backend: str | None = None
    qwen_endpoint: str | None = None
    qwen_auth_token: str | None = None
    qwen_workspace_id: str | None = None
    qwen_timeout_seconds: float | None = None
    qwen_model: str | None = None
    local_model_dir: str | None = None
    local_device: str | None = None
    local_dtype: str | None = None
    local_max_new_tokens: int | None = None


_PLANNER_BASELINE = {
    "planner_backend": settings.planner_backend,
    "qwen_endpoint": settings.qwen_endpoint,
    "qwen_auth_token": settings.qwen_auth_token,
    "qwen_workspace_id": settings.qwen_workspace_id,
    "qwen_timeout_seconds": settings.qwen_timeout_seconds,
    "qwen_model": settings.qwen_model,
    "local_model_dir": settings.local_model_dir,
    "local_device": settings.local_device,
    "local_dtype": settings.local_dtype,
    "local_max_new_tokens": settings.local_max_new_tokens,
}


def _normalize_optional(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = value.strip()
    return cleaned if cleaned else None


def _planner_snapshot() -> dict[str, object]:
    runtime_info: dict[str, object]
    try:
        runtime_info = agent.planner.runtime_info()
    except RuntimeError as exc:
        runtime_info = {
            "planner_backend": settings.planner_backend,
            "planner_model": settings.qwen_model,
            "status": "unconfigured",
            "error": str(exc),
        }

    return {
        "planner_backend": settings.planner_backend,
        "qwen_endpoint": settings.qwen_endpoint,
        "qwen_auth_token_configured": bool(settings.effective_qwen_auth_token),
        "qwen_workspace_id": settings.qwen_workspace_id,
        "qwen_timeout_seconds": settings.qwen_timeout_seconds,
        "qwen_model": settings.qwen_model,
        "local_model_dir": str(settings.local_model_dir) if settings.local_model_dir else None,
        "local_device": settings.local_device,
        "local_dtype": settings.local_dtype,
        "local_max_new_tokens": settings.local_max_new_tokens,
        "runtime_info": runtime_info,
    }


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/", response_class=HTMLResponse)
def ui_index() -> HTMLResponse:
    return HTMLResponse(load_index_html())


@app.get("/api/ui/bootstrap")
def ui_bootstrap(library_root: str | None = None) -> dict[str, object]:
    return build_ui_bootstrap(library_root)


@app.get("/api/ui/planner-config")
def get_planner_config() -> dict[str, object]:
    return _planner_snapshot()


@app.post("/api/ui/planner-config")
def set_planner_config(update: PlannerConfigUpdate) -> dict[str, object]:
    payload = update.model_dump(exclude_unset=True)
    if "planner_backend" in payload and payload["planner_backend"] is not None:
        settings.planner_backend = str(payload["planner_backend"]).strip().lower()
    if "qwen_endpoint" in payload:
        settings.qwen_endpoint = _normalize_optional(payload["qwen_endpoint"])
    if "qwen_auth_token" in payload:
        settings.qwen_auth_token = _normalize_optional(payload["qwen_auth_token"])
    if "qwen_workspace_id" in payload:
        settings.qwen_workspace_id = _normalize_optional(payload["qwen_workspace_id"])
    if "qwen_timeout_seconds" in payload and payload["qwen_timeout_seconds"] is not None:
        settings.qwen_timeout_seconds = float(payload["qwen_timeout_seconds"])
    if "qwen_model" in payload and payload["qwen_model"] is not None:
        settings.qwen_model = str(payload["qwen_model"]).strip()
    if "local_model_dir" in payload:
        local_dir = _normalize_optional(payload["local_model_dir"])
        settings.local_model_dir = Path(local_dir).expanduser().resolve() if local_dir else None
    if "local_device" in payload and payload["local_device"] is not None:
        settings.local_device = str(payload["local_device"]).strip().lower()
    if "local_dtype" in payload and payload["local_dtype"] is not None:
        settings.local_dtype = str(payload["local_dtype"]).strip().lower()
    if "local_max_new_tokens" in payload and payload["local_max_new_tokens"] is not None:
        settings.local_max_new_tokens = int(payload["local_max_new_tokens"])

    agent.planner.reset_runtime()
    return _planner_snapshot()


@app.delete("/api/ui/planner-config")
def reset_planner_config() -> dict[str, object]:
    settings.planner_backend = str(_PLANNER_BASELINE["planner_backend"])
    settings.qwen_endpoint = _PLANNER_BASELINE["qwen_endpoint"]
    settings.qwen_auth_token = _PLANNER_BASELINE["qwen_auth_token"]
    settings.qwen_workspace_id = _PLANNER_BASELINE["qwen_workspace_id"]
    settings.qwen_timeout_seconds = float(_PLANNER_BASELINE["qwen_timeout_seconds"])
    settings.qwen_model = str(_PLANNER_BASELINE["qwen_model"])
    settings.local_model_dir = _PLANNER_BASELINE["local_model_dir"]
    settings.local_device = str(_PLANNER_BASELINE["local_device"])
    settings.local_dtype = str(_PLANNER_BASELINE["local_dtype"])
    settings.local_max_new_tokens = int(_PLANNER_BASELINE["local_max_new_tokens"])
    agent.planner.reset_runtime()
    return _planner_snapshot()


@app.get("/api/ui/media")
def ui_media(path: str) -> FileResponse:
    resolved = Path(path).expanduser().resolve()
    if not resolved.exists() or not resolved.is_file():
        raise HTTPException(status_code=404, detail="media not found")
    return FileResponse(resolved)


@app.post("/agent/execute", response_model=AgentResponse)
def execute_agent(request: AgentRequest) -> AgentResponse:
    # Convert str paths to Path objects for handler
    request.library_root = Path(request.library_root) if isinstance(request.library_root, str) else request.library_root
    request.input_image_paths = [Path(p) if isinstance(p, str) else p for p in request.input_image_paths]
    request.input_video_paths = [Path(p) if isinstance(p, str) else p for p in request.input_video_paths]
    return agent.execute(request)


# ---------------------------------------------------------------------------
# Run observability (local replacement for LangSmith tracing)
# ---------------------------------------------------------------------------

@app.get("/api/runs/recent")
def recent_runs(limit: int = 20) -> dict[str, object]:
    """Return the most recent graph run records for local trace visualization.

    Reads ``settings.graph_run_log_file`` (JSONL, one record per run written by
    ``PlannerGraphRunner``) so the UI can render intent/plan/tool-arrangement/
    execution timelines without depending on an external tracer like LangSmith.
    """
    import json

    log_file = settings.graph_run_log_file
    if not log_file.exists():
        return {"runs": []}

    records: list[dict[str, object]] = []
    for raw_line in log_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(row, dict):
            records.append(row)

    records.reverse()
    return {"runs": records[: max(limit, 0)]}


# ---------------------------------------------------------------------------
# Album sync
# ---------------------------------------------------------------------------

class AlbumSyncRequest(BaseModel):
    library_root: str
    force: bool = False


@app.post("/api/album/sync")
def album_sync(request: AlbumSyncRequest) -> dict[str, object]:
    """Scan the library root and rebuild the offline preprocess index.

    1. Scans assets and builds quality signals.
    2. Enriches semantic signals via VLM (skips already-done rows).

    Rows whose fingerprint is unchanged AND already have VLM semantic signals
    are reused as-is.  Everything else is re-enriched.
    Set ``force=true`` to unconditionally rebuild every row.
    """
    library_root = Path(request.library_root).expanduser().resolve()
    if not library_root.exists() or not library_root.is_dir():
        raise HTTPException(status_code=400, detail=f"library_root not found: {library_root}")
    indexer = OfflinePreprocessIndexer(library_service=LibraryService())
    # Step 1: Scan + quality signals (fast)
    report = indexer.build(library_root=library_root, force_rebuild=request.force)
    # Step 2: VLM enrichment (slow, prints progress)
    indexer.enrich_semantics_pass(force=request.force)
    return report.to_dict()


# ---------------------------------------------------------------------------
# Memory / feedback
# ---------------------------------------------------------------------------

class FeedbackRequest(BaseModel):
    session_id: str = ""
    run_id: str = ""
    asset_ids: list[str] = Field(default_factory=list)
    accepted: bool = True
    comment: str = ""


@app.post("/api/memory/feedback")
def memory_feedback(request: FeedbackRequest) -> dict[str, object]:
    """Record explicit human acceptance / rejection for one agent turn.

    Prefer targeting by ``run_id`` (returned as ``context.graph_observability.run_id``
    from ``/agent/execute``); falls back to the most recent unconfirmed turn when
    omitted. Only ``accepted=true`` commits the turn's deliverable into
    ``multimodal_memory``; either way ``strategy_memory`` learns from the outcome.
    Rejected turns are expected to be retried via ``/agent/execute`` with
    ``retry_feedback``/``retry_of_run_id`` set until accepted.
    """
    memory = MemoryService(settings.memory_file)
    return memory.confirm_feedback(
        accepted=request.accepted,
        run_id=request.run_id,
        comment=request.comment,
        asset_ids=request.asset_ids,
    )
