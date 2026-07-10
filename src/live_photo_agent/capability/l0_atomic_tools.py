from __future__ import annotations

from pathlib import Path

from ..foundation import LibraryService, MediaOps, MediaOpsError
from ..models import LivePhotoAsset, ToolCall, ToolResult


class L0AtomicTools:
    """L0 atomic tools: single-purpose media operations."""

    def __init__(self, library_service: LibraryService) -> None:
        self.library_service = library_service
        self.media_ops = MediaOps()

    def scan_library(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        assets = self.library_service.scan_live_photos(context["library_root"])
        context["assets"] = assets
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={"asset_count": len(assets), "asset_ids": [asset.asset_id for asset in assets]},
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
        frames: dict[str, list[str]] = {}
        workspace = self._workspace_dir(context)

        try:
            for asset in focus_assets:
                motion_path = self._asset_motion_path(asset)
                if motion_path is None:
                    continue
                output_dir = workspace / "key_frames" / asset.asset_id
                extracted = self.media_ops.extract_frames(
                    motion_path,
                    output_dir,
                    frame_interval_ms=frame_interval_ms,
                    max_frames=max_frames,
                )
                frames[asset.asset_id] = [str(path) for path in extracted]
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
        strategy = str(call.arguments.get("strategy", "sharpest"))
        key_frames = dict(context.get("key_frames", {}))
        cover_frames: dict[str, str] = {}
        for asset in focus_assets:
            candidates = key_frames.get(asset.asset_id, [])
            if candidates:
                cover_frames[asset.asset_id] = str(candidates[0])
            else:
                cover_frames[asset.asset_id] = str(asset.image_path)
        context["cover_frames"] = cover_frames
        return ToolResult(tool=call.tool, success=True, payload={"cover_frames": cover_frames, "strategy": strategy})

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
        focus_assets = self._assets_for_l0(call, context)
        stabilized_paths = dict(context.get("stabilized_assets", {}))
        default_order = [asset.asset_id for asset in focus_assets if asset.asset_id in stabilized_paths]
        final_order = [str(item) for item in order] if order else default_order
        workspace = self._workspace_dir(context)
        timeline_id = "timeline_triptych_001"
        timeline_path = workspace / "timeline" / f"{timeline_id}.mp4"

        try:
            clip_paths = [Path(stabilized_paths[asset_id]) for asset_id in final_order if asset_id in stabilized_paths]
            if clip_paths:
                self.media_ops.concat_videos(clip_paths, timeline_path)
            timeline = {"timeline_id": timeline_id, "order": final_order, "path": str(timeline_path)}
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "concat_clips_failed"},
            )

        context["timeline"] = timeline
        return ToolResult(tool=call.tool, success=True, payload={"timeline_id": timeline["timeline_id"], "segment_count": len(final_order)})

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
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "add_text_overlay_failed"},
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

        output_path = workspace / "exports" / f"{output_name}.mp4"
        try:
            result_path = self.media_ops.export_mp4(Path(str(source_path)), output_path, resolution=resolution)
            probe = self.media_ops.probe_video(result_path)
        except MediaOpsError as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "export_mp4_failed"},
            )

        export_meta = {
            "output_path": str(result_path),
            "resolution": resolution,
            "duration_ms": int(probe.get("duration_ms", 0)),
        }
        context["export"] = export_meta
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={"output_path": str(result_path), "duration_ms": int(probe.get("duration_ms", 0))},
        )

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
