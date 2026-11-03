#!/usr/bin/env python3
"""Make a smaller copy of a FreeSpace Open mod for the headset by halving its big textures.

    python3 tools/quest/shrink_textures.py <mod folder> <out folder> [--min 2048] [--keep cockpit]

Every .dds in data/maps and data/effects that is --min pixels or larger and already has
mipmaps loses its top mip level: the file then starts at the next (half-size) level, which
the mod's authors already made. Nothing is re-compressed, so there is no extra quality loss,
and it takes about a minute. Textures whose name contains a --keep word (default: cockpit,
seen up close) stay as they are, as does everything else (unchanged files are hard links, so
the copy only uses disk space for the shrunken textures).
"""
import argparse, os, shutil, struct

BLOCK = {b"DXT1": 8, b"DXT3": 16, b"DXT5": 16, b"ATI1": 8, b"BC4U": 8, b"ATI2": 16, b"BC5U": 16}
DX10_BLOCK = {70: 8, 71: 8, 72: 8, 73: 16, 74: 16, 75: 16, 76: 16, 77: 16, 78: 16, 79: 8, 80: 8,
              81: 8, 82: 16, 83: 16, 84: 16, 94: 16, 95: 16, 96: 16, 97: 16, 98: 16, 99: 16}


def level_size(w, h, block, bpp):
    if block:
        return max(1, (w + 3) // 4) * max(1, (h + 3) // 4) * block
    return w * h * bpp // 8


def shrink(src, dst, minsize):
    """Write src without its top mip to dst; False if the file isn't suitable (dst untouched)."""
    with open(src, "rb") as f:
        data = f.read()
    if len(data) < 128 or data[:4] != b"DDS ":
        return False
    h, w, _, depth, mips = struct.unpack("<5I", data[12:32])
    pf_flags = struct.unpack("<I", data[80:84])[0]
    fourcc = data[84:88]
    rgb_bits = struct.unpack("<I", data[88:92])[0]
    caps2 = struct.unpack("<I", data[112:116])[0]
    if mips < 2 or max(w, h) < minsize or caps2 & 0x200 or caps2 & 0x200000:
        return False  # no mipmaps, small, cube map or volume
    hdr = 128
    block, bpp = 0, 0
    if pf_flags & 0x4:  # FOURCC
        if fourcc == b"DX10":
            dxgi, dim, _, array = struct.unpack("<4I", data[128:144])
            if array != 1 or dxgi not in DX10_BLOCK:
                return False
            block, hdr = DX10_BLOCK[dxgi], 148
        elif fourcc in BLOCK:
            block = BLOCK[fourcc]
        else:
            return False
    elif rgb_bits in (24, 32):
        bpp = rgb_bits
    else:
        return False
    top = level_size(w, h, block, bpp)
    total = sum(level_size(max(1, w >> i), max(1, h >> i), block, bpp) for i in range(mips))
    if hdr + total != len(data):
        return False  # layout we don't understand: leave it alone
    nw, nh = max(1, w // 2), max(1, h // 2)
    header = bytearray(data[:hdr])
    pitch = level_size(nw, nh, block, bpp) if block else nw * bpp // 8
    struct.pack_into("<5I", header, 12, nh, nw, pitch, depth, mips - 1)
    with open(dst, "wb") as f:
        f.write(header)
        f.write(data[hdr + top:])
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mod")
    ap.add_argument("out")
    ap.add_argument("--min", type=int, default=2048)
    ap.add_argument("--keep", action="append", default=None)
    a = ap.parse_args()
    keep = [k.lower() for k in (a.keep or ["cockpit"])]
    before = after = 0
    n = 0
    for r, _, fs in os.walk(a.mod):
        rel = os.path.relpath(r, a.mod)
        os.makedirs(os.path.join(a.out, rel), exist_ok=True)
        parts = rel.replace(os.sep, "/").lower().split("/")
        area = len(parts) >= 2 and parts[0] == "data" and parts[1] in ("maps", "effects")
        for f in fs:
            src, dst = os.path.join(r, f), os.path.join(a.out, rel, f)
            if os.path.exists(dst):
                os.remove(dst)
            size = os.path.getsize(src)
            before += size
            if area and f.lower().endswith(".dds") and not any(k in f.lower() for k in keep) \
                    and shrink(src, dst, a.min):
                n += 1
                after += os.path.getsize(dst)
                continue
            try:
                os.link(src, dst)
            except OSError:
                shutil.copy2(src, dst)
            after += size
    print("%d textures halved; %.2f GB -> %.2f GB" % (n, before / 1e9, after / 1e9))


if __name__ == "__main__":
    main()
