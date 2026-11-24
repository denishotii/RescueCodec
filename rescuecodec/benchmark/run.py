"""Run deterministic compression/extraction metrics over synthetic reports."""
from __future__ import annotations

import csv
import json
import statistics
import time
import zlib
from pathlib import Path

from rescuecodec.benchmark.dataset import dataset
from rescuecodec.encoding.airtime import airtime_report
from rescuecodec.protocol.codec import encode_report
from rescuecodec.schemas import PersonFoundReport
from rescuecodec.semantic import extract_report

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "results"


def run():
    OUT.mkdir(exist_ok=True)
    records, rows = dataset(), []
    field_correct = field_total = exact = supported_correct = supported_total = 0
    latencies = []
    for item in records:
        text = item["text"]
        raw = text.encode("utf-8")
        if item["supported"]:
            gt = PersonFoundReport.model_validate(item["ground_truth"])
            minjson = json.dumps(gt.model_dump(mode="json"), separators=(",", ":"), ensure_ascii=False).encode()
            binary = encode_report(gt, int(item["id"][-2:]))
        else:
            minjson = json.dumps(item["ground_truth"], separators=(",", ":")).encode()
            binary = b""
        start = time.perf_counter(); extracted = extract_report(text, use_ollama=False); latency = (time.perf_counter()-start)*1000
        latencies.append(latency)
        pred, pred_supported = extracted["report"], extracted["supported"]
        if item["supported"]:
            supported_total += 1; supported_correct += int(pred_supported)
            target = PersonFoundReport.model_validate(item["ground_truth"])
            p = pred.model_dump(mode="json"); g = target.model_dump(mode="json")
            keys = ("consciousness", "breathing", "equipment_needed", "request", "urgency")
            field_correct += sum(p[k] == g[k] for k in keys); field_total += len(keys)
            exact += int(all(p[k] == g[k] for k in keys))
        else:
            supported_total += 1; supported_correct += int(not pred_supported)
        sizes = {"utf8": len(raw), "zlib_utf8": len(zlib.compress(raw)), "minified_json": len(minjson),
                 "zlib_json": len(zlib.compress(minjson)), "structured_binary": len(binary)}
        rows.append({"id": item["id"], "supported": item["supported"], "original_utf8_bytes": sizes["utf8"],
            "zlib_utf8_bytes": sizes["zlib_utf8"], "minified_json_bytes": sizes["minified_json"], "zlib_json_bytes": sizes["zlib_json"],
            "semantic_binary_bytes": sizes["structured_binary"], "binary_airtime_estimate_seconds": airtime_report(len(binary))["airtime_seconds_estimate"] if binary else "",
            "fallback_supported_prediction": pred_supported, "fallback_latency_ms": round(latency, 4)})
    with (OUT / "benchmark.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=rows[0].keys()); w.writeheader(); w.writerows(rows)
    (OUT / "synthetic_dataset.json").write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n")
    binary_sizes = [r["semantic_binary_bytes"] for r in rows if r["semantic_binary_bytes"]]
    result = {"dataset_count": len(records), "synthetic_only": True, "extraction_mode": "conservative deterministic fallback (Ollama unavailable/unconfigured)",
        "supported_classification_accuracy": supported_correct / supported_total, "supported_examples_exact_field_match": exact / sum(x["supported"] for x in records),
        "field_accuracy": field_correct / field_total, "median_fallback_latency_ms": statistics.median(latencies),
        "compression": {"mean_utf8_bytes_supported": statistics.mean(r["original_utf8_bytes"] for r in rows if r["supported"]),
            "mean_binary_bytes_supported": statistics.mean(binary_sizes), "mean_reduction_vs_utf8_percent": 100 * (1-statistics.mean(binary_sizes)/statistics.mean(r["original_utf8_bytes"] for r in rows if r["supported"]))},
        "notes": ["Binary sizes encode ground-truth annotations, not model extraction output.", "Semantic accuracy applies only to the simple offline rule-based fallback.", "LoRa airtime values are estimates, not measured RF airtime.", "No physical radio or Meshtasticator packets were transmitted."]}
    (OUT / "benchmark.json").write_text(json.dumps(result, indent=2) + "\n")
    _write_svg(rows, OUT / "compression.svg")
    return result


def _write_svg(rows, path):
    selected = [r for r in rows if r["supported"]]
    means = [("UTF-8", statistics.mean(r["original_utf8_bytes"] for r in selected)),
             ("zlib text", statistics.mean(r["zlib_utf8_bytes"] for r in selected)),
             ("minified JSON", statistics.mean(r["minified_json_bytes"] for r in selected)),
             ("zlib JSON", statistics.mean(r["zlib_json_bytes"] for r in selected)),
             ("semantic binary", statistics.mean(r["semantic_binary_bytes"] for r in selected))]
    width, maxval = 720, max(v for _, v in means)
    bars = "".join(f'<text x="18" y="{54+i*54}" fill="#dce8df" font-size="13">{n}</text><rect x="150" y="{36+i*54}" width="{v/maxval*420:.1f}" height="25" rx="5" fill="{("#b9ec68" if n=="semantic binary" else "#4d7171")}"/><text x="{160+v/maxval*420:.1f}" y="{54+i*54}" fill="#ffffff" font-size="13">{v:.1f} B mean</text>' for i, (n,v) in enumerate(means))
    path.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="320" viewBox="0 0 {width} 320"><rect width="100%" height="100%" fill="#10191c"/><text x="18" y="26" fill="#b9ec68" font-size="14" font-weight="bold">Synthetic person-found messages · mean bytes (n={len(selected)})</text>{bars}</svg>')


def main():
    print(json.dumps(run(), indent=2))


if __name__ == "__main__": main()
