#!/usr/bin/env python3
"""Reads the screen description `uiautomator dump` writes and prints it for people.

    uitree.py            one line per thing on screen that says or does something
    uitree.py --find W   the centre "x y" of the first thing whose text or description
                         is W (exact match first, then one that contains it); exit 1 if none

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
    for depth, node in walk(tree, 0):
        b = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        yield depth, node, (x1, y1, x2, y2)


def walk(node, depth):
    for child in node:
        yield depth, child
        yield from walk(child, depth + 1)


def main():
    text = sys.stdin.read()
    if len(sys.argv) >= 3 and sys.argv[1] == "--find":
        want = sys.argv[2]
        found = None
        for _, node, (x1, y1, x2, y2) in nodes(text):
            if x2 <= x1 or y2 <= y1:
                continue
            labels = [node.get("text", ""), node.get("content-desc", "")]
            if want in labels:
                found = (x1, y1, x2, y2)
                break
            if found is None and any(want.lower() in label.lower() for label in labels if label):
                found = (x1, y1, x2, y2)
        if not found:
            sys.exit(1)
        print((found[0] + found[2]) // 2, (found[1] + found[3]) // 2)
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
