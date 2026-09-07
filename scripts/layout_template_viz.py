#!/usr/bin/env python3
"""三联画布局模板可视化 — 展示对三个素材合成布局的理解。

布局逻辑：
  上层 = 瓶子(主体在上半部分) → 保留主体中线以上的背景
  中层 = 静态图(无主体检测) → 固定1/3
  下层 = 杯子(主体在下半部分) → 保留主体中线以下的背景

三个素材的重心物体不碰上，各自占据画面位置。
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


def get_mask_center_y(mask_dir):
    masks = sorted(Path(mask_dir).glob('*.png'))
    centers = []
    for mp in masks[:10]:
        m = cv2.imread(str(mp), cv2.IMREAD_GRAYSCALE)
        ys = np.where(m > 127)[0]
        if len(ys) > 0:
            centers.append((ys.min() + ys.max()) / 2)
    return int(np.median(centers)), masks


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
c1, masks1 = get_mask_center_y(MASKS1)
c2, masks2 = get_mask_center_y(MASKS2)
print(f"素材1(瓶子): 主体中心y={c1}/1920 → 上层(主体在上半部分)")
print(f"素材3(静态图): 无主体检测 → 中层(固定1/3)")
print(f"素材2(杯子): 主体中心y={c2}/1920 → 下层(主体在下半部分)")

# ═══════════════════════════════════════════════════════════
# 2. 计算各层高度
#    上层 = 瓶子主体中线以上的背景
#    中层 = 固定1/3
#    下层 = 杯子主体中线以下的背景
# ═══════════════════════════════════════════════════════════
h_top = c1                              # 上层高度 = 瓶子主体中线
h_mid = H_TOTAL // 3                    # 中层高度 = 固定1/3 = 640
h_bot = H_TOTAL - c2                    # 下层高度 = 1920 - 杯子中线
total = h_top + h_mid + h_bot
print(f"\n=== 各层高度 ===")
print(f"上层(瓶子背景): y=[0, {c1}] → 高度={h_top}")
print(f"中层(静态图):   固定1/3 → 高度={h_mid}")
print(f"下层(杯子背景): y=[{c2}, {H_TOTAL}] → 高度={h_bot}")
print(f"总高度: {total}")

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
# 4. 提取各层背景区域
#    上层: 瓶子帧 [0:c1] (主体中线以上的背景)
#    中层: 静态图裁剪到 [h_top : h_top+h_mid]
#    下层: 杯子帧 [c2:] (主体中线以下的背景)
# ═══════════════════════════════════════════════════════════
top_bg = frame1[0:c1, :]                        # 瓶子帧上半部分背景
mid_bg = resize_crop_center(img3, W, h_mid)      # 静态图裁剪中层
bot_bg = frame2[c2:, :]                          # 杯子帧下半部分背景

print(f"\n=== 各层背景尺寸 ===")
print(f"上层: {top_bg.shape}")
print(f"中层: {mid_bg.shape}")
print(f"下层: {bot_bg.shape}")

# ═══════════════════════════════════════════════════════════
# 5. 合成模板预览图 (静态)
# ═══════════════════════════════════════════════════════════
canvas_static = np.vstack([top_bg, mid_bg, bot_bg])
cv2.imwrite(str(OUT_DIR / 'template_layout_static.png'), canvas_static)
print(f"\n静态模板已保存: {OUT_DIR / 'template_layout_static.png'} ({canvas_static.shape})")

# ═══════════════════════════════════════════════════════════
# 6. 合成带主体的完整模板 (主体通过mask叠加到背景上)
# ═══════════════════════════════════════════════════════════
mask1 = cv2.imread(str(masks1[10]), cv2.IMREAD_GRAYSCALE)
mask2 = cv2.imread(str(masks2[10]), cv2.IMREAD_GRAYSCALE)
m1 = feather_mask(mask1, 21)
m2 = feather_mask(mask2, 21)

# 在完整帧上叠加主体，然后提取各层
canvas_full = frame1.astype(np.float32)
canvas_full = frame2.astype(np.float32) * m2[..., None] + canvas_full * (1 - m2[..., None])
canvas_full = frame1.astype(np.float32) * m1[..., None] + canvas_full * (1 - m1[..., None])
canvas_full = np.clip(canvas_full, 0, 255).astype(np.uint8)

# 提取各层 (上层=瓶子主体+背景[0:c1], 下层=杯子主体+背景[c2:])
top_layer = canvas_full[0:c1, :]
mid_layer = mid_bg
bot_layer = canvas_full[c2:, :]

canvas_with_subject = np.vstack([top_layer, mid_layer, bot_layer])
cv2.imwrite(str(OUT_DIR / 'template_layout_with_subject.png'), canvas_with_subject)
print(f"带主体模板已保存: {OUT_DIR / 'template_layout_with_subject.png'} ({canvas_with_subject.shape})")

# ═══════════════ detection ═════════════════════════════════════════════════════════════
# 7. 绘制布局示意图 (标注各层)
# ═════════════════════════════════════════════════════════════════════════════════════════
diag = canvas_with_subject.copy()
# 分界线
cv2.line(diag, (0, h_top), (W, h_top), (0, 0, 255), 3)
cv2.line(diag, (0, h_top + h_mid), (W, h_top + h_mid), (0, 0, 255), 3)
# 标注
cv2.putText(diag, f'UPPER: bottle bg [0:{c1}] h={h_top}', (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2)
cv2.putText(diag, f'MID: static img (no subject) 1/3 h={h_mid}', (20, h_top + 40), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2)
cv2.putText(diag, f'LOWER: cup bg [{c2}:{H_TOTAL}] h={h_bot}', (20, h_top + h_mid + 40), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2)
cv2.imwrite(str(OUT_DIR / 'template_layout_diagram.png'), diag)
print(f"布局示意图已保存: {OUT_DIR / 'template_layout_diagram.png'}")

cap1.release()
cap2.release()
print(f"\n=== 模板总结 ===")
print(f"画布: {W}x{total}")
print(f"上层(瓶子): 背景+主体 占据 y=[0, {h_top}]")
print(f"中层(静态图): 固定1/3 占据 y=[{h_top}, {h_top + h_mid}]")
print(f"下层(杯子): 背景+主体 占据 y=[{h_top + h_mid}, {total}]")
print(f"三个重心物体不碰上，各自占据画面位置")
