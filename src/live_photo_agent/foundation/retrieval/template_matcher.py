"""Template Matcher: 将 K 个素材匹配到合适的模板。

匹配逻辑:
  1. 素材数量 → 候选模板 (slot_count 匹配)
  2. 素材特征 (有主体/无主体, 位置) → 模板 slot 约束匹配
  3. 匹配度排序
"""
from __future__ import annotations

from typing import Any

from .template_library import CollageTemplate, SlotConstraint, TemplateLibrary


class TemplateMatcher:
    """模板匹配器。"""

    def __init__(self, library: TemplateLibrary) -> None:
        self.library = library

    def match(
        self,
        assets: list[dict[str, Any]],
        preferred_template_id: str | None = None,
    ) -> list[dict[str, Any]]:
        """为 K 个素材推荐模板。

        Args:
            assets: 搜索到的 K 个素材 (含 content_summary, subject_tags 等)
            preferred_template_id: 用户指定的模板 ID

        Returns:
            排序后的候选列表 [{template, score, assignment, reason}]
        """
        k = len(assets)

        # 用户指定模板
        if preferred_template_id:
            template = self.library.get(preferred_template_id)
            if template and template.slot_count == k:
                score, assignment, reason = self._evaluate_match(assets, template)
                return [{
                    "template": template.to_dict(),
                    "score": score,
                    "assignment": assignment,
                    "reason": reason,
                }]
            else:
                print(f"  [matcher] template {preferred_template_id} not found or slot count mismatch")

        # 按 slot_count 找候选模板
        candidates = self.library.find_by_slot_count(k)
        if not candidates:
            # 没有精确匹配, 找 slot_count >= k 的模板
            candidates = [t for t in self.library.list_all() if t.slot_count >= k]
            if not candidates:
                return []

        # 评估每个候选
        results = []
        for template in candidates:
            score, assignment, reason = self._evaluate_match(assets, template)
            results.append({
                "template": template.to_dict(),
                "score": score,
                "assignment": assignment,
                "reason": reason,
            })

        # 排序
        results.sort(key=lambda r: r["score"], reverse=True)
        return results

    def _evaluate_match(
        self,
        assets: list[dict[str, Any]],
        template: CollageTemplate,
    ) -> tuple[float, list[dict[str, str]], str]:
        """评估素材与模板的匹配度。

        Returns:
            (score 0-1, assignment [{asset_id, slot_index}], reason)
        """
        k = len(assets)
        slot_count = template.slot_count

        # 如果素材数 < slot 数, 需要复用素材
        # 如果素材数 > slot 数, 需要丢弃多余素材
        if k != slot_count:
            # 简单处理: 只用前 slot_count 个
            assets = assets[:slot_count]
            k = slot_count

        # 分析每个素材的特征
        asset_features = []
        for a in assets:
            has_subject = bool(a.get("subject_tags") or a.get("has_subject"))
            subject_tags = a.get("subject_tags", [])
            # 判断位置
            content = (a.get("content_summary", "") + " " + " ".join(subject_tags)).lower()
            if any(w in content for w in ["上", "顶", "天空", "top", "upper", "上面"]):
                position = "upper"
            elif any(w in content for w in ["下", "底", "地面", "bottom", "lower", "下面"]):
                position = "lower"
            else:
                position = "middle"
            asset_features.append({
                "asset_id": a.get("asset_id", ""),
                "has_subject": has_subject,
                "position": position,
                "subject_tags": subject_tags,
            })

        # 匹配素材到 slot
        assignment = []
        used_assets = set()
        total_score = 0.0

        for slot_idx, slot in enumerate(template.slots):
            best_asset = None
            best_score = 0.0

            for af in asset_features:
                if af["asset_id"] in used_assets:
                    continue

                score = self._slot_match_score(af, slot)
                if score > best_score:
                    best_score = score
                    best_asset = af

            if best_asset:
                used_assets.add(best_asset["asset_id"])
                assignment.append({
                    "asset_id": best_asset["asset_id"],
                    "slot_index": slot_idx,
                    "match_score": best_score,
                })
                total_score += best_score
            else:
                # 没找到匹配的素材, 用未分配的
                for af in asset_features:
                    if af["asset_id"] not in used_assets:
                        used_assets.add(af["asset_id"])
                        assignment.append({
                            "asset_id": af["asset_id"],
                            "slot_index": slot_idx,
                            "match_score": 0.0,
                        })
                        break

        avg_score = total_score / max(len(template.slots), 1)

        # 偏好简单拼接模板: needs_segmentation=False 的模板加偏好分
        # 用户说"三拼/拼起来"时, 默认意图是简单堆叠, 不是 overlay 分割
        if not template.needs_segmentation:
            avg_score += 0.15

        reason = self._build_reason(assets, template, avg_score)
        return avg_score, assignment, reason

    def _slot_match_score(self, asset_feature: dict, slot: SlotConstraint) -> float:
        """计算单个素材与单个 slot 的匹配分数 (0-1)。"""
        score = 0.5  # 基础分

        # 主体要求
        if slot.subject_required:
            if asset_feature["has_subject"]:
                score += 0.3
            else:
                score -= 0.3
        else:
            # 不需要主体的 slot, 有主体也可以但不是必须
            if not asset_feature["has_subject"]:
                score += 0.1  # 纯背景素材更适合背景 slot

        # 位置偏好
        if slot.position_preference != "any":
            if asset_feature["position"] == slot.position_preference:
                score += 0.2
            else:
                score -= 0.1

        return max(0.0, min(1.0, score))

    def _build_reason(self, assets, template, score) -> str:
        """构建匹配理由。"""
        if score > 0.8:
            return f"高度匹配: {len(assets)}个素材符合模板'{template.name}'的{template.slot_count}个slot约束"
        elif score > 0.5:
            return f"基本匹配: {len(assets)}个素材大致符合模板'{template.name}'"
        else:
            return f"勉强匹配: 素材特征与模板'{template.name}'约束有偏差"
