#!/usr/bin/env bash
#
# 05b-env.sh - stage Bionic Linux userspace toolchain (assets/env/{pkg,overlay,bootstrap}).
#

log "[6/12] stage assets/env (Bionic Linux toolchain + ds-box + rg + bootstrap.tar.zst)"
ENV_ASSETS="$STAGE_ASSETS/env"
export ENV_ASSETS

ENV_ROOT=""
if [ -d "$ENV_DIST/pkg" ] || [ -d "$ENV_DIST/overlay" ] \
        || [ -f "$ENV_DIST/bootstrap/bootstrap.tar.zst" ] \
        || [ -f "$ENV_DIST/bootstrap/bootstrap.tar" ]; then
    ENV_ROOT="$ENV_DIST"
    log "  source: env/dist/env"
elif [ -d "$ENV_DIR/pkg" ] || [ -d "$ENV_DIR/overlay" ] \
        || [ -f "$ENV_DIR/bootstrap/bootstrap.tar.zst" ] \
        || [ -f "$ENV_DIR/bootstrap/bootstrap.tar" ]; then
    ENV_ROOT="$ENV_DIR"
    log "  source: env/{pkg,overlay,bootstrap}"
else
    die "env/ Bionic userspace directory missing: $ENV_DIR"
fi

[ -d "$ENV_ROOT/pkg" ]                        || die "env pkg missing: $ENV_ROOT/pkg"
[ -d "$ENV_ROOT/overlay" ]                    || die "env overlay missing: $ENV_ROOT/overlay"
[ -f "$ENV_ROOT/overlay/bin/ds-box" ]         || die "compiled ds-box missing: $ENV_ROOT/overlay/bin/ds-box"
[ -f "$ENV_ROOT/overlay/bin/rg" ]             || die "ripgrep (rg) missing: $ENV_ROOT/overlay/bin/rg"
[ -f "$ENV_ROOT/overlay/lib/libpcre2-8.so" ]  || die "libpcre2-8.so missing: $ENV_ROOT/overlay/lib/libpcre2-8.so"

ENV_TZST="$ENV_ROOT/bootstrap/bootstrap.tar.zst"
ENV_TAR="$ENV_ROOT/bootstrap/bootstrap.tar"
if [ -f "$ENV_TZST" ]; then
    zstd -q -t "$ENV_TZST" || die "env bootstrap failed Zstandard check: $ENV_TZST"
    BOOTSTRAP_SRC="$ENV_TZST"
    BOOTSTRAP_ZST=1
elif [ -f "$ENV_TAR" ] && file -b "$ENV_TAR" | grep -q 'tar archive'; then
    BOOTSTRAP_SRC="$ENV_TAR"
    BOOTSTRAP_ZST=0
else
    die "env bootstrap missing: $ENV_ROOT/bootstrap/bootstrap.{tar.zst,tar}"
fi

rm -rf "$ENV_ASSETS"
mkdir -p "$ENV_ASSETS/bootstrap" "$ENV_ASSETS/pkg" "$ENV_ASSETS/overlay"
cp -r "$ENV_ROOT/pkg/."     "$ENV_ASSETS/pkg/"
cp -r "$ENV_ROOT/overlay/." "$ENV_ASSETS/overlay/"

BOOTSTRAP_STAGED="$ENV_ASSETS/bootstrap/bootstrap.tar.zst"
if [ "$BOOTSTRAP_ZST" -eq 1 ]; then
    cp -f "$BOOTSTRAP_SRC" "$BOOTSTRAP_STAGED"
else
    log "  source bootstrap is plain tar; zstd -19 -> bootstrap.tar.zst"
    zstd -19 -T0 -f "$BOOTSTRAP_SRC" -o "$BOOTSTRAP_STAGED"
fi

if [ "$DEV_MODE" -eq 1 ] && [ "${#PKG}" -eq "${#PKG_DEV}" ]; then
    log "  --dev: rewriting 10-byte prefix $PKG -> $PKG_DEV in staged env"
    python3 - "$ENV_ASSETS" "$PKG" "$PKG_DEV" <<'PY'
import io, os, subprocess, sys, tarfile

env_assets, src_pkg, dst_pkg = sys.argv[1], sys.argv[2].encode(), sys.argv[3].encode()
assert len(src_pkg) == len(dst_pkg)

for dp, _, fns in os.walk(os.path.join(env_assets, "overlay")):
    for fn in fns:
        p = os.path.join(dp, fn)
        with open(p, "rb") as f:
            data = f.read()
        if src_pkg in data:
            with open(p, "wb") as f:
                f.write(data.replace(src_pkg, dst_pkg))

tzst = os.path.join(env_assets, "bootstrap", "bootstrap.tar.zst")
tmp_tzst = tzst + ".devtmp"
dec = subprocess.Popen(["zstd", "-dc", tzst], stdout=subprocess.PIPE)
enc = subprocess.Popen(["zstd", "-12", "-T0", "-f", "-o", tmp_tzst], stdin=subprocess.PIPE)

with tarfile.open(mode="r|", fileobj=dec.stdout) as tin, tarfile.open(mode="w|", fileobj=enc.stdin, format=tarfile.PAX_FORMAT) as tout:
    for m in tin:
        if m.issym() or m.islnk():
            if src_pkg.decode() in m.linkname:
                m.linkname = m.linkname.replace(src_pkg.decode(), dst_pkg.decode())
            tout.addfile(m)
        elif m.isfile():
            raw = tin.extractfile(m).read()
            if src_pkg in raw:
                raw = raw.replace(src_pkg, dst_pkg)
            m.size = len(raw)
            tout.addfile(m, io.BytesIO(raw))
        else:
            tout.addfile(m)

enc.stdin.close()
if dec.wait() != 0 or enc.wait() != 0:
    sys.exit("zstd transcode failed on bootstrap.tar.zst")
os.replace(tmp_tzst, tzst)
PY
else
    for _tree in pkg overlay; do
        diff -r "$ENV_ROOT/$_tree" "$ENV_ASSETS/$_tree" >/dev/null 2>&1 \
            || die "invariant failed: assets/env/$_tree != $ENV_ROOT/$_tree"
    done
fi

zstd -q -t "$BOOTSTRAP_STAGED" || die "staged bootstrap zstd integrity failed: $BOOTSTRAP_STAGED"
log "  staged assets/env ready (bootstrap.tar.zst zstd ok)"
