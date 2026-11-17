package com.ds.ide.shell;

import android.content.Context;

import java.io.File;

/** Locates the bundled rish binary. */
public final class RishLocator {

    private final Context appContext;

    public RishLocator(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Prefers $PREFIX/bin/rish (installed by agy-adb-bridge Rust plugin when present)
     * and falls back to the bundled Shizuku connector at runtime/bin/rish.
     */
    public File rishBinary() {
        File files = appContext.getFilesDir();
        File[] candidates = new File[]{
                new File(files, "usr/bin/rish"),
                new File(files, "runtime/bin/rish"),
                new File(files, "bin/rish")
        };
        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                return candidate;
            }
        }
        return candidates[1];
    }
}
