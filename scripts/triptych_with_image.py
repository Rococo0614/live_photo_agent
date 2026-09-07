#!/usr/bin/env python3
"""三联画合成：上=视频1(瓶子), 中=静态图片, 下=视频2(杯子)。

用原始视频帧裁剪到 mask 内容范围，中间填充静态图片，
消灭所有 checkerboard。
"""
import argparse
import cv2
import numpy as np
from pathlib import Path


def find_content_range(mask_dir, num_samples=10):
    masks = sorted(Path(mask_dir).glob('*.png'))
    if not masks:
        raise FileNotFoundError(f"No masks in {mask_dir}")
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


def resize_crop_center(img, target_w, target_h):
    """Resize image to cover target_w x target_h, then center-crop."""
    h, w = img.shape[:2]
    scale = max(target_w / w, target_h / h)
    new_w, new_h = int(w * scale), int(h * scale)
    resized = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_AREA)
    x0 = (new_w - target_w) // 2
    y0 = (new_h - target_h) // 2
    return resized[y0:y0 + target_h, x0:x0 + target_w]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--vid1', default='data/live_photo/test2/IMG_20260904_184349.mp4')
    ap.add_argument('--vid2', default='data/live_photo/test2/IMG_20260904_184359.mp4')
    ap.add_argument('--image', default='data/live_photo/test2/IMG_20260710_093237.jpg')
    ap.add_argument('--masks1', default='data/live_photo/triptych_bottle/seg_1/masks')
    ap.add_argument('--masks2', default='data/live_photo/triptych_bottle/seg_2/masks')
    ap.add_argument('--middle_ratio', type=float, default=0.33,
                    help='Middle section height as fraction of total video height')
    ap.add_argument('--fps', type=float, default=27.45)
    ap.add_argument('--output', default='data/live_photo/triptych_bottle/triptych_with_image.mp4')
    args = ap.parse_args()

    y1_min, y1_max = find_content_range(args.masks1)
    y2_min, y2_max = find_content_range(args.masks2)
    print(f"seg1 content: [{y1_min}, {y1_max}] height={y1_max - y1_min}")
    print(f"seg2 content: [{y2_min}, {y2_max}] height={y2_max - y2_min}")

    cap1 = cv2.VideoCapture(args.vid1)
    cap2 = cv2.VideoCapture(args.vid2)
    n1 = int(cap1.get(cv2.CAP_PROP_FRAME_COUNT))
    n2 = int(cap2.get(cv2.CAP_PROP_FRAME_COUNT))
    n = min(n1, n2)
    w = int(cap1.get(cv2.CAP_PROP_FRAME_WIDTH))

    h1 = y1_max - y1_min
    h2 = y2_max - y2_min
    mid_h = int((h1 + h2) * args.middle_ratio)
    total_h = h1 + mid_h + h2
    print(f"Layout: top={w}x{h1}, mid={w}x{mid_h}, bot={w}x{h2}, total={w}x{total_h}")

    static_img = cv2.imread(args.image)
    if static_img is None:
        raise FileNotFoundError(f"Cannot read {args.image}")
    mid_frame = resize_crop_center(static_img, w, mid_h)
    print(f"Static image cropped to {mid_frame.shape}")

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer = cv2.VideoWriter(str(out), fourcc, args.fps, (w, total_h))

    for fi in range(n):
        ret1, frame1 = cap1.read()
        ret2, frame2 = cap2.read()
        if not ret1 or not ret2:
            break

        top = frame1[y1_min:y1_max, :]
        bot = frame2[y2_min:y2_max, :]

        canvas = np.vstack([top, mid_frame, bot])
        writer.write(canvas)

        if fi % 20 == 0:
            print(f"  Frame {fi}/{n}")

    writer.release()
    cap1.release()
    cap2.release()
    print(f"Done: {out} ({out.stat().st_size / 1e6:.1f}MB), {w}x{total_h}")


if __name__ == '__main__':
    main()
