"""Local demo API and two-responder web interface."""
from __future__ import annotations

import itertools
import json
import time
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

from rescuecodec.protocol.codec import CODEC_CONFIRMATION, CODEC_TEXT, decode_confirmation, decode_report, decode_text, encode_report, encode_text, reconstruct
from rescuecodec.schemas import PersonFoundReport
from rescuecodec.semantic import extract_report
from rescuecodec.transport.emulator import MeshEmulator
from rescuecodec.transport.gateway import PhysicalToVirtualGateway

app = FastAPI(title="RescueCodec", version="0.1.0")
mesh = MeshEmulator()
mesh.connect()
counter = itertools.count(1)
requests: dict[int, dict] = {}
events: list[dict] = []
gateway: PhysicalToVirtualGateway | None = None


def on_receive(packet):
    payload = packet.payload
    try:
        if len(payload) >= 3 and payload[1] == CODEC_CONFIRMATION:
            request_id, status = decode_confirmation(payload)
            target = requests.get(request_id)
            if target:
                target["status"] = "accepted" if status == 1 else "completed"
                if not target["history"] or target["history"][-1]["status"] != target["status"]:
                    target["history"].append({"status": target["status"], "node": packet.destination, "time": time.time()})
            events.append({"kind": "confirmation", "status": status, "request_id": request_id, "from": packet.source, "to": packet.destination})
            return
        if len(payload) >= 3 and payload[1] == CODEC_TEXT:
            text, message_id = decode_text(payload)
            entry = requests.setdefault(message_id, {"request_id": message_id, "status": "delivered", "history": []})
            entry.update({"status": "delivered", "reconstructed": text, "source": packet.source, "destination": packet.destination, "path": packet.hops, "payload_bytes": len(payload), "kind": "text"})
            events.append({"kind": "text", "from": packet.source, "to": packet.destination, "bytes": len(payload)})
            return
        report, request_id, sent_at = decode_report(payload)
        entry = requests.setdefault(request_id, {"request_id": request_id, "status": "delivered", "history": []})
        if entry.get("status") not in ("accepted", "completed"):
            entry["status"] = "delivered"
        entry.update({"report": report.model_dump(mode="json"), "reconstructed": reconstruct(report), "source": packet.source,
                      "destination": packet.destination, "path": packet.hops, "payload_bytes": len(payload)})
        if entry["status"] == "delivered" and (not entry["history"] or entry["history"][-1]["status"] != "delivered"):
            entry["history"].append({"status": "delivered", "node": packet.destination, "time": time.time()})
        events.append({"kind": "report", "request_id": request_id, "from": packet.source, "to": packet.destination, "bytes": len(payload), "path": packet.hops})
    except ValueError as exc:
        events.append({"kind": "decode_error", "error": str(exc)})


for node in ("A", "B", "C"):
    mesh.subscribe(node, on_receive)


class ExtractRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)


class SendRequest(BaseModel):
    source: str = "A"
    destination: str = "C"
    report: PersonFoundReport


class ActionRequest(BaseModel):
    node: str = "C"


class TextSendRequest(BaseModel):
    text: str = Field(min_length=1, max_length=190)
    source: str = "A"
    destination: str = "C"


class LinkRequest(BaseModel):
    a: str
    b: str
    up: bool


@app.get("/", response_class=HTMLResponse)
def home(): return HTML


@app.get("/api/status")
def status():
    return {"transport": mesh.capabilities, "metrics": mesh.metrics, "gateway": gateway.capabilities if gateway and gateway.running else {"running": False}, "requests": list(requests.values()), "events": events[-50:], "ollama_configured": bool(__import__("os").getenv("RESCUECODEC_OLLAMA_MODEL"))}


@app.post("/api/gateway/start")
def start_gateway():
    global gateway
    if gateway and gateway.running: return gateway.capabilities
    gateway = PhysicalToVirtualGateway(mesh)
    try:
        return gateway.start()
    except Exception as exc:
        gateway = None
        raise HTTPException(503, f"Could not open read-only USB gateway: {exc}") from exc


@app.post("/api/gateway/stop")
def stop_gateway():
    global gateway
    if gateway: gateway.stop(); gateway = None
    return {"running": False, "radio_tx_enabled": False}


