#!/usr/bin/env python3
"""三联画布局模板 v3 — 主体跨越中层背景，上下层用原始视频帧。

布局逻辑：
  上层 = 瓶子原始视频帧 [0:c1] (主体自然包含，无需合成)
  中层 = 静态图背景 + 瓶子主体跨越部分 + 杯子主体跨越部分 (交错穿插)
  下层 = 杯子原始视频帧 [c2:1920] (主体自然包含，无需合成)
"""
import cv2
import numpy as np
from pathlib import Path

OUT_DIR = Path('data/live_photo/triptych_bottle')
OUT_DIR.mkdir(parents=True, exist_ok=True)

VID1 = 'data/live_photo/test2/IMG_20260904_184349.mp4'
VID2 = 'data/live_photo/test2/IMG_20260904_184359.mp4'
IMG3 = 'data/live_photo/test2/IMG_20260710_093237.jpg'
MASKS1 = 'data/live_photo/triptych_bottle/seg_1/masks'
MASKS2 = 'data/live_photo/triptych_bottle/seg_2/masks'

W = 1440
H_TOTAL = 1920


def get_mask_range(mask_dir, frame_idx=10):
    masks = sorted(Path(mask_dir).glob('*.png'))
    idx = min(frame_idx, len(masks) - 1)
    m = cv2.imread(str(masks[idx]), cv2.IMREAD_GRAYSCALE)
    ys = np.where(m > 127)[0]
    return ys.min(), ys.max(), masks


def resize_crop_center(img, target_w, target_h):
    h, w = img.shape[:2]
    scale = max(target_w / w, target_h / h)
    new_w, new_h = int(w * scale), int(h * scale)
    resized = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_AREA)
    x0 = (new_w - target_w) // 2
    y0 = (new_h - target_h) // 2
    return resized[y0:y0 + target_h, x0:x0 + target_w]


def feather_mask(mask, blur_size=21):
    mask_f = mask.astype(np.float32) / 255.0
    return cv2.GaussianBlur(mask_f, (blur_size, blur_size), 0)


# 1. 分析主体位置
print("=== 主体位置分析 ===")
y1_min, y1_max, masks1 = get_mask_range(MASKS1)
c1 = (y1_min + y1_max) // 2
y2_min, y2_max, masks2 = get_mask_range(MASKS2)
c2 = (y2_min + y2_max) // 2
print(f"瓶子: mask y=[{y1_min}, {y1_max}], 中线={c1}")
print(f"杯子: mask y=[{y2_min}, {y2_max}], 中线={c2}")

# 2. 计算各层高度
h_top = c1
h_mid = H_TOTAL // 3
h_bot = H_TOTAL - c2
total_h = h_top + h_mid + h_bot
print(f"\n上层(瓶子帧): [0:{c1}] h={h_top}")
print(f"中层(静态图): 1/3 h={h_mid}")
print(f"下层(杯子帧): [{c2}:{H_TOTAL}] h={h_bot}")
print(f"总高度: {total_h}")

# 3. 读取素材帧
cap1 = cv2.VideoCapture(VID1)
cap2 = cv2.VideoCapture(VID2)
cap1.set(cv2.CAP_PROP_POS_FRAMES, 10)
cap2.set(cv2.CAP_PROP_POS_FRAMES, 10)
ret1, frame1 = cap1.read()
ret2, frame2 = cap2.read()
img3 = cv2.imread(IMG3)

# 4. 上下层 = 原始视频帧(主体自然包含)
top_layer = frame1[0:c1, :]
bot_layer = frame2[c2:, :]

# 5. 中层 = 静态图背景 + 瓶子跨越部分 + 杯子跨越部分
mid_bg = resize_crop_center(img3, W, h_mid)

# 瓶子跨越到中层的部分: 原始帧 [c1 : y1_max] (如果 y1_max > c1)
bottle_span = min(y1_max - c1, h_mid)
bottle_in_mid = frame1[c1:c1 + bottle_span, :]
bottle_mask_in_mid = feather_mask(
    cv2.imread(str(masks1[10]), cv2.IMREAD_GRAYSCALE)[c1:c1 + bottle_span, :], 21
)

# 杯子跨越到中层的部分: 原始帧 [y2_min : c2] (放在中层底部)
cup_span = min(c2 - y2_min, h_mid)
cup_in_mid = frame2[y2_min:y2_min + cup_span, :]
cup_mask_in_mid = feather_mask(
    cv2.imread(str(masks2[10]), cv2.IMREAD_GRAYSCALE)[y2_min:y2_min + cup_span, :], 21
)

# 合成中层: 静态图背景 + 瓶子跨越(顶部) + 杯子跨越(底部)
mid_canvas = mid_bg.astype(np.float32)
if bottle_span > 0:
    mid_canvas[:bottle_span, :] = (
        bottle_in_mid.astype(np.float32) * bottle_mask_in_mid[..., None] +
        mid_canvas[:bottle_span, :] * (1 - bottle_mask_in_mid[..., None])
    )
if cup_span > 0:
    mid_offset = h_mid - cup_span
    mid_canvas[mid_offset:, :] = (
        cup_in_mid.astype(np.float32) * cup_mask_in_mid[..., None] +
        mid_canvas[mid_offset:, :] * (1 - cup_mask_in_mid[..., None])
    )
mid_layer = np.clip(mid_canvas, 0, 255).astype(np.uint8)

# 6. 拼接三层
canvas = np.vstack([top_layer, mid_layer, bot_layer])
cv2.imwrite(str(OUT_DIR / 'template_v3_with_subject.png'), canvas)
print(f"\n带主体模板: {OUT_DIR / 'template_v3_with_subject.png'} ({canvas.shape})")

# 7. 布局示意图
diag = canvas.copy()
cv2.line(diag, (0, h_top), (W, h_top), (0, 0, 255), 3)
cv2.line(diag, (0, h_top + h_mid), (W, h_top + h_mid), (0, 0, 255), 3)
cv2.putText(diag, f'UPPER: bottle video frame [0:{c1}] h={h_top}',
            (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
cv2.putText(diag, f'MID: static img + bottle span({bottle_span}px) + cup span({cup_span}px)',
            (20, h_top + 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
cv2.putText(diag, f'LOWER: cup video frame [{c2}:{H_TOTAL}] h={h_bot}',
            (20, total_h - 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
cv2.imwrite(str(OUT_DIR / 'template_v3_diagram.png'), diag)
print(f"布局示意图: {OUT_DIR / 'template_v3_diagram.png'}")

cap1.release()
cap2.release()
print(f"\n=== 总结 ===")
print(f"上层: 原始瓶子视频帧(主体自然包含)")
print(f"中层: 静态图背景 + 瓶子底部跨越({bottle_span}px) + 杯子顶部跨越({cup_span}px)")
print(f"下层: 原始杯子视频帧(主体自然包含)")
print(f"→ 主体跨越中层背景，形成交错穿插效果")
