from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

FTYP_MAGIC = b"ftyp"
JPEG_EOI = b"\xff\xd9"

PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA_ROOT = PROJECT_ROOT / "data"
DEFAULT_INPUT_DIR = DEFAULT_DATA_ROOT / "live_photo"
DEFAULT_DECODED_DIR = DEFAULT_DATA_ROOT / "live_photo_decoded"
DEFAULT_REPACKED_DIR = DEFAULT_DATA_ROOT / "repacked"
DEFAULT_VIDEO_DIR = DEFAULT_DATA_ROOT / "video" / "Inter4K" / "60fps" / "UHD"
DEFAULT_BATCH_WORK_DIR = DEFAULT_DECODED_DIR / "batch_generated"
LEGACY_REPACK_DIRS = [
    "live_photo_repacked",
    "live_photo_repacked_clean",
    "live_photo_repacked_verify",
]


def _find_mp4_start(data: bytes, search_start: int = 0) -> int:
    """Find mp4 payload start by locating ftyp and backing up to size field when valid."""
    ftyp_offset = data.find(FTYP_MAGIC, search_start)
    if ftyp_offset == -1:
        ftyp_offset = data.find(FTYP_MAGIC)
    if ftyp_offset == -1:
        raise ValueError("No mp4 signature 'ftyp' found")

    candidate_start = max(ftyp_offset - 4, 0)
    if candidate_start + 8 <= len(data) and data[candidate_start + 4 : candidate_start + 8] == FTYP_MAGIC:
        box_size = int.from_bytes(data[candidate_start : candidate_start + 4], byteorder="big", signed=False)
        if box_size >= 8:
            return candidate_start

    return ftyp_offset


def _extract_primary_jpeg(data: bytes) -> tuple[bytes, int]:
    """Extract primary JPEG bytes without being confused by embedded EXIF thumbnail JPEGs."""
    mp4_start = _find_mp4_start(data)
    if mp4_start <= 2:
        raise ValueError("Invalid mp4 start when extracting JPEG")

    # Motion photos can include thumbnail JPEGs in EXIF. Using the first EOI truncates the image.
    # We use the last JPEG EOI before the MP4 payload start as the primary image boundary.
    eoi_index = data.rfind(JPEG_EOI, 0, mp4_start)
    if eoi_index == -1:
        raise ValueError("No JPEG EOI marker found before MP4 payload")
    jpeg_end = eoi_index + len(JPEG_EOI)
    return data[:jpeg_end], jpeg_end


def is_live_photo_container(input_path: Path) -> bool:
    """Return True when a jpeg container has an embedded mp4 payload."""
    if input_path.suffix.lower() not in {".jpg", ".jpeg"}:
        return False
    if not input_path.exists() or not input_path.is_file():
        return False

    try:
        raw = input_path.read_bytes()
        _, jpeg_end = _extract_primary_jpeg(raw)
        mp4_start = _find_mp4_start(raw, search_start=jpeg_end)
    except Exception:  # noqa: BLE001
        return False

    # 注意: MP4 紧接 JPEG EOI 之后(间隙为 0, 即 mp4_start == jpeg_end)是标准布局,
    # vivo 相机产出的 Live Photo 均为此形态。此处必须用 '<' 而非 '<=',
    # 否则所有正常 Live Photo 都会被误判为非容器。
    if mp4_start < jpeg_end:
        return False
    if len(raw) - mp4_start < 16:
        return False
    return True


def unpack_motion_photo(input_path: Path, out_dir: Path | None = None) -> tuple[Path, Path]:
    """Split a motion photo file into primary JPEG and motion MP4."""
    if not input_path.exists():
        raise FileNotFoundError(f"Input file not found: {input_path}")

    raw = input_path.read_bytes()
    image_bytes, jpeg_end = _extract_primary_jpeg(raw)
    mp4_start = _find_mp4_start(raw, search_start=jpeg_end)
    mp4_bytes = raw[mp4_start:]

    output_dir = out_dir or input_path.parent
    output_dir.mkdir(parents=True, exist_ok=True)

    stem = input_path.stem
    image_out = output_dir / f"{stem}.jpg"
    video_out = output_dir / f"{stem}.mp4"

    image_out.write_bytes(image_bytes)
    video_out.write_bytes(mp4_bytes)
    return image_out, video_out


