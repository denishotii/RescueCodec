#!/usr/bin/env python3
"""Full end-to-end scenario on the simulator: Android app (emulator) <-> SIM-FIELD ~~ SIM-BASE <-> gateway <-> dashboard.

Run it with scripts/e2e-full.sh (starts and stops everything). Simulator and emulator only: no USB device, no
Bluetooth, no real radio is touched.

Per run:
  1. App: type the German pitch sentence (with umlauts), check the fields, Send.
  2. Dashboard: the card shows in German and English with a map pin; stats update; MQTT and CoT are observed.
  3. Dashboard Accept -> app task turns accepted. Dashboard Done -> app task turns done.
  4. App: 5 GPS track batches are queued while the radio is away, then a Mayday from the SOS strip. The Mayday must
     reach the gateway first.
  5. App: a sentence the rules cannot code goes out as readable text.
  6. Gateway restart and app background/foreground: state persists, the connection recovers.

Screenshots go to .overnight/gallery/e2e/, numbers to the results file (default: <tmp>/e2e-results.json).
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import random
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from e2e_gateway import CotWatcher, MqttWatcher, StreamWatcher, call, wait  # noqa: E402

PKG = "org.rescuecodec.app"
GALLERY = Path(os.environ.get("E2E_GALLERY") or ROOT / ".overnight" / "gallery" / "e2e")
API = "http://127.0.0.1:8000"
PITCH = "Kind gefunden, ansprechbar, stark unterkühlt, Knöchel verletzt. Wir brauchen eine Trage und zwei Leute."
# Sentences the rules cannot code. One per run: the gateway drops a text with the same sender and bytes seen in the last 10 minutes.
UNSUPPORTED_SENTENCES = ["Wir treffen uns um acht am Parkplatz", "Hundestaffel ist angekommen",
                         "Der Einsatzleiter faehrt zum Parkplatz Nord", "Die Hundefuehrer essen jetzt Mittag"]
FIELD_LAT, FIELD_LON = 50.69212, 7.21345
CLI = os.environ.get("PLAYWRIGHT_CLI") or shutil.which("playwright-cli") or "playwright-cli"


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def sh(args, check=True, timeout=120, **kw) -> subprocess.CompletedProcess:
    return subprocess.run(args, check=check, timeout=timeout, capture_output=True, text=True, **kw)


# ----------------------------------------------------------------------------------------------- Android

def adb(*args, check=True, timeout=60) -> str:
    return sh(["adb", *args], check=check, timeout=timeout).stdout


def ui_dump() -> ET.Element:
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/rc-ui.xml", check=False)
        out = sh(["adb", "exec-out", "cat", "/sdcard/rc-ui.xml"], check=False).stdout
        if out.strip().startswith("<?xml"):
            try:
                return ET.fromstring(out)
            except ET.ParseError:
                pass
        time.sleep(0.5)
    raise RuntimeError("uiautomator dump failed")


def rid(n: ET.Element) -> str:
    return n.attrib.get("resource-id", "").split("/")[-1]


def centre(n: ET.Element) -> tuple[int, int]:
    import re
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.attrib["bounds"]))
    return (x1 + x2) // 2, (y1 + y2) // 2


def subtree_text(n: ET.Element) -> str:
    parts = []
    for e in n.iter("node"):
        for k in ("text", "content-desc"):
            if e.attrib.get(k):
                parts.append(e.attrib[k])
    return " | ".join(parts)


def find(root: ET.Element, ident: str, prefix=False) -> ET.Element | None:
    for n in root.iter("node"):
        r = rid(n)
        if r == ident or (prefix and r.startswith(ident)):
            return n
    return None


def tap_id(ident: str, tries=4, prefix=False) -> None:
    for _ in range(tries):
        n = find(ui_dump(), ident, prefix)
        if n is not None:
            x, y = centre(n)
            adb("shell", "input", "tap", str(x), str(y))
            return
        time.sleep(1)
    raise AssertionError(f"app element not found: {ident}")


def find_task(key: str, scrolls=6) -> ET.Element | None:
    """Find a task card on the Actions screen. The list is lazy, so scroll down until the card shows up."""
    n = find(ui_dump(), f"task_{key}")
    if n is not None:
        return n
    for _ in range(scrolls):
        adb("shell", "input", "swipe", "540", "1500", "540", "600", "250")
        time.sleep(0.4)
        n = find(ui_dump(), f"task_{key}")
        if n is not None:
            return n
    for _ in range(scrolls):
        adb("shell", "input", "swipe", "540", "600", "540", "1700", "200")
    return None


def screen_text() -> str:
    return subtree_text(ui_dump())


def app_shot(name: str) -> str:
    GALLERY.mkdir(parents=True, exist_ok=True)
    path = GALLERY / f"{name}.png"
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=True, timeout=30).stdout)
    return path.name


def open_screen(screen: str) -> None:
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "screen", screen)
    time.sleep(1.5)


def set_gps() -> None:
    """The emulator has no GPS fix by itself. A test provider gives the app a fresh position near Koeln."""
    adb("shell", "appops", "set", "com.android.shell", "android:mock_location", "allow", check=False)
    adb("shell", "cmd", "location", "providers", "add-test-provider", "gps", check=False)
    adb("shell", "cmd", "location", "providers", "set-test-provider-enabled", "gps", "true", check=False)
    adb("shell", "cmd", "location", "providers", "set-test-provider-location", "gps",
        "--location", f"{FIELD_LAT},{FIELD_LON}", "--accuracy", "5", check=False)


def inject_text(text: str, screen="REPORT") -> None:
    b64 = base64.b64encode(text.encode("utf-8")).decode()
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "screen", screen, "--es", "report_text_b64", b64)
    time.sleep(1.5)


def app_link_connected() -> bool:
    return "Connected to SIM-FIELD" in screen_text()


def intent_json(message: dict) -> None:
    adb("shell", "am", "start", "-a", "org.rescuecodec.action.SEND_REPORT", "-p", PKG,
        "--es", "org.rescuecodec.extra.JSON", "'" + json.dumps(message, separators=(",", ":")) + "'")


# ---------------------------------------------------------------------------------------------- dashboard

def pw(*args, check=True, timeout=60) -> str:
    return sh([CLI, *args], check=check, timeout=timeout, cwd=PW_DIR).stdout


PW_DIR = Path(tempfile.mkdtemp(prefix="rc-pw-"))


def dash_shot(name: str) -> str:
    GALLERY.mkdir(parents=True, exist_ok=True)
    pw("screenshot", "--filename", str(GALLERY / f"{name}.png"))
    return f"{name}.png"


def dash_text() -> str:
    out = pw("--raw", "eval", "document.body.innerText")
    return out


def dash_wait_text(needle: str, timeout=30) -> float:
    t0 = time.monotonic()
    wait(lambda: needle in dash_text(), f"dashboard shows {needle!r}", timeout, 0.5)
    return time.monotonic() - t0


def dash_click(name: str, exact=True, index=0) -> None:
    sel = f"getByRole('button', {{ name: '{name}', exact: {str(exact).lower()} }}).nth({index})"
    pw("click", sel)


def dash_answer(name: str, index=0) -> None:
    """Click Accept or Done on the dashboard. Accept opens a small form (team, ETA): confirm it."""
    dash_click(name, index=index)
    time.sleep(0.6)
    if pw("--raw", "eval", "[...document.querySelectorAll('button')].some(b => b.textContent.trim() === 'Confirm')").strip().startswith("true"):
        dash_click("Confirm")


# -------------------------------------------------------------------------------------------- processes

class Stack:
    def __init__(self, workdir: Path, keep: bool):
        self.workdir = workdir
        self.keep = keep
        self.gw: subprocess.Popen | None = None
        self.started: list[str] = []

    def gateway_cmd(self) -> list[str]:
        return [sys.executable, "-m", "rescuecodec.gateway", "--tcp", "127.0.0.1:4404", "--mqtt", "127.0.0.1:1883",
                "--cot", "--cot-udp", "239.2.3.1:16969", "--cot-iface", "127.0.0.1", "--db", str(self.workdir / "gateway.db"),
                "--team", "Leitstelle", "--port", "8000", "--log-level", "INFO"]

    def start_gateway(self) -> None:
        self.log = open(self.workdir / "gateway.log", "ab")
        self.gw = subprocess.Popen(self.gateway_cmd(), cwd=ROOT, stdout=self.log, stderr=subprocess.STDOUT)
        wait(lambda: self.health().get("radio", {}).get("connected") and self.health().get("mqtt", {}).get("connected"),
             "gateway connected to SIM-BASE and MQTT", 60, 0.5)

    def stop_gateway(self) -> None:
        if self.gw and self.gw.poll() is None:
            self.gw.terminate()
            try:
                self.gw.wait(10)
            except subprocess.TimeoutExpired:
                self.gw.kill()
        self.gw = None

    def health(self) -> dict:
        try:
            return call(API, "GET", "/v2/health", timeout=3)[1] or {}
        except (urllib.error.URLError, ConnectionError, OSError, socket.timeout):
            return {}

    def stop_all(self) -> None:
        self.stop_gateway()
        if self.keep:
            return
        sh([str(ROOT / "scripts/emulator-down.sh")], check=False)
        sh([str(ROOT / "scripts/mqtt-down.sh")], check=False)
        sh([str(ROOT / "sim/down.sh")], check=False, timeout=180)


def get(path: str):
    return call(API, "GET", path)[1]


def tasks_by_type(type_: str) -> list[dict]:
    return [t for t in get("/v2/tasks")["items"] if t["type"] == type_]


# ------------------------------------------------------------------------------------------------ steps

def step_found(run: int, res: dict, stream: StreamWatcher, mqtt: MqttWatcher, cot: CotWatcher) -> str:
    """1 + 2 + 3: type, send, dashboard card, Accept, Done."""
    p = f"run{run}"
    set_gps()
    known = {t["key"] for t in get("/v2/tasks")["items"]}
    inject_text(PITCH)
    txt = screen_text()
    for needle in ("Person found", "child", "conscious", "hypothermia", "fracture", "stretcher", "2 more helpers"):
        assert needle in txt, f"field chip missing in the app: {needle!r}\n{txt}"
    byte_counter = [s for s in txt.split(" | ") if "bytes" in s]
    app_shot(f"{p}-01-app-fields")
    t_send = time.monotonic()
    tap_id("send_button")
    # the task reaches the gateway over the simulated mesh
    task = wait(lambda: next((t for t in get("/v2/tasks")["items"] if t["key"] not in known and t["type"] == "found"), None),
                "gateway receives the found task", 60, 0.1)
    res["send_to_gateway_s"] = round(time.monotonic() - t_send, 2)
    key = task["key"]
    app_shot(f"{p}-02-app-sent")
    assert task["state"] == "open" and task["priority"] == "urgent", task
    assert abs(task["pos"]["lat"] - FIELD_LAT) < 1e-4 and abs(task["pos"]["lon"] - FIELD_LON) < 1e-4, task["pos"]
    assert task["text"]["de"].startswith("Person gefunden: Kind, ansprechbar, Unterkühlung, Knochenbruch, braucht Trage, 2 weitere Helfer"), task["text"]
    assert task["text"]["en"].startswith("Person found: child, conscious, hypothermia, fracture, needs stretcher, 2 more helpers"), task["text"]
    rec = next(m for m in get("/v2/messages?direction=in")["items"] if m.get("task_key") == key or m.get("key") == key)
    res["found_bytes"] = rec["codec"]["size"]
    res["found_text_bytes"] = len(PITCH.encode("utf-8"))
    res["app_byte_counter"] = byte_counter

    # dashboard: card in English and German, map pin
    pw("goto", API + "/")
    pw("resize", "1440", "900")
    res["dashboard_card_s"] = round(dash_wait_text("Person found: child, conscious", 20), 2)
    time.sleep(1.5)  # let the map settle on the pin
    assert "Open" in dash_text()
    dash_shot(f"{p}-03-dashboard-card-en")
    dash_click("DE")
    dash_wait_text("Person gefunden: Kind, ansprechbar", 10)
    time.sleep(0.5)
    dash_shot(f"{p}-04-dashboard-card-de")
    dash_click("EN")
    pin = pw("--raw", "eval", "document.querySelectorAll('.maplibregl-marker').length")
    res["map_pins"] = int(pin.strip().split()[0]) if pin.strip().split()[0].isdigit() else pin.strip()
    assert res["map_pins"] not in (0, "0"), "no map pin on the dashboard"

    # stats, MQTT, CoT, readable line
    wait(lambda: get("/v2/stats"), "stats", 5)
    stats = get("/v2/stats")
    res["stats"] = stats
    wait(lambda: any(m.get("key") == key for m in mqtt.json("rescuecodec/v2/found")), "MQTT found", 15)
    wait(lambda: any(t["key"] == key and t["state"] == "open" for t in mqtt.json("rescuecodec/v2/tasks")), "MQTT task open", 15)
    uid = f"RescueCodec.{key}"
    wait(lambda: cot.for_uid(uid), "CoT marker", 15)
    ev = cot.for_uid(uid)[0]
    assert ev.get("type") == "b-m-p-s-m", ev.attrib
    assert abs(float(ev.find("point").get("lat")) - FIELD_LAT) < 1e-4
    readable = [p_.decode() for t, p_ in mqtt.seen if t == "rescuecodec/v2/readable"]
    assert any(line.startswith("Person found: child") for line in readable), readable[-3:]
    res["outputs"] = {"mqtt_topics": sorted({t for t, _ in mqtt.seen}), "cot_type": ev.get("type"), "readable": readable[-1]}

    # 3. Accept on the dashboard -> the app turns accepted
    open_screen("ACTIONS")
    app_shot(f"{p}-05-app-actions-open")
    dash_click("Accept")
    time.sleep(0.6)
    dash_shot(f"{p}-05b-dashboard-accept-form")
    t_acc = time.monotonic()
    dash_click("Confirm")
    wait(lambda: get(f"/v2/tasks/{key}")["state"] == "accepted", "gateway task accepted", 15, 0.2)

    def app_state(word: str):
        n = find_task(key)
        return n is not None and word in subtree_text(n)

    wait(lambda: app_state("Accepted"), "app task accepted", 60, 0.2)
    res["accept_to_app_s"] = round(time.monotonic() - t_acc, 2)
    app_shot(f"{p}-06-app-accepted")
    dash_shot(f"{p}-07-dashboard-accepted")
    wait(lambda: any(t["key"] == key and t["state"] == "accepted" for t in mqtt.json("rescuecodec/v2/tasks")), "MQTT accepted", 15)
    wait(lambda: any("[ACCEPTED" in (e.findtext("detail/remarks") or "") for e in cot.for_uid(uid)), "CoT accepted", 15)
    # Done
    t_done = time.monotonic()
    dash_answer("Done")
    wait(lambda: app_state("Done"), "app task done", 60, 0.2)
    res["done_to_app_s"] = round(time.monotonic() - t_done, 2)
    app_shot(f"{p}-08-app-done")
    dash_shot(f"{p}-09-dashboard-done")
    wait(lambda: any("[DONE" in (e.findtext("detail/remarks") or "") for e in cot.for_uid(uid)), "CoT done", 15)
    assert get(f"/v2/tasks/{key}")["state"] == "done"
    return key


def step_mayday(run: int, res: dict, mqtt: MqttWatcher) -> None:
    """4: 5 track batches wait for the radio, then a Mayday from the SOS strip. The Mayday must reach the gateway first."""
    p = f"run{run}"
    before = {m["seq"] for m in get("/v2/messages?direction=in")["items"]}
    sh(["docker", "stop", "rc-sim-field"], timeout=60)
    time.sleep(3)
    open_screen("RADIO")
    time.sleep(2)
    app_shot(f"{p}-10-app-radio-away")
    for i in range(5):
        # different points every run: the gateway drops a packet with the same sender and bytes seen in the last 10 minutes
        shift = run * 0.0123 + random.random() * 0.001
        pts = [{"lat": round(FIELD_LAT + shift + 0.0004 * (i * 4 + j), 5), "lon": round(FIELD_LON + shift + 0.0003 * (i * 4 + j), 5)} for j in range(4)]
        intent_json({"track": {"points": pts}})
        time.sleep(1.0)
    open_screen("FEED")
    time.sleep(1)
    app_shot(f"{p}-11-app-5-tracks-queued")
    # hold SOS for 2.3 s (the strip is on every screen)
    n = find(ui_dump(), "sos_bar")
    assert n is not None, "SOS bar not found"
    x, y = centre(n)
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "2600", timeout=30)
    time.sleep(1.5)
    app_shot(f"{p}-12-app-sos-queued")
    sh(["docker", "start", "rc-sim-field"], timeout=60)
    t0 = time.monotonic()
    new = wait(lambda: (lambda items: items if sum(1 for m in items if m["type"] in ("track", "mayday")) >= 6 else None)(
        [m for m in get("/v2/messages?direction=in")["items"] if m["seq"] not in before]),
        "gateway receives mayday and 5 tracks after the radio returns", 180, 0.5)
    res["radio_back_to_all_six_s"] = round(time.monotonic() - t0, 1)
    order = [m["type"] for m in sorted(new, key=lambda m: m["seq"]) if m["type"] in ("track", "mayday")]
    res["queue_order_at_gateway"] = order
    assert order[0] == "mayday", f"Mayday did not jump the queue: {order}"
    assert order.count("track") == 5, order
    # dashboard: banner
    pw("goto", API + "/")
    dash_wait_text("MAYDAY", 15)
    time.sleep(1)
    dash_shot(f"{p}-13-dashboard-mayday-banner")
    # answer the Mayday on the dashboard (banner Accept, then Done) so the next steps start clean
    may = next(t for t in tasks_by_type("mayday") if t["state"] == "open")
    dash_answer("Accept")
    wait(lambda: get(f"/v2/tasks/{may['key']}")["state"] == "accepted", "mayday accepted", 15, 0.5)
    open_screen("ACTIONS")
    time.sleep(1)
    app_shot(f"{p}-14-app-mayday-accepted")
    dash_answer("Done")
    wait(lambda: get(f"/v2/tasks/{may['key']}")["state"] == "done", "mayday done", 15, 0.5)
    wait(lambda: any(t["key"] == may["key"] and t["state"] == "done" for t in mqtt.json("rescuecodec/v2/tasks")), "MQTT mayday done", 15)
    # the app link must be back
    wait(app_link_connected, "app reconnected to SIM-FIELD", 90, 2)


def step_unsupported(run: int, res: dict) -> None:
    """5: a sentence the rules cannot code arrives as readable text."""
    p = f"run{run}"
    UNSUPPORTED = UNSUPPORTED_SENTENCES[(run - 1) % len(UNSUPPORTED_SENTENCES)]
    before = {m["seq"] for m in get("/v2/messages?direction=in")["items"]}
    open_screen("REPORT")
    inject_text(UNSUPPORTED)
    time.sleep(1)
    app_shot(f"{p}-15-app-unsupported")
    txt = screen_text()
    res["app_unsupported_screen"] = [s for s in txt.split(" | ") if s and len(s) < 120][:25]
    tap_id("send_button")
    msg = wait(lambda: next((m for m in get("/v2/messages?direction=in")["items"] if m["seq"] not in before and m["type"] == "text"), None),
               "gateway receives the text message", 60, 0.3)
    assert UNSUPPORTED in json.dumps(msg, ensure_ascii=False), msg
    res["text_bytes"] = msg["codec"]["size"]
    pw("goto", API + "/")
    dash_wait_text(UNSUPPORTED, 15)
    time.sleep(1)
    dash_shot(f"{p}-16-dashboard-text")


def step_restart(run: int, res: dict, stack: Stack, key: str, stream_holder: dict, mqtt: MqttWatcher) -> None:
    """6: gateway restart and app background/foreground."""
    p = f"run{run}"
    before = get("/v2/tasks")["items"]
    before_msgs = get("/v2/messages")["items"]
    stats_before = get("/v2/stats")
    stack.stop_gateway()
    time.sleep(2)
    pw("reload", check=False)
    time.sleep(2)
    dash_shot(f"{p}-17-dashboard-gateway-down")
    t0 = time.monotonic()
    stack.start_gateway()
    res["gateway_restart_to_radio_s"] = round(time.monotonic() - t0, 1)
    after = get("/v2/tasks")["items"]
    assert {t["key"]: t["state"] for t in before} == {t["key"]: t["state"] for t in after}, "task states changed over a gateway restart"
    assert len(get("/v2/messages")["items"]) >= len(before_msgs), "messages lost over a gateway restart"
    stats_after = get("/v2/stats")
    for k in ("messages_in", "messages_out", "bytes_in", "bytes_out"):
        assert stats_after[k]["value"] >= stats_before[k]["value"], f"stats restarted from zero: {k}"
    pw("reload")
    dash_wait_text("Person found: child, conscious", 20)
    time.sleep(2)
    dash_shot(f"{p}-18-dashboard-after-restart")
    # an Accept or Done that no relay repeated is worded "no relay confirmed", not "failed"
    dash_wait_text("no relay confirmed", 30)
    pw("click", "getByRole('button', { name: /^Other/ })")
    time.sleep(1)
    assert "No relay confirmed" in dash_text(), "dashboard does not say 'No relay confirmed' for max_retransmit"
    dash_shot(f"{p}-18b-dashboard-no-relay-confirmed")
    pw("click", "getByRole('button', { name: /^All/ })")
    # app to background and back
    open_screen("ACTIONS")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(8)
    app_shot(f"{p}-19-emulator-home")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2)
    open_screen("ACTIONS")
    txt = screen_text()
    n = find_task(key)
    assert n is not None and "Done" in subtree_text(n), "task list lost after background/foreground"
    app_shot(f"{p}-20-app-back-from-background")
    wait(app_link_connected, "app still connected after background", 30, 2)
    # a new report after both restarts must still get through, and the new gateway sees it
    known = {m["seq"] for m in get("/v2/messages?direction=in")["items"]}
    set_gps()
    inject_text("Hiker found, conscious, broken ankle, need stretcher")
    tap_id("send_button")
    msg = wait(lambda: next((m for m in get("/v2/messages?direction=in")["items"] if m["seq"] not in known and m["type"] == "found"), None),
               "new report after restarts reaches the gateway", 60, 0.3)
    res["after_restart_report_bytes"] = msg["codec"]["size"]
    app_shot(f"{p}-21-app-after-restart-sent")
    # clear that task so the next run starts clean
    dash_click_task_done()


def dash_click_task_done() -> None:
    pw("goto", API + "/")
    time.sleep(2)
    dash_answer("Accept")
    time.sleep(1.5)
    dash_answer("Done")
    time.sleep(1.5)


# ------------------------------------------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--keep", action="store_true", help="leave the simulator, emulator and MQTT running")
    ap.add_argument("--results", default=None)
    ap.add_argument("--no-install", action="store_true")
    ap.add_argument("--fresh", action="store_true", help="clear the app data first (empty task list)")
    args = ap.parse_args()
    workdir = Path(tempfile.mkdtemp(prefix="rc-e2e-full-"))
    results_path = Path(args.results) if args.results else workdir / "e2e-results.json"
    stack = Stack(workdir, args.keep)
    all_results: list[dict] = []
    mqtt = cot = stream = None
    try:
        log("simulator, MQTT, emulator")
        # sim/up.sh configures the nodes over their TCP API, and a node serves one API client at a time: running it
        # would kick the app (and the gateway) off the radio. So only run it when the simulator is not up yet.
        running = sh(["docker", "ps", "--format", "{{.Names}}"]).stdout.split()
        if not all(n in running for n in ("rc-sim-field", "rc-sim-base", "rc-sim-relay")):
            sh([str(ROOT / "sim/up.sh")], timeout=300)
        sh([str(ROOT / "scripts/mqtt-up.sh")])
        sh([str(ROOT / "scripts/emulator-up.sh")], timeout=400)
        if not args.no_install:
            apk = ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"
            adb("install", "-r", str(apk), timeout=180)
        for perm in ("POST_NOTIFICATIONS", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "CAMERA"):
            adb("shell", "pm", "grant", PKG, f"android.permission.{perm}", check=False)
        adb("shell", "cmd", "uimode", "night", "no", check=False)
        for f in GALLERY.glob("*.png") if GALLERY.exists() else []:
            f.unlink()
        log("gateway on SIM-BASE")
        for f in ROOT.glob("rescuecodec/gateway/static/index.html"):
            assert f.read_text().find("assets/") > 0, "dashboard is not built (run npm run build in dashboard/)"
        stack.start_gateway()
        mqtt, cot = MqttWatcher(), CotWatcher()
        pw("open", API + "/", timeout=120)
        log("app connects to SIM-FIELD")
        set_gps()
        if args.fresh:
            adb("shell", "pm", "clear", PKG)
            for perm in ("POST_NOTIFICATIONS", "RECORD_AUDIO", "ACCESS_FINE_LOCATION", "CAMERA"):
                adb("shell", "pm", "grant", PKG, f"android.permission.{perm}", check=False)
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "screen", "REPORT")
        time.sleep(2)
        if not app_link_connected():
            # The connect extra (debug builds) is read when the app process starts, so restart the process.
            adb("shell", "am", "force-stop", PKG)
            adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--es", "screen", "REPORT", "--es", "connect", "10.0.2.2:4403")
            wait(app_link_connected, "app connected to SIM-FIELD", 90, 2)
        time.sleep(3)
        app_shot("00-app-connected")
        pw("goto", API + "/")
        time.sleep(3)
        dash_shot("00-dashboard-empty")
        for run in range(1, args.runs + 1):
            res: dict = {"run": run}
            t0 = time.monotonic()
            log(f"=== run {run}")
            try:
                key = step_found(run, res, None, mqtt, cot)
                log("found/accept/done ok", {k: res[k] for k in ("send_to_gateway_s", "dashboard_card_s", "accept_to_app_s", "done_to_app_s")})
                step_mayday(run, res, mqtt)
                log("mayday ok", res["queue_order_at_gateway"])
                step_unsupported(run, res)
                log("unsupported text ok")
                step_restart(run, res, stack, key, {}, mqtt)
                log("restart ok")
                res["passed"] = True
            except BaseException as exc:  # keep going, report
                res["passed"] = False
                res["error"] = f"{type(exc).__name__}: {exc}"[:1500]
                log("RUN FAILED", res["error"])
                try:
                    app_shot(f"run{run}-FAILED-app")
                    dash_shot(f"run{run}-FAILED-dashboard")
                except Exception:
                    pass
            res["seconds"] = round(time.monotonic() - t0, 1)
            all_results.append(res)
            results_path.write_text(json.dumps(all_results, indent=1, ensure_ascii=False, default=str))
            # make sure the radio and the gateway are healthy before the next run
            if not stack.health().get("radio", {}).get("connected"):
                stack.stop_gateway()
                stack.start_gateway()
            sh(["docker", "start", "rc-sim-field"], check=False)
        print(json.dumps(all_results, indent=1, ensure_ascii=False, default=str))
        log("results:", results_path)
        return 0 if all(r.get("passed") for r in all_results) else 1
    finally:
        for w in (mqtt, cot, stream):
            try:
                w and w.stop()
            except Exception:
                pass
        pw("close-all", check=False)
        stack.stop_all()


if __name__ == "__main__":
    sys.exit(main())
