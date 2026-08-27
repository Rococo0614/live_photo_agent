from __future__ import annotations

import json
import math
import shutil
import subprocess
import tempfile
from pathlib import Path

JPEG_EOI = b"\xff\xd9"
FTYP_MAGIC = b"ftyp"


class MediaOpsError(RuntimeError):
    pass


class MediaOps:
    def __init__(self) -> None:
        self._ffmpeg = shutil.which("ffmpeg")
        self._ffprobe = shutil.which("ffprobe")
        # Hard ceiling for any single ffmpeg/ffprobe invocation. Prevents a
        # misconfigured filter graph (e.g. unbounded color source) from hanging
        # the agent process indefinitely.
        self.command_timeout_seconds: float = 300.0

    def ffmpeg_available(self) -> bool:
        return self._ffmpeg is not None and self._ffprobe is not None

    def probe_video(self, video_path: Path) -> dict[str, object]:
        self._ensure_binaries()
        if not video_path.exists():
            raise MediaOpsError(f"video_not_found: {video_path}")

        cmd = [
            str(self._ffprobe),
            "-v",
            "error",
            "-print_format",
            "json",
            "-show_streams",
            "-show_format",
            str(video_path),
        ]
        result = self._run(cmd)
        data = json.loads(result.stdout)
        streams = data.get("streams", [])
        video_stream = next((s for s in streams if s.get("codec_type") == "video"), {})
        fps_value = self._parse_fps(str(video_stream.get("r_frame_rate", "0/1")))
        duration = float(video_stream.get("duration") or data.get("format", {}).get("duration") or 0.0)
        return {
            "width": int(video_stream.get("width") or 0),
            "height": int(video_stream.get("height") or 0),
            "fps": fps_value,
            "duration_ms": int(duration * 1000),
            "codec": str(video_stream.get("codec_name") or "unknown"),
        }

    def extract_frames(
        self,
        video_path: Path,
        output_dir: Path,
        frame_interval_ms: int,
        max_frames: int,
    ) -> list[Path]:
        self._ensure_binaries()
        output_dir.mkdir(parents=True, exist_ok=True)
        interval_seconds = max(frame_interval_ms / 1000.0, 0.05)
        output_pattern = output_dir / "frame_%03d.jpg"
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-i",
            str(video_path),
            "-vf",
            f"fps=1/{interval_seconds}",
            "-frames:v",
            str(max_frames),
            str(output_pattern),
        ]
        self._run(cmd)
        return sorted(output_dir.glob("frame_*.jpg"))

    def motion_score(self, video_path: Path, sample_frames: int = 48) -> float:
        try:
            import cv2
            import numpy as np
        except ImportError as exc:
            raise MediaOpsError("opencv_missing") from exc

        capture = cv2.VideoCapture(str(video_path))
        if not capture.isOpened():
            raise MediaOpsError(f"open_video_failed: {video_path}")

        total_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        stride = max(total_frames // max(sample_frames, 1), 1)
        prev_gray = None
        diffs: list[float] = []
        index = 0

        while True:
            ok, frame = capture.read()
            if not ok:
                break
            if index % stride != 0:
                index += 1
                continue

            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            if prev_gray is not None:
                diff = cv2.absdiff(gray, prev_gray)
                diffs.append(float(np.mean(diff)))
            prev_gray = gray
            index += 1

            if len(diffs) >= sample_frames:
                break

        capture.release()
        if not diffs:
            return 0.0

        # Normalize rough motion score into [0, 1] for downstream ranking.
        raw = sum(diffs) / len(diffs)
        return max(0.0, min(1.0, raw / 30.0))

    def locate_cover_frame(
        self,
        video_path: Path,
        image_path: Path,
        sample_budget: int = 180,
        compare_size: int = 160,
    ) -> dict[str, object]:
        try:
            import cv2
            import numpy as np
        except ImportError as exc:
            raise MediaOpsError("opencv_missing") from exc

        if not video_path.exists():
            raise MediaOpsError(f"video_not_found: {video_path}")
        if not image_path.exists():
            raise MediaOpsError(f"image_not_found: {image_path}")

        cover = cv2.imread(str(image_path))
        if cover is None:
            raise MediaOpsError(f"image_decode_failed: {image_path}")
        cover_gray = self._prepare_match_frame(cover, compare_size=compare_size)

        capture = cv2.VideoCapture(str(video_path))
        if not capture.isOpened():
            raise MediaOpsError(f"open_video_failed: {video_path}")

        try:
            total_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
            fps = float(capture.get(cv2.CAP_PROP_FPS) or 0.0)
            probe = self.probe_video(video_path)
            duration_ms = int(probe.get("duration_ms", 0))
            if fps <= 0:
                fps = float(probe.get("fps", 0.0))
            if total_frames <= 0 and fps > 0 and duration_ms > 0:
                total_frames = max(int(round(duration_ms * fps / 1000.0)), 1)

            stride = max(total_frames // max(sample_budget, 1), 1) if total_frames > 0 else 1
            best_frame_index: int | None = None
            best_distance = float("inf")

            frame_index = 0
            while True:
                ok, frame = capture.read()
                if not ok:
                    break
                if frame_index % stride != 0:
                    frame_index += 1
                    continue

                distance = self._frame_match_distance(frame, cover_gray, compare_size=compare_size, np_module=np)
                if distance < best_distance:
                    best_distance = distance
                    best_frame_index = frame_index
                frame_index += 1

            if best_frame_index is None:
                raise MediaOpsError(f"no_frames_sampled: {video_path}")

            if stride > 1 and total_frames > 0:
                refine_start = max(best_frame_index - stride + 1, 0)
                refine_end = min(best_frame_index + stride - 1, total_frames - 1)
                capture.set(cv2.CAP_PROP_POS_FRAMES, refine_start)
                frame_index = refine_start
                while frame_index <= refine_end:
                    ok, frame = capture.read()
                    if not ok:
                        break
                    distance = self._frame_match_distance(frame, cover_gray, compare_size=compare_size, np_module=np)
                    if distance < best_distance:
                        best_distance = distance
                        best_frame_index = frame_index
                    frame_index += 1

            if fps > 0:
                timestamp_ms = int(round(best_frame_index * 1000.0 / fps))
            elif duration_ms > 0 and total_frames > 0:
                timestamp_ms = int(round(best_frame_index * duration_ms / max(total_frames - 1, 1)))
            else:
                timestamp_ms = 0

            position_ratio = (
                max(0.0, min(1.0, timestamp_ms / duration_ms))
                if duration_ms > 0
                else 0.0
            )
            match_score = max(0.0, min(1.0, 1.0 - (best_distance / 255.0)))

            return {
                "cover_frame_index": int(best_frame_index),
                "cover_frame_timestamp_ms": int(timestamp_ms),
                "cover_frame_position_ratio": round(position_ratio, 4),
                "cover_frame_match_score": round(match_score, 4),
            }
        finally:
            capture.release()

    def segment_subject(
        self,
        image_path: Path,
        output_mask_path: Path,
        mode: str = "person_first",
        rect: tuple[int, int, int, int] | None = None,
    ) -> dict[str, object]:
        _ = mode
        try:
            import cv2
            import numpy as np
        except ImportError as exc:
            raise MediaOpsError("opencv_missing") from exc

        image = cv2.imread(str(image_path))
        if image is None:
            raise MediaOpsError(f"image_decode_failed: {image_path}")

        height, width = image.shape[:2]
        # grabCut cost scales ~quadratically with pixels. Phone photos are often
        # 3000x4000+, where a full-res grabCut takes 20-30s. Downscale to a cap
        # (preserving aspect) for the mask, then upscale the mask back to native
        # resolution — visually indistinguishable for compositing, ~50-100x faster.
        max_side = 512
        scale = min(1.0, max_side / max(width, height))
        if scale < 1.0:
            small = cv2.resize(image, (max(1, int(width * scale)), max(1, int(height * scale))))
        else:
            small = image

        s_h, s_w = small.shape[:2]
        mask = np.zeros(small.shape[:2], np.uint8)
        bg_model = np.zeros((1, 65), np.float64)
        fg_model = np.zeros((1, 65), np.float64)
        if rect is not None:
            # User-provided subject rectangle (already in small-image pixels).
            # Constrains grabCut to the framed region instead of the whole frame.
            rx, ry, rw, rh = rect
            rx = max(0, min(s_w - 1, int(rx)))
            ry = max(0, min(s_h - 1, int(ry)))
            rw = max(1, min(s_w - rx, int(rw)))
            rh = max(1, min(s_h - ry, int(rh)))
            grab_rect = (rx, ry, rw, rh)
        else:
            grab_rect = (
                max(1, s_w // 10),
                max(1, s_h // 10),
                max(2, s_w * 8 // 10),
                max(2, s_h * 8 // 10),
            )
        cv2.grabCut(small, mask, grab_rect, bg_model, fg_model, 4, cv2.GC_INIT_WITH_RECT)
        small_binary = ((mask == 1) | (mask == 3)).astype("uint8") * 255
        if scale < 1.0:
            binary = cv2.resize(small_binary, (width, height), interpolation=cv2.INTER_NEAREST)
        else:
            binary = small_binary

        output_mask_path.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(output_mask_path), binary)

        foreground_ratio = float(binary.mean() / 255.0)
        return {
            "mask_path": str(output_mask_path),
            "foreground_ratio": foreground_ratio,
        }

    def extract_subject_matte_frames(
        self,
        video_path: Path,
        output_dir: Path,
        mode: str = "mog2",
    ) -> dict[str, object]:
        """Cut out the moving subject across an entire motion clip.

        Uses background-subtraction (MOG2/KNN) rather than a per-frame deep model:
        it needs no extra dependency or downloaded weights, and works reasonably
        well for live-photo clips where the camera is roughly static and the
        subject moves. Each frame is written as an RGBA PNG (alpha = cleaned
        foreground mask) so downstream compositing can treat the sequence as a
        transparent overlay without an intermediate alpha-video codec.
        """
        try:
            import cv2
            import numpy as np
        except ImportError as exc:
            raise MediaOpsError("opencv_missing") from exc

        if not video_path.exists():
            raise MediaOpsError(f"video_not_found: {video_path}")

        capture = cv2.VideoCapture(str(video_path))
        if not capture.isOpened():
            raise MediaOpsError(f"open_video_failed: {video_path}")

        normalized_mode = mode.strip().lower()
        if normalized_mode == "knn":
            subtractor = cv2.createBackgroundSubtractorKNN(detectShadows=True)
        else:
            subtractor = cv2.createBackgroundSubtractorMOG2(detectShadows=True)

        probe = self.probe_video(video_path)
        fps = float(probe.get("fps") or capture.get(cv2.CAP_PROP_FPS) or 25.0)
        if fps <= 0:
            fps = 25.0

        output_dir.mkdir(parents=True, exist_ok=True)
        kernel = np.ones((5, 5), np.uint8)

        try:
            # Warm-up pass: let the background model converge over the whole clip
            # before it is used to produce the masks that are actually kept.
            while True:
                ok, frame = capture.read()
                if not ok:
                    break
                subtractor.apply(frame, learningRate=-1)

            capture.set(cv2.CAP_PROP_POS_FRAMES, 0)

            frame_index = 0
            foreground_ratios: list[float] = []
            width = height = 0
            while True:
                ok, frame = capture.read()
                if not ok:
                    break
                height, width = frame.shape[:2]

                raw_mask = subtractor.apply(frame, learningRate=0)
                # Shadows are labelled 127 by detectShadows=True; treat as background.
                _, binary_mask = cv2.threshold(raw_mask, 200, 255, cv2.THRESH_BINARY)
                cleaned = cv2.morphologyEx(binary_mask, cv2.MORPH_OPEN, kernel)
                cleaned = cv2.morphologyEx(cleaned, cv2.MORPH_CLOSE, kernel)

                contours, _ = cv2.findContours(cleaned, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
                if contours:
                    largest = max(contours, key=cv2.contourArea)
                    refined = np.zeros_like(cleaned)
                    cv2.drawContours(refined, [largest], -1, 255, thickness=cv2.FILLED)
                else:
                    refined = cleaned

                alpha = cv2.GaussianBlur(refined, (7, 7), 0)
                rgba = cv2.cvtColor(frame, cv2.COLOR_BGR2BGRA)
                rgba[:, :, 3] = alpha

                frame_path = output_dir / f"frame_{frame_index:05d}.png"
                cv2.imwrite(str(frame_path), rgba)
                foreground_ratios.append(float(np.mean(alpha) / 255.0))
                frame_index += 1
        finally:
            capture.release()

        if frame_index == 0:
            raise MediaOpsError(f"no_frames_decoded: {video_path}")

        return {
            "frames_dir": str(output_dir),
            "frame_count": frame_index,
            "fps": fps,
            "width": width,
            "height": height,
            "average_foreground_ratio": round(sum(foreground_ratios) / len(foreground_ratios), 4)
            if foreground_ratios
            else 0.0,
        }

    def composite_foreground_over_background(
        self,
        background_path: Path,
        foreground_frames_dir: Path,
        foreground_fps: float,
        output_path: Path,
        *,
        anchor: str = "bottom_right",
        scale: float = 0.45,
        x_offset: int = 0,
        y_offset: int = 0,
        fit_mode: str = "loop",
        placement: dict[str, float] | None = None,
    ) -> Path:
        self._ensure_binaries()
        if not background_path.exists():
            raise MediaOpsError(f"video_not_found: {background_path}")

        frame_files = sorted(foreground_frames_dir.glob("frame_*.png"))
        if not frame_files:
            raise MediaOpsError(f"no_matte_frames: {foreground_frames_dir}")

        output_path.parent.mkdir(parents=True, exist_ok=True)

        # Precise spatial placement takes priority over the anchor keyword.
        # placement is edge-anchored percentages (left/top/width/height) that
        # match LayoutResolver / frontend grid semantics: the foreground tile
        # is scaled to (width% x height%) of the canvas and placed at its
        # LEFT/TOP edge (left%, top%). This is what makes a框选 region land
        # exactly where the user dragged it on the canvas.
        if placement:
            left = float(placement.get("left", 0.0))
            top = float(placement.get("top", 0.0))
            width = max(0.0, min(100.0, float(placement.get("width", 100.0))))
            height = max(0.0, min(100.0, float(placement.get("height", 100.0))))
            # Scale the foreground to the requested tile size, then position it
            # at the LEFT/TOP edge (not center) of the canvas region.
            # format=rgba preserves the alpha channel through scale so overlay
            # correctly composites only the segmented subject (transparent
            # background lets the underlying tile show through).
            # Probe the background to get exact canvas pixel dimensions so
            # we can scale the foreground to the absolute pixel size the
            # user expects. Scaling relative to the foreground input (iw/ih)
            # leads to tiny overlays when the source image resolution is low.
            probe = self.probe_video(background_path)
            main_w = int(probe.get("width") or 0)
            main_h = int(probe.get("height") or 0)
            if main_w <= 0 or main_h <= 0:
                raise MediaOpsError(f"invalid_background_dimensions: {background_path}")

            target_w = max(1, int(round(main_w * (width / 100.0))))
            target_h = max(1, int(round(main_h * (height / 100.0))))
            fg_scale_expr = f"{target_w}:{target_h}"
            x_expr = f"{int(round(main_w * (left / 100.0)))}"
            y_expr = f"{int(round(main_h * (top / 100.0)))}"
            cmd = [str(self._ffmpeg), "-y", "-i", str(background_path)]
            if fit_mode.strip().lower() == "loop":
                cmd.extend(["-stream_loop", "-1"])
            cmd.extend(
                [
                    "-framerate",
                    f"{max(foreground_fps, 1.0):.3f}",
                    "-i",
                    str(foreground_frames_dir / "frame_%05d.png"),
                    "-filter_complex",
                    (
                        f"[1:v]scale='{fg_scale_expr}',format=rgba[fg];"
                        f"[0:v][fg]overlay=x='{x_expr}':y='{y_expr}':shortest=1[outv]"
                    ),
                    "-map",
                    "[outv]",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "veryfast",
                    "-crf",
                    "20",
                    "-pix_fmt",
                    "yuv420p",
                    "-an",
                    str(output_path),
                ]
            )
            self._run(cmd)
            return output_path

        anchor_expressions = {
            "top_left": ("0", "0"),
            "top_right": ("main_w-overlay_w", "0"),
            "bottom_left": ("0", "main_h-overlay_h"),
            "bottom_right": ("main_w-overlay_w", "main_h-overlay_h"),
            "center": ("(main_w-overlay_w)/2", "(main_h-overlay_h)/2"),
        }
        base_x, base_y = anchor_expressions.get(anchor.strip().lower(), anchor_expressions["bottom_right"])
        x_expr = f"({base_x})+({int(x_offset)})"
        y_expr = f"({base_y})+({int(y_offset)})"

        safe_scale = scale if scale > 0 else 0.45
        cmd = [str(self._ffmpeg), "-y", "-i", str(background_path)]
        if fit_mode.strip().lower() == "loop":
            cmd.extend(["-stream_loop", "-1"])
        cmd.extend(
            [
                "-framerate",
                f"{max(foreground_fps, 1.0):.3f}",
                "-i",
                str(foreground_frames_dir / "frame_%05d.png"),
                "-filter_complex",
                (
                    f"[1:v]scale=iw*{safe_scale}:ih*{safe_scale},format=rgba[fg];"
                    f"[0:v][fg]overlay=x='{x_expr}':y='{y_expr}':shortest=1[outv]"
                ),
                "-map",
                "[outv]",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "20",
                "-pix_fmt",
                "yuv420p",
                "-an",
                str(output_path),
            ]
        )
        self._run(cmd)
        return output_path

    def trim_video(self, video_path: Path, output_path: Path, start_ms: int, end_ms: int) -> Path:
        self._ensure_binaries()
        start_sec = max(start_ms / 1000.0, 0.0)
        duration_sec = max((end_ms - start_ms) / 1000.0, 0.1)

        output_path.parent.mkdir(parents=True, exist_ok=True)
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-ss",
            f"{start_sec:.3f}",
            "-i",
            str(video_path),
            "-t",
            f"{duration_sec:.3f}",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-an",
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def speed_video(self, video_path: Path, output_path: Path, speed: float) -> Path:
        self._ensure_binaries()
        if speed <= 0:
            raise MediaOpsError("invalid_speed")

        output_path.parent.mkdir(parents=True, exist_ok=True)
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-i",
            str(video_path),
            "-filter:v",
            f"setpts={1.0 / speed:.6f}*PTS",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-an",
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def color_enhance(self, video_path: Path, output_path: Path, profile: str) -> Path:
        self._ensure_binaries()
        profiles = {
            "vivid": "eq=saturation=1.18:contrast=1.07:brightness=0.02",
            "natural": "eq=saturation=1.06:contrast=1.03:brightness=0.01",
            "soft": "eq=saturation=0.96:contrast=0.97:brightness=0.03",
        }
        eq_filter = profiles.get(profile, profiles["vivid"])

        output_path.parent.mkdir(parents=True, exist_ok=True)
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-i",
            str(video_path),
            "-vf",
            eq_filter,
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-an",
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def stabilize_video(self, video_path: Path, output_path: Path) -> Path:
        self._ensure_binaries()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-i",
            str(video_path),
            "-vf",
            "deshake",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-an",
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def concat_videos(self, video_paths: list[Path], output_path: Path) -> Path:
        self._ensure_binaries()
        if not video_paths:
            raise MediaOpsError("no_clips_to_concat")

        output_path.parent.mkdir(parents=True, exist_ok=True)
        list_file = output_path.parent / f"{output_path.stem}_concat.txt"
        list_file.write_text("\n".join(f"file '{path.as_posix()}'" for path in video_paths), encoding="utf-8")

        cmd = [
            str(self._ffmpeg),
            "-y",
            "-f",
            "concat",
            "-safe",
            "0",
            "-i",
            str(list_file),
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-an",
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def image_to_static_video(
        self,
        image_path: Path,
        output_path: Path,
        *,
        duration_seconds: float = 3.0,
        fps: float = 15.0,
    ) -> Path:
        """Render a still JPEG into a short static-frame video.

        Live-photo compositions require a video timeline. A pure still asset
        (no motion clip) is materialized as a fixed-duration video so it can
        participate in concat/compose just like a live photo.
        """
        self._ensure_binaries()
        if not image_path.exists():
            raise MediaOpsError(f"image_not_found: {image_path}")
        output_path.parent.mkdir(parents=True, exist_ok=True)
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-loop",
            "1",
            "-i",
            str(image_path),
            "-t",
            f"{duration_seconds:.3f}",
            "-r",
            f"{fps:.3f}",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-an",
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def compose_videos_spatial(
        self,
        video_paths: list[Path],
        output_path: Path,
        *,
        canvas: str = "1080x1920",
        layout: str = "auto",
        placements: list[dict[str, float]] | None = None,
    ) -> Path:
        self._ensure_binaries()
        if not video_paths:
            raise MediaOpsError("no_clips_to_compose")
        normalized_canvas = str(canvas).strip().lower().replace("×", "x").replace("*", "x")
        if "x" not in normalized_canvas:
            raise MediaOpsError("invalid_canvas")

        width_raw, height_raw = (part.strip() for part in normalized_canvas.split("x", maxsplit=1))
        if not width_raw.isdigit() or not height_raw.isdigit():
            raise MediaOpsError("invalid_canvas")

        canvas_w = int(width_raw)
        canvas_h = int(height_raw)
        output_path.parent.mkdir(parents=True, exist_ok=True)

        if placements:
            return self._compose_videos_with_placements(video_paths, output_path, canvas_w, canvas_h, placements)

        # Single clip: normalize to canvas to keep downstream deterministic.
        if len(video_paths) == 1:
            cmd = [
                str(self._ffmpeg),
                "-y",
                "-i",
                str(video_paths[0]),
                "-vf",
                self._cover_filter(canvas_w, canvas_h),
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "20",
                "-pix_fmt",
                "yuv420p",
                "-an",
                str(output_path),
            ]
            self._run(cmd)
            return output_path

        normalized_layout = layout.strip().lower()
        if normalized_layout in {"timeline", "temporal"}:
            return self.concat_videos(video_paths, output_path)

        if normalized_layout in {"horizontal", "left_right", "lr", "row"}:
            mode = "horizontal"
        elif normalized_layout in {"vertical", "top_bottom", "tb", "column"}:
            mode = "vertical"
        elif normalized_layout in {"triptych_portrait", "portrait"}:
            mode = "triptych_portrait"
        elif normalized_layout in {"triptych_landscape", "landscape"}:
            mode = "triptych_landscape"
        else:
            mode = "triptych_portrait" if canvas_h >= canvas_w else "triptych_landscape"

        # For 2 clips, prefer strict split modes.
        if len(video_paths) == 2 and mode in {"triptych_portrait", "triptych_landscape"}:
            mode = "vertical" if canvas_h >= canvas_w else "horizontal"

        filter_parts: list[str] = []
        input_count = len(video_paths)

        if mode == "horizontal":
            tile_w = max(canvas_w // input_count, 2)
            tile_h = canvas_h
            for idx in range(input_count):
                filter_parts.append(f"[{idx}:v]{self._cover_filter(tile_w, tile_h)}[v{idx}]")
            xstack_layout = "|".join(f"{idx * tile_w}_0" for idx in range(input_count))
            filter_parts.append("".join(f"[v{idx}]" for idx in range(input_count)) + f"xstack=inputs={input_count}:layout={xstack_layout}[outv]")
        elif mode == "vertical":
            tile_w = canvas_w
            tile_h = max(canvas_h // input_count, 2)
            for idx in range(input_count):
                filter_parts.append(f"[{idx}:v]{self._cover_filter(tile_w, tile_h)}[v{idx}]")
            xstack_layout = "|".join(f"0_{idx * tile_h}" for idx in range(input_count))
            filter_parts.append("".join(f"[v{idx}]" for idx in range(input_count)) + f"xstack=inputs={input_count}:layout={xstack_layout}[outv]")
        elif mode == "triptych_landscape" and input_count >= 3:
            left_w = max(canvas_w // 2, 2)
            right_w = canvas_w - left_w
            right_h = max(canvas_h // 2, 2)

            filter_parts.extend(
                [
                    f"[0:v]{self._cover_filter(left_w, canvas_h)}[v0]",
                    f"[1:v]{self._cover_filter(right_w, right_h)}[v1]",
                    f"[2:v]{self._cover_filter(right_w, canvas_h - right_h)}[v2]",
                    f"color=c=black:s={canvas_w}x{canvas_h}:d=1[base]",
                    "[base][v0]overlay=0:0[tmp1]",
                    f"[tmp1][v1]overlay={left_w}:0[tmp2]",
                    f"[tmp2][v2]overlay={left_w}:{right_h}[outv]",
                ]
            )
        else:
            # Default triptych portrait: one top panel + two bottom panels.
            top_h = max(canvas_h // 2, 2)
            bottom_h = canvas_h - top_h
            bottom_w = max(canvas_w // 2, 2)

            filter_parts.extend(
                [
                    f"[0:v]{self._cover_filter(canvas_w, top_h)}[v0]",
                    f"[1:v]{self._cover_filter(bottom_w, bottom_h)}[v1]",
                    f"[2:v]{self._cover_filter(canvas_w - bottom_w, bottom_h)}[v2]",
                    f"color=c=black:s={canvas_w}x{canvas_h}:d=1[base]",
                    "[base][v0]overlay=0:0[tmp1]",
                    f"[tmp1][v1]overlay=0:{top_h}[tmp2]",
                    f"[tmp2][v2]overlay={bottom_w}:{top_h}[outv]",
                ]
            )

        cmd = [str(self._ffmpeg), "-y"]
        for path in video_paths[:3]:
            cmd.extend(["-i", str(path)])
        cmd.extend(
            [
                "-filter_complex",
                ";".join(filter_parts),
                "-map",
                "[outv]",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "20",
                "-pix_fmt",
                "yuv420p",
                "-an",
                "-shortest",
                str(output_path),
            ]
        )
        self._run(cmd)
        return output_path

    def _compose_videos_with_placements(
        self,
        video_paths: list[Path],
        output_path: Path,
        canvas_w: int,
        canvas_h: int,
        placements: list[dict[str, float]],
    ) -> Path:
        filter_parts: list[str] = []
        overlay_inputs: list[str] = []
        n = len(video_paths)
        base_duration = self._longest_input_duration(video_paths)
        for index, path in enumerate(video_paths):
            placement = placements[index] if index < len(placements) else {}
            # left/top are the LEFT / TOP EDGE of the tile (edge-anchored),
            # matching LayoutResolver's output and the frontend grid semantics.
            tile_w = max(round(canvas_w * float(placement.get("width", 100.0)) / 100), 2)
            tile_h = max(round(canvas_h * float(placement.get("height", 100.0)) / 100), 2)
            x = max(0, min(canvas_w - tile_w, round(canvas_w * float(placement.get("left", 0.0)) / 100)))
            y = max(0, min(canvas_h - tile_h, round(canvas_h * float(placement.get("top", 0.0)) / 100)))
            filter_parts.append(f"[{index}:v]{self._cover_filter(tile_w, tile_h)}[placed{index}]")
            overlay_inputs.append(f"[placed{index}]@{x},{y}")

        filter_parts.append(f"color=c=black:s={canvas_w}x{canvas_h}:r=30:d={base_duration}[base]")
        current = "base"
        for index, placed in enumerate(overlay_inputs):
            source, coordinates = placed.split("@", maxsplit=1)
            x, y = coordinates.split(",", maxsplit=1)
            output_label = "outv" if index == len(overlay_inputs) - 1 else f"layer{index}"
            filter_parts.append(f"[{current}]{source}overlay={x}:{y}:eof_action=pass:repeatlast=1[{output_label}]")
            current = output_label

        cmd = [str(self._ffmpeg), "-y"]
        for path in video_paths:
            cmd.extend(["-i", str(path)])
        cmd.extend([
            "-filter_complex", ";".join(filter_parts),
            "-map", "[outv]",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-pix_fmt", "yuv420p", "-an", "-shortest", str(output_path),
        ])
        self._run(cmd)
        return output_path

    def _cover_filter(self, target_w: int, target_h: int) -> str:
        # setpts=PTS-STARTPTS: reset timestamps to 0 so clips with non-zero start PTS
        # (e.g. HEVC clips) align correctly with the color base in overlay filter.
        # format=yuv420p: normalize 10-bit clips to 8-bit for overlay compatibility.
        # setsar=1: ensure square pixels so overlay uses correct display coordinates.
        return (
            f"setpts=PTS-STARTPTS,"
            f"scale={target_w}:{target_h}:force_original_aspect_ratio=increase,"
            f"crop={target_w}:{target_h},"
            f"format=yuv420p,setsar=1"
        )
    def apply_ffmpeg_filter(self, input_path: Path, output_path: Path, filter_graph: str) -> Path:
        """Apply an ffmpeg filter graph to a single input file and write output.

        This is a thin wrapper around `ffmpeg -i input -vf <filter_graph> output`.
        """
        self._ensure_binaries()
        if not input_path.exists():
            raise MediaOpsError(f"input_not_found: {input_path}")
        output_path.parent.mkdir(parents=True, exist_ok=True)
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-i",
            str(input_path),
            "-vf",
            filter_graph,
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def apply_cv_mask_smooth(self, mask_path: Path, output_mask_path: Path, blur_radius: int = 5) -> Path:
        """Smooth/feather a binary mask using OpenCV Gaussian blur + threshold.

        Reads a grayscale mask, blurs it, thresholds and writes the result.
        """
        try:
            import cv2
            import numpy as np
        except ImportError as exc:
            raise MediaOpsError("opencv_missing") from exc

        if not mask_path.exists():
            raise MediaOpsError(f"mask_not_found: {mask_path}")

        m = cv2.imread(str(mask_path), cv2.IMREAD_GRAYSCALE)
        if m is None:
            raise MediaOpsError(f"mask_read_failed: {mask_path}")

        # Ensure odd kernel size for Gaussian
        k = max(1, int(blur_radius) // 2 * 2 + 1)
        blurred = cv2.GaussianBlur(m, (k, k), 0)
        _, th = cv2.threshold(blurred, 128, 255, cv2.THRESH_BINARY)
        output_mask_path.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(output_mask_path), th)
        return output_mask_path

    def apply_cv_frames_denoise(self, frames_dir: Path, method: str = "nlmeans") -> Path:
        """Apply a lightweight denoise/blend to all PNG frames in `frames_dir`.

        Preserves alpha channel when present.
        """
        try:
            import cv2
            import numpy as np
            from pathlib import Path as _P
        except ImportError as exc:
            raise MediaOpsError("opencv_missing") from exc

        if not frames_dir.exists():
            raise MediaOpsError(f"frames_dir_missing: {frames_dir}")

        pngs = sorted(frames_dir.glob("frame_*.png"))
        if not pngs:
            raise MediaOpsError(f"no_frames_in_dir: {frames_dir}")

        for p in pngs:
            img = cv2.imread(str(p), cv2.IMREAD_UNCHANGED)
            if img is None:
                continue
            if img.shape[2] == 4:
                bgr = img[:, :, :3]
                a = img[:, :, 3]
            else:
                bgr = img
                a = None

            if method == "nlmeans":
                # fastNlMeansDenoisingColored expects 8-bit
                denoised = cv2.fastNlMeansDenoisingColored(bgr, None, 3, 3, 7, 21)
            else:
                denoised = cv2.GaussianBlur(bgr, (3, 3), 0)

            if a is not None:
                out = cv2.cvtColor(denoised, cv2.COLOR_BGR2BGRA)
                out[:, :, 3] = a
            else:
                out = denoised

            cv2.imwrite(str(p), out)

        return frames_dir

    def optimize_subject_matte(self, frames_dir: Path, mask_path: Path, optimize_cfg: dict | None) -> None:
        """Apply optional optimize steps to a generated subject matte (mask + frames).

        optimize_cfg may specify keys like `mask: {gauss: 5}` and
        `foreground: {denoise: true}`. This method applies sensible defaults
        when keys are present.
        """
        if not optimize_cfg:
            return
        # Mask smoothing
        mask_cfg = optimize_cfg.get("mask") if isinstance(optimize_cfg, dict) else None
        if mask_cfg:
            radius = int(mask_cfg.get("gauss", 5))
            tmp = mask_path.parent / (mask_path.stem + "_smoothed" + mask_path.suffix)
            try:
                self.apply_cv_mask_smooth(mask_path, tmp, blur_radius=radius)
                tmp.replace(mask_path)
            except MediaOpsError:
                pass

        # Foreground denoise
        fg_cfg = optimize_cfg.get("foreground") if isinstance(optimize_cfg, dict) else None
        if fg_cfg:
            method = "nlmeans" if fg_cfg.get("denoise", False) else fg_cfg.get("method", "nlmeans")
            try:
                self.apply_cv_frames_denoise(frames_dir, method=method)
            except MediaOpsError:
                pass
        

    def add_text_overlay(self, video_path: Path, output_path: Path, text: str, style: str) -> Path:
        self._ensure_binaries()
        style_map = {
            "minimal": "fontsize=40:fontcolor=white:borderw=2:bordercolor=black",
            "bold": "fontsize=52:fontcolor=white:borderw=3:bordercolor=black",
        }
        drawtext_style = style_map.get(style, style_map["minimal"])
        safe_text = text.replace("'", "\\'").replace(":", "\\:")

        output_path.parent.mkdir(parents=True, exist_ok=True)
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-i",
            str(video_path),
            "-vf",
            f"drawtext=text='{safe_text}':x=(w-text_w)/2:y=h-(text_h*2):{drawtext_style}",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-an",
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def export_mp4(self, video_path: Path, output_path: Path, resolution: str) -> Path:
        self._ensure_binaries()
        if "x" not in resolution:
            raise MediaOpsError("invalid_resolution")

        width, height = resolution.split("x", maxsplit=1)
        if not width.isdigit() or not height.isdigit():
            raise MediaOpsError("invalid_resolution")

        output_path.parent.mkdir(parents=True, exist_ok=True)
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-i",
            str(video_path),
            "-vf",
            f"scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "20",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            "-an",
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def extract_cover_jpeg(self, video_path: Path, output_path: Path, timestamp_ms: int | None = None) -> Path:
        self._ensure_binaries()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        timestamp_sec = max((timestamp_ms or 0) / 1000.0, 0.0)
        cmd = [
            str(self._ffmpeg),
            "-y",
            "-ss",
            f"{timestamp_sec:.3f}",
            "-i",
            str(video_path),
            "-frames:v",
            "1",
            "-q:v",
            "2",
            str(output_path),
        ]
        self._run(cmd)
        return output_path

    def pack_motion_photo_jpg(
        self,
        image_path: Path,
        video_path: Path,
        output_path: Path,
        presentation_timestamp_ms: int | None = None,
    ) -> Path:
        if not image_path.exists():
            raise MediaOpsError(f"image_not_found: {image_path}")
        if not video_path.exists():
            raise MediaOpsError(f"video_not_found: {video_path}")

        image_data = image_path.read_bytes()
        if not image_data.startswith(b"\xff\xd8"):
            raise MediaOpsError("invalid_jpeg_for_live_photo")

        video_data = video_path.read_bytes()
        video_data = self._modify_vivo_gallery_identifier(video_data)
        presentation_us = max(int((presentation_timestamp_ms or 0) * 1000), 0)
        patched_image = image_data

        try:
            import pyexiv2

            xmp_payload = self._build_motion_xmp(video_size=len(video_data), presentation_timestamp_us=presentation_us)
            with pyexiv2.ImageData(image_data) as image:
                image.modify_raw_xmp(xmp_payload)
                patched_image = image.get_bytes()
        except Exception as exc:  # noqa: BLE001
            raise MediaOpsError(
                "pyexiv2_missing_or_xmp_write_failed: live photo packaging requires pyexiv2 and writable XMP"
            ) from exc

        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(patched_image + video_data)
        return output_path

    def normalize_motion_photo_video(self, input_video: Path, output_video: Path) -> Path:
        """Normalize video to phone-friendly motion-photo stream layout with AAC audio."""
        self._ensure_binaries()
        output_video.parent.mkdir(parents=True, exist_ok=True)
        has_audio = self._video_has_audio(input_video)

        cmd = [
            str(self._ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(input_video),
        ]

        if has_audio:
            cmd.extend(
                [
                    "-map",
                    "0:v:0",
                    "-map",
                    "0:a:0",
                    "-c:v",
                    "copy",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "96k",
                ]
            )
        else:
            cmd.extend(
                [
                    "-f",
                    "lavfi",
                    "-i",
                    "anullsrc=channel_layout=stereo:sample_rate=48000",
                    "-map",
                    "0:v:0",
                    "-map",
                    "1:a:0",
                    "-c:v",
                    "copy",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "96k",
                    "-shortest",
                ]
            )

        cmd.extend(
            [
                "-movflags",
                "+faststart",
                "-brand",
                "mp42",
                "-metadata",
                "com.android.version=16",
                str(output_video),
            ]
        )
        self._run(cmd)
        return output_video

    def validate_motion_photo_jpg(self, motion_photo_path: Path) -> dict[str, object]:
        """Validate compatibility-critical conditions for phone live-photo recognition."""
        checks: dict[str, bool] = {
            "jpeg_header": False,
            "xmp_motionphoto": False,
            "embedded_mp4": False,
            "embedded_video_stream": False,
            "embedded_audio_stream": False,
            "major_brand_mp42": False,
            "android_version_metadata": False,
        }

        if not motion_photo_path.exists() or not motion_photo_path.is_file():
            return {"valid": False, "checks": checks, "missing": ["file_exists"]}

        data = motion_photo_path.read_bytes()
        checks["jpeg_header"] = data.startswith(b"\xff\xd8")
        checks["xmp_motionphoto"] = (b"GCamera:MotionPhoto=\"1\"" in data) or (b"VCamera:VMotionPhotoVersion" in data)

        mp4_bytes = self._extract_embedded_mp4_bytes(data)
        checks["embedded_mp4"] = mp4_bytes is not None
        if mp4_bytes is None:
            return {
                "valid": False,
                "checks": checks,
                "missing": [key for key, ok in checks.items() if not ok],
            }

        with tempfile.NamedTemporaryFile(suffix=".mp4", delete=True) as tmp:
            tmp.write(mp4_bytes)
            tmp.flush()
            probe = self._probe_streams_and_format(Path(tmp.name))

        stream_types = [str(item.get("codec_type", "")) for item in probe.get("streams", []) if isinstance(item, dict)]
        checks["embedded_video_stream"] = "video" in stream_types
        checks["embedded_audio_stream"] = "audio" in stream_types

        tags = probe.get("format_tags", {})
        if not isinstance(tags, dict):
            tags = {}
        major_brand = str(tags.get("major_brand", "")).lower()
        compatible_brands = str(tags.get("compatible_brands", "")).lower()
        checks["major_brand_mp42"] = ("mp42" in major_brand) or ("mp42" in compatible_brands)
        checks["android_version_metadata"] = str(tags.get("com.android.version", "")) == "16"

        missing = [key for key, ok in checks.items() if not ok]
        return {
            "valid": len(missing) == 0,
            "checks": checks,
            "missing": missing,
        }

    def _ensure_binaries(self) -> None:
        if not self._ffmpeg or not self._ffprobe:
            raise MediaOpsError("ffmpeg_or_ffprobe_missing")

    def _run(self, cmd: list[str]) -> subprocess.CompletedProcess[str]:
        try:
            return subprocess.run(
                cmd,
                check=True,
                text=True,
                capture_output=True,
                timeout=self.command_timeout_seconds,
            )
        except subprocess.TimeoutExpired as exc:
            raise MediaOpsError(
                f"command_timed_out({self.command_timeout_seconds}s): {' '.join(cmd)}"
            ) from exc
        except subprocess.CalledProcessError as exc:
            stderr = (exc.stderr or "").strip()
            raise MediaOpsError(f"command_failed: {' '.join(cmd)} :: {stderr}") from exc

    def _parse_fps(self, text: str) -> float:
        if "/" in text:
            left, right = text.split("/", maxsplit=1)
            numerator = float(left or 0)
            denominator = float(right or 1)
            if math.isclose(denominator, 0.0):
                return 0.0
            return numerator / denominator
        try:
            return float(text)
        except ValueError:
            return 0.0

    def _prepare_match_frame(self, frame, compare_size: int) -> object:
        import cv2

        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        return cv2.resize(gray, (compare_size, compare_size), interpolation=cv2.INTER_AREA)

    def _frame_match_distance(self, frame, cover_gray, compare_size: int, np_module) -> float:
        prepared = self._prepare_match_frame(frame, compare_size=compare_size)
        diff = np_module.abs(prepared.astype("float32") - cover_gray.astype("float32"))
        return float(diff.mean())

    def _build_motion_xmp(self, video_size: int, presentation_timestamp_us: int) -> str:
        return (
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">"
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            "<rdf:Description rdf:about=\"\" "
            "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" "
            "xmlns:VCamera=\"http://ns.vivo.com/photos/1.0/camera/\" "
            "xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" "
            "xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" "
            "GCamera:MotionPhoto=\"1\" "
            "GCamera:MotionPhotoVersion=\"1\" "
            f"GCamera:MotionPhotoPresentationTimestampUs=\"{presentation_timestamp_us}\" "
            "VCamera:VMotionPhotoVersion=\"1\" "
            "VCamera:VMotionPhotoSource=\"1\" "
            "VCamera:VMediaKitVersion=\"1.0.0.5\">"
            "<Container:Directory><rdf:Seq>"
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>"
            f"<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"{video_size}\" Item:Padding=\"0\"/></rdf:li>"
            "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"
        )

    def _extract_embedded_mp4_bytes(self, data: bytes) -> bytes | None:
        if len(data) < 16:
            return None
        eoi_index = data.rfind(JPEG_EOI)
        if eoi_index == -1:
            return None
        jpeg_end = eoi_index + len(JPEG_EOI)
        ftyp_offset = data.find(FTYP_MAGIC, jpeg_end)
        if ftyp_offset == -1:
            return None
        candidate_start = max(ftyp_offset - 4, 0)
        mp4_start = ftyp_offset
        if candidate_start + 8 <= len(data) and data[candidate_start + 4 : candidate_start + 8] == FTYP_MAGIC:
            box_size = int.from_bytes(data[candidate_start : candidate_start + 4], byteorder="big", signed=False)
            if box_size >= 8:
                mp4_start = candidate_start
        if mp4_start < jpeg_end:
            return None
        return data[mp4_start:]

    def _probe_streams_and_format(self, video_path: Path) -> dict[str, object]:
        cmd = [
            str(self._ffprobe),
            "-v",
            "error",
            "-show_streams",
            "-show_format",
            "-of",
            "json",
            str(video_path),
        ]
        result = self._run(cmd)
        try:
            payload = json.loads(result.stdout)
        except json.JSONDecodeError:
            return {"streams": [], "format_tags": {}}

        streams = payload.get("streams", [])
        if not isinstance(streams, list):
            streams = []
        format_info = payload.get("format", {})
        if not isinstance(format_info, dict):
            format_info = {}
        tags = format_info.get("tags", {})
        if not isinstance(tags, dict):
            tags = {}
        return {"streams": streams, "format_tags": tags}

    def _longest_input_duration(self, video_paths: list[Path]) -> float:
        """Return the longest input clip duration (seconds), with a 1s floor.

        Used to size the color base source so ffmpeg terminates instead of
        encoding an unbounded (e.g. 24h) color source forever. The floor is kept
        small (1s) so short live-photo clips are not padded with a long black
        curtain; the base always matches the real content length.
        """
        longest = 1.0
        for path in video_paths:
            try:
                info = self._probe_streams_and_format(path)
            except Exception:  # noqa: BLE001
                continue
            fmt = info.get("format", {})
            duration = float(fmt.get("duration") or 0.0)
            if duration <= 0.0:
                for stream in info.get("streams", []):
                    if stream.get("codec_type") == "video":
                        duration = float(stream.get("duration") or 0.0)
                        break
            if duration > longest:
                longest = duration
        return round(longest, 3)

    def _video_has_audio(self, video_path: Path) -> bool:
        cmd = [
            str(self._ffprobe),
            "-v",
            "error",
            "-select_streams",
            "a",
            "-show_entries",
            "stream=index",
            "-of",
            "json",
            str(video_path),
        ]
        result = self._run(cmd)
        try:
            parsed = json.loads(result.stdout)
        except json.JSONDecodeError:
            return False
        streams = parsed.get("streams", [])
        return isinstance(streams, list) and len(streams) > 0

    def _modify_vivo_gallery_identifier(self, video_data: bytes) -> bytes:
        """Patch vivo-specific identifier when present, otherwise append compatibility block."""
        new_val = b"motionphoto00010000000000000"
        key_prefix = b'"com.android.camera.livephoto":"'
        payload = bytearray(video_data)

        start_index = payload.find(key_prefix)
        if start_index != -1:
            value_start_pos = start_index + len(key_prefix)
            end_pos = value_start_pos + len(new_val)
            if end_pos <= len(payload):
                payload[value_start_pos:end_pos] = new_val
                return bytes(payload)

        vivo_media_ext_hex = (
            "00 00 00 A8 75 75 69 64 76 69 76 6F 4D 65 64 69 61 45 78 74 49 6E 66 6F 76 69 76 6F "
            "7B 22 63 6F 6D 2E 61 6E 64 72 6F 69 64 2E 63 61 6D 65 72 61 2E 6C 69 76 65 70 68 6F "
            "74 6F 22 3A 22 6D 6F 74 69 6F 6E 70 68 6F 74 6F 30 30 30 31 30 30 30 30 30 30 30 30 "
            "30 30 30 30 30 22 2C 22 76 65 72 73 69 6F 6E 22 3A 32 31 30 38 7D 00 00 00 4E 63 61 "
            "6D 65 72 61 6C 62 75 6D 21 00 00 00 2F 6D 6F 74 69 6F 6E 70 68 6F 74 6F 30 30 30 31 "
            "30 30 30 30 30 30 30 30 30 30 30 30 30 FF FF FF FF 1B 2A 39 48 57 66 75 84 93 A2 B3"
        )
        return bytes(payload) + bytes.fromhex(vivo_media_ext_hex)
