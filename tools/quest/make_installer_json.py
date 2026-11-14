#!/usr/bin/env python3
"""Write android/app/src/main/assets/fotg_data.json, the download list of the in-app installer.

    python3 tools/quest/make_installer_json.py [--id FotG_2 --version 2.0.2] [--installed <mod dir> | --installed-sizes <file>]

Reads the mod's package list from FSNebula (names, sizes, sha256, mirror URLs) and the file
lists inside the 7z archives (7-Zip needed; only the archive headers are fetched, with HTTP
range requests). Default: the newest release of Fate of the Galaxy on Nebula (FotG_R up to
1.1.3, FotG_2 from 2.0.1 on).

Updates: for every archive, "accept" lists the sha256 of older Nebula releases of this archive
with identical contents (same file names, sizes and CRCs; the team repacks unchanged archives on
every release). The app records the sha256 of each archive it installs (fotg_installed.txt in the
mod folder) and offers an update only for archives whose recorded hash is neither "sha256" nor
in "accept". "legacy" holds the hashes the installer of FotG VR 0.3.7 and older installed
(FotG_R 1.1.1; it did not write the record yet).

"installed" (size of the archive's fotg_<name>[_N].vp files on the headset, used by the damage
check and the free space check) comes from --installed (a mod folder the installer filled) or
--installed-sizes (lines "<size> <vp name>", e.g. from
`adb shell "cd /sdcard/FotG/FotG_R && stat -c '%s %n' fotg_FG_*.vp"`). Without them it is kept
from the previous fotg_data.json for archives with unchanged contents; changed archives get an
estimate 2 % below the expected size (an estimate that is too high would make the app report
intact data as damaged) until real sizes are passed.
"""
import argparse, glob, json, os, re, shutil, struct, subprocess, tempfile, urllib.request

API = "https://api.fsnebula.org/api/1/mod/json/%s/%s"
REPO = "https://fsnebula.org/storage/repo_minimal.json"
IDS = ("FotG_R", "FotG_2")          # Nebula mod ids of Fate of the Galaxy, oldest first
LEGACY = ("FotG_R", "1.1.1")         # data of the installer in FotG VR 0.3.7 and older
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "android", "app", "src", "main", "assets", "fotg_data.json")
SZ = shutil.which("7zz") or shutil.which("7z") or os.path.expanduser("~/tools/7zip/7zz")


def rkey(r):
    return (IDS.index(r[0]), [int(x) for x in re.findall(r"\d+", r[1])])


def releases():
    mods = json.load(urllib.request.urlopen(REPO))["mods"]
    return sorted(((m["id"], m["version"]) for m in mods if m.get("id") in IDS), key=rkey)


def files_of(mod_id, ver):
    m = json.load(urllib.request.urlopen(API % (mod_id, ver)))["mod"]
    return {f["filename"]: f for p in m["packages"] for f in p["files"]}


def rng(url, a, b):
    req = urllib.request.Request(url, headers={"Range": "bytes=%d-%d" % (a, b), "User-Agent": "FotG-VR tools"})
    return urllib.request.urlopen(req).read()


_cache = {}


