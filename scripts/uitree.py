#!/usr/bin/env python3
"""Reads the screen description `uiautomator dump` writes and prints it for people.

    uitree.py            one line per thing on screen that says or does something
    uitree.py --find W   the centre "x y" of the first thing whose text or description
                         is W (exact match first, then one that contains it); exit 1 if none.
                         W may also be id:NAME for a resource id, with #2 for the second
                         one on screen: id:media_item_click_target#2
    uitree.py --texts id:NAME   the text of everything with that resource id, one per line

Used by scripts/emu (`ui`, `tap "<words>"`, `waitfor`). Runs inside the rig container.
"""
import re
import sys
import xml.etree.ElementTree as ET


def nodes(xml_text):
    start = xml_text.find("<?xml")
    end = xml_text.rfind("</hierarchy>")
    if start < 0 or end < 0:
        sys.exit("the device returned no screen description (is the screen on, or a video playing?)")
    tree = ET.fromstring(xml_text[start:end + len("</hierarchy>")])
    for depth, node, tappable in walk(tree, 0, False):
        b = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        node.set("_tappable", "true" if tappable else "false")
        yield depth, node, (x1, y1, x2, y2)


def walk(node, depth, inside_tappable):
    """Every node, with whether it or something around it responds to a tap."""
    for child in node:
        tappable = inside_tappable or child.get("clickable") == "true"
        yield depth, child, tappable
        yield from walk(child, depth + 1, tappable)


def main():
    text = sys.stdin.read()
    if len(sys.argv) >= 3 and sys.argv[1] == "--texts":
        rid = sys.argv[2].split(":", 1)[-1]
        for _, node, _ in nodes(text):
            if node.get("resource-id", "").split("/", 1)[-1] == rid and node.get("text"):
                print(node.get("text"))
        return
    if len(sys.argv) >= 3 and sys.argv[1] == "--find" and sys.argv[2].startswith("id:"):
        rid, _, nth = sys.argv[2][3:].partition("#")
        hits = [box for _, node, box in nodes(text)
                if node.get("resource-id", "").split("/", 1)[-1] == rid and box[2] > box[0] and box[3] > box[1]]
        index = int(nth or 1) - 1
        if index >= len(hits):
            sys.exit(1)
        x1, y1, x2, y2 = hits[index]
        print((x1 + x2) // 2, (y1 + y2) // 2)
        return
    if len(sys.argv) >= 3 and sys.argv[1] == "--find":
        # A form often shows the same words twice: as a label, and on the field itself.
        # Prefer the one that responds to a tap, and an exact match over a partial one.
        want = sys.argv[2]
        best, best_rank = None, 9
        for _, node, (x1, y1, x2, y2) in nodes(text):
            if x2 <= x1 or y2 <= y1:
                continue
            labels = [node.get("text", ""), node.get("content-desc", "")]
            exact = want in labels
            partial = any(want.lower() in label.lower() for label in labels if label)
            if not (exact or partial):
                continue
            rank = (0 if exact else 2) + (0 if node.get("_tappable") == "true" else 1)
            if rank < best_rank:
                best, best_rank = (x1, y1, x2, y2), rank
        if not best:
            sys.exit(1)
        print((best[0] + best[2]) // 2, (best[1] + best[3]) // 2)
        return

    for depth, node, (x1, y1, x2, y2) in nodes(text):
        label = node.get("text", "")
        desc = node.get("content-desc", "")
        rid = node.get("resource-id", "")
        flags = [name for name, attr in (("tap", "clickable"), ("hold", "long-clickable"),
                                         ("scroll", "scrollable"), ("focused", "focused"),
                                         ("checked", "checked"), ("selected", "selected"))
                 if node.get(attr) == "true"]
        if node.get("enabled") == "false":
            flags.append("disabled")
        if not (label or desc or flags or rid):
            continue
        cls = node.get("class", "").rsplit(".", 1)[-1]
        parts = ["  " * min(depth, 12) + cls]
        if label:
            parts.append('"%s"' % label.replace("\n", "\\n"))
        if desc:
            parts.append('desc="%s"' % desc.replace("\n", "\\n"))
        if rid:
            parts.append("id=" + rid.split("/", 1)[-1])
        if flags:
            parts.append("[" + " ".join(flags) + "]")
        parts.append("@%d,%d %dx%d" % ((x1 + x2) // 2, (y1 + y2) // 2, x2 - x1, y2 - y1))
        print(" ".join(parts))


if __name__ == "__main__":
    main()
