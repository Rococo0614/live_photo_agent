from __future__ import annotations

import json
import sys
from pathlib import Path

from ..capability.live_photo_cli import is_live_photo_container, unpack_motion_photo
from ..models import LivePhotoAsset, ToolCall, ToolResult


class L2VerticalTools:
    """L2 vertical tools: domain-specific workflows for live photo scenarios."""

    def draft_edit_plan(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        focus_assets = self._preferred_assets(context)
        draft = {
            "style": call.arguments.get("style", "social_highlight"),
            "asset_count": len(focus_assets),
            "suggestions": [
                "优先选择有动态片段且标签最贴近用户描述的素材",
                "为每个候选 live photo 生成封面建议和一句文案",
                "如果素材数量过多，先筛成 3 到 5 个精选候选",
            ],
        }
        context["edit_plan"] = draft
        return ToolResult(tool=call.tool, success=True, payload=draft)

    def live_photo_collage(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        """Live photo collage: segment subjects, plan free layout, compose 1440x1920 video.

        Orchestrates 5 phases:
          Phase 1: Asset analysis (Mask2Former candidate detection)
          Phase 2: Free layout planning (subjects don't overlap, span >=2 backgrounds)
          Phase 3: Cutie segmentation + tracking
          Phase 4: Split into bg + subject + alpha
          Phase 5: Compose final video

        Each phase outputs observable MP4 files for debugging.
        """
        asset_paths = call.arguments.get("asset_paths", [])
        output_dir = Path(str(call.arguments.get("output_dir", "data/live_photo/collage_output")))
        canvas_w = int(call.arguments.get("canvas_width", 1440))
        canvas_h = int(call.arguments.get("canvas_height", 1920))
        span_ratio = float(call.arguments.get("span_ratio", 0.25))

        if not asset_paths:
            assets = self._preferred_assets(context)
            asset_paths = [str(a.image_path) for a in assets]
            motion_paths = [str(a.motion_path) for a in assets if a.motion_path]
            asset_paths = asset_paths + motion_paths

        if not asset_paths:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": "no_assets", "error_code": "no_subject_detected"},
            )

        output_dir.mkdir(parents=True, exist_ok=True)

        discovered_assets = self._discover_assets(asset_paths, output_dir)

        try:
            manifest = self._run_phase1(discovered_assets, output_dir, canvas_w, canvas_h)
            layout = self._run_phase2(manifest, output_dir, canvas_w, canvas_h)
            self._run_phase3(manifest, layout, output_dir, canvas_w, canvas_h)
            self._run_phase4(manifest, layout, output_dir, canvas_w, canvas_h)
            final_path = self._run_phase5(manifest, layout, output_dir, canvas_w, canvas_h)
        except Exception as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "composition_failed"},
            )

        phase_videos = []
        for name in ["phase1_asset_analysis.mp4", "phase2_layout_plan.mp4", "final.mp4"]:
            p = output_dir / name
            if p.exists():
                phase_videos.append(str(p))
        for seg_dir in sorted(output_dir.glob("seg_*/")):
            for mp4 in sorted(seg_dir.glob("*.mp4")):
                phase_videos.append(str(mp4))
        for split_mp4 in sorted(output_dir.glob("split_*.mp4")):
            phase_videos.append(str(split_mp4))

        context["collage_output"] = {
            "final_video": str(final_path),
            "manifest": manifest,
            "layout_plan": layout,
            "phase_videos": phase_videos,
        }

        return ToolResult(
            tool=call.tool,
            success=True,
            payload={
                "final_video": str(final_path),
                "manifest": manifest,
                "layout_plan": layout,
                "phase_videos": phase_videos,
            },
        )

    # ------------------------------------------------------------------
    # Asset discovery
    # ------------------------------------------------------------------

    def _discover_assets(self, asset_paths: list[str], output_dir: Path) -> list[tuple[str, str | None, str | None]]:
        """Discover live photo assets from input paths.

        Handles: live photo containers (jpg+embedded mp4), plain mp4, plain jpg.
        Live photos are auto-unpacked to extract the embedded motion video.
        """
        assets = []
        decode_dir = output_dir / "_decoded"
        decode_dir.mkdir(parents=True, exist_ok=True)

        for i, raw_path in enumerate(asset_paths):
            p = Path(str(raw_path))
            asset_id = f"asset_{i + 1:02d}"

            if not p.exists():
                continue

            if p.suffix.lower() in (".jpg", ".jpeg"):
                if is_live_photo_container(p):
                    jpg_out, mp4_out = unpack_motion_photo(p, decode_dir / p.stem)
                    assets.append((asset_id, str(mp4_out), str(jpg_out)))
                else:
                    assets.append((asset_id, None, str(p)))
            elif p.suffix.lower() in (".mp4", ".mov"):
                jpg = p.with_suffix(".jpg")
                assets.append((asset_id, str(p), str(jpg) if jpg.exists() else None))
            else:
                continue

        return assets

    # ------------------------------------------------------------------
    # Phase orchestration
    # ------------------------------------------------------------------

    def _run_phase1(self, assets, output_dir, canvas_w, canvas_h):
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent / "scripts"))
        from pipeline.phase1_analyze import Phase1Analyzer

        analyzer = Phase1Analyzer(output_dir, canvas_w=canvas_w, canvas_h=canvas_h)
        return analyzer.run(assets)

    def _run_phase2(self, manifest, output_dir, canvas_w, canvas_h):
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent / "scripts"))
        from pipeline.phase2_layout import Phase2Planner

        planner = Phase2Planner(output_dir, canvas_w=canvas_w, canvas_h=canvas_h)
        return planner.run(manifest)

    def _run_phase3(self, manifest, layout, output_dir, canvas_w, canvas_h):
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent / "scripts"))
        from pipeline.phase3_segment import Phase3Segmenter

        segmenter = Phase3Segmenter(output_dir, canvas_w=canvas_w, canvas_h=canvas_h)
        segmenter.run(manifest, layout)

    def _run_phase4(self, manifest, layout, output_dir, canvas_w, canvas_h):
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent / "scripts"))
        from pipeline.phase4_split import Phase4Splitter

        splitter = Phase4Splitter(output_dir, canvas_w=canvas_w, canvas_h=canvas_h)
        splitter.run(manifest, layout)

    def _run_phase5(self, manifest, layout, output_dir, canvas_w, canvas_h):
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent.parent / "scripts"))
        from pipeline.phase5_compose import Phase5Composer

        composer = Phase5Composer(output_dir, canvas_w=canvas_w, canvas_h=canvas_h)
        composer.run(manifest, layout)
        return output_dir / "final.mp4"

    # ------------------------------------------------------------------
    # Context helpers
    # ------------------------------------------------------------------

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
