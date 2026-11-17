package com.ds.ide.install;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.ds.ide.runtime.NativeBootstrap;
import com.ds.ide.util.AssetPicker;
import com.ds.ide.util.FileModeUtil;
import com.ds.ide.util.IoUtil;
import com.ds.ide.util.PathSafety;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Installs the bundled Bionic toolchain ({@code assets/env}) into {@code getFilesDir()/usr}.
 * Uses native Rust + Zstandard ({@link NativeBootstrap}) for zero-copy APK extraction and
 * prefix/shebang rewriting, guarded by {@code BuildConfig.VERSION_CODE} so daily cold starts
 * complete in {@code < 1 ms} with zero disk I/O.
 */
public final class EnvInstaller {

    private static final String TAG = "EnvInstaller";

    private EnvInstaller() {
    }

    public static File prefix(Context context) {
        return EnvPaths.prefix(context);
    }

    public static boolean isInstalled(Context context) {
        File prefix = prefix(context);
        return new File(prefix, EnvPaths.MARKER).isFile()
                && new File(prefix, EnvPaths.BASH_REL).exists()
                && new File(prefix, "lib").isDirectory();
    }

    /** @return true if the env was installed or upgraded this call, false on fast-path skip. */
    public static synchronized boolean install(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("null context");
        }
        Context ctx = context.getApplicationContext();
        if (ctx == null) {
            ctx = context;
        }
        AssetManager assets = ctx.getAssets();
        File prefix = prefix(ctx);
        String versionStamp = String.valueOf(com.ds.ide.BuildConfig.VERSION_CODE);

        try {
            File userData = new File(ctx.getFilesDir(), EnvPaths.USER_DATA_DIR);
            if (PathSafety.isInside(userData, prefix)) {
                throw new IllegalStateException(
                        "refusing to install env over user data: " + prefix);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve prefix " + prefix, e);
        }

        if (isInstalled(ctx)) {
            if (isMarkerVersionMatch(prefix, versionStamp)) {
                Log.i(TAG, "env toolchain up to date (v" + versionStamp + "); fast-path skip");
                return false;
            }
            Log.i(TAG, "env installed at " + prefix + "; refreshing overlay for APK v" + versionStamp);
            try {
                PkgInstaller.install(assets, prefix);
                OverlayCopier.overlay(assets, EnvPaths.ASSET_OVERLAY, prefix);
                BusInstaller.install(assets, prefix);
                applyPrefixAndPermissions(ctx, prefix);
                writeMarker(prefix, versionStamp);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "best-effort pkg/overlay refresh skipped: " + e.getMessage());
                return false;
            }
        }

        String bootstrapAsset = AssetPicker.pick(assets,
                EnvPaths.ASSET_BOOTSTRAP, EnvPaths.ASSET_BOOTSTRAP_PLAIN);
        if (bootstrapAsset == null) {
            throw new IllegalStateException(
                    "required asset missing: " + EnvPaths.ASSET_BOOTSTRAP
                            + " (or " + EnvPaths.ASSET_BOOTSTRAP_PLAIN + ")");
        }
        Log.i(TAG, "installing env toolchain into " + prefix + " (" + bootstrapAsset + ")");

        if (!prefix.isDirectory() && !prefix.mkdirs() && !prefix.isDirectory()) {
            throw new IllegalStateException("cannot create prefix: " + prefix);
        }

        if (NativeBootstrap.isAvailable() && bootstrapAsset.endsWith(".tar.zst")) {
            NativeBootstrap.extractApkTarZstOrThrow(ctx, bootstrapAsset, prefix, "");
        } else {
            BootstrapExtractor.extract(assets, bootstrapAsset, prefix);
        }
        if (!new File(prefix, "bin").isDirectory()) {
            throw new IllegalStateException("bootstrap produced no bin/ under " + prefix);
        }

        OverlayCopier.overlay(assets, EnvPaths.ASSET_OVERLAY, prefix);
        PkgInstaller.install(assets, prefix);
        BusInstaller.install(assets, prefix);
        applyPrefixAndPermissions(ctx, prefix);
        writeMarker(prefix, versionStamp);

        Log.i(TAG, "env install complete: " + prefix);
        return true;
    }

    private static void applyPrefixAndPermissions(Context ctx, File prefix) {
        File dsBox = new File(prefix, "bin/ds-box");
        File agyBox = new File(prefix, "bin/agy-box");
        if (dsBox.isFile() && !agyBox.exists()) {
            try {
                android.system.Os.symlink("ds-box", agyBox.getAbsolutePath());
            } catch (Throwable ignored) {
            }
        }
        if (NativeBootstrap.isAvailable() && NativeBootstrap.fixupPrefix(prefix, ctx.getPackageName()) >= 0) {
            File pkgScript = new File(prefix, "libexec/pkg/pkg");
            if (pkgScript.isFile()) {
                FileModeUtil.setExec(pkgScript);
            }
            return;
        }
        TermuxSymlinkFixer.fix(prefix);
        TermuxShebangRewriter.rewrite(prefix);
        TermuxPrefixRewriter.rewrite(prefix);
        FileModeUtil.recursiveSetExec(new File(prefix, "bin"));
        FileModeUtil.recursiveSetExec(new File(prefix, "libexec"));
        FileModeUtil.setExec(new File(prefix, "libexec/pkg/pkg"));
        File fixPaths = new File(prefix, EnvPaths.FIX_PATHS_REL);
        if (fixPaths.isFile()) {
            FileModeUtil.setExec(fixPaths);
        }
        TermuxHooksRemover.remove(prefix);
    }

    private static boolean isMarkerVersionMatch(File prefix, String expectedVersion) {
        File marker = new File(prefix, EnvPaths.MARKER);
        if (!marker.isFile()) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(marker)) {
            String actual = IoUtil.readAll(fis, "UTF-8").trim();
            return expectedVersion.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeMarker(File prefix, String versionStamp) {
        File marker = new File(prefix, EnvPaths.MARKER);
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write((versionStamp + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write marker " + marker + ": " + e.getMessage(), e);
        }
    }
}
