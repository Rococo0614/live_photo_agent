#!/usr/bin/env python3
"""Loopback-only research inpaint backend for AndroidHttpMaskedGenerationBackend.

The model/cache are external to the repository and APK. This server intentionally exposes only
the v1 multipart contract on 127.0.0.1; Android reaches it through `adb reverse`.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import os
import threading

from flask import Flask, jsonify, request
from PIL import Image


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "--model",
        default="sd2-community/stable-diffusion-2-inpainting",
    )
    parser.add_argument(
        "--cache-dir",
        default="/mnt/ssd/lpa-generation-backend/hf-cache",
    )
    parser.add_argument("--steps", type=int, default=20)
    parser.add_argument("--guidance", type=float, default=7.5)
    parser.add_argument("--padding", type=int, default=96)
    return parser.parse_args()


def png_bytes(image: Image.Image) -> bytes:
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=False)
    return output.getvalue()


def main() -> None:
    args = parse_args()
    if args.host not in {"127.0.0.1", "localhost", "::1"}:
        raise SystemExit("research backend must bind to loopback")
    if not 1 <= args.steps <= 100:
        raise SystemExit("steps must be in 1..100")

    import torch
    from diffusers import DPMSolverMultistepScheduler, StableDiffusionInpaintPipeline

    if not torch.cuda.is_available():
        raise SystemExit("CUDA is required for this research backend")
    os.makedirs(args.cache_dir, exist_ok=True)
    pipe = StableDiffusionInpaintPipeline.from_pretrained(
        args.model,
        torch_dtype=torch.float16,
        cache_dir=args.cache_dir,
    )
    pipe.scheduler = DPMSolverMultistepScheduler.from_config(pipe.scheduler.config)
    pipe = pipe.to("cuda")
    pipe.set_progress_bar_config(disable=True)
    generation_lock = threading.Lock()

    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = 32 * 1024 * 1024

    @app.get("/health")
    def health():
        return jsonify(
            schema_version=1,
            status="ready",
            backend_id="sd2_diffusers_inpaint",
            model_id=args.model,
            cuda_device=torch.cuda.get_device_name(0),
        )

    @app.post("/v1/inpaint")
    def inpaint():
        try:
            if request.form.get("schema_version") != "1":
                return jsonify(error="unsupported schema_version"), 400
            request_sha = request.form["request_sha256"]
            if len(request_sha) != 64:
                return jsonify(error="invalid request_sha256"), 400
            if request.form["model_id"] != args.model:
                return jsonify(error="model_id mismatch"), 409
            prompt = request.form["prompt"].strip()
            if not prompt or len(prompt) > 160:
                return jsonify(error="invalid prompt"), 400
            seed = int(request.form["seed"])
            source = Image.open(request.files["source"].stream).convert("RGB")
            mask = Image.open(request.files["mask"].stream).convert("L")
            if source.size != mask.size:
                return jsonify(error="source/mask dimensions differ"), 400
            if mask.getbbox() is None:
                return jsonify(error="empty mask"), 400

            generator = torch.Generator(device="cuda").manual_seed(seed)
            with generation_lock, torch.inference_mode():
                generated = pipe(
                    prompt=prompt,
                    negative_prompt=(
                        "blurry, distorted, duplicate object, text, watermark, logo, "
                        "low quality, malformed"
                    ),
                    image=source,
                    mask_image=mask,
                    num_inference_steps=args.steps,
                    guidance_scale=args.guidance,
                    generator=generator,
                    padding_mask_crop=args.padding,
                ).images[0].convert("RGB")
            if generated.size != source.size:
                generated = generated.resize(source.size, Image.Resampling.LANCZOS)
            # The phone repeats this composite and treats its source pixels as authoritative.
            output = Image.composite(generated, source, mask)
            encoded = png_bytes(output)
            return jsonify(
                schema_version=1,
                request_sha256=request_sha,
                width=source.width,
                height=source.height,
                output_sha256=hashlib.sha256(encoded).hexdigest(),
                image_base64=base64.b64encode(encoded).decode("ascii"),
                backend_id="sd2_diffusers_inpaint",
                model_id=args.model,
                seed=seed,
                single_frame_only=True,
            )
        except Exception as error:  # Flask converts neither model nor image errors usefully.
            app.logger.exception("inpaint request failed")
            return jsonify(error=type(error).__name__, message=str(error)[:500]), 500

    app.run(host=args.host, port=args.port, threaded=True, use_reloader=False)


if __name__ == "__main__":
    main()
