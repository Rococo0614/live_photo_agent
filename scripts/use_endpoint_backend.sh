#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: source scripts/use_endpoint_backend.sh <endpoint_url> <api_key> [model_name] [workspace_id]"
  echo "Example: source scripts/use_endpoint_backend.sh https://api.example.com/v1/chat/completions sk-xxxx Qwen/Qwen3-4B-Instruct-2507 ws-xxxx"
  return 1 2>/dev/null || exit 1
fi

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "Warning: run this script with 'source' so exported variables persist in your current shell."
fi

ENDPOINT_URL="$1"
API_KEY="$2"
MODEL_NAME="${3:-Qwen/Qwen3-4B-Instruct-2507}"

if [[ -z "$API_KEY" ]]; then
  echo "Error: api_key is empty. Export DASHSCOPE_API_KEY first or pass a non-empty key."
  return 1 2>/dev/null || exit 1
fi
WORKSPACE_ID="${4:-}"

export LPA_PLANNER_BACKEND=endpoint
export LPA_QWEN_ENDPOINT="$ENDPOINT_URL"
export LPA_QWEN_AUTH_TOKEN="$API_KEY"
export LPA_QWEN_MODEL="$MODEL_NAME"
export LPA_VLM_BACKEND=endpoint
export LPA_VLM_ENDPOINT="$ENDPOINT_URL"
export LPA_VLM_MODEL="$MODEL_NAME"
export LPA_VLM_TIMEOUT_SECONDS="60"
if [[ -n "$WORKSPACE_ID" ]]; then
  export LPA_QWEN_WORKSPACE_ID="$WORKSPACE_ID"
else
  unset LPA_QWEN_WORKSPACE_ID || true
fi

# Clear local-hf override to avoid accidental backend confusion.
unset LPA_LOCAL_MODEL_DIR || true

echo "Planner backend set to endpoint"
echo "LPA_QWEN_ENDPOINT=$LPA_QWEN_ENDPOINT"
echo "LPA_QWEN_MODEL=$LPA_QWEN_MODEL"
echo "LPA_QWEN_AUTH_TOKEN=*** (hidden)"
if [[ -n "${LPA_QWEN_WORKSPACE_ID:-}" ]]; then
  echo "LPA_QWEN_WORKSPACE_ID=$LPA_QWEN_WORKSPACE_ID"
fi
