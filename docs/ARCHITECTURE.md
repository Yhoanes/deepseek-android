# Architecture Specification: DeepSeek for Android (`com.ds.ide`)

1. **Layer 1 — Android Java 8 Host & Native Rust Bootstrap (`app/src/main/` + `tools/rust-bootstrap/`):**
   - `targetSdkVersion 28` (`minSdkVersion 28`) permits direct kernel `execve()` of `runtime/bin/node.elf` and Bionic userspace binaries (`usr/bin/*`) without Android 10+ `W^X` SELinux blocks.
   - `libds_bootstrap.so` (zero-dependency Rust JNI engine + `libzstd.so`) `mmap`s `ZIP_STORED` `.tar.zst` archives directly out of the installed APK, verifies SHA-256 in memory, extracts `deepseek-core.tar.zst` and `bootstrap.tar.zst` in ~2–3s with symlink-safe `O_NOFOLLOW`/unlink guards, and rewrites 31-byte ELF prefixes in place.
   - `CoreServerService` + `CoreProcessSupervisor` supervise `dsh-host`, exchange the one-time startup token in `CoreReadyProbe` for a 30-day signed `dsh-auth-*` session cookie, pre-fetch root HTML, and wake `MainActivity` via event-driven callbacks.

2. **Layer 2 — Capability-Hardened WebView & Desktop-Parity OAuth (`web/` + `com.ds.ide.ui.webview`):**
   - `LocalAssetServer` injects a frozen `window.Android` facade, a per-session 256-bit `bridgeAuthToken`, and `/__ds_android_boot.js` into the authenticated root document while passing Cordis combo bundles (`/plugins/??*`), static Vite assets, `/oauth/callback`, and WebSocket streams directly to loopback (`127.0.0.1:45187`).
   - `OAuthManager` + `OAuthCallbackActivity` (`deepseek://oauth-success`) execute full PKCE login with `platform.deepseek.com` via Chrome Custom Tabs and return focus seamlessly to the running workspace.

3. **Layer 3 — Direct-Execve Node v24 + Isolated `glibc 2.44` (`core/deepseek-core.tar.zst`):**
   - `bin/node.elf` carries `PT_INTERP=/data/data/com.ds.ide/files/runtime/lib/ld-linux-aarch64.so.1` and `DT_RPATH=$ORIGIN/../lib` with V8 `NODE_COMPILE_CACHE` persistence, running `node-pty`, `system.node`, `node:sqlite`, `sharp`, `koffi`, and `sherpa-onnx` SenseVoice offline transcription natively on ARM64.
   - `dsh-app/android.patch.yml` configures `@deepseek-ai/dsh-bash-local` and `@deepseek-ai/dsh-host-directory-picker-browse`.

4. **Layer 4 — 10-Byte Parity Bionic Userspace & Live MCP HMR (`env/` + `~/.dsh/`):**
   - `len("com.ds.ide") == len("com.termux") == 10` enables zero-PRoot native execution of `bash`, `git`, `rg`, `ds-box` (`v0.3.1` Rust multicall `pkg`, `ds-daemon`, `ds-fix-paths`, `ds-notify`), `clang`, `rust`, `python`, and `rish` (`agy-adb-bridge` / Shizuku with 2.5s watchdog).
   - `EngineBridge` hot-mounts 1-Tap installed ARM64 Rust MCP servers into `~/.dsh/cordis.patch.yml` and skills into `~/.dsh/skills/`, watched live by `dsh-hmr` and `dsh-skill-filesystem`.
