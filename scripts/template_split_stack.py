#!/usr/bin/env python3
"""三段 Live Photo 分割拼接模板

流程:
  1. 对3段 cat live video 分别调用 Mask2Former + Cutie 分割
  2. 底部 1/3 复原（保留背景）
  3. 三段分割结果上中下垂直拼接
  4. 输出最终 MP4

用法:
  python3 scripts/template_split_stack.py
  python3 scripts/template_split_stack.py --videos cat_1.mp4 cat_2.mp4 cat_3.mp4
  python3 scripts/template_split_stack.py --interactive  # 手动选候选
"""
import sys
import json
import argparse
from pathlib import Path

import cv2
import numpy as np

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
sys.path.insert(0, "/home/vivo/Cutie")

from live_photo_agent.foundation.video_segmentation import (
    VideoSegmentationPipeline, SegmentationTemplate, SegmentationStage,
)

DEFAULT_VIDEOS = [
    "data/live_photo/cat_1.mp4",
    "data/live_photo/cat_2.mp4",
    "data/live_photo/cat_3.mp4",
]
OUTPUT_ROOT = Path("data/live_photo/triptych_split_stack")
CANVAS_W = 1080
CANVAS_H = 1440


def run_segmentation(
    video_path: Path,
    output_dir: Path,
    selected_indices: list[int] | None = None,
    interactive: bool = False,
    strip_position: str = "top",  # "top" | "middle" | "bottom"
) -> Path | None:
    """Run Mask2Former→Cutie segmentation on one video.

    strip_position determines which 1/3 of the frame is kept as background:
      - "top":    keep top 1/3, mask only applies to top 1/3
      - "middle": keep middle 1/3
      - "bottom": keep bottom 1/3

    Returns path to mask directory, or None if user skipped.
    """
    template = SegmentationTemplate(
        auto_select_min_coverage=0.50,  # Always pause for selection
    )
    # Override region config based on strip position
    if strip_position in ("top", "top_half"):
        template.region = "top_half"
    elif strip_position in ("top_third",):
        template.region = "top_third"
    elif strip_position == "middle":
        template.region = "middle_third"
    elif strip_position in ("bottom_two_thirds",):
        template.region = "bottom_two_thirds"
    else:  # bottom, bottom_half
        template.region = "bottom_half"

    pipeline = VideoSegmentationPipeline(template)

    # Stage 1: detect + select
    result = pipeline.run(video_path, output_dir, selected_indices=selected_indices)

    if result.stage == SegmentationStage.AWAITING_SELECTION:
        print(f"\n{'='*60}")
        print(f"  候选检测完成: {video_path.name}")
        print(f"  可视化: {result.visualization_path}")
        print(f"  候选列表:")
        for c in result.candidates:
            print(f"    #{c.index}: {c.label_name} 覆盖率={c.coverage*100:.1f}%")

        # Always save candidates.jpg for inspection
        cand_src = Path(result.visualization_path) if result.visualization_path else None
        if cand_src and cand_src.exists():
            cand_dst = output_dir / "candidates.jpg"
            if cand_src.resolve() != cand_dst.resolve():
                import shutil
                shutil.copy2(cand_src, cand_dst)
            print(f"  候选图已保存: {cand_dst}")

        if interactive:
            print(f"\n  请输入要选择的候选编号 (逗号分隔, 如 0,7):")
            user_input = input("  > ").strip()
            indices = [int(x.strip()) for x in user_input.split(",")]
            result = pipeline.run(video_path, output_dir, selected_indices=indices)
        else:
            # Auto-select common subject candidates (person, cat, dog, bird, etc.)
            SUBJECT_LABELS = {
                # 动物
                "cat", "dog", "person", "bird", "horse", "sheep",
                "cow", "elephant", "bear", "zebra", "giraffe",
                # 物品 (前景物体, 不含背景类)
                "cup", "bottle", "vase", "bowl", "potted plant",
                "vase-merged", "potted-plant-merged", "cup-merged",
                "bottle-merged",
            }
            # 排除背景类 (wall, table, tv, floor, etc.)
            BG_LABELS = {
                "wall-other-merged", "wall-merged", "wall-tile-merged",
                "table-merged", "floor-merged", "ceiling-merged",
                "tv", "curtain-merged", "window-merged",
                "rug-merged", "wall-other", "rock-merged",
                "sky-other-merged", "grass-merged", "fence-merged",
                "mountain-merged", "tree-merged", "house",
                "sand-merged", "road-merged", "pavement-merged",
            }
            subject_indices = [
                c.index for c in result.candidates
                if c.label_name in SUBJECT_LABELS
                and c.label_name not in BG_LABELS
            ]
            if subject_indices:
                # For small objects (bottle, cup, vase), pick the smallest coverage
                # candidate — large coverage usually means background misclassification
                SMALL_OBJECTS = {"bottle", "bottle-merged", "vase", "vase-merged",
                                 "bowl", "cup", "cup-merged", "potted plant",
                                 "potted-plant-merged"}
                subject_cands = [c for c in result.candidates if c.index in subject_indices]
                has_small = any(c.label_name in SMALL_OBJECTS for c in subject_cands)
                if has_small:
                    # Prefer small objects, pick smallest among them
                    small_cands = [c for c in subject_cands if c.label_name in SMALL_OBJECTS]
                    selected = [min(small_cands, key=lambda c: c.coverage).index]
                    labels = [c.label_name for c in result.candidates if c.index in selected]
                else:
                    # For animals/person, pick largest
                    selected = [max(subject_cands, key=lambda c: c.coverage).index]
                    labels = [c.label_name for c in result.candidates if c.index in selected]
                print(f"  自动选择主体候选: {selected} ({labels})")
                result = pipeline.run(
                    video_path, output_dir, selected_indices=selected
                )
            else:
                print(f"  未找到主体候选, 跳过")
                return None

    if result.final_mask_dir:
        print(f"  分割完成: coverage={result.coverage*100:.1f}%")
        print(f"  Mask目录: {result.final_mask_dir}")
        print(f"  MP4: {result.metrics.get('mp4_path', 'N/A')}")
        return Path(result.final_mask_dir)

    return None


