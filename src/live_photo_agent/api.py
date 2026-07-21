from pathlib import Path

from fastapi import FastAPI
from fastapi import HTTPException
from pydantic import BaseModel
from fastapi.responses import HTMLResponse
from fastapi.responses import FileResponse

from .config import settings
from .models import AgentRequest, AgentResponse
from .orchestrator import LivePhotoAgent
from .ui import build_ui_bootstrap, load_index_html


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
        "qwen_auth_token_configured": bool(settings.qwen_auth_token),
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
    return agent.execute(request)