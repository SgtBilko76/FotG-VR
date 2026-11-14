#!/usr/bin/env python3
"""Download Fate of the Galaxy from Nebula (the Knossos mod server), unpack it and
optionally copy it to the headset (Meta Quest or PICO, connected by USB with adb).

    python3 tools/quest/get_fotg_data.py [--version 2.0.2] [--dir FotG-data] [--push [--serial <id>]]

Needs 7-Zip (7zz / 7z / 7za on PATH, or --7z <path>) and, for --push, adb.
Result: <dir>/FotG_R/data/... ; on the headset /sdcard/FotG/FotG_R/data/...
"""
import argparse, hashlib, json, os, shutil, subprocess, sys, urllib.request

MOD = "FotG_R"                 # folder name on the headset (the app's -mod), kept since 1.1.1
NEBULA_IDS = ("FotG_R", "FotG_2")  # Nebula mod ids: FotG_R up to 1.1.3, FotG_2 from 2.0.1 on
API = "https://api.fsnebula.org/api/1/mod/json/%s/%s"
REPO = "https://fsnebula.org/storage/repo_minimal.json"
QUEST_DIR = "/sdcard/FotG/" + MOD


def latest_version():
    """(Nebula id, version) of the newest release."""
    with urllib.request.urlopen(REPO) as r:
        mods = json.load(r)["mods"]
    rel = [(m["id"], m["version"]) for m in mods if m.get("id") in NEBULA_IDS]
    return max(rel, key=lambda r: (NEBULA_IDS.index(r[0]), [int(x) for x in r[1].split(".") if x.isdigit()]))


def nebula_id(ver):
    return "FotG_R" if int(ver.split(".")[0]) < 2 else "FotG_2"


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
    nid, ver = (nebula_id(a.version), a.version) if a.version else latest_version()
    print("Fate of the Galaxy", ver, "(" + nid + ")")
    with urllib.request.urlopen(API % (nid, ver)) as r:
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
        need = int(4.6e9)  # packed, with the halved textures
        df = subprocess.check_output(adb + ["shell", "df", "-k", "/sdcard"]).decode().split("\n")
        free = int([l for l in df if l.strip()][-1].split()[3]) * 1024
        print("needs %.1f GB on the headset, %.1f GB free" % (need / 1e9, free / 1e9))
        if free < need:
            sys.exit("not enough free space on the headset")
        # pack into .vp archives first: thousands of loose files on /sdcard (FUSE) load slowly
        vpdir = os.path.join(a.dir, "vp")
        here = os.path.dirname(os.path.abspath(__file__))
        if not any(f.endswith(".vp") for f in (os.listdir(vpdir) if os.path.isdir(vpdir) else [])):
            # halve the big ship / planet textures (8.7 -> 4.2 GB; cockpits stay full size)
            small = out.rstrip("/") + "_small"
            subprocess.check_call([sys.executable, os.path.join(here, "shrink_textures.py"), out, small])
            subprocess.check_call([sys.executable, os.path.join(here, "pack_vp.py"), small, vpdir, "--prefix", "fotg"])
        subprocess.check_call(adb + ["shell", "mkdir", "-p", QUEST_DIR])
        # loose files from an older install would override the archives; set them aside
        subprocess.call(adb + ["shell", "[ -d %s/data ] && rm -rf %s_loose_old && mv %s/data %s_loose_old" % ((QUEST_DIR,) * 4)])
        vps = sorted(f for f in os.listdir(vpdir) if f.endswith(".vp"))
        for f in vps:
            print("pushing", f)
            subprocess.check_call(adb + ["push", os.path.join(vpdir, f), QUEST_DIR + "/"])
        n_q = subprocess.check_output(adb + ["shell", "ls %s/*.vp | wc -l" % QUEST_DIR]).decode().strip()
        print("archives on PC: %d, on the headset: %s" % (len(vps), n_q))
    print("done:", out)


if __name__ == "__main__":
    main()
