from __future__ import annotations

from enum import Enum
from pathlib import Path

from pydantic import BaseModel, Field


class AssetPreprocessSummary(BaseModel):
    version: str = "v2"
    media_format: str
    capture_time: str | None = None
    content_tags: list[str] = Field(default_factory=list)
    content_summary: str = ""
    technical_signals: dict[str, object] = Field(default_factory=dict)
    semantic_signals: dict[str, object] = Field(default_factory=dict)
    coarse_semantics: dict[str, object] = Field(default_factory=dict)
    provenance: dict[str, object] = Field(default_factory=dict)
    # Retained for compatibility with existing tools and index rows.
    quality_signals: dict[str, object] = Field(default_factory=dict)
    editability_signals: dict[str, object] = Field(default_factory=dict)
    source: str = "scan"
    updated_at: str | None = None


class LivePhotoAsset(BaseModel):
    asset_id: str
    image_path: Path
    motion_path: Path | None = None
    tags: list[str] = Field(default_factory=list)
    metadata: dict[str, str] = Field(default_factory=dict)
    preprocess_summary: AssetPreprocessSummary | None = None


class LayoutRole(str, Enum):
    """How a slot participates in the final composition."""

    BACKGROUND = "background"  # spatial tile on the canvas (z-order low → high)
    FOREGROUND = "foreground"  # segmented subject overlaid on top of the canvas


class LayoutSlot(BaseModel):
    """One resolved placement on the canvas (spatial + temporal v2).

    v2 adds: start_time_s, end_time_s, enter/exit_transition, fill_mode.
    """

    asset_id: str
    slot_id: str = ""
    role: LayoutRole = LayoutRole.BACKGROUND
    # Spatial: canvas-relative percentages
    left_pct: float = 0.0
    top_pct: float = 0.0
    width_pct: float = 100.0
    height_pct: float = 100.0
    grid_x: int = 0
    grid_y: int = 0
    grid_w: int = 0
    grid_h: int = 0
    z_index: int = 0
    # Temporal (v2)
    start_time_s: float = 0.0
    end_time_s: float = 6.0
    enter_transition_type: str = "fade"
    enter_transition_ms: int = 300
    exit_transition_type: str = "fade"
    exit_transition_ms: int = 300
    fill_mode: str = "freeze"
    # Foreground tuning
    anchor: str = "center"
    scale: float = 0.45
    x_offset: int = 0
    y_offset: int = 0
    label: str = ""
    edit_rect: dict[str, float] | None = None
    pin_to_top: bool = False
    image_prompt: str = ""


class CompositionTemplate(BaseModel):
    """Deterministic layout description (spatial + temporal v2).

    Forward:  layout_context (grid) -> LayoutResolver -> CompositionTemplate
    Reverse:  decomposed成品 -> CompositionTemplate (future)
    Both feed the same executor (compose_videos_spatial + overlay_subject_clip).
    """

    canvas_width: int = 1080
    canvas_height: int = 1440
    grid_cols: int = 120
    grid_rows: int = 160
    slots: list[LayoutSlot] = Field(default_factory=list)
    total_duration_s: float = 6.0  # v2: video duration


