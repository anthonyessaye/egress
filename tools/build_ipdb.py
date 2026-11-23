#!/usr/bin/env python3
"""
Builds app/src/main/assets/ipcountry.bin from DB-IP "IP to Country Lite" (CC BY 4.0, https://db-ip.com).
Used on-device for country blocking: synchronous IP -> country lookups and per-country route lists.

    curl -sSL -o dbip.csv.gz https://download.db-ip.com/free/dbip-country-lite-YYYY-MM.csv.gz && gunzip dbip.csv.gz
    python3 tools/build_ipdb.py dbip.csv app/src/main/assets/ipcountry.bin

Format (big-endian, read by IpCountryDb.kt). Each table is a sorted list of range starts; entry i covers
[start_i, start_{i+1}) and is tagged with a country index (0 = unknown/reserved). Adjacent ranges of the
same country are merged. IPv6 keeps only the top 64 bits (allocations are never finer than /64).
    "EGI1"
    u8 countryCount, countryCount x 2 bytes ISO code (index 0 = "--")
    u32 n4, n4 x u32 start, n4 x u8 country
    u32 n6, n6 x u64 start, n6 x u8 country
Stdlib only.
"""
import csv
import ipaddress
import struct
import sys


def build(rows, bits):
    """rows: (start, end, cc) sorted. Returns merged [(start, cc)] covering the whole space from 0."""
    out = []
    expect = 0
    for s, e, cc in rows:
        if s > expect:
            out.append((expect, "--"))  # gap: unknown
        if s < expect:  # overlapping input: skip the overlapped part
            if e < expect:
                continue
            s = expect
        out.append((s, cc))
        expect = e + 1
    if expect < (1 << bits):
        out.append((expect, "--"))
    merged = []
    for s, cc in out:
        if merged and merged[-1][1] == cc:
            continue
        merged.append((s, cc))
    return merged


def main(src, dst):
    v4, v6 = [], []
    for a, b, cc in csv.reader(open(src, encoding="utf-8")):
        s, e = ipaddress.ip_address(a), ipaddress.ip_address(b)
        cc = cc.upper() if len(cc) == 2 and cc.isalpha() and cc.upper() != "ZZ" else "--"
        if s.version == 4:
            v4.append((int(s), int(e), cc))
        else:
            v6.append((int(s) >> 64, int(e) >> 64, cc))
    v4.sort()
    v6.sort()
    t4 = build(v4, 32)
    t6 = build(v6, 64)

    codes = ["--"] + sorted({cc for _, cc in t4 + t6} - {"--"})
    assert len(codes) < 256
    idx = {c: i for i, c in enumerate(codes)}

    buf = bytearray(b"EGI1")
    buf += struct.pack(">B", len(codes))
    for c in codes:
        buf += c.encode("ascii")
    buf += struct.pack(">I", len(t4))
    buf += b"".join(struct.pack(">I", s) for s, _ in t4)
    buf += bytes(idx[c] for _, c in t4)
    buf += struct.pack(">I", len(t6))
    buf += b"".join(struct.pack(">Q", s) for s, _ in t6)
    buf += bytes(idx[c] for _, c in t6)
    open(dst, "wb").write(buf)
    print(f"{len(codes) - 1} countries, {len(t4)} IPv4 runs, {len(t6)} IPv6 runs, {len(buf)} bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
