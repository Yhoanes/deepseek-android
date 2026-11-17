#!/usr/bin/env bash
#
# 04c-rt-manifest.sh - generate assets/runtime/manifest.json (SHA-256 of every
# bundled runtime file + core_archive_sha256) for CoreInstaller / NativeBootstrap checks.
#

log "[4/12] generate assets/runtime/manifest.json"
python3 - "$RT" "$STAGED_CORE" <<'PY'
import hashlib, json, os, sys, time

rt = os.path.abspath(sys.argv[1])
core_archive = os.path.abspath(sys.argv[2])

def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()

files = {}
for dp, _, fns in os.walk(rt):
    for fn in sorted(fns):
        full = os.path.join(dp, fn)
        rel = os.path.relpath(full, rt).replace(os.sep, "/")
        if rel == "manifest.json":
            continue
        files[rel] = sha(full)

out = os.path.join(rt, "manifest.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump({
        "schema": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "core_archive_sha256": sha(core_archive) if os.path.isfile(core_archive) else "",
        "files": files
    }, f, indent=2, sort_keys=True)
    f.write("\n")

print("[runtime.manifest] files=%d -> %s" % (len(files), out))
PY
