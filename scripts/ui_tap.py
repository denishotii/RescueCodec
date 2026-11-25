#!/usr/bin/env python3
"""Prints the centre of a UI element from a `uiautomator dump` file, as "x y".

    ui_tap.py ui.xml resource_id_suffix       # exact id match on the part after the last "/"
    ui_tap.py ui.xml --text "Accept"          # element whose text matches
Compose test tags are exposed as resource ids (the app sets testTagsAsResourceId).
Exit code 1 when nothing matches. First match wins; --index N takes the Nth.
"""
import re
import sys
import xml.etree.ElementTree as ET

args = sys.argv[1:]
path = args.pop(0)
by_text = False
prefix = False
index = 0
if "--index" in args:
    i = args.index("--index"); index = int(args[i + 1]); del args[i:i + 2]
if "--prefix" in args:
    args.remove("--prefix"); prefix = True
if args and args[0] == "--text":
    by_text = True; args.pop(0)
needle = args[0]
hits = []
for n in ET.parse(path).getroot().iter("node"):
    rid = n.attrib.get("resource-id", "").split("/")[-1]
    ok = (n.attrib.get("text") == needle) if by_text else (rid.startswith(needle) if prefix else rid == needle)
    if ok:
        m = re.findall(r"\d+", n.attrib["bounds"])
        x1, y1, x2, y2 = map(int, m)
        hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if len(hits) <= index:
    sys.exit(1)
print(*hits[index])
