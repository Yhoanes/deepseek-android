package com.ds.ide.bridge;

import com.ds.ide.shell.ShizukuBridge;

/** Shizuku shell access exposed to the Web UI. */
public final class ShellBridge {

    private final ShizukuBridge shizuku;

    public ShellBridge(ShizukuBridge shizuku) {
        this.shizuku = shizuku;
    }

    public boolean available() {
        return shizuku.isAvailable();
    }
}
