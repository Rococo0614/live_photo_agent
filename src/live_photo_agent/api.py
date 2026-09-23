import os

os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")

from pathlib import Path

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
    local_model_dir: str | None = None
    local_device: str | None = None
    local_dtype: str | None = None
    local_max_new_tokens: int | None = None
    local_quantization: str | None = None


_PLANNER_BASELINE = {
    "local_model_dir": settings.local_model_dir,
    "local_device": settings.local_device,
    "local_dtype": settings.local_dtype,
    "local_max_new_tokens": settings.local_max_new_tokens,
    "local_quantization": settings.local_quantization,
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
            "planner_backend": "local_hf",
            "status": "unconfigured",
            "error": str(exc),
        }

    return {
        "planner_backend": "local_hf",
        "local_model_dir": str(settings.local_model_dir) if settings.local_model_dir else None,
        "local_device": settings.local_device,
        "local_dtype": settings.local_dtype,
        "local_max_new_tokens": settings.local_max_new_tokens,
        "local_quantization": settings.local_quantization,
        "runtime_info": runtime_info,
    }


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/dialog-state")
def dialog_state() -> dict[str, object]:
    """Debug endpoint: check DST state for multi-turn conversation."""
    ds = agent._dialog_state
    return {
        "turn_count": ds.turn_count,
        "last_intent": ds.last_intent,
        "last_query": ds.last_query,
        "last_asset_ids": ds.last_asset_ids,
        "last_template_id": ds.last_template_id,
        "last_template_name": ds.last_template_name,
        "last_assignment": ds.last_assignment,
        "last_recommendations": ds.last_recommendations,
        "last_final_video": ds.last_final_video,
    }


@app.get("/api/templates")
def list_templates() -> dict[str, object]:
    """List all available collage templates (v2: with time windows).

    This is the interface for external template systems — they can GET this
    endpoint to see the current template definitions and the JSON schema.
    """
    from .foundation.retrieval.template_library import TemplateLibrary
    lib = TemplateLibrary()
    return {
        "schema_version": "2.0",
        "schema_url": "docs/template_schema_v2.json",
        "templates": [t.to_dict() for t in lib.list_all()],
    }


@app.get("/api/templates/{template_id}")
def get_template(template_id: str) -> dict[str, object]:
    """Get a single template definition by ID."""
    from .foundation.retrieval.template_library import TemplateLibrary
    lib = TemplateLibrary()
    t = lib.get(template_id)
    if t is None:
        from fastapi import HTTPException
        raise HTTPException(status_code=404, detail=f"Template '{template_id}' not found")
    return t.to_dict()


class TemplateParseRequest(BaseModel):
    # ``image_base64`` is kept for old clients.  New clients may upload a
    # complete single-file Live Photo through ``media_base64``.
    image_base64: str = ""
    media_base64: str = ""
    image_name: str = ""
    grid_cols: int = 3
    grid_rows: int = 4
    backend: str = "vlm"


@app.post("/api/template/parse")
def parse_template(req: TemplateParseRequest) -> dict[str, object]:
    """Parse an image or a complete single-file Live Photo into a template."""
    import base64
    import tempfile
    from .capability.live_photo_cli import is_live_photo_container, unpack_motion_photo
    from .foundation.template_parser import parse_image_to_template

    encoded = req.media_base64 or req.image_base64
    if not encoded:
        raise HTTPException(status_code=400, detail="media_base64 or image_base64 is required")
    media_bytes = base64.b64decode(encoded)
    suffix = Path(req.image_name or "upload.jpg").suffix or ".jpg"
    video_path: Path | None = None
    image_bytes = media_bytes
    with tempfile.TemporaryDirectory(prefix="template-parse-") as tmp_dir:
        raw_path = Path(tmp_dir) / f"input{suffix}"
        raw_path.write_bytes(media_bytes)
        if raw_path.suffix.lower() in {".jpg", ".jpeg"} and is_live_photo_container(raw_path):
            unpacked_image, unpacked_video = unpack_motion_photo(raw_path, out_dir=Path(tmp_dir) / "unpacked")
            image_bytes = unpacked_image.read_bytes()
            video_path = unpacked_video
        template = parse_image_to_template(
            image_bytes,
            image_name=req.image_name,
            grid_cols=req.grid_cols,
            grid_rows=req.grid_rows,
            video_path=video_path,
        )
    return {"template": template}


class TemplateSaveRequest(BaseModel):
    template: dict[str, object]


@app.post("/api/template/save")
def save_template(req: TemplateSaveRequest) -> dict[str, object]:
    """Save a parsed/custom template into the shared local template library."""
    from .foundation.retrieval.template_library import TemplateLibrary

    try:
        template = TemplateLibrary().save_template_dict(req.template)
    except (TypeError, ValueError, KeyError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"template": template.to_dict()}


@app.delete("/api/templates/{template_id}")
def delete_template(template_id: str) -> dict[str, object]:
    from .foundation.retrieval.template_library import TemplateLibrary

    deleted = TemplateLibrary().delete_template(template_id)
    if not deleted:
        raise HTTPException(status_code=404, detail=f"Custom template '{template_id}' not found")
    return {"deleted": template_id}


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
    if "local_model_dir" in payload:
        local_dir = _normalize_optional(payload["local_model_dir"])
        settings.local_model_dir = Path(local_dir).expanduser().resolve() if local_dir else None
    if "local_device" in payload and payload["local_device"] is not None:
        settings.local_device = str(payload["local_device"]).strip().lower()
    if "local_dtype" in payload and payload["local_dtype"] is not None:
        settings.local_dtype = str(payload["local_dtype"]).strip().lower()
    if "local_max_new_tokens" in payload and payload["local_max_new_tokens"] is not None:
        settings.local_max_new_tokens = int(payload["local_max_new_tokens"])
    if "local_quantization" in payload and payload["local_quantization"] is not None:
        settings.local_quantization = str(payload["local_quantization"]).strip().lower()

    agent.planner.reset_runtime()
    return _planner_snapshot()


@app.delete("/api/ui/planner-config")
def reset_planner_config() -> dict[str, object]:
    settings.local_model_dir = _PLANNER_BASELINE["local_model_dir"]
    settings.local_device = str(_PLANNER_BASELINE["local_device"])
    settings.local_dtype = str(_PLANNER_BASELINE["local_dtype"])
    settings.local_max_new_tokens = int(_PLANNER_BASELINE["local_max_new_tokens"])
    settings.local_quantization = str(_PLANNER_BASELINE["local_quantization"])
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
    # Debug: log DST state and request info
    ds = agent._dialog_state
    is_refine = ds.is_refine(str(request.text))
    print(f"  [API] text={request.text!r} selected_asset_ids={request.selected_asset_ids} "
          f"is_refine={is_refine} dst_turns={ds.turn_count} dst_assets={len(ds.last_asset_ids)}")
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