@app.post("/api/extract")
def extract(body: ExtractRequest):
    result = extract_report(body.text)
    report = result["report"]
    result["report"] = report.model_dump(mode="json")
    result["reconstructed_preview"] = reconstruct(report)
    result["payload_bytes"] = len(encode_report(report, 0)) if result["supported"] else None
    return result


@app.post("/api/send")
def send(body: SendRequest):
    if body.source == body.destination: raise HTTPException(400, "Sender and recipient must differ")
    request_id = next(counter)
    payload = encode_report(body.report, request_id, int(time.time()))
    record = {"request_id": request_id, "status": "submitted", "report": body.report.model_dump(mode="json"),
              "source": body.source, "destination": body.destination, "payload_bytes": len(payload), "hex": payload.hex(),
              "airtime": __import__("rescuecodec.encoding.airtime", fromlist=["airtime_report"]).airtime_report(len(payload)),
              "original_bytes": len((body.report.source_text or json.dumps(body.report.model_dump(mode="json"), ensure_ascii=False)).encode()),
              "history": [{"status": "submitted", "node": body.source, "time": time.time()}]}
    requests[request_id] = record
    mesh.send(body.source, body.destination, payload)
    if record["status"] == "submitted": record["status"] = "unknown"
    return record


@app.post("/api/text")
def send_text(body: TextSendRequest):
    request_id = next(counter)
    payload = encode_text(body.text, request_id)
    requests[request_id] = {"request_id": request_id, "status": "submitted", "kind": "text", "source": body.source, "destination": body.destination, "payload_bytes": len(payload), "history": []}
    mesh.send(body.source, body.destination, payload)
    if requests[request_id]["status"] == "submitted": requests[request_id]["status"] = "unknown"
    return requests[request_id]


def _confirm(request_id: int, node: str, status: int):
    record = requests.get(request_id)
    if not record: raise HTTPException(404, "Unknown request")
    if record.get("kind") == "text": raise HTTPException(409, "Ordinary text messages do not have task responsibility actions")
    if record.get("status") not in ("delivered", "accepted", "completed"): raise HTTPException(409, "Request is not confirmed delivered to the receiver")
    if status == 2 and record.get("receiver_status") != "accepted": raise HTTPException(409, "Accept responsibility before completing")
    if record.get("receiver_status") == "completed": return record
    if status == 1: record["receiver_status"] = "accepted"
    dest = record["source"]
    mesh.send(node, dest, __import__("rescuecodec.protocol.codec", fromlist=["confirmation_packet"]).confirmation_packet(request_id, status))
    if status == 2: record["receiver_status"] = "completed"
    return record


@app.post("/api/requests/{request_id}/accept")
def accept(request_id: int, body: ActionRequest): return _confirm(request_id, body.node, 1)


@app.post("/api/requests/{request_id}/complete")
def complete(request_id: int, body: ActionRequest): return _confirm(request_id, body.node, 2)


@app.post("/api/network/link")
def set_link(body: LinkRequest):
    mesh.set_link(body.a, body.b, body.up)
    events.append({"kind": "link", "a": body.a, "b": body.b, "up": body.up})
    return {"ok": True, "links": {n: sorted(v) for n, v in mesh.links.items()}}


@app.post("/api/network/reset")
def reset_network():
    mesh.links = {"A": {"B"}, "B": {"A", "C"}, "C": {"B"}}
    mesh.available = {"A": True, "B": True, "C": True}
    return {"ok": True, "links": {n: sorted(v) for n, v in mesh.links.items()}}


def run():
    import uvicorn
    uvicorn.run("rescuecodec.api:app", host="0.0.0.0", port=8000, reload=False)


