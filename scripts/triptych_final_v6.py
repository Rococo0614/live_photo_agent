#!/usr/bin/env python3
"""三联画最终合成 v6 — 先拼背景，再叠加主体。

背景拼接 (1440x1920):
  上1/3 = bottle_bg.mp4 (640px)
  中1/3 = 景色图 resize 到 1440x640
  下1/3 = cup_bg.mp4 (640px)

主体叠加 (z-order 底→顶):
  1. cup_subject (底层)
  2. 景色图 (中间层, 已在背景中)
  3. bottle_subject (顶层)
"""
import cv2
import numpy as np
from pathlib import Path

OUT_DIR = Path('data/live_photo/triptych_bottle')

VID_BG1 = OUT_DIR / 'bottle_bg.mp4'
VID_BG2 = OUT_DIR / 'cup_bg.mp4'
VID_SUB1 = OUT_DIR / 'bottle_subject.mp4'
VID_SUB2 = OUT_DIR / 'cup_subject.mp4'
VID_ALPHA1 = OUT_DIR / 'bottle_subject_alpha.mp4'
VID_ALPHA2 = OUT_DIR / 'cup_subject_alpha.mp4'
IMG_SCENERY = 'data/live_photo/test2/IMG_20260710_093237.jpg'

W = 1440
H = 1920
THIRD = H // 3  # 640


def feather_mask(mask, blur_size=21):
    mask_f = mask.astype(np.float32) / 255.0
    return cv2.GaussianBlur(mask_f, (blur_size, blur_size), 0)


def resize_crop_center(img, target_w, target_h):
    h, w = img.shape[:2]
    scale = max(target_w / w, target_h / h)
    new_w, new_h = int(w * scale), int(h * scale)
    resized = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_AREA)
    x0 = (new_w - target_w) // 2
    y0 = (new_h - target_h) // 2
    return resized[y0:y0 + target_h, x0:x0 + target_w]


# ═══════════════════════════════════════════════════════════
# 1. 打开背景视频 + 主体视频
# ═══════════════════════════════════════════════════════════
cap_bg1 = cv2.VideoCapture(str(VID_BG1))
cap_bg2 = cv2.VideoCapture(str(VID_BG2))
cap_sub1 = cv2.VideoCapture(str(VID_SUB1))
cap_sub2 = cv2.VideoCapture(str(VID_SUB2))
cap_alpha1 = cv2.VideoCapture(str(VID_ALPHA1))
cap_alpha2 = cv2.VideoCapture(str(VID_ALPHA2))

n1 = int(cap_bg1.get(cv2.CAP_PROP_FRAME_COUNT))
n2 = int(cap_bg2.get(cv2.CAP_PROP_FRAME_COUNT))
n = min(n1, n2)
fps = cap_bg1.get(cv2.CAP_PROP_FPS)
print(f"bottle_bg: {n1}帧, cup_bg: {n2}帧, 使用{n}帧, {fps:.2f}fps")

# ═══════════════════════════════════════════════════════════
# 2. 准备景色图 (resize到1440x640)
# ═══════════════════════════════════════════════════════════
img3 = cv2.imread(IMG_SCENERY)
mid_bg = resize_crop_center(img3, W, THIRD)
print(f"景色图: {img3.shape} → {mid_bg.shape}")

# ═══════════════════════════════════════════════════════════
# 3. 合成
# ═══════════════════════════════════════════════════════════
out_path = OUT_DIR / 'triptych_v6_final.mp4'
fourcc = cv2.VideoWriter_fourcc(*'mp4v')
writer = cv2.VideoWriter(str(out_path), fourcc, fps, (W, H))

for fi in range(n):
    # 读取背景
    ret1, bg1 = cap_bg1.read()  # 瓶子背景 1440x640
    ret2, bg2 = cap_bg2.read()  # 杯子背景 1440x640
    if not ret1 or not ret2:
        break

    # 1. 拼接背景: 上1/3 + 中1/3(景色图) + 下1/3
    canvas = np.vstack([bg1, mid_bg, bg2])  # 1920x1440

    # 读取主体 + alpha
    ret_s1, sub1 = cap_sub1.read()  # 瓶子主体 1440x1920
    ret_s2, sub2 = cap_sub2.read()  # 杯子主体 1440x1920
    ret_a1, alpha1 = cap_alpha1.read()  # 瓶子alpha
    ret_a2, alpha2 = cap_alpha2.read()  # 杯子alpha

    # 2. 叠加主体 (z-order: 杯子底 → 瓶子顶)
    canvas_f = canvas.astype(np.float32)

    if ret_s2 and ret_a2:
        a2 = feather_mask(cv2.cvtColor(alpha2, cv2.COLOR_BGR2GRAY), 21)
        canvas_f = sub2.astype(np.float32) * a2[..., None] + canvas_f * (1 - a2[..., None])

    if ret_s1 and ret_a1:
        a1 = feather_mask(cv2.cvtColor(alpha1, cv2.COLOR_BGR2GRAY), 21)
        canvas_f = sub1.astype(np.float32) * a1[..., None] + canvas_f * (1 - a1[..., None])

    canvas = np.clip(canvas_f, 0, 255).astype(np.uint8)
    writer.write(canvas)

    if fi % 20 == 0:
        print(f"  帧 {fi}/{n}")

writer.release()
cap_bg1.release()
cap_bg2.release()
cap_sub1.release()
cap_sub2.release()
cap_alpha1.release()
cap_alpha2.release()

print(f"\n=== 完成 ===")
print(f"输出: {out_path} ({out_path.stat().st_size/1e6:.1f}MB)")
print(f"分辨率: {W}x{H}")
print(f"布局: bottle_bg(640) + scenery(640) + cup_bg(640) = 1920")
print(f"z-order: 杯子(底) → 景色图(中) → 瓶子(顶)")
