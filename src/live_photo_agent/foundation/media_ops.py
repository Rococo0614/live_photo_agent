from __future__ import annotations

import json
import math
import shutil
import subprocess
from pathlib import Path


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
