"""VLM Scorer: 用 VLM 对候选组合进行视觉协调性打分。

对每个候选 (素材组合 × 模板), VLM 评估:
  1. 主题协调性: 素材内容是否协调
  2. 视觉协调性: 颜色/构图/风格是否一致
  3. 布局合理性: 素材在模板中的位置是否合理

返回 0-1 分数 + 评语。
"""
from __future__ import annotations

import json
import base64
import tempfile
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen

from ...config import settings


class VLMScorer:
    """VLM 打分器: 评估候选组合的视觉协调性。"""

    def __init__(self) -> None:
        self.endpoint = settings.vlm_endpoint
        self.model = settings.vlm_model
        self.timeout = float(settings.vlm_timeout_seconds)

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
        if not self.endpoint:
            # VLM 不可用, 用启发式打分
            return self._heuristic_score(assets, template, assignment)

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
        """调用 VLM 打分。"""
        # 构建打分 prompt
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

        # 调用 VLM
        request_body = json.dumps({
            "model": self.model or "default",
            "messages": [
                {"role": "system", "content": "你是一个视觉设计专家, 评估素材组合的协调性。"},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.3,
            "max_tokens": 256,
        }).encode()

        req = Request(
            self.endpoint,
            data=request_body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        with urlopen(req, timeout=self.timeout) as response:
            result = json.loads(response.read())

        content = result["choices"][0]["message"]["content"]
        # 解析 VLM 返回的 JSON
        scores = self._parse_vlm_response(content)

        overall = scores.get("overall", 50) / 100.0
        return {
            "score": overall,
            "reason": scores.get("reason", ""),
            "details": scores,
        }

    def _parse_vlm_response(self, content: str) -> dict[str, Any]:
        """解析 VLM 返回的 JSON。"""
        # 尝试直接解析
        try:
            return json.loads(content)
        except json.JSONDecodeError:
            pass

        # 尝试提取 JSON 块
        import re
        match = re.search(r'\{[^}]+\}', content, re.DOTALL)
        if match:
            try:
                return json.loads(match.group())
            except json.JSONDecodeError:
                pass

        return {"overall": 50, "reason": "parse_failed"}

    def _heuristic_score(
        self,
        assets: list[dict[str, Any]],
        template: dict[str, Any],
        assignment: list[dict[str, str]],
    ) -> dict[str, Any]:
        """启发式打分 (VLM 不可用时的兜底)。"""
        score = 0.5

        # 主题协调性: 标签重叠度
        all_tags = []
        for a in assets:
            all_tags.extend(a.get("subject_tags", []))
            all_tags.extend(a.get("scene_tags", []))

        if all_tags:
            from collections import Counter
            tag_counts = Counter(all_tags)
            # 有重复标签说明主题一致
            overlap = sum(c - 1 for c in tag_counts.values() if c > 1)
            theme_score = min(1.0, 0.5 + overlap * 0.15)
            score = (score + theme_score) / 2

        # 分配匹配度
        if assignment:
            avg_match = sum(a.get("match_score", 0.5) for a in assignment) / len(assignment)
            score = (score + avg_match) / 2

        return {
            "score": score,
            "reason": f"heuristic: tag_overlap={overlap if all_tags else 0}, match={avg_match if assignment else 0.5:.2f}",
            "details": {"method": "heuristic"},
        }
