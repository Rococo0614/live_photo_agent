"""Collage Template Library: 预定义拼贴模板 + 热插拔接口。

模板定义素材在画布上的空间布局:
  - slot_count: 需要几个素材
  - slots: 每个 slot 的约束 (主体/背景, 位置, bg_region)
  - description: 供 VLM 理解模板意图

热插拔: 模板从 JSON 文件加载, 新增模板只需添加 JSON 文件。
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class SlotConstraint:
    """单个 slot 的约束。"""
    subject_required: bool = False       # 是否需要主体
    position_preference: str = "any"    # "upper" | "middle" | "lower" | "any"
    bg_region: str = "full"             # "top_third" | "bottom_third" | "middle_third" | "full"

    def to_dict(self) -> dict[str, Any]:
        return {
            "subject_required": self.subject_required,
            "position_preference": self.position_preference,
            "bg_region": self.bg_region,
        }


@dataclass
class CollageTemplate:
    """拼贴模板定义。"""
    id: str                              # "T01", "T02", ...
    name: str                            # "上下三格"
    description: str                     # 供 VLM 理解
    slot_count: int                      # 需要的素材数
    slots: list[SlotConstraint]          # 每个 slot 的约束
    canvas_width: int = 1440
    canvas_height: int = 1080
    layout_type: str = "vertical"        # "vertical" | "horizontal" | "grid" | "free"
    needs_segmentation: bool = False     # 是否需要 Cutie 主体分割 (overlay 模板才需要)

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
        """加载内置模板。"""
        builtins = [
            CollageTemplate(
                id="T01",
                name="上下两格",
                description="两个素材上下排列, 上面的保留上1/3背景, 下面的保留下1/3背景, 主体跨越中间",
                slot_count=2,
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third"),
                    SlotConstraint(subject_required=True, position_preference="lower", bg_region="bottom_third"),
                ],
                needs_segmentation=False,
            ),
            CollageTemplate(
                id="T02",
                name="三格竖排",
                description="三个素材上下排列, 上中下各1/3, 中间为纯背景素材, 上下为主体素材",
                slot_count=3,
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third"),
                    SlotConstraint(subject_required=False, position_preference="middle", bg_region="full"),
                    SlotConstraint(subject_required=True, position_preference="lower", bg_region="bottom_third"),
                ],
                needs_segmentation=False,
            ),
            CollageTemplate(
                id="T03",
                name="四格竖排",
                description="四个素材上下排列, 主体和背景交替",
                slot_count=4,
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third"),
                    SlotConstraint(subject_required=False, position_preference="any", bg_region="full"),
                    SlotConstraint(subject_required=True, position_preference="any", bg_region="bottom_third"),
                    SlotConstraint(subject_required=False, position_preference="any", bg_region="full"),
                ],
                needs_segmentation=False,
            ),
            CollageTemplate(
                id="T04",
                name="大主体+两背景",
                description="一个大主体居中, 两个背景素材上下填充",
                slot_count=3,
                slots=[
                    SlotConstraint(subject_required=False, position_preference="upper", bg_region="full"),
                    SlotConstraint(subject_required=True, position_preference="middle", bg_region="full"),
                    SlotConstraint(subject_required=False, position_preference="lower", bg_region="full"),
                ],
                needs_segmentation=True,
            ),
            CollageTemplate(
                id="T05",
                name="两主体+一背景",
                description="两个主体素材在上和下, 一个背景素材在中间",
                slot_count=3,
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third"),
                    SlotConstraint(subject_required=False, position_preference="middle", bg_region="full"),
                    SlotConstraint(subject_required=True, position_preference="lower", bg_region="bottom_third"),
                ],
                needs_segmentation=True,
            ),
            CollageTemplate(
                id="T06",
                name="五格竖排",
                description="五个素材上下排列, 适应更多素材",
                slot_count=5,
                slots=[
                    SlotConstraint(subject_required=True, position_preference="upper", bg_region="top_third"),
                    SlotConstraint(subject_required=False, position_preference="any", bg_region="full"),
                    SlotConstraint(subject_required=True, position_preference="any", bg_region="middle_third"),
                    SlotConstraint(subject_required=False, position_preference="any", bg_region="full"),
                    SlotConstraint(subject_required=True, position_preference="lower", bg_region="bottom_third"),
                ],
                needs_segmentation=False,
            ),
        ]
        for t in builtins:
            self._templates[t.id] = t

    def _load_from_dir(self, template_dir: Path) -> None:
        """从目录加载自定义模板 (热插拔)。"""
        for json_path in sorted(template_dir.glob("*.json")):
            try:
                data = json.loads(json_path.read_text())
                template = CollageTemplate(
                    id=data["id"],
                    name=data["name"],
                    description=data["description"],
                    slot_count=data["slot_count"],
                    slots=[SlotConstraint(**s) for s in data["slots"]],
                    canvas_width=data.get("canvas_width", 1440),
                    canvas_height=data.get("canvas_height", 1080),
                    layout_type=data.get("layout_type", "vertical"),
                    needs_segmentation=data.get("needs_segmentation", False),
                )
                self._templates[template.id] = template
                print(f"  [template] loaded {template.id}: {template.name}")
            except Exception as e:
                print(f"  [template] FAILED to load {json_path}: {e}")

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
