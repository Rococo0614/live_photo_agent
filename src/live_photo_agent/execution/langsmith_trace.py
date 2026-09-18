"""LangSmith trace integration for the Live Photo Agent pipeline.

Captures the full pipeline as a nested trace:
  user_input → planner_raw_plan → normalize → validation → execution → retry → output

This lets you go from a symptom ("拼贴没出来") directly to the root cause
(planner bad plan? normalize bug? validation blocked? tool failure?)
in the LangSmith UI.

Usage:
    from live_photo_agent.execution.langsmith_trace import LangSmithTracer
    tracer = LangSmithTracer()
    with tracer.start_run(request) as run:
        run.add_node("planner", raw_plan)
        run.add_node("normalize", normalized_plan)
        run.add_node("validation", errors)
        run.add_node("execution", tool_results)
        run.finish(final_response)
"""
from __future__ import annotations

import logging
import os
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Iterator

logger = logging.getLogger("live_photo_agent.tracing")


@dataclass
class TraceNode:
    """A single step in the pipeline trace."""
    name: str
    timestamp: str
    data: dict[str, object] = field(default_factory=dict)
    children: list[TraceNode] = field(default_factory=list)
    duration_ms: int = 0
    error: str | None = None


@dataclass
class PipelineTrace:
    """Full pipeline trace for a single agent.execute() call."""
    run_id: str
    user_input: str
    start_time: str
    nodes: list[TraceNode] = field(default_factory=list)
    end_time: str = ""
    final_output: str = ""
    total_duration_ms: int = 0

    def add_node(
        self,
        name: str,
        data: dict[str, object],
        duration_ms: int = 0,
        error: str | None = None,
    ) -> TraceNode:
        node = TraceNode(
            name=name,
            timestamp=datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            data=data,
            duration_ms=duration_ms,
            error=error,
        )
        self.nodes.append(node)
        return node

    def to_dict(self) -> dict[str, object]:
        return {
            "run_id": self.run_id,
            "user_input": self.user_input,
            "start_time": self.start_time,
            "end_time": self.end_time,
            "total_duration_ms": self.total_duration_ms,
            "final_output": self.final_output,
            "nodes": [
                {
                    "name": n.name,
                    "timestamp": n.timestamp,
                    "duration_ms": n.duration_ms,
                    "error": n.error,
                    "data": n.data,
                }
                for n in self.nodes
            ],
        }


class LangSmithTracer:
    """Wraps LangSmith client to capture structured pipeline traces.

    Falls back to local file logging if LangSmith is not configured
    (no API key), so the trace is always available for debugging.
    """

    def __init__(self) -> None:
        self._client: Any = None
        self._enabled = bool(os.environ.get("LANGSMITH_API_KEY"))
        self._project = os.environ.get("LANGSMITH_PROJECT", "live-photo-agent")
        self._local_log = os.environ.get(
            "LIVE_PHOTO_AGENT_TRACE_LOG",
            ".agent_trace.jsonl",
        )

        if self._enabled:
            try:
                from langsmith import Client
                self._client = Client()
            except ImportError:
                logger.warning("langsmith not installed, traces will be local-only")
                self._enabled = False

    @property
    def enabled(self) -> bool:
        return self._enabled

    @contextmanager
    def start_run(self, request: Any) -> Iterator[PipelineTrace]:
        """Context manager that starts a trace run and persists it on exit."""
        from time import perf_counter
        start = perf_counter()
        run_id = f"run_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S_%f')}"
        trace = PipelineTrace(
            run_id=run_id,
            user_input=str(getattr(request, "text", "")),
            start_time=datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
        )
        try:
            yield trace
        except Exception as exc:
            trace.add_node("error", {"exception": str(exc)}, error=str(exc))
            raise
        finally:
            trace.end_time = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
            trace.total_duration_ms = max(0, int((perf_counter() - start) * 1000))
            self._persist(trace)

    def _persist(self, trace: PipelineTrace) -> None:
        """Persist trace to LangSmith (if enabled) and local file (always)."""
        trace_dict = trace.to_dict()

        # Always write to local JSONL for offline debugging.
        try:
            from pathlib import Path
            log_path = Path(self._local_log)
            log_path.parent.mkdir(parents=True, exist_ok=True)
            import json
            with log_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(trace_dict, ensure_ascii=False, default=str) + "\n")
        except Exception as exc:
            logger.warning("Failed to write local trace: %s", exc)

        # Push to LangSmith if enabled.
        if self._enabled and self._client:
            try:
                self._client.create_run(
                    name="live_photo_agent.execute",
                    project_name=self._project,
                    inputs={"user_input": trace.user_input},
                    outputs={"final_output": trace.final_output, "trace": trace_dict},
                    run_type="chain",
                )
            except Exception as exc:
                logger.warning("Failed to push trace to LangSmith: %s", exc)

    def capture_plan_diff(
        self,
        raw_plan: Any,
        normalized_plan: Any,
    ) -> dict[str, object]:
        """Capture the diff between raw planner output and normalized plan.

        This is the most valuable trace node — it shows exactly what the
        constraint layer changed.
        """
        raw_tools = []
        normalized_tools = []
        if hasattr(raw_plan, "tool_calls"):
            raw_tools = [
                {"tool": c.tool.value, "args": dict(c.arguments)}
                for c in raw_plan.tool_calls
            ]
        if hasattr(normalized_plan, "tool_calls"):
            normalized_tools = [
                {"tool": c.tool.value, "args": dict(c.arguments)}
                for c in normalized_plan.tool_calls
            ]

        added = []
        removed = []
        for n in normalized_tools:
            if n not in raw_tools:
                added.append(n)
        for r in raw_tools:
            if r not in normalized_tools:
                removed.append(r)

        return {
            "raw_plan_tools": raw_tools,
            "normalized_plan_tools": normalized_tools,
            "added_by_normalize": added,
            "removed_by_normalize": removed,
            "raw_intent": getattr(raw_plan, "intent", ""),
            "normalized_intent": getattr(normalized_plan, "intent", ""),
        }


# Singleton tracer instance.
_tracer: LangSmithTracer | None = None


def get_tracer() -> LangSmithTracer:
    global _tracer
    if _tracer is None:
        _tracer = LangSmithTracer()
    return _tracer
