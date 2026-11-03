#!/usr/bin/env python3
"""Pack a FreeSpace Open mod's loose data/ folder into .vp archives.

On Android (/sdcard is FUSE) every file open is slow, so thousands of loose files make
the game start and mission loads slow; FSO reads files out of .vp archives much faster.

    python3 tools/quest/pack_vp.py <mod folder> <out folder> [--max-gb 1.9] [--prefix fotg]

<mod folder> holds data/; writes <prefix>_<group>[_N].vp files into <out folder>. Every file
stays at its data/... path inside the archive. Archives stay below --max-gb (VP offsets are
32-bit). Names longer than 31 characters can't go into a VP; they are listed and skipped
(leave those as loose files).
"""
import argparse, os, struct, sys, time

MAX_NAME = 31


def write_vp(path, root, files):
    """files: list of paths relative to root (e.g. data/maps/x.dds)"""
    # directory tree in sorted order so each folder is entered once
    files = sorted(files, key=lambda p: [s.lower() for s in p.split("/")])
    entries = []  # (offset, size, name, timestamp)
    with open(path, "wb") as vp:
        vp.write(b"\0" * 16)
        cur = []
        for rel in files:
            parts = rel.split("/")
            dirs, name = parts[:-1], parts[-1]
            common = 0
            while common < len(cur) and common < len(dirs) and cur[common] == dirs[common]:
                common += 1
            for _ in range(len(cur) - common):
                entries.append((0, 0, "..", 0))
            for d in dirs[common:]:
                entries.append((0, 0, d, 0))
            cur = dirs
            full = os.path.join(root, rel)
            offset = vp.tell()
            with open(full, "rb") as f:
                while True:
                    b = f.read(1 << 22)
                    if not b:
                        break
                    vp.write(b)
            size = vp.tell() - offset
            entries.append((offset, size, name, int(os.path.getmtime(full))))
        for _ in range(len(cur)):
            entries.append((0, 0, "..", 0))
        diroffset = vp.tell()
        for off, size, name, ts in entries:
            vp.write(struct.pack("<ii32si", off, size, name.encode("latin-1"), ts))
        vp.seek(0)
        vp.write(struct.pack("<4siii", b"VPVP", 2, diroffset, len(entries)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mod")
    ap.add_argument("out")
    ap.add_argument("--max-gb", type=float, default=1.9)
    ap.add_argument("--prefix", default="mod")
    a = ap.parse_args()
    limit = int(a.max_gb * (1 << 30))
    data = os.path.join(a.mod, "data")
    os.makedirs(a.out, exist_ok=True)

    groups = {}
    skipped = []
    for r, _, fs in os.walk(data):
        for f in fs:
            full = os.path.join(r, f)
            rel = os.path.relpath(full, a.mod).replace(os.sep, "/")
            if any(len(p) > MAX_NAME for p in rel.split("/")):
                skipped.append(rel)
                continue
            sub = rel.split("/")
            group = sub[1] if len(sub) > 2 else "root"
            groups.setdefault(group, []).append(rel)

    t0 = time.time()
    written = []
    for group in sorted(groups):
        files = sorted(groups[group])
        chunks, chunk, size = [], [], 0
        for rel in files:
            s = os.path.getsize(os.path.join(a.mod, rel))
            if chunk and size + s > limit:
                chunks.append(chunk)
                chunk, size = [], 0
            chunk.append(rel)
            size += s
        if chunk:
            chunks.append(chunk)
        for i, ch in enumerate(chunks):
            name = "%s_%s%s.vp" % (a.prefix, group, "" if len(chunks) == 1 else "_%d" % (i + 1))
            out = os.path.join(a.out, name)
            write_vp(out, a.mod, ch)
            written.append(name)
            print("%-28s %5d files %7.1f MB" % (name, len(ch), os.path.getsize(out) / 1e6), flush=True)
    if skipped:
        print("skipped (name longer than %d characters, keep them loose):" % MAX_NAME)
        for s in skipped:
            print("  " + s)
    print("%d archives in %.0f s" % (len(written), time.time() - t0))


if __name__ == "__main__":
    main()
