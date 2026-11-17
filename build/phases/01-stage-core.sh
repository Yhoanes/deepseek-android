#!/usr/bin/env bash
#
# 01-stage-core.sh - stage & verify DeepSeek Harness ARM64 core archive (deepseek-core.tar.zst).
#

log "[1/12] stage & verify DeepSeek Harness ARM64 core archive (.tar.zst)"
[ -f "$CORE_SRC" ] || die "DeepSeek core archive not found: $CORE_SRC"
zstd -q -t "$CORE_SRC" || die "DeepSeek core archive failed Zstandard integrity check: $CORE_SRC"

cp -f "$CORE_SRC" "$STAGED_CORE"

if [ "$DEV_MODE" -eq 1 ] && [ "${#PKG}" -eq "${#PKG_DEV}" ]; then
    log "  --dev: rewriting 10-byte prefix $PKG -> $PKG_DEV inside deepseek-core.tar.zst"
    python3 - "$STAGED_CORE" "$PKG" "$PKG_DEV" <<'PY'
import io, os, subprocess, sys, tarfile

tzst, src_pkg, dst_pkg = sys.argv[1], sys.argv[2].encode(), sys.argv[3].encode()
assert len(src_pkg) == len(dst_pkg)
tmp_tzst = tzst + ".devtmp"

dec = subprocess.Popen(["zstd", "-dc", tzst], stdout=subprocess.PIPE)
enc = subprocess.Popen(["zstd", "-10", "-T0", "-f", "-o", tmp_tzst], stdin=subprocess.PIPE)

with tarfile.open(mode="r|", fileobj=dec.stdout) as tin, tarfile.open(mode="w|", fileobj=enc.stdin, format=tarfile.PAX_FORMAT) as tout:
    for m in tin:
        if m.isfile() and (m.name.startswith("bin/") or m.name.startswith("lib/") or m.name.endswith(".so") or m.name.endswith(".node")):
            raw = tin.extractfile(m).read()
            if src_pkg in raw:
                raw = raw.replace(src_pkg, dst_pkg)
            m.size = len(raw)
            tout.addfile(m, io.BytesIO(raw))
        elif m.isfile():
            tout.addfile(m, tin.extractfile(m))
        else:
            tout.addfile(m)

enc.stdin.close()
if dec.wait() != 0 or enc.wait() != 0:
    sys.exit("zstd transcode failed during --dev rewrite")
os.replace(tmp_tzst, tzst)
PY
fi

# Verify archive contains ELF64 ARM64 node.elf, glibc loader, and @deepseek-ai/dsh entry
python3 - "$STAGED_CORE" <<'PY'
import subprocess, sys, tarfile

tzst = sys.argv[1]
required = {
    "bin/node.elf",
    "lib/ld-linux-aarch64.so.1",
    "lib/libc.so.6",
    "lib/libstdc++.so.6",
    "lib/libutil.so.1",
    "dsh-app/android.patch.yml",
    "dsh-app/node_modules/@deepseek-ai/dsh/lib/bin.js",
}
found = set()
proc = subprocess.Popen(["zstd", "-dc", tzst], stdout=subprocess.PIPE)
try:
    with tarfile.open(mode="r|", fileobj=proc.stdout) as tf:
        for m in tf:
            if m.name in required:
                found.add(m.name)
                if m.name == "bin/node.elf":
                    hdr = tf.extractfile(m).read(64)
                    if hdr[:4] != b"\x7fELF" or hdr[4] != 2 or hdr[18] != 0xb7:
                        sys.exit("bin/node.elf is not ELF64 AArch64")
                if found == required:
                    break
finally:
    proc.terminate()
    proc.wait()
missing = required - found
if missing:
    sys.exit("deepseek-core.tar.zst missing required entries: %r" % sorted(missing))
print("  verified deepseek-core.tar.zst required entries & ELF64 AArch64 header")
PY
