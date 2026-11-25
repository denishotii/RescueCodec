#!/usr/bin/env python3
"""Print what a RUNNING gateway saw, as a table you can paste into your notes. Stdlib only, read only (transmits nothing).

    python3 hil/report.py                 # last 30 messages
    python3 hil/report.py --md            # same as a Markdown table
    python3 hil/report.py --limit 100 --token SECRET

Columns: bytes on air (coded size), RSSI and SNR (real radios only, empty on the simulator), hops, status.
At the end: how long each task took from the report arriving to Accept and to Done (gateway clock).
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime


def get(base: str, path: str, token: str | None):
    request = urllib.request.Request(base.rstrip("/") + path)
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def when(text: str | None) -> datetime | None:
    return datetime.fromisoformat(text.replace("Z", "+00:00")) if text else None


def cell(value) -> str:
    return "" if value is None else str(value)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://127.0.0.1:8000")
    p.add_argument("--token")
    p.add_argument("--limit", type=int, default=30)
    p.add_argument("--md", action="store_true", help="Markdown table")
    args = p.parse_args()
    try:
        items = get(args.url, f"/v2/messages?limit={args.limit}", args.token)["items"]
        tasks = get(args.url, "/v2/tasks", args.token)["items"]
        health = get(args.url, "/v2/health", args.token)
    except (urllib.error.URLError, OSError) as exc:
        print(f"FAIL  gateway not reachable at {args.url}: {exc}")
        return 1
    radio = health["radio"]
    print(f"Radio: {radio['kind']} {radio['node']}, firmware {radio.get('firmware')}, channel {radio.get('channel')!r}, {radio.get('region')} {radio.get('preset')}")
    head = ["time", "dir", "type", "bytes", "rssi", "snr", "hops", "status", "hex"]
    rows = []
    for m in sorted(items, key=lambda m: m["seq"]):
        r = m.get("radio") or {}
        rows.append([cell((m.get("time") or "")[11:19]), m["direction"], m["type"], cell((m.get("codec") or {}).get("size")),
                     cell(r.get("rssi")), cell(r.get("snr")), cell(r.get("hops")), m["status"] + (f" ({m['error']})" if m.get("error") else ""),
                     cell((m.get("codec") or {}).get("hex"))])
    if args.md:
        print("| " + " | ".join(head) + " |\n|" + "---|" * len(head))
        for row in rows:
            print("| " + " | ".join(row) + " |")
    else:
        widths = [max(len(head[i]), *(len(row[i]) for row in rows)) if rows else len(head[i]) for i in range(len(head) - 1)]
        for row in [head] + rows:
            print("  ".join(c.ljust(w) for c, w in zip(row, widths)) + "  " + row[-1])
    sizes = [int(r[3]) for r in rows if r[3]]
    if sizes:
        print(f"\n{len(sizes)} coded messages, {sum(sizes)} bytes in total, {sum(sizes) / len(sizes):.1f} bytes on average.")
    print("\nTasks:")
    for t in tasks:
        created = when(t["created"])
        acks = t.get("acks") or []
        parts = []
        for a in acks:
            at = when(a.get("time"))
            if at and created:
                parts.append(f"{a.get('status')} after {(at - created).total_seconds():.0f} s")
        print(f"  {t['key']}  {t['type']}  {t['state']}  " + (", ".join(parts) if parts else "no answer yet"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
