#!/usr/bin/env python3
"""Turn one language-grounded OWLv2 box into an EdgeTAM mask on AI Hub.

The prompt points are deterministic backend plumbing derived from the box. They
are never user input. The script refuses to submit EdgeTAM jobs unless the
upstream grounding evidence contains exactly one eligible candidate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path
from typing import Any

import numpy as np
import qai_hub as hub
import torch
from PIL import Image
from qai_hub_models.utils.inference import AsyncOnDeviceModel


EDGE_SIZE = 1024
BOX_MARGIN_FRACTION = 0.15
MAX_MASK_COVERAGE = 0.25
MAX_OUTSIDE_BOX_FRACTION = 0.25


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a fail-closed OWLv2-box to EdgeTAM-mask probe."
    )
    parser.add_argument("--encoder-model-id", required=True)
    parser.add_argument("--decoder-model-id", required=True)
    parser.add_argument("--grounding-evidence", required=True, type=Path)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--device", default="Samsung Galaxy S26")
    parser.add_argument("--device-os", default="16")
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    for path in (args.grounding_evidence, args.image):
        if not path.is_file():
            parser.error(f"file does not exist: {path}")
    return args


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def select_device(name: str, os_version: str) -> hub.Device:
    matches = [device for device in hub.get_devices(name=name) if device.os == os_version]
    if len(matches) != 1:
        raise RuntimeError(
            f"expected one AI Hub device for {name!r} OS {os_version!r}; "
            f"found {len(matches)}"
        )
    return matches[0]


def resolve_output(outputs: dict[str, list[np.ndarray]], name: str) -> np.ndarray:
    matching = [key for key in outputs if key == name or key.split(":")[0] == name]
    if len(matching) != 1 or len(outputs[matching[0]]) != 1:
        raise RuntimeError(f"expected one output tensor named {name!r}; got {outputs.keys()}")
    return outputs[matching[0]][0]


def wait_for_outputs(job: hub.InferenceJob) -> dict[str, list[np.ndarray]]:
    status = job.wait()
    if not status.success:
        raise RuntimeError(f"AI Hub job {job.job_id} failed: {status.message}")
    outputs = job.download_output_data()
    if not isinstance(outputs, dict):
        raise RuntimeError(f"AI Hub job {job.job_id} returned no tensor dictionary")
    return outputs


def tensor_to_numpy(value: torch.Tensor | np.ndarray) -> np.ndarray:
    if isinstance(value, torch.Tensor):
        return value.detach().cpu().numpy()
    return value


def write_blocked_evidence(
    output_dir: Path,
    grounding_path: Path,
    status: str,
    candidate_count: int,
) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    path = output_dir / "mask-evidence.json"
    path.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "probe": "aihub_edgetam_mask_grounding",
                "read_only": True,
                "status": "Blocked",
                "blocked_by": "OWLv2Grounding",
                "upstream_status": status,
                "candidate_count": candidate_count,
                "upstream_evidence": str(grounding_path),
                "remote_jobs_submitted": 0,
                "content_mutation_allowed": False,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return path


def prompt_from_box(
    box: list[float], image_width: int, image_height: int
) -> tuple[np.ndarray, np.ndarray]:
    x1, y1, x2, y2 = box
    center_x = (x1 + x2) / 2
    height = y2 - y1
    points = np.array(
        [[center_x, y1 + height * 0.5], [center_x, y1 + height * 0.65]],
        dtype=np.float32,
    )
    points[:, 0] = np.clip(points[:, 0] / image_width, 0.0, 1.0)
    points[:, 1] = np.clip(points[:, 1] / image_height, 0.0, 1.0)
    labels = np.ones((1, 2), dtype=np.float32)
    return points[None, ...], labels


def outside_expanded_box_fraction(
    mask: np.ndarray,
    box: list[float],
) -> float:
    x1, y1, x2, y2 = box
    margin_x = (x2 - x1) * BOX_MARGIN_FRACTION
    margin_y = (y2 - y1) * BOX_MARGIN_FRACTION
    left = max(0, int(np.floor(x1 - margin_x)))
    top = max(0, int(np.floor(y1 - margin_y)))
    right = min(mask.shape[1], int(np.ceil(x2 + margin_x)))
    bottom = min(mask.shape[0], int(np.ceil(y2 + margin_y)))
    selected = int(mask.sum())
    if selected == 0:
        return 1.0
    inside = int(mask[top:bottom, left:right].sum())
    return (selected - inside) / selected


def mask_overlay(image: Image.Image, mask: np.ndarray) -> Image.Image:
    rgb = np.asarray(image, dtype=np.float32).copy()
    selected = mask.astype(bool)
    color = np.array([0.0, 255.0, 0.0], dtype=np.float32)
    rgb[selected] = rgb[selected] * 0.55 + color * 0.45
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))


def main() -> None:
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    grounding = json.loads(args.grounding_evidence.read_text(encoding="utf-8"))
    candidates = grounding.get("candidates", [])
    upstream_status = grounding.get("decision", {}).get("status", "Unknown")
    if upstream_status != "CandidateSetReady" or len(candidates) != 1:
        path = write_blocked_evidence(
            args.output_dir,
            args.grounding_evidence,
            upstream_status,
            len(candidates),
        )
        print(path)
        raise SystemExit(2)

    image_digest = sha256(args.image)
    if grounding.get("input", {}).get("sha256") != image_digest:
        raise RuntimeError("image hash does not match upstream grounding evidence")

    started = time.monotonic()
    image = Image.open(args.image).convert("RGB")
    candidate = candidates[0]
    prompt_coords, labels = prompt_from_box(
        candidate["box_xyxy"], image.width, image.height
    )
    resized = image.resize((EDGE_SIZE, EDGE_SIZE), Image.Resampling.BILINEAR)
    image_input = np.asarray(resized, dtype=np.float32)[None, ...] / 255.0

    device = select_device(args.device, args.device_os)
    encoder_model = hub.get_model(args.encoder_model_id)
    decoder_model = hub.get_model(args.decoder_model_id)
    encoder_has_producer = encoder_model.get_producer() is not None
    decoder_has_producer = decoder_model.get_producer() is not None
    if encoder_has_producer != decoder_has_producer:
        raise RuntimeError("encoder and decoder must use the same remote adapter type")

    if encoder_has_producer:
        remote_adapter = "compiled_model_channel_adapter"
        encoder = AsyncOnDeviceModel(
            encoder_model,
            ["image", "unnorm_coords", "labels"],
            device,
            output_names=[
                "image_embeddings",
                "high_res_features1",
                "high_res_features2",
                "sparse_embedding",
                "pix_feat",
            ],
        )
        source_image = np.transpose(image_input, (0, 3, 1, 2))
        encoder_result = encoder(source_image, prompt_coords, labels)
        encoder_values = encoder_result.wait()
        if not isinstance(encoder_values, tuple) or len(encoder_values) != 5:
            raise RuntimeError("compiled EdgeTAM encoder returned unexpected outputs")
        _, high_res_1, high_res_2, sparse_embedding, pix_feat = encoder_values
        encoder_job = encoder_result.inference_job

        decoder = AsyncOnDeviceModel(
            decoder_model,
            [
                "image_embeddings",
                "high_res_features1",
                "high_res_features2",
                "sparse_embedding",
            ],
            device,
            output_names=["masks", "scores", "obj_ptr", "object_score_logits"],
        )
        decoder_result = decoder(
            pix_feat,
            high_res_1,
            high_res_2,
            sparse_embedding,
        )
        decoder_values = decoder_result.wait()
        if not isinstance(decoder_values, tuple) or len(decoder_values) != 4:
            raise RuntimeError("compiled EdgeTAM decoder returned unexpected outputs")
        low_resolution_logits, score_output, _, object_logit_output = decoder_values
        decoder_job = decoder_result.inference_job
        low_resolution_logits = tensor_to_numpy(low_resolution_logits)
        score_output = tensor_to_numpy(score_output)
        object_logit_output = tensor_to_numpy(object_logit_output)
    else:
        remote_adapter = "raw_dlc_metadata_adapter"
        encoder_job = hub.submit_inference_job(
            model=encoder_model,
            device=device,
            inputs={
                "image": [image_input],
                "unnorm_coords": [prompt_coords],
                "labels": [labels],
            },
            name="lpa-edgetam-language-mask-encoder",
            profile=False,
        )
        if not isinstance(encoder_job, hub.InferenceJob):
            raise RuntimeError("AI Hub returned multiple encoder jobs")
        encoder_outputs = wait_for_outputs(encoder_job)

        decoder_job = hub.submit_inference_job(
            model=decoder_model,
            device=device,
            inputs={
                "image_embeddings": [resolve_output(encoder_outputs, "pix_feat")],
                "high_res_features1": [
                    resolve_output(encoder_outputs, "high_res_features1")
                ],
                "high_res_features2": [
                    resolve_output(encoder_outputs, "high_res_features2")
                ],
                "sparse_embedding": [
                    resolve_output(encoder_outputs, "sparse_embedding")
                ],
            },
            name="lpa-edgetam-language-mask-decoder",
            profile=False,
        )
        if not isinstance(decoder_job, hub.InferenceJob):
            raise RuntimeError("AI Hub returned multiple decoder jobs")
        decoder_outputs = wait_for_outputs(decoder_job)
        low_resolution_logits = resolve_output(decoder_outputs, "masks")
        score_output = resolve_output(decoder_outputs, "scores")
        object_logit_output = resolve_output(
            decoder_outputs, "object_score_logits"
        )

    logits_2d = np.squeeze(low_resolution_logits).astype(np.float32)
    resized_logits = Image.fromarray(logits_2d).resize(
        image.size, Image.Resampling.BILINEAR
    )
    mask = np.asarray(resized_logits) > 0.0
    overlay_path = args.output_dir / "mask-overlay.png"
    mask_overlay(image, mask).save(overlay_path)
    mask_path = args.output_dir / "mask.png"
    Image.fromarray(mask.astype(np.uint8) * 255).save(mask_path)

    score = float(np.squeeze(score_output))
    object_logit = float(np.squeeze(object_logit_output))
    coverage = float(mask.mean())
    outside_fraction = outside_expanded_box_fraction(
        mask, candidate["box_xyxy"]
    )
    mask_quality_passed = (
        object_logit > 0
        and 0 < coverage <= MAX_MASK_COVERAGE
        and outside_fraction <= MAX_OUTSIDE_BOX_FRACTION
    )
    if mask_quality_passed:
        status = "MaskCandidateReady"
        reason = "single-keyframe mask has not been propagated or scope-certified"
        next_action = "run EdgeTAM temporal propagation and ScopeCoverageValidator"
    else:
        status = "MaskRejected"
        reason = "mask failed coverage or language-box leakage limits"
        next_action = "request another grounding observation or use a stronger segmenter"
    evidence = {
        "schema_version": 1,
        "probe": "aihub_edgetam_mask_grounding",
        "read_only": True,
        "status": status,
        "upstream": {
            "evidence_sha256": sha256(args.grounding_evidence),
            "candidate_id": candidate["candidate_id"],
            "box_xyxy": candidate["box_xyxy"],
        },
        "internal_prompt": {
            "source": "deterministic_box_interior_positive_points",
            "normalized_points": prompt_coords.squeeze(0).tolist(),
            "sam_labels": labels.squeeze(0).tolist(),
            "user_supplied_coordinates": False,
        },
        "remote": {
            "device": device.name,
            "device_os": device.os,
            "encoder_model_id": args.encoder_model_id,
            "decoder_model_id": args.decoder_model_id,
            "adapter": remote_adapter,
            "jobs": [
                {
                    "component": "encoder",
                    "job_id": encoder_job.job_id,
                    "url": encoder_job.url,
                },
                {
                    "component": "video_decoder",
                    "job_id": decoder_job.job_id,
                    "url": decoder_job.url,
                },
            ],
        },
        "mask": {
            "width": image.width,
            "height": image.height,
            "coverage": round(coverage, 6),
            "outside_expanded_box_fraction": round(outside_fraction, 6),
            "score": round(score, 6),
            "object_score_logit": round(object_logit, 6),
        },
        "quality_gate": {
            "passed": mask_quality_passed,
            "box_margin_fraction": BOX_MARGIN_FRACTION,
            "max_mask_coverage": MAX_MASK_COVERAGE,
            "max_outside_box_fraction": MAX_OUTSIDE_BOX_FRACTION,
        },
        "decision": {
            "status": "NeedObservation",
            "reason": reason,
            "next": next_action,
            "content_mutation_allowed": False,
        },
        "artifacts": {"mask": mask_path.name, "overlay": overlay_path.name},
        "host_elapsed_ms": round((time.monotonic() - started) * 1000),
    }
    evidence_path = args.output_dir / "mask-evidence.json"
    evidence_path.write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(evidence_path)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise
