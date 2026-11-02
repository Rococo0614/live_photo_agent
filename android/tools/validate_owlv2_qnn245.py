#!/usr/bin/env python3
"""Differential-test QAIRT 2.45 OWLv2 contexts with fixed reference tensors.

Only model tensors execute remotely. Tokenization, NMS, cardinality decisions, evidence and all
acceptance checks remain deterministic host code. Inference is pinned to QAIRT 2.45 + NPU.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import time
from pathlib import Path
from typing import Any

import numpy as np
import qai_hub as hub
import torch
from qai_hub_models.utils.bounding_box_processing import batched_nms
from qai_hub_models.utils.inference import AsyncOnDeviceModel


REQUIRED_QAIRT = "2.45"
INFERENCE_OPTIONS = "--qairt_version 2.45 --compute_unit npu"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vision-model-id", required=True)
    parser.add_argument("--text-model-id", required=True)
    parser.add_argument("--broad-reference-dir", required=True, type=Path)
    parser.add_argument("--precise-reference-dir", required=True, type=Path)
    parser.add_argument("--device", default="Samsung Galaxy S26")
    parser.add_argument("--device-os", default="16")
    parser.add_argument("--minimum-psnr-db", type=float, default=50.0)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    for directory in (args.broad_reference_dir, args.precise_reference_dir):
        if not directory.is_dir():
            parser.error(f"reference directory does not exist: {directory}")
    return args


def select_device(name: str, os_version: str) -> hub.Device:
    matches = [device for device in hub.get_devices(name=name) if device.os == os_version]
    if len(matches) != 1:
        raise RuntimeError(f"expected one {name!r} / Android {os_version}; got {len(matches)}")
    return matches[0]


def model_metadata(model: hub.Model) -> dict[str, str]:
    values = {
        getattr(key, "name", str(key)): str(value)
        for key, value in model.metadata.items()
    }
    version = values.get("QAIRT_SDK_VERSION", "")
    if not version.startswith(REQUIRED_QAIRT + "."):
        raise RuntimeError(
            f"model {model.model_id} uses {version or 'unknown QAIRT'}, not 2.45"
        )
    return values


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_raw(path: Path, dtype: np.dtype[Any], shape: tuple[int, ...]) -> np.ndarray:
    value = np.fromfile(path, dtype=dtype)
    expected = math.prod(shape)
    if value.size != expected:
        raise RuntimeError(f"{path} has {value.size} values; expected {expected}")
    return value.reshape(shape)


def psnr(reference: np.ndarray, actual: np.ndarray) -> float:
    reference64 = reference.astype(np.float64)
    actual64 = actual.astype(np.float64)
    mse = float(np.mean(np.square(reference64 - actual64)))
    if mse == 0.0:
        return float("inf")
    peak = max(float(np.max(np.abs(reference64))), 1.0)
    return 20.0 * math.log10(peak / math.sqrt(mse))


def cosine(reference: np.ndarray, actual: np.ndarray) -> float:
    left = reference.astype(np.float64).reshape(-1)
    right = actual.astype(np.float64).reshape(-1)
    denominator = float(np.linalg.norm(left) * np.linalg.norm(right))
    return float(np.dot(left, right) / denominator) if denominator else 1.0


def differential(reference: np.ndarray, actual: np.ndarray) -> dict[str, Any]:
    difference = np.abs(reference.astype(np.float64) - actual.astype(np.float64))
    return {
        "psnr_db": psnr(reference, actual),
        "cosine": cosine(reference, actual),
        "maximum_absolute_error": float(np.max(difference)),
        "mean_absolute_error": float(np.mean(difference)),
    }


def load_reference(directory: Path, include_image: bool) -> dict[str, np.ndarray]:
    values = {
        "input_ids": load_raw(directory / "input_ids.raw", np.int32, (1, 16)),
        "attention_mask": load_raw(
            directory / "attention_mask.raw", np.int32, (1, 16)
        ),
        "boxes": load_raw(directory / "boxes.raw", np.float32, (1, 3600, 4)),
        "scores": load_raw(directory / "scores.raw", np.float32, (1, 3600)),
        "labels": load_raw(directory / "labels.raw", np.int32, (1, 3600)),
    }
    if include_image:
        values["pixel_values"] = load_raw(
            directory / "pixel_values.raw", np.float32, (1, 3, 960, 960)
        )
        values["image_embeds"] = load_raw(
            directory / "image_embeds.raw", np.float32, (1, 60, 60, 768)
        )
    return values


def candidates(
    boxes: np.ndarray,
    scores: np.ndarray,
    labels: np.ndarray,
    query: str,
    image_width: int,
    image_height: int,
) -> list[dict[str, Any]]:
    nms_boxes, nms_scores, nms_labels = batched_nms(
        0.5,
        0.1,
        torch.from_numpy(boxes),
        torch.from_numpy(scores),
        torch.from_numpy(labels),
    )
    scale = 960 / max(image_width, image_height)
    mapped = nms_boxes[0].float() / scale
    mapped[:, 0::2].clamp_(0, image_width)
    mapped[:, 1::2].clamp_(0, image_height)
    return [
        {
            "candidate_id": f"candidate-{index}",
            "query": query,
            "score": float(score),
            "label": int(label),
            "box_xyxy": [float(value) for value in box],
        }
        for index, (box, score, label) in enumerate(
            zip(mapped, nms_scores[0], nms_labels[0], strict=True)
        )
    ]


def save_outputs(directory: Path, values: dict[str, np.ndarray]) -> dict[str, Any]:
    directory.mkdir(parents=True, exist_ok=True)
    artifacts: dict[str, Any] = {}
    for name, value in values.items():
        target = directory / f"{name}.raw"
        value.tofile(target)
        artifacts[target.name] = {
            "shape": list(value.shape),
            "dtype": str(value.dtype),
            "size_bytes": target.stat().st_size,
            "sha256": sha256(target),
        }
    return artifacts


def main() -> None:
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    device = select_device(args.device, args.device_os)
    vision_model = hub.get_model(args.vision_model_id)
    text_model = hub.get_model(args.text_model_id)
    vision_metadata = model_metadata(vision_model)
    text_metadata = model_metadata(text_model)
    broad = load_reference(args.broad_reference_dir, include_image=True)
    precise = load_reference(args.precise_reference_dir, include_image=False)
    broad_evidence = json.loads(
        (args.broad_reference_dir / "reference.json").read_text(encoding="utf-8")
    )
    precise_evidence = json.loads(
        (args.precise_reference_dir / "reference.json").read_text(encoding="utf-8")
    )
    image_embeds_sha = sha256(args.broad_reference_dir / "image_embeds.raw")
    if precise_evidence.get("reused_image_embeds_sha256") != image_embeds_sha:
        raise RuntimeError("precise reference was produced from different image embeddings")
    image_width = int(broad_evidence["image"]["width"])
    image_height = int(broad_evidence["image"]["height"])

    vision_runner = AsyncOnDeviceModel(
        vision_model,
        ["pixel_values"],
        device,
        inference_options=INFERENCE_OPTIONS,
        output_names=["image_embeds"],
    )
    vision_result = vision_runner(broad["pixel_values"])
    remote_image_embeds = vision_result.wait()
    if not isinstance(remote_image_embeds, torch.Tensor):
        raise RuntimeError("vision context did not return one image_embeds tensor")
    remote_image_embeds_np = remote_image_embeds.numpy().astype(np.float32)

    text_runner = AsyncOnDeviceModel(
        text_model,
        ["input_ids", "attention_mask", "image_embeds"],
        device,
        inference_options=INFERENCE_OPTIONS,
        output_names=["boxes", "scores", "labels"],
    )
    query_records: dict[str, Any] = {}
    raw_outputs: dict[str, np.ndarray] = {"image_embeds": remote_image_embeds_np}
    for name, reference, query in (
        ("broad", broad, broad_evidence["query"]),
        ("precise", precise, precise_evidence["query"]),
    ):
        result = text_runner(
            reference["input_ids"],
            reference["attention_mask"],
            remote_image_embeds_np,
        )
        outputs = result.wait()
        if not isinstance(outputs, tuple) or len(outputs) != 3:
            raise RuntimeError(f"{name} text inference did not return boxes/scores/labels")
        boxes, scores, labels = (
            output.numpy().astype(dtype)
            for output, dtype in zip(
                outputs, (np.float32, np.float32, np.int32), strict=True
            )
        )
        raw_outputs[f"{name}_boxes"] = boxes
        raw_outputs[f"{name}_scores"] = scores
        raw_outputs[f"{name}_labels"] = labels
        detected = candidates(
            boxes, scores, labels, query, image_width, image_height
        )
        decision = (
            "NeedObservation" if not detected
            else "CandidateSetReady" if len(detected) == 1
            else "NeedUser"
        )
        query_records[name] = {
            "query": query,
            "job_id": result.inference_job.job_id,
            "job_url": result.inference_job.url,
            "differential": {
                "boxes": differential(reference["boxes"], boxes),
                "scores": differential(reference["scores"], scores),
                "labels_exact": bool(np.array_equal(reference["labels"], labels)),
            },
            "candidate_count": len(detected),
            "decision": decision,
            "candidates": detected,
        }

    vision_diff = differential(broad["image_embeds"], remote_image_embeds_np)
    numerical_values = [vision_diff]
    for record in query_records.values():
        numerical_values.extend(
            [record["differential"]["boxes"], record["differential"]["scores"]]
        )
    numerical_pass = all(
        value["psnr_db"] >= args.minimum_psnr_db for value in numerical_values
    ) and all(
        record["differential"]["labels_exact"] for record in query_records.values()
    )
    semantic_pass = (
        query_records["broad"]["decision"] == "NeedUser"
        and query_records["precise"]["decision"] == "CandidateSetReady"
    )
    evidence = {
        "schema_version": 1,
        "status": "PASS" if numerical_pass and semantic_pass else "FAIL",
        "read_only": True,
        "content_mutation_allowed": False,
        "required_qairt_version": REQUIRED_QAIRT,
        "inference_options": INFERENCE_OPTIONS,
        "device": {"name": device.name, "os": device.os},
        "models": {
            "vision": {
                "model_id": vision_model.model_id,
                "metadata": vision_metadata,
            },
            "text": {"model_id": text_model.model_id, "metadata": text_metadata},
        },
        "jobs": {
            "vision": {
                "job_id": vision_result.inference_job.job_id,
                "job_url": vision_result.inference_job.url,
            }
        },
        "acceptance": {"minimum_psnr_db": args.minimum_psnr_db},
        "vision_differential": vision_diff,
        "queries": query_records,
        "artifacts": save_outputs(args.output_dir / "raw", raw_outputs),
        "elapsed_ms": round((time.monotonic() - started) * 1000),
    }
    target = args.output_dir / "validation.json"
    target.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(target)
    if evidence["status"] != "PASS":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
