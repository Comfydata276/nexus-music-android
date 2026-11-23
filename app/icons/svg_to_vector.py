#!/usr/bin/env python3
"""Turns the Windows app's icons (ui/icons/*.svg) into Android vector drawables.

    python3 app/icons/svg_to_vector.py ../nexus-music/ui/icons app/src/main/res/drawable

Each `name.svg` becomes `ic_name.xml` (dashes become underscores). Lucide's stroke icons stay
strokes (2 wide, round caps and joins); the app's own `*-fill` icons and `bars` are filled.
Circles, rectangles, lines and polygons are written out as paths, and path data is re-spaced
so no reader has to cope with run-together arc flags. The drawables are white: Compose tints
them where they're drawn.

Only what the icon set uses is handled; anything else stops the conversion rather than
quietly producing a wrong icon.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = "{http://www.w3.org/2000/svg}"
KNOWN = {"class", "xmlns", "width", "height", "viewBox", "fill", "stroke", "stroke-width",
         "stroke-linecap", "stroke-linejoin", "d", "cx", "cy", "r", "x", "y", "rx", "ry",
         "x1", "y1", "x2", "y2", "points", "transform"}
ARGS = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "a": 7, "z": 0}


def num(value):
    text = f"{float(value):.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def respace(d):
    """Re-writes path data with every number and flag apart."""
    out = []
    i, n = 0, len(d)
    command = None
    count = 0
    number = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
    while i < n:
        ch = d[i]
        if ch in " ,\t\r\n":
            i += 1
            continue
        if ch.isalpha():
            command = ch
            count = 0
            out.append(ch)
            i += 1
            continue
        if command is None:
            raise ValueError(f"number before a command in {d!r}")
        # Arc flags (the 4th and 5th of each 7) are single digits, often run together.
        if command.lower() == "a" and count % 7 in (3, 4):
            if ch not in "01":
                raise ValueError(f"bad arc flag in {d!r}")
            out.append(ch)
            i += 1
        else:
            match = number.match(d, i)
            if not match:
                raise ValueError(f"can't read {d[i:i + 12]!r} in {d!r}")
            out.append(num(match.group()))
            i = match.end()
        count += 1
    return " ".join(out)


def rect_path(x, y, w, h, rx, ry):
    if rx == 0 and ry == 0:
        return f"M{num(x)} {num(y)}h{num(w)}v{num(h)}h{num(-w)}z"
    rx, ry = min(rx, w / 2), min(ry, h / 2)
    return (f"M{num(x + rx)} {num(y)}h{num(w - 2 * rx)}a{num(rx)} {num(ry)} 0 0 1 {num(rx)} {num(ry)}"
            f"v{num(h - 2 * ry)}a{num(rx)} {num(ry)} 0 0 1 {num(-rx)} {num(ry)}"
            f"h{num(-(w - 2 * rx))}a{num(rx)} {num(ry)} 0 0 1 {num(-rx)} {num(-ry)}"
            f"v{num(-(h - 2 * ry))}a{num(rx)} {num(ry)} 0 0 1 {num(rx)} {num(-ry)}z")


def element_path(el):
    tag = el.tag.replace(NS, "")
    a = {k: v for k, v in el.attrib.items()}
    f = lambda k, default=0.0: float(a.get(k, default))
    if tag == "path":
        return respace(a["d"])
    if tag == "circle":
        cx, cy, r = f("cx"), f("cy"), f("r")
        return (f"M{num(cx - r)} {num(cy)}a{num(r)} {num(r)} 0 1 0 {num(2 * r)} 0"
                f"a{num(r)} {num(r)} 0 1 0 {num(-2 * r)} 0z")
    if tag == "rect":
        rx = f("rx", a.get("ry", 0))
        ry = f("ry", a.get("rx", 0))
        return rect_path(f("x"), f("y"), f("width"), f("height"), rx, ry)
    if tag == "line":
        return f"M{num(f('x1'))} {num(f('y1'))}L{num(f('x2'))} {num(f('y2'))}"
    if tag == "polygon":
        points = [float(p) for p in re.split(r"[\s,]+", a["points"].strip())]
        pairs = [f"{num(points[i])} {num(points[i + 1])}" for i in range(0, len(points), 2)]
        return "M" + " L".join(pairs) + "z"
    raise ValueError(f"unhandled element <{tag}>")


def translate(el):
    t = el.attrib.get("transform")
    if not t:
        return None
    match = re.fullmatch(r"translate\(\s*([-\d.]+)(?:[\s,]+([-\d.]+))?\s*\)", t.strip())
    if not match:
        raise ValueError(f"unhandled transform {t!r}")
    return float(match.group(1)), float(match.group(2) or 0)


def convert(svg: Path) -> str:
    root = ET.parse(svg).getroot()
    for el in root.iter():
        unknown = set(el.attrib) - KNOWN
        if unknown:
            raise ValueError(f"{svg.name}: unhandled attributes {unknown}")
    if root.attrib.get("viewBox", "0 0 24 24") != "0 0 24 24":
        raise ValueError(f"{svg.name}: unexpected viewBox")
    stroked = root.attrib.get("stroke") not in (None, "none")
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- From the Windows app's ui/icons/{svg.name} by app/icons/svg_to_vector.py. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24">',
    ]
    for el in root:
        tag = el.tag.replace(NS, "")
        if tag not in ("path", "circle", "rect", "line", "polygon"):
            raise ValueError(f"{svg.name}: unhandled element <{tag}>")
        attrs = [f'android:pathData="{element_path(el)}"']
        own_fill = el.attrib.get("fill")
        if stroked:
            attrs += ['android:strokeColor="#FFFFFFFF"', 'android:strokeWidth="2"',
                      'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
            if own_fill and own_fill != "none":
                attrs.append('android:fillColor="#FFFFFFFF"')
        else:
            attrs.append('android:fillColor="#FFFFFFFF"')
        body = "    <path\n" + "".join(f"        {x}\n" for x in attrs[:-1]) + f"        {attrs[-1]} />"
        shift = translate(el)
        if shift:
            body = (f'    <group android:translateX="{num(shift[0])}" android:translateY="{num(shift[1])}">\n'
                    + "\n".join("    " + line for line in body.split("\n")) + "\n    </group>")
        lines.append(body)
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def main():
    source, target = Path(sys.argv[1]), Path(sys.argv[2])
    target.mkdir(parents=True, exist_ok=True)
    for svg in sorted(source.glob("*.svg")):
        name = "ic_" + svg.stem.replace("-", "_")
        (target / f"{name}.xml").write_text(convert(svg))
        print(name)


if __name__ == "__main__":
    main()
