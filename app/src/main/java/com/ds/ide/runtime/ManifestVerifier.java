package com.ds.ide.runtime;

import android.content.Context;

import com.ds.ide.util.IoUtil;
import com.ds.ide.util.Sha256;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.Iterator;

/** Verifies every runtime file against assets/runtime/manifest.json (fail-closed). */
public final class ManifestVerifier {

    private ManifestVerifier() {
    }

    /** Returns null on success, or a human-readable reason on the first failure. */
    public static String verifyError(Context appContext, File runtimeDir) {
        return verifyError(appContext, runtimeDir, false);
    }

    /**
     * Verifies {@code assets/runtime/*} files against {@code manifest.json}.
     * When {@code skipArchiveHash} is true, {@code core_archive_sha256} is verified in native Rust
     * by {@link NativeBootstrap#extractApkTarZstOrThrow} in a single zero-copy mmap pass.
     */
    public static String verifyError(Context appContext, File runtimeDir, boolean skipArchiveHash) {
        try (InputStream raw = appContext.getAssets().open(RuntimePaths.MANIFEST_ASSET)) {
            JSONObject root = new JSONObject(IoUtil.readAll(raw, "UTF-8"));
            JSONObject files = root.optJSONObject("files");
            if (files == null) {
                files = root;
            }
            Iterator<String> keys = files.keys();
            while (keys.hasNext()) {
                String relative = keys.next();
                if (relative.endsWith("manifest.json")) {
                    continue;
                }
                String expected = files.optString(relative, null);
                File target = new File(runtimeDir, relative);
                if (expected == null || !target.isFile()) {
                    return "missing runtime file: " + relative;
                }
                String actual = Sha256.of(target);
                if (!expected.equalsIgnoreCase(actual)) {
                    return "hash mismatch for " + relative
                            + " (expected=" + expected + " actual=" + actual + ")";
                }
            }
            if (!skipArchiveHash) {
                String expectedCoreSha = root.optString("core_archive_sha256", "");
                if (!expectedCoreSha.isEmpty()) {
                    try (InputStream coreIn = appContext.getAssets().open(RuntimePaths.CORE_ARCHIVE_ASSET)) {
                        String actualCoreSha = Sha256.of(coreIn);
                        if (!expectedCoreSha.equalsIgnoreCase(actualCoreSha)) {
                            return "core archive hash mismatch (expected=" + expectedCoreSha + " actual=" + actualCoreSha + ")";
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return "manifest unreadable: " + e.getMessage();
        }
    }

    public static String readExpectedCoreArchiveSha256(Context appContext) {
        try (InputStream raw = appContext.getAssets().open(RuntimePaths.MANIFEST_ASSET)) {
            JSONObject root = new JSONObject(IoUtil.readAll(raw, "UTF-8"));
            return root.optString("core_archive_sha256", "").trim();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read core_archive_sha256 from manifest.json: " + e.getMessage(), e);
        }
    }
}
