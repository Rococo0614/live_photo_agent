#!/usr/bin/env python3
"""Phase 5: 合成

1. 背景拼接: 所有素材的 bg strip 按布局垂直拼接 → 背景画布(铺满1920)
   - subject 素材的 bg_{id}.mp4 (1/3背景strip)
   - background 素材的 bg_{id}.mp4 (完整视频)
2. 主体叠加: subject_{id}.mp4 + alpha_{id}.mp4 按 z-order 叠加
   z-order: 按 coverage 升序 (小的在底, 大的在顶)

输出: final.mp4 (1440×1920)
"""
import json
from pathlib import Path

import cv2
import numpy as np


class Phase5Composer:
    def __init__(self, output_dir: Path, canvas_w=1440, canvas_h=1920):
        self.output_dir = Path(output_dir)
        self.canvas_w = canvas_w
        self.canvas_h = canvas_h

    def run(self, manifest: dict, layout: dict):
        """合成最终视频"""
        self.output_dir.mkdir(parents=True, exist_ok=True)

        assets = {a["id"]: a for a in manifest["assets"]}
        placements = layout["placements"]

        # 所有素材都贡献背景
        bg_placements = [p for p in placements if p["type"] in ("subject", "background")]
        # 主体按 coverage 升序 (小的在底, 大的在顶)
        subject_placements = [p for p in placements if p["type"] == "subject"]
        subject_placements.sort(key=lambda p: assets[p["id"]].get("coverage", 0))

        # 打开所有 bg 视频
        bg_caps = {}  # asset_id -> cap
        bg_ranges = {}  # asset_id -> (canvas_y, canvas_h)
        for p in bg_placements:
            bg_path = self.output_dir / f"bg_{p['id']}.mp4"
            if bg_path.exists():
                bg_caps[p["id"]] = cv2.VideoCapture(str(bg_path))
                bg_ranges[p["id"]] = (p["canvas_y"], p["canvas_h"])

        # 打开 subject + alpha 视频
        sub_caps = {}
        alpha_caps = {}
        for p in subject_placements:
            sub_path = self.output_dir / f"subject_{p['id']}.mp4"
            alpha_path = self.output_dir / f"alpha_{p['id']}.mp4"
            if sub_path.exists() and alpha_path.exists():
                sub_caps[p["id"]] = cv2.VideoCapture(str(sub_path))
                alpha_caps[p["id"]] = cv2.VideoCapture(str(alpha_path))

        # 帧数取所有视频的最小值
        all_caps = list(bg_caps.values()) + list(sub_caps.values()) + list(alpha_caps.values())
        if not all_caps:
            print("  无可用视频!")
            return
        n = min(int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) for cap in all_caps)
        fps = 30.0
        for cap in all_caps:
            f = cap.get(cv2.CAP_PROP_FPS)
            if f > 0:
                fps = f
                break

        print(f"  帧数: {n}, FPS: {fps}")
        print(f"  背景素材: {len(bg_caps)}")
        print(f"  主体素材: {len(sub_caps)}")

        # 输出
        out_path = self.output_dir / "final.mp4"
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(out_path), fourcc, fps, (self.canvas_w, self.canvas_h))

        for fi in range(n):
            # 1. 构建背景画布 (铺满1920)
            canvas = np.zeros((self.canvas_h, self.canvas_w, 3), dtype=np.uint8)

            # 读取所有背景帧
            bg_frames = {}
            for aid, cap in bg_caps.items():
                ret, frame = cap.read()
                if ret:
                    bg_frames[aid] = frame

            # 用第一个背景素材铺满整个画布作为底色 (防止空隙黑色)
            if bg_frames:
                first_aid = next(iter(bg_frames))
                fill_resized = self._resize_to_canvas(bg_frames[first_aid], self.canvas_w, self.canvas_h)
                canvas[:] = fill_resized

            # 在各自位置放对应背景 (覆盖底色)
            for aid, frame in bg_frames.items():
                y, h = bg_ranges[aid]
                actual_h = frame.shape[0]
                target_h = min(h, actual_h)
                frame_resized = self._resize_to_canvas(frame, self.canvas_w, target_h)
                end_y = min(y + target_h, self.canvas_h)
                canvas[y:end_y, :] = frame_resized[:end_y - y, :]

            # 2. 叠加主体 (z-order: coverage升序, 小的在底)
            for p in subject_placements:
                aid = p["id"]
                if aid not in sub_caps:
                    continue

                ret_sub, sub_frame = sub_caps[aid].read()
                ret_alpha, alpha_frame = alpha_caps[aid].read()
                if not ret_sub or not ret_alpha:
                    continue

                # alpha mask (resize to canvas first)
                alpha_resized = self._resize_to_canvas(alpha_frame, self.canvas_w, self.canvas_h)
                alpha_gray = cv2.cvtColor(alpha_resized, cv2.COLOR_BGR2GRAY)
                m_f = cv2.GaussianBlur(alpha_gray.astype(np.float32) / 255.0, (21, 21), 0)

                # subject 帧 resize 到画布尺寸
                sub_resized = self._resize_to_canvas(sub_frame, self.canvas_w, self.canvas_h)

                # alpha 叠加: 只在 mask 区域覆盖, mask 外保留背景
                canvas_f = canvas.astype(np.float32)
                canvas_f = sub_resized.astype(np.float32) * m_f[..., None] + canvas_f * (1 - m_f[..., None])
                canvas = np.clip(canvas_f, 0, 255).astype(np.uint8)

            writer.write(canvas)

            if fi % 20 == 0:
                print(f"  帧 {fi}/{n}")

        writer.release()
        for cap in list(bg_caps.values()) + list(sub_caps.values()) + list(alpha_caps.values()):
            cap.release()

        print(f"\n  final.mp4 (raw): {out_path} ({out_path.stat().st_size/1e6:.1f}MB)")

        # 转码为浏览器兼容的 H.264 (mp4v 不被 Chrome 支持)
        h264_path = self.output_dir / "final_h264.mp4"
        import subprocess, shutil
        ffmpeg = shutil.which("ffmpeg")
        if ffmpeg:
            rc = subprocess.run(
                [ffmpeg, "-y", "-i", str(out_path),
                 "-c:v", "libx264", "-preset", "fast", "-crf", "20",
                 "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                 str(h264_path)],
                capture_output=True, text=True,
            )
            if rc.returncode == 0 and h264_path.exists() and h264_path.stat().st_size > 0:
                out_path.unlink()
                h264_path.rename(out_path)
                print(f"  final.mp4 (H.264): {out_path} ({out_path.stat().st_size/1e6:.1f}MB)")
            else:
                print(f"  ffmpeg 转码失败(rc={rc.returncode})，保留 mp4v 原始文件")
                if h264_path.exists():
                    h264_path.unlink()
        else:
            print("  未找到 ffmpeg，保留 mp4v 原始文件")
        print(f"  分辨率: {self.canvas_w}x{self.canvas_h}")

    def _resize_to_canvas(self, frame, target_w, target_h):
        """Resize + center crop 到目标尺寸"""
        h, w = frame.shape[:2]
        if h == target_h and w == target_w:
            return frame
        scale = max(target_w / w, target_h / h)
        new_w, new_h = int(w * scale), int(h * scale)
        resized = cv2.resize(frame, (new_w, new_h), interpolation=cv2.INTER_AREA)
        x0 = (new_w - target_w) // 2
        y0 = (new_h - target_h) // 2
        return resized[y0:y0 + target_h, x0:x0 + target_w]
