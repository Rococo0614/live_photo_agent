#!/usr/bin/env python3
"""Track every frame with the PyTorch EdgeTAM oracle and render a temporary tint video.

The tool is read-only with respect to the source. It exists to close the full-frame temporal and
rendering contract before the same tensors are wired through Android QNN. Encoded video is for
review; preservation metrics are computed on in-memory RGB frames before encoding.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import psutil
import torch
from PIL import Image
from qai_hub_models.models.edgetam.app import EdgeTAMVideoApp
from qai_hub_models.models.edgetam.model import EdgeTAM


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument(
        "--point-coordinates-normalized",
        required=True,
        help="Semicolon-separated normalized x,y positive points.",
    )
    parser.add_argument("--color", default="#00ff66")
    parser.add_argument("--intensity", type=float, default=0.65)
    parser.add_argument(
        "--target-y-range",
        help="Optional normalized y0,y1 protection range for this fixture.",
    )
    parser.add_argument("--maximum-outside-target-fraction", type=float, default=0.001)
    parser.add_argument("--minimum-temporal-area-ratio", type=float, default=0.10)
    parser.add_argument("--sampled-device-mask-root", type=Path)
    parser.add_argument("--minimum-sampled-device-iou", type=float, default=0.85)
    parser.add_argument(
        "--photo-timestamp-us",
        type=int,
        help="Optional Live Photo presentation timestamp used to emit a matching tinted JPEG cover.",
    )
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    if not args.video.is_file():
        parser.error("video does not exist")
    if not 0.0 <= args.intensity <= 1.0:
        parser.error("intensity must be in [0, 1]")
    if not __import__("re").fullmatch(r"#[0-9a-fA-F]{6}", args.color):
        parser.error("color must be #RRGGBB")
    if args.sampled_device_mask_root and not args.sampled_device_mask_root.is_dir():
        parser.error("sampled device mask root is not a directory")
    if args.photo_timestamp_us is not None and args.photo_timestamp_us < 0:
        parser.error("photo timestamp cannot be negative")
    return args


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_video(path: Path) -> tuple[list[np.ndarray], float]:
    capture = cv2.VideoCapture(str(path))
    fps = float(capture.get(cv2.CAP_PROP_FPS))
    frames: list[np.ndarray] = []
    while capture.isOpened():
        ok, bgr = capture.read()
        if not ok:
            break
        frames.append(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
    capture.release()
    if not frames or fps <= 0:
        raise RuntimeError("video decode returned no frames or invalid fps")
    return frames, fps


def parse_points(value: str, width: int, height: int) -> torch.Tensor:
    points = []
    for row in value.split(";"):
        pieces = row.split(",")
        if len(pieces) != 2:
            raise RuntimeError(f"invalid point: {row}")
        x, y = (float(piece) for piece in pieces)
        if not 0.0 <= x <= 1.0 or not 0.0 <= y <= 1.0:
            raise RuntimeError("normalized points must be in [0, 1]")
        points.append([x * width, y * height])
    if not points:
        raise RuntimeError("at least one positive point is required")
    return torch.tensor(points, dtype=torch.float32)


def tint(source: np.ndarray, mask: np.ndarray, color: np.ndarray, intensity: float) -> np.ndarray:
    alpha = mask.astype(np.float64)[..., None] * intensity
    output = np.floor(
        source.astype(np.float64) * (1.0 - alpha) + color.reshape(1, 1, 3) * alpha + 0.5
    )
    return np.clip(output, 0, 255).astype(np.uint8)


def encode_review_video(
    frames: list[np.ndarray], fps: float, source: Path, output: Path, ffmpeg: str
) -> None:
    executable = shutil.which(ffmpeg)
    if executable is None:
        raise RuntimeError("ffmpeg is required for the temporary review artifact")
    height, width = frames[0].shape[:2]
    command = [
        executable,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-thread_queue_size",
        "512",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgb24",
        "-s",
        f"{width}x{height}",
        "-framerate",
        f"{fps:.12f}",
        "-i",
        "pipe:0",
        "-i",
        str(source),
        "-map",
        "0:v:0",
        "-map",
        "1:a?",
        "-c:v",
        "libx264",
        "-crf",
        "18",
        "-preset",
        "fast",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "copy",
        str(output),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    assert process.stdin is not None
    try:
        for frame in frames:
            process.stdin.write(np.ascontiguousarray(frame).tobytes())
    finally:
        process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("ffmpeg failed to encode tracked tint preview")


def sampled_device_masks(root: Path | None) -> dict[int, Path]:
    if root is None:
        return {}
    result: dict[int, Path] = {}
    for directory in root.glob("frame-*-*"):
        try:
            frame_index = int(directory.name.rsplit("-", 1)[1])
        except ValueError:
            continue
        mask = directory / "device-mask.png"
        if mask.is_file():
            result[frame_index] = mask
    return result


def main() -> None:
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    mask_dir = args.output_dir / "masks"
    sample_dir = args.output_dir / "tinted-samples"
    mask_dir.mkdir(exist_ok=True)
    sample_dir.mkdir(exist_ok=True)
    started = time.monotonic()
    frames, fps = decode_video(args.video)
    height, width = frames[0].shape[:2]
    if any(frame.shape != frames[0].shape for frame in frames):
        raise RuntimeError("video dimensions changed mid-stream")
    point_coords = parse_points(args.point_coordinates_normalized, width, height)
    point_labels = torch.ones(len(point_coords), dtype=torch.float32)

    model = EdgeTAM.from_pretrained()
    app = EdgeTAMVideoApp(
        encoder=model.encoder,
        video_decoder=model.video_decoder,
        memory_encoder=model.memory_encoder,
        sam2=model.sam2,
        maskmem_pos_enc=model.memory_encoder.maskmem_pos_enc,
        encoder_input_img_size=model.sam2.image_size,
    )
    with torch.inference_mode():
        masks = app.track(frames, point_coords, point_labels, raw_output=True)
    if len(masks) != len(frames):
        raise RuntimeError("tracker did not return one mask per decoded frame")

    color = np.array(
        [int(args.color[1:3], 16), int(args.color[3:5], 16), int(args.color[5:7], 16)],
        dtype=np.float64,
    )
    target_range = None
    if args.target_y_range:
        y0, y1 = (float(value) for value in args.target_y_range.split(","))
        if not 0.0 <= y0 < y1 <= 1.0:
            raise RuntimeError("target y range must satisfy 0 <= y0 < y1 <= 1")
        target_range = (round(y0 * height), round(y1 * height))
    device_masks = sampled_device_masks(args.sampled_device_mask_root)
    frame_evidence: list[dict[str, Any]] = []
    tinted_frames: list[np.ndarray] = []
    previous_visible_pixels: int | None = None
    sampled_ious: list[float] = []
    maximum_outside_fraction = 0.0
    total_outside_changed = 0
    for index, (source, raw_mask) in enumerate(zip(frames, masks, strict=True)):
        mask = np.asarray(raw_mask, dtype=np.uint8) > 0
        pixels = int(mask.sum())
        area_ratio = pixels / previous_visible_pixels if previous_visible_pixels else None
        status = "VISIBLE_VALIDATED"
        if pixels == 0 or (area_ratio is not None and area_ratio < args.minimum_temporal_area_ratio):
            status = "UNCERTAIN_TEMPORAL_COVERAGE"
        else:
            previous_visible_pixels = pixels
        outside_fraction = 0.0
        if target_range and pixels:
            y0, y1 = target_range
            outside_pixels = int(mask[:y0].sum() + mask[y1:].sum())
            outside_fraction = outside_pixels / pixels
            maximum_outside_fraction = max(maximum_outside_fraction, outside_fraction)
            if outside_fraction > args.maximum_outside_target_fraction:
                status = "REJECTED_PROTECTED_OVERLAP"

        output = tint(source, mask, color, args.intensity)
        changed = np.any(output != source, axis=2)
        outside_changed = int(changed[~mask].sum())
        total_outside_changed += outside_changed
        if outside_changed:
            raise RuntimeError(f"mask exterior changed at frame {index}")
        tinted_frames.append(output)
        mask_path = mask_dir / f"mask-{index:04d}.png"
        Image.fromarray(mask.astype(np.uint8) * 255, "L").save(mask_path)

        sampled_iou = None
        device_path = device_masks.get(index)
        if device_path:
            device_mask = np.asarray(Image.open(device_path).convert("L")) > 0
            intersection = int(np.logical_and(mask, device_mask).sum())
            union = int(np.logical_or(mask, device_mask).sum())
            sampled_iou = intersection / union if union else 1.0
            sampled_ious.append(sampled_iou)
        if index in device_masks or index in {0, len(frames) // 2, len(frames) - 1}:
            Image.fromarray(output, "RGB").save(sample_dir / f"tinted-{index:04d}.png")
        frame_evidence.append(
            {
                "frame_index": index,
                "timestamp_us": round(index / fps * 1_000_000),
                "observation_status": status,
                "mask_pixels": pixels,
                "temporal_area_ratio": area_ratio,
                "outside_target_fraction": outside_fraction,
                "outside_mask_changed_pixels": outside_changed,
                "sampled_device_iou": sampled_iou,
                "mask_sha256": sha256(mask_path),
            }
        )

    visible_complete = all(
        row["observation_status"] == "VISIBLE_VALIDATED" for row in frame_evidence
    )
    sampled_iou_pass = not sampled_ious or min(sampled_ious) >= args.minimum_sampled_device_iou
    output_video = args.output_dir / "full-frame-tracked-tint-preview.mp4"
    encode_review_video(tinted_frames, fps, args.video, output_video, args.ffmpeg)
    cover_artifact = None
    if args.photo_timestamp_us is not None:
        cover_frame_index = round(args.photo_timestamp_us / 1_000_000 * fps)
        cover_frame_index = min(max(cover_frame_index, 0), len(tinted_frames) - 1)
        cover = args.output_dir / "full-frame-tracked-tint-cover.jpg"
        Image.fromarray(tinted_frames[cover_frame_index], "RGB").save(cover, quality=95)
        cover_artifact = {
            "filename": cover.name,
            "size_bytes": cover.stat().st_size,
            "sha256": sha256(cover),
            "photo_timestamp_us": args.photo_timestamp_us,
            "source_frame_index": cover_frame_index,
        }
    elapsed_ms = round((time.monotonic() - started) * 1000)
    evidence = {
        "schema_version": 1,
        "status": (
            "PASS_READ_ONLY_FULL_FRAME_TRACKED_TINT"
            if visible_complete and sampled_iou_pass and total_outside_changed == 0
            else "FAIL"
        ),
        "source": {
            "path": str(args.video),
            "sha256": sha256(args.video),
            "width": width,
            "height": height,
            "fps": fps,
            "frame_count": len(frames),
        },
        "tracker": {
            "model": "EdgeTAM PyTorch oracle",
            "point_coordinates_normalized": args.point_coordinates_normalized,
            "minimum_temporal_area_ratio": args.minimum_temporal_area_ratio,
        },
        "effect": {"kind": "color_tint", "color_hex": args.color.upper(), "intensity": args.intensity},
        "validation": {
            "all_frames_visible_validated": visible_complete,
            "total_outside_mask_changed_pixels": total_outside_changed,
            "maximum_outside_target_fraction": maximum_outside_fraction,
            "sampled_device_comparison_count": len(sampled_ious),
            "minimum_sampled_device_iou": min(sampled_ious) if sampled_ious else None,
            "sampled_device_iou_gate": args.minimum_sampled_device_iou,
        },
        "performance": {
            "wall_elapsed_ms_including_decode_track_render_encode": elapsed_ms,
            "host_peak_rss_bytes_after_run": psutil.Process().memory_info().rss,
        },
        "artifacts": {
            "preview_video": {
                "filename": output_video.name,
                "size_bytes": output_video.stat().st_size,
                "sha256": sha256(output_video),
                "validation_role": "temporary human review; lossless preservation measured before encoding",
            },
            "cover": cover_artifact,
            "mask_directory": mask_dir.name,
            "sample_directory": sample_dir.name,
        },
        "frames": frame_evidence,
        "boundaries": {
            "read_only": True,
            "source_media_modified": False,
            "live_photo_repacked": False,
            "published": False,
            "production_backend": False,
        },
    }
    evidence_path = args.output_dir / "full-frame-tracked-tint-evidence.json"
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(evidence_path)
    if evidence["status"] == "FAIL":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