def listing(f):
    """{path: (size, crc)} of a 7z on the server, read from its start header and the end of the file."""
    key = f["checksum"][1]
    if key in _cache:
        return _cache[key]
    url, size = f["urls"][0], f["filesize"]
    head = rng(url, 0, 31)
    if head[:6] != b"7z\xbc\xaf\x27\x1c":
        raise SystemExit("%s is not a 7z archive" % f["filename"])
    off, ln = struct.unpack("<QQ", head[12:28])
    end = 32 + off + ln
    body = ""
    with tempfile.TemporaryDirectory() as td:
        path = os.path.join(td, f["filename"])
        for back in (4 << 20, 64 << 20, end):
            st = max(32, end - back)
            with open(path, "wb") as o:   # sparse copy: a listing only reads the headers
                o.truncate(size)
                o.write(head)
                o.seek(st)
                o.write(rng(url, st, end - 1))
            out = subprocess.run([SZ, "l", "-slt", path], capture_output=True, text=True).stdout
            body = out.split("----------", 1)[1] if "----------" in out else ""
            if "Path = " in body:
                break
    if "Path = " not in body:
        raise SystemExit("cannot list " + f["filename"])
    d: dict = {}
    cur: dict = {}
    for l in body.splitlines():
        if l.startswith("Path = "):
            cur = {"p": l[7:]}
        elif l.startswith("Size = "):
            cur["s"] = int(l[7:])
        elif l.startswith("CRC = "):
            d[cur["p"]] = (cur["s"], l[6:])
    _cache[key] = d
    return d


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--id", default=IDS[-1], help="Nebula mod id (with --version)")
    ap.add_argument("--version", help="default: the newest release")
    ap.add_argument("--installed", help="mod folder filled by the installer")
    ap.add_argument("--installed-sizes", help="file with '<size> <vp name>' lines")
    a = ap.parse_args()
    rel = releases()
    target = (a.id, a.version) if a.version else rel[-1]
    cur = files_of(*target)
    prev = json.load(open(OUT)) if os.path.exists(OUT) else {"archives": []}
    prev_by_sha = {x["sha256"]: x for x in prev["archives"]}

    measured = {}
    lines = []
    if a.installed:
        lines = ["%d %s" % (os.path.getsize(x), x) for x in glob.glob(os.path.join(a.installed, "fotg_*.vp"))]
    elif a.installed_sizes:
        lines = open(a.installed_sizes).read().splitlines()
    for l in lines:
        p = l.split()
        m = re.match(r"fotg_(.+?)(?:_\d+)?\.vp$", os.path.basename(p[-1])) if len(p) >= 2 and p[0].isdigit() else None
        if m:
            measured[m.group(1) + ".7z"] = measured.get(m.group(1) + ".7z", 0) + int(p[0])

    older = [r for r in rel if rkey(r) < rkey(target)]
    old_files = {r: files_of(*r) for r in older}
    legacy = {n: f["checksum"][1] for n, f in files_of(*LEGACY).items()}

    arcs = []
    for name, f in cur.items():
        if f.get("dest"):
            raise SystemExit("package %s unpacks into %s: the installer expects archives rooted at the mod folder" % (name, f["dest"]))
        lst = listing(f)
        sha = f["checksum"][1]
        accept = []
        for r in older:
            of = old_files[r].get(name)
            if of and of["checksum"][1] != sha and of["checksum"][1] not in accept and listing(of) == lst:
                accept.append(of["checksum"][1])
        e = dict(name=name, size=f["filesize"], sha256=sha, urls=f["urls"], unpacked=sum(s for s, _ in lst.values()))
        if accept:
            e["accept"] = accept
        same = next((prev_by_sha[h] for h in [sha] + accept if h in prev_by_sha), None)
        note = "unchanged since %s" % ("an older release" if accept else "the last manifest") if same else "CHANGED"
        if name in measured:
            e["installed"] = measured[name]
        elif same and same.get("installed"):
            e["installed"] = same["installed"]
        else:
            old = next((x for x in prev["archives"] if x["name"] == name and x.get("installed")), None)
            if old:
                e["installed"] = int(e["unpacked"] * old["installed"] / old["unpacked"] * 0.98)
                note += ", installed size ESTIMATED"
        arcs.append(e)
        print("%-14s %s" % (name, note))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as fo:
        json.dump(dict(mod=target[0], version=target[1], legacy=legacy, archives=arcs), fo, indent=1)
        fo.write("\n")
    print("%s %s: %d archives, %.2f GB download, %.2f GB installed -> %s" % (target[0], target[1], len(arcs), sum(x["size"] for x in arcs) / 1e9,
          sum(x.get("installed", x["unpacked"]) for x in arcs) / 1e9, os.path.normpath(OUT)))


if __name__ == "__main__":
    main()
