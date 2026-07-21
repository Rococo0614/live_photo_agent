from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

from ..models import AgentRequest, AgentResponse


SESSION_KEY = "session_memory"
MULTIMODAL_KEY = "multimodal_memory"
STRATEGY_KEY = "strategy_memory"


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
        graph_observability = response.context.get("graph_observability", {})
        if not isinstance(graph_observability, dict):
            graph_observability = {}
        graph_run_id = str(graph_observability.get("run_id", ""))
        graph_route_reason = str(graph_observability.get("route_reason", ""))

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
            "graph_run_id": graph_run_id,
            "graph_route_reason": graph_route_reason,
            "accepted": self._is_accepted_outcome(response),
        }
        multimodal_entry = {
            "ts": self._utc_now_iso(),
            "user_id": request.user_id,
            "intent": response.plan.intent,
            "focus_asset_ids": focus_asset_ids,
            "selected_asset_ids": request.selected_asset_ids,
            "required_context": list(response.plan.required_context),
            "summary_focus_asset_count": int(summary.get("focus_asset_count", len(focus_asset_ids))),
            "graph_run_id": graph_run_id,
        }

        state[SESSION_KEY].append(session_entry)
        state[MULTIMODAL_KEY].append(multimodal_entry)
        self._upsert_strategy_memory(state, request, response)

        self.memory_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        return [
            f"写入会话记忆 1 条（总计 {len(state[SESSION_KEY])}）",
            f"写入多模态记忆 1 条（总计 {len(state[MULTIMODAL_KEY])}）",
        ]

    def suggest_reusable_sequences(self, request_text: str, limit: int = 3) -> list[dict[str, object]]:
        state = self._load_state()
        strategies = state.get(STRATEGY_KEY, [])
        if not isinstance(strategies, list) or not strategies:
            return []

        request_tokens = set(self._tokenize_text(request_text))
        scored: list[tuple[float, dict[str, object]]] = []
        for item in strategies:
            if not isinstance(item, dict):
                continue
            run_count = int(item.get("run_count", 0))
            accept_count = int(item.get("accept_count", 0))
            if run_count <= 0 or accept_count <= 0:
                continue
            token_profile = item.get("token_profile", [])
            if not isinstance(token_profile, list):
                token_profile = []
            profile_tokens = {str(token) for token in token_profile}
            overlap = len(request_tokens & profile_tokens)
            union = len(request_tokens | profile_tokens)
            lexical_score = overlap / union if union > 0 else 0.0
            accept_rate = accept_count / run_count
            score = lexical_score * 0.7 + accept_rate * 0.3
            if score <= 0:
                continue
            scored.append(
                (
                    score,
                    {
                        "intent": str(item.get("intent", "")),
                        "tool_sequence": list(item.get("tool_sequence", [])),
                        "concat_composition": str(item.get("concat_composition", "")),
                        "accept_rate": round(accept_rate, 3),
                        "score": round(score, 3),
                        "request_example": str(item.get("request_example", "")),
                    },
                )
            )

        scored.sort(key=lambda pair: pair[0], reverse=True)
        return [payload for _, payload in scored[: max(limit, 0)]]

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
        strategy_memory = parsed.get(STRATEGY_KEY, [])

        if not isinstance(session_memory, list):
            session_memory = []
        if not isinstance(multimodal_memory, list):
            multimodal_memory = []
        if not isinstance(strategy_memory, list):
            strategy_memory = []

        return {
            SESSION_KEY: list(session_memory),
            MULTIMODAL_KEY: list(multimodal_memory),
            STRATEGY_KEY: list(strategy_memory),
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
            STRATEGY_KEY: [],
        }

    def _is_accepted_outcome(self, response: AgentResponse) -> bool:
        if response.plan.need_clarification:
            return False
        total = len(response.tool_results)
        if total == 0:
            return True
        success = len([result for result in response.tool_results if result.success])
        return success == total

    def _upsert_strategy_memory(
        self,
        state: dict[str, list[dict[str, object]]],
        request: AgentRequest,
        response: AgentResponse,
    ) -> None:
        sequence = [call.tool.value for call in response.plan.tool_calls]
        if not sequence:
            return

        strategies = state.get(STRATEGY_KEY, [])
        if not isinstance(strategies, list):
            strategies = []

        key = self._strategy_key(response.plan.intent, sequence)
        accepted = self._is_accepted_outcome(response)
        request_tokens = self._tokenize_text(request.text)
        concat_composition = self._extract_concat_composition(response)

        found: dict[str, object] | None = None
        for item in strategies:
            if isinstance(item, dict) and str(item.get("strategy_key", "")) == key:
                found = item
                break

        if found is None:
            found = {
                "strategy_key": key,
                "intent": response.plan.intent,
                "tool_sequence": sequence,
                "request_example": request.text,
                "token_profile": request_tokens,
                "concat_composition": concat_composition,
                "run_count": 0,
                "accept_count": 0,
                "last_seen_ts": "",
            }
            strategies.append(found)

        run_count = int(found.get("run_count", 0)) + 1
        accept_count = int(found.get("accept_count", 0)) + (1 if accepted else 0)
        existing_profile = found.get("token_profile", [])
        if not isinstance(existing_profile, list):
            existing_profile = []
        merged_profile = list(dict.fromkeys([*existing_profile, *request_tokens]))[:24]

        found["run_count"] = run_count
        found["accept_count"] = accept_count
        if concat_composition:
            found["concat_composition"] = concat_composition
        found["token_profile"] = merged_profile
        found["last_seen_ts"] = self._utc_now_iso()

        state[STRATEGY_KEY] = strategies

    def _strategy_key(self, intent: str, tool_sequence: list[str]) -> str:
        return f"{intent}::{'>'.join(tool_sequence)}"

    def _extract_concat_composition(self, response: AgentResponse) -> str:
        timeline = response.context.get("timeline", {})
        if not isinstance(timeline, dict):
            return ""
        value = timeline.get("composition", "")
        return str(value) if value else ""

    def _tokenize_text(self, text: str) -> list[str]:
        normalized = text.lower().replace("_", " ").replace("-", " ")
        raw_tokens = []
        buffer = ""
        for ch in normalized:
            if ch.isalnum() or ch in {"三", "拼", "导", "出", "封", "面", "剪", "辑", "播", "放", "视", "频", "图"}:
                buffer += ch
            else:
                if buffer:
                    raw_tokens.append(buffer)
                    buffer = ""
        if buffer:
            raw_tokens.append(buffer)

        tokens = [token for token in raw_tokens if len(token) >= 2]
        return list(dict.fromkeys(tokens))[:24]

    def _utc_now_iso(self) -> str:
        return datetime.now(timezone.utc).isoformat(timespec="seconds")
