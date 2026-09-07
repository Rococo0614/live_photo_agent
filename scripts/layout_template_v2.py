#!/usr/bin/env python3
"""三联画布局模板 v2 — 主体跨越两个背景层，形成交错穿插效果。

布局逻辑（修正版）：
  - 上层背景 = 瓶子主体中线以上的背景 [0 : c1]
  - 中层背景 = 固定1/3 (静态图/无主体素材)
  - 下层背景 = 杯子主体中线以下的背景 [c2 : 1920]
  
  关键：每个主体至少跨越两个背景层
  - 瓶子主体: 从上层背景延伸到中层背景 (主体中线以下部分落在中层背景上)
  - 杯子主体: 从中层背景延伸到下层背景 (主体中线以上部分落在中层背景上)
  - 中层背景上同时有瓶子底部+杯子顶部+静态图背景的交错
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


# ═══════════════════════════════════════════════════════════
# 1. 分析主体位置
# ═══════════════════════════════════════════════════════════
print("=== 主体位置分析 ===")
y1_min, y1_max, masks1 = get_mask_range(MASKS1)
c1 = (y1_min + y1_max) // 2
y2_min, y2_max, masks2 = get_mask_range(MASKS2)
c2 = (y2_min + y2_max) // 2

print(f"瓶子: mask y=[{y1_min}, {y1_max}], 中线={c1}")
print(f"  → 上层背景: [0:{c1}] 高度={c1}")
print(f"  → 瓶子主体跨越到中层: [{c1}:{y1_max}] 高度={y1_max - c1}")
print(f"杯子: mask y=[{y2_min}, {y2_max}], 中线={c2}")
print(f"  → 下层背景: [{c2}:{H_TOTAL}] 高度={H_TOTAL - c2}")
print(f"  → 杯子主体跨越到中层: [{y2_min}:{c2}] 高度={c2 - y2_min}")

# ═══════════════════════════════════════════════════════════
# 2. 计算各层背景高度
#    上层 = [0 : c1] = 瓶子背景
#    中层 = 固定1/3 = 静态图背景
#    下层 = [c2 : 1920] = 杯子背景
# ═══════════════════════════════════════════════════════════
h_top = c1                              # 上层背景高度
h_mid = H_TOTAL // 3                    # 中层背景高度 = 640
h_bot = H_TOTAL - c2                   # 下层背景高度
total_h = h_top + h_mid + h_bot
print(f"\n=== 各层背景高度 ===")
print(f"上层背景(瓶子): [0:{c1}] h={h_top}")
print(f"中层背景(静态图): 1/3 h={h_mid}")
print(f"下层背景(杯子): [{c2}:{H_TOTAL}] h={h_bot}")
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

# ═══════════════════════════════════════════════════════════
# 4. 构建背景画布 (三层背景拼接)
#    关键修正: 背景层中主体区域用静态图填充(剔除主体)
#    上层背景 = 瓶子帧[0:c1] 中瓶子主体区域 → 静态图填充
#    中层背景 = 静态图裁剪
#    下层背景 = 杯子帧[c2:1920] 中杯子主体区域 → 静态图填充
# ═══════════════════════════════════════════════════════════
mask1_raw = cv2.imread(str(masks1[10]), cv2.IMREAD_GRAYSCALE)
mask2_raw = cv2.imread(str(masks2[10]), cv2.IMREAD_GRAYSCALE)
m1_bool = mask1_raw > 127
m2_bool = mask2_raw > 127

# 上层背景: 瓶子帧[0:c1], 主体区域用静态图替换
top_bg = frame1[0:c1, :].copy()
top_replacement = resize_crop_center(img3, W, c1)
top_bg[m1_bool[:c1, :]] = top_replacement[m1_bool[:c1, :]]

# 中层背景: 静态图裁剪
mid_bg = resize_crop_center(img3, W, h_mid)

# 下层背景: 杯子帧[c2:1920], 主体区域用静态图替换
bot_bg = frame2[c2:, :].copy()
bot_replacement = resize_crop_center(img3, W, H_TOTAL - c2)
bot_bg[m2_bool[c2:, :]] = bot_replacement[m2_bool[c2:, :]]

background = np.vstack([top_bg, mid_bg, bot_bg])
print(f"\n背景画布(主体已剔除): {background.shape}")

# ═══════════════════════════════════════════════════════════
# 5. 将主体叠加到背景上 — 主体跨越两个背景层
#
#    瓶子主体 [0:y1_max]:
#      - [0:c1] 部分落在上层背景 (同源背景，自然融合)
#      - [c1:y1_max] 部分跨越到中层背景 (交错穿插效果!)
#
#    杯子主体 [y2_min:1920]:
#      - [y2_min:c2] 部分跨越到中层背景 (交错穿插效果!)
#      - [c2:1920] 部分落在下层背景 (同源背景，自然融合)
# ═══════════════════════════════════════════════════════════
mask1 = cv2.imread(str(masks1[10]), cv2.IMREAD_GRAYSCALE)
mask2 = cv2.imread(str(masks2[10]), cv2.IMREAD_GRAYSCALE)
m1 = feather_mask(mask1, 21)
m2 = feather_mask(mask2, 21)

total_h = background.shape[0]

# 构建与画布同尺寸的瓶子帧和杯子帧 (上下补黑)
frame1_canvas = np.zeros_like(background)
fit1 = min(H_TOTAL, total_h)
frame1_canvas[:fit1, :] = frame1[:fit1, :]  # 瓶子帧放在画布顶部对齐
frame2_canvas = np.zeros_like(background)
# 杯子帧需要偏移: 杯子帧底部对齐画布底部
offset2 = total_h - H_TOTAL
if offset2 >= 0:
    frame2_canvas[offset2:offset2 + H_TOTAL, :] = frame2
else:
    frame2_canvas[:total_h, :] = frame2[:total_h, :]

# mask 也需要放到画布坐标系上
m1_full = np.zeros((total_h, W), dtype=np.float32)
m1_full[:fit1, :] = m1[:fit1, :]
m2_full = np.zeros((total_h, W), dtype=np.float32)
if offset2 >= 0:
    m2_full[offset2:offset2 + H_TOTAL, :] = m2
else:
    m2_full[:total_h, :] = m2[:total_h, :]

# 合成: 背景 + 瓶子主体 + 杯子主体
canvas = background.astype(np.float32)
canvas = frame1_canvas.astype(np.float32) * m1_full[..., None] + canvas * (1 - m1_full[..., None])
canvas = frame2_canvas.astype(np.float32) * m2_full[..., None] + canvas * (1 - m2_full[..., None])
canvas = np.clip(canvas, 0, 255).astype(np.uint8)

cv2.imwrite(str(OUT_DIR / 'template_v2_with_subject.png'), canvas)
print(f"带主体模板(主体跨越背景): {OUT_DIR / 'template_v2_with_subject.png'} ({canvas.shape})")

# ═══════════════════════════════════════════════════════════
# 6. 布局示意图
# ═══════════════════════════════════════════════════════════
diag = canvas.copy()
# 背景层分界线
cv2.line(diag, (0, h_top), (W, h_top), (0, 0, 255), 3)
cv2.line(diag, (0, h_top + h_mid), (W, h_top + h_mid), (0, 0, 255), 3)
# 主体跨越区域标注
# 瓶子主体跨越到中层: 画布 [c1 : y1_max]
bottle_span = y1_max - c1
cv2.rectangle(diag, (0, h_top), (W, h_top + bottle_span), (0, 255, 0), 2)
cv2.putText(diag, f'BOTTLE spans into MID (+{bottle_span}px)',
            (20, h_top + 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
# 杯子主体跨越到中层: 画布 [h_top + h_mid - (c2 - y2_min) : h_top + h_mid]
cup_span = c2 - y2_min
cv2.rectangle(diag, (0, h_top + h_mid - cup_span), (W, h_top + h_mid), (255, 100, 0), 2)
cv2.putText(diag, f'CUP spans into MID (+{cup_span}px)',
            (20, h_top + h_mid - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 100, 0), 2)
# 背景层标注
cv2.putText(diag, f'UPPER BG (bottle) [0:{c1}] h={h_top}',
            (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
cv2.putText(diag, f'MID BG (static img) 1/3 h={h_mid}',
            (20, h_top + h_mid // 2), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
cv2.putText(diag, f'LOWER BG (cup) [{c2}:{H_TOTAL}] h={h_bot}',
            (20, total_h - 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)

cv2.imwrite(str(OUT_DIR / 'template_v2_diagram.png'), diag)
print(f"布局示意图: {OUT_DIR / 'template_v2_diagram.png'}")

cap1.release()
cap2.release()
print(f"\n=== 模板 v2 总结 ===")
print(f"画布: {W}x{total_h}")
print(f"上层背景(瓶子): [0:{h_top}] h={h_top}")
print(f"中层背景(静态图): [{h_top}:{h_top + h_mid}] h={h_mid}")
print(f"下层背景(杯子): [{h_top + h_mid}:{total_h}] h={h_bot}")
print(f"瓶子主体跨越: 上层背景 → 中层背景 ({bottle_span}px 延伸)")
print(f"杯子主体跨越: 中层背景 → 下层背景 ({cup_span}px 延伸)")
print(f"中层背景上: 瓶子底部 + 杯子顶部 + 静态图背景 交错穿插")
