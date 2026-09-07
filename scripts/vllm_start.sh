#!/usr/bin/env bash
# Start the local VLM model server in the background.
# PID is written to .vllm.pid so vllm_stop.sh can kill it cleanly.
# Uses transformers directly (no FlashInfer) — works on RTX 5090 Blackwell.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$REPO_ROOT/.vllm.pid"
LOG_FILE="$REPO_ROOT/.vllm.log"
VLLM_PORT=8100
MODEL_DIR="$HOME/models/Qwen2.5-VL-7B-Instruct"

if [[ ! -d "$MODEL_DIR" ]]; then
    echo "[vllm] ERROR: model not found at $MODEL_DIR"
    exit 1
fi

if [[ -f "$PID_FILE" ]]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[vllm] already running (PID $OLD_PID) — stop it first with scripts/vllm_stop.sh"
        exit 1
    fi
    rm -f "$PID_FILE"
fi

nohup python "$REPO_ROOT/scripts/local_vlm.py" \
    --model "$MODEL_DIR" \
    --port "$VLLM_PORT" \
    >> "$LOG_FILE" 2>&1 &

PID=$!
echo $PID > "$PID_FILE"
echo "[vllm] model loading (PID $PID, port $VLLM_PORT) — log: $LOG_FILE"
echo "[vllm] this may take 30-60 seconds on first start..."

# Wait for the server to be ready (poll /health)
MAX_WAIT=120
ELAPSED=0
while [[ $ELAPSED -lt $MAX_WAIT ]]; do
    if curl -s --max-time 2 "http://127.0.0.1:${VLLM_PORT}/health" > /dev/null 2>&1; then
        echo "[vllm] ready — http://127.0.0.1:${VLLM_PORT}"
        exit 0
    fi
    sleep 2
    ELAPSED=$((ELAPSED + 2))
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "[vllm] ERROR: process died — check $LOG_FILE"
        rm -f "$PID_FILE"
        exit 1
    fi
done

echo "[vllm] WARNING: still not ready after ${MAX_WAIT}s — check $LOG_FILE"