def normalize_data_layout(data_root: Path, apply: bool) -> dict[str, list[str]]:
    """Consolidate legacy output folders into: live_photo, live_photo_decoded, repacked."""
    summary: dict[str, list[str]] = {
        "created": [],
        "moved": [],
        "removed": [],
        "skipped": [],
    }

    required_dirs = [
        data_root / "live_photo",
        data_root / "live_photo_decoded",
        data_root / "repacked",
    ]
    repacked_dir = data_root / "repacked"

    for directory in required_dirs:
        if directory.exists():
            continue
        summary["created"].append(str(directory))
        if apply:
            directory.mkdir(parents=True, exist_ok=True)

    for legacy_name in LEGACY_REPACK_DIRS:
        legacy_dir = data_root / legacy_name
        if not legacy_dir.exists() or not legacy_dir.is_dir():
            continue

        for file_path in sorted(legacy_dir.iterdir()):
            if not file_path.is_file():
                continue

            target = repacked_dir / file_path.name
            if target.exists():
                target = repacked_dir / f"{file_path.stem}_{legacy_name}{file_path.suffix}"

            summary["moved"].append(f"{file_path} -> {target}")
            if apply:
                repacked_dir.mkdir(parents=True, exist_ok=True)
                shutil.move(str(file_path), str(target))

        if apply:
            try:
                legacy_dir.rmdir()
                summary["removed"].append(str(legacy_dir))
            except OSError:
                summary["skipped"].append(f"not_empty: {legacy_dir}")

    return summary


def _modify_vivo_gallery_identifier(video_data: bytes) -> bytes:
    """Patch vivo-specific live photo identifier when present, else append metadata block."""
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


def _build_xmp(video_size: int, presentation_timestamp_us: int) -> str:
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


def pack_motion_photo(
    image_path: Path,
    video_path: Path,
    output_path: Path,
    use_vivo_patch: bool,
    presentation_timestamp_us: int,
) -> Path:
    """Pack primary JPEG and motion MP4 into one motion photo file."""
    if not image_path.exists():
        raise FileNotFoundError(f"Image not found: {image_path}")
    if not video_path.exists():
        raise FileNotFoundError(f"Video not found: {video_path}")

    image_data = image_path.read_bytes()
    if image_data[:2] != b"\xff\xd8":
        raise ValueError("Input image is not a valid JPEG")

    video_data = video_path.read_bytes()
    if use_vivo_patch:
        video_data = _modify_vivo_gallery_identifier(video_data)

    try:
        import pyexiv2
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError(
            "pyexiv2 is required for pack mode. Install pyexiv2 in your environment first."
        ) from exc

    xmp_payload = _build_xmp(video_size=len(video_data), presentation_timestamp_us=presentation_timestamp_us)

    with pyexiv2.ImageData(image_data) as image:
        image.modify_raw_xmp(xmp_payload)
        patched_image = image.get_bytes()

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(patched_image + video_data)
    return output_path


def _run_checked(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        cmd_str = " ".join(cmd)
        stderr = result.stderr.strip() or "unknown error"
        raise RuntimeError(f"command_failed: {cmd_str}\n{stderr}")
    return result


def _get_duration_seconds(video_path: Path) -> float:
    result = _run_checked(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(video_path),
        ]
    )
    return float(result.stdout.strip())


def _get_keyframe_timestamps(video_path: Path) -> list[float]:
    result = _run_checked(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-skip_frame",
            "nokey",
            "-show_frames",
            "-show_entries",
            "frame=best_effort_timestamp_time",
            "-of",
            "json",
            str(video_path),
        ]
    )
    payload = json.loads(result.stdout or "{}")
    frames = payload.get("frames", [])
    timestamps: list[float] = []
    for frame in frames:
        value = frame.get("best_effort_timestamp_time")
        if value is None:
            continue
        try:
            timestamps.append(float(value))
        except (TypeError, ValueError):
            continue
    return timestamps


def _select_cover_timestamp(video_path: Path) -> float:
    duration = _get_duration_seconds(video_path)
    mid = max(duration / 2.0, 0.0)
    keyframes = _get_keyframe_timestamps(video_path)
    if not keyframes:
        return mid
    return min(keyframes, key=lambda ts: abs(ts - mid))


def _extract_cover_jpeg(video_path: Path, jpeg_out: Path, timestamp_sec: float) -> None:
    jpeg_out.parent.mkdir(parents=True, exist_ok=True)
    _run_checked(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{timestamp_sec:.3f}",
            "-i",
            str(video_path),
            "-frames:v",
            "1",
            "-q:v",
            "2",
            str(jpeg_out),
        ]
    )


def _transcode_live_video(
    input_video: Path,
    output_video: Path,
    fps: int,
    max_width: int,
    crf: int,
    preset: str,
    with_silent_audio: bool,
) -> None:
    output_video.parent.mkdir(parents=True, exist_ok=True)
    vf_parts: list[str] = []
    if max_width > 0:
        vf_parts.append(f"scale='if(gt(iw,{max_width}),{max_width},iw)':-2:flags=lanczos")
    if fps > 0:
        vf_parts.append(f"fps={fps}")

    cmd = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        str(input_video),
    ]
    if with_silent_audio:
        cmd.extend(
            [
                "-f",
                "lavfi",
                "-i",
                "anullsrc=channel_layout=stereo:sample_rate=48000",
            ]
        )
    if vf_parts:
        cmd.extend(["-vf", ",".join(vf_parts)])
    cmd.extend(
        [
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-preset",
            preset,
            "-crf",
            str(crf),
            "-map",
            "0:v:0",
        ]
    )

    if with_silent_audio:
        cmd.extend(
            [
                "-map",
                "1:a:0",
                "-c:a",
                "aac",
                "-b:a",
                "96k",
                "-shortest",
            ]
        )
    else:
        cmd.extend(["-an"])

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
    _run_checked(cmd)


