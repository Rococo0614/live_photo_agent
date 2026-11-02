#!/usr/bin/env python3
"""Prepare an OWLv2 QAIRT 2.45 DLC/context pair without falling back to newer QAIRT.

The script is deliberately phased so checking a job never resubmits it:

  submit   upload no weights; compile existing Torch Exported Program model IDs
  status   read job state only
  link     require successful 2.45 DLCs, then submit one context link per component
  download require successful 2.45 contexts and download them with hashes

It does not run inference, edit media, or publish anything.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
from typing import Any

import qai_hub as hub


REQUIRED_QAIRT = "2.45"
EXPECTED_SOC_ID = "87"
EXPECTED_HEXAGON = "v81"
COMPONENTS = {
    "vision": {
        "graph": "owlv2_vision",
        "outputs": "image_embeds",
        "filename": "owlv2_vision_qnn245.bin",
    },
    "text": {
        "graph": "owlv2_text",
        "outputs": "boxes,scores,labels",
        "filename": "owlv2_text_qnn245.bin",
    },
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("submit", "status", "link", "download"))
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--vision-source-model-id")
    parser.add_argument("--text-source-model-id")
    parser.add_argument("--device", default="Samsung Galaxy S26")
    parser.add_argument("--device-os", default="16")
    parser.add_argument("--download-dir", type=Path)
    args = parser.parse_args()
    if args.phase == "submit" and not (
        args.vision_source_model_id and args.text_source_model_id
    ):
        parser.error("submit requires both source model IDs")
    if args.phase == "download" and args.download_dir is None:
        parser.error("download requires --download-dir")
    return args


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def save_manifest(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def load_manifest(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise RuntimeError(f"manifest does not exist: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("required_qairt_version") != REQUIRED_QAIRT:
        raise RuntimeError("manifest is not pinned to QAIRT 2.45")
    return value


def select_device(name: str, os_version: str) -> hub.Device:
    matches = [device for device in hub.get_devices(name=name) if device.os == os_version]
    if len(matches) != 1:
        raise RuntimeError(
            f"expected exactly one {name!r} / Android {os_version}; found {len(matches)}"
        )
    return matches[0]


def metadata(model: hub.Model) -> dict[str, str]:
    return {
        getattr(key, "name", str(key)): str(value)
        for key, value in model.metadata.items()
    }


def require_qairt245(model: hub.Model) -> dict[str, str]:
    values = metadata(model)
    version = values.get("QAIRT_SDK_VERSION", "")
    if not version.startswith(REQUIRED_QAIRT + "."):
        raise RuntimeError(
            f"model {model.model_id} was built with {version or 'unknown QAIRT'}, not 2.45"
        )
    return values


def require_context_metadata(model: hub.Model, graph: str) -> dict[str, str]:
    values = require_qairt245(model)
    expected = {
        "QNN_CONTEXT_BIN_SOC_MODEL": EXPECTED_SOC_ID,
        "QNN_CONTEXT_BIN_BACKEND": "HTP",
        "QNN_CONTEXT_BIN_HEXAGON_VERSION": EXPECTED_HEXAGON,
        "QNN_MODEL_GRAPH_NAMES": graph,
    }
    mismatches = {
        key: {"expected": wanted, "actual": values.get(key)}
        for key, wanted in expected.items()
        if values.get(key) != wanted
    }
    if mismatches:
        raise RuntimeError(f"context metadata mismatch: {mismatches}")
    return values


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def job_state(job_id: str) -> dict[str, Any]:
    job = hub.get_job(job_id)
    status = job.get_status()
    return {
        "job_id": job_id,
        "url": job.url,
        "code": str(status.code),
        "message": status.message,
        "finished": bool(status.finished),
        "success": bool(status.success),
    }


def submit(args: argparse.Namespace) -> None:
    if args.manifest.exists():
        raise RuntimeError(f"refusing to overwrite existing manifest: {args.manifest}")
    device = select_device(args.device, args.device_os)
    source_ids = {
        "vision": args.vision_source_model_id,
        "text": args.text_source_model_id,
    }
    manifest: dict[str, Any] = {
        "schema_version": 1,
        "submitted_at_utc": utc_now(),
        "required_qairt_version": REQUIRED_QAIRT,
        "device": {
            "name": device.name,
            "os": device.os,
            "attributes": device.attributes,
        },
        "read_only": True,
        "content_mutation_allowed": False,
        "components": {},
    }
    for component, config in COMPONENTS.items():
        source_model = hub.get_model(source_ids[component])
        if source_model.model_type.name != "TORCH_EXPORTED_PROGRAM":
            raise RuntimeError(
                f"{component} source must be TORCH_EXPORTED_PROGRAM, got "
                f"{source_model.model_type.name}"
            )
        options = (
            f"--target_runtime qnn_dlc --qairt_version {REQUIRED_QAIRT} "
            f"--output_names {config['outputs']} "
            f"--qnn_options context_enable_graphs={config['graph']}"
        )
        job = hub.submit_compile_job(
            model=source_model,
            device=device,
            name=f"owlv2_qnn245_{component}",
            options=options,
        )
        manifest["components"][component] = {
            "source_model_id": source_model.model_id,
            "source_model_type": source_model.model_type.name,
            "source_name": source_model.name,
            "graph_name": config["graph"],
            "compile_options": options,
            "compile_job_id": job.job_id,
            "compile_job_url": job.url,
        }
        save_manifest(args.manifest, manifest)
        print(component, "compile", job.job_id, job.url)


def status(args: argparse.Namespace) -> None:
    manifest = load_manifest(args.manifest)
    states: dict[str, Any] = {}
    for component, record in manifest["components"].items():
        states[component] = {
            "compile": job_state(record["compile_job_id"]),
        }
        if record.get("link_job_id"):
            states[component]["link"] = job_state(record["link_job_id"])
    print(json.dumps(states, indent=2))


def link(args: argparse.Namespace) -> None:
    manifest = load_manifest(args.manifest)
    device = select_device(args.device, args.device_os)
    for component, record in manifest["components"].items():
        if record.get("link_job_id"):
            print(component, "link already recorded", record["link_job_id"])
            continue
        compile_job = hub.get_job(record["compile_job_id"])
        compile_status = compile_job.get_status()
        if not compile_status.success:
            raise RuntimeError(
                f"{component} compile is not successful: "
                f"{compile_status.code} {compile_status.message}"
            )
        dlc = compile_job.get_target_model()
        record["dlc_model_id"] = dlc.model_id
        record["dlc_model_type"] = dlc.model_type.name
        record["dlc_metadata"] = require_qairt245(dlc)
        link_options = f"--qairt_version {REQUIRED_QAIRT}"
        link_job = hub.submit_link_job(
            models=[dlc],
            device=device,
            name=f"owlv2_qnn245_{component}_context",
            options=link_options,
        )
        record["link_options"] = link_options
        record["link_job_id"] = link_job.job_id
        record["link_job_url"] = link_job.url
        record["linked_at_utc"] = utc_now()
        save_manifest(args.manifest, manifest)
        print(component, "link", link_job.job_id, link_job.url)


def download(args: argparse.Namespace) -> None:
    manifest = load_manifest(args.manifest)
    args.download_dir.mkdir(parents=True, exist_ok=True)
    for component, config in COMPONENTS.items():
        record = manifest["components"][component]
        link_job_id = record.get("link_job_id")
        if not link_job_id:
            raise RuntimeError(f"{component} has no link job; run the link phase first")
        link_job = hub.get_job(link_job_id)
        link_status = link_job.get_status()
        if not link_status.success:
            raise RuntimeError(
                f"{component} link is not successful: "
                f"{link_status.code} {link_status.message}"
            )
        context = link_job.get_target_model()
        context_metadata = require_context_metadata(context, config["graph"])
        target = args.download_dir / config["filename"]
        if target.is_file():
            recorded_sha = record.get("artifact", {}).get("sha256")
            existing_sha = sha256(target)
            if not recorded_sha:
                raise RuntimeError(
                    f"refusing to trust pre-existing unrecorded artifact: {target}"
                )
            if existing_sha != recorded_sha:
                raise RuntimeError(
                    f"existing artifact hash changed: {target} "
                    f"expected={recorded_sha} actual={existing_sha}"
                )
        else:
            context.download(target)
        record["context_model_id"] = context.model_id
        record["context_model_type"] = context.model_type.name
        record["context_metadata"] = context_metadata
        record["artifact"] = {
            "filename": target.name,
            "size_bytes": target.stat().st_size,
            "sha256": sha256(target),
        }
        record["downloaded_at_utc"] = utc_now()
        save_manifest(args.manifest, manifest)
        print(component, target, record["artifact"]["sha256"])


def main() -> None:
    args = parse_args()
    if args.phase == "submit":
        submit(args)
    elif args.phase == "status":
        status(args)
    elif args.phase == "link":
        link(args)
    else:
        download(args)


if __name__ == "__main__":
    main()
