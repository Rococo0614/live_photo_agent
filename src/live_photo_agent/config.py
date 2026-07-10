from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="LPA_", env_file=".env", extra="ignore")

    app_name: str = "Live Photo Agent"
    planner_backend: str = "auto"
    qwen_endpoint: str | None = None
    qwen_auth_token: str | None = None
    qwen_timeout_seconds: float = 60.0
    qwen_model: str = "Qwen/Qwen3-VL-8B"
    local_model_dir: Path | None = None
    local_device: str = "cpu"
    local_dtype: str = "float32"
    local_max_new_tokens: int = 384
    workspace_dir: Path = Field(default_factory=lambda: Path.cwd())
    memory_file: Path = Field(default_factory=lambda: Path.cwd() / ".agent_memory.json")


settings = Settings()