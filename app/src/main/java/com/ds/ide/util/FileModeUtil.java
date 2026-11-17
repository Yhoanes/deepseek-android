package com.ds.ide.util;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.ds.ide.install.ChmodRunner;

import java.io.File;

/** Applies unix modes/exec bits to extracted runtime files. */
public final class FileModeUtil {

    private static final String TAG = "FileModeUtil";

    private FileModeUtil() {
    }

    /** RuntimeManager semantics: readable + executable, no chmod syscall. */
    public static void recursiveSetReadableExecutable(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            Log.w(TAG, "makeBinExecutable: cannot list " + dir);
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                recursiveSetReadableExecutable(file);
                continue;
            }
            try {
                Os.chmod(file.getAbsolutePath(), 0755);
            } catch (ErrnoException e) {
                file.setReadable(true, true);
                if (!file.setExecutable(true, true)) {
                    Log.w(TAG, "setExecutable failed: " + file);
                }
            }
        }
    }

    /** EnvInstaller semantics: fast native Os.chmod(0755) with ChmodRunner fallback. */
    public static void setExec(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Os.chmod(file.getAbsolutePath(), 0755);
            return;
        } catch (ErrnoException ignored) {
        }
        ChmodRunner.rawChmod(file, "755");
        file.setReadable(true, true);
        if (!file.setExecutable(true, true)) {
            Log.w(TAG, "setExecutable failed: " + file);
        }
    }

    public static void recursiveSetExec(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            Log.w(TAG, "cannot list " + dir);
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                recursiveSetExec(file);
            } else {
                setExec(file);
            }
        }
    }

    public static void applyMode(File file, long mode) {
        int perm = (int) (mode & 0777L);
        if (file.isDirectory()) {
            perm = (perm | 0700) & 0755;
        } else {
            perm = perm & 0755;
            if ((perm & 0400) == 0) perm |= 0400;
        }
        try {
            Os.chmod(file.getAbsolutePath(), perm);
            return;
        } catch (ErrnoException ignored) {
        }
        boolean readable = (mode & 0400L) != 0;
        boolean writable = (mode & 0200L) != 0;
        boolean executable = (mode & 0100L) != 0 || file.isDirectory();
        file.setReadable(readable, true);
        file.setWritable(writable, true);
        file.setExecutable(executable, true);
    }

    public static void copyPermissions(File src, File dst) {
        try {
            int mode = Os.stat(src.getAbsolutePath()).st_mode & 0777;
            ChmodRunner.rawChmod(dst, Integer.toOctalString(mode));
        } catch (ErrnoException e) {
            Log.w(TAG, "cannot stat mode of " + src + ": " + e);
        }
    }
}
