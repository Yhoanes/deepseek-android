package com.ds.ide.runtime;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * JNI bridge to {@code libds_bootstrap.so} (native Rust ARM64 + {@code libzstd.so}).
 *
 * <p>Extracts {@code ZIP_STORED} {@code .tar.zst} archives directly from the memory-mapped APK
 * with single-pass SHA-256 verification, PAX/GNU/USTAR tar support, symlink/Zip-Slip guards,
 * and native Bionic prefix/shebang rewriting.
 */
public final class NativeBootstrap {

    private static final String TAG = "NativeBootstrap";
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = null;
        try {
            System.loadLibrary("zstd");
            System.loadLibrary("ds_bootstrap");
            ok = true;
        } catch (Throwable t) {
            err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            Log.e(TAG, "Failed to load native Rust bootstrap engine: " + err, t);
        }
        AVAILABLE = ok;
        LOAD_ERROR = err;
    }

    private NativeBootstrap() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static String loadError() {
        return LOAD_ERROR;
    }

    public static void extractApkTarZstOrThrow(
            Context appContext,
            String assetRelPath,
            File destDir,
            String expectedSha256) {
        if (!AVAILABLE) {
            throw new IllegalStateException("Native Rust bootstrap library unavailable: " + LOAD_ERROR);
        }
        String apkPath = appContext.getApplicationInfo().sourceDir;
        String entryName = assetRelPath.startsWith("assets/")
                ? assetRelPath
                : ("assets/" + assetRelPath);
        long t0 = System.currentTimeMillis();
        String err = nativeExtractApkTarZst(
                apkPath,
                entryName,
                destDir.getAbsolutePath(),
                expectedSha256 != null ? expectedSha256 : "");
        if (err != null) {
            throw new IllegalStateException("Native extraction failed (" + entryName + "): " + err);
        }
        long dt = System.currentTimeMillis() - t0;
        Log.i(TAG, "Extracted " + entryName + " -> " + destDir + " in " + dt + " ms (Rust+Zstd)");
    }

    public static int fixupPrefix(File prefixDir, String pkgName) {
        if (!AVAILABLE) {
            return -1;
        }
        long t0 = System.currentTimeMillis();
        int modified = nativeFixupPrefix(prefixDir.getAbsolutePath(), pkgName);
        long dt = System.currentTimeMillis() - t0;
        Log.i(TAG, "Prefix/shebang/chmod fixup finished in " + dt + " ms (modified=" + modified + ")");
        return modified;
    }

    private static native String nativeExtractApkTarZst(
            String apkPath,
            String entryName,
            String destDir,
            String expectedSha256);

    @SuppressWarnings("unused")
    private static native String nativeExtractFileTarZst(
            String tarZstPath,
            String destDir,
            String expectedSha256);

    private static native int nativeFixupPrefix(
            String prefixDir,
            String pkgName);
}
