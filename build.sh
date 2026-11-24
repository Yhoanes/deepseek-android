#!/usr/bin/env bash
#
# build.sh - DeepSeek for Android (com.ds.ide) zero-Gradle build orchestrator.
#
#   core/deepseek-core.tar.gz -> assets/core-archive/deepseek-core.tar.gz
#   core/{tools,certs,seed,bundled-skills} -> assets/runtime/* + manifest.json
#   web/ -> assets/web/{__ds_android_boot.js,__ds_plugins_catalog.json,deepseek-icon.png}
#   env/{pkg,overlay,bootstrap} -> assets/env/*
#   gen_android_config + aapt (R.java) -> javac -> d8 -> aapt -> zip (-0 archives)
#   -> zipalign -> apksigner -> dist/DeepSeek-v0.2.2-r4.apk -> apksigner verify
#
# Flags:
#   --dev   build side-by-side dev edition (com.ds.dev, DeepSeek Dev, port 45188)
#
set -euo pipefail

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PROJECT

. "$PROJECT/build/lib/log.sh"
. "$PROJECT/build/lib/flags.sh"
parse_args "$@"

. "$PROJECT/build/lib/paths.sh"
setup_paths

. "$PROJECT/build/lib/prereqs.sh"
check_tools

clean_build

shopt -s nullglob
for p in "$BUILD_LIB_DIR"/../phases/*.sh; do
    . "$p"
done
shopt -u nullglob
