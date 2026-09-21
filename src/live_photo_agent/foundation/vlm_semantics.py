from __future__ import annotations

import contextlib
import json
import re
import tempfile
from pathlib import Path
from typing import Any, Generator

from ..capability.live_photo_cli import is_live_photo_container, unpack_motion_photo
from ..config import settings
from .local_vlm import chat_with_images, parse_json_response
from ..models import AssetPreprocessSummary, LivePhotoAsset
from .media_ops import MediaOps


class VLMSemanticAnalyzer:
    """Generate semantic enrichment for any media asset via local VLM inference.

    Supports live photos (packed or split jpg+mp4), plain images, and standalone
    video files. Media bytes are prepared inside a TemporaryDirectory context
    manager — unpacked on entry, sent to the VLM, then discarded on exit.
    No intermediate files persist after the call.
    """

    def __init__(self, media_ops: MediaOps | None = None) -> None:
        self.media_ops = media_ops or MediaOps()

    def enrich_summary(self, asset: LivePhotoAsset, base_summary: AssetPreprocessSummary | None = None) -> dict[str, Any]:
        summary = base_summary or AssetPreprocessSummary(media_format="livephoto" if asset.motion_path else "photo")
        semantic_payload = self._build_semantic_payload(asset=asset, summary=summary)
        tags = self._normalize_tags(list(summary.content_tags) + list(semantic_payload.get("search_keywords", [])))
        content_summary = str(semantic_payload.get("summary") or summary.content_summary or asset.image_path.stem)
        coarse_semantics = dict(summary.coarse_semantics)
        coarse_semantics.update(
            {
                "scene": semantic_payload.get("scene_tags", []),
                "summary": content_summary,
                "subjects": semantic_payload.get("subject_tags", []),
                "motion": semantic_payload.get("motion_tags", []),
                "audio": semantic_payload.get("audio_tags", []),
                "theme": semantic_payload.get("theme", ""),
            }
        )
        semantic_signals = dict(summary.semantic_signals)
        semantic_signals.update(
            {
                "producer": semantic_payload.get("producer", "local_vlm"),
                "model": semantic_payload.get("model", "local_vlm"),
                "source": semantic_payload.get("source", "local_vlm"),
                "summary": content_summary,
                "scene_tags": semantic_payload.get("scene_tags", []),
                "subject_tags": semantic_payload.get("subject_tags", []),
                "motion_tags": semantic_payload.get("motion_tags", []),
                "audio_tags": semantic_payload.get("audio_tags", []),
                "search_keywords": semantic_payload.get("search_keywords", []),
                "theme": semantic_payload.get("theme", ""),
            }
        )
        provenance = dict(summary.provenance)
        provenance.update(
            {
                "semantic_producer": semantic_payload.get("producer", "local_vlm"),
                "semantic_version": semantic_payload.get("version", "v1"),
            }
        )
        return {
            "content_tags": tags,
            "content_summary": content_summary,
            "semantic_signals": semantic_signals,
            "coarse_semantics": coarse_semantics,
            "provenance": provenance,
        }

    def _build_semantic_payload(self, asset: LivePhotoAsset, summary: AssetPreprocessSummary) -> dict[str, Any]:
        provider = "local_vlm"
        model_name = "local_vlm"
        source = "local_vlm"

        try:
            payload = self._call_local_vlm(asset)
        except Exception as exc:
            payload = None
            print(f"  [vlm_semantics] local VLM failed for {asset.image_path.name}: {exc}")

        if payload is None:
            summary_text = summary.content_summary or asset.image_path.stem
            return {
                "producer": provider,
                "model": model_name,
                "source": source,
                "summary": summary_text,
                "scene_tags": [],
                "subject_tags": [],
                "motion_tags": [],
                "audio_tags": [],
                "search_keywords": [],
                "theme": "",
                "version": "v1",
            }

        payload.setdefault("producer", provider)
        payload.setdefault("model", model_name)
        payload.setdefault("source", source)
        payload.setdefault("version", "v1")
        return payload

    def _call_local_vlm(self, asset: LivePhotoAsset) -> dict[str, Any] | None:
        """调用本地 VLM 分析素材，返回语义 payload dict。"""
        from PIL import Image
        import cv2

        with self._media_context(asset) as (image_path, video_path, has_video, has_image):
            if not has_video and not has_image:
                return None

            images: list[Image.Image] = []
            if has_video and video_path and Path(video_path).exists():
                images = self._extract_key_frames(video_path)
                if not images and has_image and image_path and Path(image_path).exists():
                    images = [Image.open(image_path).convert("RGB")]
            elif has_image and image_path and Path(image_path).exists():
                images = [Image.open(image_path).convert("RGB")]

            if not images:
                return None

            prompt = self._build_user_prompt(asset=asset, has_video=has_video, has_image=has_image)
            system_prompt = self._default_prompt()

            text = chat_with_images(
                images=images,
                prompt=prompt,
                system_prompt=system_prompt,
                max_new_tokens=256,
            )

            parsed = parse_json_response(text)
            if parsed is None:
                return {"summary": text[:200] if text else asset.image_path.stem}

            return self._normalize_payload(parsed)

    def _extract_key_frames(self, video_path: str, max_frames: int = 4) -> list:
        """Extract evenly-spaced key frames from a video as PIL Images."""
        from PIL import Image
        import cv2

        cap = cv2.VideoCapture(str(video_path))
        if not cap.isOpened():
            return []

        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        if total <= 0:
            cap.release()
            return []

        indices = [int(total * (i + 0.5) / max_frames) for i in range(min(max_frames, total))]
        frames = []
        for idx in indices:
            cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
            ret, frame = cap.read()
            if ret:
                frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                h, w = frame_rgb.shape[:2]
                scale = 448 / max(h, w)
                if scale < 1.0:
                    frame_rgb = cv2.resize(frame_rgb, (int(w * scale), int(h * scale)))
                frames.append(Image.fromarray(frame_rgb))

        cap.release()
        return frames

    @contextlib.contextmanager
    def _media_context(self, asset: LivePhotoAsset) -> Generator[tuple, None, None]:
        """Yield (image_path, video_path, has_video, has_image).

        If the image path is a packed live-photo container, unpack it to a
        TemporaryDirectory; the temp dir is cleaned up on exit.
        """
        with tempfile.TemporaryDirectory(prefix="vlm-unpack-") as tmp_dir:
            tmp = Path(tmp_dir)
            image_path = asset.image_path
            motion_path = asset.motion_path

            if image_path.exists() and is_live_photo_container(image_path):
                unpacked_jpg, unpacked_mp4 = unpack_motion_photo(image_path, out_dir=tmp)
                image_path = unpacked_jpg
                motion_path = unpacked_mp4

            has_video = bool(motion_path and Path(motion_path).exists())
            has_image = bool(image_path and Path(image_path).exists())
            yield (image_path, motion_path, has_video, has_image)

    def _normalize_payload(self, parsed: dict[str, Any]) -> dict[str, Any]:
        """Normalize VLM response keys to the canonical schema."""
        return {
            "summary": str(parsed.get("summary") or parsed.get("content_summary") or ""),
            "theme": str(parsed.get("theme") or ""),
            "scene_tags": list(parsed.get("scene_tags") or []),
            "subject_tags": list(parsed.get("subject_tags") or []),
            "motion_tags": list(parsed.get("motion_tags") or []),
            "audio_tags": list(parsed.get("audio_tags") or []),
            "search_keywords": list(parsed.get("search_keywords") or []),
        }

    def _build_user_prompt(self, asset: LivePhotoAsset, has_video: bool, has_image: bool) -> str:
        if has_video and has_image:
            media_desc = "一段 live photo（包含视频和封面图）"
            observe_instruction = "请仔细观看视频内容（包括画面动态和声音）以及封面图，"
        elif has_video:
            media_desc = "一段视频"
            observe_instruction = "请仔细观看视频内容（包括画面动态和声音），"
        else:
            media_desc = "一张图片"
            observe_instruction = "请仔细分析图片内容，"
        return (
            f"这是{media_desc}，文件名: {asset.image_path.name}。\n"
            f"{observe_instruction}"
            "提取一个适合 agent 检索的语义摘要。\n"
            "输出必须是纯 JSON，字段包含：\n"
            "  summary      （1 句话，描述素材核心内容，贴近用户搜索语义）\n"
            "  theme        （1 个核心主题词）\n"
            "  scene_tags   （2-5 个场景标签）\n"
            "  subject_tags （2-5 个主体标签：人物、物体、事件、动作主体）\n"
            "  motion_tags  （1-3 个动作或动态描述标签，如无动态则写 static）\n"
            "  audio_tags   （1-3 个声音或环境标签，如无音频则写 no_audio）\n"
            "  search_keywords（至少 5 个可检索短词，涵盖主题、场景、人物、动作）\n"
            "不能包含任何解释文字，只输出 JSON。"
        )

    def _default_prompt(self) -> str:
        return (
            "你是一个面向 agent 检索的多媒体语义标注器。"
            "你的任务是通过观看视频/图片内容，生成可检索的语义摘要。"
            "请返回纯 JSON，字段包含 summary, theme, scene_tags, subject_tags, motion_tags, audio_tags, search_keywords。"
            "字段值简洁、稳定，适合用于后续检索和 chunk 构建，不含任何解释文字。"
        )

    def _normalize_tags(self, values: list[str]) -> list[str]:
        normalized: list[str] = []
        for value in values:
            if not value:
                continue
            text = str(value).strip().lower()
            text = re.sub(r"[^a-z0-9\u4e00-\u9fff]+", " ", text)
            text = " ".join(text.split())
            if not text:
                continue
            if text not in normalized:
                normalized.append(text)
        return normalized
