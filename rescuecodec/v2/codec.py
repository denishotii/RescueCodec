"""RescueCodec v2 reference codec: canonical JSON message <-> wire bytes.

The canonical JSON shape is described in spec/SPEC.md ("Canonical JSON").
Example:
    {"id": 7, "pos": {"lat": 50.69212, "lon": 7.21345},
     "found": {"age": "child", "consciousness": "conscious",
               "injuries": ["hypothermia"], "needs": ["stretcher"], "responders": 2}}
"""
from __future__ import annotations

import re
from collections.abc import Mapping
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

from google.protobuf.message import DecodeError

from rescuecodec.v2 import codes as C
from rescuecodec.v2 import rescuecodec_pb2 as pb
from rescuecodec.v2 import textdomain, textpack, textwords


class CodecError(ValueError):
    """Rejected input. `code` is a stable machine-readable string (spec/SPEC.md, "Errors")."""

    def __init__(self, code: str, message: str, field: str | None = None):
        super().__init__(f"{code}: {message}" + (f" ({field})" if field else ""))
        self.code = code
        self.field = field


# ------------------------------------------------------------------ helpers

_UNKNOWN_RE = re.compile(r"^unknown_(\d{1,5})$")
_BIT_RE = re.compile(r"^bit_(\d{1,2})$")
_TIME_RE = re.compile(r"^([01]\d|2[0-3]):([0-5]\d)$")
_NODE_RE = re.compile(r"^!([0-9a-f]{8})$")


def _err(code: str, message: str, field: str | None = None) -> CodecError:
    return CodecError(code, message, field)


