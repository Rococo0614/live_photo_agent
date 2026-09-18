import os
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


CONFIG_HOME = Path(
    os.environ.get("XDG_CONFIG_HOME") or (Path.home() / ".config")
) / "live_photo_agent"

# 凭据放在仓库外。放在仓库内的 .env 会被密钥扫描反复命中并清理，
# 每次清理都要重新配一遍；外置后凭据与代码解耦，不会因为仓库操作丢失。
# 仓库内 .env 仍然读取，仅作为本地临时覆盖，且已被 .gitignore 忽略。
# 顺序靠后者优先：外置文件覆盖仓库内的同名项。
ENV_FILES = (".env", CONFIG_HOME / "env")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="LPA_", env_file=ENV_FILES, extra="ignore"
    )

    app_name: str = "Live Photo Agent"
    planner_backend: str = "local_hf"
    planner_clarification_policy: str = "balanced"
    planner_tool_constraint_policy: str = "strict"
    qwen_endpoint: str | None = None
    qwen_auth_token: str | None = None
    qwen_workspace_id: str | None = None
    qwen_timeout_seconds: float = 60.0
    qwen_model: str = "Qwen/Qwen3-VL-8B"
    local_model_dir: Path | None = None
    local_device: str = "cuda"
    local_dtype: str = "bfloat16"
    local_quantization: str = "8bit"
    local_max_new_tokens: int = 384
    agent_work_dir: Path = Field(default_factory=lambda: Path.cwd() / ".agent_work")
    vlm_backend: str = "disabled"
    vlm_endpoint: str | None = None
    vlm_model: str | None = None
    vlm_timeout_seconds: float = 20.0
    vlm_prompt: str | None = None
    workspace_dir: Path = Field(default_factory=lambda: Path.cwd())
    memory_file: Path = Field(default_factory=lambda: Path.cwd() / ".agent_memory.json")
    default_library_root: Path = Field(default_factory=lambda: Path.home() / "DCIM")
    album_catalog_file: Path = Field(default_factory=lambda: Path.cwd() / ".album_catalog.json")
    album_operation_log_file: Path = Field(default_factory=lambda: Path.cwd() / ".album_operations.jsonl")
    album_preprocess_index_file: Path = Field(default_factory=lambda: Path.cwd() / ".album_preprocess_index.jsonl")
    graph_observability_enabled: bool = True
    graph_run_log_file: Path = Field(default_factory=lambda: Path.cwd() / ".graph_runs.jsonl")


settings = Settings()