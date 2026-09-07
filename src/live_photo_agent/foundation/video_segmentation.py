"""Template-driven video subject segmentation pipeline.

Pipeline: Mask2Former (candidate detection) -> user/automatic selection ->
Cutie temporal propagation -> bottom region restoration.

The pipeline is "template-driven": a SegmentationTemplate carries thresholds
and region configuration. When segmentation confidence is low, the pipeline
emits candidates for human review instead of silently producing a bad mask.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

import cv2  # noqa: E402
import numpy as np


class SegmentationStage(str, Enum):
    CANDIDATE_DETECTION = "candidate_detection"
    AWAITING_SELECTION = "awaiting_selection"
    TRACKING = "tracking"
    COMPOSITE = "composite"


@dataclass
class SegmentationTemplate:
    """Configuration template for the segmentation pipeline.

    Thresholds control when the pipeline defers to human judgment. When
    Mask2Former's best candidate coverage is below ``auto_select_min_coverage``
    the pipeline emits all candidates and pauses for selection.
    """

    # --- Region configuration ---
    region: str = "bottom_third"
    keep_region: bool = True

    # --- Mask2Former candidate filtering ---
    m2f_score_threshold: float = 0.5
    m2f_no_object_label: int = 134
    m2f_max_candidates: int = 10

    # --- Auto-selection threshold ---
    # If the best candidate covers at least this fraction of the frame,
    # automatically select it without human review.
    auto_select_min_coverage: float = 0.15

    # --- Cutie tracking configuration ---
    cutie_max_internal_size: int = 256

    # --- Output ---
    save_visualization: bool = True


@dataclass
class Candidate:
    """A single segmentation candidate from Mask2Former."""

    index: int
    label_id: int
    label_name: str
    score: float
    coverage: float
    bbox: tuple[int, int, int, int]
    mask: np.ndarray  # uint8 binary HxW

    def to_dict(self) -> dict[str, Any]:
        return {
            "index": self.index,
            "label_id": self.label_id,
            "label_name": self.label_name,
            "score": round(self.score, 4),
            "coverage": round(self.coverage, 4),
            "bbox": list(self.bbox),
        }


@dataclass
class SegmentationResult:
    """Result of the segmentation pipeline."""

    stage: SegmentationStage
    candidates: list[Candidate] = field(default_factory=list)
    selected_indices: list[int] = field(default_factory=list)
    mask_dir: str | None = None
    coverage: float = 0.0
    visualization_path: str | None = None
    metrics: dict[str, Any] = field(default_factory=dict)
    final_mask_dir: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "stage": self.stage.value,
            "candidates": [c.to_dict() for c in self.candidates],
            "selected_indices": self.selected_indices,
            "mask_dir": self.mask_dir,
            "coverage": round(self.coverage, 4),
            "visualization_path": self.visualization_path,
            "metrics": self.metrics,
            "final_mask_dir": self.final_mask_dir,
            "metadata": self.metadata,
        }


class VideoSegmentationPipeline:
    """Template-driven video segmentation with human-in-the-loop selection.

    Pipeline:
        1. Mask2Former detects candidates on frame 0
        2. Auto-select best candidate, or pause for human review
        3. Cutie propagates the selected mask across all frames
        4. Restore the bottom region (e.g. bottom 1/3) to full opacity
        5. Generate composite MP4
    """

    def __init__(self, template: SegmentationTemplate | None = None) -> None:
        self.template = template or SegmentationTemplate()
        self._m2f_model = None
        self._m2f_processor = None
        self._cutie_processor = None

    # ------------------------------------------------------------------
    # Lazy model loaders
    # ------------------------------------------------------------------

    def _load_mask2former(self):
        if self._m2f_model is not None:
            return
        import torch
        from transformers import Mask2FormerForUniversalSegmentation, AutoImageProcessor

        model_id = "facebook/mask2former-swin-tiny-coco-panoptic"
        self._m2f_processor = AutoImageProcessor.from_pretrained(model_id)
        self._m2f_model = (
            Mask2FormerForUniversalSegmentation.from_pretrained(model_id)
            .to("cuda")
            .eval()
            .requires_grad_(False)
        )

    def _unload_mask2former(self):
        import gc
        import torch

        self._m2f_model = None
        self._m2f_processor = None
        gc.collect()
        torch.cuda.empty_cache()

    def _load_cutie(self):
        if self._cutie_processor is not None:
            return
        import sys

        cutie_root = "/home/vivo/Cutie"
        if cutie_root not in sys.path:
            sys.path.insert(0, cutie_root)
        import torch
        from cutie.inference.inference_core import InferenceCore
        from cutie.utils.get_default_model import get_default_model
        from hydra.core.global_hydra import GlobalHydra

        try:
            GlobalHydra.instance().clear()
        except Exception:
            pass
        cutie = get_default_model()
        self._cutie_processor = InferenceCore(cutie, cfg=cutie.cfg)
        self._cutie_processor.max_internal_size = self.template.cutie_max_internal_size

    # ------------------------------------------------------------------
    # Step 1: Mask2Former candidate detection
    # ------------------------------------------------------------------

    def detect_candidates(self, frame_rgb: np.ndarray) -> list[Candidate]:
        """Run Mask2Former on a single frame and return filtered candidates."""
        import torch
        from PIL import Image

        self._load_mask2former()

        pil_img = Image.fromarray(frame_rgb)
        inputs = self._m2f_processor(images=pil_img, return_tensors="pt")
        inputs = {k: v.to("cuda") for k, v in inputs.items()}

        with torch.no_grad():
            outputs = self._m2f_model(**inputs)

        result = self._m2f_processor.post_process_panoptic_segmentation(
            outputs, target_sizes=[(frame_rgb.shape[0], frame_rgb.shape[1])]
        )[0]

        t = self.template
        candidates: list[Candidate] = []
        for seg in result["segments_info"]:
            label_id = seg["label_id"]
            if label_id == t.m2f_no_object_label:
                continue
            if seg.get("score", 0) < t.m2f_score_threshold:
                continue

            mask = (result["segmentation"] == seg["id"]).cpu().numpy()
            coverage = mask.sum() / mask.size
            ys, xs = np.where(mask)
            if len(ys) == 0:
                continue
            bbox = (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))
            label_name = self._m2f_model.config.id2label.get(label_id, f"label_{label_id}")

            candidates.append(
                Candidate(
                    index=seg["id"],
                    label_id=label_id,
                    label_name=label_name,
                    score=float(seg.get("score", 0)),
                    coverage=float(coverage),
                    bbox=bbox,
                    mask=mask.astype(np.uint8),
                )
            )

        candidates.sort(key=lambda c: c.coverage, reverse=True)
        return candidates[: t.m2f_max_candidates]

    # ------------------------------------------------------------------
    # Step 2: Candidate selection (auto or human)
    # ------------------------------------------------------------------

    # Background labels that should never be auto-selected as "subject"
    BG_LABELS = {
        "wall-other-merged", "wall-merged", "wall-tile-merged",
        "table-merged", "floor-merged", "ceiling-merged",
        "tv", "curtain-merged", "window-merged",
        "rug-merged", "wall-other", "rock-merged",
        "sky-other-merged", "grass-merged", "fence-merged",
        "mountain-merged", "tree-merged", "house",
        "sand-merged", "road-merged", "pavement-merged",
    }

    def select_candidates(
        self, candidates: list[Candidate], frame_rgb: np.ndarray
    ) -> tuple[np.ndarray, list[int]]:
        """Auto-select the best subject candidate.

        Prefers foreground objects (person, cat, dog, cup, bottle, etc.)
        over background classes (wall, table, sky, etc.).
        """
        t = self.template

        if not candidates:
            raise NeedsSelection(candidates)

        # Filter out background classes
        fg_candidates = [
            c for c in candidates if c.label_name not in self.BG_LABELS
        ]

        if not fg_candidates:
            # All candidates are background — emit for human review
            raise NeedsSelection(candidates)

        # Pick the largest foreground candidate
        best = max(fg_candidates, key=lambda c: c.coverage)

        if best.coverage >= t.auto_select_min_coverage:
            mask = best.mask.copy()
            selected = [best.index]
            return mask, selected

        # Below threshold: emit candidates for human selection
        raise NeedsSelection(candidates)

        best = candidates[0]
        if best.coverage >= t.auto_select_min_coverage:
            mask = best.mask.copy()
            selected = [best.index]
            return mask, selected

        # Below threshold: emit candidates for human selection
        raise NeedsSelection(candidates)

    # ------------------------------------------------------------------
    # Step 3: Cutie temporal propagation
    # ------------------------------------------------------------------

    def track_video(
        self,
        frames: list[np.ndarray],
        initial_mask: np.ndarray,
        output_dir: Path,
    ) -> list[np.ndarray]:
        """Propagate the initial mask across all frames via Cutie."""
        import torch
        from torchvision.transforms.functional import to_tensor
        from PIL import Image

        self._load_cutie()

        t = self.template
        h, w = frames[0].shape[:2]

        # Determine keep region boundaries
        if t.region == "bottom_third":
            keep_y_start = h * 2 // 3
            keep_y_end = h
        elif t.region == "bottom_two_thirds":
            keep_y_start = h // 3
            keep_y_end = h
        elif t.region == "bottom_half":
            keep_y_start = h // 2
            keep_y_end = h
        elif t.region == "top_third":
            keep_y_start = 0
            keep_y_end = h // 3
        elif t.region == "top_half":
            keep_y_start = 0
            keep_y_end = h // 2
        elif t.region == "middle_third":
            keep_y_start = h // 3
            keep_y_end = h * 2 // 3
        else:
            keep_y_start = 0
            keep_y_end = h

        # Prepare initial mask: index 1 = subject
        mask_np = initial_mask.astype(np.uint8)
        objects = [1]
        mask_tensor = torch.from_numpy(mask_np).cuda()

        output_masks: list[np.ndarray] = []
        for ti, frame in enumerate(frames):
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            image_tensor = to_tensor(Image.fromarray(rgb)).cuda().float()

            if ti == 0:
                output_prob = self._cutie_processor.step(
                    image_tensor, mask_tensor, objects=objects
                )
            else:
                output_prob = self._cutie_processor.step(image_tensor)

            out_mask = self._cutie_processor.output_prob_to_mask(output_prob)
            binary = (out_mask.cpu().numpy() == 1).astype(np.uint8) * 255

            # Build final mask: subject (cat) is on top of the strip.
            # The designated strip is always opaque (background kept).
            # The subject mask is preserved across the ENTIRE frame,
            # so a cat extending beyond the strip stays visible as overlay.
            if t.keep_region:
                # Start with the subject mask (full frame), then OR the strip
                # on top — subject pixels remain, strip fills in background
                strip = np.zeros_like(binary)
                strip[keep_y_start:keep_y_end] = 255
                binary = binary | strip  # subject OR strip = both visible

            output_masks.append(binary)
            cv2.imwrite(str(output_dir / f"{ti:04d}.png"), binary)

        return output_masks

    # ------------------------------------------------------------------
    # Visualization
    # ------------------------------------------------------------------

    def render_candidates(
        self, frame_bgr: np.ndarray, candidates: list[Candidate], output_path: Path
    ) -> None:
        """Render all candidates as a numbered overlay grid."""
        colors = [
            (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0),
            (255, 0, 255), (0, 255, 255), (128, 128, 0), (128, 0, 128),
            (0, 128, 128), (255, 128, 0),
        ]

        vis = frame_bgr.copy()
        for i, cand in enumerate(candidates):
            color = colors[i % len(colors)]
            overlay = vis.copy()
            overlay[cand.mask > 0] = color
            vis = cv2.addWeighted(vis, 0.6, overlay, 0.4, 0)
            x1, y1, x2, y2 = cand.bbox
            cv2.rectangle(vis, (x1, y1), (x2, y2), color, 3)
            label = f"#{cand.index} {cand.label_name} {cand.coverage*100:.1f}%"
            cv2.putText(vis, label, (x1, y1 - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.7, color, 2)

        cv2.imwrite(str(output_path), vis)

    # ------------------------------------------------------------------
    # Full pipeline
    # ------------------------------------------------------------------

    def run(
        self,
        video_path: Path,
        output_dir: Path,
        selected_indices: list[int] | None = None,
    ) -> SegmentationResult:
        """Run the full segmentation pipeline.

        If ``selected_indices`` is provided, use them to merge candidates.
        If not and auto-select threshold is met, auto-select.
        Otherwise return AWAITING_SELECTION with candidates for human review.
        """
        import cv2

        t = self.template
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        cap = cv2.VideoCapture(str(video_path))
        ret, frame = cap.read()
        if not ret:
            raise RuntimeError(f"Cannot read video: {video_path}")
        frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        cap.release()

        # Step 1: detect candidates
        candidates = self.detect_candidates(frame_rgb)
        self._unload_mask2former()

        # Step 2: selection
        if selected_indices is not None:
            mask = np.zeros((frame_rgb.shape[0], frame_rgb.shape[1]), dtype=np.uint8)
            for cand in candidates:
                if cand.index in selected_indices:
                    mask |= cand.mask
            selected = selected_indices
        else:
            try:
                mask, selected = self.select_candidates(candidates, frame_rgb)
            except NeedsSelection as e:
                vis_path = output_dir / "candidates.jpg"
                self.render_candidates(frame, e.candidates, vis_path)
                return SegmentationResult(
                    stage=SegmentationStage.AWAITING_SELECTION,
                    candidates=e.candidates,
                    visualization_path=str(vis_path),
                )

        # Render visualization of selected mask
        vis_path = output_dir / "initial_mask.jpg"
        self._render_mask_overlay(frame, mask, vis_path)

        # Step 3: load all frames and track
        frames = self._load_all_frames(video_path)
        mask_dir = output_dir / "masks"
        mask_dir.mkdir(exist_ok=True)
        masks = self.track_video(frames, mask, mask_dir)

        # Step 4: generate MP4
        mp4_path = output_dir / "segmentation.mp4"
        self._generate_mp4(video_path, masks, mp4_path)

        coverage = float(mask.sum() / mask.size)
        return SegmentationResult(
            stage=SegmentationStage.COMPOSITE,
            selected_indices=selected,
            mask_dir=str(mask_dir),
            final_mask_dir=str(mask_dir),
            coverage=coverage,
            visualization_path=str(vis_path),
            metrics={
                "mp4_path": str(mp4_path),
                "frame_count": len(masks),
                "region": self.template.region,
                "initial_coverage": round(coverage, 4),
            },
            metadata={
                "mp4_path": str(mp4_path),
                "frame_count": len(masks),
                "region": self.template.region,
            },
        )

    def _load_all_frames(self, video_path: Path) -> list[np.ndarray]:
        import cv2

        cap = cv2.VideoCapture(str(video_path))
        frames = []
        while True:
            ret, frame = cap.read()
            if not ret:
                break
            frames.append(frame)
        cap.release()
        return frames

    def _render_mask_overlay(
        self, frame_bgr: np.ndarray, mask: np.ndarray, output_path: Path
    ) -> None:
        import cv2

        h, w = frame_bgr.shape[:2]
        cx, cy = np.meshgrid(np.arange(w), np.arange(h))
        checker = ((cx // 20 + cy // 20) % 2 == 0).astype(np.uint8) * 100 + 80
        checker_rgb = np.stack([checker] * 3, axis=-1)
        alpha = np.clip(mask.astype(float) * 255, 0, 255) / 255.0
        alpha = alpha[:, :, None]
        composite = (frame_bgr * alpha + checker_rgb * (1 - alpha)).astype(np.uint8)
        cv2.imwrite(str(output_path), composite)

    def _generate_mp4(
        self,
        video_path: Path,
        masks: list[np.ndarray],
        output_path: Path,
    ) -> None:
        import cv2

        cap = cv2.VideoCapture(str(video_path))
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        fps = cap.get(cv2.CAP_PROP_FPS)

        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(output_path), fourcc, fps, (w, h))
        for i, mask in enumerate(masks):
            cap.set(cv2.CAP_PROP_POS_FRAMES, i)
            ret, frame = cap.read()
            if not ret:
                break
            cx, cy = np.meshgrid(np.arange(w), np.arange(h))
            checker = ((cx // 20 + cy // 20) % 2 == 0).astype(np.uint8) * 100 + 80
            checker_rgb = np.stack([checker] * 3, axis=-1)
            alpha = np.clip(mask.astype(float) / 255.0, 0, 1)[:, :, None]
            composite = (frame * alpha + checker_rgb * (1 - alpha)).astype(np.uint8)
            writer.write(composite)
        cap.release()
        writer.release()


class NeedsSelection(Exception):
    """Raised when the pipeline needs human selection among candidates."""

    def __init__(self, candidates: list[Candidate]) -> None:
        self.candidates = candidates
        super().__init__(
            f"Coverage below auto-select threshold. "
            f"{len(candidates)} candidates available for selection."
        )
