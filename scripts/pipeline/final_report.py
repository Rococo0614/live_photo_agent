#!/usr/bin/env python3
"""最终合成报告 MP4 — 逐步展示每个素材的裁剪和放置。

流程:
  Part 1: 逐个素材展示 (3段)
    a. 完整素材 (原始视频帧)
    b. 如果有主体: 展示分割主体 (mask叠加)
    c. 展示切出来最后用了哪些背景 (bg strip)
  Part 2: 所有主体的分布空间
  Part 3: 所有背景的放置位置
  Part 4: 最终成品
"""
import json
import sys
from pathlib import Path

import cv2
import numpy as np

OUTPUT_DIR = Path('data/live_photo/composition_output')
CANVAS_W = 1440
CANVAS_H = 1920
THIRD = CANVAS_H // 3


def resize_to_canvas(frame, target_w, target_h):
    h, w = frame.shape[:2]
    if h == target_h and w == target_w:
        return frame
    scale = max(target_w / w, target_h / h)
    new_w, new_h = int(w * scale), int(h * scale)
    resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_AREA)
    x0 = (new_w - target_w) // 2
    y0 = (new_h - target_h) // 2
    return resized[y0:y0 + target_h, x0:x0 + target_w]


def feather_mask(mask, blur_size=21):
    mask_f = mask.astype(np.float32) / 255.0
    return cv2.GaussianBlur(mask_f, (blur_size, blur_size), 0)


def get_bg_range(bg_region, frame_h):
    if bg_region == "top_third":
        return 0, frame_h // 3
    elif bg_region == "bottom_third":
        return frame_h * 2 // 3, frame_h
    elif bg_region == "middle_third":
        return frame_h // 3, frame_h * 2 // 3
    return 0, frame_h


def put_text(img, text, pos, size=0.6, color=(255, 255, 255), thickness=2):
    cv2.putText(img, text, pos, cv2.FONT_HERSHEY_SIMPLEX, size, color, thickness)


