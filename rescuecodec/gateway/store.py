"""SQLite storage for message records (one row per record, the full JSON in `doc`)."""
from __future__ import annotations

import json
import sqlite3
import threading
from collections.abc import Iterable
from datetime import datetime, timezone
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS messages (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    key TEXT,
    direction TEXT NOT NULL,
    type TEXT NOT NULL,
    status TEXT NOT NULL,
    time TEXT NOT NULL,
    doc TEXT NOT NULL,
    channel TEXT
);
CREATE INDEX IF NOT EXISTS messages_type ON messages (type);
CREATE INDEX IF NOT EXISTS messages_key ON messages (key);
CREATE TABLE IF NOT EXISTS meta (name TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


def channel_name(record: dict[str, Any]) -> str | None:
    channel = record.get("channel")
    return channel.get("name") if isinstance(channel, dict) else None


def iso_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Store:
    """Thread-safe wrapper around one SQLite connection. `path` may be ":memory:"."""

    def __init__(self, path: str = ":memory:"):
        self.path = path
        self._lock = threading.RLock()
        self._db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        if path != ":memory:":
            self._db.execute("PRAGMA journal_mode=WAL")
            self._db.execute("PRAGMA synchronous=NORMAL")
        self._db.executescript(SCHEMA)
        self._migrate()

    def _migrate(self) -> None:
        """Databases from before channels have no `channel` column: add it. The records get their channel in
        backfill_channels(), which needs the gateway's default channel name."""
        with self._lock:
            columns = {row["name"] for row in self._db.execute("PRAGMA table_info(messages)")}
            if "channel" not in columns:
                self._db.execute("ALTER TABLE messages ADD COLUMN channel TEXT")
            self._db.execute("CREATE INDEX IF NOT EXISTS messages_channel ON messages (channel)")

    def backfill_channels(self, default_name: str, default_index: int | None = None) -> int:
        """Give records stored before channels existed their `channel` and `to`. The gateway then listened on one
        private channel only, so they belong to the default channel. Its index is the channel most received records
        came in on (or `default_index`). A received record on another index was a direct message: it keeps that index.
        Returns the number of records changed."""
        with self._lock:
            rows = [(r["seq"], json.loads(r["doc"])) for r in self._db.execute("SELECT seq, doc FROM messages WHERE channel IS NULL")]
            if not rows:
                return 0
            if default_index is None:
                heard = [((doc.get("radio") or {}).get("channel")) for _, doc in rows if doc.get("direction") == "in"]
                heard = [i for i in heard if isinstance(i, int)]
                default_index = max(set(heard), key=heard.count) if heard else 0
            for seq, doc in rows:
                index = (doc.get("radio") or {}).get("channel")
                index = index if isinstance(index, int) and doc.get("direction") == "in" else default_index
                name = default_name if index == default_index else f"Channel {index}"
                doc.setdefault("channel", {"index": index, "name": name, "public": False})
                doc.setdefault("to", None)
                self._db.execute("UPDATE messages SET doc=?, channel=? WHERE seq=?",
                                 (json.dumps(doc, ensure_ascii=False), channel_name(doc) or default_name, seq))
            return len(rows)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ------------------------------------------------------------ records
    def insert(self, record: dict[str, Any]) -> dict[str, Any]:
        """Store a record without `seq`. Returns the record with its new `seq`."""
        with self._lock:
            cur = self._db.execute(
                "INSERT INTO messages (key, direction, type, status, time, doc, channel) VALUES (?,?,?,?,?,?,?)",
                (record.get("key"), record["direction"], record["type"], record["status"], record["time"], "{}",
                 channel_name(record)))
            record = {"seq": cur.lastrowid, **record}
            self._db.execute("UPDATE messages SET doc=? WHERE seq=?", (json.dumps(record, ensure_ascii=False), record["seq"]))
            return record

    def get(self, seq: int) -> dict[str, Any] | None:
        with self._lock:
            row = self._db.execute("SELECT doc FROM messages WHERE seq=?", (seq,)).fetchone()
        return json.loads(row["doc"]) if row else None

    def update_status(self, seq: int, status: str, error: str | None = None) -> dict[str, Any] | None:
        with self._lock:
            record = self.get(seq)
            if record is None:
                return None
            record["status"], record["error"] = status, error
            self._db.execute("UPDATE messages SET status=?, doc=? WHERE seq=?",
                             (status, json.dumps(record, ensure_ascii=False), seq))
            return record

    def list(self, since_seq: int = 0, types: Iterable[str] | None = None, direction: str | None = None,
             limit: int = 100, channels: Iterable[str] | None = None) -> list[dict[str, Any]]:
        """`channels`: channel names, compared without case."""
        sql, args = "SELECT doc FROM messages WHERE seq > ?", [since_seq]
        types = list(types or [])
        if types:
            sql += f" AND type IN ({','.join('?' * len(types))})"
            args += types
        names = [n.lower() for n in channels or []]
        if names:
            sql += f" AND lower(channel) IN ({','.join('?' * len(names))})"
            args += names
        if direction:
            sql += " AND direction = ?"
            args.append(direction)
        sql += " ORDER BY seq ASC LIMIT ?"
        args.append(limit)
        with self._lock:
            return [json.loads(r["doc"]) for r in self._db.execute(sql, args)]

    def last_seq(self) -> int:
        with self._lock:
            row = self._db.execute("SELECT COALESCE(MAX(seq), 0) AS n FROM messages").fetchone()
        return int(row["n"])

    def find_pending_hex(self, hex_payload: str, channel: str | None = None, to: str | None = None) -> dict[str, Any] | None:
        """An outgoing record with these bytes, for the same channel and peer, that is still `queued` (used to avoid
        queuing the same bytes twice)."""
        with self._lock:
            for row in self._db.execute("SELECT doc FROM messages WHERE direction='out' AND status='queued' ORDER BY seq"):
                doc = json.loads(row["doc"])
                if doc["codec"]["hex"] == hex_payload and channel_name(doc) == channel and doc.get("to") == to:
                    return doc
        return None

    # ------------------------------------------------------------ small values
    def get_meta(self, name: str, default: str | None = None) -> str | None:
        with self._lock:
            row = self._db.execute("SELECT value FROM meta WHERE name=?", (name,)).fetchone()
        return row["value"] if row else default

    def set_meta(self, name: str, value: str) -> None:
        with self._lock:
            self._db.execute("INSERT INTO meta (name, value) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET value=excluded.value",
                             (name, value))

    def peek_message_id(self) -> int:
        """The next id of the per-sender counter (1..16383). It survives restarts."""
        return int(self.get_meta("next_id", "1"))

    def commit_message_id(self, used: int) -> None:
        with self._lock:
            self.set_meta("next_id", str(used % 16383 + 1))
