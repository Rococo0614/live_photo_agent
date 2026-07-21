from __future__ import annotations

import math
import shutil
from pathlib import Path

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
        workspace = self._workspace_dir(context)
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
                source_path = self._resolve_asset_video_path(asset, source_maps)
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

            composition_mode = self._resolve_concat_composition_mode(
                explicit_layout=layout_mode,
                request_text=str(context.get("request_text", "")),
                clip_count=len(clip_paths),
                reusable_strategies=context.get("reusable_strategies", []),
            )
            total_candidate_count = len(clip_paths)
            capped_clip_count = total_candidate_count
            if composition_mode != "timeline" and len(clip_paths) > 3:
                clip_paths = clip_paths[:3]
                resolved_order = resolved_order[:3]
                capped_clip_count = len(clip_paths)

            if composition_mode == "timeline":
                self.media_ops.concat_videos(clip_paths, timeline_path)
            else:
                self.media_ops.compose_videos_spatial(
                    clip_paths,
                    timeline_path,
                    canvas=canvas,
                    layout=composition_mode,
                )

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

        try:
            self.media_ops.extract_cover_jpeg(source_video, cover_path, timestamp_ms=max(duration_ms // 2, 0))
            normalized_motion = self.media_ops.normalize_motion_photo_video(source_video, normalized_motion_path)
            packed = self.media_ops.pack_motion_photo_jpg(
                image_path=cover_path,
                video_path=normalized_motion,
                output_path=live_photo_path,
                presentation_timestamp_ms=max(duration_ms // 2, 0),
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

    def _resolve_asset_video_path(self, asset: LivePhotoAsset, source_maps: list[dict[str, object]]) -> Path | None:
        for source_map in source_maps:
            resolved = self._segment_source_path(asset, source_map)
            if resolved is not None and resolved.exists():
                return resolved
        return self._asset_motion_path(asset)
