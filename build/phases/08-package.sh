#!/usr/bin/env bash
#
# 08-package.sh - package resources + assets + classes.dex + native Rust .so + stored .tar.zst archives.
#

log "[10/12] aapt: package unaligned.apk + classes.dex + lib/arm64-v8a/*.so + stored .tar.zst archives"

# Hold .tar.zst archives out of aapt -A so they are injected strictly as ZIP_STORED (-0) for zero-copy mmap
BOOTSTRAP_HOLD="$BUILD_DIR/bootstrap.tar.zst.hold"
BOOTSTRAP_ASSET_REL="assets/env/bootstrap/bootstrap.tar.zst"
HAS_BOOTSTRAP=0
if [ -f "$STAGE_APK/$BOOTSTRAP_ASSET_REL" ]; then
    mv -f "$STAGE_APK/$BOOTSTRAP_ASSET_REL" "$BOOTSTRAP_HOLD"
    HAS_BOOTSTRAP=1
fi

CORE_HOLD="$BUILD_DIR/deepseek-core.tar.zst.hold"
CORE_ASSET_REL="assets/core-archive/deepseek-core.tar.zst"
HAS_CORE=0
if [ -f "$STAGE_APK/$CORE_ASSET_REL" ]; then
    mv -f "$STAGE_APK/$CORE_ASSET_REL" "$CORE_HOLD"
    HAS_CORE=1
fi

aapt package -f \
    --min-sdk-version "$MIN_API" \
    --target-sdk-version "$TARGET_SDK" \
    -M "$TARGET_MANIFEST" \
    -S "$TARGET_RES" \
    -I "$ANDROID_JAR" \
    -A "$STAGE_ASSETS" \
    -F "$BUILD_DIR/unaligned.apk"

# Inject classes.dex (and any overflow classesN.dex) at APK root
( cd "$DEX_DIR" && zip -q -u "$BUILD_DIR/unaligned.apk" classes*.dex )

# Inject native ARM64 shared libraries (libzstd.so + libds_bootstrap.so) as ZIP_STORED (-0)
if [ -d "$STAGE_APK/lib/arm64-v8a" ]; then
    ( cd "$STAGE_APK" && zip -q -0 -u "$BUILD_DIR/unaligned.apk" lib/arm64-v8a/*.so )
fi

# Restore and inject Zstandard archives as ZIP_STORED (-0) for direct Rust mmap slicing
if [ "$HAS_BOOTSTRAP" -eq 1 ]; then
    mkdir -p "$(dirname "$STAGE_APK/$BOOTSTRAP_ASSET_REL")"
    mv -f "$BOOTSTRAP_HOLD" "$STAGE_APK/$BOOTSTRAP_ASSET_REL"
    ( cd "$STAGE_APK" && zip -q -0 -u "$BUILD_DIR/unaligned.apk" "$BOOTSTRAP_ASSET_REL" )
fi

if [ "$HAS_CORE" -eq 1 ]; then
    mkdir -p "$(dirname "$STAGE_APK/$CORE_ASSET_REL")"
    mv -f "$CORE_HOLD" "$STAGE_APK/$CORE_ASSET_REL"
    ( cd "$STAGE_APK" && zip -q -0 -u "$BUILD_DIR/unaligned.apk" "$CORE_ASSET_REL" )
fi
