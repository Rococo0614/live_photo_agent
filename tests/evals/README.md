# Planner Eval Set (100 cases)

This folder contains a human-review-first evaluation set for planner reasoning and tool-chain design.

## File

- `planner_eval_set_50.jsonl` (100 rows total)

## JSONL fields

- `case_id`: unique case id.
- `category`: scenario bucket (`direct_execution`, `clarification_required`, `conflict`, `robustness`, `policy_boundary`).
- `user_text`: user request input.
- `selected_asset_ids`: optional selected ids from UI.
- `expected_intent`: expected normalized intent label.
- `expected_need_clarification`: whether planner should ask clarification before execution.
- `expected_clarification_topics`: expected question topics when clarification is required.
- `expected_tools_must_include`: required tools in plan.
- `expected_tools_must_not_include`: forbidden tools in plan.
- `expected_argument_constraints`: optional argument-key constraints per tool.
- `expected_tool_order`: expected tool sequence for order validation.
- `expected_tool_order_mode`: order check mode.
	- `subsequence`: expected order must appear in sequence, extra tools allowed.
	- `exact`: expected order must exactly equal planned tool list.

## Dataset layout

- Rows `LP001`-`LP050`: base set (coverage-oriented).
- Rows `LP051`-`LP100`: strict set (order-sensitive), mostly with `expected_tool_order_mode=exact`.

## Review checklist

1. Intent label is stable for semantically similar requests.
2. Clarification triggers only when requirements are underspecified or conflicting.
3. Tool order is correct for the scenario (especially strict set rows).
4. Tool arguments only use contract-approved keys.
5. Selected-asset behavior is respected (`filter_selected` only when ids exist).

## Automated evaluation script

Run planner-vs-label comparison:

```bash
python tests/evals/run_planner_eval.py
```

Backend can be switched by env config:

```bash
# endpoint mode
export LPA_PLANNER_BACKEND=endpoint
export LPA_QWEN_ENDPOINT='http://127.0.0.1:8000/v1/chat/completions'

# or local_hf mode
export LPA_PLANNER_BACKEND=local_hf
export LPA_LOCAL_MODEL_DIR='/path/to/local/model'
```

Quick dry run on first 10 cases:

```bash
python tests/evals/run_planner_eval.py --max-cases 10
```

Outputs:

- `tests/evals/planner_eval_report.json`: aggregated metrics.
- `tests/evals/planner_eval_failures.jsonl`: failed case details for manual inspection.

## Dual scoring (compliance + quality)

The evaluator now outputs dual scores for each case:

- `compliance_score`: deterministic rule-based score from checks.
- `quality_score`: quality assessment score.
- `final_score`: weighted score (`compliance_weight * compliance_score + quality_weight * quality_score`).
- `strict_passed`: legacy all-checks pass result for comparison.

Current pass/fail for `passed` uses:

1. Hard gate: `must_not_include_match` and `argument_constraints_match` must be true.
2. `final_score >= final_pass_threshold`.

### Quality score modes

- `--quality-mode cloud` (default): use cloud model as placeholder evaluator.
	- If cloud call fails, auto-fallback to heuristic score.
- `--quality-mode heuristic`: local heuristic score only.
- `--quality-mode off`: disables cloud and uses fallback heuristic.

Image-result quality can be added as part of `quality_score` using JPEG IQA models:

- `--quality-image-model none|musiq|clipiqa` (default `none`)
- `--quality-image-fields` dataset fields containing output JPEG path(s), comma separated
	- default: `result_jpeg_path,output_jpeg_path,jpeg_path`
- `--quality-image-weight` weight of image IQA component inside quality score
- `--quality-text-weight` weight of text/planning component inside quality score
- `--quality-image-device` device for IQA model (`cpu` or `cuda`)

When image paths are available in the case row and IQA is enabled, quality is combined as:

`quality_score = quality_text_weight * text_quality + quality_image_weight * image_iqa_score`

If image scoring is unavailable for a case, evaluator falls back to text-quality component.

Related args:

- `--quality-endpoint` (default from `LPA_QUALITY_ENDPOINT` or `LPA_QWEN_ENDPOINT`)
- `--quality-model` (default from `LPA_QUALITY_MODEL` or `LPA_QWEN_MODEL`)
- `--quality-auth-token` (default from `LPA_QUALITY_AUTH_TOKEN` or `LPA_QWEN_AUTH_TOKEN`)
- `--compliance-weight` (default `0.4`)
- `--quality-weight` (default `0.6`)
- `--final-pass-threshold` (default `0.7`)
- `--result-only-pass` pass/fail uses only quality score threshold
- `--result-score-threshold` quality threshold for result-only mode and retry stop condition
- `--max-quality-retries` re-generate plan when score is below result threshold

Example:

```bash
python tests/evals/run_planner_eval.py \
	--quality-mode cloud \
	--quality-model qwen3-omni-flash \
	--quality-image-model musiq \
	--quality-image-weight 0.6 \
	--quality-text-weight 0.4 \
	--result-only-pass \
	--result-score-threshold 0.75 \
	--max-quality-retries 2 \
	--compliance-weight 0.4 \
	--quality-weight 0.6 \
	--final-pass-threshold 0.7
```

Notes:

- The script evaluates `intent` match using Stage A mapping (embedding nearest-centroid over `user_text + raw_intent`), clarification trigger, required/forbidden tools, tool order, and argument key constraints.
- Strict raw-intent equality is kept only as a diagnostic field (`details.strict_intent_match`) and does not gate pass/fail.
- Cloud quality scoring is currently a placeholder evaluator to enable flexible quality-oriented iteration; replace rubric or model later as needed.
- Stage A mapping uses local embedding model directory `models/bge/bge-small-zh-v1.5` by default; override with `--intent-model-dir` when needed.
- `category` remains evaluation metadata and is not intended as planner input.
- Script startup prints resolved backend runtime info for quick verification.
- For `musiq` / `clipiqa`, install an IQA backend first (for example `pyiqa`), otherwise image scoring will be skipped for that case.