HTML = r'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>RescueCodec | Field Link</title><style>
:root{color-scheme:dark;--bg:#0b1114;--panel:#131d21;--line:#28383c;--text:#edf4f1;--muted:#98aaa7;--lime:#b8ec67;--orange:#ffb45c;--red:#ff7268}*{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at 80% 0,#1a2a2b 0,transparent 38%),var(--bg);color:var(--text);font:15px/1.45 Inter,ui-sans-serif,system-ui,sans-serif}header{display:flex;align-items:center;justify-content:space-between;padding:18px 5vw;border-bottom:1px solid var(--line)}.brand{font-weight:800;letter-spacing:.08em}.brand i{color:var(--lime);font-style:normal}.pill{border:1px solid #47614b;border-radius:999px;color:var(--lime);padding:6px 12px;font-size:12px}main{max-width:1260px;margin:32px auto;padding:0 24px}.hero{display:flex;justify-content:space-between;align-items:end;margin-bottom:24px}.eyebrow{color:var(--lime);font-size:11px;font-weight:700;letter-spacing:.17em;text-transform:uppercase}h1{font-size:clamp(28px,4vw,42px);letter-spacing:-.04em;margin:8px 0 3px}.sub{color:var(--muted);margin:0}.grid{display:grid;grid-template-columns:1.1fr .9fr;gap:18px}.card{background:linear-gradient(145deg,#172226,#11191c);border:1px solid var(--line);border-radius:16px;padding:22px;box-shadow:0 15px 50px #0002}.card h2{font-size:15px;margin:0 0 18px;display:flex;justify-content:space-between}.step{color:var(--lime);font-size:11px;letter-spacing:.1em}textarea{width:100%;min-height:112px;background:#0c1316;border:1px solid #35484b;color:var(--text);border-radius:10px;padding:14px;font:inherit;resize:vertical}button,select{font:inherit;border-radius:8px;border:1px solid #384a4d;background:#1a292d;color:var(--text);padding:10px 13px}button{cursor:pointer}button.primary{background:var(--lime);border-color:var(--lime);color:#10170d;font-weight:800}button:hover{filter:brightness(1.08)}.row{display:flex;gap:10px;align-items:center;margin-top:12px}.small{font-size:12px;color:var(--muted)}.fields{display:grid;grid-template-columns:1fr 1fr;gap:12px}.fields label{font-size:11px;color:var(--muted);display:block;margin-bottom:5px}.fields select,.fields input{width:100%;background:#0c1316;border:1px solid #35484b;border-radius:8px;color:var(--text);padding:10px 13px}.equip{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.equip label{border:1px solid var(--line);border-radius:20px;padding:7px 10px;color:var(--muted);font-size:12px}.equip input{accent-color:var(--lime)}.report{padding:14px;border-radius:10px;background:#0c1518;border:1px solid var(--line);font-size:14px}.metric{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:14px 0}.metric div{background:#0d1518;border:1px solid var(--line);padding:12px;border-radius:9px}.metric b{display:block;font-size:19px;color:var(--lime)}.metric small{color:var(--muted)}pre{white-space:pre-wrap;word-break:break-all;background:#0a1113;padding:12px;border-radius:8px;color:#b9d7b5;font:11px/1.6 ui-monospace,monospace}.lower{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:18px}.request{padding:14px 0;border-top:1px solid var(--line)}.status{color:var(--orange);text-transform:uppercase;font-size:11px;font-weight:800}.status.accepted,.status.completed{color:var(--lime)}.network{display:flex;align-items:center;justify-content:center;gap:14px;padding:15px}.node{border:1px solid #496358;background:#192922;border-radius:50%;height:56px;width:56px;display:grid;place-items:center;font-weight:800;color:var(--lime)}.link{width:80px;height:2px;background:var(--lime)}.event{font:11px ui-monospace,monospace;color:var(--muted);padding:7px 0;border-bottom:1px solid #213034}.warning{font-size:11px;color:var(--orange);margin-top:12px}@media(max-width:820px){.grid,.lower{grid-template-columns:1fr}.hero{align-items:start;gap:12px;flex-direction:column}}
</style></head><body><header><div class="brand">RESCUE<i>CODEC</i></div><div class="pill">● LOCAL SYNTHETIC MESH</div></header><main><div class="hero"><div><div class="eyebrow">Connected. No matter what.</div><h1>Transmit meaning, not words.</h1><p class="sub">Understand · Compress · Confirm — local semantic messaging for constrained links.</p></div><span class="small">Prototype · not for operational use</span></div><div class="grid"><section class="card"><h2><span>Compose emergency report</span><span class="step">01 / UNDERSTAND</span></h2><textarea id="text">I found the missing person. He is unconscious but breathing. We urgently need an oxygen mask and medical assistance.</textarea><div class="row"><button onclick="extract()">Extract meaning</button><button onclick="sendText()">Send ordinary text</button><span class="small" id="method">No cloud connection</span></div><div class="fields" style="margin-top:18px"><label>Consciousness<select id="consciousness"><option value="0">Unknown</option><option value="1">Conscious</option><option value="2">Unconscious</option></select></label><label>Breathing<select id="breathing"><option value="0">Unknown</option><option value="1">Yes</option><option value="2">No</option></select></label><label>Urgency<select id="urgency"><option value="0">Unknown</option><option value="1">Routine</option><option value="2">Urgent</option><option value="3">Critical</option></select></label><label>Request<select id="request"><option value="0">Unknown</option><option value="1">None</option><option value="2">Medical assistance</option><option value="3">Search</option><option value="4">Extraction</option></select></label><label>Latitude<input id="loclat" type="number" min="-90" max="90" step="0.00001" placeholder="Optional"></label><label>Longitude<input id="loclon" type="number" min="-180" max="180" step="0.00001" placeholder="Optional"></label><label>People found<input id="peoplecount" type="number" min="1" max="15" step="1" placeholder="Unknown"></label><label>Additional responders needed (minimum)<input id="responders" type="number" min="1" max="15" step="1" placeholder="Unknown"></label></div><div class="equip"><label><input type="checkbox" value="OXYGEN_MASK" checked> Oxygen mask</label><label><input type="checkbox" value="FIRST_AID_KIT"> First aid</label><label><input type="checkbox" value="AED"> AED</label><label><input type="checkbox" value="STRETCHER"> Stretcher</label></div><div class="row"><label class="small">From <select id="src"><option>A</option><option>C</option></select></label><label class="small">To <select id="dst"><option>C</option><option>A</option></select></label><button class="primary" onclick="send()">Encode & send →</button></div><p class="warning">Synthetic in-memory transport. No radio packet is transmitted.</p></section><section class="card"><h2><span>Packet preview</span><span class="step">02 / COMPRESS</span></h2><div class="report" id="preview">Extract or edit fields, then inspect the compact wire packet.</div><div class="metric"><div><b id="bytes">—</b><small>binary bytes</small></div><div><b id="reduction">—</b><small>size reduction</small></div><div><b id="airtime">—</b><small>estimated airtime</small></div></div><div class="small">Packet hex · application payload</div><pre id="hex">Awaiting packet</pre><p class="small">Airtime uses the configured SF11 / 250 kHz estimate plus 23 B assumed Meshtastic overhead and 16 preamble symbols. It is an estimate, not a measured RF result.</p></section></div><div class="lower"><section class="card"><h2><span>Responder inbox & responsibility</span><span class="step">03 / CONFIRM</span></h2><div id="requests" class="small">No active requests</div></section><section class="card"><h2><span>Network path</span><span class="step">SYNTHETIC</span></h2><div class="network"><div class="node">A</div><div class="link" id="ab"></div><div class="node">B</div><div class="link" id="bc"></div><div class="node">C</div></div><div class="row"><button onclick="partition(true)">Partition B—C</button><button onclick="partition(false)">Restore link</button><span class="small" id="netstate">A ↔ B ↔ C</span></div><div class="row"><button onclick="gatewayStart()">Start USB receive gateway</button><button onclick="gatewayStop()">Stop gateway</button><span class="small" id="gatewaystate">Gateway stopped · no radio TX</span></div><div id="events" style="margin-top:13px"></div><p class="warning">Mesh latency, topology, and delivery are synthetic and reproducible, not a LoRa RF simulation.</p></section></div></main><script>
const equipmentIds={OXYGEN_MASK:0,FIRST_AID_KIT:1,AED:2,STRETCHER:3,OXYGEN:4,WATER:5,RADIO:6,OTHER:7};let extracted=null;function report(){return {schema_id:6,event:'PERSON_FOUND',consciousness:+document.getElementById('consciousness').value,breathing:+document.getElementById('breathing').value,urgency:+document.getElementById('urgency').value,request:+document.getElementById('request').value,equipment_needed:[...document.querySelectorAll('.equip input:checked')].map(x=>equipmentIds[x.value]),location:(document.getElementById('loclat').value!==''&&document.getElementById('loclon').value!==''?[+document.getElementById('loclat').value,+document.getElementById('loclon').value]:null),location_quality:(document.getElementById('loclat').value!==''&&document.getElementById('loclon').value!==''?3:0),person_count:(document.getElementById('peoplecount').value===''?null:+document.getElementById('peoplecount').value),responders_needed_min:(document.getElementById('responders').value===''?null:+document.getElementById('responders').value),source_text:document.getElementById('text').value}}async function extract(){let d=await fetch('/api/extract',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({text:document.getElementById('text').value})}).then(r=>r.json());if(!d.supported){document.getElementById('method').textContent='Unsupported: ordinary text fallback';return}extracted=d.report;for(let k of ['consciousness','breathing','urgency','request']){document.getElementById(k).value=extracted[k];}document.getElementById('loclat').value=extracted.location?.[0]??'';document.getElementById('loclon').value=extracted.location?.[1]??'';document.getElementById('peoplecount').value=extracted.person_count??'';document.getElementById('responders').value=extracted.responders_needed_min??'';document.querySelectorAll('.equip input').forEach(x=>x.checked=extracted.equipment_needed.includes(equipmentIds[x.value]));document.getElementById('method').textContent=d.method+' · '+d.latency_ms.toFixed(1)+' ms';document.getElementById('preview').textContent=d.reconstructed_preview;document.getElementById('bytes').textContent=d.payload_bytes;}
async function send(){let d=await fetch('/api/send',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({source:document.getElementById('src').value,destination:document.getElementById('dst').value,report:report()})}).then(r=>r.json());if(d.detail){alert(d.detail);return}document.getElementById('bytes').textContent=d.payload_bytes;document.getElementById('hex').textContent=d.hex;document.getElementById('airtime').textContent=(d.airtime.airtime_seconds_estimate*1000).toFixed(0)+' ms';document.getElementById('reduction').textContent=(100*(1-d.payload_bytes/Math.max(1,d.original_bytes))).toFixed(0)+'%*';document.getElementById('preview').textContent='Reconstructed structured report: '+d.reconstructed;await refresh()}
async function sendText(){let d=await fetch('/api/text',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({source:document.getElementById('src').value,destination:document.getElementById('dst').value,text:document.getElementById('text').value})}).then(r=>r.json());if(d.detail){alert(d.detail);return}document.getElementById('bytes').textContent=d.payload_bytes;document.getElementById('preview').textContent='Ordinary text message sent.';await refresh()}async function gatewayStart(){let r=await fetch('/api/gateway/start',{method:'POST'});let d=await r.json();document.getElementById('gatewaystate').textContent=r.ok?'USB connected · receive-only · forwarded '+d.valid_reports_forwarded:'Gateway error: '+d.detail;await refresh()}async function gatewayStop(){let r=await fetch('/api/gateway/stop',{method:'POST'});await r.json();document.getElementById('gatewaystate').textContent='Gateway stopped · no radio TX';await refresh()}
async function action(id,verb){await fetch(`/api/requests/${id}/${verb}`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({node:document.getElementById('dst').value})});refresh()}async function refresh(){let s=await fetch('/api/status').then(r=>r.json());if(s.gateway.running)document.getElementById('gatewaystate').textContent='USB connected · receive-only · forwarded '+s.gateway.valid_reports_forwarded;requests.innerHTML=s.requests.length?s.requests.map(r=>`<div class="request"><span class="status ${r.status}">${r.status}</span> · Request ${r.request_id} · ${r.payload_bytes||'—'} B<div>${r.reconstructed||'Waiting for delivery confirmation'}</div>${r.kind!=='text'&&r.status==='delivered'&&!r.receiver_status?`<div class="row"><button onclick="action(${r.request_id},'accept')">Accept responsibility</button></div>`:r.kind!=='text'&&(r.receiver_status==='accepted'||r.status==='accepted')?`<div class="row"><button onclick="action(${r.request_id},'complete')">Mark completed</button></div>`:''}<small class="small">Path: ${(r.path||[]).join(' → ')||'no route'} · Delivery is distinct from acceptance</small></div>`).join(''):'No active requests';events.innerHTML=s.events.slice(-5).reverse().map(e=>`<div class="event">${JSON.stringify(e)}</div>`).join('')}
async function partition(up){await fetch('/api/network/link',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({a:'B',b:'C',up})});document.getElementById('bc').style.background=up?'var(--lime)':'var(--red)';netstate.textContent=up?'A ↔ B ↔ C':'A ↔ B   //   C';}refresh();</script></body></html>'''