def main():
    manifest = json.loads((OUTPUT_DIR / 'manifest.json').read_text())
    layout = json.loads((OUTPUT_DIR / 'layout_plan.json').read_text())

    assets = manifest['assets']
    placements = layout['placements']

    # 构建素材 -> placements 映射 (处理拆分的背景)
    asset_placements = {}  # asset_id -> [placements]
    for p in placements:
        base_id = p['id'].rsplit('_split', 1)[0] if '_split' in p['id'] else p['id']
        if base_id not in asset_placements:
            asset_placements[base_id] = []
        asset_placements[base_id].append(p)

    # 打开原始素材视频
    asset_caps = {}
    for a in assets:
        if a.get('video_path'):
            asset_caps[a['id']] = cv2.VideoCapture(a['video_path'])

    # 找最大帧数
    n = min(int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) for cap in asset_caps.values()) if asset_caps else 0
    fps = 30.0
    for cap in asset_caps.values():
        f = cap.get(cv2.CAP_PROP_FPS)
        if f > 0:
            fps = f
            break

    print(f"素材数: {len(assets)}, 帧数: {n}, FPS: {fps}")

    out_path = OUTPUT_DIR / 'final_composition_report.mp4'
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer = cv2.VideoWriter(str(out_path), fourcc, fps, (CANVAS_W, CANVAS_H))

    colors = [
        (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0),
        (255, 0, 255), (0, 255, 255), (128, 128, 0), (128, 0, 128),
    ]

    # ═══════════════════════════════════════════════════════════
    # Part 1: 逐个素材展示
    # ═══════════════════════════════════════════════════════════
    for ai, asset in enumerate(assets):
        aid = asset['id']
        cap = asset_caps.get(aid)
        if not cap:
            continue

        has_subject = asset.get('has_subject', False)
        label = asset.get('label', '')
        a_placements = asset_placements.get(aid, [])

        # 每个素材展示 3 个阶段, 每个 2 秒
        frames_per_stage = int(fps * 2)

        # --- Stage a: 完整素材 ---
        for stage_fi in range(frames_per_stage):
            fi = (stage_fi + ai * 10) % n  # 不同素材从不同帧开始
            cap.set(cv2.CAP_PROP_POS_FRAMES, fi)
            ret, frame = cap.read()
            if not ret:
                continue

            canvas = resize_to_canvas(frame, CANVAS_W, CANVAS_H)

            # 标题
            cv2.rectangle(canvas, (0, 0), (CANVAS_W, 100), (0, 0, 0), -1)
            put_text(canvas, f"[{ai+1}/{len(assets)}] {aid} — Original", (20, 40), 0.8, (255, 255, 255), 2)
            put_text(canvas, f"has_subject={has_subject}  label={label}", (20, 75), 0.5, (200, 200, 200), 1)

            writer.write(canvas)

        # --- Stage b: 分割主体 (如果有) ---
        if has_subject:
            # 找 seg 目录
            seg_mask_dir = OUTPUT_DIR / f"seg_{aid}" / "masks"
            masks = sorted(seg_mask_dir.glob('*.png')) if seg_mask_dir.exists() else []

            for stage_fi in range(frames_per_stage):
                fi = (stage_fi + ai * 10 + 5) % n
                cap.set(cv2.CAP_PROP_POS_FRAMES, fi)
                ret, frame = cap.read()
                if not ret:
                    continue

                canvas = resize_to_canvas(frame, CANVAS_W, CANVAS_H)

                # 叠加 mask
                mask_idx = min(fi, len(masks) - 1) if masks else -1
                if mask_idx >= 0:
                    mask = cv2.imread(str(masks[mask_idx]), cv2.IMREAD_GRAYSCALE)
                    mask = resize_to_canvas(mask, CANVAS_W, CANVAS_H)
                    overlay = canvas.copy()
                    overlay[mask > 127] = [0, 0, 255]
                    canvas = cv2.addWeighted(canvas, 0.5, overlay, 0.5, 0)
                    # mask 边框
                    ys, xs = np.where(mask > 127)
                    if len(ys) > 0:
                        cv2.rectangle(canvas, (xs.min(), ys.min()), (xs.max(), ys.max()), (0, 255, 0), 3)

                cv2.rectangle(canvas, (0, 0), (CANVAS_W, 100), (0, 0, 0), -1)
                put_text(canvas, f"[{ai+1}/{len(assets)}] {aid} — Segmented Subject", (20, 40), 0.8, (255, 255, 255), 2)
                put_text(canvas, f"label={label}  coverage={asset.get('coverage', 0)*100:.1f}%", (20, 75), 0.5, (200, 200, 200), 1)

                writer.write(canvas)
        else:
            # 无主体: 展示"无主体可分割"
            for stage_fi in range(frames_per_stage):
                canvas = np.full((CANVAS_H, CANVAS_W, 3), 40, dtype=np.uint8)
                cv2.rectangle(canvas, (0, 0), (CANVAS_W, 100), (0, 0, 0), -1)
                put_text(canvas, f"[{ai+1}/{len(assets)}] {aid} — No Subject (Background Only)", (20, 40), 0.8, (255, 255, 255), 2)
                put_text(canvas, "No subject to segment — used as background", (20, 75), 0.5, (200, 200, 200), 1)
                put_text(canvas, "SKIP", (CANVAS_W // 2 - 50, CANVAS_H // 2), 2.0, (100, 100, 100), 3)
                writer.write(canvas)

        # --- Stage c: 切出来用了哪些背景 ---
        for stage_fi in range(frames_per_stage):
            fi = (stage_fi + ai * 10 + 10) % n
            cap.set(cv2.CAP_PROP_POS_FRAMES, fi)
            ret, frame = cap.read()
            if not ret:
                continue

            canvas = np.full((CANVAS_H, CANVAS_W, 3), 30, dtype=np.uint8)

            # 展示这个素材贡献的 bg strip
            for p in a_placements:
                if p['type'] != 'background' and p.get('bg_region') == 'full':
                    continue

                # 获取 bg_region
                if has_subject:
                    bg_region = p.get('bg_region', 'full')
                    bg_start, bg_end = get_bg_range(bg_region, frame.shape[0])
                else:
                    bg_start, bg_end = 0, frame.shape[0]

                # 裁剪背景 strip
                bg_strip = frame[bg_start:bg_end, :]
                bg_h = bg_strip.shape[0]
                bg_resized = resize_to_canvas(bg_strip, CANVAS_W, bg_h)

                # 放到 canvas 对应位置
                canvas_y = p['canvas_y']
                end_y = min(canvas_y + bg_h, CANVAS_H)
                canvas[canvas_y:end_y, :] = bg_resized[:end_y - canvas_y, :]

                # 标注
                cv2.rectangle(canvas, (0, canvas_y), (CANVAS_W, end_y), (0, 255, 0), 2)
                put_text(canvas, f"bg: {p['id']} y=[{canvas_y}:{end_y}] h={end_y-canvas_y}",
                         (10, canvas_y + 25), 0.5, (0, 255, 0), 1)

            cv2.rectangle(canvas, (0, 0), (CANVAS_W, 100), (0, 0, 0), -1)
            put_text(canvas, f"[{ai+1}/{len(assets)}] {aid} — Background Strips Used", (20, 40), 0.8, (255, 255, 255), 2)
            bg_regions = [p.get('bg_region', 'full') for p in a_placements]
            put_text(canvas, f"bg_regions: {bg_regions}", (20, 75), 0.5, (200, 200, 200), 1)

            writer.write(canvas)

    # ═══════════════════════════════════════════════════════════
    # Part 2: 所有主体的分布空间
    # ═══════════════════════════════════════════════════════════
    subject_placements = [p for p in placements if p['type'] == 'subject']
    frames_part2 = int(fps * 3)

    for stage_fi in range(frames_part2):
        canvas = np.full((CANVAS_H, CANVAS_W, 3), 30, dtype=np.uint8)

        cv2.rectangle(canvas, (0, 0), (CANVAS_W, 100), (0, 0, 0), -1)
        put_text(canvas, f"Part 2: Subject Placement ({len(subject_placements)} subjects)", (20, 40), 0.8, (255, 255, 255), 2)
        put_text(canvas, "Green boxes = subject canvas positions", (20, 75), 0.5, (200, 200, 200), 1)

        for i, p in enumerate(subject_placements):
            color = colors[i % len(colors)]
            y = p['canvas_y']
            h = p['canvas_h']

            overlay = canvas.copy()
            overlay[y:y+h, :] = color
            canvas = cv2.addWeighted(canvas, 0.7, overlay, 0.3, 0)
            cv2.rectangle(canvas, (0, y), (CANVAS_W, y+h), color, 3)

            asset = next((a for a in assets if a['id'] == p['id']), {})
            put_text(canvas, f"{p['id']} {asset.get('label', '')} y=[{y}:{y+h}] h={h} pos={p.get('position','')}",
                     (10, y + 30), 0.5, color, 1)

        writer.write(canvas)

    # ═══════════════════════════════════════════════════════════
    # Part 3: 所有背景的放置位置
    # ═══════════════════════════════════════════════════════════
    bg_placements = [p for p in placements if p['type'] == 'background']
    frames_part3 = int(fps * 3)

    for stage_fi in range(frames_part3):
        canvas = np.full((CANVAS_H, CANVAS_W, 3), 30, dtype=np.uint8)

        cv2.rectangle(canvas, (0, 0), (CANVAS_W, 100), (0, 0, 0), -1)
        put_text(canvas, f"Part 3: Background Placement ({len(bg_placements)} strips)", (20, 40), 0.8, (255, 255, 255), 2)
        put_text(canvas, "Blue boxes = background canvas positions", (20, 75), 0.5, (200, 200, 200), 1)

        for i, p in enumerate(bg_placements):
            color = (200, 100, 0)  # 橙色
            y = p['canvas_y']
            h = p['canvas_h']

            overlay = canvas.copy()
            overlay[y:y+h, :] = color
            canvas = cv2.addWeighted(canvas, 0.7, overlay, 0.3, 0)
            cv2.rectangle(canvas, (0, y), (CANVAS_W, y+h), color, 3)

            base_id = p['id'].rsplit('_split', 1)[0] if '_split' in p['id'] else p['id']
            put_text(canvas, f"{p['id']} from {base_id} y=[{y}:{y+h}] h={h}",
                     (10, y + 30), 0.5, color, 1)

        writer.write(canvas)

    # ═══════════════════════════════════════════════════════════
    # Part 4: 最终成品
    # ═══════════════════════════════════════════════════════════
    # 重新打开所有视频
    bg_caps = {}
    bg_ranges = {}
    for p in bg_placements:
        bg_path = OUTPUT_DIR / f"bg_{p['id']}.mp4"
        if bg_path.exists():
            bg_caps[p['id']] = cv2.VideoCapture(str(bg_path))
            bg_ranges[p['id']] = (p['canvas_y'], p['canvas_h'])

    sub_caps = {}
    alpha_caps = {}
    subject_placements.sort(key=lambda p: next((a['coverage'] for a in assets if a['id'] == p['id']), 0))
    for p in subject_placements:
        sub_path = OUTPUT_DIR / f"subject_{p['id']}.mp4"
        alpha_path = OUTPUT_DIR / f"alpha_{p['id']}.mp4"
        if sub_path.exists() and alpha_path.exists():
            sub_caps[p['id']] = cv2.VideoCapture(str(sub_path))
            alpha_caps[p['id']] = cv2.VideoCapture(str(alpha_path))

    all_caps = list(bg_caps.values()) + list(sub_caps.values()) + list(alpha_caps.values())
    n_final = min(int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) for cap in all_caps) if all_caps else 0

    # 底色
    first_bg_frame = None
    if bg_caps:
        first_aid = next(iter(bg_caps))
        cap = bg_caps[first_aid]
        cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
        ret, f = cap.read()
        if ret:
            first_bg_frame = f.copy()
        cap.set(cv2.CAP_PROP_POS_FRAMES, 0)

    for fi in range(n_final):
        # 背景画布
        canvas = np.zeros((CANVAS_H, CANVAS_W, 3), dtype=np.uint8)
        if first_bg_frame is not None:
            canvas[:] = resize_to_canvas(first_bg_frame, CANVAS_W, CANVAS_H)

        bg_frames = {}
        for aid, cap in bg_caps.items():
            ret, frame = cap.read()
            if ret:
                bg_frames[aid] = frame

        for aid, frame in bg_frames.items():
            y, h = bg_ranges[aid]
            actual_h = frame.shape[0]
            target_h = min(h, actual_h)
            frame_resized = resize_to_canvas(frame, CANVAS_W, target_h)
            end_y = min(y + target_h, CANVAS_H)
            canvas[y:end_y, :] = frame_resized[:end_y - y, :]

        # 叠加主体
        for p in subject_placements:
            aid = p['id']
            if aid not in sub_caps:
                continue
            ret_sub, sub_frame = sub_caps[aid].read()
            ret_alpha, alpha_frame = alpha_caps[aid].read()
            if not ret_sub or not ret_alpha:
                continue
            alpha_gray = cv2.cvtColor(alpha_frame, cv2.COLOR_BGR2GRAY)
            m_f = feather_mask(alpha_gray, 21)
            sub_resized = resize_to_canvas(sub_frame, CANVAS_W, CANVAS_H)
            canvas_f = canvas.astype(np.float32)
            canvas_f = sub_resized.astype(np.float32) * m_f[..., None] + canvas_f * (1 - m_f[..., None])
            canvas = np.clip(canvas_f, 0, 255).astype(np.uint8)

        # 标题
        cv2.rectangle(canvas, (0, 0), (CANVAS_W, 60), (0, 0, 0), -1)
        put_text(canvas, f"Part 4: Final Composite — Frame {fi}/{n_final}", (20, 35), 0.7, (255, 255, 255), 2)

        writer.write(canvas)

        if fi % 20 == 0:
            print(f"  Part4 帧 {fi}/{n_final}")

    writer.release()
    for cap in list(bg_caps.values()) + list(sub_caps.values()) + list(alpha_caps.values()) + list(asset_caps.values()):
        cap.release()

    print(f"\n输出: {out_path} ({out_path.stat().st_size/1e6:.1f}MB)")
    print(f"尺寸: {CANVAS_W}x{CANVAS_H}")


if __name__ == '__main__':
    main()
