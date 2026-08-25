#!/usr/bin/env bash
# Start the Live Photo Agent server in the background.
# PID is written to .server.pid so server_stop.sh can kill it cleanly.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$REPO_ROOT/.server.pid"
LOG_FILE="$REPO_ROOT/.server.log"

if [[ -f "$PID_FILE" ]]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[server] already running (PID $OLD_PID) — stop it first with scripts/server_stop.sh"
        exit 1
    fi
    rm -f "$PID_FILE"
fi

cd "$REPO_ROOT"
nohup /home/vivo/miniconda3/envs/live-photo-agent/bin/uvicorn \
    live_photo_agent.api:app \
    --host 127.0.0.1 \
    --port 8000 \
    >> "$LOG_FILE" 2>&1 &

PID=$!
echo $PID > "$PID_FILE"
echo "[server] started (PID $PID) — log: $LOG_FILE"

# Wait briefly and confirm it came up.
sleep 2
if kill -0 "$PID" 2>/dev/null; then
    echo "[server] health check..."
    curl -s --max-time 5 http://127.0.0.1:8000/health && echo ""
else
    echo "[server] ERROR: process died immediately — check $LOG_FILE"
    rm -f "$PID_FILE"
    exit 1
fi
