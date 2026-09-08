#!/usr/bin/env python3
"""三联画布局 v5 — 以两个seg为主，背景适应。

两个seg分辨率相同(1440x1920)，以它们为主：
  上层 = 瓶子seg上1/3背景 [0:640]
  中层 = 景色图 resize 到 1440x640 (适应中间空间)
  下层 = 杯子seg下1/3背景 [1280:1920]

跨越部分用原始像素(不缩放)，z-order: 杯子(底) → 景色图(中) → 瓶子(顶)
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
H = 1920
THIRD = H // 3  # 640


def feather_mask(mask, blur_size=21):
    mask_f = mask.astype(np.float32) / 255.0
    return cv2.GaussianBlur(mask_f, (blur_size, blur_size), 0)


def find_subject_range(mask_dir, keep_start, keep_end, frame_idx=10):
    """找出 subject 区域 (去掉背景保留区后的 mask 范围)"""
    masks = sorted(Path(mask_dir).glob('*.png'))
    idx = min(frame_idx, len(masks) - 1)
    m = cv2.imread(str(masks[idx]), cv2.IMREAD_GRAYSCALE)
    subject_mask = m.copy()
    subject_mask[keep_start:keep_end] = 0
    ys = np.where(subject_mask > 127)[0]
    if len(ys) > 0:
        return ys.min(), ys.max(), masks
    return 0, 0, masks


# ═══════════════════════════════════════════════════════════
# 1. 分析主体位置
# ═══════════════════════════════════════════════════════════
print("=== 主体位置分析 ===")
# 瓶子: 背景保留区在上1/3 [0:640]
b1_min, b1_max, masks1 = find_subject_range(MASKS1, 0, THIRD)
print(f"瓶子 subject: [{b1_min}, {b1_max}]")

# 杯子: 背景保留区在下1/3 [1280:1920]
b2_min, b2_max, masks2 = find_subject_range(MASKS2, THIRD * 2, H)
print(f"杯子 subject: [{b2_min}, {b2_max}]")

# ═══════════════════════════════════════════════════════════
# 2. 布局: 两个seg拼一起, 中间1/3添加背景
#    上层 = 瓶子seg上1/3 [0:640] (原始分辨率)
#    中层 = 景色图 resize 到 1440x640 (适应中间空间)
#    下层 = 杯子seg下1/3 [1280:1920] (原始分辨率)
# ═══════════════════════════════════════════════════════════
h_top = THIRD      # 640
h_mid = THIRD      # 640
h_bot = THIRD      # 640
total_h = h_top + h_mid + h_bot  # 1920
print(f"\n上层(瓶子seg): [0:{THIRD}] h={h_top}")
print(f"中层(景色图): resize到 {W}x{h_mid}")
print(f"下层(杯子seg): [{THIRD*2}:{H}] h={h_bot}")
print(f"总高度: {total_h}")

# ═══════════════════════════════════════════════════════════
# 3. 读取素材帧
# ═══════════════════════════════════════════════════════════
cap1 = cv2.VideoCapture(VID1)
cap2 = cv2.VideoCapture(VID2)
cap1.set(cv2.CAP_PROP_POS_FRAMES, 10)
cap2.set(cv2.CAP_PROP_POS_FRAMES, 10)
ret1, frame1 = cap1.read()
ret2, frame2 = cap2.read()
img3 = cv2.imread(IMG3)
print(f"\n瓶子帧: {frame1.shape}")
print(f"杯子帧: {frame2.shape}")
print(f"景色图: {img3.shape}")

# ═══════════════════════════════════════════════════════════
# 4. 上下层 = 原始视频帧1/3 (不缩放, 主体自然包含)
# ═══════════════════════════════════════════════════════════
top_layer = frame1[0:THIRD, :]       # 瓶子seg上1/3
bot_layer = frame2[THIRD*2:, :]     # 杯子seg下1/3

# ═══════════════════════════════════════════════════════════
# 5. 中层 = 景色图 resize 适应 + 主体跨越(原始像素)
#    z-order: 杯子跨越(底) → 景色图(中) → 瓶子跨越(顶)
# ═══════════════════════════════════════════════════════════
# 景色图 resize 到 1440x640 (cover crop)
scale = max(W / img3.shape[1], h_mid / img3.shape[0])
new_w, new_h = int(img3.shape[1] * scale), int(img3.shape[0] * scale)
resized = cv2.resize(img3, (new_w, new_h), interpolation=cv2.INTER_AREA)
x0 = (new_w - W) // 2
y0 = (new_h - h_mid) // 2
mid_bg = resized[y0:y0 + h_mid, x0:x0 + W]
print(f"景色图 resize: {img3.shape} → {mid_bg.shape}")

# 杯子跨越到中层(底部): subject边缘, 原始像素
cup_mask_raw = cv2.imread(str(masks2[10]), cv2.IMREAD_GRAYSCALE)
cup_subject_h = b2_max - b2_min
cup_span = min(cup_subject_h, h_mid // 3)  # 不超过中层1/3
cup_span = max(cup_span, 30)
cup_in_mid = frame2[b2_min:b2_min + cup_span, :]
cup_mask_in_mid = feather_mask(cup_mask_raw[b2_min:b2_min + cup_span, :], 21)

# 瓶子跨越到中层(顶部): subject边缘, 原始像素
bottle_mask_raw = cv2.imread(str(masks1[10]), cv2.IMREAD_GRAYSCALE)
bottle_subject_h = b1_max - b1_min
bottle_span = min(bottle_subject_h, h_mid // 3)
bottle_span = max(bottle_span, 30)
bottle_in_mid = frame1[b1_max - bottle_span:b1_max, :]
bottle_mask_in_mid = feather_mask(bottle_mask_raw[b1_max - bottle_span:b1_max, :], 21)

print(f"瓶子跨越: {bottle_span}px (原始像素)")
print(f"杯子跨越: {cup_span}px (原始像素)")

# 合成中层 — z-order: 底→顶
mid_canvas = np.zeros((h_mid, W, 3), dtype=np.float32)

# 1. 杯子跨越(底层) — 放在中层底部
cup_offset = h_mid - cup_span
mid_canvas[cup_offset:, :] = cup_in_mid.astype(np.float32) * cup_mask_in_mid[..., None]

# 2. 景色图(中间层) — 覆盖中层, 杯子跨越区域透出
cup_alpha_full = np.zeros((h_mid, W), dtype=np.float32)
cup_alpha_full[cup_offset:, :] = cup_mask_in_mid
mid_canvas = mid_bg.astype(np.float32) * (1 - cup_alpha_full[..., None]) + mid_canvas * cup_alpha_full[..., None]

# 3. 瓶子跨越(顶层) — 放在中层顶部, 覆盖景色图
if bottle_span > 0:
    mid_canvas[:bottle_span, :] = (
        bottle_in_mid.astype(np.float32) * bottle_mask_in_mid[..., None] +
        mid_canvas[:bottle_span, :] * (1 - bottle_mask_in_mid[..., None])
    )

mid_layer = np.clip(mid_canvas, 0, 255).astype(np.uint8)

# ═══════════════════════════════════════════════════════════
# 6. 拼接三层
# ═══════════════════════════════════════════════════════════
canvas = np.vstack([top_layer, mid_layer, bot_layer])
cv2.imwrite(str(OUT_DIR / 'template_v5_with_subject.png'), canvas)
print(f"\n带主体模板: {OUT_DIR / 'template_v5_with_subject.png'} ({canvas.shape})")

# ═══════════════════════════════════════════════════════════
# 7. 布局示意图
# ═══════════════════════════════════════════════════════════
diag = canvas.copy()
cv2.line(diag, (0, h_top), (W, h_top), (0, 0, 255), 3)
cv2.line(diag, (0, h_top + h_mid), (W, h_top + h_mid), (0, 0, 255), 3)
cv2.putText(diag, f'UPPER: bottle seg [0:{THIRD}] h={h_top}',
            (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
cv2.putText(diag, f'MID: scenery + bottle({bottle_span}px) + cup({cup_span}px)',
            (20, h_top + 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
cv2.putText(diag, f'LOWER: cup seg [{THIRD*2}:{H}] h={h_bot}',
            (20, total_h - 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
cv2.imwrite(str(OUT_DIR / 'template_v5_diagram.png'), diag)
print(f"布局示意图: {OUT_DIR / 'template_v5_diagram.png'}")

cap1.release()
cap2.release()
print(f"\n=== v5 总结 ===")
print(f"画布: {W}x{total_h} (两个seg原始分辨率)")
print(f"上层: 瓶子seg上1/3 (原始分辨率)")
print(f"中层: 景色图resize适应 + 主体跨越(原始像素)")
print(f"下层: 杯子seg下1/3 (原始分辨率)")
print(f"z-order: 杯子(底) → 景色图(中) → 瓶子(顶)")
