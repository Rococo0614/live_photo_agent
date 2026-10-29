#!/usr/bin/env python3
"""Run a language-only OWLv2 grounding probe on a remote AI Hub device.

The host performs deterministic preprocessing, NMS, and audit serialization. The
vision and text model components execute on the selected AI Hub phone. This is a
read-only probe: even an unambiguous box is only eligible for EdgeTAM tracking;
it never authorizes a media mutation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path
from typing import Any

import numpy as np
import qai_hub as hub
import torch
from PIL import Image, ImageDraw
from qai_hub_models.utils.bounding_box_processing import batched_nms
from qai_hub_models.utils.inference import AsyncOnDeviceModel
from transformers import AutoProcessor


HF_MODEL_ID = "google/owlv2-base-patch16-ensemble"
IMAGE_SIZE = 960


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run OWLv2 language grounding on a remote AI Hub phone."
    )
    parser.add_argument("--vision-model-id", required=True)
    parser.add_argument("--text-model-id", required=True)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument(
        "--reuse-vision-evidence",
        type=Path,
        help=(
            "Prior evidence for the same image/model. Reuses its remote vision "
            "output and submits only a new text-head job."
        ),
    )
    parser.add_argument(
        "--query",
        required=True,
        help="One short canonical OWLv2 concept phrase.",
    )
    parser.add_argument(
        "--target-description",
        help="Original user-language target. Defaults to the canonical query.",
    )
    parser.add_argument("--expected-count", type=int, default=1)
    parser.add_argument("--score-threshold", type=float, default=0.1)
    parser.add_argument("--iou-threshold", type=float, default=0.5)
    parser.add_argument("--device", default="Samsung Galaxy S26")
    parser.add_argument("--device-os", default="16")
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    if args.expected_count < 1:
        parser.error("--expected-count must be positive")
    if not 0.0 <= args.score_threshold <= 1.0:
        parser.error("--score-threshold must be in [0, 1]")
    if not 0.0 <= args.iou_threshold <= 1.0:
        parser.error("--iou-threshold must be in [0, 1]")
    if not args.image.is_file():
        parser.error(f"image does not exist: {args.image}")
    if args.reuse_vision_evidence and not args.reuse_vision_evidence.is_file():
        parser.error(
            f"vision evidence does not exist: {args.reuse_vision_evidence}"
        )
    return args


def select_device(name: str, os_version: str) -> hub.Device:
    matches = [device for device in hub.get_devices(name=name) if device.os == os_version]
    if len(matches) != 1:
        raise RuntimeError(
            f"expected one AI Hub device for {name!r} OS {os_version!r}; "
            f"found {len(matches)}"
        )
    return matches[0]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_output_tensor(job_id: str, output_name: str) -> torch.Tensor:
    job = hub.get_job(job_id)
    if not isinstance(job, hub.InferenceJob):
        raise RuntimeError(f"AI Hub job {job_id} is not an inference job")
    status = job.get_status()
    if not status.success:
        raise RuntimeError(f"AI Hub job {job_id} is not successful: {status.message}")
    outputs = job.download_output_data()
    if not isinstance(outputs, dict):
        raise RuntimeError(f"AI Hub job {job_id} has no downloadable outputs")
    matches = [
        key for key in outputs if key == output_name or key.split(":")[0] == output_name
    ]
    if len(matches) != 1 or not outputs[matches[0]]:
        raise RuntimeError(f"AI Hub job {job_id} has no unique {output_name!r} output")
    return torch.from_numpy(np.concatenate(outputs[matches[0]], axis=0))


def map_boxes_to_original(
    boxes: torch.Tensor,
    original_width: int,
    original_height: int,
) -> torch.Tensor:
    """Invert OWLv2's square padding followed by its 960px resize."""
    if len(boxes) == 0:
        return boxes
    scale = IMAGE_SIZE / max(original_height, original_width)
    mapped = boxes.clone().float() / scale
    mapped[:, 0] = mapped[:, 0].clamp(0, original_width)
    mapped[:, 1] = mapped[:, 1].clamp(0, original_height)
    mapped[:, 2] = mapped[:, 2].clamp(0, original_width)
    mapped[:, 3] = mapped[:, 3].clamp(0, original_height)
    return mapped


def grounding_decision(candidate_count: int, expected_count: int) -> dict[str, Any]:
    if candidate_count == 0:
        return {
            "status": "NeedObservation",
            "reason": "no candidate exceeded the score threshold",
            "next": "sample another keyframe or revise the language predicate",
            "content_mutation_allowed": False,
        }
    if candidate_count != expected_count:
        return {
            "status": "NeedUser",
            "reason": (
                f"expected {expected_count} candidate(s), observed {candidate_count}"
            ),
            "next": "ask a language-only clarification question",
            "content_mutation_allowed": False,
        }
    return {
        "status": "CandidateSetReady",
        "reason": "keyframe candidate cardinality matches the target predicate",
        "next": "propagate with EdgeTAM and issue a ScopeCertificate",
        "content_mutation_allowed": False,
    }


def draw_candidates(image: Image.Image, candidates: list[dict[str, Any]]) -> Image.Image:
    output = image.copy()
    draw = ImageDraw.Draw(output)
    line_width = max(2, round(max(output.size) / 500))
    for candidate in candidates:
        box = candidate["box_xyxy"]
        label = (
            f"{candidate['candidate_id']} {candidate['query']} "
            f"{candidate['score']:.3f}"
        )
        draw.rectangle(box, outline=(0, 255, 0), width=line_width)
        text_box = draw.textbbox((box[0], box[1]), label)
        draw.rectangle(text_box, fill=(0, 0, 0))
        draw.text((box[0], box[1]), label, fill=(255, 255, 255))
    return output


