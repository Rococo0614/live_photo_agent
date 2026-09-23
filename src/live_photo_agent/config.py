import os
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


CONFIG_HOME = Path(
    os.environ.get("XDG_CONFIG_HOME") or (Path.home() / ".config")
) / "live_photo_agent"

ENV_FILES = (".env", CONFIG_HOME / "env")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="LPA_", env_file=ENV_FILES, extra="ignore"
    )

    app_name: str = "Live Photo Agent"

    # Planner (大脑): 本地纯文本 LLM, 不再支持云端 endpoint
    planner_clarification_policy: str = "balanced"
    planner_tool_constraint_policy: str = "strict"
    local_model_dir: Path | None = None
    local_device: str = "cuda"
    local_dtype: str = "float16"
    local_quantization: str = "nf4"
    local_max_new_tokens: int = 768

    # VLM (视觉语言模型): 本地 VL 模型, 按需加载, 不再支持云端 endpoint
    vlm_model_dir: Path | None = None
    vlm_device: str = "cuda"
    vlm_dtype: str = "float16"
    vlm_quantization: str = "nf4"
    vlm_max_new_tokens: int = 256
    vlm_template_score_candidate_limit: int = 3

    # Diffusion (生图): 本地 diffusion 模型, 预留接口
    diffusion_model_dir: Path | None = None
    diffusion_device: str = "cuda"
    diffusion_dtype: str = "float16"

    # 工作目录
    agent_work_dir: Path = Field(default_factory=lambda: Path.cwd() / ".agent_work")
    workspace_dir: Path = Field(default_factory=lambda: Path.cwd())
    memory_file: Path = Field(default_factory=lambda: Path.cwd() / ".agent_memory.json")
    default_library_root: Path = Field(default_factory=lambda: Path.home() / "DCIM")
    album_catalog_file: Path = Field(default_factory=lambda: Path.cwd() / ".album_catalog.json")
    album_operation_log_file: Path = Field(default_factory=lambda: Path.cwd() / ".album_operations.jsonl")
    album_preprocess_index_file: Path = Field(default_factory=lambda: Path.cwd() / ".album_preprocess_index.jsonl")

    # Observability
    graph_observability_enabled: bool = True
    graph_run_log_file: Path = Field(default_factory=lambda: Path.cwd() / ".graph_runs.jsonl")


settings = Settings()
