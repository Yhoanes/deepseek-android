---
name: android-environment
description: Native Android ARM64 package management (pkg), Shizuku elevated ADB shell (rish), shared storage paths, notifications (ds-notify), and background daemon (ds-daemon) inside DeepSeek for Android.
---

# Android ARM64 Environment Guide (DeepSeek for Android)

1. **Paths:** `$HOME` is `/data/user/0/com.ds.ide/files`, `$PREFIX` is `$HOME/usr`, and shared storage is `/storage/emulated/0` (`~/Storage`).
2. **Packages (`pkg`):** Run `pkg update` then `pkg install git python clang rust nodejs ffmpeg` (never pass `-y`), followed by `ds-fix-paths`.
3. **Elevated ADB (`rish`):** Run `rish -c "<cmd>"` for `pm install -r`, `am start`, `logcat -d`, and `dumpsys`.
4. **URLs & Notifications:** Open links in Chrome Custom Tabs via `xdg-open "<url>"` and send Android notifications via `ds-notify post -t "Title" -m "Message"`.
