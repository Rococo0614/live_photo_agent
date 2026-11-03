#!/usr/bin/env python3
"""Render a lossless tracked-tint proof from validated source frames and masks.

This is a host validation tool, not an Android media backend. Pixel preservation is measured on
lossless PNGs. An optional ffmpeg call only packages side-by-side proof frames for human review.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frame-root", required=True, type=Path)
    parser.add_argument("--mask-root", required=True, type=Path)
    parser.add_argument("--tracking-evidence", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--color", default="#00ff66")
    parser.add_argument("--intensity", type=float, default=0.65)
    parser.add_argument("--source-fps", type=float, default=30.0)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    if not args.frame_root.is_dir() or not args.mask_root.is_dir():
        parser.error("frame and mask roots must be directories")
    if not args.tracking_evidence.is_file():
        parser.error("tracking evidence does not exist")
    if not 0.0 <= args.intensity <= 1.0:
        parser.error("--intensity must be in [0, 1]")
    if args.source_fps <= 0:
        parser.error("--source-fps must be positive")
    if not __import__("re").fullmatch(r"#[0-9a-fA-F]{6}", args.color):
        parser.error("--color must be #RRGGBB")
    return args


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tint(source: np.ndarray, mask: np.ndarray, color: np.ndarray, intensity: float) -> np.ndarray:
    alpha = mask.astype(np.float64)[..., None] / 255.0 * intensity
    mixed = np.floor(
        source.astype(np.float64) * (1.0 - alpha) + color.reshape(1, 1, 3) * alpha + 0.5
    )
    return np.clip(mixed, 0, 255).astype(np.uint8)


def proof_frame(source: Image.Image, tinted: Image.Image, mask: Image.Image, label: str) -> Image.Image:
    width, height = source.size
    proof = Image.new("RGB", (width * 2, height), "black")
    proof.paste(source, (0, 0))
    proof.paste(tinted, (width, 0))
    draw = ImageDraw.Draw(proof)
    draw.rectangle((0, 0, width * 2, 34), fill=(0, 0, 0))
    draw.text((12, 10), f"SOURCE | {label}", fill=(255, 255, 255))
    draw.text((width + 12, 10), "TRACKED TINT | mask exterior preserved", fill=(255, 255, 255))
    # A thin cyan contour makes it possible to compare the visible tint with the original mask.
    binary = np.asarray(mask.convert("L")) > 0
    padded = np.pad(binary, 1, mode="constant")
    eroded = (
        padded[:-2, 1:-1]
        & padded[2:, 1:-1]
        & padded[1:-1, :-2]
        & padded[1:-1, 2:]
    )
    boundary = binary & ~eroded
    ys, xs = np.nonzero(boundary)
    for x, y in zip(xs.tolist(), ys.tolist(), strict=True):
        proof.putpixel((width + x, y), (0, 255, 255))
    return proof


def build_video(
    ffmpeg: str,
    proof_paths: list[Path],
    frame_indices: list[int],
    source_fps: float,
    output: Path,
) -> dict[str, Any] | None:
    executable = shutil.which(ffmpeg)
    if executable is None:
        return None
    concat = output.with_suffix(".concat.txt")
    rows: list[str] = []
    for index, path in enumerate(proof_paths):
        rows.append(f"file '{path.resolve()}'")
        if index + 1 < len(proof_paths):
            duration = (frame_indices[index + 1] - frame_indices[index]) / source_fps
            rows.append(f"duration {duration:.9f}")
    rows.append(f"file '{proof_paths[-1].resolve()}'")
    concat.write_text("\n".join(rows) + "\n", encoding="utf-8")
    command = [
        executable,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-f",
        "concat",
        "-safe",
        "0",
        "-i",
        str(concat),
        "-vsync",
        "vfr",
        "-c:v",
        "libx264",
        "-crf",
        "18",
        "-pix_fmt",
        "yuv420p",
        str(output),
    ]
    subprocess.run(command, check=True)
    return {
        "filename": output.name,
        "size_bytes": output.stat().st_size,
        "sha256": sha256(output),
        "validation_role": "human review only; not used for pixel preservation metrics",
    }


def main() -> None:
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    tinted_dir = args.output_dir / "tinted"
    proof_dir = args.output_dir / "proof"
    tinted_dir.mkdir(exist_ok=True)
    proof_dir.mkdir(exist_ok=True)
    tracking = json.loads(args.tracking_evidence.read_text(encoding="utf-8"))
    if tracking.get("status") != "PASS":
        raise RuntimeError("tracking evidence is not PASS")
    tracking_frames = {int(row["sequence_index"]): row for row in tracking["frames"]}

    frame_dirs = sorted(
        directory for directory in args.frame_root.iterdir() if directory.is_dir()
    )
    if len(frame_dirs) != len(tracking_frames):
        raise RuntimeError(
            f"frame count {len(frame_dirs)} does not match tracking evidence {len(tracking_frames)}"
        )
    color = np.array(
        [int(args.color[1:3], 16), int(args.color[3:5], 16), int(args.color[5:7], 16)],
        dtype=np.float64,
    )
    frame_evidence: list[dict[str, Any]] = []
    proof_paths: list[Path] = []
    frame_indices: list[int] = []
    total_outside_changed = 0
    total_inside_changed = 0
    for sequence_index, frame_dir in enumerate(frame_dirs):
        tracking_row = tracking_frames[sequence_index]
        source_index = int(tracking_row["source_frame_index"])
        expected_suffix = f"-{source_index:03d}"
        if not frame_dir.name.endswith(expected_suffix):
            raise RuntimeError(f"frame order mismatch: {frame_dir.name} vs {source_index}")
        source_path = frame_dir / "source.png"
        mask_path = args.mask_root / frame_dir.name / "device-mask.png"
        if not source_path.is_file() or not mask_path.is_file():
            raise RuntimeError(f"missing source or mask for {frame_dir.name}")
        if sha256(mask_path) != tracking_row["mask_sha256"]:
            raise RuntimeError(f"mask hash mismatch for {frame_dir.name}")

        source_image = Image.open(source_path).convert("RGB")
        mask_image = Image.open(mask_path).convert("L")
        if source_image.size != mask_image.size:
            raise RuntimeError(f"source/mask size mismatch for {frame_dir.name}")
        source = np.asarray(source_image)
        mask = np.asarray(mask_image)
        output = tint(source, mask, color, args.intensity)
        outside = mask == 0
        inside = mask > 0
        changed = np.any(output != source, axis=2)
        outside_changed = int(changed[outside].sum())
        inside_changed = int(changed[inside].sum())
        if outside_changed != 0:
            raise RuntimeError(f"mask exterior changed in {frame_dir.name}")
        if tracking_row["observation_status"] == "VISIBLE_VALIDATED" and inside_changed == 0:
            raise RuntimeError(f"visible mask produced no tint in {frame_dir.name}")

        output_path = tinted_dir / f"{sequence_index:02d}-{source_index:03d}.png"
        tinted_image = Image.fromarray(output, "RGB")
        tinted_image.save(output_path)
        proof_path = proof_dir / f"{sequence_index:02d}-{source_index:03d}.png"
        proof_frame(
            source_image,
            tinted_image,
            mask_image,
            f"frame={source_index} status={tracking_row['observation_status']}",
        ).save(proof_path)
        proof_paths.append(proof_path)
        frame_indices.append(source_index)
        total_outside_changed += outside_changed
        total_inside_changed += inside_changed
        frame_evidence.append(
            {
                "sequence_index": sequence_index,
                "source_frame_index": source_index,
                "observation_status": tracking_row["observation_status"],
                "mask_pixels": int(inside.sum()),
                "outside_mask_changed_pixels": outside_changed,
                "inside_mask_changed_pixels": inside_changed,
                "mask_sha256": sha256(mask_path),
                "tinted_png_sha256": sha256(output_path),
                "iou_vs_host_reference": tracking_row.get("iou_vs_host_reference"),
                "excess_outside_middle_panel_pixels": tracking_row.get(
                    "excess_outside_middle_panel_pixels"
                ),
            }
        )

    video = build_video(
        args.ffmpeg,
        proof_paths,
        frame_indices,
        args.source_fps,
        args.output_dir / "tracked-tint-proof.mp4",
    )
    contact_indices = sorted({0, len(proof_paths) // 4, len(proof_paths) // 2,
                              len(proof_paths) * 3 // 4, len(proof_paths) - 1})
    contact_frames = [Image.open(proof_paths[index]).convert("RGB") for index in contact_indices]
    contact_width = max(image.width for image in contact_frames)
    thumbnail_height = 320
    thumbnails = []
    for image in contact_frames:
        resized = image.copy()
        resized.thumbnail((contact_width, thumbnail_height), Image.Resampling.LANCZOS)
        thumbnails.append(resized)
    contact = Image.new(
        "RGB", (max(image.width for image in thumbnails), sum(image.height for image in thumbnails)), "black"
    )
    offset = 0
    for image in thumbnails:
        contact.paste(image, (0, offset))
        offset += image.height
    contact_path = args.output_dir / "tracked-tint-contact-sheet.jpg"
    contact.save(contact_path, quality=92)

    evidence = {
        "schema_version": 1,
        "status": "PASS_READ_ONLY_TRACKED_TINT",
        "source_tracking_evidence": {
            "path": str(args.tracking_evidence),
            "sha256": sha256(args.tracking_evidence),
            "runtime": tracking.get("runtime"),
            "device": tracking.get("device"),
        },
        "effect": {"kind": "color_tint", "color_hex": args.color.upper(), "intensity": args.intensity},
        "frame_count": len(frame_evidence),
        "total_outside_mask_changed_pixels": total_outside_changed,
        "total_inside_mask_changed_pixels": total_inside_changed,
        "minimum_iou_vs_host_reference": min(
            row["iou_vs_host_reference"] for row in frame_evidence
            if row["iou_vs_host_reference"] is not None
        ),
        "maximum_excess_preservation_leakage_pixels": max(
            int(row["excess_outside_middle_panel_pixels"] or 0) for row in frame_evidence
        ),
        "frames": frame_evidence,
        "artifacts": {
            "contact_sheet": {
                "filename": contact_path.name,
                "sha256": sha256(contact_path),
            },
            "proof_video": video,
        },
        "boundaries": {
            "read_only": True,
            "source_media_modified": False,
            "live_photo_repacked": False,
            "published": False,
            "ffmpeg_used_only_for_human_review_artifact": video is not None,
        },
    }
    evidence_path = args.output_dir / "tracked-tint-evidence.json"
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(evidence_path)


if __name__ == "__main__":
    main()
