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

    def segment_subject(self, image_path: Path, output_mask_path: Path, mode: str = "person_first") -> dict[str, object]:
        _ = mode
        try:
            import cv2
            import numpy as np
        except ImportError as exc:
            raise MediaOpsError("opencv_missing") from exc

        image = cv2.imread(str(image_path))
        if image is None:
            raise MediaOpsError(f"image_decode_failed: {image_path}")

        mask = np.zeros(image.shape[:2], np.uint8)
        bg_model = np.zeros((1, 65), np.float64)
        fg_model = np.zeros((1, 65), np.float64)

        height, width = image.shape[:2]
        rect = (
            max(1, width // 10),
            max(1, height // 10),
            max(2, width * 8 // 10),
            max(2, height * 8 // 10),
        )
        cv2.grabCut(image, mask, rect, bg_model, fg_model, 4, cv2.GC_INIT_WITH_RECT)
        binary = ((mask == 1) | (mask == 3)).astype("uint8") * 255

        output_mask_path.parent.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(output_mask_path), binary)

        foreground_ratio = float(binary.mean() / 255.0)
        return {
            "mask_path": str(output_mask_path),
            "foreground_ratio": foreground_ratio,
        }

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

    def compose_videos_spatial(
        self,
        video_paths: list[Path],
        output_path: Path,
        *,
        canvas: str = "1080x1920",
        layout: str = "auto",
    ) -> Path:
        self._ensure_binaries()
        if not video_paths:
            raise MediaOpsError("no_clips_to_compose")
        if "x" not in canvas:
            raise MediaOpsError("invalid_canvas")

        width_raw, height_raw = canvas.split("x", maxsplit=1)
        if not width_raw.isdigit() or not height_raw.isdigit():
            raise MediaOpsError("invalid_canvas")

        canvas_w = int(width_raw)
        canvas_h = int(height_raw)
        output_path.parent.mkdir(parents=True, exist_ok=True)

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

    def _cover_filter(self, target_w: int, target_h: int) -> str:
        # setpts=PTS-STARTPTS: reset timestamps to 0 so clips with non-zero start PTS
        # (e.g. HEVC clips) align correctly with the color base in overlay filter.
        # format=yuv420p: normalize 10-bit clips to 8-bit for overlay compatibility.
        # setsar=1: ensure square pixels so overlay uses correct display coordinates.
        return (
            f"setpts=PTS-STARTPTS,"
            f"scale={target_w}:{target_h}:force_original_aspect_ratio=increase,"
            f"crop={target_w}:{target_h},"
            f"format=yuv420p,"
            f"setsar=1"
        )

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
            return subprocess.run(cmd, check=True, text=True, capture_output=True)
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
