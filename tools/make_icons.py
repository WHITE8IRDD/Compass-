#!/usr/bin/env python3
"""
Generates the monochrome launcher icons for Compass.

Why a script instead of a shipped PNG?
  * the icon is *drawn*, not photographed - a script keeps the source of
    truth readable and lets you re-generate every density with one command,
  * it has no third-party dependency (pure Python + zlib), so it runs
    anywhere,
  * it documents exactly how the icon is made, which is part of the
    "step by step" build instructions.

Usage:
    python3 tools/make_icons.py

It writes:
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png
"""

import math
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# The classic density buckets for launcher icons.
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

BLACK = (0, 0, 0)
WHITE = (255, 255, 255)


def needle_polygon(cx, cy, size):
    """A slim compass needle pointing up, plus the hub."""
    r = size * 0.30
    return [
        (cx, cy - r),          # north tip
        (cx + r * 0.16, cy),   # east waist
        (cx, cy + r * 0.62),   # south tail
        (cx - r * 0.16, cy),   # west waist
    ]


def point_in_polygon(x, y, polygon):
    inside = False
    n = len(polygon)
    j = n - 1
    for i in range(n):
        xi, yi = polygon[i]
        xj, yj = polygon[j]
        if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / (yj - yi) + xi):
            inside = not inside
        j = i
    return inside


def render_icon(size, round_icon=False):
    """Returns RGBA bytes for one icon."""
    pixels = [[BLACK + (255,) for _ in range(size)] for _ in range(size)]
    cx = cy = size / 2.0
    ring_outer = size * 0.38
    ring_inner = size * 0.355
    polygon = needle_polygon(cx, cy, size)

    for y in range(size):
        for x in range(size):
            dx = x + 0.5 - cx
            dy = y + 0.5 - cy
            distance = math.hypot(dx, dy)

            in_ring = ring_inner <= distance <= ring_outer
            in_needle = point_in_polygon(x + 0.5, y + 0.5, polygon)
            in_hub = distance <= size * 0.035

            if in_ring or in_needle or in_hub:
                pixels[y][x] = WHITE + (255,)
            elif round_icon and distance > size * 0.5:
                # The round variant is clipped to a circle.
                pixels[y][x] = (0, 0, 0, 0)

    return pixels


def write_png(path, pixels):
    size = len(pixels)
    raw = bytearray()
    for row in pixels:
        raw.append(0)  # filter type 0
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(png)
    print("wrote", os.path.relpath(path, ROOT))


def main():
    for bucket, size in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + bucket)
        write_png(os.path.join(folder, "ic_launcher.png"), render_icon(size, False))
        write_png(os.path.join(folder, "ic_launcher_round.png"), render_icon(size, True))


if __name__ == "__main__":
    main()
