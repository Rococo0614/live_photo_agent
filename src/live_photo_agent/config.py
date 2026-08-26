import os
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="LPA_", env_file=".env", extra="ignore")

    app_name: str = "Live Photo Agent"
    planner_backend: str = "auto"
    planner_clarification_policy: str = "balanced"
    planner_tool_constraint_policy: str = "strict"
    qwen_endpoint: str | None = None
    qwen_auth_token: str | None = None
    qwen_workspace_id: str | None = None

    # 兼容用户现场 `export DASHSCOPE_API_KEY=...`（或 OPENAI_API_KEY）的习惯：
    # 代码主路径只读 LPA_QWEN_AUTH_TOKEN，导致这类通用变量名被忽略，
    # 网页端永远显示「未配置」，逼用户每次手填。这里做兜底回填。
    @property
    def effective_qwen_auth_token(self) -> str | None:
        if self.qwen_auth_token:
            return self.qwen_auth_token
        for env_name in ("DASHSCOPE_API_KEY", "DASHSCOPE_API_KEY_ENV", "OPENAI_API_KEY"):
            val = os.environ.get(env_name)
            if val:
                return val.strip()
        return None
    qwen_timeout_seconds: float = 60.0
    # 规划生成硬超时：从开始生成起超过该秒数仍未出结果，直接中断并抛错。
    # Kept low so the deterministic fallback engages quickly (worst-case wall
    # time stays well under ~30s for the user, since the fallback itself runs
    # in a few seconds).
    planner_generation_timeout_seconds: float = 12.0
    qwen_model: str = "Qwen/Qwen3-VL-8B"
    local_model_dir: Path | None = None
    local_device: str = "cpu"
    local_dtype: str = "float32"
    local_max_new_tokens: int = 384
    vlm_backend: str = "endpoint"
    vlm_endpoint: str | None = None
    vlm_model: str | None = None
    vlm_timeout_seconds: float = 20.0
    vlm_prompt: str | None = None
    workspace_dir: Path = Field(default_factory=lambda: Path.cwd())
    memory_file: Path = Field(default_factory=lambda: Path.cwd() / ".agent_memory.json")
    default_library_root: Path = Field(default_factory=lambda: Path("/home/vivo/live_photo_agent/data"))
    album_catalog_file: Path = Field(default_factory=lambda: Path.cwd() / ".album_catalog.json")
    album_operation_log_file: Path = Field(default_factory=lambda: Path.cwd() / ".album_operations.jsonl")
    album_preprocess_index_file: Path = Field(default_factory=lambda: Path.cwd() / ".album_preprocess_index.jsonl")
    graph_observability_enabled: bool = True
    graph_run_log_file: Path = Field(default_factory=lambda: Path.cwd() / ".graph_runs.jsonl")


settings = Settings()