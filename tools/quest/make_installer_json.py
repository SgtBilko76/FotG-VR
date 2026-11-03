#!/usr/bin/env python3
"""Write android/app/src/main/assets/fotg_data.json, the download list of the in-app installer.

    python3 tools/quest/make_installer_json.py [--version 1.1.1] [--dl FotG-data/_dl] [--installed <mod dir>]

Reads the mod's package list from FSNebula (names, sizes, sha256, mirror URLs). The unpacked
sizes come from the downloaded archives in --dl (7-Zip needed). With --installed (a folder the
installer filled, e.g. by the PC test) the size of each archive's fotg_<name>*.vp files is stored
too, so the app can check the free space it really needs.
"""
import argparse, glob, json, os, shutil, subprocess, urllib.request

API = "https://api.fsnebula.org/api/1/mod/json/FotG_R/%s"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "android", "app", "src", "main", "assets", "fotg_data.json")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="1.1.1")
    ap.add_argument("--dl", default="FotG-data/_dl")
    ap.add_argument("--installed")
    ap.add_argument("--7z", dest="sz", default=shutil.which("7zz") or shutil.which("7z") or os.path.expanduser("~/tools/7zip/7zz"))
    a = ap.parse_args()
    mod = json.load(urllib.request.urlopen(API % a.version))["mod"]
    arcs = []
    for p in mod["packages"]:
        for f in p["files"]:
            if f.get("dest"):
                raise SystemExit("package %s unpacks into %s: the installer expects archives rooted at the mod folder" % (f["filename"], f["dest"]))
            last = subprocess.check_output([a.sz, "l", os.path.join(a.dl, f["filename"])]).decode().strip().splitlines()[-1]
            e = dict(name=f["filename"], size=f["filesize"], sha256=f["checksum"][1], urls=f["urls"], unpacked=int(last.split()[2]))
            if a.installed:
                stem = f["filename"][:-3]
                e["installed"] = sum(os.path.getsize(x) for x in glob.glob(os.path.join(a.installed, "fotg_%s.vp" % stem)) + glob.glob(os.path.join(a.installed, "fotg_%s_*.vp" % stem)))
            arcs.append(e)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as fo:
        json.dump(dict(mod="FotG_R", version=a.version, archives=arcs), fo, indent=1)
        fo.write("\n")
    print("%d archives, %.2f GB download, %.2f GB installed -> %s" % (len(arcs), sum(x["size"] for x in arcs) / 1e9,
          sum(x.get("installed", x["unpacked"]) for x in arcs) / 1e9, os.path.normpath(OUT)))


if __name__ == "__main__":
    main()
