from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import torch
from transformers import AutoModel, AutoTokenizer

ROOT_DIR = Path(__file__).resolve().parents[2]


@dataclass(slots=True)
class CaseItem:
    case_id: str
    expected_intent: str
    user_text: str
    raw_intent: str


def load_dataset(dataset_path: Path) -> dict[str, dict[str, str]]:
    rows: dict[str, dict[str, str]] = {}
    for line_no, raw in enumerate(dataset_path.read_text(encoding="utf-8").splitlines(), start=1):
        text = raw.strip()
        if not text:
            continue
        try:
            row = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid dataset JSONL at line {line_no}: {exc}") from exc

        case_id = str(row.get("case_id", ""))
        if not case_id:
            continue

        rows[case_id] = {
            "expected_intent": str(row.get("expected_intent", "")),
            "user_text": str(row.get("user_text", "")),
        }
    return rows


def load_failures(
    failures_path: Path,
    dataset_map: dict[str, dict[str, str]],
) -> list[CaseItem]:
    items: list[CaseItem] = []

    for line_no, raw in enumerate(failures_path.read_text(encoding="utf-8").splitlines(), start=1):
        text = raw.strip()
        if not text:
            continue
        try:
            row = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid failures JSONL at line {line_no}: {exc}") from exc

        case_id = str(row.get("case_id", ""))
        if not case_id or case_id not in dataset_map:
            continue

        details = row.get("details", {})
        if not isinstance(details, dict):
            continue

        # Skip infrastructure errors (401, parse failures, etc.)
        if "error" in details:
            continue

        raw_intent = str(details.get("actual_intent", "")).strip()
        if not raw_intent:
            continue

        items.append(
            CaseItem(
                case_id=case_id,
                expected_intent=dataset_map[case_id]["expected_intent"],
                user_text=dataset_map[case_id]["user_text"],
                raw_intent=raw_intent,
            )
        )

    return items


def mean_pool(last_hidden_state: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
    mask = attention_mask.unsqueeze(-1).expand(last_hidden_state.size()).float()
    summed = torch.sum(last_hidden_state * mask, dim=1)
    counts = torch.clamp(mask.sum(dim=1), min=1e-9)
    return summed / counts


def encode_texts(
    texts: list[str],
    tokenizer: AutoTokenizer,
    model: AutoModel,
    device: torch.device,
    batch_size: int,
) -> torch.Tensor:
    outputs: list[torch.Tensor] = []

    model.eval()
    with torch.no_grad():
        for start in range(0, len(texts), batch_size):
            batch = texts[start : start + batch_size]
            encoded = tokenizer(
                batch,
                padding=True,
                truncation=True,
                max_length=512,
                return_tensors="pt",
            )
            encoded = {k: v.to(device) for k, v in encoded.items()}
            out = model(**encoded)
            pooled = mean_pool(out.last_hidden_state, encoded["attention_mask"])
            pooled = torch.nn.functional.normalize(pooled, p=2, dim=1)
            outputs.append(pooled.cpu())

    return torch.cat(outputs, dim=0) if outputs else torch.empty((0, 0))


def build_prototypes(dataset_map: dict[str, dict[str, str]]) -> dict[str, list[str]]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for row in dataset_map.values():
        intent = row["expected_intent"]
        user_text = row["user_text"].strip()
        if intent and user_text:
            grouped[intent].append(user_text)

    # Add intent token itself as a short semantic anchor.
    for intent in list(grouped.keys()):
        grouped[intent].append(intent.replace("_", " "))

    return dict(grouped)


def stage_a_input(user_text: str, raw_intent: str) -> str:
    return (
        f"user_request: {user_text}\n"
        f"planner_intent: {raw_intent}\n"
        "task: map to the closest canonical intent label"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Stage A intent mapping eval (user_text + raw_intent).")
    parser.add_argument(
        "--dataset",
        type=Path,
        default=ROOT_DIR / "tests" / "evals" / "planner_eval_set_50.jsonl",
        help="Path to eval dataset jsonl.",
    )
    parser.add_argument(
        "--failures",
        type=Path,
        default=ROOT_DIR / "tests" / "evals" / "planner_eval_failures.jsonl",
        help="Path to planner failures jsonl containing actual_intent.",
    )
    parser.add_argument(
        "--model-dir",
        type=Path,
        default=ROOT_DIR / "models" / "bge" / "bge-small-zh-v1.5",
        help="Local embedding model directory.",
    )
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT_DIR / "tests" / "evals" / "intent_stage_a_report.json",
        help="Output report path.",
    )
    args = parser.parse_args()

    dataset_map = load_dataset(args.dataset)
    items = load_failures(args.failures, dataset_map)

    if not items:
        print(
            json.dumps(
                {
                    "error": "no_valid_items",
                    "hint": "failures file may only contain infrastructure errors; run planner eval successfully first.",
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return

    prototypes = build_prototypes(dataset_map)
    labels = sorted(prototypes.keys())

    device = torch.device("cpu")
    tokenizer = AutoTokenizer.from_pretrained(str(args.model_dir), trust_remote_code=True)
    model = AutoModel.from_pretrained(str(args.model_dir), trust_remote_code=True).to(device)

    proto_texts: list[str] = []
    proto_owner: list[str] = []
    for label in labels:
        for text in prototypes[label]:
            proto_texts.append(text)
            proto_owner.append(label)

    proto_emb = encode_texts(proto_texts, tokenizer, model, device, args.batch_size)

    by_label: dict[str, list[torch.Tensor]] = defaultdict(list)
    for idx, owner in enumerate(proto_owner):
        by_label[owner].append(proto_emb[idx])

    centroid_list: list[torch.Tensor] = []
    for label in labels:
        stacked = torch.stack(by_label[label], dim=0)
        centroid = torch.nn.functional.normalize(stacked.mean(dim=0), p=2, dim=0)
        centroid_list.append(centroid)
    centroids = torch.stack(centroid_list, dim=0)  # [L, D]

    case_texts = [stage_a_input(item.user_text, item.raw_intent) for item in items]
    case_emb = encode_texts(case_texts, tokenizer, model, device, args.batch_size)

    sims = case_emb @ centroids.T
    top_vals, top_idx = torch.max(sims, dim=1)

    correct = 0
    details: list[dict[str, Any]] = []
    confusions: Counter[tuple[str, str]] = Counter()

    for i, item in enumerate(items):
        pred = labels[int(top_idx[i].item())]
        score = float(top_vals[i].item())
        ok = pred == item.expected_intent
        if ok:
            correct += 1
        else:
            confusions[(item.expected_intent, pred)] += 1

        details.append(
            {
                "case_id": item.case_id,
                "expected_intent": item.expected_intent,
                "raw_intent": item.raw_intent,
                "predicted_intent": pred,
                "score": round(score, 6),
                "matched": ok,
            }
        )

    total = len(items)
    report = {
        "total_evaluated": total,
        "correct": correct,
        "accuracy": (correct / total) if total else 0.0,
        "model_dir": str(args.model_dir),
        "input_mode": "stage_a(user_text+raw_intent)",
        "top_confusions": [
            {
                "expected": exp,
                "predicted": pred,
                "count": count,
            }
            for (exp, pred), count in confusions.most_common(20)
        ],
        "details": details,
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "details"}, ensure_ascii=False, indent=2))
    print(f"report_saved_to: {args.output}")


if __name__ == "__main__":
    main()
