"""Asset Indexer: 离线预处理 Live Photo 素材, 生成 content_summary + embedding 索引。

每条素材处理一次:
  1. VLM 语义标注 (content_summary, scene_tags, subject_tags, motion_tags)
  2. BGE embedding (content_summary → 512维向量)
  3. 技术信号 (分辨率, 帧数, 时长)
  4. 写入 SQLite 数据库 (AssetStore)

增量更新: 只处理新增/变更的素材, 复用已有索引。
"""
from __future__ import annotations

import json
import hashlib
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from .asset_store import AssetStore


class AssetIndexer:
    """离线素材索引构建器。"""

    INDEX_VERSION = "v3"
    EMBEDDING_MODEL = "BAAI/bge-small-zh-v1.5"

    def __init__(self, index_path: Path | None = None, db_path: Path | None = None) -> None:
        """初始化索引器。

        Args:
            index_path: 旧 JSONL 路径 (用于自动迁移, 可选)
            db_path: SQLite 数据库路径 (优先使用)
        """
        # Determine db_path: explicit > derived from index_path > default
        if db_path is not None:
            self.db_path = Path(db_path)
        elif index_path is not None:
            # If index_path is .asset_search_index.jsonl, use .asset_store.db in same dir
            self.db_path = index_path.parent / ".asset_store.db"
            self._jsonl_path = index_path
        else:
            self.db_path = Path(".asset_store.db")
            self._jsonl_path = Path(".asset_search_index.jsonl")

        self._embedder = None
        self._store: AssetStore | None = None

    def _get_store(self) -> AssetStore:
        """获取/创建 AssetStore 实例。"""
        if self._store is None:
            self._store = AssetStore(self.db_path)
            # Auto-migrate from JSONL if db is empty and JSONL exists
            if self._store.count() == 0:
                jsonl = getattr(self, "_jsonl_path", None)
                if jsonl and jsonl.exists():
                    migrated = AssetStore.migrate_from_jsonl(jsonl, self.db_path)
                    if migrated > 0:
                        print(f"  [indexer] Migrated {migrated} assets from JSONL to SQLite")
        return self._store

    def _get_embedder(self):
        """延迟加载 embedding 模型。bge-small-zh 很小, 放 CPU 避免与 planner 争 GPU。"""
        if self._embedder is None:
            from sentence_transformers import SentenceTransformer
            self._embedder = SentenceTransformer(self.EMBEDDING_MODEL, device="cpu")
        return self._embedder

    def release_embedder(self):
        """释放 embedding 模型。"""
        if self._embedder is not None:
            import gc
            del self._embedder
            self._embedder = None
            gc.collect()

    def close(self):
        """关闭数据库连接。"""
        if self._store:
            self._store.close()
            self._store = None

    def build(
        self,
        assets: list[dict[str, Any]],
        force_rebuild: bool = False,
    ) -> dict[str, int]:
        """构建/增量更新素材索引。

        Args:
            assets: 素材列表, 每个素材含 asset_id, image_path, motion_path, vlm_summary
            force_rebuild: 是否强制重建所有素材

        Returns:
            统计信息 {total, rebuilt, reused, failed}
        """
        store = self._get_store()

        rebuilt = 0
        reused = 0
        failed = 0

        for asset in sorted(assets, key=lambda a: a.get("asset_id", "")):
            asset_id = asset.get("asset_id", "")
            image_path = asset.get("image_path", "")
            motion_path = asset.get("motion_path")

            fingerprint = self._fingerprint(image_path, motion_path)

            prev = store.get(asset_id)
            if not force_rebuild and prev and prev.get("fingerprint") == fingerprint and prev.get("version") == self.INDEX_VERSION:
                reused += 1
                continue

            try:
                row = self._index_one(asset, fingerprint)
                store.upsert(row)
                rebuilt += 1
                print(f"  [indexer] indexed {asset_id}")
            except Exception as e:
                print(f"  [indexer] FAILED {asset_id}: {e}")
                failed += 1

        return {
            "total": len(assets),
            "rebuilt": rebuilt,
            "reused": reused,
            "failed": failed,
        }

    def _index_one(self, asset: dict[str, Any], fingerprint: str) -> dict[str, Any]:
        """索引单个素材。"""
        asset_id = asset.get("asset_id", "")
        image_path = asset.get("image_path", "")
        motion_path = asset.get("motion_path")
        vlm_summary = asset.get("vlm_summary") or asset.get("preprocess_summary")

        # content_summary: 优先用 VLM 生成的, 否则用文件名
        if vlm_summary and isinstance(vlm_summary, dict):
            content_summary = vlm_summary.get("content_summary", "") or vlm_summary.get("summary", "")
            scene_tags = vlm_summary.get("scene_tags", [])
            subject_tags = vlm_summary.get("subject_tags", [])
            motion_tags = vlm_summary.get("motion_tags", [])
            content_tags = vlm_summary.get("content_tags", [])
        else:
            content_summary = Path(image_path).stem
            scene_tags = []
            subject_tags = []
            motion_tags = []
            content_tags = []

        # embedding
        embedder = self._get_embedder()
        embedding = embedder.encode(content_summary, normalize_embeddings=True)
        embedding_list = embedding.tolist()

        # 技术信号
        tech = self._extract_tech_signals(image_path, motion_path)

        # 搜索文本 (拼接所有标签用于搜索)
        search_text = " ".join(filter(None, [content_summary] + scene_tags + subject_tags + motion_tags + content_tags))
        search_embedding = embedder.encode(search_text, normalize_embeddings=True).tolist()

        return {
            "version": self.INDEX_VERSION,
            "asset_id": asset_id,
            "image_path": str(image_path),
            "motion_path": str(motion_path) if motion_path else "",
            "fingerprint": fingerprint,
            "content_summary": content_summary,
            "scene_tags": scene_tags,
            "subject_tags": subject_tags,
            "motion_tags": motion_tags,
            "content_tags": content_tags,
            "tech_signals": tech,
            "embedding": embedding_list,
            "search_embedding": search_embedding,
            "search_text": search_text,
        }

    def _fingerprint(self, image_path: str, motion_path: str | None) -> str:
        """计算素材指纹 (基于文件大小+修改时间)。"""
        parts = []
        p = Path(image_path)
        if p.exists():
            parts.append(f"{p.stat().st_size}:{p.stat().st_mtime}")
        if motion_path:
            mp = Path(motion_path)
            if mp.exists():
                parts.append(f"{mp.stat().st_size}:{mp.stat().st_mtime}")
        return hashlib.md5("|".join(parts).encode()).hexdigest()

    def _extract_tech_signals(self, image_path: str, motion_path: str | None) -> dict[str, Any]:
        """提取技术信号。"""
        tech: dict[str, Any] = {}

        img = cv2.imread(image_path)
        if img is not None:
            tech["image_resolution"] = f"{img.shape[1]}x{img.shape[0]}"
            tech["image_channels"] = img.shape[2]

        if motion_path:
            cap = cv2.VideoCapture(motion_path)
            if cap.isOpened():
                tech["video_width"] = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
                tech["video_height"] = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
                tech["video_fps"] = cap.get(cv2.CAP_PROP_FPS)
                tech["video_frames"] = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
                tech["video_duration_s"] = tech["video_frames"] / max(tech["video_fps"], 1)
            cap.release()

        return tech

    def load_index(self) -> list[dict[str, Any]]:
        """加载完整索引。"""
        store = self._get_store()
        return store.get_all()

    def _read_index(self) -> dict[str, dict[str, Any]]:
        """读取现有索引 (以 asset_id 为 key)。"""
        store = self._get_store()
        return {row["asset_id"]: row for row in store.get_all()}
