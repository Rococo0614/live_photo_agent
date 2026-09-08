#!/usr/bin/env python3
"""Phase 3: 分割

对每个有主体的素材, 根据 layout_plan 中的 bg_region,
运行 Mask2Former + Cutie 分割追踪。

输出: seg_{id}/masks/ + seg_{id}.mp4 (完整叠加: 原始帧 + mask红色半透明)
"""
import json
import sys
from pathlib import Path

import cv2
import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "src"))

from live_photo_agent.foundation.video_segmentation import (
    VideoSegmentationPipeline,
    SegmentationTemplate,
)


class Phase3Segmenter:
    def __init__(self, output_dir: Path, canvas_w=1440, canvas_h=1920):
        self.output_dir = Path(output_dir)
        self.canvas_w = canvas_w
        self.canvas_h = canvas_h

    def run(self, manifest: dict, layout: dict):
        """对每个有主体素材运行 Cutie 分割"""
        self.output_dir.mkdir(parents=True, exist_ok=True)

        assets = {a["id"]: a for a in manifest["assets"]}
        placements = layout["placements"]

        # 只处理 subject 素材
        subject_placements = [p for p in placements if p["type"] == "subject"]

        if not subject_placements:
            print("  无主体素材, 跳过 Phase 3")
            return

        for p in subject_placements:
            asset = assets[p["id"]]
            video_path = asset.get("video_path")
            if not video_path:
                print(f"  跳过 {p['id']}: 无视频")
                continue

            bg_region = p["bg_region"]
            subject_id = asset.get("subject_id")

            print(f"\n  分割 {p['id']} (region={bg_region}, subject_id={subject_id})...")

            seg_dir = self.output_dir / f"seg_{p['id']}"
            seg_dir.mkdir(parents=True, exist_ok=True)

            # 配置 template
            template = SegmentationTemplate(
                region=bg_region,
                keep_region=True,
                auto_select_min_coverage=0.15,
            )

            pipeline = VideoSegmentationPipeline(template)

            # 用 Phase 1 选定的 subject_id
            selected = [subject_id] if subject_id else None
            result = pipeline.run(
                Path(video_path),
                seg_dir,
                selected_indices=selected,
            )

            print(f"    stage: {result.stage}")
            print(f"    coverage: {result.coverage*100:.1f}%")
            print(f"    masks: {seg_dir / 'masks'}")

            # 生成 seg_{id}.mp4 (完整叠加)
            if result.stage.value == "composite":
                self._render_seg_mp4(seg_dir, video_path, p["id"], result)

    def _render_seg_mp4(self, seg_dir, video_path, asset_id, result):
        """生成 seg_{id}.mp4 — 原始帧 + mask红色半透明叠加"""
        out_path = self.output_dir / f"seg_{asset_id}.mp4"

        mask_dir = seg_dir / "masks"
        masks = sorted(mask_dir.glob("*.png"))

        cap = cv2.VideoCapture(video_path)
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        fps = cap.get(cv2.CAP_PROP_FPS)

        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(out_path), fourcc, fps, (w, h))

        n = min(int(cap.get(cv2.CAP_PROP_FRAME_COUNT)), len(masks))
        for fi in range(n):
            ret, frame = cap.read()
            if not ret:
                break

            mask = cv2.imread(str(masks[fi]), cv2.IMREAD_GRAYSCALE)

            # 红色半透明叠加
            overlay = frame.copy()
            overlay[mask > 127] = [0, 0, 255]  # 红色
            composite = cv2.addWeighted(frame, 0.5, overlay, 0.5, 0)

            # 底部标注
            cv2.rectangle(composite, (0, h - 60), (w, h), (0, 0, 0), -1)
            cv2.putText(composite, f"{asset_id} frame {fi}/{n}",
                        (20, h - 20), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)

            writer.write(composite)

        writer.release()
        cap.release()
        print(f"    seg_{asset_id}.mp4: {out_path} ({out_path.stat().st_size/1e6:.1f}MB)")
