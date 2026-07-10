# Planner Eval Set (50 cases)

This folder contains a human-review-first evaluation set for planner reasoning and tool-chain design.

## File

- `planner_eval_set_50.jsonl`

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

## Review checklist

1. Intent label is stable for semantically similar requests.
2. Clarification triggers only when requirements are underspecified or conflicting.
3. Plan tool sequence starts from `scan_library` and ends with `summarize_results` for execution cases.
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

Notes:

- The script evaluates `intent`, clarification trigger, required/forbidden tools, and argument key constraints.
- `category` remains evaluation metadata and is not intended as planner input.
- Script startup prints resolved backend runtime info for quick verification.
