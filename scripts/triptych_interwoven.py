#!/usr/bin/env python3
"""三联画合成 v2：静态图片做背景，瓶子+杯子视频通过 mask 叠加穿插其中。

布局：
  - 静态图片裁剪到目标画布尺寸作为背景
  - seg1(瓶子)视频帧按 mask 叠加到上半部分
  - seg2(杯子)视频帧按 mask 叠加到下半部分
  - 瓶子与杯子的轮廓自然穿插在图片背景中
"""
import argparse
import cv2
import numpy as np
from pathlib import Path


def load_masks(mask_dir, frame_idx):
    """加载指定帧的 mask。"""
    mask_path = Path(mask_dir) / f'{frame_idx:04d}.png'
    if not mask_path.exists():
        # 找最近的 mask
        masks = sorted(Path(mask_dir).glob('*.png'))
        idx = min(frame_idx, len(masks) - 1)
        mask_path = masks[idx]
    mask = cv2.imread(str(mask_path), cv2.IMREAD_GRAYSCALE)
    return mask


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


def feather_mask(mask, blur_size=15):
    """对 mask 边缘做羽化，让叠加更自然。"""
    mask_f = mask.astype(np.float32) / 255.0
    mask_blurred = cv2.GaussianBlur(mask_f, (blur_size, blur_size), 0)
    return mask_blurred


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--vid1', default='data/live_photo/test2/IMG_20260904_184349.mp4')
    ap.add_argument('--vid2', default='data/live_photo/test2/IMG_20260904_184359.mp4')
    ap.add_argument('--image', default='data/live_photo/test2/IMG_20260710_093237.jpg')
    ap.add_argument('--masks1', default='data/live_photo/triptych_bottle/seg_1/masks')
    ap.add_argument('--masks2', default='data/live_photo/triptych_bottle/seg_2/masks')
    ap.add_argument('--feather', type=int, default=21, help='Mask feather blur size')
    ap.add_argument('--fps', type=float, default=27.45)
    ap.add_argument('--output', default='data/live_photo/triptych_bottle/triptych_interwoven.mp4')
    args = ap.parse_args()

    y1_min, y1_max = find_content_range(args.masks1)
    y2_min, y2_max = find_content_range(args.masks2)
    print(f"seg1(bottle) mask y-range: [{y1_min}, {y1_max}]")
    print(f"seg2(cup)    mask y-range: [{y2_min}, {y2_max}]")

    cap1 = cv2.VideoCapture(args.vid1)
    cap2 = cv2.VideoCapture(args.vid2)
    n1 = int(cap1.get(cv2.CAP_PROP_FRAME_COUNT))
    n2 = int(cap2.get(cv2.CAP_PROP_FRAME_COUNT))
    w = int(cap1.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap1.get(cv2.CAP_PROP_FRAME_HEIGHT))
    n = min(n1, n2)
    print(f"Video: {w}x{h}, frames: vid1={n1}, vid2={n2}, using={n}")

    # 静态图片做背景
    static_img = cv2.imread(args.image)
    if static_img is None:
        raise FileNotFoundError(f"Cannot read {args.image}")
    bg = resize_crop_center(static_img, w, h)
    print(f"Background: {bg.shape}")

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer = cv2.VideoWriter(str(out), fourcc, args.fps, (w, h))

    for fi in range(n):
        ret1, frame1 = cap1.read()
        ret2, frame2 = cap2.read()
        if not ret1 or not ret2:
            break

        # 加载对应帧的 mask
        mask1 = load_masks(args.masks1, fi)
        mask2 = load_masks(args.masks2, fi)

        # 羽化 mask
        m1 = feather_mask(mask1, args.feather)
        m2 = feather_mask(mask2, args.feather)

        # 合成：背景 + 瓶子 + 杯子
        canvas = bg.astype(np.float32)
        # 叠加瓶子 (seg1)
        canvas = frame1.astype(np.float32) * m1[..., None] + canvas * (1 - m1[..., None])
        # 叠加杯子 (seg2)
        canvas = frame2.astype(np.float32) * m2[..., None] + canvas * (1 - m2[..., None])

        canvas = np.clip(canvas, 0, 255).astype(np.uint8)
        writer.write(canvas)

        if fi % 20 == 0:
            print(f"  Frame {fi}/{n}")

    writer.release()
    cap1.release()
    cap2.release()
    print(f"Done: {out} ({out.stat().st_size / 1e6:.1f}MB), {w}x{h}")


if __name__ == '__main__':
    main()
