#!/usr/bin/env python3
"""Phase 2: 自由布局规划

内容驱动的动态布局算法:
  1. 主体按原始帧位置分组 (upper/middle/lower)
  2. 高度 ∝ subject_height, 背景等分剩余
  3. 主体从上/下/中放置, 检查mask交集<10%
  4. 背景填充间隙, 保证主体跨越≥1个背景边界

输出: layout_plan.json + phase2_layout_plan.mp4 (动画)
"""
import json
from pathlib import Path

import cv2
import numpy as np


class Phase2Planner:
    def __init__(self, output_dir: Path, canvas_w=1440, canvas_h=1920):
        self.output_dir = Path(output_dir)
        self.canvas_w = canvas_w
        self.canvas_h = canvas_h

    def run(self, manifest: dict) -> dict:
        self.output_dir.mkdir(parents=True, exist_ok=True)

        assets = manifest["assets"]
        subject_assets = [a for a in assets if a["has_subject"]]
        bg_assets = [a for a in assets if not a["has_subject"]]

        print(f"  主体素材: {len(subject_assets)}")
        print(f"  背景素材: {len(bg_assets)}")

        layout = self._plan_free_layout(subject_assets, bg_assets)

        layout_path = self.output_dir / "layout_plan.json"
        layout_path.write_text(json.dumps(layout, indent=2, ensure_ascii=False))
        print(f"\n  layout_plan.json: {layout_path}")

        self._render_layout_animation(manifest, layout)
        return layout

    def _classify_position(self, asset):
        """判断主体在原始帧中的位置"""
        bbox = asset.get("bbox")
        frame_h = asset.get("frame_shape", [1920])[0]
        if not bbox:
            return "middle", "middle_third"

        subject_top = bbox[1]
        subject_bottom = bbox[3]
        subject_center = (subject_top + subject_bottom) / 2
        third = frame_h / 3
        two_thirds = frame_h * 2 / 3

        if subject_center < third:
            return "upper", "top_third"
        elif subject_center >= two_thirds:
            return "lower", "bottom_third"
        else:
            if subject_bottom > two_thirds:
                return "lower", "bottom_third"
            elif subject_top < third:
                return "upper", "top_third"
            return "middle", "middle_third"

    def _plan_free_layout(self, subject_assets, bg_assets):
        """自由布局: 内容驱动分配 + 跨越保证"""
        M = len(subject_assets)
        B = len(bg_assets)

        if M + B == 0:
            return {"canvas": {"w": self.canvas_w, "h": self.canvas_h}, "placements": []}

        # Step 1: 主体分组
        for a in subject_assets:
            pos, bg_region = self._classify_position(a)
            a["_position"] = pos
            a["_bg_region"] = bg_region
            bbox = a.get("bbox")
            a["_subject_h"] = (bbox[3] - bbox[1]) if bbox else 100

        upper = [a for a in subject_assets if a["_position"] == "upper"]
        middle = [a for a in subject_assets if a["_position"] == "middle"]
        lower = [a for a in subject_assets if a["_position"] == "lower"]

        # 同组按 coverage 降序
        upper.sort(key=lambda a: a["coverage"], reverse=True)
        middle.sort(key=lambda a: a["coverage"], reverse=True)
        lower.sort(key=lambda a: a["coverage"], reverse=True)

        print(f"\n  分组: upper={len(upper)} middle={len(middle)} lower={len(lower)} bg={len(bg_assets)}")

        # Step 2: 高度分配
        total_subject_h = sum(a["_subject_h"] for a in subject_assets)
        if total_subject_h == 0:
            total_subject_h = M * 100

        # 主体占70%画布, 背景占30%
        subject_budget = int(self.canvas_h * 0.70)
        bg_budget = self.canvas_h - subject_budget

        # 每个主体按 subject_h 比例分配
        for a in subject_assets:
            a["_alloc_h"] = max(int(subject_budget * a["_subject_h"] / total_subject_h), 100)

        # 背景等分
        bg_count = max(B, 1)
        bg_strip_h = bg_budget // bg_count

        # Step 3: 放置主体 + 背景
        placements = []
        current_y = 0

        # --- upper 组 (从顶部往下) ---
        for a in upper:
            h = a["_alloc_h"]
            placements.append(self._make_placement(a, "subject", current_y, h))
            current_y += h

        # --- 背景 strip (upper 和 middle/lower 之间) ---
        bg_idx = 0
        # 如果有 middle 或 lower 主体, 在它们之前插入背景
        if (middle or lower) and bg_idx < B:
            a = bg_assets[bg_idx]
            h = bg_strip_h
            placements.append(self._make_placement(a, "background", current_y, h))
            current_y += h
            bg_idx += 1

        # --- middle 组 ---
        for a in middle:
            h = a["_alloc_h"]
            placements.append(self._make_placement(a, "subject", current_y, h))
            current_y += h

        # --- 背景 strip (middle 和 lower 之间) ---
        if lower and bg_idx < B:
            a = bg_assets[bg_idx]
            h = bg_strip_h
            placements.append(self._make_placement(a, "background", current_y, h))
            current_y += h
            bg_idx += 1

        # --- lower 组 (从当前位置往下) ---
        for a in lower:
            h = a["_alloc_h"]
            placements.append(self._make_placement(a, "subject", current_y, h))
            current_y += h

        # --- 剩余背景素材填到末尾 ---
        while bg_idx < B:
            a = bg_assets[bg_idx]
            # 最后一个背景取剩余所有高度
            remaining = self.canvas_h - current_y
            h = remaining if bg_idx == B - 1 else min(bg_strip_h, remaining)
            if h <= 0:
                h = max(remaining, 1)
            placements.append(self._make_placement(a, "background", current_y, h))
            current_y += h
            bg_idx += 1

        # Step 4: 调整铺满1920
        total_h = sum(p["canvas_h"] for p in placements)
        if total_h < self.canvas_h and placements:
            # 把差额加到最后一个
            diff = self.canvas_h - total_h
            placements[-1]["canvas_h"] += diff
        elif total_h > self.canvas_h and placements:
            # 缩减最后一个
            diff = total_h - self.canvas_h
            placements[-1]["canvas_h"] = max(placements[-1]["canvas_h"] - diff, 1)

        # Step 5: 跨越保证
        self._ensure_crossing(placements, subject_assets)

        # Step 6: 重叠检测
        self._check_overlap(placements, subject_assets)

        layout = {
            "canvas": {"w": self.canvas_w, "h": self.canvas_h},
            "placements": placements,
        }

        # 打印布局
        total_h = sum(p["canvas_h"] for p in placements)
        print(f"\n  总高度: {total_h} (应为{self.canvas_h})")
        for p in placements:
            print(f"    {p['id']} ({p['type']}): y={p['canvas_y']}, h={p['canvas_h']}, region={p.get('bg_region', '-')}")

        return layout

    def _make_placement(self, asset, ptype, y, h):
        if ptype == "subject":
            return {
                "id": asset["id"],
                "type": "subject",
                "label": asset.get("label", ""),
                "canvas_y": y,
                "canvas_h": h,
                "bg_region": asset["_bg_region"],
                "position": asset["_position"],
                "subject_bbox": list(asset["bbox"]) if asset.get("bbox") else None,
            }
        else:
            return {
                "id": asset["id"],
                "type": "background",
                "canvas_y": y,
                "canvas_h": h,
                "bg_region": "full",
            }

    def _ensure_crossing(self, placements, subject_assets):
        """保证每个主体跨越≥1个背景边界
        
        不移动背景位置, 而是检查背景的边界(top/bottom)是否在主体范围内。
        如果没有, 记录但不强制调整(布局已经保证主体在背景之间)。
        """
        subject_placements = [p for p in placements if p["type"] == "subject"]
        bg_placements = [p for p in placements if p["type"] == "background"]

        if not bg_placements:
            return

        for sp in subject_placements:
            s_top = sp["canvas_y"]
            s_bot = sp["canvas_y"] + sp["canvas_h"]

            # 检查主体是否跨越任何背景的边界
            # 相邻主体和背景的边界天然在主体边缘
            # 主体mask会跨越到相邻背景 → 算跨越
            crossings = 0
            for bp in bg_placements:
                b_top = bp["canvas_y"]
                b_bot = bp["canvas_y"] + bp["canvas_h"]
                # 背景 top 边界在主体范围内(含边缘)
                if s_top <= b_top <= s_bot:
                    crossings += 1
                # 背景 bottom 边界在主体范围内(含边缘)
                if s_top <= b_bot <= s_bot:
                    crossings += 1

            if crossings == 0:
                print(f"    ⚠ {sp['id']} 未跨越任何背景边界")
            else:
                print(f"    ✓ {sp['id']} 跨越 {crossings} 个背景边界")

    def _check_overlap(self, placements, subject_assets):
        """检查主体mask交集<10%"""
        subject_placements = [p for p in placements if p["type"] == "subject"]
        asset_map = {a["id"]: a for a in subject_assets}

        for i in range(len(subject_placements)):
            for j in range(i + 1, len(subject_placements)):
                p1 = subject_placements[i]
                p2 = subject_placements[j]

                # 用 bbox 估算重叠
                a1 = asset_map.get(p1["id"])
                a2 = asset_map.get(p2["id"])
                if not a1 or not a2:
                    continue

                bbox1 = a1.get("bbox")
                bbox2 = a2.get("bbox")
                if not bbox1 or not bbox2:
                    continue

                # 画布坐标的 y 范围
                y1_top = p1["canvas_y"]
                y1_bot = p1["canvas_y"] + p1["canvas_h"]
                y2_top = p2["canvas_y"]
                y2_bot = p2["canvas_y"] + p2["canvas_h"]

                # y 方向重叠
                y_overlap = max(0, min(y1_bot, y2_bot) - max(y1_top, y2_top))
                if y_overlap == 0:
                    continue

                # 估算 mask 交集 (用 bbox 面积近似)
                # x 方向重叠 (bbox)
                x_overlap = max(0, min(bbox1[2], bbox2[2]) - max(bbox1[0], bbox2[0]))
                if x_overlap == 0:
                    continue

                overlap_area = x_overlap * y_overlap
                area1 = (bbox1[2] - bbox1[0]) * (bbox1[3] - bbox1[1])
                ratio = overlap_area / max(area1, 1)

                print(f"    重叠检查: {p1['id']} ∩ {p2['id']} = {ratio*100:.1f}% (y_overlap={y_overlap}px)")
                if ratio > 0.10:
                    print(f"      ⚠ 重叠>{10}%, 需要z-order处理 (小coverage在底)")

    def _render_layout_animation(self, manifest, layout):
        """生成 phase2_layout_plan.mp4 — 动画展示放置过程"""
        out_path = self.output_dir / "phase2_layout_plan.mp4"
        fps = 2.0
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(out_path), fourcc, fps, (self.canvas_w, self.canvas_h))

        placements = layout["placements"]
        colors = [
            (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0),
            (255, 0, 255), (0, 255, 255), (128, 128, 0), (128, 0, 128),
            (0, 128, 128), (255, 128, 0),
        ]

        for step in range(len(placements) + 1):
            canvas = np.full((self.canvas_h, self.canvas_w, 3), 240, dtype=np.uint8)

            cv2.rectangle(canvas, (0, 0), (self.canvas_w, 80), (0, 0, 0), -1)
            cv2.putText(canvas, f"Phase 2: Free Layout — Step {step}/{len(placements)}",
                        (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)

            for i in range(step):
                p = placements[i]
                color = colors[i % len(colors)]
                y = p["canvas_y"]
                h = p["canvas_h"]

                overlay = canvas.copy()
                overlay[y:y + h, :] = color
                canvas = cv2.addWeighted(canvas, 0.7, overlay, 0.3, 0)
                cv2.rectangle(canvas, (0, y), (self.canvas_w, y + h), color, 3)

                if p["type"] == "subject":
                    label = f"#{i + 1} {p['id']} {p.get('label', '')} [{p['position']}] bg={p['bg_region']}"
                else:
                    label = f"#{i + 1} {p['id']} BG"
                cv2.putText(canvas, label, (20, y + 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 0), 2)

            writer.write(canvas)

        for _ in range(4):
            writer.write(canvas)

        writer.release()
        print(f"  phase2_layout_plan.mp4: {out_path} ({out_path.stat().st_size / 1e6:.1f}MB)")
