#!/usr/bin/env bash
#
# 04-runtime.sh - assemble assets/runtime/{bin,certs,seed,bundled-skills} + assets/core-archive/.
#

log "[3/12] assemble assets/runtime + assets/core-archive"
mkdir -p "$RT/bin" "$RT/certs" "$RT/seed" "$RT/bundled-skills" "$STAGE_ASSETS/core-archive"

cp -f "$STAGED_CORE" "$STAGE_ASSETS/core-archive/deepseek-core.tar.zst"

# Native Android bridge tools & launchers (dsh-host, dsh, node, rish, xdg-open, chrome shims)
for tool in "$TOOLS_SRC_DIR"/*; do
    [ -e "$tool" ] || continue
    cp -f "$tool" "$RT/bin/"
done

if [ -d "$SKILLS_SRC_DIR" ]; then
    cp -r "$SKILLS_SRC_DIR/." "$RT/bundled-skills/"
fi

cp -f "$CERTS_DIR"/* "$RT/certs/"
cp -f "$SEED_DIR"/* "$RT/seed/"
chmod 755 "$RT/bin"/* 2>/dev/null || true
