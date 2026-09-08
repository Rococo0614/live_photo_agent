from __future__ import annotations

from collections.abc import Callable

from ..foundation.library import LibraryService
from ..models import ToolCall, ToolName, ToolResult
from .l0_atomic_tools import L0AtomicTools
from .l1_integrated_tools import L1IntegratedTools
from .l2_vertical_tools import L2VerticalTools


ToolHandler = Callable[[ToolCall, dict[str, object]], ToolResult]


class ToolRegistry:
    """Capability tool registry dispatching across L0/L1/L2 layers."""

    def __init__(self, library_service: LibraryService) -> None:
        self.l0 = L0AtomicTools(library_service)
        self.l1 = L1IntegratedTools(library_service)
        self.l2 = L2VerticalTools()

        self._handlers: dict[ToolName, ToolHandler] = {
            ToolName.SCAN_LIBRARY: self.l0.scan_library,
            ToolName.FILTER_SELECTED: self.l0.filter_selected,
            ToolName.SEARCH_BY_TEXT: self.l1.search_by_text,
            ToolName.DRAFT_EDIT_PLAN: self.l2.draft_edit_plan,
            ToolName.SUMMARIZE_RESULTS: self.l1.summarize_results,
            ToolName.EXTRACT_KEY_FRAMES: self.l0.extract_key_frames,
            ToolName.ESTIMATE_MOTION_SCORE: self.l0.estimate_motion_score,
            ToolName.SUBJECT_SEGMENTATION: self.l0.subject_segmentation,
            ToolName.SELECT_COVER_FRAME: self.l0.select_cover_frame,
            ToolName.CLIP_TRIM: self.l0.clip_trim,
            ToolName.CLIP_SPEED: self.l0.clip_speed,
            ToolName.COLOR_ENHANCE: self.l0.color_enhance,
            ToolName.STABILIZE_CLIP: self.l0.stabilize_clip,
            ToolName.CONCAT_CLIPS: self.l0.concat_clips,
            ToolName.ADD_TEXT_OVERLAY: self.l0.add_text_overlay,
            ToolName.MIX_AUDIO_BGM: self.l0.mix_audio_bgm,
            ToolName.EXPORT_MP4: self.l0.export_mp4,
            ToolName.SET_DISPLAY_FRAME: self.l0.set_display_frame,
            ToolName.EXTRACT_SUBJECT_MATTE: self.l0.extract_subject_matte,
            ToolName.OVERLAY_SUBJECT_CLIP: self.l0.overlay_subject_clip,
            ToolName.EXTRACT_REGION_MATTE: self.l0.extract_region_matte,
            ToolName.LIVE_PHOTO_COLLAGE: self.l2.live_photo_collage,
        }

    def execute(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        handler = self._handlers[call.tool]
        return handler(call, context)