def _int(value: Any, field: str, lo: int, hi: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise _err("invalid_value", "must be an integer", field)
    if not lo <= value <= hi:
        raise _err("invalid_value", f"must be {lo}..{hi}", field)
    return value


def _enum_to_wire(value: Any, table: Mapping[str, int], field: str, max_value: int = 0x7FFFFFFF) -> int:
    if isinstance(value, str):
        if value in table:
            return table[value]
        m = _UNKNOWN_RE.match(value)
        if m and 0 < int(m.group(1)) <= max_value and int(m.group(1)) not in table.values():
            return int(m.group(1))
    raise _err("invalid_value", f"unknown value {value!r}", field)


def _enum_to_json(number: int, table: Mapping[str, int]) -> str:
    for name, n in table.items():
        if n == number:
            return name
    return f"unknown_{number}"


def _flags_to_wire(values: Any, table: Mapping[str, int], field: str) -> int:
    if not isinstance(values, list):
        raise _err("invalid_value", "must be a list of names", field)
    mask = 0
    for value in values:
        if isinstance(value, str) and value in table:
            mask |= 1 << table[value]
            continue
        m = _BIT_RE.match(value) if isinstance(value, str) else None
        if m and int(m.group(1)) < 32 and int(m.group(1)) not in table.values():
            mask |= 1 << int(m.group(1))
            continue
        raise _err("invalid_value", f"unknown flag {value!r}", field)
    return mask


def _flags_to_json(mask: int, table: Mapping[str, int]) -> list[str]:
    by_bit = {bit: name for name, bit in table.items()}
    return [by_bit.get(bit, f"bit_{bit}") for bit in range(32) if mask & (1 << bit)]


def deg_to_e5(value: Any, field: str) -> int:
    """Degrees -> integer degrees x 1e5, rounding half away from zero on the decimal text."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise _err("invalid_value", "must be a number", field)
    if value != value or value in (float("inf"), float("-inf")):
        raise _err("invalid_value", "must be a finite number", field)
    return int((Decimal(repr(value)) * 100000).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def e5_to_deg(value: int) -> float:
    return value / 100000


def node_to_json(num: int) -> str:
    return f"!{num:08x}"


def node_from_json(value: Any, field: str = "ack.node") -> int:
    if isinstance(value, str):
        m = _NODE_RE.match(value)
        if m:
            return int(m.group(1), 16)
    raise _err("invalid_value", 'must look like "!a1b2c3d4"', field)


def _check_keys(obj: Any, allowed: set[str], field: str) -> Mapping:
    if not isinstance(obj, Mapping):
        raise _err("invalid_value", "must be an object", field)
    for key in obj:
        if key not in allowed:
            raise _err("unknown_key", f"unknown key {key!r}", f"{field}.{key}" if field else key)
    return obj


def _check_lat_lon(lat: int, lon: int, field: str) -> None:
    if not -9_000_000 <= lat <= 9_000_000:
        raise _err("invalid_value", "latitude must be -90..90", f"{field}.lat")
    if not -18_000_000 <= lon <= 18_000_000:
        raise _err("invalid_value", "longitude must be -180..180", f"{field}.lon")


# ------------------------------------------------------- JSON -> Envelope

def to_proto(msg: Mapping) -> pb.Envelope:
    """Build an Envelope from a canonical JSON message. Raises CodecError."""
    _check_keys(msg, {"v", "id", "priority", "pos", "time", *C.BODY_TYPES}, "")
    if "v" in msg and msg["v"] != 2:
        raise _err("invalid_value", "only v=2 can be encoded", "v")
    bodies = [k for k in C.BODY_TYPES if k in msg]
    if not bodies:
        raise _err("no_body", "a message needs exactly one body (found, track, ...)")
    if len(bodies) > 1:
        raise _err("multiple_bodies", f"only one body allowed, got {bodies}")
    kind = bodies[0]
    env = pb.Envelope()
    if "id" in msg:
        env.id = _int(msg["id"], "id", 0, C.MAX_ID)
    if "priority" in msg:
        env.priority = _enum_to_wire(msg["priority"], C.PRIORITY, "priority")
    if "pos" in msg:
        pos = _check_keys(msg["pos"], {"lat", "lon", "acc"}, "pos")
        if "lat" not in pos or "lon" not in pos:
            raise _err("missing_field", "pos needs lat and lon", "pos")
        lat, lon = deg_to_e5(pos["lat"], "pos.lat"), deg_to_e5(pos["lon"], "pos.lon")
        _check_lat_lon(lat, lon, "pos")
        env.pos.lat, env.pos.lon = lat, lon
        if "acc" in pos:
            env.pos.acc = _enum_to_wire(pos["acc"], C.ACCURACY, "pos.acc")
        env.pos.SetInParent()
    if "time" in msg:
        t = msg["time"]
        m = _TIME_RE.match(t) if isinstance(t, str) else None
        if not m:
            raise _err("invalid_value", 'must be "HH:MM" (UTC)', "time")
        env.time = int(m.group(1)) * 60 + int(m.group(2))
    body = msg[kind]
    builder = _BUILDERS[kind]
    builder(env, body)
    validate(env)
    return env


def _build_found(env: pb.Envelope, body: Any) -> None:
    b = _check_keys(body, {"count", "age", "consciousness", "breathing", "mobility", "injuries", "needs",
                           "responders"}, "found")
    f = env.found
    f.SetInParent()
    state = 0
    for name, shift, width, table in C.STATE_LAYOUT:
        if name in b:
            state |= _enum_to_wire(b[name], table, f"found.{name}", (1 << width) - 1) << shift
    f.state = state
    if "injuries" in b:
        f.injuries = _flags_to_wire(b["injuries"], C.INJURIES, "found.injuries")
    if "needs" in b:
        f.needs = _flags_to_wire(b["needs"], C.NEEDS, "found.needs")
    if "count" in b:
        f.count = _int(b["count"], "found.count", 0, C.MAX_COUNT)
    if "responders" in b:
        f.responders = _int(b["responders"], "found.responders", 0, C.MAX_COUNT)


def _build_track(env: pb.Envelope, body: Any) -> None:
    b = _check_keys(body, {"points"}, "track")
    points = b.get("points")
    if not isinstance(points, list) or not points:
        raise _err("missing_field", "a track needs 1 or more points", "track.points")
    if len(points) > C.MAX_TRACK_POINTS:
        raise _err("invalid_value", f"at most {C.MAX_TRACK_POINTS} points per packet", "track.points")
    t = env.track
    t.SetInParent()
    with_time = None
    prev_lat = prev_lon = prev_s = 0
    for i, p in enumerate(points):
        field = f"track.points[{i}]"
        _check_keys(p, {"lat", "lon", "s"}, field)
        if "lat" not in p or "lon" not in p:
            raise _err("missing_field", "a point needs lat and lon", field)
        lat, lon = deg_to_e5(p["lat"], f"{field}.lat"), deg_to_e5(p["lon"], f"{field}.lon")
        _check_lat_lon(lat, lon, field)
        t.lat.append(lat - prev_lat)
        t.lon.append(lon - prev_lon)
        prev_lat, prev_lon = lat, lon
        has_s = "s" in p
        if with_time is None:
            with_time = has_s
        elif has_s != with_time:
            raise _err("invalid_value", "give s on every point or on none", "track.points")
        if has_s:
            s = _int(p["s"], f"{field}.s", 0, 86_400)
            if s < prev_s:
                raise _err("invalid_value", "s must not go down", f"{field}.s")
            t.dt.append(s - prev_s)
            prev_s = s


def _build_sector(env: pb.Envelope, body: Any) -> None:
    b = _check_keys(body, {"sector", "status", "coverage"}, "sector")
    s = env.sector
    s.SetInParent()
    if "sector" in b:
        s.sector = _int(b["sector"], "sector.sector", 0, C.MAX_SECTOR)
    if "status" in b:
        s.status = _enum_to_wire(b["status"], C.SECTOR_STATUS, "sector.status")
    if "coverage" in b:
        s.coverage = _int(b["coverage"], "sector.coverage", 0, 100)


def _build_request(env: pb.Envelope, body: Any) -> None:
    b = _check_keys(body, {"resource", "count", "urgency"}, "request")
    r = env.request
    r.SetInParent()
    if "resource" in b:
        r.resource = _enum_to_wire(b["resource"], C.RESOURCE, "request.resource")
    if "count" in b:
        r.count = _int(b["count"], "request.count", 0, C.MAX_COUNT)
    if "urgency" in b:
        r.urgency = _enum_to_wire(b["urgency"], C.URGENCY, "request.urgency")


def _build_hazard(env: pb.Envelope, body: Any) -> None:
    b = _check_keys(body, {"kind", "severity", "radius"}, "hazard")
    h = env.hazard
    h.SetInParent()
    if "kind" in b:
        h.kind = _enum_to_wire(b["kind"], C.HAZARD, "hazard.kind")
    if "severity" in b:
        h.severity = _enum_to_wire(b["severity"], C.SEVERITY, "hazard.severity")
    if "radius" in b:
        h.radius = _int(b["radius"], "hazard.radius", 0, C.MAX_RADIUS_M)


def _build_mayday(env: pb.Envelope, body: Any) -> None:
    b = _check_keys(body, {"reason", "persons"}, "mayday")
    m = env.mayday
    m.SetInParent()
    if "reason" in b:
        m.reason = _enum_to_wire(b["reason"], C.MAYDAY_REASON, "mayday.reason")
    if "persons" in b:
        m.persons = _int(b["persons"], "mayday.persons", 0, C.MAX_COUNT)


def _build_ack(env: pb.Envelope, body: Any) -> None:
    b = _check_keys(body, {"ref", "node", "status", "eta", "team"}, "ack")
    a = env.ack
    a.SetInParent()
    if "ref" in b:
        a.ref = _int(b["ref"], "ack.ref", 0, C.MAX_ID)
    if "node" in b:
        a.node = node_from_json(b["node"])
    if "status" in b:
        a.status = _enum_to_wire(b["status"], C.ACK_STATUS, "ack.status")
    if "eta" in b:
        a.eta = _int(b["eta"], "ack.eta", 0, C.MAX_ETA_MIN)
    if "team" in b:
        if not isinstance(b["team"], str):
            raise _err("invalid_value", "must be a string", "ack.team")
        a.team = b["team"]


def _build_text(env: pb.Envelope, body: Any) -> None:
    b = _check_keys(body, {"text", "dict", "words", "vocab"}, "text")
    if "vocab" in b:            # a domain frame (0x03, SPEC 3.8) has no Envelope form: encode_domain builds it
        raise _err("invalid_value", "a text with a team vocabulary goes in a domain frame (encode_domain), "
                   "not in an Envelope", "text.vocab")
    t = env.text
    t.SetInParent()
    if "dict" in b and _int(b["dict"], "text.dict", 1, 0xFFFFFFFF) not in textpack.DICTS:
        raise _err("invalid_value", f"unknown dictionary, known: {sorted(textpack.DICTS)}", "text.dict")
    if "words" in b:
        if "dict" in b:
            raise _err("invalid_value", "give dict or words, not both", "text.words")
        version = _int(b["words"], "text.words", 1, 0xFFFFFFFF)
        if version not in textwords.DICTS or not textwords.available(version):
            raise _err("invalid_value", f"unknown word dictionary, known: {sorted(textwords.DICTS)}", "text.words")
        if "text" in b:
            if not isinstance(b["text"], str):
                raise _err("invalid_value", "must be a string", "text.text")
            try:
                raw = b["text"].encode("utf-8")
            except UnicodeEncodeError as exc:
                raise _err("invalid_value", "must be valid Unicode", "text.text") from exc
            if raw:
                if len(raw) > textwords.MAX_TEXT_BYTES:
                    raise _err("invalid_value", f"a compact text is at most {textwords.MAX_TEXT_BYTES} bytes", "text.text")
                t.words = textwords.encode_words(b["text"], version)
                t.words_dict = version
        return
    if "text" in b:
        if not isinstance(b["text"], str):
            raise _err("invalid_value", "must be a string", "text.text")
        try:
            raw = b["text"].encode("utf-8")
        except UnicodeEncodeError as exc:
            raise _err("invalid_value", "must be valid Unicode", "text.text") from exc
        if "dict" not in b:
            t.text = b["text"]
        elif raw:
            if len(raw) > textpack.MAX_UNPACKED_BYTES:
                raise _err("invalid_value", f"a packed text is at most {textpack.MAX_UNPACKED_BYTES} bytes", "text.text")
            t.deflate = textpack.pack(b["text"], b["dict"])
            t.dict = b["dict"]


_BUILDERS = {"found": _build_found, "track": _build_track, "sector": _build_sector, "request": _build_request,
             "hazard": _build_hazard, "mayday": _build_mayday, "ack": _build_ack, "text": _build_text}


# -------------------------------------------------- validation (both ways)

def body_type(env: pb.Envelope) -> str:
    """Return the body type name, or "unknown" for a body from a newer spec version. A text packed with a dictionary
    this library does not know is "unknown" too: it can only be read after an update."""
    kind = env.WhichOneof("body") or "unknown"
    if kind == "text" and env.text.deflate and env.text.dict and env.text.dict not in textpack.DICTS:
        return "unknown"
    if kind == "text" and _words_unknown(env.text):
        return "unknown"
    return kind


def _words_unknown(t: pb.Text) -> bool:
    """A compact text whose word dictionary this library does not have (or whose copy fails its SHA-256 check)."""
    return bool(t.words) and t.words_dict != 0 and not t.text and not t.deflate and not textwords.available(t.words_dict)


def unpacked_text(t: pb.Text) -> str:
    """The words of a Text body: `text`, `deflate` unpacked or `words` decoded. Raises CodecError."""
    if t.words:
        try:
            return textwords.decode_words(t.words, t.words_dict)
        except textwords.WordsError as exc:
            raise _err(exc.code, str(exc), "text.words") from exc
    if not t.deflate:
        return t.text
    try:
        return textpack.unpack(t.deflate, t.dict)
    except textpack.PackError as exc:
        raise _err(exc.code, str(exc), "text.deflate") from exc


def validate(env: pb.Envelope) -> None:
    """Value rules shared by encoder and decoder (spec/SPEC.md, "Validation")."""
    kind = body_type(env)
    if env.id > C.MAX_ID:
        raise _err("invalid_value", f"id must be 0..{C.MAX_ID}", "id")
    if kind in C.ID_REQUIRED and env.id == 0:
        raise _err("missing_field", f"{kind} needs an id (1..{C.MAX_ID})", "id")
    if env.HasField("pos"):
        _check_lat_lon(env.pos.lat, env.pos.lon, "pos")
    if env.HasField("time") and env.time > 1439:
        raise _err("invalid_value", "time must be 0..1439 (minute of the UTC day)", "time")
    if kind == "found":
        f = env.found
        if f.count > C.MAX_COUNT:
            raise _err("invalid_value", f"must be 0..{C.MAX_COUNT}", "found.count")
        if f.responders > C.MAX_COUNT:
            raise _err("invalid_value", f"must be 0..{C.MAX_COUNT}", "found.responders")
    elif kind == "track":
        t = env.track
        n = len(t.lat)
        if n == 0:
            raise _err("missing_field", "a track needs 1 or more points", "track.points")
        if n > C.MAX_TRACK_POINTS:
            raise _err("invalid_value", f"at most {C.MAX_TRACK_POINTS} points per packet", "track.points")
        if len(t.lon) != n or len(t.dt) not in (0, n):
            raise _err("invalid_value", "lat, lon and dt must have the same length (dt may be empty)",
                       "track.points")
        if t.dt and not env.HasField("time"):
            raise _err("missing_field", "a track with times needs Envelope.time", "time")
        lat = lon = 0
        for i in range(n):
            lat += t.lat[i]
            lon += t.lon[i]
            _check_lat_lon(lat, lon, f"track.points[{i}]")
    elif kind == "sector":
        s = env.sector
        if s.sector == 0 or s.sector > C.MAX_SECTOR:
            raise _err("missing_field" if s.sector == 0 else "invalid_value",
                       f"sector must be 1..{C.MAX_SECTOR}", "sector.sector")
        if s.status == 0:
            raise _err("missing_field", "sector needs a status", "sector.status")
        if s.coverage > 100:
            raise _err("invalid_value", "coverage must be 0..100", "sector.coverage")
    elif kind == "request":
        r = env.request
        if r.resource == 0:
            raise _err("missing_field", "request needs a resource", "request.resource")
        if r.count > C.MAX_COUNT:
            raise _err("invalid_value", f"must be 0..{C.MAX_COUNT}", "request.count")
    elif kind == "hazard":
        h = env.hazard
        if h.kind == 0:
            raise _err("missing_field", "hazard needs a kind", "hazard.kind")
        if h.radius > C.MAX_RADIUS_M:
            raise _err("invalid_value", f"radius must be 0..{C.MAX_RADIUS_M}", "hazard.radius")
    elif kind == "mayday":
        if env.mayday.persons > C.MAX_COUNT:
            raise _err("invalid_value", f"must be 0..{C.MAX_COUNT}", "mayday.persons")
    elif kind == "ack":
        a = env.ack
        if a.ref == 0 or a.ref > C.MAX_ID:
            raise _err("missing_field" if a.ref == 0 else "invalid_value", f"ref must be 1..{C.MAX_ID}",
                       "ack.ref")
        if a.node == 0:
            raise _err("missing_field", "ack needs the node of the answered message", "ack.node")
        if a.status == 0:
            raise _err("missing_field", "ack needs a status", "ack.status")
        if a.eta > C.MAX_ETA_MIN:
            raise _err("invalid_value", f"eta must be 0..{C.MAX_ETA_MIN} minutes", "ack.eta")
        if len(a.team.encode("utf-8")) > C.MAX_TEAM_BYTES:
            raise _err("invalid_value", f"team label is at most {C.MAX_TEAM_BYTES} bytes", "ack.team")
    elif kind == "text":
        t = env.text
        if t.words:
            if t.text or t.deflate:
                raise _err("invalid_value", "give one of text, deflate and words", "text.words")
            if t.dict:
                raise _err("invalid_value", "dict is only allowed with deflate", "text.dict")
            if t.words_dict == 0:
                raise _err("missing_field", "a compact text needs its word dictionary number", "text.words_dict")
            if not unpacked_text(t):
                raise _err("missing_field", "text must not be empty", "text.text")
            return
        if t.words_dict:
            raise _err("invalid_value", "words_dict is only allowed with words", "text.words_dict")
        if t.deflate:
            if t.text:
                raise _err("invalid_value", "give text or deflate, not both", "text.deflate")
            if t.dict == 0:
                raise _err("missing_field", "a packed text needs its dictionary number", "text.dict")
            if not unpacked_text(t):
                raise _err("missing_field", "text must not be empty", "text.text")
            return
        if t.dict:
            raise _err("invalid_value", "dict is only allowed with deflate", "text.dict")
        size = len(t.text.encode("utf-8"))
        if size == 0:
            raise _err("missing_field", "text must not be empty", "text.text")
        if size > C.MAX_TEXT_BYTES:
            raise _err("invalid_value", f"text is at most {C.MAX_TEXT_BYTES} bytes", "text.text")


# ------------------------------------------------------- Envelope -> JSON

def from_proto(env: pb.Envelope) -> dict:
    """Envelope -> canonical JSON message (no "v" key). Call validate() first."""
    out: dict[str, Any] = {}
    if env.id:
        out["id"] = env.id
    if env.priority:
        out["priority"] = _enum_to_json(env.priority, C.PRIORITY)
    if env.HasField("pos"):
        pos: dict[str, Any] = {"lat": e5_to_deg(env.pos.lat), "lon": e5_to_deg(env.pos.lon)}
        if env.pos.acc:
            pos["acc"] = _enum_to_json(env.pos.acc, C.ACCURACY)
        out["pos"] = pos
    if env.HasField("time"):
        out["time"] = f"{env.time // 60:02d}:{env.time % 60:02d}"
    kind = body_type(env)
    if kind == "found":
        f = env.found
        b: dict[str, Any] = {}
        if f.count:
            b["count"] = f.count
        for name, shift, width, table in C.STATE_LAYOUT:
            value = (f.state >> shift) & ((1 << width) - 1)
            if value:
                b[name] = _enum_to_json(value, table)
        if f.injuries:
            b["injuries"] = _flags_to_json(f.injuries, C.INJURIES)
        if f.needs:
            b["needs"] = _flags_to_json(f.needs, C.NEEDS)
        if f.responders:
            b["responders"] = f.responders
        out["found"] = b
    elif kind == "track":
        t = env.track
        points = []
        lat = lon = s = 0
        for i in range(len(t.lat)):
            lat += t.lat[i]
            lon += t.lon[i]
            p: dict[str, Any] = {"lat": e5_to_deg(lat), "lon": e5_to_deg(lon)}
            if t.dt:
                s += t.dt[i]
                p["s"] = s
            points.append(p)
        out["track"] = {"points": points}
    elif kind == "sector":
        s_ = env.sector
        b = {"sector": s_.sector, "status": _enum_to_json(s_.status, C.SECTOR_STATUS)}
        if s_.coverage:
            b["coverage"] = s_.coverage
        out["sector"] = b
    elif kind == "request":
        r = env.request
        b = {"resource": _enum_to_json(r.resource, C.RESOURCE)}
        if r.count:
            b["count"] = r.count
        if r.urgency:
            b["urgency"] = _enum_to_json(r.urgency, C.URGENCY)
        out["request"] = b
    elif kind == "hazard":
        h = env.hazard
        b = {"kind": _enum_to_json(h.kind, C.HAZARD)}
        if h.severity:
            b["severity"] = _enum_to_json(h.severity, C.SEVERITY)
        if h.radius:
            b["radius"] = h.radius
        out["hazard"] = b
    elif kind == "mayday":
        m = env.mayday
        b = {}
        if m.reason:
            b["reason"] = _enum_to_json(m.reason, C.MAYDAY_REASON)
        if m.persons:
            b["persons"] = m.persons
        out["mayday"] = b
    elif kind == "ack":
        a = env.ack
        b = {"ref": a.ref, "node": node_to_json(a.node), "status": _enum_to_json(a.status, C.ACK_STATUS)}
        if a.eta:
            b["eta"] = a.eta
        if a.team:
            b["team"] = a.team
        out["ack"] = b
    elif kind == "text":
        t = env.text
        if t.words:
            out["text"] = {"text": unpacked_text(t), "words": t.words_dict}
        elif t.deflate:
            out["text"] = {"text": unpacked_text(t), "dict": t.dict}
        else:
            out["text"] = {"text": t.text}
    elif env.WhichOneof("body") == "text" and _words_unknown(env.text):
        out["unknown"] = {"words": env.text.words_dict}     # shown as "dictionary this app does not have"
    else:
        out["unknown"] = {}
    return out


# ------------------------------------------------------------ public API

def encode(msg: Mapping) -> bytes:
    """Canonical JSON message -> v2 wire bytes. Raises CodecError."""
    env = to_proto(msg)
    data = env.SerializeToString(deterministic=True)
    if len(data) > C.MAX_ENCODE_BYTES:
        raise _err("too_large", f"{len(data)} bytes, the limit is {C.MAX_ENCODE_BYTES}")
    return data


#: First byte of a compact frame (SPEC section 3.7): 0x02, the word dictionary version, the word stream (3.6).
COMPACT_FRAME = 0x02


def is_compact_frame(data: bytes) -> bool:
    """True when the payload is a compact frame (first byte 0x02), not an Envelope."""
    return bool(data) and data[0] == COMPACT_FRAME


def encode_compact(text: str, version: int = textwords.CURRENT_DICT) -> bytes:
    """Text -> compact frame: 0x02, 1 byte word dictionary version, the word stream. No id, no position, no time.
    Raises CodecError (missing_field for an empty text, invalid_value for an unknown dictionary, too_large)."""
    if not isinstance(text, str):
        raise _err("invalid_value", "must be a string", "text.text")
    if not text:
        raise _err("missing_field", "a compact frame needs a text", "text.text")
    if not isinstance(version, int) or not 1 <= version <= 255 or not textwords.available(version):
        raise _err("invalid_value", f"unknown word dictionary, known: {sorted(textwords.DICTS)}", "text.words")
    try:
        stream = textwords.encode_words(text, version)
    except textwords.WordsError as exc:
        raise _err(exc.code, str(exc), "text.text") from exc
    data = bytes((COMPACT_FRAME, version)) + stream
    if len(data) > C.MAX_ENCODE_BYTES:
        raise _err("too_large", f"{len(data)} bytes, the limit is {C.MAX_ENCODE_BYTES}")
    return data


def decode_compact(data: bytes) -> dict:
    """Compact frame -> canonical JSON {"text": {"text": ..., "words": n}} (no id). A dictionary this library does not
    have (or whose copy fails the SHA-256 check) gives {"unknown": {"words": n}}. Raises CodecError."""
    data = bytes(data)
    if not data or data[0] != COMPACT_FRAME:
        raise _err("unsupported_version", "not a compact frame")
    if len(data) > C.MAX_DECODE_BYTES:
        raise _err("too_large", f"{len(data)} bytes, the limit is {C.MAX_DECODE_BYTES}")
    if len(data) < 2:
        raise _err("malformed", "the compact frame is cut off (no dictionary version)", "text.words_dict")
    version = data[1]
    if version == 0:
        raise _err("malformed", "word dictionary 0 does not exist", "text.words_dict")
    if not textwords.available(version):
        return {"unknown": {"words": version}}          # shown as "dictionary this app does not have"
    try:
        text = textwords.decode_words(data[2:], version)
    except textwords.WordsError as exc:
        raise _err(exc.code, str(exc), "text.words") from exc
    return {"text": {"text": text, "words": version}}


#: First byte of a domain frame (SPEC section 3.8): 0x03, the vocabulary id, the bit stream of N-bit codes.
DOMAIN_FRAME = 0x03


def is_domain_frame(data: bytes) -> bool:
    """True when the payload is a domain frame (first byte 0x03), not an Envelope."""
    return bool(data) and data[0] == DOMAIN_FRAME


def encode_domain(text: str, vocab: int | str) -> bytes:
    """Text -> domain frame: 0x03, 1 byte vocabulary id, the bit stream. `vocab` is an id (1..5) or a domain name
    ("emergency", "sar", "fire", "hiking", "medical"). No id, no position, no time. Raises CodecError (missing_field
    for an empty text, invalid_value for an unknown vocabulary, too_large)."""
    if not isinstance(text, str):
        raise _err("invalid_value", "must be a string", "text.text")
    if not text:
        raise _err("missing_field", "a domain frame needs a text", "text.text")
    try:
        vid = textdomain.vocab_id(vocab)
    except KeyError:
        vid = None
    if vid is None or not textdomain.available(vid):
        known = ", ".join(f"{v.id} {v.domain}" for v in textdomain.VOCABS.values())
        raise _err("invalid_value", f"unknown team vocabulary, known: {known}", "text.vocab")
    try:
        stream = textdomain.encode_stream(text, vid)
    except UnicodeEncodeError as exc:
        raise _err("invalid_value", "must be valid Unicode", "text.text") from exc
    except textwords.WordsError as exc:
        raise _err(exc.code, str(exc), "text.text") from exc
    data = bytes((DOMAIN_FRAME, vid)) + stream
    if len(data) > C.MAX_ENCODE_BYTES:
        raise _err("too_large", f"{len(data)} bytes, the limit is {C.MAX_ENCODE_BYTES}")
    return data


def decode_domain(data: bytes) -> dict:
    """Domain frame -> canonical JSON {"text": {"text": ..., "vocab": id}} (no id). A vocabulary this library does not
    have (ids 0 and 6..255 today, or a copy that fails the SHA-256 check) gives {"unknown": {"vocab": id}}, shown as
    "unsupported vocabulary". Raises CodecError (malformed, too_large)."""
    data = bytes(data)
    if not data or data[0] != DOMAIN_FRAME:
        raise _err("unsupported_version", "not a domain frame")
    if len(data) > C.MAX_DECODE_BYTES:
        raise _err("too_large", f"{len(data)} bytes, the limit is {C.MAX_DECODE_BYTES}")
    if len(data) < 2:
        raise _err("malformed", "the domain frame is cut off (no vocabulary id)", "text.vocab")
    vid = data[1]
    if not textdomain.available(vid):
        return {"unknown": {"vocab": vid}}            # shown as "unsupported vocabulary", never guessed words
    try:
        text = textdomain.decode_stream(data[2:], vid)
    except textwords.WordsError as exc:
        raise _err(exc.code, str(exc), "text.vocab") from exc
    return {"text": {"text": text, "vocab": vid}}


def detect_version(data: bytes) -> int:
    """1 for a v1 packet (first byte 0x01), 2 for v2, 0 for reserved/unknown versions or empty input."""
    if not data:
        return 0
    first = data[0]
    if first == 0x01:
        return 1
    if first <= 0x07:
        return 0
    return 2


def parse(data: bytes) -> pb.Envelope:
    """v2 wire bytes -> validated Envelope. Raises CodecError."""
    if not isinstance(data, (bytes, bytearray)):
        raise _err("invalid_value", "payload must be bytes")
    data = bytes(data)
    if not data:
        raise _err("empty", "empty payload")
    if len(data) > C.MAX_DECODE_BYTES:
        raise _err("too_large", f"{len(data)} bytes, the limit is {C.MAX_DECODE_BYTES}")
    version = detect_version(data)
    if version != 2:
        raise _err("unsupported_version", f"first byte 0x{data[0]:02x} is not a v2 packet")
    env = pb.Envelope()
    try:
        env.ParseFromString(data)
    except DecodeError as exc:
        raise _err("malformed", f"not a valid protobuf Envelope: {exc}") from exc
    if env.WhichOneof("body") is None:
        known = pb.Envelope()
        known.CopyFrom(env)
        known.DiscardUnknownFields()
        if len(known.SerializeToString()) == len(env.SerializeToString()):
            raise _err("no_body", "the Envelope has no body")
    validate(env)
    return env


def decode(data: bytes) -> dict:
    """v2 wire bytes -> canonical JSON message. Raises CodecError."""
    return from_proto(parse(data))


def message_type(msg: Mapping) -> str:
    for kind in (*C.BODY_TYPES, "unknown"):
        if kind in msg:
            return kind
    raise _err("no_body", "the message has no body")


def effective_priority(msg: Mapping) -> str:
    """Priority on the wire, or the default for the message type."""
    return msg.get("priority") or C.DEFAULT_PRIORITY[message_type(msg)]


def resolve_time(hhmm: str, now: "datetime") -> "datetime":
    """Turn a message time "HH:MM" (UTC) into a full UTC datetime.

    Rule (spec/SPEC.md, "Time"): the most recent matching minute that is at most
    10 minutes after `now` (the receiver's clock, timezone-aware UTC).
    """
    from datetime import timedelta, timezone

    m = _TIME_RE.match(hhmm)
    if not m:
        raise _err("invalid_value", 'must be "HH:MM" (UTC)', "time")
    now = now.astimezone(timezone.utc)
    candidate = now.replace(hour=int(m.group(1)), minute=int(m.group(2)), second=0, microsecond=0)
    if candidate > now + timedelta(minutes=10):
        candidate -= timedelta(days=1)
    return candidate


def canonical(msg: Mapping) -> dict:
    """Normalise a JSON message (e.g. flag order, rounding) by encoding and decoding it."""
    return decode(encode(msg))