def main() -> None:
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()

    device = select_device(args.device, args.device_os)
    text_model = hub.get_model(args.text_model_id)
    text = AsyncOnDeviceModel(
        text_model,
        ["input_ids", "attention_mask", "image_embeds"],
        device,
    )
    processor = AutoProcessor.from_pretrained(
        HF_MODEL_ID,
        use_fast=True,
        size={"height": IMAGE_SIZE, "width": IMAGE_SIZE},
    )

    image = Image.open(args.image).convert("RGB")
    image_digest = sha256(args.image)
    inputs = processor(text=[[args.query]], images=image, return_tensors="pt")
    reused_vision_evidence_sha256: str | None = None
    remote_jobs: list[dict[str, Any]]
    if args.reuse_vision_evidence:
        prior = json.loads(args.reuse_vision_evidence.read_text(encoding="utf-8"))
        if prior.get("input", {}).get("sha256") != image_digest:
            raise RuntimeError("reused vision evidence has a different image hash")
        if prior.get("remote", {}).get("vision_model_id") != args.vision_model_id:
            raise RuntimeError("reused vision evidence has a different vision model")
        prior_vision_jobs = [
            job
            for job in prior.get("remote", {}).get("jobs", [])
            if job.get("component") == "vision"
        ]
        if len(prior_vision_jobs) != 1:
            raise RuntimeError("reused evidence has no unique vision job")
        prior_vision_job = prior_vision_jobs[0]
        image_embeds = download_output_tensor(
            prior_vision_job["job_id"], "image_embeds"
        )
        reused_vision_evidence_sha256 = sha256(args.reuse_vision_evidence)
        remote_jobs = [
            {
                "component": "vision",
                "job_id": prior_vision_job["job_id"],
                "url": prior_vision_job["url"],
                "reused": True,
            }
        ]
    else:
        vision_model = hub.get_model(args.vision_model_id)
        vision = AsyncOnDeviceModel(vision_model, ["pixel_values"], device)
        vision_result = vision(inputs["pixel_values"])
        image_embeds = vision_result.wait()
        if not isinstance(image_embeds, torch.Tensor):
            raise RuntimeError("OWLv2 vision component returned multiple tensors")
        remote_jobs = [
            {
                "component": "vision",
                "job_id": vision_result.inference_job.job_id,
                "url": vision_result.inference_job.url,
                "reused": False,
            }
        ]
    input_ids = inputs["input_ids"].to(torch.int32).reshape(-1, 16)
    attention_mask = inputs["attention_mask"].to(torch.int32).reshape(-1, 16)
    text_result = text(input_ids, attention_mask, image_embeds)
    raw_outputs = text_result.wait()
    if not isinstance(raw_outputs, tuple) or len(raw_outputs) != 3:
        raise RuntimeError("OWLv2 text component did not return boxes, scores, labels")
    boxes, scores, labels = raw_outputs
    nms_boxes, nms_scores, _ = batched_nms(
        args.iou_threshold,
        args.score_threshold,
        boxes,
        scores,
        labels,
    )
    mapped_boxes = map_boxes_to_original(nms_boxes[0], image.width, image.height)
    candidates: list[dict[str, Any]] = []
    for index, (box, score) in enumerate(
        zip(mapped_boxes, nms_scores[0], strict=True)
    ):
        box_values = [round(float(value), 3) for value in box]
        candidates.append(
            {
                "candidate_id": f"candidate-{index}",
                "query": args.query,
                "score": round(float(score), 6),
                "box_xyxy": box_values,
                "center_normalized": [
                    round((box_values[0] + box_values[2]) / (2 * image.width), 6),
                    round((box_values[1] + box_values[3]) / (2 * image.height), 6),
                ],
            }
        )
    remote_jobs.append(
        {
            "component": "text",
            "job_id": text_result.inference_job.job_id,
            "url": text_result.inference_job.url,
        }
    )

    candidates.sort(key=lambda candidate: candidate["score"], reverse=True)
    for index, candidate in enumerate(candidates):
        candidate["candidate_id"] = f"candidate-{index}"

    overlay_path = args.output_dir / "grounding-overlay.png"
    draw_candidates(image, candidates).save(overlay_path)
    evidence = {
        "schema_version": 1,
        "probe": "aihub_owlv2_language_grounding",
        "read_only": True,
        "target_predicate": {
            "source_text": args.target_description or args.query,
            "canonical_concept_query": args.query,
            "expected_count": args.expected_count,
        },
        "input": {
            "sha256": image_digest,
            "width": image.width,
            "height": image.height,
        },
        "remote": {
            "device": device.name,
            "device_os": device.os,
            "vision_model_id": args.vision_model_id,
            "text_model_id": args.text_model_id,
            "reused_vision_evidence_sha256": reused_vision_evidence_sha256,
            "jobs": remote_jobs,
        },
        "thresholds": {
            "score": args.score_threshold,
            "iou": args.iou_threshold,
        },
        "candidates": candidates,
        "decision": grounding_decision(len(candidates), args.expected_count),
        "artifacts": {"overlay": overlay_path.name},
        "host_elapsed_ms": round((time.monotonic() - started) * 1000),
    }
    evidence_path = args.output_dir / "grounding-evidence.json"
    evidence_path.write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(evidence_path)


if __name__ == "__main__":
    main()
