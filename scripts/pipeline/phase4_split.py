#!/usr/bin/env python3
"""Phase 4: 拆分

把每个素材拆成:
  - bg_{id}.mp4: 背景strip视频 (根据 bg_region 裁剪的1/3)
  - subject_{id}.mp4: 主体视频 (mask区域, 背景透明)
  - alpha_{id}.mp4: alpha mask视频
  - split_{id}.mp4: 三列并排可视化

背景素材: 整个视频作为 bg_{id}.mp4
"""
import json
from pathlib import Path

import cv2
import numpy as np


class Phase4Splitter:
    def __init__(self, output_dir: Path, canvas_w=1440, canvas_h=1920):
        self.output_dir = Path(output_dir)
        self.canvas_w = canvas_w
        self.canvas_h = canvas_h

    def run(self, manifest: dict, layout: dict):
        """拆分每个素材"""
        self.output_dir.mkdir(parents=True, exist_ok=True)

        assets = {a["id"]: a for a in manifest["assets"]}
        placements = layout["placements"]

        for p in placements:
            asset = assets[p["id"]]
            video_path = asset.get("video_path")
            jpg_path = asset.get("jpg_path")

            if p["type"] == "subject":
                self._split_subject(asset, p, video_path)
            else:
                self._split_background(asset, p, video_path, jpg_path)

    def _get_bg_range(self, bg_region, frame_h):
        """根据 bg_region 返回 (bg_start, bg_end)"""
        if bg_region == "top_third":
            return 0, frame_h // 3
        elif bg_region == "bottom_third":
            return frame_h * 2 // 3, frame_h
        elif bg_region == "middle_third":
            return frame_h // 3, frame_h * 2 // 3
        else:
            return 0, frame_h

    def _split_subject(self, asset, placement, video_path):
        """拆分主体素材: bg + subject + alpha + split可视化"""
        asset_id = placement["id"]
        bg_region = placement["bg_region"]

        seg_dir = self.output_dir / f"seg_{asset_id}"
        mask_dir = seg_dir / "masks"

        if not mask_dir.exists():
            print(f"  跳过 {asset_id}: 无mask (Phase 3未完成)")
            return

        masks = sorted(mask_dir.glob("*.png"))
        cap = cv2.VideoCapture(video_path)
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        fps = cap.get(cv2.CAP_PROP_FPS)
        n = min(int(cap.get(cv2.CAP_PROP_FRAME_COUNT)), len(masks))

        bg_start, bg_end = self._get_bg_range(bg_region, h)
        bg_h = bg_end - bg_start

        print(f"\n  拆分 {asset_id} (bg_region={bg_region}, bg=[{bg_start}:{bg_end}])...")

        bg_path = self.output_dir / f"bg_{asset_id}.mp4"
        sub_path = self.output_dir / f"subject_{asset_id}.mp4"
        alpha_path = self.output_dir / f"alpha_{asset_id}.mp4"
        split_path = self.output_dir / f"split_{asset_id}.mp4"

        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer_bg = cv2.VideoWriter(str(bg_path), fourcc, fps, (w, bg_h))
        writer_sub = cv2.VideoWriter(str(sub_path), fourcc, fps, (w, h))
        writer_alpha = cv2.VideoWriter(str(alpha_path), fourcc, fps, (w, h))
        writer_split = cv2.VideoWriter(str(split_path), fourcc, fps, (w * 3, h))

        for fi in range(n):
            ret, frame = cap.read()
            if not ret:
                break

            mask = cv2.imread(str(masks[fi]), cv2.IMREAD_GRAYSCALE)
            m_f = (mask.astype(np.float32) / 255.0)
            m_blur = cv2.GaussianBlur(m_f, (21, 21), 0)

            # 1. 背景 strip
            bg_frame = frame[bg_start:bg_end, :]
            writer_bg.write(bg_frame)

            # 2. 主体 (mask区域, 背景黑)
            subject_frame = (frame.astype(np.float32) * m_blur[..., None]).astype(np.uint8)
            writer_sub.write(subject_frame)

            # 3. Alpha mask
            alpha_vis = np.stack([mask, mask, mask], axis=2)
            writer_alpha.write(alpha_vis)

            # 4. Split 可视化 (三列并排)
            bg_label = cv2.copyMakeBorder(bg_frame, 0, h - bg_h, 0, 0, cv2.BORDER_CONSTANT, value=0)
            split = np.hstack([bg_label, subject_frame, alpha_vis])
            # 标注
            cv2.putText(split, "BG", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
            cv2.putText(split, "SUBJECT", (w + 10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
            cv2.putText(split, "ALPHA", (w * 2 + 10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
            writer_split.write(split)

            if fi % 20 == 0:
                print(f"    帧 {fi}/{n}")

        writer_bg.release()
        writer_sub.release()
        writer_alpha.release()
        writer_split.release()
        cap.release()

        print(f"    bg_{asset_id}.mp4 ({bg_path.stat().st_size/1e6:.1f}MB)")
        print(f"    subject_{asset_id}.mp4 ({sub_path.stat().st_size/1e6:.1f}MB)")
        print(f"    alpha_{asset_id}.mp4 ({alpha_path.stat().st_size/1e6:.1f}MB)")
        print(f"    split_{asset_id}.mp4 ({split_path.stat().st_size/1e6:.1f}MB)")

    def _split_background(self, asset, placement, video_path, jpg_path):
        """背景素材: 整个视频/图片作为 bg"""
        asset_id = placement["id"]
        canvas_h = placement["canvas_h"]

        print(f"\n  拆分 {asset_id} (background, canvas_h={canvas_h})...")

        bg_path = self.output_dir / f"bg_{asset_id}.mp4"
        split_path = self.output_dir / f"split_{asset_id}.mp4"

        if video_path:
            cap = cv2.VideoCapture(video_path)
            w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            fps = cap.get(cv2.CAP_PROP_FPS)
            n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

            fourcc = cv2.VideoWriter_fourcc(*"mp4v")
            writer_bg = cv2.VideoWriter(str(bg_path), fourcc, fps, (self.canvas_w, canvas_h))
            writer_split = cv2.VideoWriter(str(split_path), fourcc, fps, (self.canvas_w * 3, canvas_h))

            for fi in range(n):
                ret, frame = cap.read()
                if not ret:
                    break

                bg_frame = self._resize_to_canvas(frame, self.canvas_w, canvas_h)
                writer_bg.write(bg_frame)

                # split: bg | 空 | 空
                empty = np.zeros((canvas_h, self.canvas_w, 3), dtype=np.uint8)
                split = np.hstack([bg_frame, empty, empty])
                cv2.putText(split, "BG (no subject)", (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
                writer_split.write(split)

            writer_bg.release()
            writer_split.release()
            cap.release()
        elif jpg_path:
            # 静态图 → 生成1帧视频
            img = cv2.imread(jpg_path)
            bg_frame = self._resize_to_canvas(img, self.canvas_w, canvas_h)

            fourcc = cv2.VideoWriter_fourcc(*"mp4v")
            writer_bg = cv2.VideoWriter(str(bg_path), fourcc, 1.0, (self.canvas_w, canvas_h))
            writer_bg.write(bg_frame)
            writer_bg.release()

        print(f"    bg_{asset_id}.mp4 ({bg_path.stat().st_size/1e6:.1f}MB)")

    def _resize_to_canvas(self, frame, target_w, target_h):
        """Resize + center crop 到目标尺寸"""
        h, w = frame.shape[:2]
        scale = max(target_w / w, target_h / h)
        new_w, new_h = int(w * scale), int(h * scale)
        resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_AREA)
        x0 = (new_w - target_w) // 2
        y0 = (new_h - target_h) // 2
        return resized[y0:y0 + target_h, x0:x0 + target_w]
