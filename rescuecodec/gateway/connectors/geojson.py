"""GeoJSON export: finds, hazards, maydays and requests as points, tracks as lines. Coordinates are [lon, lat]."""
from __future__ import annotations

from collections.abc import Callable, Iterable
from typing import Any


def _geometry(record: dict[str, Any]) -> dict[str, Any] | None:
    message = record["message"]
    points = (message.get("track") or {}).get("points")
    if points:
        coords = [[p["lon"], p["lat"]] for p in points]
        return {"type": "Point", "coordinates": coords[0]} if len(coords) == 1 else {"type": "LineString", "coordinates": coords}
    pos = message.get("pos")
    if pos:
        return {"type": "Point", "coordinates": [pos["lon"], pos["lat"]]}
    return None


def feature(record: dict[str, Any], state: str | None) -> dict[str, Any] | None:
    """One MessageRecord -> one GeoJSON Feature, or None if it has no position."""
    geometry = _geometry(record)
    if geometry is None:
        return None
    message = record["message"]
    return {"type": "Feature", "geometry": geometry,
            "properties": {"seq": record["seq"], "key": record["key"], "type": record["type"], "from": record["from"],
                           "priority": record["priority"], "state": state,
                           "channel": (record.get("channel") or {}).get("name"), "to": record.get("to"),
                           "acc": (message.get("pos") or {}).get("acc", "gps"),
                           "time": record["event_time"] or record["time"],
                           "text_en": record["text"]["en"], "text_de": record["text"]["de"]}}


def feature_collection(records: Iterable[dict[str, Any]], task_state: Callable[[str | None], str | None]) -> dict[str, Any]:
    """`task_state(key)` gives the state of a task or None. Acks and texts without a position are left out."""
    features = []
    for record in records:
        if record["type"] == "ack":
            continue
        state = task_state(record["task_key"]) if record["type"] in ("found", "request", "hazard", "mayday") else None
        item = feature(record, state)
        if item:
            features.append(item)
    return {"type": "FeatureCollection", "features": features}
