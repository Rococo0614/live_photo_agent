#!/usr/bin/env python3
"""Phase 1: 资产分析

对每个素材运行 Mask2Former 检测候选, 判断有主体/无主体。
输出: manifest.json + phase1_asset_analysis.mp4 (只展示有subject的素材)
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
    Candidate,
)


class Phase1Analyzer:
    def __init__(self, output_dir: Path, canvas_w=1440, canvas_h=1920):
        self.output_dir = Path(output_dir)
        self.canvas_w = canvas_w
        self.canvas_h = canvas_h

    def run(self, assets: list[tuple[str, str | None, str | None]]) -> dict:
        """分析所有素材, 生成 manifest.json + asset_analysis.mp4"""
        self.output_dir.mkdir(parents=True, exist_ok=True)

        template = SegmentationTemplate(auto_select_min_coverage=0.15)
        pipeline = VideoSegmentationPipeline(template)

        asset_records = []
        frames_with_subjects = []  # [(frame, candidates, asset_id), ...]

        for asset_id, video_path, jpg_path in assets:
            print(f"\n  分析 {asset_id}...")
            record = {
                "id": asset_id,
                "video_path": video_path,
                "jpg_path": jpg_path,
                "has_subject": False,
                "subject_id": None,
                "label": None,
                "coverage": 0.0,
                "bbox": None,
                "candidates": [],
            }

            # 获取第一帧
            frame = self._get_first_frame(video_path, jpg_path)
            if frame is None:
                print(f"    无法读取素材: {asset_id}")
                record["has_subject"] = False
                record["reason"] = "cannot read"
                asset_records.append(record)
                continue

            frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

            # Mask2Former 检测
            try:
                candidates = pipeline.detect_candidates(frame_rgb)
            except Exception as e:
                print(f"    Mask2Former失败: {e}")
                candidates = []

            print(f"    候选数: {len(candidates)}")
            for c in candidates:
                print(f"      #{c.index}: {c.label_name} score={c.score:.3f} coverage={c.coverage*100:.1f}%")

            # 过滤背景类
            fg_candidates = [c for c in candidates if c.label_name not in pipeline.BG_LABELS]

            # 额外过滤: 风景/建筑类也视为背景
            SCENERY_LABELS = {
                "building-other-merged", "building-other", "sky-other",
                "river", "boat", "bridge", "sea", "lake",
                "earth-merged", "earth", "clouds", "grass",
            }
            fg_candidates = [c for c in fg_candidates if c.label_name not in SCENERY_LABELS]

            if fg_candidates:
                best = max(fg_candidates, key=lambda c: c.coverage)
                record["has_subject"] = True
                record["subject_id"] = best.index
                record["label"] = best.label_name
                record["coverage"] = round(best.coverage, 4)
                record["bbox"] = list(best.bbox)
                record["candidates"] = [c.to_dict() for c in candidates]
                record["frame_shape"] = list(frame.shape)
                frames_with_subjects.append((frame, candidates, asset_id, best))
                print(f"    → 有主体: #{best.index} {best.label_name} ({best.coverage*100:.1f}%)")
            else:
                record["has_subject"] = False
                record["reason"] = "no foreground candidate"
                record["candidates"] = [c.to_dict() for c in candidates]
                print(f"    → 无主体 (纯背景)")

            asset_records.append(record)

        # 卸载 Mask2Former
        pipeline._unload_mask2former()

        # 生成 manifest.json
        manifest = {
            "canvas": {"w": self.canvas_w, "h": self.canvas_h},
            "assets": asset_records,
        }
        manifest_path = self.output_dir / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
        print(f"\n  manifest.json: {manifest_path}")

        # 生成 phase1_asset_analysis.mp4 (只展示有subject的素材)
        if frames_with_subjects:
            self._render_analysis_mp4(frames_with_subjects)

        return manifest

    def _get_first_frame(self, video_path, jpg_path):
        """获取素材第一帧"""
        if video_path:
            cap = cv2.VideoCapture(video_path)
            ret, frame = cap.read()
            cap.release()
            if ret:
                return frame
        if jpg_path:
            img = cv2.imread(jpg_path)
            return img
        return None

    def _render_analysis_mp4(self, frames_with_subjects):
        """生成 phase1_asset_analysis.mp4

        每个有主体的素材展示一帧, 标注候选框 + #编号 + label + coverage
        """
        out_path = self.output_dir / "phase1_asset_analysis.mp4"
        fps = 1.0  # 每秒一张
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(out_path), fourcc, fps, (self.canvas_w, self.canvas_h))

        colors = [
            (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0),
            (255, 0, 255), (0, 255, 255), (128, 128, 0), (128, 0, 128),
        ]

        for frame, candidates, asset_id, best in frames_with_subjects:
            # resize 到画布尺寸
            fh, fw = frame.shape[:2]
            vis = cv2.resize(frame, (self.canvas_w, self.canvas_h))

            # 标题
            cv2.rectangle(vis, (0, 0), (self.canvas_w, 80), (0, 0, 0), -1)
            cv2.putText(vis, f"Phase 1: {asset_id} — Best: #{best.index} {best.label_name}",
                        (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)

            # 绘制所有候选
            for i, cand in enumerate(candidates):
                color = colors[i % len(colors)]
                # mask resize 到画布尺寸
                mask_resized = cv2.resize(cand.mask, (self.canvas_w, self.canvas_h))
                # 半透明填充
                overlay = vis.copy()
                overlay[mask_resized > 0] = color
                vis = cv2.addWeighted(vis, 0.6, overlay, 0.4, 0)
                # 边框 (按缩放比例调整)
                x1, y1, x2, y2 = cand.bbox
                sx = self.canvas_w / fw
                sy = self.canvas_h / fh
                x1r, y1r, x2r, y2r = int(x1*sx), int(y1*sy), int(x2*sx), int(y2*sy)
                cv2.rectangle(vis, (x1r, y1r), (x2r, y2r), color, 3)
                # 标签
                label = f"#{cand.index} {cand.label_name} {cand.coverage*100:.1f}%"
                cv2.putText(vis, label, (x1r, max(y1r - 10, 90)), cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)

            writer.write(vis)

        writer.release()
        print(f"  phase1_asset_analysis.mp4: {out_path} ({out_path.stat().st_size/1e6:.1f}MB)")
