"""Tiny helper over a uiautomator dump: `find dump.xml LABEL` prints the centre of the first node whose
text or content-desc equals LABEL (case-insensitive); `list dump.xml` prints the labelled nodes."""
import re, sys
import xml.etree.ElementTree as ET

def nodes(path):
    for n in ET.parse(path).iter("node"):
        yield n.get("text") or "", n.get("content-desc") or "", n.get("bounds") or "", n.get("package") or ""

cmd, path = sys.argv[1], sys.argv[2]
if cmd == "list":
    for t, d, b, p in nodes(path):
        if t or d: print(p, repr(t), repr(d), b)
    sys.exit(0)
want = sys.argv[3].lower()
for t, d, b, _ in nodes(path):
    if want in (t.strip().lower(), d.strip().lower()):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", b))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
