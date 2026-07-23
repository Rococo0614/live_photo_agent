#!/usr/bin/env bash
# Stop the Live Photo Agent server started by server_start.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$REPO_ROOT/.server.pid"

if [[ ! -f "$PID_FILE" ]]; then
    echo "[server] no PID file found — server may not be running"
    exit 0
fi

PID=$(cat "$PID_FILE")
if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "[server] stopped (PID $PID)"
else
    echo "[server] PID $PID not found (already dead)"
fi

rm -f "$PID_FILE"
