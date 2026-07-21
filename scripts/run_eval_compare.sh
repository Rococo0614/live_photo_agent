#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'USAGE'
Usage:
  scripts/run_eval_compare.sh \
    --endpoint-url <url> \
    --endpoint-api-key <key> \
    --local-model-dir <dir> \
    [--workspace-id <id>] \
    [--model-name <name>] \
    [--max-cases <n>] \
    [--dataset <path>] \
    [--library-root <path>] \
    [--output-dir <path>]

Description:
  Runs planner eval twice (endpoint and local_hf) and writes:
    - report_endpoint.json
    - report_local.json
    - compare_summary.json

  Note: endpoint key is passed via environment variable for the eval process.
USAGE
}

ENDPOINT_URL=""
ENDPOINT_API_KEY=""
LOCAL_MODEL_DIR=""
WORKSPACE_ID=""
MODEL_NAME="Qwen/Qwen3-4B-Instruct-2507"
MAX_CASES="0"
DATASET="tests/evals/planner_eval_set_50.jsonl"
LIBRARY_ROOT="tests/_smoke_artifacts/library"
OUTPUT_DIR="tests/evals"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --endpoint-url)
      ENDPOINT_URL="$2"; shift 2 ;;
    --endpoint-api-key)
      ENDPOINT_API_KEY="$2"; shift 2 ;;
    --local-model-dir)
      LOCAL_MODEL_DIR="$2"; shift 2 ;;
    --workspace-id)
      WORKSPACE_ID="$2"; shift 2 ;;
    --model-name)
      MODEL_NAME="$2"; shift 2 ;;
    --max-cases)
      MAX_CASES="$2"; shift 2 ;;
    --dataset)
      DATASET="$2"; shift 2 ;;
    --library-root)
      LIBRARY_ROOT="$2"; shift 2 ;;
    --output-dir)
      OUTPUT_DIR="$2"; shift 2 ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Unknown argument: $1"
      usage
      exit 1 ;;
  esac
done

if [[ -z "$ENDPOINT_URL" || -z "$ENDPOINT_API_KEY" || -z "$LOCAL_MODEL_DIR" ]]; then
  echo "Error: --endpoint-url, --endpoint-api-key and --local-model-dir are required"
  usage
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

ENDPOINT_REPORT="$OUTPUT_DIR/report_endpoint.json"
ENDPOINT_FAILURES="$OUTPUT_DIR/failures_endpoint.jsonl"
LOCAL_REPORT="$OUTPUT_DIR/report_local.json"
LOCAL_FAILURES="$OUTPUT_DIR/failures_local.jsonl"
COMPARE_SUMMARY="$OUTPUT_DIR/compare_summary.json"

echo "[1/3] Running endpoint eval..."
LPA_PLANNER_BACKEND=endpoint \
LPA_QWEN_ENDPOINT="$ENDPOINT_URL" \
LPA_QWEN_AUTH_TOKEN="$ENDPOINT_API_KEY" \
LPA_QWEN_WORKSPACE_ID="$WORKSPACE_ID" \
LPA_QWEN_MODEL="$MODEL_NAME" \
python tests/evals/run_planner_eval.py \
  --dataset "$DATASET" \
  --library-root "$LIBRARY_ROOT" \
  --max-cases "$MAX_CASES" \
  --output "$ENDPOINT_REPORT" \
  --failures-output "$ENDPOINT_FAILURES"

echo "[2/3] Running local_hf eval..."
LPA_PLANNER_BACKEND=local_hf \
LPA_LOCAL_MODEL_DIR="$LOCAL_MODEL_DIR" \
LPA_QWEN_MODEL="$MODEL_NAME" \
python tests/evals/run_planner_eval.py \
  --dataset "$DATASET" \
  --library-root "$LIBRARY_ROOT" \
  --max-cases "$MAX_CASES" \
  --output "$LOCAL_REPORT" \
  --failures-output "$LOCAL_FAILURES"

echo "[3/3] Building compare summary..."
python - "$ENDPOINT_REPORT" "$LOCAL_REPORT" "$COMPARE_SUMMARY" <<'PY'
import json
import sys
from pathlib import Path

endpoint_path = Path(sys.argv[1])
local_path = Path(sys.argv[2])
out_path = Path(sys.argv[3])

endpoint = json.loads(endpoint_path.read_text(encoding='utf-8'))
local = json.loads(local_path.read_text(encoding='utf-8'))

keys = [
    'pass_rate',
]
check_keys = sorted(set(endpoint.get('overall_check_rates', {}).keys()) | set(local.get('overall_check_rates', {}).keys()))

summary = {
    'endpoint_report': str(endpoint_path),
    'local_report': str(local_path),
    'delta': {
        'pass_rate': endpoint.get('pass_rate', 0.0) - local.get('pass_rate', 0.0),
    },
    'endpoint': {
        'total': endpoint.get('total', 0),
        'passed': endpoint.get('passed', 0),
        'pass_rate': endpoint.get('pass_rate', 0.0),
        'overall_check_rates': endpoint.get('overall_check_rates', {}),
    },
    'local': {
        'total': local.get('total', 0),
        'passed': local.get('passed', 0),
        'pass_rate': local.get('pass_rate', 0.0),
        'overall_check_rates': local.get('overall_check_rates', {}),
    },
}

for k in check_keys:
    ev = endpoint.get('overall_check_rates', {}).get(k, 0.0)
    lv = local.get('overall_check_rates', {}).get(k, 0.0)
    summary['delta'][k] = ev - lv

out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))
print(f'compare_saved_to: {out_path}')
PY

echo "Done."
echo "Endpoint report: $ENDPOINT_REPORT"
echo "Local report:    $LOCAL_REPORT"
echo "Compare summary: $COMPARE_SUMMARY"
