#!/usr/bin/env bash
#
# 09b-verify.sh - verify APK cryptographic signature, native Rust ARM64 JNI libs, and Zstd payload.
#

log "[12/12] apksigner verify + native Rust & Zstd payload integrity check"
CERT_INFO="$(apksigner verify --verbose --print-certs "$OUT_APK")"
printf '%s\n' "$CERT_INFO"
if [ "${DEV_MODE:-0}" -eq 0 ] && [ -n "${DS_RELEASE_KEYSTORE:-}" ]; then
    printf '%s\n' "$CERT_INFO" | grep -q "O=DeepSeek Android" \
        || die "RELEASE APK is not signed with dedicated DeepSeek Android certificate"
fi

python3 - "$OUT_APK" <<'PY'
import hashlib, json, subprocess, sys, zipfile

apk = sys.argv[1]
required = [
    "AndroidManifest.xml",
    "classes.dex",
    "resources.arsc",
    "lib/arm64-v8a/libzstd.so",
    "lib/arm64-v8a/libds_bootstrap.so",
    "assets/core-archive/deepseek-core.tar.zst",
    "assets/runtime/bin/dsh-host",
    "assets/runtime/bin/dsh",
    "assets/runtime/bin/node",
    "assets/runtime/bin/rish",
    "assets/runtime/bin/rish_shizuku.dex",
    "assets/runtime/bin/xdg-open",
    "assets/runtime/certs/ca-certificates.crt",
    "assets/runtime/seed/AGENTS.md",
    "assets/runtime/manifest.json",
    "assets/web/__ds_android_boot.js",
    "assets/web/__ds_plugins_catalog.json",
    "assets/web/deepseek-icon.png",
    "assets/env/bootstrap/bootstrap.tar.zst",
    "assets/env/overlay/bin/ds-box",
    "assets/env/overlay/bin/rg",
    "assets/env/overlay/lib/libpcre2-8.so",
]

ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"

with zipfile.ZipFile(apk, "r") as z:
    names = set(z.namelist())
    missing = [r for r in required if r not in names]
    if missing:
        sys.exit("[verify] FAIL missing required APK entries: %r" % missing)

    forbidden = [n for n in names if n in (
        "assets/env/bootstrap/bootstrap.tar",
        "assets/env/bootstrap/bootstrap.tar.gz",
        "assets/core-archive/deepseek-core.tar",
        "assets/core-archive/deepseek-core.tar.gz",
    )]
    if forbidden:
        sys.exit("[verify] FAIL obsolete/mangled tar entries in APK: %r" % forbidden)

    # 1. Verify native ARM64 JNI libraries (ELF64 AArch64)
    for so_name in ("lib/arm64-v8a/libzstd.so", "lib/arm64-v8a/libds_bootstrap.so"):
        so_hdr = z.read(so_name)[:64]
        if so_hdr[:4] != b"\x7fELF" or so_hdr[4] != 2 or so_hdr[18] != 0xb7:
            sys.exit("[verify] FAIL %s is not ELF64 AArch64" % so_name)
    print("[verify] lib/arm64-v8a/{libzstd.so,libds_bootstrap.so} ELF64 AArch64 ok")

    # 2. Verify bootstrap.tar.zst ZIP_STORED + Zstd magic & stream integrity
    binfo = z.getinfo("assets/env/bootstrap/bootstrap.tar.zst")
    if binfo.compress_type != zipfile.ZIP_STORED:
        sys.exit("[verify] FAIL bootstrap.tar.zst must be ZIP_STORED (got %d)" % binfo.compress_type)
    bdata = z.read("assets/env/bootstrap/bootstrap.tar.zst")
    if bdata[:4] != ZSTD_MAGIC:
        sys.exit("[verify] FAIL bootstrap.tar.zst missing Zstandard magic")
    p = subprocess.run(["zstd", "-q", "-t"], input=bdata)
    if p.returncode != 0:
        sys.exit("[verify] FAIL bootstrap.tar.zst failed zstd -t")
    print("[verify] bootstrap.tar.zst ZIP_STORED + zstd ok (%d bytes)" % len(bdata))

    # 3. Verify deepseek-core.tar.zst ZIP_STORED + Zstd magic + manifest SHA-256
    cinfo = z.getinfo("assets/core-archive/deepseek-core.tar.zst")
    if cinfo.compress_type != zipfile.ZIP_STORED:
        sys.exit("[verify] FAIL deepseek-core.tar.zst must be ZIP_STORED (got %d)" % cinfo.compress_type)
    core_sha = hashlib.sha256()
    with z.open("assets/core-archive/deepseek-core.tar.zst") as cf:
        first = cf.read(1 << 20)
        if first[:4] != ZSTD_MAGIC:
            sys.exit("[verify] FAIL deepseek-core.tar.zst missing Zstandard magic")
        core_sha.update(first)
        for chunk in iter(lambda: cf.read(1 << 20), b""):
            core_sha.update(chunk)

    manifest = json.loads(z.read("assets/runtime/manifest.json").decode("utf-8"))
    if core_sha.hexdigest() != manifest.get("core_archive_sha256", ""):
        sys.exit("[verify] FAIL core_archive_sha256 mismatch: %s != %s" % (
            core_sha.hexdigest(), manifest.get("core_archive_sha256", "")))
    print("[verify] deepseek-core.tar.zst ZIP_STORED + SHA-256 ok (%d bytes)" % cinfo.file_size)

    # 4. Verify runtime/manifest.json SHA-256 matches packaged runtime shims
    for rel in ("bin/dsh-host", "bin/dsh", "bin/rish", "certs/ca-certificates.crt", "seed/AGENTS.md"):
        actual = hashlib.sha256(z.read("assets/runtime/" + rel)).hexdigest()
        expected = manifest["files"].get(rel)
        if actual != expected:
            sys.exit("[verify] FAIL runtime/%s sha256 mismatch: %s != %s" % (rel, actual, expected))
    print("[verify] runtime/manifest.json SHA-256 integrity ok (%d files)" % len(manifest["files"]))
PY

# Clean intermediate build scratch while preserving build/{lib,phases,tools}
rm -rf "$BUILD_DIR/staging" "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/dex" \
       "$BUILD_DIR/apk" "$BUILD_DIR/res" "$BUILD_DIR/AndroidManifest.xml" \
       "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk" \
       "$BUILD_DIR/bootstrap.tar.zst.hold" "$BUILD_DIR/deepseek-core.tar.zst.hold"

log "BUILD OK -> $OUT_APK"
ls -lh "$OUT_APK"
if [ -n "${OUT_APK_ALIAS:-}" ] && [ -f "$OUT_APK_ALIAS" ] && [ "$OUT_APK_ALIAS" != "$OUT_APK" ]; then
    ls -lh "$OUT_APK_ALIAS"
fi
