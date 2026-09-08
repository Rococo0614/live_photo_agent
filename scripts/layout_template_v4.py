#!/usr/bin/env python3
"""三联画布局模板 v4 — 修正 z-order（置顶顺序）。

布局：
  上层 = 瓶子原始视频帧 [0:c1]
  中层 = 静态景色图 + 瓶子跨越 + 杯子跨越
  下层 = 杯子原始视频帧 [c2:1920]

z-order（底→顶）：
  1. 杯子跨越部分（底层）
  2. 静态景色图（中间层）
  3. 瓶子跨越部分（顶层）
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

# 2. 计算各层高度 — 所有素材统一缩放比例
#    景色图 4080x3064, 视频帧 1440x1920
#    统一缩放比例 = max(W/img3_w, ...) 取景色图水平缩放
img3_tmp = cv2.imread(IMG3)
scenery_scale = W / img3_tmp.shape[1]  # 景色图水平缩放到1440px
# 视频帧也用同样比例: 1440px宽 → 1440px(不变), 1920px高 → 1920px(不变)
# 但景色图缩放后高度 = 3064 * scenery_scale = 2298px, 裁剪到640px
# 视频帧不需要缩放(本身就是1440px宽), 但跨越部分要按景色图比例
# 关键: 上层和中层都用原始视频帧像素, 不缩放 → 比例一致

h_top = c1
h_mid = H_TOTAL // 3
cup_keep_start = H_TOTAL * 2 // 3  # 1280, 杯子背景保留区起点
h_bot = H_TOTAL - cup_keep_start
total_h = h_top + h_mid + h_bot
print(f"\n上层(瓶子帧): [0:{c1}] h={h_top}")
print(f"中层(景色图+跨越): 1/3 h={h_mid}")
print(f"下层(杯子帧): [{cup_keep_start}:{H_TOTAL}] h={h_bot}")
print(f"总高度: {total_h}")

# 3. 读取素材帧
cap1 = cv2.VideoCapture(VID1)
cap2 = cv2.VideoCapture(VID2)
cap1.set(cv2.CAP_PROP_POS_FRAMES, 10)
cap2.set(cv2.CAP_PROP_POS_FRAMES, 10)
ret1, frame1 = cap1.read()
ret2, frame2 = cap2.read()
img3 = cv2.imread(IMG3)

# 4. 上下层 = 原始视频帧(主体自然包含, 不缩放)
#    上层 = 瓶子背景保留区 [0:c1]
#    下层 = 杯子背景保留区 [cup_keep_start:1920]
top_layer = frame1[0:c1, :]
bot_layer = frame2[cup_keep_start:, :]

# 5. 中层 z-order: 杯子跨越(底) → 景色图(中) → 瓶子跨越(顶)
#    关键修正: 中层视频跨越部分也用原始像素(不缩放), 与上下层一致
#    景色图 cover-crop 到 1440x640 (缩放比例独立, 因为是不同素材)
mid_h_actual = h_mid

# 分离 subject 区域 (去掉背景保留区)
# seg_1 (瓶子): subject在上半部分, 背景保留区在 [0:c1] → subject在 [c1:y1_max]
# seg_2 (杯子): subject在上1/3, 背景保留区在 [1280:1920] → subject在 [y2_min:640]
# 但需要动态检测 subject 真正范围
def find_subject_range(mask, keep_y_start, keep_y_end):
    """找出 subject 区域 (去掉背景保留区后的 mask 范围)"""
    subject_mask = mask.copy()
    subject_mask[keep_y_start:keep_y_end] = 0  # 去掉背景保留区
    ys = np.where(subject_mask > 127)[0]
    if len(ys) > 0:
        return ys.min(), ys.max()
    return 0, 0

# 瓶子 subject 范围 (背景保留区在 [0:c1])
b1_min, b1_max = find_subject_range(
    cv2.imread(str(masks1[10]), cv2.IMREAD_GRAYSCALE), 0, c1
)
print(f"瓶子 subject 范围: [{b1_min}, {b1_max}]")

# 杯子 subject 范围 (背景保留区在 [1280:1920], 即 h*2//3)
cup_keep_start = H_TOTAL * 2 // 3  # 1280
b2_min, b2_max = find_subject_range(
    cv2.imread(str(masks2[10]), cv2.IMREAD_GRAYSCALE), cup_keep_start, H_TOTAL
)
print(f"杯子 subject 范围: [{b2_min}, {b2_max}]")

# 杯子跨越到中层(底部): 只取 subject 边缘
cup_mask_raw = cv2.imread(str(masks2[10]), cv2.IMREAD_GRAYSCALE)
cup_subject_h = b2_max - b2_min
cup_span = max(30, min(cup_subject_h, mid_h_actual // 3))
# 从 subject 底部往上取 cup_span px (杯子顶部探入中层)
cup_in_mid = frame2[b2_min:b2_min + cup_span, :]
cup_mask_in_mid = feather_mask(cup_mask_raw[b2_min:b2_min + cup_span, :], 21)

# 瓶子跨越到中层(顶部): 只取 subject 边缘
bottle_mask_raw = cv2.imread(str(masks1[10]), cv2.IMREAD_GRAYSCALE)
bottle_subject_h = b1_max - b1_min
bottle_span = max(30, min(bottle_subject_h, mid_h_actual // 3))
# 从 subject 底部往上取 bottle_span px (瓶子底部探入中层)
bottle_in_mid = frame1[b1_max - bottle_span:b1_max, :]
bottle_mask_in_mid = feather_mask(bottle_mask_raw[b1_max - bottle_span:b1_max, :], 21)
print(f"瓶子跨越: {bottle_span}px (subject边缘)")
print(f"杯子跨越: {cup_span}px (subject边缘)")

# 景色图(中间层)
mid_bg = resize_crop_center(img3, W, mid_h_actual)

# 合成中层 — z-order: 底→顶
# 1. 杯子跨越(底层) — 放在中层底部
mid_canvas = np.zeros((mid_h_actual, W, 3), dtype=np.float32)
cup_offset = mid_h_actual - cup_span
mid_canvas[cup_offset:, :] = cup_in_mid.astype(np.float32) * cup_mask_in_mid[..., None]

# 2. 景色图(中间层) — 覆盖整个中层，alpha=1.0(不透明)
#    但杯子跨越部分已经放在底层，景色图直接覆盖会完全遮住杯子
#    所以景色图也用 alpha 合成，在杯子跨越区域让杯子透出
#    → 景色图 alpha = 1.0 - 杯子mask (杯子跨越的地方景色图透明)
cup_alpha_full = np.zeros((mid_h_actual, W), dtype=np.float32)
cup_alpha_full[cup_offset:, :] = cup_mask_in_mid
mid_canvas = mid_bg.astype(np.float32) * (1 - cup_alpha_full[..., None]) + mid_canvas * cup_alpha_full[..., None]

# 3. 瓶子跨越(顶层) — 放在中层顶部，覆盖景色图
if bottle_span > 0:
    mid_canvas[:bottle_span, :] = (
        bottle_in_mid.astype(np.float32) * bottle_mask_in_mid[..., None] +
        mid_canvas[:bottle_span, :] * (1 - bottle_mask_in_mid[..., None])
    )

mid_layer = np.clip(mid_canvas, 0, 255).astype(np.uint8)

# 6. 拼接三层
canvas = np.vstack([top_layer, mid_layer, bot_layer])
cv2.imwrite(str(OUT_DIR / 'template_v4_with_subject.png'), canvas)
print(f"\n带主体模板: {OUT_DIR / 'template_v4_with_subject.png'} ({canvas.shape})")

# 7. 布局示意图
diag = canvas.copy()
cv2.line(diag, (0, h_top), (W, h_top), (0, 0, 255), 3)
cv2.line(diag, (0, h_top + h_mid), (W, h_top + h_mid), (0, 0, 255), 3)
cv2.putText(diag, f'UPPER: bottle video [0:{c1}] h={h_top}',
            (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
cv2.putText(diag, f'MID z-order: cup(bottom) -> scenery -> bottle(top)',
            (20, h_top + 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
cv2.putText(diag, f'  bottle span={bottle_span}px(top), cup span={cup_span}px(bot)',
            (20, h_top + 60), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 2)
cv2.putText(diag, f'LOWER: cup video [{c2}:{H_TOTAL}] h={h_bot}',
            (20, total_h - 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
cv2.imwrite(str(OUT_DIR / 'template_v4_diagram.png'), diag)
print(f"布局示意图: {OUT_DIR / 'template_v4_diagram.png'}")

cap1.release()
cap2.release()
print(f"\n=== v4 总结 ===")
print(f"上层: 原始瓶子视频帧(主体自然包含)")
print(f"中层 z-order(底→顶):")
print(f"  1. 杯子跨越({cup_span}px) — 中层底部")
print(f"  2. 景色图 — 覆盖中层(杯子跨越区域透出)")
print(f"  3. 瓶子跨越({bottle_span}px) — 中层顶部(最顶层)")
print(f"下层: 原始杯子视频帧(主体自然包含)")
