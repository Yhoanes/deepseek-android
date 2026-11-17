# DeepSeek Harness Android Agent Rules & Environment Guidelines

> [!NOTE]
> You are running inside **DeepSeek for Android (`com.ds.ide`)**, a native Android ARM64 (`aarch64`) standalone port of **DeepSeek Harness Desktop (`dsh`)**.

---

## 1. Platform & Filesystem Paths

- **Architecture:** Native Android Linux kernel (`aarch64` / ARMv8-A+).
- **App Sandbox Root (`$HOME`):** `/data/user/0/com.ds.ide/files`
- **Bionic Userspace (`$PREFIX`):** `/data/user/0/com.ds.ide/files/usr`
- **User Binaries:** `/data/user/0/com.ds.ide/files/usr/bin` (always keep in `$PATH`)
- **DeepSeek Harness Home (`$DSH_HOME`):** `/data/user/0/com.ds.ide/files/.dsh`
- **Shared Android Storage:** `/storage/emulated/0` (Default workspace: `/storage/emulated/0/Documents/DeepSeek`; quick symlinks in `~/Storage` and `~/Documents`)

---

## 2. Shell & Execution Rules

- **No Desktop Sandboxes (`bwrap` / Landlock / Docker):** Android enforces SELinux app sandboxing; never wrap commands in `bwrap` or `docker`. Run commands directly in `$PREFIX/bin/bash`.
- **Always Quote Paths:** Shared storage paths often contain spaces.
- **Git on Android Shared Storage:**
  ```bash
  export GIT_CONFIG_NOSYSTEM=1
  git config --global --add safe.directory "*"
  ```

---

## 3. Native Package Manager (`pkg`)

Install compilers and developer tools natively into `$PREFIX` (do NOT pass `-y`):
```bash
pkg update
pkg install git python clang rust nodejs ffmpeg
ds-fix-paths
```

---

## 4. Elevated ADB Shell via Shizuku (`rish`)

When Shizuku or Wireless ADB Bridge is active, execute privileged `uid=2000(shell)` commands via:
```bash
rish -c "pm list packages -3"
rish -c "pm install -r /storage/emulated/0/.../app.apk"
rish -c "logcat -d -t 150"
```

---

## 5. Browser, Notifications & Background Daemon

- **Open URLs in Android Chrome Custom Tabs:** `xdg-open "https://example.com"`
- **Android Status-Bar Notifications:** `ds-notify post -t "Build Complete" -m "All checks passed"`
- **Background Daemon:** `ds-daemon add --name "Task" --interval 30m --command "..."`
