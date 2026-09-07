#!/usr/bin/env bash
# Stop the local vLLM model server started by vllm_start.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$REPO_ROOT/.vllm.pid"

if [[ ! -f "$PID_FILE" ]]; then
    echo "[vllm] no PID file found — vLLM may not be running"
    exit 0
fi

PID=$(cat "$PID_FILE")
if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "[vllm] stopped (PID $PID)"
else
    echo "[vllm] PID $PID not found (already dead)"
fi

rm -f "$PID_FILE"