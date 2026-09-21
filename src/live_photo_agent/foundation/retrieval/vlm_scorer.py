"""VLM Scorer: 用本地 VLM 对候选组合进行视觉协调性打分。

对每个候选 (素材组合 × 模板), VLM 评估:
  1. 主题协调性: 素材内容是否协调
  2. 视觉协调性: 颜色/构图/风格是否一致
  3. 布局合理性: 素材在模板中的位置是否合理

返回 0-1 分数 + 评语。
"""
from __future__ import annotations

import json
from typing import Any

from ..local_vlm import chat_text, parse_json_response


class VLMScorer:
    """VLM 打分器: 评估候选组合的视觉协调性。"""

    def score(
        self,
        assets: list[dict[str, Any]],
        template: dict[str, Any],
        assignment: list[dict[str, str]],
    ) -> dict[str, Any]:
        """对单个候选组合打分。

        Returns:
            {score: float, reason: str, details: dict}
        """
        try:
            return self._vlm_score(assets, template, assignment)
        except Exception as e:
            print(f"  [vlm_scorer] VLM failed: {e}, falling back to heuristic")
            return self._heuristic_score(assets, template, assignment)

    def _vlm_score(
        self,
        assets: list[dict[str, Any]],
        template: dict[str, Any],
        assignment: list[dict[str, str]],
    ) -> dict[str, Any]:
        """调用本地 VLM 打分。"""
        asset_descs = []
        for a in assets:
            asset_descs.append(
                f"- {a.get('asset_id', '')}: {a.get('content_summary', '')} "
                f"tags={a.get('subject_tags', [])}+{a.get('scene_tags', [])}"
            )

        template_desc = template.get("description", "")
        slot_desc = template.get("slots", [])

        prompt = f"""请评估以下素材组合与模板的匹配度, 返回 JSON。

素材列表:
{chr(10).join(asset_descs)}

模板: {template.get('name', '')}
模板说明: {template_desc}
模板slot: {json.dumps(slot_desc, ensure_ascii=False)}
素材分配: {json.dumps(assignment, ensure_ascii=False)}

请从以下维度打分 (0-100):
1. theme_harmony: 素材主题是否协调
2. visual_harmony: 视觉风格(颜色/构图)是否一致
3. layout_fit: 素材在模板中的位置是否合理
4. overall: 总体推荐度

返回JSON格式:
{{"theme_harmony": 80, "visual_harmony": 70, "layout_fit": 85, "overall": 78, "reason": "评语"}}
"""

        content = chat_text(
            prompt=prompt,
            system_prompt="你是一个视觉设计专家, 评估素材组合的协调性。",
            max_new_tokens=256,
        )

        scores = parse_json_response(content)
        if scores is None:
            scores = {"overall": 50, "reason": "parse_failed"}

        overall = float(scores.get("overall", 50)) / 100.0
        return {
            "score": overall,
            "reason": scores.get("reason", ""),
            "details": scores,
        }

    def _heuristic_score(
        self,
        assets: list[dict[str, Any]],
        template: dict[str, Any],
        assignment: list[dict[str, str]],
    ) -> dict[str, Any]:
        """启发式打分 (VLM 不可用时的兜底)。"""
        score = 0.5

        all_tags = []
        for a in assets:
            all_tags.extend(a.get("subject_tags", []))
            all_tags.extend(a.get("scene_tags", []))

        overlap = 0
        if all_tags:
            from collections import Counter
            tag_counts = Counter(all_tags)
            overlap = sum(c - 1 for c in tag_counts.values() if c > 1)
            theme_score = min(1.0, 0.5 + overlap * 0.15)
            score = (score + theme_score) / 2

        avg_match = 0.5
        if assignment:
            avg_match = sum(a.get("match_score", 0.5) for a in assignment) / len(assignment)
            score = (score + avg_match) / 2

        return {
            "score": score,
            "reason": f"heuristic: tag_overlap={overlap if all_tags else 0}, match={avg_match:.2f}",
            "details": {"method": "heuristic"},
        }
