from __future__ import annotations

import math
import shutil
from pathlib import Path

from ..config import settings
from ..foundation import LibraryService, MediaOps, MediaOpsError
from ..models import LivePhotoAsset, ToolCall, ToolResult


class L0AtomicTools:
    """L0 atomic tools: single-purpose media operations."""

    def __init__(self, library_service: LibraryService) -> None:
        self.library_service = library_service
        self.media_ops = MediaOps()

    def scan_library(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        library_root = Path(context["library_root"])
        requested_root_raw = call.arguments.get("library_root")
        requested_root = Path(str(requested_root_raw)).expanduser().resolve() if requested_root_raw else None

        # InputPreprocessor already loads assets once per request. Reuse that cache unless
        # tool call explicitly asks for another root.
        cached_assets = context.get("assets")
        if requested_root is None and isinstance(cached_assets, list):
            assets = cached_assets
            scan_source = "preloaded"
        elif requested_root is not None and requested_root == library_root and isinstance(cached_assets, list):
            assets = cached_assets
            scan_source = "preloaded"
        else:
            target_root = requested_root if requested_root is not None else library_root
            assets = self.library_service.scan_live_photos(target_root)
            context["library_root"] = target_root
            scan_source = "rescanned"

        context["assets"] = assets
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={
                "asset_count": len(assets),
                "asset_ids": [asset.asset_id for asset in assets],
                "scan_source": scan_source,
            },
        )

    def filter_selected(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        assets = self._get_assets(context)
        selected_assets = self.library_service.select_assets(
            assets,
            [str(asset_id) for asset_id in call.arguments.get("selected_asset_ids", [])],
        )
        context["selected_assets"] = selected_assets
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={"selected_count": len(selected_assets), "selected_ids": [asset.asset_id for asset in selected_assets]},
        )

    def extract_key_frames(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._assets_for_l0(call, context)
        frame_interval_ms = int(call.arguments.get("frame_interval_ms", 400))
        max_frames = int(call.arguments.get("max_frames", 8))
        sampling_strategy = str(call.arguments.get("sampling_strategy", "uniform")).strip().lower()
        frames: dict[str, list[str]] = {}
        effective_settings: dict[str, dict[str, object]] = {}
        workspace = self._workspace_dir(context)

        try:
            for asset in focus_assets:
                motion_path = self._asset_motion_path(asset)
                if motion_path is None:
                    continue

                interval_ms, frame_budget, motion_score = self._resolve_keyframe_settings(
                    motion_path=motion_path,
                    frame_interval_ms=frame_interval_ms,
                    max_frames=max_frames,
                    sampling_strategy=sampling_strategy,
                )

                output_dir = workspace / "key_frames" / asset.asset_id
                extracted = self.media_ops.extract_frames(
                    motion_path,
                    output_dir,
                    frame_interval_ms=interval_ms,
                    max_frames=frame_budget,
                )
                frames[asset.asset_id] = [str(path) for path in extracted]
                effective_settings[asset.asset_id] = {
                    "frame_interval_ms": interval_ms,
                    "max_frames": frame_budget,
                    "sampling_strategy": sampling_strategy,
                    "motion_score": motion_score,
                }
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "extract_key_frames_failed"},
            )

        context["key_frames"] = frames
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={
                "frame_count": sum(len(items) for items in frames.values()),
                "frames_by_asset": frames,
                "frame_interval_ms": frame_interval_ms,
                "sampling_strategy": sampling_strategy,
                "effective_settings": effective_settings,
            },
        )

    def estimate_motion_score(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._assets_for_l0(call, context)
        scores: dict[str, float] = {}

        try:
            for asset in focus_assets:
                motion_path = self._asset_motion_path(asset)
                if motion_path is None:
                    continue
                scores[asset.asset_id] = round(self.media_ops.motion_score(motion_path), 4)
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "estimate_motion_score_failed"},
            )

        context["motion_scores"] = scores
        return ToolResult(tool=call.tool, success=True, payload={"motion_scores": scores})

    def subject_segmentation(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._assets_for_l0(call, context)
        mode = str(call.arguments.get("mode", "person_first"))
        workspace = self._segmentation_workspace_dir()
        masks: dict[str, str] = {}
        ratios: dict[str, float] = {}

        try:
            for asset in focus_assets:
                image_path = Path(asset.image_path)
                mask_path = workspace / "masks" / f"{asset.asset_id}.png"
                output = self.media_ops.segment_subject(image_path, mask_path, mode=mode)
                masks[asset.asset_id] = str(output["mask_path"])
                ratios[asset.asset_id] = float(output["foreground_ratio"])
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "subject_segmentation_failed"},
            )

        context["subject_masks"] = masks
        context["subject_foreground_ratios"] = ratios
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={
                "mask_count": len(masks),
                "segmented_asset_ids": list(masks.keys()),
                "mode": mode,
                "mask_paths": masks,
                "foreground_ratios": ratios,
            },
        )

    def extract_subject_matte(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._assets_for_l0(call, context)
        mode = str(call.arguments.get("mode", "mog2")).strip().lower()
        workspace = self._segmentation_workspace_dir()
        mattes: dict[str, dict[str, object]] = {}
        average_ratios: dict[str, float] = {}
        failed_asset_ids: list[str] = []

        for asset in focus_assets:
            motion_path = self._asset_motion_path(asset)
            output_dir = workspace / "subject_mattes" / asset.asset_id
            if output_dir.exists():
                shutil.rmtree(output_dir, ignore_errors=True)
            try:
                use_motion = str(call.arguments.get("use_motion", "")).strip().lower() in ("1", "true", "yes")
                # edit_rect 来源优先级：
                #   1. call.arguments 中显式传入（planner 指定）
                #   2. composition_template 中对应 asset 的 slot.edit_rect（前端画布框选）
                edit_rect = self._resolve_edit_rect(call, context, asset.asset_id)
                if motion_path is not None and use_motion:
                    result = self.media_ops.extract_subject_matte_frames(motion_path, output_dir, mode=mode)
                else:
                    optimize_cfg = call.arguments.get("optimize_filters")
                    if optimize_cfg is not None:
                        result = self._extract_subject_matte_from_still(asset, output_dir, edit_rect, optimize_cfg)
                    else:
                        result = self._extract_subject_matte_from_still(asset, output_dir, edit_rect)
            except MediaOpsError as exc:
                failed_asset_ids.append(asset.asset_id)
                return ToolResult(
                    tool=call.tool,
                    success=False,
                    payload={"error": str(exc), "error_code": "extract_subject_matte_failed", "asset_id": asset.asset_id},
                )
            mattes[asset.asset_id] = result
            average_ratios[asset.asset_id] = float(result.get("average_foreground_ratio", 0.0))

        context["subject_mattes"] = mattes
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={
                "matte_count": len(mattes),
                "matted_asset_ids": list(mattes.keys()),
                "average_foreground_ratios": average_ratios,
                "failed_asset_ids": failed_asset_ids,
            },
        )

    def _extract_subject_matte_from_still(
        self,
        asset: LivePhotoAsset,
        output_dir: Path,
        edit_rect: dict[str, float] | None = None,
        optimize_cfg: dict | None = None,
    ) -> dict[str, object]:
        output_dir.mkdir(parents=True, exist_ok=True)
        mask_path = output_dir / "mask.png"
        # Resolve a grabCut rectangle from the user's framed region (normalized
        # 0-1 within the source still). This is what makes "分割 jpeg 框选区域"
        # actually cut out the framed subject instead of the whole frame.
        still_rect = self._normalize_edit_rect_to_pixels(asset, edit_rect)
        segmentation = self.media_ops.segment_subject(
            Path(asset.image_path), mask_path, mode="person_first", rect=still_rect
        )

        try:
            import cv2
        except ImportError as exc:
            raise MediaOpsError("opencv_missing") from exc

        image = cv2.imread(str(asset.image_path), cv2.IMREAD_COLOR)
        alpha = cv2.imread(str(mask_path), cv2.IMREAD_GRAYSCALE)
        if image is None or alpha is None:
            raise MediaOpsError(f"image_decode_failed: {asset.image_path}")

        rgba = cv2.cvtColor(image, cv2.COLOR_BGR2BGRA)
        rgba[:, :, 3] = alpha

        frame_count = 45
        for index in range(frame_count):
            frame_path = output_dir / f"frame_{index:05d}.png"
            cv2.imwrite(str(frame_path), rgba)

        # Optional post-processing: smooth mask + denoise foreground frames
        try:
            mask_path = Path(mask_path)
        except Exception:
            mask_path = output_dir / "mask.png"
        try:
            self.media_ops.optimize_subject_matte(output_dir, mask_path, optimize_cfg)
        except Exception:
            # Don't fail the whole tool if optimization step errors; it's best-effort
            pass

        return {
            "frames_dir": str(output_dir),
            "frame_count": frame_count,
            "fps": 15.0,
            "width": int(image.shape[1]),
            "height": int(image.shape[0]),
            "average_foreground_ratio": float(segmentation.get("foreground_ratio", 0.0)),
            "source_type": "still_image",
        }

    @staticmethod
    def _parse_edit_rect(raw: object) -> dict[str, float] | None:
        """Parse a normalized (0-1) subject rectangle from the request.

        The frontend sends ``edit_rect`` as {x, y, w, h} in 0-1 of the source
        still. Returns None when missing/invalid so segmentation falls back to
        the whole-frame grabCut.
        """
        if not isinstance(raw, dict):
            return None
        keys = ("x", "y", "w", "h")
        if not all(isinstance(raw.get(k), (int, float)) for k in keys):
            return None
        x = float(raw["x"])
        y = float(raw["y"])
        w = float(raw["w"])
        h = float(raw["h"])
        if w <= 0 or h <= 0:
            return None
        return {
            "x": max(0.0, min(1.0, x)),
            "y": max(0.0, min(1.0, y)),
            "w": max(0.0, min(1.0 - max(0.0, x), w)),
            "h": max(0.0, min(1.0 - max(0.0, y), h)),
        }

    @staticmethod
    def _parse_edit_rect_for_asset(raw: object, asset_id: str) -> dict[str, float] | None:
        """Resolve edit_rect for a specific asset.

        Supports two forms:
        1. A single {x, y, w, h} dict (applies to all assets).
        2. A {asset_id: {x, y, w, h}} mapping (per-asset, e.g. from fallback).
        """
        if not isinstance(raw, dict):
            return None
        # Form 2: per-asset mapping — check if any value is itself a rect dict
        for key, val in raw.items():
            if isinstance(val, dict) and "x" in val and "w" in val:
                if str(key) == str(asset_id):
                    return L0AtomicTools._parse_edit_rect(val)
                continue
        # Form 1: single rect dict
        if "x" in raw and "w" in raw:
            return L0AtomicTools._parse_edit_rect(raw)
        return None

    @staticmethod
    def _normalize_edit_rect_to_pixels(
        asset: LivePhotoAsset, edit_rect: dict[str, float] | None
    ) -> tuple[int, int, int, int] | None:
        """Convert a normalized edit_rect into source-still pixel coordinates.

        Returns None when no rect is provided or the image cannot be probed,
        letting segment_subject use its default whole-frame rectangle.
        """
        if not edit_rect:
            return None
        try:
            import cv2
        except ImportError:
            return None
        image = cv2.imread(str(asset.image_path))
        if image is None:
            return None
        height, width = image.shape[:2]
        # segment_subject downscales to a 512px max side; replicate that scale so
        # the rect lands on the same small image grabCut operates on.
        max_side = 512
        scale = min(1.0, max_side / max(width, height))
        s_w = max(1, int(width * scale))
        s_h = max(1, int(height * scale))
        rx = int(edit_rect["x"] * s_w)
        ry = int(edit_rect["y"] * s_h)
        rw = max(1, int(edit_rect["w"] * s_w))
        rh = max(1, int(edit_rect["h"] * s_h))
        return (rx, ry, rw, rh)

    def _resolve_edit_rect(
        self,
        call: ToolCall,
        context: dict[str, object],
        asset_id: str,
    ) -> dict[str, float] | None:
        """Resolve edit_rect for *asset_id* from call args or composition_template.

        Priority:
          1. ``call.arguments["edit_rect"]`` — planner explicitly passes it
             (can be a single ``{x,y,w,h}`` or ``{asset_id: {x,y,w,h}}`` map).
          2. ``context["composition_template"]["slots"]`` — the LayoutResolver
             carried the frontend canvas edit_rect into the slot.
        """
        raw = call.arguments.get("edit_rect")
        parsed = self._parse_edit_rect_for_asset(raw, asset_id)
        if parsed:
            return parsed
        template = context.get("composition_template")
        if isinstance(template, dict):
            for slot in template.get("slots", []):
                if str(slot.get("asset_id")) != str(asset_id):
                    continue
                rect = slot.get("edit_rect")
                if isinstance(rect, dict) and all(
                    isinstance(rect.get(k), (int, float)) for k in ("x", "y", "w", "h")
                ):
                    return {
                        "x": float(rect["x"]),
                        "y": float(rect["y"]),
                        "w": float(rect["w"]),
                        "h": float(rect["h"]),
                    }
        return None

    def overlay_subject_clip(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        foreground_asset_id = str(call.arguments.get("foreground_asset_id", "")).strip()
        anchor = str(call.arguments.get("anchor", "bottom_right")).strip().lower()
        scale = float(call.arguments.get("scale", 0.45))
        x_offset = int(call.arguments.get("x_offset", 0))
        y_offset = int(call.arguments.get("y_offset", 0))
        fit_mode = str(call.arguments.get("fit_mode", "loop")).strip().lower()

        subject_mattes = dict(context.get("subject_mattes", {}))
        matte = subject_mattes.get(foreground_asset_id)
        if not isinstance(matte, dict) or not matte.get("frames_dir"):
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={
                    "error": f"missing_subject_matte_for_{foreground_asset_id}",
                    "error_code": "overlay_subject_clip_failed",
                },
            )

        timeline = dict(context.get("timeline", {}))
        background_path_raw = timeline.get("path")
        if not background_path_raw:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": "no_timeline", "error_code": "overlay_subject_clip_failed"},
            )

        workspace = self._workspace_dir(context)
        timeline_id = str(timeline.get("timeline_id", "timeline"))
        output_path = workspace / "overlay_composite" / f"{timeline_id}_with_{foreground_asset_id}.mp4"

        # Prefer the deterministic spatial placement from the resolved
        # CompositionTemplate for this foreground asset. This is what makes a
        # 框选 region land exactly where the user dragged it on the canvas,
        # instead of being forced into a center/anchor keyword. Fall back to
        # explicit left/top/width/height arguments (e.g. from the deterministic
        # planner) when no template slot matches.
        placement = self._foreground_placement_from_template(context, foreground_asset_id)
        if placement is None:
            placement = self._explicit_foreground_placement(call)
        used_placement = placement is not None

        try:
            result_path = self.media_ops.composite_foreground_over_background(
                background_path=Path(str(background_path_raw)),
                foreground_frames_dir=Path(str(matte["frames_dir"])),
                foreground_fps=float(matte.get("fps", 25.0)),
                output_path=output_path,
                anchor=anchor,
                scale=scale,
                x_offset=x_offset,
                y_offset=y_offset,
                fit_mode=fit_mode,
                placement=placement,
            )
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "overlay_subject_clip_failed"},
            )

        timeline["path"] = str(result_path)
        timeline["subject_overlay_applied"] = True
        timeline["subject_overlay_asset_id"] = foreground_asset_id
        context["timeline"] = timeline

        self._cleanup_subject_matte_temp(matte)
        remaining_mattes = dict(context.get("subject_mattes", {}))
        remaining_mattes.pop(foreground_asset_id, None)
        context["subject_mattes"] = remaining_mattes

        context["subject_overlay"] = {
            "foreground_asset_id": foreground_asset_id,
            "anchor": anchor,
            "scale": scale,
            "x_offset": x_offset,
            "y_offset": y_offset,
            "fit_mode": fit_mode,
            "used_template_placement": used_placement,
            "placement": placement,
            "path": str(result_path),
        }

        return ToolResult(
            tool=call.tool,
            success=True,
            payload={"overlay_applied": True, "output_path": str(result_path)},
        )

    def _foreground_placement_from_template(
        self,
        context: dict[str, object],
        foreground_asset_id: str,
    ) -> dict[str, float] | None:
        """Resolve the canvas placement for a foreground overlay slot.

        Reads the foreground LayoutSlot from the resolved CompositionTemplate.
        Returns edge-anchored percentages (left/top/width/height) so the
        segmented subject is composited exactly where the user framed it.
        Returns ``None`` when no template / no matching foreground slot exists,
        so the caller falls back to anchor-based placement.
        """
        template = context.get("composition_template")
        if not template:
            return None
        slots = template.get("slots") if isinstance(template, dict) else None
        if not slots:
            return None
        for slot in slots:
            if str(slot.get("asset_id")) != str(foreground_asset_id):
                continue
            if (slot.get("role") or "background") != "foreground":
                continue
            return {
                "left": float(slot.get("left_pct", 0.0)),
                "top": float(slot.get("top_pct", 0.0)),
                "width": float(slot.get("width_pct", 100.0)),
                "height": float(slot.get("height_pct", 100.0)),
            }
        return None

    def _explicit_foreground_placement(self, call: ToolCall) -> dict[str, float] | None:
        """Use explicit left/top/width/height arguments as a placement fallback.

        Only returns a placement when all four edge-anchored percentages are
        present and finite, so anchor-based placement is preserved otherwise.
        """
        args = call.arguments
        keys = ("left", "top", "width", "height")
        if not all(isinstance(args.get(k), (int, float)) for k in keys):
            return None
        width = max(0.0, min(100.0, float(args["width"])))
        height = max(0.0, min(100.0, float(args["height"])))
        if width <= 0 or height <= 0:
            return None
        return {
            "left": max(0.0, min(100.0, float(args["left"]))),
            "top": max(0.0, min(100.0, float(args["top"]))),
            "width": width,
            "height": height,
        }

    def _cleanup_subject_matte_temp(self, matte: dict[str, object]) -> None:
        frames_dir_raw = matte.get("frames_dir")
        if not frames_dir_raw:
            return
        frames_dir = Path(str(frames_dir_raw))
        if frames_dir.exists():
            shutil.rmtree(frames_dir, ignore_errors=True)

    def select_cover_frame(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._assets_for_l0(call, context)
        strategy = str(call.arguments.get("strategy", "auto_adaptive")).strip().lower()
        key_frames = dict(context.get("key_frames", {}))
        cover_frames: dict[str, str] = {}
        for asset in focus_assets:
            candidates = key_frames.get(asset.asset_id, [])
            if candidates:
                cover_frames[asset.asset_id] = self._pick_cover_frame(candidates, strategy)
            else:
                cover_frames[asset.asset_id] = str(asset.image_path)
        context["cover_frames"] = cover_frames
        return ToolResult(tool=call.tool, success=True, payload={"cover_frames": cover_frames, "strategy": strategy})

    def _resolve_keyframe_settings(
        self,
        motion_path: Path,
        frame_interval_ms: int,
        max_frames: int,
        sampling_strategy: str,
    ) -> tuple[int, int, float | None]:
        if sampling_strategy not in {"adaptive", "auto", "auto_adaptive"}:
            return max(frame_interval_ms, 50), max(max_frames, 1), None

        probe = self.media_ops.probe_video(motion_path)
        duration_ms = int(probe.get("duration_ms") or 0)
        motion_score = float(self.media_ops.motion_score(motion_path))

        if motion_score >= 0.7:
            adaptive_interval_ms = 200
        elif motion_score >= 0.3:
            adaptive_interval_ms = 350
        else:
            adaptive_interval_ms = 600

        if duration_ms <= 2000:
            cap = 6
        elif duration_ms <= 4000:
            cap = 10
        else:
            cap = 14

        estimated = max(1, math.ceil(duration_ms / max(adaptive_interval_ms, 1))) if duration_ms > 0 else max_frames
        adaptive_max_frames = max(1, min(estimated, cap, max(max_frames, 1)))
        return adaptive_interval_ms, adaptive_max_frames, round(motion_score, 4)

    def _pick_cover_frame(self, candidates: list[str], strategy: str) -> str:
        if not candidates:
            return ""

        if strategy == "first":
            return str(candidates[0])
        if strategy == "middle":
            return str(candidates[len(candidates) // 2])
        if strategy == "last":
            return str(candidates[-1])

        scored: list[tuple[float, str]] = []
        for candidate in candidates:
            score = self._score_cover_candidate(Path(candidate), strategy)
            scored.append((score, candidate))

        scored.sort(key=lambda item: item[0], reverse=True)
        return str(scored[0][1])

    def _score_cover_candidate(self, frame_path: Path, strategy: str) -> float:
        try:
            import cv2
            import numpy as np
        except ImportError:
            return 0.0

        image = cv2.imread(str(frame_path))
        if image is None:
            return 0.0

        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
        sharpness_score = min(sharpness / 600.0, 1.0)

        mean_luma = float(np.mean(gray))
        exposure_score = max(0.0, 1.0 - abs(mean_luma - 128.0) / 128.0)

        hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
        saturation = float(np.mean(hsv[:, :, 1]))
        saturation_score = min(saturation / 160.0, 1.0)

        if strategy == "sharpest":
            return sharpness_score
        if strategy == "vibrant":
            return 0.6 * sharpness_score + 0.4 * saturation_score

        # auto_adaptive / balanced default: stable exposure + detail retention.
        return 0.7 * sharpness_score + 0.3 * exposure_score

    def clip_trim(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._assets_for_l0(call, context)
        start_ms = int(call.arguments.get("start_ms", 0))
        end_ms = int(call.arguments.get("end_ms", 1500))
        workspace = self._workspace_dir(context)
        trimmed: dict[str, dict[str, object]] = {}
        try:
            for asset in focus_assets:
                motion_path = self._asset_motion_path(asset)
                if motion_path is None:
                    continue
                output_path = workspace / "trimmed" / f"{asset.asset_id}.mp4"
                result_path = self.media_ops.trim_video(motion_path, output_path, start_ms=start_ms, end_ms=end_ms)
                trimmed[asset.asset_id] = {
                    "start_ms": start_ms,
                    "end_ms": end_ms,
                    "path": str(result_path),
                }
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "clip_trim_failed"},
            )
        context["trimmed_segments"] = trimmed
        return ToolResult(tool=call.tool, success=True, payload={"trimmed_segments": trimmed})

    def clip_speed(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._assets_for_l0(call, context)
        speed = float(call.arguments.get("speed", 1.0))
        workspace = self._workspace_dir(context)
        trimmed = dict(context.get("trimmed_segments", {}))
        speed_segments: dict[str, dict[str, object]] = {}

        try:
            for asset in focus_assets:
                source_path = self._segment_source_path(asset, trimmed)
                if source_path is None:
                    continue
                output_path = workspace / "speed" / f"{asset.asset_id}.mp4"
                result_path = self.media_ops.speed_video(source_path, output_path, speed=speed)
                speed_segments[asset.asset_id] = {"speed": speed, "path": str(result_path)}
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "clip_speed_failed"},
            )

        context["speed_segments"] = speed_segments
        return ToolResult(tool=call.tool, success=True, payload={"speed_adjusted_segments": speed_segments})

    def color_enhance(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._assets_for_l0(call, context)
        profile = str(call.arguments.get("profile", "vivid"))
        workspace = self._workspace_dir(context)
        speed_segments = dict(context.get("speed_segments", {}))
        enhanced_paths: dict[str, str] = {}
        try:
            for asset in focus_assets:
                source_path = self._segment_source_path(asset, speed_segments)
                if source_path is None:
                    continue
                output_path = workspace / "color" / f"{asset.asset_id}.mp4"
                result_path = self.media_ops.color_enhance(source_path, output_path, profile=profile)
                enhanced_paths[asset.asset_id] = str(result_path)
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "color_enhance_failed"},
            )

        context["color_enhanced"] = enhanced_paths
        return ToolResult(tool=call.tool, success=True, payload={"enhanced_assets": enhanced_paths, "profile": profile})

    def stabilize_clip(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._assets_for_l0(call, context)
        workspace = self._workspace_dir(context)
        enhanced_paths = dict(context.get("color_enhanced", {}))
        stabilized_paths: dict[str, str] = {}
        try:
            for asset in focus_assets:
                source_path = self._segment_source_path(asset, enhanced_paths)
                if source_path is None:
                    continue
                output_path = workspace / "stabilized" / f"{asset.asset_id}.mp4"
                result_path = self.media_ops.stabilize_video(source_path, output_path)
                stabilized_paths[asset.asset_id] = str(result_path)
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "stabilize_clip_failed"},
            )

        context["stabilized_assets"] = stabilized_paths
        return ToolResult(tool=call.tool, success=True, payload={"stabilized_assets": stabilized_paths})

    def concat_clips(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        order = call.arguments.get("order", [])
        if not isinstance(order, list):
            order = []
        layout_mode = str(call.arguments.get("layout", "auto")).strip().lower()
        canvas = str(call.arguments.get("canvas", "1080x1920")).strip()
        focus_assets = self._assets_for_l0(call, context)
        stabilized_paths = dict(context.get("stabilized_assets", {}))
        color_enhanced = dict(context.get("color_enhanced", {}))
        speed_segments = dict(context.get("speed_segments", {}))
        trimmed_segments = dict(context.get("trimmed_segments", {}))
        source_maps: list[dict[str, object]] = [stabilized_paths, color_enhanced, speed_segments, trimmed_segments]

        default_order = [asset.asset_id for asset in focus_assets]
        final_order = [str(item) for item in order] if order else default_order
        workspace = self._workspace_dir(context)
        timeline_id = "timeline_triptych_001"
        timeline_path = workspace / "timeline" / f"{timeline_id}.mp4"

        try:
            by_id = {asset.asset_id: asset for asset in focus_assets}
            clip_paths: list[Path] = []
            resolved_order: list[str] = []
            for asset_id in final_order:
                asset = by_id.get(asset_id)
                if asset is None:
                    continue
                source_path = self._resolve_asset_video_path(asset, source_maps, workspace)
                if source_path is None:
                    continue
                clip_paths.append(source_path)
                resolved_order.append(asset_id)

            if not clip_paths:
                return ToolResult(
                    tool=call.tool,
                    success=False,
                    payload={
                        "error": "no_clip_sources",
                        "error_code": "concat_clips_failed",
                        "candidate_asset_count": len(focus_assets),
                    },
                )

            # Spatial layout priority:
            #   1. If the planner/request resolved a CompositionTemplate with
            #      background slots, that is the AUTHORITATIVE spatial layout
            #      (deterministically derived from the user's grid drag). Use it
            #      directly instead of guessing from text keywords.
            #   2. Otherwise fall back to the text/learned-driven composition mode.
            template_placements = self._build_placements_from_template(context, resolved_order)
            if template_placements is not None:
                composition_mode = "template"
            else:
                composition_mode = self._resolve_concat_composition_mode(
                    explicit_layout=layout_mode,
                    request_text=str(context.get("request_text", "")),
                    clip_count=len(clip_paths),
                    reusable_strategies=context.get("reusable_strategies", []),
                )

            total_candidate_count = len(clip_paths)
            capped_clip_count = total_candidate_count
            if composition_mode != "timeline" and template_placements is None and len(clip_paths) > 3:
                clip_paths = clip_paths[:3]
                resolved_order = resolved_order[:3]
                capped_clip_count = len(clip_paths)

            if composition_mode == "timeline":
                self.media_ops.concat_videos(clip_paths, timeline_path)
            else:
                compose_kwargs: dict[str, object] = {"canvas": canvas, "layout": composition_mode}
                if template_placements is not None:
                    compose_kwargs["placements"] = template_placements
                else:
                    layout_context = context.get("layout_context", [])
                    placements: list[dict[str, float]] = []
                    if isinstance(layout_context, list):
                        placement_by_asset = {
                            str(item.get("asset_id")): item
                            for item in layout_context
                            if isinstance(item, dict) and item.get("asset_id")
                        }
                        for asset_id in resolved_order:
                            item = placement_by_asset.get(asset_id)
                            if item is None:
                                placements = []
                                break
                            placements.append({
                                key: float(item[key])
                                for key in ("left", "top", "width", "height")
                                if key in item and isinstance(item[key], (int, float))
                            })
                    if placements:
                        compose_kwargs["placements"] = placements
                self.media_ops.compose_videos_spatial(clip_paths, timeline_path, **compose_kwargs)

            timeline = {
                "timeline_id": timeline_id,
                "order": resolved_order,
                "path": str(timeline_path),
                "composition": composition_mode,
                "canvas": canvas,
                "candidate_count": total_candidate_count,
                "composed_count": capped_clip_count,
            }
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "concat_clips_failed"},
            )

        context["timeline"] = timeline
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={
                "timeline_id": timeline["timeline_id"],
                "segment_count": len(timeline["order"]),
                "composition": composition_mode,
                "canvas": canvas,
                "candidate_count": total_candidate_count,
                "composed_count": capped_clip_count,
            },
        )

    def _build_placements_from_template(
        self,
        context: dict[str, object],
        resolved_order: list[str],
    ) -> list[dict[str, float]] | None:
        """Build spatial placements from the resolved CompositionTemplate.

        Returns an ordered list of edge-anchored percentage placements
        (left/top/width/height) for the background slots, matched to
        ``resolved_order``. Returns ``None`` when no usable spatial template
        exists, so the caller falls back to text-driven composition.
        """
        template = context.get("composition_template")
        if not template:
            return None
        slots = template.get("slots") if isinstance(template, dict) else None
        if not slots:
            return None

        bg_slots = [s for s in slots if (s.get("role") or "background") != "foreground"]
        if not bg_slots:
            return None

        by_asset: dict[str, dict[str, object]] = {
            str(s.get("asset_id")): s for s in bg_slots if s.get("asset_id")
        }
        placements: list[dict[str, float]] = []
        # Try deterministic mapping by asset_id first.
        missing = False
        for asset_id in resolved_order:
            slot = by_asset.get(asset_id)
            if slot is None:
                missing = True
                break
            placements.append({
                "left": float(slot.get("left_pct", 0.0)),
                "top": float(slot.get("top_pct", 0.0)),
                "width": float(slot.get("width_pct", 100.0)),
                "height": float(slot.get("height_pct", 100.0)),
            })

        if missing:
            # Fallback: if the template's background slots count exactly matches
            # the resolved_order length, map slots positionally (ordered by
            # z_index) to tolerate ID mismatches while preserving layout.
            if len(bg_slots) == len(resolved_order):
                placements = []
                sorted_slots = sorted(bg_slots, key=lambda s: (s.get("z_index", 0), str(s.get("slot_id"))))
                for slot in sorted_slots:
                    placements.append({
                        "left": float(slot.get("left_pct", 0.0)),
                        "top": float(slot.get("top_pct", 0.0)),
                        "width": float(slot.get("width_pct", 100.0)),
                        "height": float(slot.get("height_pct", 100.0)),
                    })
            else:
                return None
        if not placements:
            return None
        return placements

    def _resolve_concat_composition_mode(
        self,
        explicit_layout: str,
        request_text: str,
        clip_count: int,
        reusable_strategies: object,
    ) -> str:
        if explicit_layout in {
            "timeline",
            "temporal",
            "horizontal",
            "vertical",
            "triptych_portrait",
            "triptych_landscape",
        }:
            return explicit_layout

        text = request_text.lower()
        learned_layout = self._learned_concat_layout(reusable_strategies)
        spatial_markers = [
            "拼贴",
            "左边",
            "右边",
            "上面",
            "下面",
            "左右",
            "上下",
            "grid",
            "layout",
        ]
        temporal_markers = ["时间轴", "串起来", "接在后面", "concat", "timeline", "sequential"]

        if any(marker in text for marker in temporal_markers):
            return "timeline"
        directional = self._directional_layout_from_text(text)
        if directional:
            return directional
        if learned_layout:
            return learned_layout
        if self._is_triptych_request(text):
            return "triptych_portrait"
        if any(marker in text for marker in spatial_markers) and clip_count >= 2:
            return "triptych_portrait"
        # Keep default conservative; preference should come from explicit user request or learned strategy.
        return "timeline"

    def _learned_concat_layout(self, reusable_strategies: object) -> str:
        if not isinstance(reusable_strategies, list):
            return ""
        allowed = {"timeline", "horizontal", "vertical", "triptych_portrait", "triptych_landscape"}
        for item in reusable_strategies:
            if not isinstance(item, dict):
                continue
            sequence = item.get("tool_sequence", [])
            if not isinstance(sequence, list) or "concat_clips" not in [str(v) for v in sequence]:
                continue
            composition = str(item.get("concat_composition", "")).strip().lower()
            if composition in allowed:
                return composition
        return ""

    def _is_triptych_request(self, text: str) -> bool:
        lowered = text.lower()
        markers = ["三拼", "triptych", "three panel", "3-panel", "3 panel"]
        return any(marker in lowered for marker in markers)

    def _directional_layout_from_text(self, text: str) -> str:
        horizontal_markers = ["左右", "左边", "右边", "横", "horizontal", "left", "right"]
        vertical_markers = ["上下", "上面", "下面", "竖", "vertical", "top", "bottom"]

        if any(marker in text for marker in horizontal_markers):
            return "triptych_landscape"
        if any(marker in text for marker in vertical_markers):
            return "vertical"
        return ""

    def add_text_overlay(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        text = str(call.arguments.get("text", ""))
        style = str(call.arguments.get("style", "minimal"))
        timeline = dict(context.get("timeline", {}))
        timeline_path = Path(str(timeline.get("path", ""))) if timeline.get("path") else None
        workspace = self._workspace_dir(context)

        if not text or timeline_path is None:
            overlay = {"text": text, "style": style, "applied": False}
            context["overlay"] = overlay
            return ToolResult(tool=call.tool, success=True, payload={"overlay_applied": False})

        output_path = workspace / "overlay" / "timeline_overlay.mp4"
        try:
            result_path = self.media_ops.add_text_overlay(timeline_path, output_path, text=text, style=style)
        except MediaOpsError as exc:
            fallback_overlay = {
                "text": text,
                "style": style,
                "applied": False,
                "path": str(timeline_path),
                "fallback_to_timeline": True,
                "warning": str(exc),
            }
            context["overlay"] = fallback_overlay
            return ToolResult(
                tool=call.tool,
                success=True,
                payload={
                    "overlay_applied": False,
                    "fallback_to_timeline": True,
                    "warning": str(exc),
                },
            )

        overlay = {"text": text, "style": style, "applied": True, "path": str(result_path)}
        context["overlay"] = overlay
        return ToolResult(tool=call.tool, success=True, payload={"overlay_applied": overlay["applied"]})

    def mix_audio_bgm(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        track_id = str(call.arguments.get("track_id", "default_track"))
        gain_db = float(call.arguments.get("gain_db", -8.0))
        audio_mix = {"track_id": track_id, "gain_db": gain_db, "applied": True, "engine": "ffmpeg_ready"}
        context["audio_mix"] = audio_mix
        return ToolResult(tool=call.tool, success=True, payload={"audio_mix_applied": True})

    def export_mp4(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        output_name = str(call.arguments.get("output_name", "triptych_output"))
        resolution = str(call.arguments.get("resolution", "1080x1920"))
        workspace = self._workspace_dir(context)
        overlay = dict(context.get("overlay", {}))
        timeline = dict(context.get("timeline", {}))
        source_path = overlay.get("path") or timeline.get("path")
        if not source_path:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": "timeline_missing", "error_code": "export_mp4_failed"},
            )
        source_path_obj = Path(str(source_path))
        if not source_path_obj.exists():
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={
                    "error": f"source_video_missing: {source_path_obj}",
                    "error_code": "export_mp4_failed",
                    "source_path": str(source_path_obj),
                },
            )

        output_path = workspace / "exports" / f"{output_name}.mp4"
        try:
            result_path = self.media_ops.export_mp4(source_path_obj, output_path, resolution=resolution)
            probe = self.media_ops.probe_video(result_path)
        except MediaOpsError as exc:
            try:
                output_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source_path_obj, output_path)
                probe = self.media_ops.probe_video(output_path)
            except Exception:  # noqa: BLE001
                return ToolResult(
                    tool=call.tool,
                    success=False,
                    payload={"error": str(exc), "error_code": "export_mp4_failed", "source_path": str(source_path_obj)},
                )

            export_meta = {
                "output_path": str(output_path),
                "resolution": resolution,
                "duration_ms": int(probe.get("duration_ms", 0)),
                "degraded": True,
                "fallback": "copy_source",
                "warning": str(exc),
            }
            context["export"] = export_meta
            return ToolResult(
                tool=call.tool,
                success=True,
                payload={
                    "output_path": str(output_path),
                    "duration_ms": int(probe.get("duration_ms", 0)),
                    "degraded": True,
                    "fallback": "copy_source",
                    "warning": str(exc),
                },
            )

        export_meta = {
            "output_path": str(result_path),
            "resolution": resolution,
            "duration_ms": int(probe.get("duration_ms", 0)),
        }
        context["export"] = export_meta

        # Final deliverable should be live-photo jpg container when possible.
        live_photo_payload = self._export_live_photo_container(
            context=context,
            source_video=result_path,
            output_name=output_name,
            duration_ms=int(probe.get("duration_ms", 0)),
        )
        if live_photo_payload is not None:
            export_meta["live_photo_output_path"] = str(live_photo_payload.get("output_path", ""))
            export_meta["final_format"] = "live_photo_jpg"
            context["export"] = export_meta

        return ToolResult(
            tool=call.tool,
            success=True,
            payload={
                "output_path": str(result_path),
                "duration_ms": int(probe.get("duration_ms", 0)),
                "live_photo": live_photo_payload or {},
            },
        )

    def _export_live_photo_container(
        self,
        context: dict[str, object],
        source_video: Path,
        output_name: str,
        duration_ms: int,
    ) -> dict[str, object] | None:
        workspace = self._workspace_dir(context)
        cover_path = workspace / "live_photo" / f"{output_name}_cover.jpg"
        normalized_motion_path = workspace / "live_photo" / f"{output_name}_motion.mp4"
        live_photo_path = workspace / "live_photo" / f"{output_name}.jpg"

        # Use previously-selected cover frame when available; fall back to video midpoint.
        cover_frames: dict[str, str] = dict(context.get("cover_frames", {}))
        selected_cover_src: Path | None = None
        for _asset_id, frame_path_str in cover_frames.items():
            candidate = Path(str(frame_path_str))
            if candidate.exists():
                selected_cover_src = candidate
                break

        presentation_ms = max(duration_ms // 2, 0)

        try:
            if selected_cover_src is not None:
                cover_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(selected_cover_src, cover_path)
                # presentation_timestamp_ms stays at 0 because the exported video
                # is a new composition; exact frame mapping is not meaningful.
                presentation_ms = 0
            else:
                self.media_ops.extract_cover_jpeg(source_video, cover_path, timestamp_ms=presentation_ms)
            normalized_motion = self.media_ops.normalize_motion_photo_video(source_video, normalized_motion_path)
            packed = self.media_ops.pack_motion_photo_jpg(
                image_path=cover_path,
                video_path=normalized_motion,
                output_path=live_photo_path,
                presentation_timestamp_ms=presentation_ms,
            )
            validation = self.media_ops.validate_motion_photo_jpg(packed)
            if not bool(validation.get("valid", False)):
                return {
                    "status": "failed",
                    "error_code": "live_photo_validation_failed",
                    "validation": validation,
                    "output_path": str(packed),
                    "cover_path": str(cover_path),
                    "motion_video_path": str(normalized_motion_path),
                }
        except MediaOpsError as exc:
            return {
                "status": "failed",
                "error_code": "live_photo_pack_failed",
                "error": str(exc),
            }

        return {
            "status": "ok",
            "output_path": str(packed),
            "cover_path": str(cover_path),
            "motion_video_path": str(normalized_motion_path),
            "format": "live_photo_jpg",
            "validation": validation,
        }

    def set_display_frame(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        """Repack existing live photos with a user-chosen display/cover frame.

        Argument priority (first match wins):
        1. ``timestamp_ms`` – extract this exact frame from the original motion clip.
        2. ``frame_path`` – use the given image file directly as the new cover JPEG.
        3. implicit – use the already-selected frame in ``context["cover_frames"]``.
        If none of the above, falls back to the middle frame of the motion clip.
        """
        focus_assets = self._assets_for_l0(call, context)
        timestamp_ms_arg: int | None = (
            int(call.arguments["timestamp_ms"]) if call.arguments.get("timestamp_ms") is not None else None
        )
        frame_path_arg: Path | None = (
            Path(str(call.arguments["frame_path"])) if call.arguments.get("frame_path") else None
        )
        workspace = self._workspace_dir(context)
        cover_frames_ctx: dict[str, str] = dict(context.get("cover_frames", {}))

        results: list[dict[str, object]] = []
        failed_asset_ids: list[str] = []

        for asset in focus_assets:
            motion_path = self._asset_motion_path(asset)
            if motion_path is None:
                failed_asset_ids.append(asset.asset_id)
                results.append({
                    "asset_id": asset.asset_id,
                    "status": "skipped",
                    "reason": "no_motion_clip",
                })
                continue

            out_dir = workspace / "display_frame" / asset.asset_id
            out_dir.mkdir(parents=True, exist_ok=True)
            cover_jpeg = out_dir / "cover.jpg"
            output_live_photo = out_dir / f"{asset.asset_id}.jpg"

            try:
                # --- Determine cover image and presentation timestamp ---
                presentation_ms: int

                if timestamp_ms_arg is not None:
                    # User provided an explicit timestamp.
                    self.media_ops.extract_cover_jpeg(motion_path, cover_jpeg, timestamp_ms=timestamp_ms_arg)
                    presentation_ms = timestamp_ms_arg

                elif frame_path_arg is not None and frame_path_arg.exists():
                    # User provided a specific frame image.
                    shutil.copy2(frame_path_arg, cover_jpeg)
                    presentation_ms = self._locate_frame_timestamp(motion_path, cover_jpeg)

                elif asset.asset_id in cover_frames_ctx:
                    # Use the frame already selected by select_cover_frame.
                    ctx_frame = Path(cover_frames_ctx[asset.asset_id])
                    if ctx_frame.exists():
                        shutil.copy2(ctx_frame, cover_jpeg)
                    else:
                        # Context frame gone; fall back to midpoint.
                        probe = self.media_ops.probe_video(motion_path)
                        mid_ms = int(probe.get("duration_ms", 0)) // 2
                        self.media_ops.extract_cover_jpeg(motion_path, cover_jpeg, timestamp_ms=mid_ms)
                    presentation_ms = self._locate_frame_timestamp(motion_path, cover_jpeg)

                else:
                    # No hint available – use middle frame.
                    probe = self.media_ops.probe_video(motion_path)
                    mid_ms = int(probe.get("duration_ms", 0)) // 2
                    self.media_ops.extract_cover_jpeg(motion_path, cover_jpeg, timestamp_ms=mid_ms)
                    presentation_ms = mid_ms

                # --- Normalise motion clip and repack ---
                normalized_motion = out_dir / "motion.mp4"
                self.media_ops.normalize_motion_photo_video(motion_path, normalized_motion)
                packed = self.media_ops.pack_motion_photo_jpg(
                    image_path=cover_jpeg,
                    video_path=normalized_motion,
                    output_path=output_live_photo,
                    presentation_timestamp_ms=presentation_ms,
                )
                validation = self.media_ops.validate_motion_photo_jpg(packed)
                results.append({
                    "asset_id": asset.asset_id,
                    "status": "ok",
                    "output_path": str(packed),
                    "cover_path": str(cover_jpeg),
                    "presentation_timestamp_ms": presentation_ms,
                    "validation": validation,
                })

            except MediaOpsError as exc:
                failed_asset_ids.append(asset.asset_id)
                results.append({
                    "asset_id": asset.asset_id,
                    "status": "failed",
                    "error": str(exc),
                })

        context["display_frame_result"] = results
        success = len(failed_asset_ids) < len(focus_assets)
        return ToolResult(
            tool=call.tool,
            success=success,
            payload={
                "results": results,
                "success_count": len([r for r in results if r.get("status") == "ok"]),
                "failed_asset_ids": failed_asset_ids,
            },
        )

    def _locate_frame_timestamp(self, motion_path: Path, frame_image: Path) -> int:
        """Return the timestamp (ms) in *motion_path* that best matches *frame_image*.

        Falls back to the middle of the clip when OpenCV is unavailable or matching fails.
        """
        try:
            result = self.media_ops.locate_cover_frame(
                video_path=motion_path,
                image_path=frame_image,
            )
            return int(result.get("cover_frame_timestamp_ms", 0))
        except Exception:  # noqa: BLE001
            try:
                probe = self.media_ops.probe_video(motion_path)
                return int(probe.get("duration_ms", 0)) // 2
            except Exception:  # noqa: BLE001
                return 0

    def _get_assets(self, context: dict[str, object]) -> list[LivePhotoAsset]:
        return list(context.get("assets", []))

    def _preferred_assets(self, context: dict[str, object]) -> list[LivePhotoAsset]:
        selected = list(context.get("selected_assets", []))
        if selected:
            return selected
        matched = list(context.get("matched_assets", []))
        if matched:
            return matched
        return self._get_assets(context)

    def _assets_for_l0(self, call: ToolCall, context: dict[str, object]) -> list[LivePhotoAsset]:
        assets = self._preferred_assets(context)
        raw_ids = call.arguments.get("asset_ids", [])
        if not isinstance(raw_ids, list) or not raw_ids:
            return assets

        selected_ids = {str(item) for item in raw_ids}
        return [asset for asset in assets if asset.asset_id in selected_ids]

    def _workspace_dir(self, context: dict[str, object]) -> Path:
        default_root = Path(context["library_root"]).resolve() / ".agent_work"
        root = Path(str(context.get("work_dir", default_root)))
        root.mkdir(parents=True, exist_ok=True)
        return root

    def _segmentation_workspace_dir(self) -> Path:
        # Keep segmentation/matte intermediates in a fixed workspace location.
        root = settings.workspace_dir.resolve() / ".agent_work" / "segmentation"
        root.mkdir(parents=True, exist_ok=True)
        return root

    def _asset_motion_path(self, asset: LivePhotoAsset) -> Path | None:
        if asset.motion_path is None:
            return None
        motion_path = Path(asset.motion_path)
        if not motion_path.exists():
            return None
        return motion_path

    def _segment_source_path(self, asset: LivePhotoAsset, segment_map: dict[str, object]) -> Path | None:
        item = segment_map.get(asset.asset_id)
        if isinstance(item, dict) and "path" in item:
            path = Path(str(item["path"]))
            if path.exists():
                return path
        if isinstance(item, str):
            path = Path(item)
            if path.exists():
                return path
        return self._asset_motion_path(asset)

    def _resolve_asset_video_path(
        self, asset: LivePhotoAsset, source_maps: list[dict[str, object]], workspace: Path | None = None
    ) -> Path | None:
        for source_map in source_maps:
            resolved = self._segment_source_path(asset, source_map)
            if resolved is not None and resolved.exists():
                return resolved
        motion = self._asset_motion_path(asset)
        if motion is not None:
            return motion
        # Pure still asset (no motion clip): materialize a short static-frame
        # video so it can participate in the composition timeline like a live
        # photo. This is what lets a plain JPEG be embedded into the result.
        image_path = Path(asset.image_path) if asset.image_path else None
        if image_path is not None and image_path.exists():
            if workspace is None:
                workspace = self._workspace_dir({"library_root": image_path.parent})
            out_dir = workspace / "static_frames"
            out_dir.mkdir(parents=True, exist_ok=True)
            out_path = out_dir / f"{asset.asset_id}.mp4"
            try:
                return self.media_ops.image_to_static_video(image_path, out_path)
            except MediaOpsError:
                return None
        return None