class ToolName(str, Enum):
    SCAN_LIBRARY = "scan_library"
    FILTER_SELECTED = "filter_selected"
    SEARCH_BY_TEXT = "search_by_text"
    DRAFT_EDIT_PLAN = "draft_edit_plan"
    SUMMARIZE_RESULTS = "summarize_results"

    # L0 atomic tools for live photo triptych workflow
    EXTRACT_KEY_FRAMES = "extract_key_frames"
    ESTIMATE_MOTION_SCORE = "estimate_motion_score"
    SUBJECT_SEGMENTATION = "subject_segmentation"
    SELECT_COVER_FRAME = "select_cover_frame"
    CLIP_TRIM = "clip_trim"
    CLIP_SPEED = "clip_speed"
    COLOR_ENHANCE = "color_enhance"
    STABILIZE_CLIP = "stabilize_clip"
    CONCAT_CLIPS = "concat_clips"
    ADD_TEXT_OVERLAY = "add_text_overlay"
    MIX_AUDIO_BGM = "mix_audio_bgm"
    EXPORT_MP4 = "export_mp4"
    SET_DISPLAY_FRAME = "set_display_frame"
    EXTRACT_SUBJECT_MATTE = "extract_subject_matte"
    OVERLAY_SUBJECT_CLIP = "overlay_subject_clip"
    EXTRACT_REGION_MATTE = "extract_region_matte"

    # L2 vertical tool: live photo collage (segmentation + free layout + composition)
    LIVE_PHOTO_COLLAGE = "live_photo_collage"

    # L2 vertical tool: template collage (direct stacking, no segmentation)
    TEMPLATE_COLLAGE = "template_collage"

    # L2 vertical tool: smart collage (search + template match + composition)
    SMART_COLLAGE = "smart_collage"

    # L2 vertical tool: VLM-based asset content summarization
    ASSET_SUMMARIZE = "asset_summarize"


class ToolCall(BaseModel):
    tool: ToolName
    reason: str
    arguments: dict[str, object] = Field(default_factory=dict)


class ExecutionPlan(BaseModel):
    user_goal: str
    intent: str
    selected_asset_ids: list[str] = Field(default_factory=list)
    required_context: list[str] = Field(default_factory=list)
    tool_calls: list[ToolCall] = Field(default_factory=list)
    need_clarification: bool = False
    clarification_questions: list[str] = Field(default_factory=list)
    blocking_missing_info: list[str] = Field(default_factory=list)


class AgentRequest(BaseModel):
    user_id: str
    text: str
    library_root: str | Path  # Accept both str and Path
    # Request entry point.  The UI has three distinct workflows; keeping the
    # mode explicit prevents a canvas request from being sent through the
    # free-form planner used by the chat workflow.
    mode: str = "agent"  # layout | chat | agent (legacy)
    selected_asset_ids: list[str] = Field(default_factory=list)
    guided_tool_names: list[ToolName] = Field(default_factory=list)
    input_image_paths: list[str | Path] = Field(default_factory=list)
    input_video_paths: list[str | Path] = Field(default_factory=list)
    layout_context: list[dict[str, object]] = Field(default_factory=list)
    operation_log: list[dict[str, object]] = Field(default_factory=list)
    # Structured, whitelisted media operations extracted from natural
    # language.  These are intentionally separate from planner tool_calls:
    # the layout itself remains authoritative and is never re-planned.
    processing_directives: list[dict[str, object]] = Field(default_factory=list)
    # Human-in-the-loop redo: set when resubmitting a rejected run. The planner
    # sees retry_feedback appended to the request text so it can correct course.
    retry_of_run_id: str = ""
    retry_feedback: str = ""
    # Conversation history: list of {role, text, template_id?, final_video?}
    # Lets the planner understand context like "换个模板" (change template).
    conversation_history: list[dict[str, object]] = Field(default_factory=list)


class ToolResult(BaseModel):
    tool: ToolName
    success: bool
    payload: dict[str, object] = Field(default_factory=dict)


class PipelineStage(str, Enum):
    INPUT = "input"
    INTENT = "intent"
    CONTEXT = "context"
    RECOMMEND = "recommend"
    TOOL_SELECTION = "tool_selection"
    OUTPUT = "output"


class PipelineTrace(BaseModel):
    stage: PipelineStage
    detail: str


class FoundationState(BaseModel):
    pipeline_version: str
    planner_model: str
    asset_count: int
    selected_asset_count: int
    session_memory_size: int
    multimodal_memory_size: int


class AgentResponse(BaseModel):
    plan: ExecutionPlan
    context: dict[str, object]
    tool_results: list[ToolResult]
    final_response: str
    memory_updates: list[str]
    review: str
    pipeline_trace: list[PipelineTrace] = Field(default_factory=list)
    foundation_state: FoundationState | None = None
