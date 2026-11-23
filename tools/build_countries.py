#!/usr/bin/env python3
"""
Builds app/src/main/assets/countries.bin from Natural Earth 1:50m admin-0 countries (public domain).

    curl -sSL -o ne_50m.geojson \
      https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_50m_admin_0_countries.geojson
    python3 tools/build_countries.py ne_50m.geojson app/src/main/assets/countries.bin

Rings are Douglas-Peucker simplified and quantised to 0.01°. Format (big-endian, read by CountryShapes.kt):
    "EGC1"  u16 countryCount
    per country:  2 bytes ISO-3166 alpha-2 ("--" if none)
                  u16 len + UTF-8 name
                  i16 labelLon*100, i16 labelLat*100, u8 labelRank
                  u16 ringCount, per ring: u16 n, n × (i16 lon*100, i16 lat*100)
Stdlib only.
"""
import json
import struct
import sys

TOLERANCE = 0.04       # degrees
MIN_RING_EXTENT = 0.3  # drop islands smaller than this (degrees), except a country's largest ring
MIN_LAT = -57.0        # bottom edge of the map projection; drops Antarctica

NAME_OVERRIDES = {
    "US": "United States",
    "BA": "Bosnia & Herz.",
    "CD": "DR Congo",
    "CF": "Central African Rep.",
    "DO": "Dominican Rep.",
}


def dp(points, tol):
    """Iterative Douglas-Peucker on a closed ring (first == last)."""
    if len(points) < 5:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    tol2 = tol * tol
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        norm = dx * dx + dy * dy
        best, best_i = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            if norm == 0:
                d = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = ((px - ax) * dx + (py - ay) * dy) / norm
                t = max(0.0, min(1.0, t))
                qx, qy = ax + t * dx, ay + t * dy
                d = (px - qx) ** 2 + (py - qy) ** 2
            if d > best:
                best, best_i = d, i
        if best > tol2 and best_i > 0:
            keep[best_i] = True
            stack.append((a, best_i))
            stack.append((best_i, b))
    return [p for p, k in zip(points, keep) if k]


def extent(ring):
    xs = [p[0] for p in ring]
    ys = [p[1] for p in ring]
    return max(max(xs) - min(xs), max(ys) - min(ys))


def area(ring):
    s = 0.0
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        s += x1 * y2 - x2 * y1
    return abs(s) / 2


def q(v):
    return int(round(v * 100))


def main(src, dst):
    feats = json.load(open(src, encoding="utf-8"))["features"]
    out = []
    total_pts = 0
    for f in feats:
        p = f["properties"]
        cc = p.get("ISO_A2_EH") or p.get("ISO_A2") or "-99"
        if cc == "AQ":
            continue
        if len(cc) != 2 or not cc.isalpha():
            cc = "--"
        g = f["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        outer = [poly[0] for poly in polys]  # holes (lakes, enclaves) are skipped: enclaves are their own countries
        outer = [r for r in outer if max(pt[1] for pt in r) > MIN_LAT]
        if not outer:
            continue
        largest = max(outer, key=area)
        rings = []
        for r in outer:
            ext = extent(r)
            if r is not largest and ext < MIN_RING_EXTENT:
                continue
            s = dp(r, min(TOLERANCE, ext / 8))
            qs = []
            for x, y in s:
                pt = (q(x), q(max(y, MIN_LAT)))
                if not qs or qs[-1] != pt:
                    qs.append(pt)
            if len(qs) >= 4:
                rings.append(qs)
        if not rings:
            continue
        name = NAME_OVERRIDES.get(cc) or p.get("NAME") or cc
        out.append((cc, name, q(p.get("LABEL_X") or 0), q(p.get("LABEL_Y") or 0), int(p.get("LABELRANK") or 9), rings))
        total_pts += sum(len(r) for r in rings)

    buf = bytearray(b"EGC1")
    buf += struct.pack(">H", len(out))
    for cc, name, lx, ly, rank, rings in out:
        buf += cc.upper().encode("ascii")
        nb = name.encode("utf-8")
        buf += struct.pack(">H", len(nb)) + nb
        buf += struct.pack(">hhB", lx, ly, rank)
        buf += struct.pack(">H", len(rings))
        for r in rings:
            buf += struct.pack(">H", len(r))
            for x, y in r:
                buf += struct.pack(">hh", x, y)
    open(dst, "wb").write(buf)
    print(f"{len(out)} countries, {sum(len(c[5]) for c in out)} rings, {total_pts} points, {len(buf)} bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
