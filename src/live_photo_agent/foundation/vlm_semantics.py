from __future__ import annotations

import base64
import contextlib
import json
import re
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import Any, Generator

from ..config import settings
from ..models import AssetPreprocessSummary, LivePhotoAsset
from .media_ops import MediaOps

# Import codec utilities from the live_photo_transfer_and_save module without
# installing it as a package — it lives alongside this project.
_CLI_DIR = Path(__file__).resolve().parents[4] / "live_photo_transfer_and_save"
if str(_CLI_DIR) not in sys.path:
    sys.path.insert(0, str(_CLI_DIR))

try:
    from live_photo_cli import is_live_photo_container, unpack_motion_photo  # type: ignore[import]
    _CODEC_AVAILABLE = True
except ImportError:
    _CODEC_AVAILABLE = False


class VLMSemanticAnalyzer:
    """Generate semantic enrichment for any media asset via prompt-based VLM inference.

    Supports live photos (packed or split jpg+mp4), plain images, and standalone
    video files.  Media bytes are prepared inside a TemporaryDirectory context
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
                "producer": semantic_payload.get("producer", "endpoint"),
                "model": semantic_payload.get("model", settings.vlm_model or "vlm"),
                "source": semantic_payload.get("source", "endpoint"),
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
                "semantic_producer": semantic_payload.get("producer", "endpoint"),
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
        provider = "endpoint"
        model_name = settings.vlm_model or "vlm"
        source = "endpoint"
        summary_text = summary.content_summary or asset.image_path.stem
        scene_tags: list[str] = []
        subject_tags: list[str] = []
        motion_tags: list[str] = []
        audio_tags: list[str] = []
        search_keywords: list[str] = []
        theme = ""

        if settings.vlm_backend != "disabled" and settings.vlm_endpoint:
            try:
                remote = self._call_endpoint(asset=asset)
                if remote:
                    summary_text = str(remote.get("summary") or summary_text)
                    scene_tags = self._normalize_tags(list(remote.get("scene_tags", [])))
                    subject_tags = self._normalize_tags(list(remote.get("subject_tags", [])))
                    motion_tags = self._normalize_tags(list(remote.get("motion_tags", [])))
                    audio_tags = self._normalize_tags(list(remote.get("audio_tags", [])))
                    search_keywords = self._normalize_tags(list(remote.get("search_keywords", [])))
                    theme = str(remote.get("theme") or "")
                else:
                    print(f"  [VLM] {asset.asset_id}: endpoint returned empty response", flush=True)
            except Exception as exc:  # noqa: BLE001
                print(f"  [VLM] {asset.asset_id}: call failed — {exc}", flush=True)
                raise

        if not summary_text:
            summary_text = summary.content_summary or asset.image_path.stem

        return {
            "producer": provider,
            "model": model_name,
            "source": source,
            "summary": summary_text,
            "scene_tags": scene_tags,
            "subject_tags": subject_tags,
            "motion_tags": motion_tags,
            "audio_tags": audio_tags,
            "search_keywords": search_keywords,
            "theme": theme,
            "version": "v1",
        }

    def _call_endpoint(self, asset: LivePhotoAsset) -> dict[str, Any] | None:
        endpoint = settings.vlm_endpoint
        if not endpoint:
            return None

        with self._media_bytes(asset) as (image_bytes, video_bytes):
            if not image_bytes and not video_bytes:
                return None

            has_video = bool(video_bytes)
            has_image = bool(image_bytes)
            user_prompt = self._build_user_prompt(asset=asset, has_video=has_video, has_image=has_image)

            # qwen-omni-turbo rejects mixed modality (video + image in same request).
            # Prefer video when available (richer AV context), fall back to image only.
            if has_video:
                encoded_video = base64.b64encode(video_bytes).decode("ascii")
                content: list[dict[str, Any]] = [
                    {"type": "text", "text": user_prompt},
                    {"type": "video_url", "video_url": {"url": f"data:video/mp4;base64,{encoded_video}"}},
                ]
            else:
                encoded_image = base64.b64encode(image_bytes).decode("ascii")
                suffix = asset.image_path.suffix.lower().lstrip(".")
                mime = "jpeg" if suffix in {"jpg", "jpeg"} else suffix or "jpeg"
                content = [
                    {"type": "text", "text": user_prompt},
                    {"type": "image_url", "image_url": {"url": f"data:image/{mime};base64,{encoded_image}"}},
                ]

            payload = {
                "model": settings.vlm_model or "default",
                "messages": [
                    {"role": "system", "content": settings.vlm_prompt or self._default_prompt()},
                    {"role": "user", "content": content},
                ],
            }
            data = json.dumps(payload).encode("utf-8")
            headers: dict[str, str] = {"Content-Type": "application/json"}
            if settings.qwen_auth_token:
                headers["Authorization"] = f"Bearer {settings.qwen_auth_token}"
            if settings.qwen_workspace_id:
                headers["X-DashScope-WorkSpace"] = settings.qwen_workspace_id
                headers["X-DashScope-Workspace"] = settings.qwen_workspace_id
            req = urllib.request.Request(endpoint, data=data, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=float(settings.vlm_timeout_seconds)) as response:
                raw = response.read().decode("utf-8")

        return self._extract_payload(json.loads(raw))

    @contextlib.contextmanager
    def _media_bytes(self, asset: LivePhotoAsset) -> Generator[tuple[bytes, bytes], None, None]:
        """Yield (image_bytes, video_bytes).

        If the image path is a packed live-photo container, unpack it to a
        TemporaryDirectory, read the bytes, then let the context manager delete
        the temp dir on exit — no intermediate files survive the call.
        """
        with tempfile.TemporaryDirectory(prefix="vlm-unpack-") as tmp_dir:
            tmp = Path(tmp_dir)
            image_bytes = b""
            video_bytes = b""

            image_path = asset.image_path
            motion_path = asset.motion_path

            # Packed single-file live photo: unpack to temp, read, discard.
            if _CODEC_AVAILABLE and image_path.exists() and is_live_photo_container(image_path):
                unpacked_jpg, unpacked_mp4 = unpack_motion_photo(image_path, out_dir=tmp)
                image_bytes = unpacked_jpg.read_bytes()
                video_bytes = unpacked_mp4.read_bytes()

            else:
                # Already split, image-only, or video-only: read directly.
                if image_path.exists() and image_path.is_file():
                    try:
                        image_bytes = image_path.read_bytes()
                    except Exception:  # noqa: BLE001
                        pass
                if motion_path and motion_path.exists():
                    try:
                        video_bytes = motion_path.read_bytes()
                    except Exception:  # noqa: BLE001
                        pass

            yield image_bytes, video_bytes
            # TemporaryDirectory.__exit__ deletes tmp_dir here automatically.

    def _extract_payload(self, parsed: Any) -> dict[str, Any] | None:
        if isinstance(parsed, dict):
            if isinstance(parsed.get("choices"), list) and parsed["choices"]:
                message = parsed["choices"][0].get("message", {})
                if isinstance(message, dict):
                    content = message.get("content", "")
                    if isinstance(content, str):
                        return self._parse_model_output(content)
                    if isinstance(content, list):
                        for block in content:
                            if isinstance(block, dict) and isinstance(block.get("text"), str):
                                return self._parse_model_output(block["text"])
            if isinstance(parsed.get("output"), dict):
                return parsed["output"]
            if isinstance(parsed.get("result"), dict):
                return parsed["result"]
            if isinstance(parsed.get("data"), dict):
                return parsed["data"]
            if isinstance(parsed.get("summary"), str) or isinstance(parsed.get("scene_tags"), list):
                return parsed
        return None

    def _parse_model_output(self, content: str) -> dict[str, Any] | None:
        cleaned = content.strip()
        if cleaned.startswith("```"):
            cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
            cleaned = re.sub(r"\s*```$", "", cleaned)
        try:
            parsed = json.loads(cleaned)
        except json.JSONDecodeError:
            return {"summary": cleaned}
        if isinstance(parsed, dict):
            return parsed
        return None

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
