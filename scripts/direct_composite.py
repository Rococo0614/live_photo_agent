#!/usr/bin/env python3
"""拼接两个原始视频 + mask，直接用原始视频画面填充，消灭所有 checkerboard。

seg1: 瓶子在上半部分，用原始视频按 mask 叠加
seg2: 杯子在下半部分，用原始视频按 mask 叠加
两个裁掉各自多余部分后紧密拼接。
"""
import cv2
import numpy as np
from pathlib import Path

def find_content_range(mask_dir, num_samples=10):
    masks = sorted(Path(mask_dir).glob('*.png'))
    step = max(1, len(masks) // num_samples)
    sampled = masks[::step][:num_samples]
    min_y, max_y = 99999, 0
    for mp in sampled:
        m = cv2.imread(str(mp), cv2.IMREAD_GRAYSCALE)
        ys = np.where(m > 0)[0]
        if len(ys) > 0:
            min_y = min(min_y, ys.min())
            max_y = max(max_y, ys.max())
    return min_y, max_y

def main():
    # Use ORIGINAL videos, not segmentation.mp4
    vid1 = 'data/live_photo/test2/IMG_20260904_184349.mp4'
    vid2 = 'data/live_photo/test2/IMG_20260904_184359.mp4'
    seg1_masks = 'data/live_photo/triptych_bottle/seg_1/masks'
    seg2_masks = 'data/live_photo/triptych_bottle/seg_2/masks'
    out = Path('data/live_photo/triptych_bottle/triptych_direct.mp4')

    y1_min, y1_max = find_content_range(seg1_masks)
    y2_min, y2_max = find_content_range(seg2_masks)
    print(f"seg1 content: [{y1_min}, {y1_max}] height={y1_max-y1_min}")
    print(f"seg2 content: [{y2_min}, {y2_max}] height={y2_max-y2_min}")

    cap1 = cv2.VideoCapture(vid1)
    cap2 = cv2.VideoCapture(vid2)
    n1 = int(cap1.get(cv2.CAP_PROP_FRAME_COUNT))
    n2 = int(cap2.get(cv2.CAP_PROP_FRAME_COUNT))
    n = min(n1, n2)
    w = int(cap1.get(cv2.CAP_PROP_FRAME_WIDTH))

    h1 = y1_max - y1_min
    h2 = y2_max - y2_min
    total_h = h1 + h2
    print(f"裁剪后: seg1={w}x{h1}, seg2={w}x{h2}, 总高={total_h}")

    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer = cv2.VideoWriter(str(out), fourcc, 27.45, (w, total_h))

    for fi in range(n):
        ret1, frame1 = cap1.read()
        ret2, frame2 = cap2.read()
        if not ret1 or not ret2:
            break

        # Load masks
        mask1_path = Path(seg1_masks) / f'{fi:04d}.png'
        mask2_path = Path(seg2_masks) / f'{fi:04d}.png'
        mask1 = cv2.imread(str(mask1_path), cv2.IMREAD_GRAYSCALE) if mask1_path.exists() else None
        mask2 = cv2.imread(str(mask2_path), cv2.IMREAD_GRAYSCALE) if mask2_path.exists() else None

        # Crop to content range — use ORIGINAL video frame, no checkerboard
        top = frame1[y1_min:y1_max, :]
        bot = frame2[y2_min:y2_max, :]

        canvas = np.vstack([top, bot])
        writer.write(canvas)

        if fi % 20 == 0:
            print(f"  Frame {fi}/{n}")

    writer.release()
    cap1.release()
    cap2.release()
    print(f"Done: {out} ({out.stat().st_size/1e6:.1f}MB), {w}x{total_h}")

if __name__ == '__main__':
    main()
