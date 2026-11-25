"""The frozen domain vocabularies (spec/vocab/domain-*-v1.txt) match spec/vocab/domains.yaml byte for byte."""
import hashlib
from pathlib import Path

import yaml

from rescuecodec.v2.textwords import WordDict

VOCAB = Path(__file__).resolve().parents[1] / "spec" / "vocab"


def _table():
    return yaml.safe_load((VOCAB / "domains.yaml").read_text(encoding="utf-8"))


def test_table_ids_and_controls():
    t = _table()
    assert t["reserved_codes"] == 8
    assert t["controls"][:5] == ["ESC16", "CAP", "UPPER", "GLUE", "LIT"]
    ids = [v["id"] for v in t["vocabularies"]]
    assert len(ids) == len(set(ids)) and all(1 <= i <= 255 for i in ids)
    assert {v["domain"] for v in t["vocabularies"]} == {"emergency", "sar", "fire", "hiking", "medical"}


def test_files_match_sha_and_size():
    t = _table()
    for v in t["vocabularies"]:
        data = (VOCAB / v["file"]).read_bytes()
        assert hashlib.sha256(data).hexdigest() == v["sha256"], v["file"]
        assert len(data) == v["bytes"]
        assert v["bits"] in (8, 9, 10)
        entries = data.decode("ascii")[:-1].split("\n")
        assert data.endswith(b"\n") and b"\r" not in data
        assert len(entries) == v["entries"] == (1 << v["bits"]) - t["reserved_codes"]
        assert all(0x20 <= ord(c) < 0x7F for e in entries for c in e)
        WordDict.from_entries(entries)          # no empty, repeated or space-padded entries
