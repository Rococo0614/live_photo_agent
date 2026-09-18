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

        # Step 5: 强制约束验证 + 调整 (最多重试5次)
        layout_ok = False
        for attempt in range(5):
            issues = self._validate_constraints(placements, subject_assets)
            if not issues:
                layout_ok = True
                print(f"\n  约束验证通过 (attempt {attempt+1})")
                break
            print(f"\n  约束验证失败 (attempt {attempt+1}), 调整中...")
            for issue in issues:
                print(f"    ✗ {issue}")
            placements = self._fix_constraints(placements, issues, subject_assets)

        if not layout_ok:
            print(f"\n  ⚠ 约束验证5次仍未通过, 使用最佳布局")

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

    def _validate_constraints(self, placements, subject_assets):
        """验证两个强制约束:
        1. 每个主体必须横跨≥2个背景(即跨越≥1个背景边界)
        2. 每个背景素材必须可见(不能被主体完全遮盖)
        
        返回 issues 列表, 空列表=通过
        """
        issues = []
        subject_placements = [p for p in placements if p["type"] == "subject"]
        bg_placements = [p for p in placements if p["type"] == "background"]
        asset_map = {a["id"]: a for a in subject_assets}

        # 约束1: 主体横跨≥2背景 (跨越≥1个背景边界)
        all_crossing_fail = []
        for sp in subject_placements:
            s_top = sp["canvas_y"]
            s_bot = sp["canvas_y"] + sp["canvas_h"]
            
            crossings = 0
            for bp in bg_placements:
                b_top = bp["canvas_y"]
                b_bot = bp["canvas_y"] + bp["canvas_h"]
                # 背景 top 边界严格在主体范围内(不含边缘)
                if s_top < b_top < s_bot:
                    crossings += 1
                # 背景 bottom 边界严格在主体范围内(不含边缘)
                if s_top < b_bot < s_bot:
                    crossings += 1
            
            if crossings < 1:
                issues.append(f"crossing_fail|{sp['id']}|crossings={crossings}")
                all_crossing_fail.append(sp["id"])
        
        # 如果多个主体不跨越, 合并为一个 group issue 方便拆分修复
        if len(all_crossing_fail) > 1:
            # 移除单独的 crossing_fail, 添加 group issue
            issues = [i for i in issues if not i.startswith("crossing_fail")]
            issues.insert(0, f"crossing_group|{','.join(all_crossing_fail)}")

        # 约束2: 每个背景必须出现(可见高度>0且不被主体完全遮盖)
        for bp in bg_placements:
            b_top = bp["canvas_y"]
            b_bot = bp["canvas_y"] + bp["canvas_h"]
            b_h = bp["canvas_h"]
            
            if b_h <= 0:
                issues.append(f"bg_invisible|{bp['id']}|height={b_h}")
                continue
            
            # 检查背景是否被某个主体完全覆盖
            # 用主体bbox估算覆盖比例
            covered_h = 0
            for sp in subject_placements:
                s_top = sp["canvas_y"]
                s_bot = sp["canvas_y"] + sp["canvas_h"]
                overlap = max(0, min(s_bot, b_bot) - max(s_top, b_top))
                covered_h = max(covered_h, overlap)
            
            visible_h = b_h - covered_h
            visibility_ratio = visible_h / b_h if b_h > 0 else 0
            
            if visibility_ratio < 0.15:
                issues.append(f"bg_covered|{bp['id']}|visible={visible_h}/{b_h}={visibility_ratio*100:.0f}%")

        return issues

    def _fix_constraints(self, placements, issues, subject_assets):
        """根据 issues 主动调整布局
        
        策略:
        - crossing_fail: 把背景移到不跨越的主体中线, 如果有多个不跨越的主体,
          把背景拆成多个strip分别插入
        - bg_invisible: 从主体借高度
        - bg_covered: 缩小主体让背景可见
        """
        
        # 收集 crossing issues
        crossing_group_subjects = []
        crossing_single_subjects = []
        other_issues = []
        for issue in issues:
            parts = issue.split("|")
            if parts[0] == "crossing_group":
                crossing_group_subjects = parts[1].split(",")
            elif parts[0] == "crossing_fail":
                crossing_single_subjects.append(parts[1])
            else:
                other_issues.append(issue)
        
        # 处理 crossing_group: 多个主体不跨越 → 拆分背景
        if crossing_group_subjects:
            bg_placements = [p for p in placements if p["type"] == "background"]
            if bg_placements:
                bg = bg_placements[0]
                n_splits = len(crossing_group_subjects)
                split_h = max(bg["canvas_h"] // n_splits, 50)
                
                placements.remove(bg)
                
                for i, sid in enumerate(crossing_group_subjects):
                    sp = next((p for p in placements if p["id"] == sid), None)
                    if not sp:
                        continue
                    s_mid = sp["canvas_y"] + sp["canvas_h"] // 2
                    new_bg = {
                        "id": f"{bg['id']}_split{i+1}",
                        "type": "background",
                        "canvas_y": max(0, s_mid - split_h // 2),
                        "canvas_h": split_h,
                        "bg_region": "full",
                    }
                    placements.append(new_bg)
                    print(f"    修复跨越(拆分): {sid} ← {new_bg['id']} 插入到中线 y={new_bg['canvas_y']}")
        
        # 处理单个 crossing_fail
        for sid in crossing_single_subjects:
            sp = next((p for p in placements if p["id"] == sid), None)
            if sp:
                s_mid = sp["canvas_y"] + sp["canvas_h"] // 2
                bg_placements = [p for p in placements if p["type"] == "background"]
                if bg_placements:
                    best_bg = min(bg_placements, key=lambda bp: abs(bp["canvas_y"] + bp["canvas_h"]//2 - s_mid))
                    old_y = best_bg["canvas_y"]
                    old_h = best_bg["canvas_h"]
                    new_y = max(0, min(s_mid - old_h // 4, self.canvas_h - old_h))
                    best_bg["canvas_y"] = new_y
                    print(f"    修复跨越: {sid} ← {best_bg['id']} y:{old_y}→{new_y}")
        
        # 处理其他 issues
        for issue in other_issues:
            parts = issue.split("|")
            issue_type = parts[0]
            asset_id = parts[1] if len(parts) > 1 else ""
            
            if issue_type == "bg_invisible":
                bp = next((p for p in placements if p["id"] == asset_id), None)
                if not bp:
                    continue
                for sp in placements:
                    if sp["type"] != "subject" or sp["canvas_h"] <= 200:
                        continue
                    sp["canvas_h"] -= 100
                    bp["canvas_h"] += 100
                    print(f"    修复背景: {bp['id']} ← {sp['id']} 借100px")
                    break
            
            elif issue_type == "bg_covered":
                bp = next((p for p in placements if p["id"] == asset_id), None)
                if not bp:
                    continue
                b_top = bp["canvas_y"]
                b_bot = bp["canvas_y"] + bp["canvas_h"]
                for sp in placements:
                    if sp["type"] != "subject":
                        continue
                    s_top = sp["canvas_y"]
                    s_bot = sp["canvas_y"] + sp["canvas_h"]
                    overlap = max(0, min(s_bot, b_bot) - max(s_top, b_top))
                    if overlap > bp["canvas_h"] * 0.5:
                        shrink = min(overlap // 2, sp["canvas_h"] // 4)
                        sp["canvas_h"] -= shrink
                        bp["canvas_h"] += shrink
                        print(f"    修复遮盖: {bp['id']} ← {sp['id']} 缩{shrink}px")
                        break
        
        # 边界保护 + 铺满
        for p in placements:
            if p["canvas_y"] < 0:
                p["canvas_y"] = 0
            if p["canvas_y"] + p["canvas_h"] > self.canvas_h:
                p["canvas_h"] = max(self.canvas_h - p["canvas_y"], 1)
        
        # 重新铺满: 把差额加到最大的背景strip
        total_h = sum(p["canvas_h"] for p in placements)
        if total_h < self.canvas_h:
            diff = self.canvas_h - total_h
            bg_placements = [p for p in placements if p["type"] == "background"]
            if bg_placements:
                biggest_bg = max(bg_placements, key=lambda p: p["canvas_h"])
                biggest_bg["canvas_h"] += diff
            else:
                placements[-1]["canvas_h"] += diff
        
        # 不做连续排列! 保留背景和主体的重叠(主体在背景之上, z-order控制)
        # 背景铺满画布由 Phase5 的底色铺满逻辑处理
        
        return placements

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
