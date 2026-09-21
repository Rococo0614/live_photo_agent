from __future__ import annotations

import json
import sys
from pathlib import Path

from ..capability.live_photo_cli import is_live_photo_container, unpack_motion_photo
from ..models import LivePhotoAsset, ToolCall, ToolName, ToolResult


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
        from ..config import settings
        output_dir = Path(str(call.arguments.get("output_dir", ""))) or (settings.agent_work_dir / "collage")
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

        self._cleanup_decoded_dir(output_dir)

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

        Note: The unpacked _decoded directory is NOT cleaned up here because the
        caller (live_photo_collage / template_collage) needs the mp4 files for
        the actual collage processing. Cleanup is the caller's responsibility.
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

    def _cleanup_decoded_dir(self, output_dir: Path) -> None:
        """Remove the _decoded temp directory after collage processing is done."""
        import shutil
        decode_dir = output_dir / "_decoded"
        if decode_dir.exists():
            shutil.rmtree(decode_dir, ignore_errors=True)

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

    def template_collage(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        """Template collage: directly stack videos per layout, no segmentation.

        A standalone tool for simple multi-clip compositions (三格竖排, 四格竖排, etc).
        Does NOT run Mask2Former / Cutie / bg-subject-alpha split.
        """
        asset_paths = call.arguments.get("asset_paths", [])
        from ..config import settings
        output_dir = Path(str(call.arguments.get("output_dir", ""))) or (settings.agent_work_dir / "collage")
        canvas_w = int(call.arguments.get("canvas_width", 1440))
        canvas_h = int(call.arguments.get("canvas_height", 1920))
        layout_type = str(call.arguments.get("layout_type", "vertical"))
        template_id = str(call.arguments.get("template_id", ""))
        template_slots = call.arguments.get("template_slots", [])

        if not asset_paths:
            assets = self._preferred_assets(context)
            asset_paths = [str(a.image_path) for a in assets]
            motion_paths = [str(a.motion_path) for a in assets if a.motion_path]
            asset_paths = asset_paths + motion_paths

        if not asset_paths:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": "no_assets", "error_code": "no_assets"},
            )

        output_dir.mkdir(parents=True, exist_ok=True)

        try:
            final_path = self._stack_videos(
                asset_paths, output_dir, canvas_w, canvas_h, layout_type,
                template_id=template_id, template_slots=template_slots,
                total_duration_s=float(call.arguments.get("total_duration_s", 6.0)))
        except Exception as exc:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": str(exc), "error_code": "composition_failed"},
            )

        layout = {
            "canvas": {"w": canvas_w, "h": canvas_h},
            "layout_type": layout_type,
            "template_id": template_id,
            "asset_count": len(asset_paths),
        }
        self._cleanup_decoded_dir(output_dir)
        return ToolResult(
            tool=call.tool,
            success=True,
            payload={
                "final_video": str(final_path),
                "layout_plan": layout,
            },
        )

    def _stack_videos(self, asset_paths, output_dir, canvas_w, canvas_h, layout_type,
                      template_id="", template_slots=None, total_duration_s=6.0):
        """按模板布局将多个视频堆叠到画布, 支持时间窗口 (v2)。

        v1: 所有素材同时播放, 帧数 = min(各素材帧数)
        v2: 按 total_duration_s 生成视频, 每个素材在 [start_time_s, end_time_s] 内出现
            支持转场 (fade/slide), fill_mode (freeze/loop/trim)
        """
        import cv2
        import numpy as np
        import subprocess, shutil

        n = len(asset_paths)
        template_slots = template_slots or []
        fps = 30.0

        # --- Build placements: spatial + temporal ---
        placements = []
        for i, p in enumerate(asset_paths):
            slot = template_slots[i] if i < len(template_slots) else {}
            # Spatial
            if layout_type == "vertical":
                seg_h = canvas_h // n
                y = i * seg_h
                h = seg_h if i < n - 1 else (canvas_h - y)
                x, w = 0, canvas_w
            elif layout_type == "horizontal":
                seg_w = canvas_w // n
                x = i * seg_w
                w = seg_w if i < n - 1 else (canvas_w - x)
                y, h = 0, canvas_h
            else:
                # Free layout from grid
                gx = int(slot.get("grid_x", 0))
                gy = int(slot.get("grid_y", i * (160 // n)))
                gw = int(slot.get("grid_w", 120))
                gh = int(slot.get("grid_h", 160 // n))
                x = int(gx / 120 * canvas_w)
                y = int(gy / 160 * canvas_h)
                w = int(gw / 120 * canvas_w)
                h = int(gh / 160 * canvas_h)

            # Temporal
            start_s = float(slot.get("start_time_s", 0.0))
            end_s = float(slot.get("end_time_s", total_duration_s))
            fill_mode = str(slot.get("fill_mode", "freeze"))
            enter_t = slot.get("enter_transition") or {}
            exit_t = slot.get("exit_transition") or {}
            enter_type = str(enter_t.get("type", "fade")) if enter_t else "fade"
            enter_ms = int(enter_t.get("duration_ms", 300)) if enter_t else 300
            exit_type = str(exit_t.get("type", "fade")) if exit_t else "fade"
            exit_ms = int(exit_t.get("duration_ms", 300)) if exit_t else 300

            placements.append({
                "path": p,
                "x": x, "y": y, "w": w, "h": h,
                "start_s": start_s, "end_s": end_s,
                "fill_mode": fill_mode,
                "enter_type": enter_type, "enter_ms": enter_ms,
                "exit_type": exit_type, "exit_ms": exit_ms,
            })

        # --- Open video captures ---
        caps = []
        for p in placements:
            vpath = p["path"]
            if not Path(vpath).exists():
                continue
            cap = cv2.VideoCapture(str(vpath))
            if cap.isOpened():
                f = cap.get(cv2.CAP_PROP_FPS)
                if f > 0:
                    fps = f
                caps.append((p, cap))

        if not caps:
            raise RuntimeError("stack_videos: 没有可用的视频素材")

        # --- Calculate total frames ---
        total_frames = int(total_duration_s * fps)
        print(f"  [template_collage] template={template_id} slots={n} duration={total_duration_s}s frames={total_frames} fps={fps}")

        # --- Pre-read all frames for each asset (for freeze/loop/trim) ---
        asset_frames: list[list[np.ndarray]] = []
        for p, cap in caps:
            frames = []
            while True:
                ret, frame = cap.read()
                if not ret:
                    break
                frames.append(frame)
            cap.release()
            asset_frames.append(frames)
            print(f"  [template_collage] {Path(p['path']).name}: {len(frames)} frames, window=[{p['start_s']:.1f}s, {p['end_s']:.1f}s], fill={p['fill_mode']}")

        # --- Render each frame ---
        out_path = output_dir / "final_raw.mp4"
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(out_path), fourcc, fps, (canvas_w, canvas_h))

        if not writer.isOpened():
            raise RuntimeError("stack_videos: VideoWriter 无法打开, 可能磁盘空间不足")

        for fi in range(total_frames):
            t = fi / fps  # current time in seconds
            canvas = np.zeros((canvas_h, canvas_w, 3), dtype=np.uint8)

            for idx, (p, frames) in enumerate(zip(caps, asset_frames)):
                start_s = p["start_s"]
                end_s = p["end_s"]

                # Check if this asset is visible at time t
                if t < start_s or t >= end_s:
                    continue

                if not frames:
                    continue

                # Determine which frame to show based on fill_mode
                window_duration = end_s - start_s
                elapsed = t - start_s
                if p["fill_mode"] == "trim":
                    # Play at normal speed, stop at end
                    frame_idx = min(int(elapsed * fps), len(frames) - 1)
                elif p["fill_mode"] == "loop":
                    frame_idx = int(elapsed * fps) % len(frames)
                elif p["fill_mode"] == "stretch":
                    # Slow down to fill exactly
                    if window_duration > 0:
                        ratio = elapsed / window_duration
                        frame_idx = min(int(ratio * len(frames)), len(frames) - 1)
                    else:
                        frame_idx = 0
                else:  # freeze (default)
                    frame_idx = min(int(elapsed * fps), len(frames) - 1)

                frame = frames[frame_idx]

                # Resize + crop to slot
                seg_w, seg_h = p["w"], p["h"]
                fh, fw = frame.shape[:2]
                scale = max(seg_w / fw, seg_h / fh)
                new_w, new_h = int(fw * scale), int(fh * scale)
                resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_AREA)
                x0 = (new_w - seg_w) // 2
                y0 = (new_h - seg_h) // 2
                crop = resized[y0:y0 + seg_h, x0:x0 + seg_w]

                # Apply enter transition
                enter_ms = p["enter_ms"]
                enter_frames = int(enter_ms / 1000 * fps)
                if enter_frames > 0 and elapsed < enter_ms / 1000 and p["enter_type"] == "fade":
                    alpha = elapsed / (enter_ms / 1000)
                    crop = (crop * alpha).astype(np.uint8)

                # Apply exit transition
                exit_ms = p["exit_ms"]
                exit_frames = int(exit_ms / 1000 * fps)
                time_to_end = end_s - t
                if exit_frames > 0 and time_to_end < exit_ms / 1000 and p["exit_type"] == "fade":
                    alpha = time_to_end / (exit_ms / 1000)
                    crop = (crop * max(0, alpha)).astype(np.uint8)

                # Place on canvas
                cx, cy = p["x"], p["y"]
                end_y = min(cy + seg_h, canvas_h)
                end_x = min(cx + seg_w, canvas_w)
                # Blend if canvas already has content (for overlapping windows)
                target = canvas[cy:end_y, cx:end_x]
                src = crop[:end_y - cy, :end_x - cx]
                if np.any(target):
                    # Alpha blend
                    alpha_mask = (src > 0).any(axis=2).astype(np.float32) * 0.5
                    for c in range(3):
                        target[:, :, c] = (target[:, :, c] * (1 - alpha_mask) + src[:, :, c] * alpha_mask).astype(np.uint8)
                    canvas[cy:end_y, cx:end_x] = target
                else:
                    canvas[cy:end_y, cx:end_x] = src

            writer.write(canvas)
            if fi % 30 == 0:
                print(f"  [template_collage] 帧 {fi}/{total_frames} (t={t:.1f}s)")

        writer.release()
        print(f"  [template_collage] final_raw.mp4: {out_path} ({out_path.stat().st_size/1e6:.1f}MB)")

        # 转码 H.264
        final_path = output_dir / "final.mp4"
        ffmpeg = shutil.which("ffmpeg")
        if ffmpeg:
            rc = subprocess.run(
                [ffmpeg, "-y", "-i", str(out_path),
                 "-c:v", "libx264", "-preset", "fast", "-crf", "20",
                 "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                 str(final_path)],
                capture_output=True, text=True,
            )
            if rc.returncode == 0 and final_path.exists() and final_path.stat().st_size > 1000:
                out_path.unlink()  # 删除 raw
                print(f"  [template_collage] final.mp4 (H.264): {final_path} ({final_path.stat().st_size/1e6:.1f}MB)")
            else:
                # ffmpeg 转码失败 — 用 ffprobe 验证 raw 是否可播放
                probe_rc = subprocess.run(
                    ["ffprobe", "-v", "error", "-show_format", str(out_path)],
                    capture_output=True, text=True,
                )
                if probe_rc.returncode == 0:
                    # raw 文件本身是好的, 直接用它
                    out_path.rename(final_path)
                    print(f"  [template_collage] H.264 转码失败, 使用 mp4v raw: {final_path} ({final_path.stat().st_size/1e6:.1f}MB)")
                else:
                    if final_path.exists():
                        final_path.unlink()
                    raise RuntimeError(f"stack_videos: ffmpeg 转码失败且 raw 文件损坏\nffmpeg stderr: {rc.stderr[:500]}")
        else:
            # 没有 ffmpeg, 直接用 raw
            out_path.rename(final_path)
            print(f"  [template_collage] final.mp4 (mp4v, no ffmpeg): {final_path} ({final_path.stat().st_size/1e6:.1f}MB)")

        return final_path

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

    # ------------------------------------------------------------------
    # Smart Collage: search + template match + composition
    # ------------------------------------------------------------------

    def smart_collage(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        """Smart collage: search K assets, match template, VLM score, compose.

        Phase 0: Search + Template Match + VLM Score
        Phase 1-5: Existing collage pipeline
        """
        query = str(call.arguments.get("query", ""))
        k = int(call.arguments.get("k", 3))
        template_id = call.arguments.get("template_id")
        exclude_template_id = call.arguments.get("exclude_template_id")
        # All intermediate/output files go to .agent_work, NOT the library
        from ..config import settings
        raw_output = str(call.arguments.get("output_dir", ""))
        if not raw_output or raw_output in ("output", "outputs", "result", "results"):
            output_dir = settings.agent_work_dir / "collage_output"
        else:
            output_dir = Path(raw_output)
        # Normalize template_id: planner sometimes generates placeholder values
        if template_id in ("template", "default", "auto", ""):
            template_id = None
        # Normalize exclude_template_id
        if exclude_template_id in ("template", "default", "auto", ""):
            exclude_template_id = None
        library_root = Path(str(call.arguments.get("library_root", context.get("library_root", "data/live_photo"))))
        print(f"  [smart_collage] library_root={library_root} (exists={library_root.exists()})")

        # If library_root doesn't have a usable index or any assets, try library_root/live_photo
        db_check = library_root / ".asset_store.db"
        index_empty = not db_check.exists() or db_check.stat().st_size == 0
        # Also check old JSONL for backward compat
        if index_empty:
            jsonl_check = library_root / ".asset_search_index.jsonl"
            index_empty = not jsonl_check.exists() or jsonl_check.stat().st_size == 0
        no_assets = not list(library_root.glob("*.jpg"))[:1] and not list(library_root.glob("*.mp4"))[:1]
        if index_empty and no_assets:
            sub = library_root / "live_photo"
            sub_db = sub / ".asset_store.db"
            sub_jsonl = sub / ".asset_search_index.jsonl"
            if sub.exists() and (sub_db.exists() or sub_jsonl.exists()):
                print(f"  [smart_collage] redirecting library_root → {sub}")
                library_root = sub

        if not query:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": "no_query", "error_code": "no_matching_assets"},
            )

        output_dir.mkdir(parents=True, exist_ok=True)

        # === Phase 0: Search + Template Match + Score ===
        from ..foundation.retrieval import (
            AssetIndexer,
            AssetSearcher,
            TemplateLibrary,
            TemplateMatcher,
            VLMScorer,
        )

        # Step 0a: Build/load asset index from SQLite database
        db_path = library_root / ".asset_store.db"
        indexer = AssetIndexer(db_path=db_path)

        # Try to load existing index
        index_rows = indexer.load_index()
        if not index_rows:
            # Build index from library — use lightweight scan (no unpacking)
            print("  [smart_collage] Building asset index...")
            assets_to_index = self._scan_library_assets(library_root)
            if assets_to_index:
                report = indexer.build(assets_to_index)
                print(f"  [smart_collage] Index: {report}")
                index_rows = indexer.load_index()
            else:
                print(f"  [smart_collage] No assets found in {library_root}")

        indexer.close()

        if not index_rows:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": "empty_index", "error_code": "no_matching_assets"},
            )

        print(f"  [smart_collage] Index loaded: {len(index_rows)} assets")

        # Step 0b: Search K assets
        searcher = AssetSearcher(index_rows=index_rows, db_path=str(db_path))
        results = searcher.search(query, k=k, require_video=True)
        print(f"  [smart_collage] Search '{query}' → {len(results)} results")
        for r in results:
            print(f"    {r['asset_id']}: score={r['score']:.3f} summary={r.get('content_summary', '')[:50]}")

        # 释放搜索模型, 回收 GPU 显存 (防止后续 collage pipeline OOM)
        searcher.release_embedder()

        if not results:
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": "no_results", "error_code": "no_matching_assets"},
            )

        # Step 0c: Template match
        template_dir = output_dir / "templates"
        library = TemplateLibrary(template_dir=template_dir if template_dir.exists() else None)
        matcher = TemplateMatcher(library)

        candidates = matcher.match(results, preferred_template_id=str(template_id) if template_id else None)
        
        # Exclude previously used template if requested (e.g. user said "换个模板")
        if exclude_template_id:
            before = len(candidates)
            candidates = [c for c in candidates if c["template"]["id"] != exclude_template_id]
            print(f"  [smart_collage] Excluded template {exclude_template_id}, {before}→{len(candidates)} candidates")

        print(f"  [smart_collage] Template match → {len(candidates)} candidates")
        for c in candidates[:3]:
            print(f"    {c['template']['id']} ({c['template']['name']}): score={c['score']:.3f}")

        if not candidates:
            # No alternative template with the same slot count.
            # Tell the user what slot counts have alternatives.
            all_templates = library.list_all()
            by_slot: dict[int, list[str]] = {}
            for t in all_templates:
                by_slot.setdefault(t.slot_count, []).append(f"{t.id}({t.name})")
            available = []
            for sc, ids in sorted(by_slot.items()):
                if len(ids) > 1:
                    available.append(f"{sc}拼: {', '.join(ids)}")
            msg = f"当前 {k} 张素材只有 1 个模板可选，无法替换。"
            if available:
                msg += f" 以下拼法有多个模板可换: {'; '.join(available)}"
            return ToolResult(
                tool=call.tool,
                success=False,
                payload={"error": "no_alternative_template", "error_code": "no_matching_template", "message": msg},
            )

        # Step 0d: VLM score
        scorer = VLMScorer()
        scored_candidates = []
        for c in candidates:
            score_result = scorer.score(results, c["template"], c["assignment"])
            scored_candidates.append({
                **c,
                "vlm_score": score_result["score"],
                "vlm_reason": score_result["reason"],
                "vlm_details": score_result["details"],
            })

        scored_candidates.sort(key=lambda c: (
            c.get("vlm_score", c["score"])
            + (0.15 if not c["template"].get("needs_segmentation", False) else 0.0)
        ), reverse=True)

        # When the planner LLM is resident on GPU, skip templates that need
        # segmentation (Mask2Former + Cutie would OOM alongside the planner).
        import torch
        gpu_free_mb = 0
        if torch.cuda.is_available():
            gpu_free_mb = torch.cuda.mem_get_info()[0] // (1024 * 1024)
        if gpu_free_mb < 10000:  # Less than 10GB free → can't run segmentation
            non_seg = [c for c in scored_candidates if not c["template"].get("needs_segmentation", False)]
            if non_seg:
                scored_candidates = non_seg
                print(f"  [smart_collage] GPU free={gpu_free_mb}MB < 10GB, skipping segmentation templates")

        print(f"  [smart_collage] VLM scored, top: {scored_candidates[0]['template']['id']} "
              f"score={scored_candidates[0].get('vlm_score', 0):.3f}")

        # Select best candidate
        best = scored_candidates[0]
        selected_template = best["template"]
        assignment = best["assignment"]

        # Build asset_paths for collage pipeline (prefer motion_path/video)
        # For live photo containers (jpg with embedded mp4), unpack to get mp4.
        from ..capability.live_photo_cli import is_live_photo_container, unpack_motion_photo
        from ..config import settings
        decode_dir = settings.agent_work_dir / "decoded"
        decode_dir.mkdir(parents=True, exist_ok=True)

        asset_paths = []
        for a in results:
            motion_path = a.get("motion_path")
            image_path = a.get("image_path")

            if motion_path and Path(str(motion_path)).exists():
                asset_paths.append(motion_path)
            elif image_path and Path(str(image_path)).exists():
                # Check if it's a live photo container — if so, unpack mp4
                p = Path(str(image_path))
                if p.suffix.lower() in (".jpg", ".jpeg") and is_live_photo_container(p):
                    try:
                        _, mp4_out = unpack_motion_photo(p, decode_dir / p.stem)
                        asset_paths.append(str(mp4_out))
                        print(f"  [smart_collage] Unpacked live photo: {p.name} → {mp4_out.name}")
                    except Exception as exc:
                        print(f"  [smart_collage] WARN: Failed to unpack {p.name}: {exc}")
                else:
                    # Plain image or video
                    asset_paths.append(image_path)

        # Deduplicate while preserving order
        seen = set()
        unique_paths = []
        for p in asset_paths:
            if p not in seen:
                seen.add(p)
                unique_paths.append(p)

        print(f"  [smart_collage] Selected template: {selected_template['id']} ({selected_template['name']})")
        print(f"  [smart_collage] Assets for collage: {unique_paths}")

        # === Dispatch to the right collage tool ===
        needs_seg = selected_template.get("needs_segmentation", False)
        try:
            from live_photo_agent.capability.l2_vertical_tools import L2VerticalTools as L2

            collage_tool = L2()
            if needs_seg:
                print(f"  [smart_collage] Dispatching to LIVE_PHOTO_COLLAGE (overlay pipeline)")
                collage_call = ToolCall(
                    tool=ToolName.LIVE_PHOTO_COLLAGE,
                    reason=f"Overlay collage with segmented subjects: {query}",
                    arguments={
                        "asset_paths": unique_paths,
                        "output_dir": str(output_dir / "collage"),
                        "canvas_width": 1440,
                        "canvas_height": 1920,
                    },
                )
                collage_result = collage_tool.live_photo_collage(collage_call, context)
            else:
                print(f"  [smart_collage] Dispatching to TEMPLATE_COLLAGE (direct stacking)")
                layout_type = selected_template.get("layout_type", "vertical")
                # Pass template slots + assignment so _stack_videos can use slot proportions
                template_slots = selected_template.get("slots", [])
                # Reorder asset_paths by assignment (slot_index order)
                assignment = best.get("assignment", [])
                if assignment:
                    # Build a map: asset_id → resolved video path (from unique_paths order)
                    # unique_paths was built earlier from results in order, so map by index
                    id_to_path: dict[str, str] = {}
                    for idx, r in enumerate(results):
                        if idx < len(unique_paths):
                            id_to_path[r.get("asset_id", "")] = unique_paths[idx]
                    ordered_paths = []
                    for a in sorted(assignment, key=lambda x: x.get("slot_index", 0)):
                        aid = a.get("asset_id", "")
                        p = id_to_path.get(aid, "")
                        if p:
                            ordered_paths.append(p)
                    if ordered_paths:
                        unique_paths = ordered_paths
                collage_call = ToolCall(
                    tool=ToolName.TEMPLATE_COLLAGE,
                    reason=f"Template collage (no segmentation): {query}",
                    arguments={
                        "asset_paths": unique_paths,
                        "output_dir": str(output_dir / "collage"),
                        "canvas_width": 1440,
                        "canvas_height": 1920,
                        "layout_type": layout_type,
                        "template_id": selected_template.get("id", ""),
                        "template_slots": template_slots,
                    },
                )
                collage_result = collage_tool.template_collage(collage_call, context)

            if not collage_result.success:
                return ToolResult(
                    tool=call.tool,
                    success=False,
                    payload={"error": collage_result.payload.get("error", "collage_failed"),
                             "error_code": "composition_failed"},
                )
            final_video = collage_result.payload.get("final_video", "")
        except Exception as e:
            print(f"  [smart_collage] Collage failed: {e}")
            final_video = ""

        # Save recommendations
        recommendations = []
        for c in scored_candidates[:5]:
            recommendations.append({
                "template_id": c["template"]["id"],
                "template_name": c["template"]["name"],
                "template_description": c["template"]["description"],
                "match_score": c["score"],
                "vlm_score": c.get("vlm_score", 0),
                "vlm_reason": c.get("vlm_reason", ""),
                "assignment": c["assignment"],
            })

        rec_path = output_dir / "recommendations.json"
        rec_path.write_text(json.dumps({
            "query": query,
            "k": k,
            "search_results": [{"asset_id": r["asset_id"], "score": r["score"],
                                "content_summary": r.get("content_summary", "")} for r in results],
            "recommendations": recommendations,
            "selected": {
                "template_id": selected_template["id"],
                "template_name": selected_template["name"],
            },
        }, indent=2, ensure_ascii=False))

        context["smart_collage_output"] = {
            "query": query,
            "search_results": results,
            "recommendations": recommendations,
            "selected_template": selected_template,
            "final_video": final_video,
        }

        return ToolResult(
            tool=call.tool,
            success=True,
            payload={
                "recommendations": recommendations,
                "final_video": final_video,
                "selected_template": selected_template,
                "search_results": [{"asset_id": r["asset_id"], "score": r["score"],
                                    "content_summary": r.get("content_summary", "")} for r in results],
            },
        )

    # ------------------------------------------------------------------
    # Asset Summarize: VLM-based content analysis for live videos
    # ------------------------------------------------------------------

    def asset_summarize(self, call: ToolCall, context: dict[str, object]) -> ToolResult:
        """VLM-based asset summarization.

        Extracts key frames from each video, sends to Qwen2.5-VL for content
        description (summary, scene/subject/motion tags), updates the search index.
        The VL model is loaded on-demand and released after processing.

        Streaming mode: unpacks one live photo at a time, processes it, then
        deletes the temp mp4 before moving to the next. This avoids filling
        the disk when the library has 1000+ assets.
        """
        import cv2
        import torch

        library_root = Path(str(call.arguments.get("library_root", context.get("library_root", "data/live_photo"))))
        asset_ids_filter = call.arguments.get("asset_ids", [])
        force_rebuild = bool(call.arguments.get("force_rebuild", False))
        max_frames = int(call.arguments.get("max_frames_per_asset", 4))

        # Resolve library_root (same redirect logic as smart_collage)
        db_check = library_root / ".asset_store.db"
        index_empty = not db_check.exists() or db_check.stat().st_size == 0
        if index_empty:
            jsonl_check = library_root / ".asset_search_index.jsonl"
            index_empty = not jsonl_check.exists() or jsonl_check.stat().st_size == 0
        no_assets = not list(library_root.glob("*.jpg"))[:1] and not list(library_root.glob("*.mp4"))[:1]
        if index_empty and no_assets:
            sub = library_root / "live_photo"
            sub_db = sub / ".asset_store.db"
            sub_jsonl = sub / ".asset_search_index.jsonl"
            if sub.exists() and (sub_db.exists() or sub_jsonl.exists()):
                library_root = sub

        # Scan assets — lightweight scan, no unpacking yet
        assets = self._scan_library_assets(library_root)
        if not assets:
            return ToolResult(
                tool=call.tool, success=False,
                payload={"error": "no_assets", "error_code": "no_assets"},
            )

        # Filter by asset_ids if specified
        if asset_ids_filter:
            assets = [a for a in assets if a["asset_id"] in asset_ids_filter]
            if not assets:
                return ToolResult(
                    tool=call.tool, success=False,
                    payload={"error": "no_matching_assets", "error_code": "no_assets"},
                )

        # Load existing index to check which assets need summarizing
        from ..foundation.retrieval.asset_indexer import AssetIndexer
        db_path = library_root / ".asset_store.db"
        indexer = AssetIndexer(db_path=db_path)
        existing = indexer._read_index()

        # Determine which assets need VLM summarization
        to_summarize = []
        for asset in assets:
            aid = asset["asset_id"]
            prev = existing.get(aid)
            vlm_summary = asset.get("vlm_summary", {})
            has_real_summary = (
                vlm_summary and isinstance(vlm_summary, dict)
                and vlm_summary.get("content_summary")
                and vlm_summary.get("content_summary") != aid
            )
            if force_rebuild or not has_real_summary:
                to_summarize.append(asset)

        print(f"  [asset_summarize] {len(assets)} total assets, {len(to_summarize)} need summarization")

        if not to_summarize:
            return ToolResult(
                tool=call.tool, success=True,
                payload={
                    "summarized_count": 0,
                    "updated_index": str(db_path),
                    "summaries": [],
                    "message": "All assets already have VLM summaries",
                },
            )

        # Load local VLM via unified runtime
        from ..foundation.local_vlm import get_runtime, release_runtime
        try:
            runtime = get_runtime()
            model = runtime.model
            processor = runtime.processor
        except Exception as exc:
            return ToolResult(
                tool=call.tool, success=False,
                payload={"error": f"vlm_load_failed: {exc}", "error_code": "vlm_load_failed"},
            )

        print(f"  [asset_summarize] VLM ready. GPU memory: {torch.cuda.memory_allocated()/1e9:.1f}GB")

        # Process each asset — streaming: unpack → summarize → cleanup temp
        from ..capability.live_photo_cli import is_live_photo_container, unpack_motion_photo
        from ..config import settings
        decode_dir = settings.agent_work_dir / "decoded"
        decode_dir.mkdir(parents=True, exist_ok=True)

        summaries = []
        for i, asset in enumerate(to_summarize):
            aid = asset["asset_id"]
            image_path = Path(asset.get("image_path", ""))
            motion_path = asset.get("motion_path") or ""

            # Streaming unpack: if the image is a live photo container, unpack
            # to a temp dir, process, then delete the temp mp4.
            temp_mp4 = None
            if not motion_path and image_path.exists() and is_live_photo_container(image_path):
                try:
                    _, mp4 = unpack_motion_photo(image_path, decode_dir / aid)
                    temp_mp4 = mp4
                    motion_path = str(mp4)
                except Exception:
                    motion_path = ""

            if not motion_path or not Path(motion_path).exists():
                print(f"  [asset_summarize] SKIP {aid}: no video file")
                continue

            print(f"  [asset_summarize] ({i+1}/{len(to_summarize)}) Processing {aid}...")

            try:
                # Extract key frames
                frames = self._extract_key_frames_from_video(motion_path, max_frames)
                if not frames:
                    print(f"  [asset_summarize] SKIP {aid}: no frames extracted")
                    continue

                # Send to VL model
                summary = self._vlm_summarize_frames(model, processor, frames, aid)
                if summary:
                    asset["vlm_summary"] = summary
                    summaries.append({"asset_id": aid, "summary": summary})
                    print(f"  [asset_summarize] {aid}: {summary.get('content_summary', '')[:80]}")
                else:
                    print(f"  [asset_summarize] {aid}: VLM returned empty")
            except Exception as exc:
                print(f"  [asset_summarize] FAILED {aid}: {exc}")
            finally:
                # Cleanup temp mp4 to save disk space
                if temp_mp4 and temp_mp4.exists():
                    temp_mp4.unlink()
                    # Also try to remove the parent dir if empty
                    try:
                        temp_mp4.parent.rmdir()
                    except OSError:
                        pass

        # Release VL model via unified runtime
        release_runtime()
        print(f"  [asset_summarize] VL model released. GPU memory: {torch.cuda.memory_allocated()/1e9:.1f}GB")

        # Rebuild index with new summaries
        report = indexer.build(assets, force_rebuild=force_rebuild)
        print(f"  [asset_summarize] Index updated: {report}")

        return ToolResult(
            tool=call.tool, success=True,
            payload={
                "summarized_count": len(summaries),
                "updated_index": str(db_path),
                "summaries": summaries,
                "index_report": report,
            },
        )

    def _extract_key_frames_from_video(self, video_path: str, max_frames: int = 4) -> list:
        """Extract evenly-spaced key frames from a video as PIL Images."""
        import cv2
        from PIL import Image

        cap = cv2.VideoCapture(str(video_path))
        if not cap.isOpened():
            return []

        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        if total <= 0:
            cap.release()
            return []

        # Evenly sample frames
        indices = [int(total * (i + 0.5) / max_frames) for i in range(min(max_frames, total))]
        frames = []
        for idx in indices:
            cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
            ret, frame = cap.read()
            if ret:
                # Convert BGR → RGB → PIL
                frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                # Resize to max 448px on long side for VLM efficiency
                h, w = frame_rgb.shape[:2]
                scale = 448 / max(h, w)
                if scale < 1.0:
                    frame_rgb = cv2.resize(frame_rgb, (int(w * scale), int(h * scale)))
                frames.append(Image.fromarray(frame_rgb))

        cap.release()
        return frames

    def _vlm_summarize_frames(self, model, processor, frames: list, asset_id: str) -> dict | None:
        """Send frames to Qwen2.5-VL and get structured content summary."""
        import torch

        if not frames:
            return None

        # Build message with images
        content = []
        for frame in frames:
            content.append({"type": "image", "image": frame})
        content.append({
            "type": "text",
            "text": (
                "这是一个实况照片(live photo)的视频帧序列。请分析内容并返回 JSON 格式的描述:\n"
                '{"content_summary": "一句话描述这个视频的主要内容", '
                '"scene_tags": ["场景标签1", "场景标签2"], '
                '"subject_tags": ["主体标签1"], '
                '"motion_tags": ["动态特征1"], '
                '"content_tags": ["内容标签1"]}\n'
                "只返回 JSON，不要其他文字。"
            ),
        })

        messages = [{"role": "user", "content": content}]

        try:
            text = processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
            inputs = processor(text=text, images=frames, return_tensors="pt", padding=True)
            inputs = {k: v.to(model.device) if isinstance(v, torch.Tensor) else v for k, v in inputs.items()}

            with torch.no_grad():
                output = model.generate(**inputs, max_new_tokens=200, do_sample=False)

            generated = output[:, inputs["input_ids"].shape[1]:]
            response = processor.decode(generated[0], skip_special_tokens=True).strip()

            # Parse JSON from response
            import json
            # Find JSON in response
            start = response.find("{")
            end = response.rfind("}")
            if start >= 0 and end > start:
                result = json.loads(response[start:end + 1])
                return result
        except Exception as exc:
            print(f"  [vlm_summarize] Error: {exc}")
        return None

    def _scan_library_for_indexing(self, library_root: Path) -> list[dict[str, object]]:
        """扫描素材库, 生成索引输入。中间件输出到 .agent_work/decoded, 不污染 library。

        使用流式解包: 解包一个 → 读取信息 → 删除临时 mp4, 避免磁盘堆积。
        """
        from ..capability.live_photo_cli import is_live_photo_container, unpack_motion_photo
        from ..config import settings

        assets = []
        decode_dir = settings.agent_work_dir / "decoded"
        decode_dir.mkdir(parents=True, exist_ok=True)

        for jpg in sorted(library_root.glob("*.jpg")):
            asset_id = jpg.stem
            motion_path = None

            if is_live_photo_container(jpg):
                try:
                    _, mp4 = unpack_motion_photo(jpg, decode_dir / jpg.stem)
                    motion_path = str(mp4)
                    # Read metadata we need, then delete temp mp4
                    # (motion_path is only used for indexing, not for actual processing)
                except Exception:
                    motion_path = None

            # Try to find companion mp4
            if not motion_path:
                companion = jpg.with_suffix(".mp4")
                if companion.exists():
                    motion_path = str(companion)

            # Use filename as content_summary placeholder
            content_summary = jpg.stem.replace("_", " ")

            assets.append({
                "asset_id": asset_id,
                "image_path": str(jpg),
                "motion_path": motion_path,
                "vlm_summary": {
                    "content_summary": content_summary,
                    "scene_tags": [],
                    "subject_tags": [],
                    "motion_tags": [],
                    "content_tags": [],
                },
            })

        # Also scan for standalone mp4s
        for mp4 in sorted(library_root.glob("*.mp4")):
            asset_id = mp4.stem
            if any(a["asset_id"] == asset_id for a in assets):
                continue
            assets.append({
                "asset_id": asset_id,
                "image_path": str(mp4.with_suffix(".jpg")) if mp4.with_suffix(".jpg").exists() else str(mp4),
                "motion_path": str(mp4),
                "vlm_summary": {
                    "content_summary": mp4.stem.replace("_", " "),
                    "scene_tags": [],
                    "subject_tags": [],
                    "motion_tags": [],
                    "content_tags": [],
                },
            })

        # Cleanup: delete all temp mp4s created during this scan
        import shutil
        for sub in decode_dir.iterdir():
            if sub.is_dir():
                shutil.rmtree(sub, ignore_errors=True)
            else:
                sub.unlink(missing_ok=True)

        return assets

    def _scan_library_assets(self, library_root: Path) -> list[dict[str, object]]:
        """Lightweight scan: list assets without unpacking live photos.

        Unlike _scan_library_for_indexing, this does NOT unpack live photos
        to disk. The unpacking is deferred to the streaming processing loop
        in asset_summarize, which unpacks one at a time and cleans up.
        """
        assets = []

        for jpg in sorted(library_root.glob("*.jpg")):
            asset_id = jpg.stem
            # Check for companion mp4 (non-live-photo case)
            companion = jpg.with_suffix(".mp4")
            motion_path = str(companion) if companion.exists() else None

            content_summary = jpg.stem.replace("_", " ")
            assets.append({
                "asset_id": asset_id,
                "image_path": str(jpg),
                "motion_path": motion_path,
                "vlm_summary": {
                    "content_summary": content_summary,
                    "scene_tags": [],
                    "subject_tags": [],
                    "motion_tags": [],
                    "content_tags": [],
                },
            })

        for mp4 in sorted(library_root.glob("*.mp4")):
            asset_id = mp4.stem
            if any(a["asset_id"] == asset_id for a in assets):
                continue
            assets.append({
                "asset_id": asset_id,
                "image_path": str(mp4.with_suffix(".jpg")) if mp4.with_suffix(".jpg").exists() else str(mp4),
                "motion_path": str(mp4),
                "vlm_summary": {
                    "content_summary": mp4.stem.replace("_", " "),
                    "scene_tags": [],
                    "subject_tags": [],
                    "motion_tags": [],
                    "content_tags": [],
                },
            })

        return assets
