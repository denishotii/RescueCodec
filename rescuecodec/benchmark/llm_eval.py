"""Evaluate the configured local Ollama model on the synthetic labeled set."""
from __future__ import annotations

import csv
import json
import math
import os
import statistics
from pathlib import Path

from rescuecodec.benchmark.dataset import dataset
from rescuecodec.schemas import PersonFoundReport
from rescuecodec import semantic
from rescuecodec.semantic import extract_report


def run(model: str | None = None):
    model = model or os.getenv("RESCUECODEC_OLLAMA_MODEL")
    if not model:
        raise SystemExit("Set RESCUECODEC_OLLAMA_MODEL to an installed Ollama model tag.")
    os.environ["RESCUECODEC_OLLAMA_MODEL"] = model
    semantic.OLLAMA_MODEL = model
    rows, latencies = [], []
    supported_total = supported_correct = exact = field_correct = field_total = 0
    unsupported_total = unsupported_correct = unsupported_false_accepts = 0
    hallucinated_critical = critical_total = 0
    fields = ("consciousness", "breathing", "equipment_needed", "request", "urgency", "location", "responders_needed_min")
    critical = ("consciousness", "breathing", "request", "urgency")
    for index, item in enumerate(dataset(), 1):
        result = extract_report(item["text"], use_ollama=True)
        report: PersonFoundReport = result["report"]
        predicted = report.model_dump(mode="json")
        latencies.append(result["latency_ms"])
        if item["supported"]:
            supported_total += 1
            supported_correct += int(result["supported"])
            truth = PersonFoundReport.model_validate(item["ground_truth"]).model_dump(mode="json")
            equal = {k: predicted[k] == truth[k] for k in fields}
            exact += int(all(equal.values()))
            field_correct += sum(equal.values()); field_total += len(fields)
            for key in critical:
                critical_total += 1
                if truth[key] in (0, [], None) and predicted[key] not in (0, [], None):
                    hallucinated_critical += 1
        else:
            unsupported_total += 1
            correct_reject = not result["supported"]
            unsupported_correct += int(correct_reject)
            unsupported_false_accepts += int(not correct_reject)
            equal = {}
        rows.append({"id": item["id"], "supported_truth": item["supported"], "supported_prediction": result["supported"],
            "method": result["method"], "latency_ms": round(result["latency_ms"], 2),
            "field_exact": all(equal.values()) if equal else None, "consciousness": predicted["consciousness"],
            "breathing": predicted["breathing"], "equipment_needed": json.dumps(predicted["equipment_needed"]),
            "request": predicted["request"], "urgency": predicted["urgency"], "person_count": predicted["person_count"],
            "location": json.dumps(predicted["location"]), "responders_needed_min": predicted["responders_needed_min"],
            "unsupported_reason": report.unsupported_reason or "", "model_error": result.get("model_error", "")})
        print(f"[{index:02d}/{len(dataset())}] {item['id']} {result['method']} {result['latency_ms']:.0f} ms", flush=True)
    result = {"model": model, "synthetic_cases": len(rows), "supported_count": supported_total,
        "supported_classification_accuracy": supported_correct / supported_total if supported_total else None,
        "supported_exact_match": exact / supported_total if supported_total else None,
        "supported_field_accuracy": field_correct / field_total if field_total else None,
        "unsupported_count": unsupported_total,
        "unsupported_detection_accuracy": unsupported_correct / unsupported_total if unsupported_total else None,
        "unsupported_false_accepts": unsupported_false_accepts,
        "critical_field_hallucination_rate": hallucinated_critical / critical_total if critical_total else None,
        "latency_ms": {"mean": statistics.mean(latencies), "median": statistics.median(latencies),
                       "p95": sorted(latencies)[max(0, math.ceil(len(latencies)*0.95)-1)]},
        "notes": ["Synthetic fictional reports only.", "Ollama was called locally; any conservative fallback caused by timeout/validation error is labeled per row.", "Field accuracy is only computed for examples marked representable by schema 6."]}
    out = Path(__file__).resolve().parents[2] / "results"
    out.mkdir(exist_ok=True)
    (out / "llama3_8b_evaluation.json").write_text(json.dumps(result, indent=2) + "\n")
    with (out / "llama3_8b_evaluation.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
    print(json.dumps(result, indent=2))
    return result


if __name__ == "__main__": run()
