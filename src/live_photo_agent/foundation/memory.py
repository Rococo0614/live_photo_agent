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
        """Record this turn in session memory.

        Turns that produced a real deliverable (at least one tool executed and no
        clarification requested) are held as *pending*: asset-facing memory
        (``multimodal_memory``) and the learned tool-sequence memory
        (``strategy_memory``) are NOT written yet. They only commit once a human
        explicitly confirms the result via :meth:`confirm_feedback`. Turns with no
        deliverable (clarification / planner errors) have nothing to review, so
        they are finalized immediately as before.
        """
        state = self._load_state()
        summary = dict(response.context.get("summary", {}))
        focus_asset_ids = self._derive_focus_asset_ids(response)
        graph_observability = response.context.get("graph_observability", {})
        if not isinstance(graph_observability, dict):
            graph_observability = {}
        run_id = str(graph_observability.get("run_id", ""))
        route_reason = str(graph_observability.get("route_reason", ""))

        session_entry: dict[str, object] = {
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
            "run_id": run_id,
            "graph_run_id": run_id,
            "graph_route_reason": route_reason,
        }

        pending_payload = {
            "intent": response.plan.intent,
            "tool_sequence": session_entry["tool_sequence"],
            "focus_asset_ids": focus_asset_ids,
            "selected_asset_ids": list(request.selected_asset_ids),
            "required_context": list(response.plan.required_context),
            "summary_focus_asset_count": int(summary.get("focus_asset_count", len(focus_asset_ids))),
            "concat_composition": self._extract_concat_composition(response),
            "request_text": request.text,
            "request_tokens": self._tokenize_text(request.text),
        }

        has_deliverable = bool(response.tool_results) and not response.plan.need_clarification

        if has_deliverable:
            session_entry.update(
                {
                    "accepted": None,
                    "feedback_comment": "",
                    "requires_feedback": True,
                    "_feedback_recorded": False,
                    "_committed": False,
                    "_pending_commit": pending_payload,
                }
            )
            commit_note = f"run_id={run_id} 待人工反馈确认后才会计入策略/资产记忆"
        else:
            accepted = self._is_accepted_outcome(response)
            session_entry.update(
                {
                    "accepted": accepted,
                    "feedback_comment": "",
                    "requires_feedback": False,
                    "_feedback_recorded": True,
                    "_committed": True,
                }
            )
            state.setdefault(MULTIMODAL_KEY, []).append(
                self._build_multimodal_entry(request.user_id, pending_payload, run_id)
            )
            self._upsert_strategy_memory_from_payload(state, pending_payload, accepted=accepted)
            commit_note = f"run_id={run_id} 无待复核产出，已立即计入记忆（accepted={accepted}）"

        state[SESSION_KEY].append(session_entry)
        self.memory_file.parent.mkdir(parents=True, exist_ok=True)
        self.memory_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        return [
            f"写入会话记忆 1 条（总计 {len(state[SESSION_KEY])}）",
            commit_note,
        ]

    def confirm_feedback(
        self,
        accepted: bool,
        run_id: str = "",
        comment: str = "",
        asset_ids: list[str] | None = None,
    ) -> dict[str, object]:
        """Resolve a pending turn with an explicit human decision.

        Only on ``accepted=True`` does the turn's deliverable get written into
        ``multimodal_memory``. Either way, ``strategy_memory`` is updated once
        (run_count always increments, accept_count only on acceptance) so future
        planning learns from both good and bad outcomes.
        """
        state = self._load_state()
        session_entries = state[SESSION_KEY]

        target: dict[str, object] | None = None
        if run_id:
            for entry in reversed(session_entries):
                if isinstance(entry, dict) and str(entry.get("run_id", "")) == run_id:
                    target = entry
                    break
        if target is None:
            for entry in reversed(session_entries):
                if isinstance(entry, dict) and not entry.get("_feedback_recorded"):
                    target = entry
                    break

        if target is None:
            return {"recorded": False, "reason": "no_matching_session_entry", "accepted": accepted}

        target["accepted"] = accepted
        target["_feedback_recorded"] = True
        if comment:
            target["feedback_comment"] = comment
        if asset_ids:
            target["feedback_asset_ids"] = list(asset_ids)

        committed_to_memory = False
        pending_payload = target.get("_pending_commit")
        if isinstance(pending_payload, dict) and not target.get("_committed"):
            if accepted:
                state.setdefault(MULTIMODAL_KEY, []).append(
                    self._build_multimodal_entry(str(target.get("user_id", "")), pending_payload, str(target.get("run_id", "")))
                )
                committed_to_memory = True
            self._upsert_strategy_memory_from_payload(state, pending_payload, accepted=accepted)
            target["_committed"] = True
            target.pop("_pending_commit", None)

        state[SESSION_KEY] = session_entries
        self.memory_file.parent.mkdir(parents=True, exist_ok=True)
        self.memory_file.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")

        return {
            "recorded": True,
            "accepted": accepted,
            "run_id": str(target.get("run_id", "")),
            "committed_to_memory": committed_to_memory,
            "session_memory_size": len(session_entries),
        }

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

    def _build_multimodal_entry(self, user_id: str, payload: dict[str, object], run_id: str) -> dict[str, object]:
        return {
            "ts": self._utc_now_iso(),
            "user_id": user_id,
            "intent": payload.get("intent", ""),
            "focus_asset_ids": payload.get("focus_asset_ids", []),
            "selected_asset_ids": payload.get("selected_asset_ids", []),
            "required_context": payload.get("required_context", []),
            "summary_focus_asset_count": payload.get("summary_focus_asset_count", 0),
            "graph_run_id": run_id,
        }

    def _upsert_strategy_memory_from_payload(
        self,
        state: dict[str, list[dict[str, object]]],
        payload: dict[str, object],
        accepted: bool,
    ) -> None:
        sequence = [str(item) for item in payload.get("tool_sequence", [])]
        if not sequence:
            return

        strategies = state.get(STRATEGY_KEY, [])
        if not isinstance(strategies, list):
            strategies = []

        intent = str(payload.get("intent", ""))
        key = self._strategy_key(intent, sequence)
        request_tokens = [str(token) for token in payload.get("request_tokens", [])]
        concat_composition = str(payload.get("concat_composition", ""))

        found: dict[str, object] | None = None
        for item in strategies:
            if isinstance(item, dict) and str(item.get("strategy_key", "")) == key:
                found = item
                break

        if found is None:
            found = {
                "strategy_key": key,
                "intent": intent,
                "tool_sequence": sequence,
                "request_example": str(payload.get("request_text", "")),
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
