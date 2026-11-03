#!/usr/bin/env python3
"""Download Fate of the Galaxy from Nebula (the Knossos mod server), unpack it and
optionally copy it to the headset (Meta Quest or PICO, connected by USB with adb).

    python3 tools/quest/get_fotg_data.py [--version 1.1.1] [--dir FotG-data] [--push [--serial <id>]]

Needs 7-Zip (7zz / 7z / 7za on PATH, or --7z <path>) and, for --push, adb.
Result: <dir>/FotG_R/data/... ; on the headset /sdcard/FotG/FotG_R/data/...
"""
import argparse, hashlib, json, os, shutil, subprocess, sys, urllib.request

MOD = "FotG_R"
API = "https://api.fsnebula.org/api/1/mod/json/%s/%s"
REPO = "https://fsnebula.org/storage/repo_minimal.json"
QUEST_DIR = "/sdcard/FotG/" + MOD


def latest_version():
    with urllib.request.urlopen(REPO) as r:
        mods = json.load(r)["mods"]
    vers = [m["version"] for m in mods if m.get("id") == MOD]
    return max(vers, key=lambda v: [int(x) for x in v.split(".") if x.isdigit()])


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def download(url, out):
    subprocess.check_call(["curl", "-fSL", "--retry", "5", "-C", "-", "-o", out, url])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", help="mod version (default: newest on Nebula)")
    ap.add_argument("--dir", default="FotG-data")
    ap.add_argument("--7z", dest="sevenzip")
    ap.add_argument("--push", action="store_true", help="copy to the headset with adb")
    ap.add_argument("--serial", help="adb serial of the headset (only needed with several devices)")
    ap.add_argument("--adb", default=shutil.which("adb") or os.path.expanduser("~/Android/Sdk/platform-tools/adb"))
    a = ap.parse_args()

    sz = a.sevenzip or shutil.which("7zz") or shutil.which("7z") or shutil.which("7za")
    if not sz:
        sys.exit("7-Zip not found: install p7zip/7zip or pass --7z <path>")
    ver = a.version or latest_version()
    print("Fate of the Galaxy", ver)
    with urllib.request.urlopen(API % (MOD, ver)) as r:
        mod = json.load(r)["mod"]

    dl = os.path.join(a.dir, "_dl")
    out = os.path.join(a.dir, MOD)
    os.makedirs(dl, exist_ok=True)
    for p in mod["packages"]:
        for f in p["files"]:
            path = os.path.join(dl, f["filename"])
            if not (os.path.exists(path) and os.path.getsize(path) == f["filesize"]):
                for u in f["urls"]:
                    try:
                        download(u, path)
                        break
                    except subprocess.CalledProcessError:
                        print("retrying with the next mirror")
            if sha256(path) != f["checksum"][1]:
                sys.exit("checksum mismatch: " + path + " (delete it and run again)")
            print("ok", f["filename"])
            subprocess.check_call([sz, "x", "-y", "-bso0", "-bsp0", "-o" + out, path])

    if a.push:
        adb = [a.adb] + (["-s", a.serial] if a.serial else [])
        need = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(out) for f in fs)
        df = subprocess.check_output(adb + ["shell", "df", "-k", "/sdcard"]).decode().split("\n")
        free = int([l for l in df if l.strip()][-1].split()[3]) * 1024
        print("needs %.1f GB on the headset, %.1f GB free" % (need / 1e9, free / 1e9))
        if free < need:
            sys.exit("not enough free space on the headset")
        subprocess.check_call(adb + ["shell", "mkdir", "-p", QUEST_DIR + "/data"])
        data = os.path.join(out, "data")
        for d in sorted(os.listdir(data)):
            print("pushing", d)
            subprocess.check_call(adb + ["push", os.path.join(data, d), QUEST_DIR + "/data/"])
        n_pc = sum(len(fs) for _, _, fs in os.walk(out))
        n_q = subprocess.check_output(adb + ["shell", "find %s -type f | wc -l" % QUEST_DIR]).decode().strip()
        print("files on PC: %d, on the headset: %s" % (n_pc, n_q))
    print("done:", out)


if __name__ == "__main__":
    main()
