from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

from ..models import AgentRequest, AgentResponse


SESSION_KEY = "session_memory"
MULTIMODAL_KEY = "multimodal_memory"


class MemoryService:
    def __init__(self, memory_file: Path) -> None:
        self.memory_file = memory_file

    def session_memory_size(self) -> int:
        state = self._load_state()
        return len(state[SESSION_KEY])

    def multimodal_memory_size(self) -> int:
        state = self._load_state()
        return len(state[MULTIMODAL_KEY])

    def append(self, request: AgentRequest, response: AgentResponse) -> list[str]:
        state = self._load_state()
        summary = dict(response.context.get("summary", {}))
        focus_asset_ids = self._derive_focus_asset_ids(response)

        session_entry = {
            "ts": self._utc_now_iso(),
            "user_id": request.user_id,
            "request": request.text,
            "intent": response.plan.intent,
            "selected_asset_ids": request.selected_asset_ids,
            "focus_asset_ids": focus_asset_ids,
            "final_response": response.final_response,
            "review": response.review,
            "tool_sequence": [call.tool.value for call in response.plan.tool_calls],
            "tool_success_count": len([result for result in response.tool_results if result.success]),
        }
        multimodal_entry = {
            "ts": self._utc_now_iso(),
            "user_id": request.user_id,
            "intent": response.plan.intent,
            "focus_asset_ids": focus_asset_ids,
            "selected_asset_ids": request.selected_asset_ids,
            "required_context": list(response.plan.required_context),
            "summary_focus_asset_count": int(summary.get("focus_asset_count", len(focus_asset_ids))),
        }

        state[SESSION_KEY].append(session_entry)
        state[MULTIMODAL_KEY].append(multimodal_entry)

        self.memory_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        return [
            f"写入会话记忆 1 条（总计 {len(state[SESSION_KEY])}）",
            f"写入多模态记忆 1 条（总计 {len(state[MULTIMODAL_KEY])}）",
        ]

    def build_review(self, response: AgentResponse) -> str:
        successful_tools = [result.tool.value for result in response.tool_results if result.success]
        return (
            f"本次流程识别到意图 {response.plan.intent}，"
            f"准备了 {len(response.context)} 项上下文，"
            f"成功执行工具 {', '.join(successful_tools)}。"
        )

    def _load_state(self) -> dict[str, list[dict[str, object]]]:
        if not self.memory_file.exists():
            return self._empty_state()

        content = self.memory_file.read_text(encoding="utf-8").strip()
        if not content:
            return self._empty_state()

        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            return self._empty_state()

        session_memory = parsed.get(SESSION_KEY, [])
        multimodal_memory = parsed.get(MULTIMODAL_KEY, [])

        if not isinstance(session_memory, list):
            session_memory = []
        if not isinstance(multimodal_memory, list):
            multimodal_memory = []

        return {
            SESSION_KEY: list(session_memory),
            MULTIMODAL_KEY: list(multimodal_memory),
        }

    def _derive_focus_asset_ids(self, response: AgentResponse) -> list[str]:
        summary = dict(response.context.get("summary", {}))
        ids = summary.get("focus_asset_ids", [])
        if isinstance(ids, list) and ids:
            return [str(item) for item in ids]

        for key in ("matched_assets", "selected_assets", "assets"):
            candidates = response.context.get(key, [])
            if isinstance(candidates, list) and candidates:
                derived = []
                for item in candidates[:5]:
                    if isinstance(item, dict) and "asset_id" in item:
                        derived.append(str(item["asset_id"]))
                if derived:
                    return derived
        return []

    def _empty_state(self) -> dict[str, list[dict[str, object]]]:
        return {
            SESSION_KEY: [],
            MULTIMODAL_KEY: [],
        }

    def _utc_now_iso(self) -> str:
        return datetime.now(timezone.utc).isoformat(timespec="seconds")
