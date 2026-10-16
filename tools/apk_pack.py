#!/usr/bin/env python3
"""
Adds a file to an APK and re-aligns every stored entry, exactly like the
`zipalign` step of a normal Android release build.

Why not just use `zip`?
    `zip` rewrites the archive and throws away the alignment of
    `resources.arsc`, which the Android platform likes to have on a 4 byte
    boundary (it can then memory map the resource table instead of copying
    it). This tool rewrites the archive itself and pads the local file
    headers so that every uncompressed entry starts aligned:

        * 4 byte alignment for resources.arsc and classes.dex
        * 16 byte alignment for native libraries (.so)

    The compressed bytes of the existing entries are copied verbatim, so
    nothing is recompressed and the content stays bit for bit identical.

Usage:
    python3 tools/apk_pack.py <input.apk> <entry-name> <file> <output.apk>
"""

import os
import struct
import sys
import zipfile
import zlib

LOCAL_SIG = 0x04034B50
CENTRAL_SIG = 0x02014B50
EOCD_SIG = 0x06054B50

# The "extra field" id that zipalign uses for its padding.
PADDING_ID = 0xD935


def alignment_for(name):
    if name.endswith(".so"):
        return 16
    return 4


def build_extra(padding):
    """Returns the local extra field, padded to `padding` bytes.

    aapt2 aligns resources.arsc by writing raw zero bytes into the local
    extra field. Those bytes are not a valid "extra field" structure, so
    they are dropped here and replaced by a properly structured padding
    field (the same trick zipalign uses).
    """
    if padding <= 0:
        return b""
    return struct.pack("<HH", PADDING_ID, padding) + b"\x00" * padding


def main():
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)

    src, entry_name, payload, dst = sys.argv[1:5]

    with open(src, "rb") as handle:
        raw = handle.read()
    with open(payload, "rb") as handle:
        blob = handle.read()

    archive = zipfile.ZipFile(src)

    out = bytearray()
    central = bytearray()
    entries = []

    # The new entry is appended last.
    new_entry = {
        "name": entry_name,
        "data": blob,
        "method": 0,          # stored
        "crc": zlib.crc32(blob) & 0xFFFFFFFF,
        "usize": len(blob),
        "extra": b"",
    }
    entries.append(new_entry)

    for info in archive.infolist():
        offset = info.header_offset
        # --- read the raw local record ------------------------------------
        if struct.unpack_from("<I", raw, offset)[0] != LOCAL_SIG:
            raise SystemExit("bad local header for %s" % info.filename)
        (sig, version, flags, method, mtime, mdate, crc, csize, usize,
         name_len, extra_len) = struct.unpack_from("<IHHHHHIIIHH", raw, offset)
        name_bytes = raw[offset + 30:offset + 30 + name_len]
        extra_bytes = raw[offset + 30 + name_len:offset + 30 + name_len + extra_len]
        data_start = offset + 30 + name_len + extra_len
        data_bytes = raw[data_start:data_start + csize]

        entries.append({
            "name": info.filename,
            "data": data_bytes,
            "method": method,
            "crc": crc,
            "usize": usize,
            "extra": extra_bytes,
            "mtime": mtime,
            "mdate": mdate,
            "flags": flags,
            "version": version,
            "external_attr": info.external_attr,
            "create_system": info.create_system,
            "comment": info.comment,
        })

    # --- write the local records -------------------------------------------
    for entry in entries:
        name_bytes = entry["name"].encode("utf-8")
        data = entry["data"]
        header_len = 30 + len(name_bytes)

        # How much padding does this entry need to become aligned?
        provisional = len(out) + header_len
        alignment = alignment_for(entry["name"])
        padding = (-provisional) % alignment
        extra = build_extra(padding)

        local = struct.pack(
            "<IHHHHHIIIHH",
            LOCAL_SIG,
            entry.get("version", 20),
            entry.get("flags", 0),
            entry["method"],
            entry.get("mtime", 0),
            entry.get("mdate", 0),
            entry["crc"],
            len(data),
            entry.get("usize", len(data)),
            len(name_bytes),
            len(extra),
        )
        header_offset = len(out)
        out += local
        out += name_bytes
        out += extra
        out += data

        entry["offset"] = header_offset
        entry["name_bytes"] = name_bytes
        entry["extra_out"] = extra

    # --- write the central directory ----------------------------------------
    central_offset = len(out)
    for entry in entries:
        name_bytes = entry["name_bytes"]
        extra = entry["extra_out"]
        record = struct.pack(
            "<IHHHHHHIIIHHHHHII",
            CENTRAL_SIG,
            (entry.get("create_system", 0) << 8) | 20,
            entry.get("version", 20),
            entry.get("flags", 0),
            entry["method"],
            entry.get("mtime", 0),
            entry.get("mdate", 0),
            entry["crc"],
            len(entry["data"]),
            entry.get("usize", len(entry["data"])),
            len(name_bytes),
            len(extra),
            len(entry.get("comment", b"")),
            0,                              # disk number start
            0,                              # internal attributes
            entry.get("external_attr", 0),
            entry["offset"],
        )
        central += record
        central += name_bytes
        central += extra
        central += entry.get("comment", b"")

    out += central

    # --- end of central directory --------------------------------------------
    out += struct.pack(
        "<IHHHHIIH",
        EOCD_SIG,
        0, 0,
        len(entries), len(entries),
        len(central),
        central_offset,
        0,
    )

    with open(dst, "wb") as handle:
        handle.write(out)

    # --- sanity check --------------------------------------------------------
    with zipfile.ZipFile(dst) as check:
        broken = check.testzip()
        if broken is not None:
            raise SystemExit("corrupt entry after packing: %s" % broken)
        for info in check.infolist():
            if info.compress_type == zipfile.ZIP_STORED:
                offset = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
                if offset % alignment_for(info.filename) != 0:
                    raise SystemExit("entry %s is not aligned" % info.filename)

    print("packed %s (%d bytes) into %s - %d entries, all aligned" %
          (entry_name, len(blob), dst, len(entries)))


if __name__ == "__main__":
    main()
