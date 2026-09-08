#!/usr/bin/env python3
"""拆分seg视频: 把每个seg拆成单独的背景1/3视频 + 主体mp4。

seg_1(瓶子): 上1/3背景 [0:640] + 瓶子主体(mask区域)
seg_2(杯子): 下1/3背景 [1280:1920] + 杯子主体(mask区域)
"""
import cv2
import numpy as np
from pathlib import Path

OUT_DIR = Path('data/live_photo/triptych_bottle')
THIRD = 1920 // 3  # 640


def feather_mask(mask, blur_size=21):
    mask_f = mask.astype(np.float32) / 255.0
    return cv2.GaussianBlur(mask_f, (blur_size, blur_size), 0)


def split_seg(video_path, mask_dir, output_prefix, bg_range):
    """把seg拆成背景1/3视频 + 主体mp4。

    bg_range: (start, end) 背景保留区域
    """
    cap = cv2.VideoCapture(str(video_path))
    n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    masks = sorted(Path(mask_dir).glob('*.png'))

    bg_start, bg_end = bg_range
    bg_h = bg_end - bg_start
    print(f"\n=== {output_prefix} ===")
    print(f"视频: {w}x{h}, {n}帧, {fps:.2f}fps")
    print(f"背景区域: [{bg_start}:{bg_end}] h={bg_h}")
    print(f"mask帧数: {len(masks)}")

    # 输出路径
    bg_path = OUT_DIR / f"{output_prefix}_bg.mp4"
    sub_path = OUT_DIR / f"{output_prefix}_subject.mp4"
    sub_alpha_path = OUT_DIR / f"{output_prefix}_subject_alpha.mp4"

    fourcc = cv2.VideoWriter_fourcc(*'mp4v')
    writer_bg = cv2.VideoWriter(str(bg_path), fourcc, fps, (w, bg_h))
    writer_sub = cv2.VideoWriter(str(sub_path), fourcc, fps, (w, h))
    writer_alpha = cv2.VideoWriter(str(sub_alpha_path), fourcc, fps, (w, h))

    for fi in range(n):
        ret, frame = cap.read()
        if not ret:
            break

        # 加载mask
        if fi < len(masks):
            mask = cv2.imread(str(masks[fi]), cv2.IMREAD_GRAYSCALE)
        else:
            mask = cv2.imread(str(masks[-1]), cv2.IMREAD_GRAYSCALE)

        # 1. 背景1/3视频 (直接裁剪背景区域)
        bg_frame = frame[bg_start:bg_end, :]
        writer_bg.write(bg_frame)

        # 2. 主体mp4 (mask区域, 背景透明/黑)
        #    用mask提取主体, 背景置黑
        m_f = feather_mask(mask, 3)  # 轻微羽化
        subject_frame = (frame.astype(np.float32) * m_f[..., None]).astype(np.uint8)
        writer_sub.write(subject_frame)

        # 3. alpha mask视频 (主体alpha通道)
        alpha_vis = np.stack([mask, mask, mask], axis=2)
        writer_alpha.write(alpha_vis)

        if fi % 20 == 0:
            print(f"  帧 {fi}/{n}")

    writer_bg.release()
    writer_sub.release()
    writer_alpha.release()
    cap.release()

    print(f"  背景: {bg_path} ({bg_path.stat().st_size/1e6:.1f}MB)")
    print(f"  主体: {sub_path} ({sub_path.stat().st_size/1e6:.1f}MB)")
    print(f"  Alpha: {sub_alpha_path} ({sub_alpha_path.stat().st_size/1e6:.1f}MB)")


# seg_1(瓶子): 上1/3背景 [0:640]
split_seg(
    video_path='data/live_photo/test2/IMG_20260904_184349.mp4',
    mask_dir='data/live_photo/triptych_bottle/seg_1/masks',
    output_prefix='bottle',
    bg_range=(0, THIRD),
)

# seg_2(杯子): 下1/3背景 [1280:1920]
split_seg(
    video_path='data/live_photo/test2/IMG_20260904_184359.mp4',
    mask_dir='data/live_photo/triptych_bottle/seg_2/masks',
    output_prefix='cup',
    bg_range=(THIRD * 2, 1920),
)

print("\n=== 拆分完成 ===")
print("输出:")
for f in sorted(OUT_DIR.glob('*_bg.mp4')):
    print(f"  {f.name} ({f.stat().st_size/1e6:.1f}MB)")
for f in sorted(OUT_DIR.glob('*_subject*.mp4')):
    print(f"  {f.name} ({f.stat().st_size/1e6:.1f}MB)")
