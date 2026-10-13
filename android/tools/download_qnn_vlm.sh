#!/usr/bin/env bash
set -euo pipefail

URL="${QNN_VLM_URL:-https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/qwen3_vl_4b_instruct/releases/v0.64.0/qwen3_vl_4b_instruct-genie-w4a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip}"
OUT="${1:-/tmp/bluecode/qwen3-vl-4b-gen5-for-galaxy.zip}"
TOTAL_BYTES="${QNN_VLM_SIZE:-3037645727}"
PARTS="${QNN_VLM_PARTS:-16}"
PART_DIR="${OUT}.parts"

mkdir -p "$PART_DIR"
chunk_size=$(( (TOTAL_BYTES + PARTS - 1) / PARTS ))

for ((index = 0; index < PARTS; index++)); do
    start=$((index * chunk_size))
    end=$((start + chunk_size - 1))
    if ((end >= TOTAL_BYTES)); then end=$((TOTAL_BYTES - 1)); fi
    part="$PART_DIR/part-$(printf '%02d' "$index").bin"
    expected=$((end - start + 1))
    if [[ -f "$part" ]] && [[ "$(stat -c '%s' "$part")" -eq "$expected" ]]; then
        continue
    fi
    rm -f "$part"
    curl --fail --http1.1 --location --retry 20 --retry-all-errors --retry-delay 5 --connect-timeout 30 \
        --range "$start-$end" --output "$part" "$URL" \
        >"$part.log" 2>&1 &
done
wait

for ((index = 0; index < PARTS; index++)); do
    part="$PART_DIR/part-$(printf '%02d' "$index").bin"
    expected_start=$((index * chunk_size))
    expected_end=$((expected_start + chunk_size - 1))
    if ((expected_end >= TOTAL_BYTES)); then expected_end=$((TOTAL_BYTES - 1)); fi
    expected=$((expected_end - expected_start + 1))
    [[ -f "$part" ]] && [[ "$(stat -c '%s' "$part")" -eq "$expected" ]] || {
        printf 'incomplete part: %s\n' "$part" >&2
        exit 1
    }
done

cat "$PART_DIR"/part-*.bin > "$OUT"
[[ "$(stat -c '%s' "$OUT")" -eq "$TOTAL_BYTES" ]]
sha256sum "$OUT"
