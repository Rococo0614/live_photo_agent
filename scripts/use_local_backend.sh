#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: source scripts/use_local_backend.sh <local_model_dir> [device] [dtype] [model_name]"
  echo "Example: source scripts/use_local_backend.sh /path/to/model cpu float32 Qwen/Qwen3-4B-Instruct-2507"
  return 1 2>/dev/null || exit 1
fi

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "Warning: run this script with 'source' so exported variables persist in your current shell."
fi

LOCAL_MODEL_DIR="$1"
LOCAL_DEVICE="${2:-cpu}"
LOCAL_DTYPE="${3:-float32}"
MODEL_NAME="${4:-Qwen/Qwen3-4B-Instruct-2507}"

export LPA_PLANNER_BACKEND=local_hf
export LPA_LOCAL_MODEL_DIR="$LOCAL_MODEL_DIR"
export LPA_LOCAL_DEVICE="$LOCAL_DEVICE"
export LPA_LOCAL_DTYPE="$LOCAL_DTYPE"
export LPA_QWEN_MODEL="$MODEL_NAME"

# Clear endpoint auth settings to avoid accidental backend confusion.
unset LPA_QWEN_ENDPOINT || true
unset LPA_QWEN_AUTH_TOKEN || true

echo "Planner backend set to local_hf"
echo "LPA_LOCAL_MODEL_DIR=$LPA_LOCAL_MODEL_DIR"
echo "LPA_LOCAL_DEVICE=$LPA_LOCAL_DEVICE"
echo "LPA_LOCAL_DTYPE=$LPA_LOCAL_DTYPE"
echo "LPA_QWEN_MODEL=$LPA_QWEN_MODEL"
