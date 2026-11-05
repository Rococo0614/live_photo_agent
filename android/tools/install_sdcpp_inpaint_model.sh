#!/usr/bin/env bash
set -euo pipefail

MODEL="${1:-/mnt/ssd/lpa-ondevice-inpaint/models/sd2-inpainting-q4_0.gguf}"
PACKAGE="com.vivo.lpa"
EXPECTED_SHA="7d154a1f52167da45bb86f34ded41feb3b3f4f2f0f3e729a7997c8bb394bf44b"
EXPECTED_BYTES="1733629824"
REMOTE_TMP="/data/local/tmp/lpa-sd2-inpainting-q4_0.gguf"
STEPS="${SDCPP_STEPS:-20}"
INFERENCE_SIZE="${SDCPP_INFERENCE_SIZE:-320}"

test -f "$MODEL"
test "$(stat -c %s "$MODEL")" = "$EXPECTED_BYTES"
test "$(sha256sum "$MODEL" | awk '{print $1}')" = "$EXPECTED_SHA"
test "$STEPS" -ge 1
test "$STEPS" -le 30
[[ "$INFERENCE_SIZE" == "256" || "$INFERENCE_SIZE" == "320" || "$INFERENCE_SIZE" == "512" ]]

adb shell "run-as $PACKAGE true"
adb push "$MODEL" "$REMOTE_TMP"
adb shell "chmod 0644 $REMOTE_TMP"
adb shell "run-as $PACKAGE mkdir -p files/models/local-generation-sdcpp"
adb shell "run-as $PACKAGE cp $REMOTE_TMP files/models/local-generation-sdcpp/sd2-inpainting-q4_0.gguf"

CONFIG=$(cat <<EOF
{
  "schema_version": 1,
  "model_file": "sd2-inpainting-q4_0.gguf",
  "model_sha256": "$EXPECTED_SHA",
  "model_id": "sd2-community/stable-diffusion-2-inpainting/sdcpp-q4_0",
  "runtime_commit": "58bb3c1b1cd4749e94e39f537e9adfce295dcaee",
  "steps": $STEPS,
  "guidance_scale": 7.5,
  "inference_size": $INFERENCE_SIZE,
  "roi_context_padding_px": 96,
  "timeout_ms": 600000
}
EOF
)
ENCODED=$(printf '%s' "$CONFIG" | base64 -w0)
adb shell "run-as $PACKAGE sh -c 'echo $ENCODED | base64 -d > files/models/local-generation-sdcpp/config.json'"
adb shell "run-as $PACKAGE sha256sum files/models/local-generation-sdcpp/sd2-inpainting-q4_0.gguf"
adb shell "rm -f $REMOTE_TMP"
