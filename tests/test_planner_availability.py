from pathlib import Path

from live_photo_agent.brain import QwenPlanner
from live_photo_agent.models import AgentRequest
from live_photo_agent.orchestrator import LivePhotoAgent


def test_runner_fails_fast_when_planner_unavailable(monkeypatch) -> None:
    def _raise_runtime_info(self):
        raise RuntimeError("planner_not_configured")

    monkeypatch.setattr(QwenPlanner, "runtime_info", _raise_runtime_info)

    agent = LivePhotoAgent()
    request = AgentRequest(user_id="u1", text="做一个三拼", library_root=Path("."))
    response = agent.execute(request)

    assert response.plan.intent == "planner_unavailable"
    assert response.plan.need_clarification is True
    assert response.tool_results == []
    assert "未执行任何工具" in response.final_response
