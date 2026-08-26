from __future__ import annotations

from ..models import (
    CompositionTemplate,
    LayoutRole,
    LayoutSlot,
)

# Frontend grid definition (ui.html: GRID_LAYOUT). Kept here so the resolver and
# any future reverse-engineering share one source of truth for coordinate space.
DEFAULT_GRID_COLS = 120
DEFAULT_GRID_ROWS = 160


class LayoutResolver:
    """Deterministically convert frontend layout_context into a CompositionTemplate.

    The planner must not be trusted to convert grid coordinates into spatial
    placements — it routinely degrades a spatial layout into a serial concat.
    This resolver removes that ambiguity: grid coordinates are converted to
    canvas-relative percentages with exact arithmetic, and foreground (overlay)
    slots are separated from background (spatial tile) slots.
    """

    def __init__(
        self,
        canvas_width: int = 1080,
        canvas_height: int = 1440,
        grid_cols: int = DEFAULT_GRID_COLS,
        grid_rows: int = DEFAULT_GRID_ROWS,
    ) -> None:
        self.canvas_width = canvas_width
        self.canvas_height = canvas_height
        self.grid_cols = grid_cols
        self.grid_rows = grid_rows

    def resolve(self, layout_context: list[dict[str, object]]) -> CompositionTemplate:
        slots: list[LayoutSlot] = []
        for index, item in enumerate(layout_context):
            if not isinstance(item, dict):
                continue
            asset_id = item.get("asset_id")
            if not asset_id:
                continue
            grid = self._grid_box(item)
            if grid is None:
                # No explicit grid box: treat as a full-canvas background slot.
                grid = {"x": 0, "y": 0, "w": self.grid_cols, "h": self.grid_rows}

            is_foreground = bool(item.get("foreground") or item.get("is_overlay"))
            left_pct = self._pct(grid["x"], self.grid_cols)
            top_pct = self._pct(grid["y"], self.grid_rows)
            width_pct = self._pct(grid["w"], self.grid_cols)
            height_pct = self._pct(grid["h"], self.grid_rows)

            slot = LayoutSlot(
                asset_id=str(asset_id),
                slot_id=str(item.get("id", f"slot_{index}")),
                role=LayoutRole.FOREGROUND if is_foreground else LayoutRole.BACKGROUND,
                left_pct=round(left_pct, 3),
                top_pct=round(top_pct, 3),
                width_pct=round(width_pct, 3),
                height_pct=round(height_pct, 3),
                grid_x=grid["x"],
                grid_y=grid["y"],
                grid_w=grid["w"],
                grid_h=grid["h"],
                z_index=int(item.get("z_index", 0)) if item.get("z_index") is not None else 0,
                anchor=str(item.get("anchor", "center")),
                scale=float(item.get("scale", 0.45)),
                x_offset=int(item.get("x_offset", 0)),
                y_offset=int(item.get("y_offset", 0)),
                label=str(item.get("label", "")),
            )
            slots.append(slot)

        slots.sort(key=lambda s: (s.z_index, s.slot_id))
        return CompositionTemplate(
            canvas_width=self.canvas_width,
            canvas_height=self.canvas_height,
            grid_cols=self.grid_cols,
            grid_rows=self.grid_rows,
            slots=slots,
        )

    def _grid_box(self, item: dict[str, object]) -> dict[str, int] | None:
        x = item.get("grid_x")
        y = item.get("grid_y")
        w = item.get("grid_w")
        h = item.get("grid_h")
        if not all(isinstance(v, (int, float)) for v in (x, y, w, h)):
            return None
        x, y, w, h = int(x), int(y), int(w), int(h)
        if w <= 0 or h <= 0:
            return None
        return {"x": x, "y": y, "w": w, "h": h}

    @staticmethod
    def _pct(value: int, total: int) -> float:
        if total <= 0:
            return 0.0
        return max(0.0, min(100.0, value / total * 100.0))
