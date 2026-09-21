"""Collage Template Library v2: 预定义拼贴模板 + 热插拔接口。

模板定义素材在画布上的空间布局 + 时间窗口:
  - slot_count: 需要几个素材
  - slots: 每个 slot 的约束 (主体/背景, 位置, bg_region, 时间窗口, 转场)
  - total_duration_s: 视频总时长
  - description: 供 VLM 理解模板意图

热插拔: 模板从 JSON 文件加载, 新增模板只需添加 JSON 文件。
Schema: docs/template_schema_v2.json
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class Transition:
    """转场效果。"""
    type: str = "fade"           # "fade" | "slide_left" | "slide_right" | "slide_up" | "slide_down" | "zoom"
    duration_ms: int = 300       # 转场时长

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.type, "duration_ms": self.duration_ms}


@dataclass
class SlotConstraint:
    """单个 slot 的约束 (空间 + 时间)。"""
    # --- 空间约束 (v1 兼容) ---
    subject_required: bool = False
    position_preference: str = "any"
    bg_region: str = "full"
    # Free layout grid coordinates (0 means auto)
    grid_x: int = 0
    grid_y: int = 0
    grid_w: int = 0
    grid_h: int = 0
    z_index: int = 0
    pin_to_top: bool = False
    # --- 时间约束 (v2 新增) ---
    start_time_s: float = 0.0       # 绝对开始时间
    end_time_s: float = 6.0         # 绝对结束时间
    enter_transition: Transition | None = field(default_factory=lambda: Transition("fade", 300))
    exit_transition: Transition | None = field(default_factory=lambda: Transition("fade", 300))
    fill_mode: str = "freeze"        # "freeze" | "loop" | "trim" | "stretch"
    # --- 其他 ---
    image_prompt: str = ""
    label: str = ""
    slot_id: str = ""

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {
            "subject_required": self.subject_required,
            "position_preference": self.position_preference,
            "bg_region": self.bg_region,
            "start_time_s": self.start_time_s,
            "end_time_s": self.end_time_s,
            "fill_mode": self.fill_mode,
        }
        if self.grid_w > 0 or self.grid_h > 0:
            d["grid_x"] = self.grid_x
            d["grid_y"] = self.grid_y
            d["grid_w"] = self.grid_w
            d["grid_h"] = self.grid_h
        if self.z_index != 0:
            d["z_index"] = self.z_index
        if self.pin_to_top:
            d["pin_to_top"] = True
        if self.enter_transition:
            d["enter_transition"] = self.enter_transition.to_dict()
        if self.exit_transition:
            d["exit_transition"] = self.exit_transition.to_dict()
        if self.image_prompt:
            d["image_prompt"] = self.image_prompt
        if self.label:
            d["label"] = self.label
        if self.slot_id:
            d["slot_id"] = self.slot_id
        return d


@dataclass
class CollageTemplate:
    """拼贴模板定义 (v2: 带时间窗口)。"""
    id: str
    name: str
    description: str = ""
    slot_count: int = 0
    slots: list[SlotConstraint] = field(default_factory=list)
    canvas_width: int = 1440
    canvas_height: int = 1080
    layout_type: str = "vertical"
    needs_segmentation: bool = False
    # v2 新增
    total_duration_s: float = 6.0   # 视频总时长
    style: str = "editorial"
    board: str = "xhs"

    def __post_init__(self) -> None:
        if self.slot_count == 0 and self.slots:
            self.slot_count = len(self.slots)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "slot_count": self.slot_count,
            "slots": [s.to_dict() for s in self.slots],
            "canvas_width": self.canvas_width,
            "canvas_height": self.canvas_height,
            "layout_type": self.layout_type,
            "needs_segmentation": self.needs_segmentation,
            "total_duration_s": self.total_duration_s,
            "style": self.style,
            "board": self.board,
        }


class TemplateLibrary:
    """模板库: 管理预定义模板, 支持热插拔。"""

    def __init__(self, template_dir: Path | None = None) -> None:
        self.template_dir = template_dir
        self._templates: dict[str, CollageTemplate] = {}
        self._load_builtin()
        if template_dir and template_dir.exists():
            self._load_from_dir(template_dir)

    def _load_builtin(self) -> None:
        """加载内置模板 (v2: 带时间窗口)。"""
        default_dur = 6.0  # 默认 6 秒（每个素材约 2 秒，live photo 通常 3-5 秒）
        builtins = [
            CollageTemplate(
                id="T01",
                name="上下两格",
                description="两个素材上下排列, 同时显示, 全程可见",
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=True, position_preference="lower", bg_region="bottom_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                ],
                needs_segmentation=False,
                total_duration_s=default_dur,
            ),
            CollageTemplate(
                id="T02",
                name="三格竖排",
                description="三个素材上下排列, 上中下各1/3, 同时显示全程可见",
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=False, position_preference="middle", bg_region="full",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=True, position_preference="lower", bg_region="bottom_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                ],
                needs_segmentation=False,
                total_duration_s=default_dur,
            ),
            CollageTemplate(
                id="T03",
                name="四格竖排",
                description="四个素材上下排列, 主体和背景交替, 同时显示",
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=False, position_preference="any", bg_region="full",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=True, position_preference="any", bg_region="bottom_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=False, position_preference="any", bg_region="full",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                ],
                needs_segmentation=False,
                total_duration_s=default_dur,
            ),
            CollageTemplate(
                id="T04",
                name="大主体+两背景",
                description="一个大主体居中, 两个背景素材上下填充, 需要分割",
                slots=[
                    SlotConstraint(subject_required=False, position_preference="upper", bg_region="full",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=True, position_preference="middle", bg_region="full",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=False, position_preference="lower", bg_region="full",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                ],
                needs_segmentation=True,
                total_duration_s=default_dur,
            ),
            CollageTemplate(
                id="T05",
                name="两主体+一背景",
                description="两个主体素材在上和下, 一个背景素材在中间, 需要分割",
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=False, position_preference="middle", bg_region="full",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=True, position_preference="lower", bg_region="bottom_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                ],
                needs_segmentation=True,
                total_duration_s=default_dur,
            ),
            CollageTemplate(
                id="T06",
                name="五格竖排",
                description="五个素材上下排列, 同时显示",
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=False, position_preference="any", bg_region="full",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=True, position_preference="any", bg_region="middle_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=False, position_preference="any", bg_region="full",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                    SlotConstraint(subject_required=True, position_preference="lower", bg_region="bottom_third",
                                   start_time_s=0.0, end_time_s=default_dur, fill_mode="freeze"),
                ],
                needs_segmentation=False,
                total_duration_s=default_dur,
            ),
            # v2 新增: 时序模板
            CollageTemplate(
                id="T07",
                name="三格依次出现",
                description="三个素材各占2秒, 依次出现, 有淡入淡出转场",
                slots=[
                    SlotConstraint(subject_required=False, position_preference="upper", bg_region="top_third",
                                   start_time_s=0.0, end_time_s=2.0, fill_mode="trim",
                                   enter_transition=Transition("fade", 200), exit_transition=Transition("fade", 200)),
                    SlotConstraint(subject_required=False, position_preference="middle", bg_region="middle_third",
                                   start_time_s=2.0, end_time_s=4.0, fill_mode="trim",
                                   enter_transition=Transition("slide_up", 300), exit_transition=Transition("fade", 200)),
                    SlotConstraint(subject_required=False, position_preference="lower", bg_region="bottom_third",
                                   start_time_s=4.0, end_time_s=6.0, fill_mode="trim",
                                   enter_transition=Transition("slide_up", 300), exit_transition=Transition("fade", 200)),
                ],
                needs_segmentation=False,
                total_duration_s=6.0,
            ),
        ]
        for t in builtins:
            self._templates[t.id] = t

    def _load_from_dir(self, template_dir: Path) -> None:
        """从目录加载自定义模板 (热插拔, v2 格式)。"""
        for json_path in sorted(template_dir.glob("*.json")):
            try:
                data = json.loads(json_path.read_text())
                template = self._parse_template_dict(data)
                if template:
                    self._templates[template.id] = template
                    print(f"  [template] loaded {template.id}: {template.name}")
            except Exception as e:
                print(f"  [template] FAILED to load {json_path}: {e}")

    def _parse_template_dict(self, data: dict[str, Any]) -> CollageTemplate | None:
        """Parse a template dict (supports v1 and v2 format)."""
        slots: list[SlotConstraint] = []
        for s in data.get("slots", []):
            enter_t = s.get("enter_transition")
            exit_t = s.get("exit_transition")
            slots.append(SlotConstraint(
                subject_required=s.get("subject_required", False),
                position_preference=s.get("position_preference", "any"),
                bg_region=s.get("bg_region", "full"),
                grid_x=s.get("grid_x", 0),
                grid_y=s.get("grid_y", 0),
                grid_w=s.get("grid_w", 0),
                grid_h=s.get("grid_h", 0),
                z_index=s.get("z_index", 0),
                pin_to_top=s.get("pin_to_top", False),
                start_time_s=s.get("start_time_s", 0.0),
                end_time_s=s.get("end_time_s", data.get("total_duration_s", 6.0)),
                enter_transition=Transition(enter_t["type"], enter_t["duration_ms"]) if enter_t and isinstance(enter_t, dict) else Transition("fade", 300),
                exit_transition=Transition(exit_t["type"], exit_t["duration_ms"]) if exit_t and isinstance(exit_t, dict) else Transition("fade", 300),
                fill_mode=s.get("fill_mode", "freeze"),
                image_prompt=s.get("image_prompt", ""),
                label=s.get("label", ""),
                slot_id=s.get("slot_id", ""),
            ))
        return CollageTemplate(
            id=data["id"],
            name=data["name"],
            description=data.get("description", ""),
            slot_count=data.get("slot_count", len(slots)),
            slots=slots,
            canvas_width=data.get("canvas_width", 1440),
            canvas_height=data.get("canvas_height", 1080),
            layout_type=data.get("layout_type", "vertical"),
            needs_segmentation=data.get("needs_segmentation", False),
            total_duration_s=data.get("total_duration_s", 6.0),
            style=data.get("style", "editorial"),
            board=data.get("board", "xhs"),
        )

    def get(self, template_id: str) -> CollageTemplate | None:
        return self._templates.get(template_id)

    def list_all(self) -> list[CollageTemplate]:
        return sorted(self._templates.values(), key=lambda t: t.id)

    def find_by_slot_count(self, count: int) -> list[CollageTemplate]:
        """按 slot 数量查找模板。"""
        return [t for t in self._templates.values() if t.slot_count == count]

    def add_template(self, template: CollageTemplate) -> None:
        """动态添加模板。"""
        self._templates[template.id] = template

    def save_template(self, template: CollageTemplate) -> Path:
        """保存模板到目录 (热插拔)。"""
        if not self.template_dir:
            raise RuntimeError("No template_dir configured")
        self.template_dir.mkdir(parents=True, exist_ok=True)
        path = self.template_dir / f"{template.id}.json"
        path.write_text(json.dumps(template.to_dict(), indent=2, ensure_ascii=False))
        self._templates[template.id] = template
        return path