def batch_generate_motion_photos(
    video_dir: Path,
    output_dir: Path,
    work_dir: Path,
    pattern: str,
    workers: int,
    overwrite: bool,
    keep_temp: bool,
    limit: int,
    dry_run: bool,
    fps: int,
    max_width: int,
    crf: int,
    preset: str,
    with_silent_audio: bool,
    use_vivo_patch: bool,
    presentation_timestamp_us: int,
) -> dict[str, int]:
    if not video_dir.exists() or not video_dir.is_dir():
        raise FileNotFoundError(f"video directory not found: {video_dir}")

    videos = sorted(video_dir.glob(pattern))
    videos = [path for path in videos if path.is_file()]
    if limit > 0:
        videos = videos[:limit]
    if not videos:
        raise RuntimeError(f"no videos matched {pattern} under {video_dir}")

    output_dir.mkdir(parents=True, exist_ok=True)
    work_dir.mkdir(parents=True, exist_ok=True)

    summary = {
        "total": len(videos),
        "success": 0,
        "failed": 0,
        "skipped": 0,
    }

    def _process(video_path: Path) -> tuple[str, str]:
        stem = video_path.stem
        temp_jpeg = work_dir / f"{stem}.jpg"
        temp_video = work_dir / f"{stem}.mp4"
        out_motion_photo = output_dir / f"{stem}.jpg"

        if out_motion_photo.exists() and not overwrite:
            return "skipped", f"{video_path.name}: output exists"

        if dry_run:
            return "success", f"{video_path.name}: dry-run"

        ts = _select_cover_timestamp(video_path)
        _extract_cover_jpeg(video_path=video_path, jpeg_out=temp_jpeg, timestamp_sec=ts)
        _transcode_live_video(
            input_video=video_path,
            output_video=temp_video,
            fps=fps,
            max_width=max_width,
            crf=crf,
            preset=preset,
            with_silent_audio=with_silent_audio,
        )
        pack_motion_photo(
            image_path=temp_jpeg,
            video_path=temp_video,
            output_path=out_motion_photo,
            use_vivo_patch=use_vivo_patch,
            presentation_timestamp_us=presentation_timestamp_us,
        )

        if not keep_temp:
            if temp_jpeg.exists():
                temp_jpeg.unlink()
            if temp_video.exists():
                temp_video.unlink()

        return "success", f"{video_path.name}: packed -> {out_motion_photo.name}"

    if workers <= 1:
        for video in videos:
            try:
                status, message = _process(video)
            except Exception as exc:  # noqa: BLE001
                summary["failed"] += 1
                print(f"[failed] {video.name}: {exc}")
                continue

            summary[status] += 1
            print(f"[{status}] {message}")
        return summary

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(_process, video): video for video in videos}
        for future in as_completed(futures):
            video = futures[future]
            try:
                status, message = future.result()
                summary[status] += 1
                print(f"[{status}] {message}")
            except Exception as exc:  # noqa: BLE001
                summary["failed"] += 1
                print(f"[failed] {video.name}: {exc}")

    return summary


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Motion/Live Photo converter: split single-file motion photo into jpg+mp4, or pack jpg+mp4 back."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    unpack_parser = subparsers.add_parser("unpack", help="Split motion photo file into jpg and mp4")
    unpack_parser.add_argument("input", type=Path, help="Input motion photo file (jpg container with embedded mp4)")
    unpack_parser.add_argument(
        "--out-dir",
        type=Path,
        default=DEFAULT_DECODED_DIR,
        help=f"Output directory (default: {DEFAULT_DECODED_DIR})",
    )

    pack_parser = subparsers.add_parser("pack", help="Pack jpg + mp4 into motion photo single file")
    pack_parser.add_argument("--image", type=Path, required=True, help="Primary JPEG path")
    pack_parser.add_argument("--video", type=Path, required=True, help="Motion MP4 path")
    pack_parser.add_argument("--output", type=Path, required=True, help="Output motion photo path")
    pack_parser.add_argument(
        "--presentation-timestamp-us",
        type=int,
        default=1504095,
        help="XMP motion photo presentation timestamp in microseconds",
    )
    pack_parser.add_argument(
        "--no-vivo-patch",
        action="store_true",
        help="Disable vivo gallery identifier patch in embedded mp4",
    )

    normalize_parser = subparsers.add_parser(
        "normalize-layout",
        help="Consolidate data folders into live_photo / live_photo_decoded / repacked",
    )
    normalize_parser.add_argument(
        "--data-root",
        type=Path,
        default=DEFAULT_DATA_ROOT,
        help=f"Data root directory (default: {DEFAULT_DATA_ROOT})",
    )
    normalize_parser.add_argument(
        "--apply",
        action="store_true",
        help="Apply changes. Without this flag, prints dry-run actions only.",
    )

    batch_parser = subparsers.add_parser(
        "batch-from-video",
        help="Batch create motion photo containers from input videos",
    )
    batch_parser.add_argument(
        "--video-dir",
        type=Path,
        default=DEFAULT_VIDEO_DIR,
        help=f"Input video directory (default: {DEFAULT_VIDEO_DIR})",
    )
    batch_parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_INPUT_DIR,
        help=f"Output live photo directory (default: {DEFAULT_INPUT_DIR})",
    )
    batch_parser.add_argument(
        "--work-dir",
        type=Path,
        default=DEFAULT_BATCH_WORK_DIR,
        help=f"Temporary jpg/mp4 directory (default: {DEFAULT_BATCH_WORK_DIR})",
    )
    batch_parser.add_argument("--pattern", type=str, default="*.mp4", help="Glob pattern for source videos")
    batch_parser.add_argument(
        "--workers",
        type=int,
        default=max(1, (os.cpu_count() or 2) // 2),
        help="Parallel worker count",
    )
    batch_parser.add_argument("--limit", type=int, default=0, help="Process only first N matched videos")
    batch_parser.add_argument("--overwrite", action="store_true", help="Overwrite existing output files")
    batch_parser.add_argument("--keep-temp", action="store_true", help="Keep intermediate jpg/mp4 files")
    batch_parser.add_argument("--dry-run", action="store_true", help="Preview workload without writing files")
    batch_parser.add_argument("--fps", type=int, default=30, help="Output motion video FPS")
    batch_parser.add_argument("--max-width", type=int, default=1920, help="Max output video width (0 to keep original)")
    batch_parser.add_argument("--crf", type=int, default=20, help="libx264 CRF for output motion video")
    batch_parser.add_argument("--preset", type=str, default="medium", help="libx264 preset")
    batch_parser.add_argument(
        "--no-silent-audio",
        action="store_true",
        help="Disable adding silent AAC track to output motion video",
    )
    batch_parser.add_argument(
        "--presentation-timestamp-us",
        type=int,
        default=1504095,
        help="XMP motion photo presentation timestamp in microseconds",
    )
    batch_parser.add_argument(
        "--no-vivo-patch",
        action="store_true",
        help="Disable vivo gallery identifier patch in embedded mp4",
    )

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    try:
        if args.command == "unpack":
            image_out, video_out = unpack_motion_photo(args.input, out_dir=args.out_dir)
            print(f"image_saved_to: {image_out}")
            print(f"video_saved_to: {video_out}")
            return 0

        if args.command == "pack":
            output = pack_motion_photo(
                image_path=args.image,
                video_path=args.video,
                output_path=args.output,
                use_vivo_patch=not args.no_vivo_patch,
                presentation_timestamp_us=args.presentation_timestamp_us,
            )
            print(f"motion_photo_saved_to: {output}")
            return 0

        if args.command == "normalize-layout":
            summary = normalize_data_layout(data_root=args.data_root, apply=args.apply)
            print(f"mode: {'apply' if args.apply else 'dry-run'}")
            for key in ["created", "moved", "removed", "skipped"]:
                print(f"{key}: {len(summary[key])}")
                for row in summary[key]:
                    print(f"  - {row}")
            return 0

        if args.command == "batch-from-video":
            summary = batch_generate_motion_photos(
                video_dir=args.video_dir,
                output_dir=args.output_dir,
                work_dir=args.work_dir,
                pattern=args.pattern,
                workers=max(1, args.workers),
                overwrite=args.overwrite,
                keep_temp=args.keep_temp,
                limit=max(0, args.limit),
                dry_run=args.dry_run,
                fps=max(0, args.fps),
                max_width=max(0, args.max_width),
                crf=args.crf,
                preset=args.preset,
                with_silent_audio=not args.no_silent_audio,
                use_vivo_patch=not args.no_vivo_patch,
                presentation_timestamp_us=args.presentation_timestamp_us,
            )
            print("batch_summary:")
            print(f"  total: {summary['total']}")
            print(f"  success: {summary['success']}")
            print(f"  skipped: {summary['skipped']}")
            print(f"  failed: {summary['failed']}")
            return 0

        parser.error("Unsupported command")
        return 2
    except Exception as exc:  # noqa: BLE001
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
