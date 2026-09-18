"""Asset Store: SQLite-based persistent storage for live photo assets.

Replaces the JSONL index with a proper database supporting:
  - Per-asset CRUD (create/read/update/delete)
  - Tag-based SQL queries
  - Vector embedding storage (BLOB, loaded to memory for cosine search)
  - Fingerprint-based incremental updates

Schema is Android-compatible (standard SQLite, no extensions needed).
"""
from __future__ import annotations

import json
import sqlite3
import hashlib
from pathlib import Path
from typing import Any, Iterator

import numpy as np


SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS assets (
    asset_id        TEXT PRIMARY KEY,
    image_path      TEXT,
    motion_path     TEXT,
    fingerprint     TEXT,
    content_summary TEXT,
    scene_tags      TEXT,   -- JSON array
    subject_tags    TEXT,   -- JSON array
    motion_tags     TEXT,   -- JSON array
    content_tags    TEXT,   -- JSON array
    tech_signals    TEXT,   -- JSON object
    search_text     TEXT,
    embedding       BLOB,   -- float32 numpy bytes
    search_embedding BLOB,  -- float32 numpy bytes
    version         TEXT,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_motion_path ON assets(motion_path);
CREATE INDEX IF NOT EXISTS idx_fingerprint ON assets(fingerprint);
"""


class AssetStore:
    """SQLite-backed asset storage with vector search support."""

    INDEX_VERSION = "v3"

    def __init__(self, db_path: Path | str | None = None) -> None:
        self.db_path = Path(db_path) if db_path else Path(".asset_store.db")
        self._conn: sqlite3.Connection | None = None
        self._init_db()

    def _init_db(self) -> None:
        """Initialize database and schema."""
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self.db_path))
        self._conn.row_factory = sqlite3.Row
        self._conn.executescript(SCHEMA_SQL)
        self._conn.commit()

    def close(self) -> None:
        if self._conn:
            self._conn.close()
            self._conn = None

    # ------------------------------------------------------------------
    # CRUD operations
    # ------------------------------------------------------------------

    def upsert(self, row: dict[str, Any]) -> None:
        """Insert or update a single asset record."""
        tags_json = lambda key: json.dumps(row.get(key, []), ensure_ascii=False)
        tech_json = json.dumps(row.get("tech_signals", {}), ensure_ascii=False)

        embedding = row.get("embedding")
        search_embedding = row.get("search_embedding")

        self._conn.execute(
            """
            INSERT OR REPLACE INTO assets (
                asset_id, image_path, motion_path, fingerprint,
                content_summary, scene_tags, subject_tags, motion_tags, content_tags,
                tech_signals, search_text, embedding, search_embedding, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                row["asset_id"],
                str(row.get("image_path", "")),
                str(row.get("motion_path") or ""),
                row.get("fingerprint", ""),
                row.get("content_summary", ""),
                tags_json("scene_tags"),
                tags_json("subject_tags"),
                tags_json("motion_tags"),
                tags_json("content_tags"),
                tech_json,
                row.get("search_text", ""),
                self._vec_to_blob(embedding),
                self._vec_to_blob(search_embedding),
                row.get("version", self.INDEX_VERSION),
            ),
        )
        self._conn.commit()

    def upsert_batch(self, rows: list[dict[str, Any]]) -> int:
        """Batch insert/update multiple assets. Returns count of upserted rows."""
        count = 0
        for row in rows:
            self.upsert(row)
            count += 1
        return count

    def get(self, asset_id: str) -> dict[str, Any] | None:
        """Get a single asset by ID."""
        cur = self._conn.execute("SELECT * FROM assets WHERE asset_id = ?", (asset_id,))
        row = cur.fetchone()
        return self._row_to_dict(row) if row else None

    def get_all(self) -> list[dict[str, Any]]:
        """Get all assets."""
        cur = self._conn.execute("SELECT * FROM assets ORDER BY asset_id")
        return [self._row_to_dict(r) for r in cur.fetchall()]

    def get_by_fingerprint(self, fingerprint: str) -> dict[str, Any] | None:
        """Find asset by fingerprint."""
        cur = self._conn.execute("SELECT * FROM assets WHERE fingerprint = ?", (fingerprint,))
        row = cur.fetchone()
        return self._row_to_dict(row) if row else None

    def delete(self, asset_id: str) -> bool:
        """Delete an asset. Returns True if a row was deleted."""
        cur = self._conn.execute("DELETE FROM assets WHERE asset_id = ?", (asset_id,))
        self._conn.commit()
        return cur.rowcount > 0

    def count(self) -> int:
        """Count total assets."""
        cur = self._conn.execute("SELECT COUNT(*) FROM assets")
        return cur.fetchone()[0]

    # ------------------------------------------------------------------
    # Query operations
    # ------------------------------------------------------------------

    def search_by_tag(self, tag: str, tag_column: str = "subject_tags") -> list[dict[str, Any]]:
        """Find assets containing a tag in the specified tag column.

        Args:
            tag: Tag to search for (e.g. "猫")
            tag_column: One of "subject_tags", "scene_tags", "motion_tags", "content_tags"

        Returns:
            List of matching assets
        """
        if tag_column not in ("subject_tags", "scene_tags", "motion_tags", "content_tags"):
            raise ValueError(f"Invalid tag column: {tag_column}")
        pattern = f'%"{tag}"%'
        cur = self._conn.execute(
            f"SELECT * FROM assets WHERE {tag_column} LIKE ? ORDER BY asset_id",
            (pattern,),
        )
        return [self._row_to_dict(r) for r in cur.fetchall()]

    def search_by_tags_any(self, tags: list[str]) -> list[dict[str, Any]]:
        """Find assets matching any of the given tags across all tag columns."""
        if not tags:
            return []
        conditions = []
        params: list[str] = []
        for tag in tags:
            pattern = f'%"{tag}"%'
            conditions.append(
                f"(subject_tags LIKE ? OR scene_tags LIKE ? OR motion_tags LIKE ? OR content_tags LIKE ?)"
            )
            params.extend([pattern, pattern, pattern, pattern])
        sql = f"SELECT * FROM assets WHERE {' OR '.join(conditions)} ORDER BY asset_id"
        cur = self._conn.execute(sql, params)
        return [self._row_to_dict(r) for r in cur.fetchall()]

    def search_by_text(self, text: str) -> list[dict[str, Any]]:
        """Full-text search on content_summary and search_text."""
        pattern = f"%{text}%"
        cur = self._conn.execute(
            "SELECT * FROM assets WHERE content_summary LIKE ? OR search_text LIKE ? ORDER BY asset_id",
            (pattern, pattern),
        )
        return [self._row_to_dict(r) for r in cur.fetchall()]

    def get_with_video(self) -> list[dict[str, Any]]:
        """Get all assets that have a motion_path."""
        cur = self._conn.execute(
            "SELECT * FROM assets WHERE motion_path IS NOT NULL AND motion_path != '' ORDER BY asset_id"
        )
        return [self._row_to_dict(r) for r in cur.fetchall()]

    # ------------------------------------------------------------------
    # Vector search
    # ------------------------------------------------------------------

    def get_embeddings_matrix(self) -> tuple[np.ndarray, list[str]]:
        """Load all search_embeddings into a numpy matrix for cosine search.

        Returns:
            (embeddings_matrix [N, D], asset_ids [N])
        """
        cur = self._conn.execute(
            "SELECT asset_id, search_embedding FROM assets WHERE search_embedding IS NOT NULL ORDER BY asset_id"
        )
        rows = cur.fetchall()
        if not rows:
            return np.array([]), []

        asset_ids = []
        embeddings = []
        for r in rows:
            vec = self._blob_to_vec(r["search_embedding"])
            if vec is not None and len(vec) > 0:
                asset_ids.append(r["asset_id"])
                embeddings.append(vec)

        if not embeddings:
            return np.array([]), []

        return np.array(embeddings, dtype=np.float32), asset_ids

    # ------------------------------------------------------------------
    # Migration helpers
    # ------------------------------------------------------------------

    @staticmethod
    def migrate_from_jsonl(jsonl_path: Path, db_path: Path) -> int:
        """Migrate existing JSONL index to SQLite. Returns migrated count."""
        if not jsonl_path.exists():
            return 0

        store = AssetStore(db_path)
        count = 0
        for line in jsonl_path.read_text().splitlines():
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                store.upsert(row)
                count += 1
            except json.JSONDecodeError:
                continue
        store.close()
        return count

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _vec_to_blob(vec: list[float] | np.ndarray | None) -> bytes | None:
        """Convert a float vector to BLOB (float32 bytes)."""
        if vec is None:
            return None
        arr = np.array(vec, dtype=np.float32)
        return arr.tobytes()

    @staticmethod
    def _blob_to_vec(blob: bytes | None) -> np.ndarray | None:
        """Convert BLOB back to float32 numpy array."""
        if blob is None:
            return None
        try:
            return np.frombuffer(blob, dtype=np.float32)
        except Exception:
            return None

    def _row_to_dict(self, row: sqlite3.Row) -> dict[str, Any]:
        """Convert a database row to a dict with parsed JSON fields."""
        d = dict(row)
        # Parse JSON array fields
        for key in ("scene_tags", "subject_tags", "motion_tags", "content_tags"):
            val = d.get(key)
            if isinstance(val, str) and val:
                try:
                    d[key] = json.loads(val)
                except json.JSONDecodeError:
                    d[key] = []
            elif val is None:
                d[key] = []
            else:
                d[key] = val

        # Parse tech_signals
        tech = d.get("tech_signals")
        if isinstance(tech, str) and tech:
            try:
                d["tech_signals"] = json.loads(tech)
            except json.JSONDecodeError:
                d["tech_signals"] = {}
        elif tech is None:
            d["tech_signals"] = {}

        # Convert embedding blobs to lists
        for key in ("embedding", "search_embedding"):
            blob = d.get(key)
            if isinstance(blob, bytes):
                vec = self._blob_to_vec(blob)
                d[key] = vec.tolist() if vec is not None else []
            elif blob is None:
                d[key] = []

        return d

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.close()
