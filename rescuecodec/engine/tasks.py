"""Tasks and Accept (spec section 15): every found, request, hazard and mayday is a task. Licence: Apache-2.0.

Key: "<sender node hex>-<id>", for example "a1b2c3d4-7". States:
  open      nobody answered yet
  accepted  a team took it
  declined  a team cannot take it (another team may still accept)
  done      finished. Final: nothing changes it any more.
  unknown   an Ack arrived for a task we never received (or have not received yet)

Moves you make yourself (`transition`): open -> accepted | declined; accepted -> done | declined;
declined -> accepted; the same state again is allowed (it re-sends the Ack). Everything else raises InvalidTransition
(the gateway answers 409). Acks that arrive from the radio (`on_ack`) are applied as they come, newest wins,
except that done stays done and a late Ack for a missing task makes an `unknown` placeholder.
"""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from rescuecodec.engine.sendqueue import TYPE_RANK
from rescuecodec.v2 import codes as C
from rescuecodec.v2.codec import effective_priority, message_type, node_from_json, node_to_json

TASK_TYPES = ("found", "request", "hazard", "mayday")
STATES = ("open", "accepted", "declined", "done", "unknown")
ALLOWED = {"open": {"accepted", "declined"}, "accepted": {"done", "declined"}, "declined": {"accepted"}}


class InvalidTransition(ValueError):
    code = "invalid_transition"


def _iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def task_key(node: int | str, msg_id: int) -> str:
    num = node_from_json(node) if isinstance(node, str) else int(node)
    return f"{num:08x}-{int(msg_id)}"


@dataclass
class Task:
    key: str
    sender: str                       # "!a1b2c3d4"
    id: int
    type: str | None                  # None for an unknown placeholder
    priority: str | None
    state: str = "open"
    created: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    message: dict | None = None
    acks: list[dict] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        out = {"key": self.key, "from": self.sender, "id": self.id, "type": self.type, "priority": self.priority,
               "state": self.state, "created": _iso(self.created), "updated": _iso(self.updated),
               "acks": [dict(a, time=_iso(a["time"])) for a in self.acks]}
        if self.message and "pos" in self.message:
            out["pos"] = self.message["pos"]
        if self.message is not None:
            out["message"] = self.message
        return out


class TaskBoard:
    def __init__(self) -> None:
        self._tasks: dict[str, Task] = {}

    def __len__(self) -> int:
        return len(self._tasks)

    def get(self, key: str) -> Task | None:
        return self._tasks.get(key)

    def list(self, states: set[str] | list[str] | None = None) -> list[Task]:
        """Tasks in the order a command post wants them: Mayday first, then type rank and priority, open before
        answered, newest first."""
        rows = [t for t in self._tasks.values() if not states or t.state in states]
        def order(t: Task):
            return (TYPE_RANK.get(t.type or "unknown", 5), -C.PRIORITY.get(t.priority or "normal", 2),
                    0 if t.state in ("open", "unknown") else 1, -t.created.timestamp())
        return sorted(rows, key=order)

    # --- receiving
    def on_message(self, sender: int | str, msg: Mapping, now: datetime | None = None) -> Task | None:
        """Register a received (or own sent) message. Acks update their task. Returns the task or None."""
        now = now or datetime.now(timezone.utc)
        kind = message_type(msg)
        if kind == "ack":
            return self.on_ack(sender, msg, now)
        if kind not in TASK_TYPES or not msg.get("id"):
            return None
        num = node_from_json(sender) if isinstance(sender, str) else int(sender)
        key = task_key(num, msg["id"])
        task = self._tasks.get(key)
        if task is None:
            task = Task(key, node_to_json(num), int(msg["id"]), kind, effective_priority(msg), "open", now, now, dict(msg))
            self._tasks[key] = task
        elif task.type is None:      # the Ack came first: fill in the placeholder
            task.type, task.priority, task.message, task.created = kind, effective_priority(msg), dict(msg), now
            task.state = self._state_from_acks(task) if task.acks else "open"
            task.updated = now
        return task

    @staticmethod
    def _state_from_acks(task: Task) -> str:
        if any(a["status"] == "done" for a in task.acks):
            return "done"
        return task.acks[-1]["status"] if task.acks else "open"

    def on_ack(self, sender: int | str, msg: Mapping, now: datetime | None = None) -> Task | None:
        now = now or datetime.now(timezone.utc)
        ack = msg.get("ack")
        if not ack or not ack.get("ref") or not ack.get("node"):
            return None
        key = task_key(ack["node"], ack["ref"])
        task = self._tasks.get(key)
        if task is None:
            task = Task(key, ack["node"], int(ack["ref"]), None, None, "unknown", now, now)
            self._tasks[key] = task
        by = node_to_json(node_from_json(sender) if isinstance(sender, str) else int(sender))
        entry = {"by": by, "status": ack["status"], "eta": ack.get("eta"), "team": ack.get("team"), "time": now}
        task.acks.append({k: v for k, v in entry.items() if v is not None})
        if task.state != "done" and task.type is not None:
            task.state = ack["status"]
        task.updated = now
        return task

    # --- own moves
    def transition(self, key: str, status: str, *, eta: int = 0, team: str = "", now: datetime | None = None) -> dict:
        """Accept, decline or finish a task yourself. Returns the Ack message to send.
        Raises KeyError for an unknown key and InvalidTransition for a move that is not allowed."""
        now = now or datetime.now(timezone.utc)
        task = self._tasks[key]
        if status not in C.ACK_STATUS:
            raise InvalidTransition(f"unknown status {status!r}")
        if task.type is None:
            raise InvalidTransition("the task itself has not been received yet")
        if status != task.state and status not in ALLOWED.get(task.state, set()):
            raise InvalidTransition(f"task is {task.state}; cannot change to {status}")
        body: dict[str, Any] = {"ref": task.id, "node": task.sender, "status": status}
        if eta:
            body["eta"] = eta
        if team:
            body["team"] = team
        task.state = status
        task.updated = now
        return {"ack": body}