def analyze_subject_bboxes(
    mask_dirs: list[Path], num_samples: int = 10, split_ratio: float = 0.5,
) -> list[tuple[int, int, int, int]]:
    """Analyze subject bounding boxes across frames for each video.

    Returns list of (y_min, y_max, x_min, x_max) for each video,
    averaged across sampled frames. Only counts subject pixels
    (mask>0 minus the strip background).
    """
    bboxes = []
    for i, mask_dir in enumerate(mask_dirs):
        masks = sorted(mask_dir.glob("*.png"))
        if not masks:
            bboxes.append((0, 0, 0, 0))
            continue

        step = max(1, len(masks) // num_samples)
        sampled = masks[::step][:num_samples]

        y_mins, y_maxs, x_mins, x_maxs = [], [], [], []
        h = cv2.imread(str(masks[0]), cv2.IMREAD_GRAYSCALE).shape[0]
        n = len(mask_dirs)
        if n <= 2:
            top_h = int(h * split_ratio)
            strip_ranges = [(0, top_h), (top_h, h)]
        else:
            third = h // 3
            strip_ranges = [(0, third), (third, third * 2), (third * 2, h)]
        y_start, y_end = strip_ranges[i] if i < len(strip_ranges) else (0, h)

        for mp in sampled:
            mask = cv2.imread(str(mp), cv2.IMREAD_GRAYSCALE)
            if mask is None:
                continue
            strip_bg = np.zeros_like(mask)
            strip_bg[y_start:y_end] = 255
            subject = (mask > 0) & (strip_bg == 0)
            if not subject.any():
                continue
            ys, xs = np.where(subject)
            y_mins.append(ys.min())
            y_maxs.append(ys.max())
            x_mins.append(xs.min())
            x_maxs.append(xs.max())

        if y_mins:
            bbox = (
                int(np.median(y_mins)),
                int(np.median(y_maxs)),
                int(np.median(x_mins)),
                int(np.median(x_maxs)),
            )
        else:
            bbox = (0, 0, 0, 0)
        bboxes.append(bbox)
        print(f"  Video {i+1} subject bbox: y=[{bbox[0]},{bbox[1]}] x=[{bbox[2]},{bbox[3]}] h={bbox[1]-bbox[0]} w={bbox[3]-bbox[2]}")
    return bboxes


def compute_adaptive_strip_ranges(
    bboxes: list[tuple[int, int, int, int]],
    canvas_h: int,
) -> list[tuple[int, int]]:
    """Compute strip boundaries that separate subjects vertically."""
    h = canvas_h
    n = len(bboxes)
    ranges = []
    for i, (y_min, y_max, _, _) in enumerate(bboxes):
        if y_max > y_min:
            ranges.append((y_min, y_max, i))
        else:
            panel_h = h // n
            ranges.append((i * panel_h, (i + 1) * panel_h, i))
    ranges.sort()
    boundaries = [0]
    for j in range(len(ranges) - 1):
        bottom = ranges[j][1]
        top_next = ranges[j + 1][0]
        boundary = (bottom + top_next) // 2
        boundaries.append(boundary)
    boundaries.append(h)
    min_strip = h // 6
    for j in range(1, len(boundaries) - 1):
        boundaries[j] = max(min_strip, min(h - min_strip, boundaries[j]))
    for j in range(1, len(boundaries)):
        if boundaries[j] <= boundaries[j - 1]:
            boundaries[j] = boundaries[j - 1] + min_strip
    strip_by_video = [None] * n
    for j, (_, _, orig_idx) in enumerate(ranges):
        strip_by_video[orig_idx] = (boundaries[j], boundaries[j + 1])
    print(f"  Adaptive strip ranges:")
    for i, (ys, ye) in enumerate(strip_by_video):
        print(f"    Video {i+1}: rows [{ys}, {ye}] ({ye-ys}px)")
    return strip_by_video


def render_overlap_visualization(
    bboxes: list[tuple[int, int, int, int]],
    output_path: Path,
):
    """Render all subject bboxes on one canvas to show overlap."""
    canvas = np.full((CANVAS_H, CANVAS_W, 3), 30, dtype=np.uint8)
    colors = [(0, 0, 255), (0, 255, 0), (255, 0, 0)]
    labels = ["Video 1", "Video 2", "Video 3"]
    for i, (y_min, y_max, x_min, x_max) in enumerate(bboxes):
        if y_max <= y_min:
            continue
        color = colors[i % len(colors)]
        cv2.rectangle(canvas, (x_min, y_min), (x_max, y_max), color, 3)
        cv2.putText(canvas, f"{labels[i]} cat", (x_min, y_min - 10),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)
    cv2.imwrite(str(output_path), canvas)
    print(f"  Overlap visualization: {output_path}")


def compute_subject_layout(
    bboxes: list[tuple[int, int, int, int]],
    canvas_w: int,
    canvas_h: int,
    orig_w: int,
    orig_h: int,
    split_ratio: float = 0.5,  # top zone = split_ratio * canvas_h
) -> list[dict]:
    """Compute placement for each video so subjects don't overlap.

    split_ratio: fraction of canvas height for the top zone.
    0.5 = equal halves, 0.33 = top 1/3 + bottom 2/3, etc.
    """
    n = len(bboxes)
    top_h = int(canvas_h * split_ratio)

    # Collect subject centers
    subjects = []
    for i, (y_min, y_max, x_min, x_max) in enumerate(bboxes):
        if y_max > y_min and x_max > x_min:
            y_center = (y_min + y_max) / 2
            x_center = (x_min + x_max) / 2
        else:
            y_center = (i % 2) * top_h + top_h / 2
            x_center = (i % 2) * (canvas_w // 2) + canvas_w / 4
        subjects.append({
            "i": i,
            "y_center": y_center,
            "x_center": x_center,
            "y_min": y_min, "y_max": y_max,
            "x_min": x_min, "x_max": x_max,
        })

    # Sort by y-center: first goes top zone, rest go bottom zone
    subjects.sort(key=lambda s: s["y_center"])

    assignments = [None] * n
    for zone_idx, s in enumerate(subjects):
        i = s["i"]
        if zone_idx == 0:
            zone_top = 0
            zone_bot = top_h
        else:
            zone_top = top_h
            zone_bot = canvas_h

        # No scaling
        scale = 1.0

        # Shift so subject is centered in its zone
        zone_h = zone_bot - zone_top
        subj_h = s["y_max"] - s["y_min"]
        subj_w = s["x_max"] - s["x_min"]

        # Y offset: center subject in zone
        target_y = zone_top + (zone_h - subj_h) // 2
        y_offset = target_y - s["y_min"]
        if y_offset < 0 and orig_h > canvas_h:
            y_offset = -(s["y_min"]) + zone_top

        # X offset: center horizontally
        target_x = (canvas_w - subj_w) // 2
        x_offset = target_x - s["x_min"]
        x_offset = max(-orig_w + canvas_w, min(0, x_offset))

        assignments[i] = {
            "scale": scale,
            "x_offset": int(x_offset),
            "y_offset": int(y_offset),
            "zone_top": zone_top,
            "zone_bot": zone_bot,
        }

    # Print layout
    print(f"\n  布局规划 (无缩放, 位置分配):")
    for i, a in enumerate(assignments):
        zone = "上" if a["zone_top"] == 0 else "下"
        print(f"    Video {i+1}: {zone}半区, offset=({a['x_offset']},{a['y_offset']}), "
              f"背景 rows [{a['zone_top']}, {a['zone_bot']}]")

    return assignments


def render_layout_visualization(
    bboxes: list[tuple[int, int, int, int]],
    layout: list[dict],
    output_path: Path,
):
    """Render the planned layout: where each subject will land on canvas."""
    canvas = np.full((CANVAS_H, CANVAS_W, 3), 30, dtype=np.uint8)
    colors = [(0, 0, 255), (0, 255, 0), (255, 0, 0)]
    labels = ["Video 1", "Video 2", "Video 3"]

    # Draw zone boundaries
    for i, a in enumerate(layout):
        cv2.line(canvas, (0, a["zone_top"]), (CANVAS_W, a["zone_top"]),
                 (100, 100, 100), 1)
        cv2.putText(canvas, f"Zone {i+1}", (10, a["zone_top"] + 20),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (120, 120, 120), 1)

    # Draw scaled subject boxes
    for i, (a, bbox) in enumerate(zip(layout, bboxes)):
        y_min, y_max, x_min, x_max = bbox
        scale = a["scale"]
        sx1 = int(x_min * scale) + a["x_offset"]
        sy1 = int(y_min * scale) + a["y_offset"]
        sx2 = int(x_max * scale) + a["x_offset"]
        sy2 = int(y_max * scale) + a["y_offset"]
        color = colors[i % len(colors)]
        cv2.rectangle(canvas, (sx1, sy1), (sx2, sy2), color, 3)
        cv2.putText(canvas, f"{labels[i]} (scale={scale:.2f})",
                    (sx1, sy1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)

    cv2.line(canvas, (0, CANVAS_H), (CANVAS_W, CANVAS_H), (100, 100, 100), 1)
    cv2.imwrite(str(output_path), canvas)
    print(f"  Layout visualization: {output_path}")


def composite_split_stack(
    video_paths: list[Path],
    mask_dirs: list[Path],
    output_path: Path,
    output_root: Path,
    split_ratio: float = 0.5,
):
    """Composite 3 videos with adaptive scale+offset so subjects don't overlap.

    Pipeline:
    1. Analyze subject bboxes from masks
    2. Render overlap visualization (before adjustment)
    3. Compute scale + offset layout
    4. Render layout visualization (after adjustment)
    5. Composite final video using computed layout
    """
    fps = 27.45
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    out_video = cv2.VideoWriter(
        str(output_path), fourcc, fps, (CANVAS_W, CANVAS_H)
    )

    caps = [cv2.VideoCapture(str(v)) for v in video_paths]
    frame_counts = [int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) for cap in caps]
    total_frames = min(frame_counts)  # 按最短的来
    print(f"\n  帧数: {frame_counts}, 取最短: {total_frames}")

    # Step 1: Analyze subject positions
    print(f"\n{'='*60}")
    print(f"  Step 1: 分析主体位置")
    bboxes = analyze_subject_bboxes(mask_dirs, split_ratio=split_ratio)

    # Get original frame dimensions
    orig_h, orig_w = caps[0].get(cv2.CAP_PROP_FRAME_HEIGHT), caps[0].get(cv2.CAP_PROP_FRAME_WIDTH)
    orig_h, orig_w = int(orig_h), int(orig_w)
    print(f"  原始分辨率: {orig_w}x{orig_h}")

    # Step 2: Render overlap visualization
    print(f"\n  Step 2: 输出重叠可视化")
    overlap_path = output_root / "1_overlap_analysis.jpg"
    render_overlap_visualization(bboxes, overlap_path)

    # Step 3: Compute adaptive layout
    print(f"\n  Step 3: 计算自适应布局 (缩放+位移)")
    layout = compute_subject_layout(bboxes, CANVAS_W, CANVAS_H, orig_w, orig_h, split_ratio)
    for i, a in enumerate(layout):
        print(f"    Video {i+1}: scale={a['scale']:.2f} "
              f"x_offset={a['x_offset']} y_offset={a['y_offset']} "
              f"zone=[{a['zone_top']},{a['zone_bot']}]")

    # Step 4: Render layout visualization
    print(f"\n  Step 4: 输出布局可视化")
    layout_path = output_root / "2_layout_plan.jpg"
    render_layout_visualization(bboxes, layout, layout_path)

    # Step 4.5: Render frame-0 mask preview for each video
    print(f"\n  Step 4.5: 输出分割预览")
    for i, (cap, mask_dir) in enumerate(zip(caps, mask_dirs)):
        cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
        ret, frame = cap.read()
        if not ret:
            continue
        mask_path = mask_dir / "0000.png"
        if mask_path.exists():
            mask = cv2.imread(str(mask_path), cv2.IMREAD_GRAYSCALE)
        else:
            mask = np.zeros(frame.shape[:2], dtype=np.uint8)

        # Subject-only
        a = layout[i]
        strip_bg = np.zeros_like(mask)
        strip_bg[a["zone_top"]:a["zone_bot"]] = 255
        subject = (mask > 0) & (strip_bg == 0)

        vis = frame.copy()
        vis[mask > 0] = [0, 255, 0]  # green = mask (subject + bg)
        vis[subject] = [0, 0, 255]   # red = subject only
        vis = cv2.addWeighted(frame, 0.4, vis, 0.6, 0)
        cv2.rectangle(vis, (0, a["zone_top"]), (frame.shape[1], a["zone_bot"]),
                      (255, 255, 0), 3)  # yellow = zone boundary
        cv2.imwrite(str(output_root / f"3_seg{i+1}_preview.jpg"), vis)
        print(f"    Video {i+1}: 3_seg{i+1}_preview.jpg")

    # Step 5: Composite
    # 不缩放, 素材跟随mask, 主体全画面保留, 背景各占一半(上/下)
    # 按z-order: 后面的视频覆盖前面的
    print(f"\n  Step 5: 合成最终视频")
    print(f"  画布: {CANVAS_W}x{CANVAS_H}, {total_frames}帧")
    print(f"  策略: 无缩放, 主体保留, 背景各占一半, 按z-order覆盖")
    print(f"  输出: {output_path}")

    for fi in range(total_frames):
        canvas = np.full((CANVAS_H, CANVAS_W, 3), 30, dtype=np.uint8)

        for i, (cap, mask_dir) in enumerate(zip(caps, mask_dirs)):
            ret, frame = cap.read()
            if not ret:
                cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                ret, frame = cap.read()
            if not ret:
                continue

            h, w = frame.shape[:2]
            a = layout[i]
            scale = a["scale"]  # = 1.0, no scaling
            new_w, new_h = w, h  # original size

            # No resize — use original frame and mask
            scaled_frame = frame
            mask_path = mask_dir / f"{fi:04d}.png"
            if mask_path.exists():
                scaled_mask = cv2.imread(str(mask_path), cv2.IMREAD_GRAYSCALE)
            else:
                scaled_mask = np.zeros((new_h, new_w), dtype=np.uint8)

            # Subject-only: mask minus the assigned half background
            # Each video's background is its half (top or bottom)
            strip_bg = np.zeros_like(scaled_mask)
            zone_top = a["zone_top"]
            zone_bot = a["zone_bot"]
            strip_bg[zone_top:zone_bot] = 255
            subject_only = (scaled_mask > 0) & (strip_bg == 0)

            # Place on canvas at computed offset (no scaling)
            x_off = a["x_offset"]
            y_off = a["y_offset"]

            # Calculate overlap region between frame and canvas
            cy1 = max(0, y_off)
            cy2 = min(CANVAS_H, y_off + new_h)
            cx1 = max(0, x_off)
            cx2 = min(CANVAS_W, x_off + new_w)
            sy1 = max(0, -y_off)
            sy2 = sy1 + (cy2 - cy1)
            sx1 = max(0, -x_off)
            sx2 = sx1 + (cx2 - cx1)

            if cy2 > cy1 and cx2 > cx1:
                # Paint: where mask > 0 (subject OR background half), paint frame
                # Background half fills the zone, subject overlays on top
                region_mask = scaled_mask[sy1:sy2, sx1:sx2]
                region_frame = scaled_frame[sy1:sy2, sx1:sx2]

                paint_area = region_mask > 0
                canvas[cy1:cy2, cx1:cx2][paint_area] = region_frame[paint_area]

        out_video.write(canvas)
        if fi % 20 == 0:
            print(f"  Frame {fi}/{total_frames}")

    out_video.release()
    for cap in caps:
        cap.release()
    print(f"  完成: {output_path}")


def main():
    parser = argparse.ArgumentParser(description="三段 Live Photo 分割拼接模板")
    parser.add_argument(
        "--videos",
        nargs="+",
        default=DEFAULT_VIDEOS,
        help="3个视频文件路径",
    )
    parser.add_argument(
        "--interactive",
        action="store_true",
        help="交互模式: 手动选择候选",
    )
    parser.add_argument(
        "--output",
        default=str(OUTPUT_ROOT / "triptych.mp4"),
        help="输出文件路径",
    )
    parser.add_argument(
        "--split",
        type=float,
        default=0.5,
        help="背景分区比例 (0.33=上1/3下2/3, 0.5=上下各半, 默认0.5)",
    )
    args = parser.parse_args()

    output_root = Path(args.output).parent
    output_root.mkdir(parents=True, exist_ok=True)

    # Step 1: Segment each video with its strip position
    # For 2 videos: top + bottom; for 3: top + middle + bottom thirds
    if len(args.videos) <= 2:
        # Use split ratio to determine region
        if args.split <= 0.4:
            # Top zone is smaller (e.g. 1/3), bottom zone is larger (2/3)
            strip_positions = ["top_third", "bottom_two_thirds"]
        else:
            strip_positions = ["top_half", "bottom_half"]
    else:
        strip_positions = ["top", "middle", "bottom"]
    mask_dirs = []
    for i, vpath in enumerate(args.videos):
        vpath = Path(vpath)
        if not vpath.exists():
            print(f"视频不存在: {vpath}")
            return

        seg_dir = output_root / f"seg_{i+1}"
        strip = strip_positions[i] if i < 3 else "bottom"
        print(f"\n{'='*60}")
        print(f"  处理视频 {i+1}/3: {vpath.name}")
        print(f"  条带位置: {strip} 1/3")
        print(f"  输出: {seg_dir}")

        mask_dir = run_segmentation(
            vpath,
            seg_dir,
            interactive=args.interactive,
            strip_position=strip,
        )
        if mask_dir:
            mask_dirs.append(mask_dir)
        else:
            print(f"  跳过视频 {vpath.name}")

    if len(mask_dirs) < 2:
        print(f"\n需要至少2个视频的分割结果, 当前只有 {len(mask_dirs)}")
        return

    # Step 2: Composite with adaptive layout
    output_path = Path(args.output)
    composite_split_stack(
        [Path(v) for v in args.videos[: len(mask_dirs)]],
        mask_dirs,
        output_path,
        output_root,
        split_ratio=args.split,
    )

    print(f"\n{'='*60}")
    print(f"  最终输出: {output_path}")
    print(f"  大小: {output_path.stat().st_size / 1e6:.1f}MB")


if __name__ == "__main__":
    main()
