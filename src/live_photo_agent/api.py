from fastapi import FastAPI

from .config import settings
from .models import AgentRequest, AgentResponse
from .orchestrator import LivePhotoAgent


app = FastAPI(title=settings.app_name)
agent = LivePhotoAgent()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/agent/execute", response_model=AgentResponse)
def execute_agent(request: AgentRequest) -> AgentResponse:
    return agent.execute(request)