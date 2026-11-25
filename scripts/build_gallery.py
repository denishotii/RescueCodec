#!/usr/bin/env python3
"""Builds build/screenshots.html: every screenshot with a caption.

    python3 scripts/build_gallery.py

It shows docs/screenshots/app/, docs/screenshots/dashboard/ and, if it exists, the last end-to-end run in
build/e2e/ (scripts/e2e-full.sh). Plain HTML with relative image links, no scripts, no network. Open the file
in a browser. build/ is not in git.
"""
from __future__ import annotations

import html
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build" / "screenshots.html"

# Captions for the end-to-end run (file name after "runN-NN-").
E2E = {
    "app-connected": "App on the emulator, connected to SIM-FIELD (10.0.2.2:4403)",
    "dashboard-empty": "Dashboard served by the real gateway on SIM-BASE, before any report",
    "app-fields": "App: the German sentence with umlauts, read into fields (check before sending)",
    "app-sent": "App: report queued, bytes as text and bytes sent",
    "dashboard-card-en": "Dashboard: the card in English, map pin, stats updated",
    "dashboard-card-de": "Dashboard: the same card in German",
    "app-actions-open": "App, Actions tab: the task is open",
    "dashboard-accept-form": "Dashboard: Accept asks for team and arrival time",
    "app-accepted": "App: the task turned accepted after Accept on the dashboard",
    "dashboard-accepted": "Dashboard: task in the Accepted lane",
    "app-done": "App: the task turned done",
    "dashboard-done": "Dashboard: task in the Done lane",
    "app-radio-away": "App: radio switched off, link lost",
    "app-5-tracks-queued": "App: GPS track batches waiting for the radio",
    "app-sos-queued": "App: SOS held for two seconds while the track batches wait",
    "dashboard-mayday-banner": "Dashboard: the Mayday arrived first, red banner",
    "app-mayday-accepted": "App: the Mayday is accepted by the command post",
    "app-unsupported": "App: a sentence the rules cannot code, offered as plain text",
    "dashboard-text": "Dashboard: the unsupported sentence arrived as readable text",
    "dashboard-gateway-down": "Dashboard while the gateway is stopped",
    "dashboard-after-restart": "Dashboard after the gateway restart: cards are still there",
    "emulator-home": "Emulator home screen: the app is in the background",
    "app-back-from-background": "App back from the background: task list kept, link up",
    "app-after-restart-sent": "App: a new report after both restarts",
}
FOLDERS = [("app", ROOT / "docs" / "screenshots" / "app", "Android app screens"),
           ("dashboard", ROOT / "docs" / "screenshots" / "dashboard", "Dashboard screens"),
           ("e2e", ROOT / "build" / "e2e", "Last end-to-end run on the simulator")]


def caption(folder: str, name: str) -> str:
    stem = Path(name).stem
    if folder == "e2e":
        m = re.match(r"(run\d+)-(?:\d+b?-)?(.*)$", stem)
        if m:
            run, rest = m.groups()
            if rest.startswith("FAILED"):
                return f"{run}: failure screenshot ({rest.replace('FAILED-', '')})"
            return f"{run}: {E2E.get(rest, rest.replace('-', ' '))}"
        m = re.match(r"\d+-(.*)$", stem)
        if m:
            return E2E.get(m.group(1), m.group(1).replace("-", " "))
    return stem.replace("-", " ").replace("_", " ")


def main() -> None:
    parts = []
    total = 0
    for folder, path, title in FOLDERS:
        files = sorted(path.glob("*.png")) if path.is_dir() else []
        if not files:
            continue
        total += len(files)
        figs = []
        for f in files:
            cap = html.escape(caption(folder, f.name))
            src = html.escape(Path(os.path.relpath(f, OUT.parent)).as_posix())
            figs.append(f'<figure><a href="{src}"><img src="{src}" alt="{cap}" loading="lazy"></a><figcaption>{cap}<br><code>{html.escape(f.name)}</code></figcaption></figure>')
        parts.append(f"<h2>{html.escape(title)} ({len(files)})</h2>\n<div class=\"grid\">\n" + "\n".join(figs) + "\n</div>")
    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>RescueCodec screenshots</title>
<style>
:root {{ --bg:#f3f4ee; --ink:#1b251f; --mute:#566; --card:#fff; --line:#cfd4c6; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#151a17; --ink:#e8ece4; --mute:#9ab; --card:#202824; --line:#35403a; }} }}
body {{ margin:0; padding:16px; background:var(--bg); color:var(--ink); font:16px/1.4 system-ui, sans-serif; }}
h1 {{ margin:0 0 4px; }} h2 {{ margin:28px 0 12px; border-bottom:2px solid var(--line); padding-bottom:4px; }}
p {{ color:var(--mute); margin:0 0 8px; }}
.grid {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(220px,1fr)); gap:14px; }}
figure {{ margin:0; background:var(--card); border:1px solid var(--line); border-radius:8px; padding:8px; }}
img {{ width:100%; height:auto; display:block; border-radius:4px; }}
figcaption {{ font-size:14px; margin-top:6px; }} code {{ color:var(--mute); font-size:12px; }}
</style></head><body>
<h1>RescueCodec screenshots</h1>
<p>{total} screenshots from the simulator and the Android emulator. Click one to see it full size.</p>
{chr(10).join(parts)}
</body></html>
"""
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(page, encoding="utf-8")
    print(f"{OUT}: {total} screenshots")


if __name__ == "__main__":
    main()
