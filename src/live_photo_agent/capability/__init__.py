from __future__ import annotations

from typing import Any

__all__ = ["ToolContract", "TOOL_CONTRACTS", "CapabilityLayer", "ToolRegistry"]


def __getattr__(name: str) -> Any:
    if name in {"ToolContract", "TOOL_CONTRACTS", "CapabilityLayer"}:
        from .contracts import CapabilityLayer, TOOL_CONTRACTS, ToolContract

        exports = {
            "ToolContract": ToolContract,
            "TOOL_CONTRACTS": TOOL_CONTRACTS,
            "CapabilityLayer": CapabilityLayer,
        }
        return exports[name]
    if name == "ToolRegistry":
        from .registry import ToolRegistry

        return ToolRegistry
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
