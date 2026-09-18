"""Asset Searcher: 根据用户文本 query 从 SQLite 存储中检索最贴合的 K 张素材。

策略:
  1. query → BGE embedding (CPU)
  2. 从 SQLite 加载 search_embedding → numpy 矩阵
  3. cosine similarity 排序
  4. 返回 top-K

也支持标签搜索 (SQL LIKE) 和全文搜索 (SQL LIKE)。
"""
from __future__ import annotations

from typing import Any

import numpy as np

from .asset_store import AssetStore


class AssetSearcher:
    """素材搜索器: 基于 SQLite + 向量搜索。"""

    EMBEDDING_MODEL = "BAAI/bge-small-zh-v1.5"

    def __init__(
        self,
        index_rows: list[dict[str, Any]] | None = None,
        db_path: str | None = None,
        store: AssetStore | None = None,
    ) -> None:
        """初始化搜索器。

        Args:
            index_rows: 可选, 直接传入索引行 (兼容旧接口)
            db_path: SQLite 数据库路径
            store: 可选, 已有的 AssetStore 实例
        """
        self._rows = index_rows or []
        self._embeddings = None
        self._embedder = None
        self._store: AssetStore | None = store
        self._db_path = db_path

    def _get_store(self) -> AssetStore | None:
        """获取 AssetStore 实例 (延迟加载)。"""
        if self._store is not None:
            return self._store
        if self._db_path:
            self._store = AssetStore(self._db_path)
            return self._store
        return None

    def set_index(self, rows: list[dict[str, Any]]) -> None:
        """设置索引数据 (兼容旧接口, 从内存行加载)。"""
        self._rows = rows
        self._embeddings = None

    def _get_embedder(self):
        """延迟加载 embedding 模型。bge-small-zh 很小, 放 CPU 避免与 planner 争 GPU。"""
        if self._embedder is None:
            from sentence_transformers import SentenceTransformer
            self._embedder = SentenceTransformer(self.EMBEDDING_MODEL, device="cpu")
        return self._embedder

    def release_embedder(self):
        """释放 embedding 模型, 回收显存。"""
        if self._embedder is not None:
            import gc, torch
            del self._embedder
            self._embedder = None
            gc.collect()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
                torch.cuda.ipc_collect()
            print("  [searcher] embedder released, GPU cache cleared")

    def close(self):
        """关闭数据库连接。"""
        if self._store:
            self._store.close()
            self._store = None

    def _get_all_rows(self) -> list[dict[str, Any]]:
        """获取所有素材行 (优先从 SQLite, 其次从内存)。"""
        store = self._get_store()
        if store is not None:
            return store.get_all()
        return self._rows

    def _get_embeddings(self) -> np.ndarray:
        """获取所有素材的 search_embedding, 构成矩阵。"""
        if self._embeddings is not None:
            return self._embeddings

        store = self._get_store()
        if store is not None:
            mat, _ids = store.get_embeddings_matrix()
            if len(mat) > 0:
                self._embeddings = mat
                return mat

        # Fallback: from in-memory rows
        rows = self._get_all_rows()
        if rows:
            self._embeddings = np.array(
                [row.get("search_embedding", row.get("embedding", [])) for row in rows],
                dtype=np.float32,
            )
        return self._embeddings or np.array([])

    def search(
        self,
        query: str,
        k: int = 3,
        min_score: float = 0.1,
        filter_fn: callable | None = None,
        require_video: bool = True,
    ) -> list[dict[str, Any]]:
        """搜索最贴合的 K 张素材。

        Args:
            query: 用户文本查询
            k: 返回数量
            min_score: 最低相似度阈值
            filter_fn: 可选过滤函数 (row -> bool)
            require_video: 是否只返回有视频的素材 (默认 True)
                — live photo 容器 (jpg 内嵌 mp4) 也算有视频

        Returns:
            排序后的素材列表, 每个含 score 字段
        """
        rows = self._get_all_rows()
        if not rows:
            return []

        embedder = self._get_embedder()
        query_vec = embedder.encode(query, normalize_embeddings=True)

        embeddings = self._get_embeddings()
        if embeddings is None or len(embeddings) == 0:
            return []

        # cosine similarity (已 normalize, 直接点积)
        scores = embeddings @ query_vec

        # 构建结果
        results = []
        for i, row in enumerate(rows):
            score = float(scores[i])
            if score < min_score:
                continue
            if filter_fn and not filter_fn(row):
                continue
            if require_video and not self._has_video(row):
                continue
            result = dict(row)
            result["score"] = score
            results.append(result)

        # 排序
        results.sort(key=lambda r: r["score"], reverse=True)

        return results[:k]

    def _has_video(self, row: dict[str, Any]) -> bool:
        """Check if an asset has usable video content.

        An asset has video if:
        - It has a motion_path (standalone mp4 or pre-unpacked), OR
        - Its image_path is a live photo container (jpg with embedded mp4)
        """
        motion_path = row.get("motion_path")
        if motion_path and str(motion_path).strip():
            return True
        image_path = row.get("image_path")
        if image_path and str(image_path).lower().endswith((".jpg", ".jpeg")):
            # Check if it's a live photo container by looking at file size
            # Live photo containers are typically > 1MB (jpg + embedded mp4)
            try:
                from pathlib import Path
                p = Path(str(image_path))
                if p.exists() and p.stat().st_size > 500_000:
                    return True
            except Exception:
                pass
        return False

    def search_by_tags(
        self,
        tags: list[str],
        k: int = 3,
    ) -> list[dict[str, Any]]:
        """按标签搜索素材。"""
        store = self._get_store()
        if store is not None:
            # Use SQLite for tag search
            results = store.search_by_tags_any(tags)
            # Score by tag overlap
            for row in results:
                row_tags = set(row.get("subject_tags", []) + row.get("scene_tags", []) + row.get("content_tags", []))
                row["score"] = len(set(tags) & row_tags) / max(len(tags), 1)
            results.sort(key=lambda r: r["score"], reverse=True)
            return results[:k]

        # Fallback: in-memory
        rows = self._get_all_rows()
        if not rows:
            return []

        results = []
        for row in rows:
            row_tags = set(row.get("subject_tags", []) + row.get("scene_tags", []) + row.get("content_tags", []))
            overlap = len(set(tags) & row_tags)
            if overlap == 0:
                continue
            score = overlap / max(len(tags), 1)
            result = dict(row)
            result["score"] = score
            results.append(result)

        results.sort(key=lambda r: r["score"], reverse=True)
        return results[:k]

    def list_all(self) -> list[dict[str, Any]]:
        """返回所有索引素材。"""
        return self._get_all_rows()
