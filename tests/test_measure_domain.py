"""spec/tools/measure_domain.py counts the same sizes as the send-rule vectors (spec/vectors/domain.json -> choice).

The measurement itself needs the downloaded corpora (not in the repo); this checks its counting on known texts."""
import base64
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

from rescuecodec.v2 import textdomain, textwords

ROOT = Path(__file__).resolve().parents[1]


def _tool():
    if "measure_domain" not in sys.modules:          # dataclasses need the module registered while it loads
        spec = importlib.util.spec_from_file_location("measure_domain", ROOT / "spec" / "tools" / "measure_domain.py")
        mod = importlib.util.module_from_spec(spec)
        sys.modules["measure_domain"] = mod
        spec.loader.exec_module(mod)
    return sys.modules["measure_domain"]


def _choice():
    return json.loads((ROOT / "spec" / "vectors" / "domain.json").read_text(encoding="utf-8"))["choice"]


def test_sizes_match_choice_vectors():
    md = _tool()
    d2 = textwords.dictionary(2)
    for case in _choice():
        if case["setting"] != "auto":
            continue
        dd = textdomain.vocabulary(textdomain.vocab_id("medical"))
        r = md.measure_one(case["text"], "medical", dd, d2)
        assert r["auto"] == case["size"], case["name"]
        assert r["plain"] == case["sizes"]["plain"], case["name"]
        assert r["lean"] == case["sizes"]["lean"], case["name"]
        assert r["domain"] == case["sizes"]["domain"]["medical"], case["name"]
        assert r["fit_auto"] == 1
        if case["form"] == "domain":
            assert r["pick"] == ("domain_own" if case["vocab"] == "medical" else "domain_other"), case["name"]
            assert r["vocab"] == case["vocab"]
        else:
            assert r["pick"] == case["form"] and r["vocab"] is None, case["name"]


def test_general_and_pinned_settings():
    md = _tool()
    text = "Patient is conscious, bleeding from the leg, apply a tourniquet."
    total, kinds = md.measure([(text, "messages"), (text, "documents")], "medical")
    assert total.texts == 2 and kinds["messages"].texts == 1 and kinds["documents"].texts == 1
    assert total.general == 2 * 38          # General: the 0x02 frame
    assert total.auto == 2 * 19             # Auto: the Medical domain frame
    assert total.pinned == 2 * 19           # pinned to Medical
    assert total.smaller == 2 and total.picks["domain_own"] == 2
    sar, _ = md.measure([(text, "messages")], "sar")
    assert sar.pinned == 38 and sar.picks["domain_other"] == 1    # pinned S&R: 0x02 frame (tie with S&R 38 B)


def test_text_that_does_not_fit_counts_its_complete_size():
    md = _tool()
    # 399 B of base64 (6 bits per character, so DEFLATE stays over 200 B too): no form fits one packet
    text = " ".join(base64.b64encode(hashlib.sha256(bytes([n])).digest()).decode() for n in range(10))[:399]
    t, _ = md.measure([(text, "messages")], None)
    assert t.fit_plain == 0 and t.fit_general == 0 and t.fit_auto == 0
    assert t.auto >= 200 and t.general >= 200
