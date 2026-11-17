package com.ds.ide.runtime;

import android.content.Context;
import android.util.Log;

import com.ds.ide.install.EnvInstaller;
import com.ds.ide.install.TarExtractor;
import com.ds.ide.util.AssetUnpacker;
import com.ds.ide.util.AtomicFileWriter;
import com.ds.ide.util.FileModeUtil;
import com.ds.ide.util.IoUtil;
import com.ds.ide.util.Sha256;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Phase 1: extract + verify DeepSeek Harness v0.2.2 runtime, glibc 2.44, and Node v24. */
public final class CoreInstaller {

    private static final String TAG = "CoreInstaller";
    private static final Object INSTALL_LOCK = new Object();

    private CoreInstaller() {
    }

    public static void installCore(RuntimeManager rm) {
        synchronized (INSTALL_LOCK) {
            installCoreLocked(rm);
        }
    }

    private static void installCoreLocked(RuntimeManager rm) {
        Context ctx = rm.appContext();
        File runtimeDir = rm.getRuntimeDir();
        String expectedHash = getAssetManifestHash(ctx);
        String runtimeStamp = expectedHash.isEmpty()
                ? ""
                : (expectedHash + ":" + com.ds.ide.BuildConfig.VERSION_CODE);
        boolean upToDate = isRuntimeUpToDate(rm, runtimeStamp);

        if (!upToDate) {
            long t0 = System.currentTimeMillis();
            Log.i(TAG, "extracting assets/runtime + core-archive (first launch or runtime update)");
            try {
                fastDiscardDir(rm, runtimeDir);
                File v8Cache = new File(ctx.getFilesDir(), ".cache/v8-compile-cache");
                if (v8Cache.exists()) {
                    fastDiscardDir(rm, v8Cache);
                }
                AssetUnpacker.unpackTree(ctx.getAssets(), RuntimePaths.RUNTIME_ASSET, runtimeDir);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "cannot unpack assets/runtime into " + runtimeDir + ": " + e.getMessage(), e);
            }

            boolean useNativeRust = NativeBootstrap.isAvailable()
                    && RuntimePaths.CORE_ARCHIVE_ASSET.endsWith(".tar.zst");
            String manifestError = ManifestVerifier.verifyError(ctx, runtimeDir, useNativeRust);
            if (manifestError != null) {
                throw new IllegalStateException("runtime integrity check failed: " + manifestError);
            }

            if (useNativeRust) {
                String expectedCoreSha = ManifestVerifier.readExpectedCoreArchiveSha256(ctx);
                NativeBootstrap.extractApkTarZstOrThrow(
                        ctx, RuntimePaths.CORE_ARCHIVE_ASSET, runtimeDir, expectedCoreSha);
            } else {
                try (InputStream coreStream = ctx.getAssets().open(RuntimePaths.CORE_ARCHIVE_ASSET)) {
                    TarExtractor.untarGz(coreStream, runtimeDir);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "cannot extract " + RuntimePaths.CORE_ARCHIVE_ASSET + ": " + e.getMessage(), e);
                }
                if (rm.getLibDir().isDirectory()) {
                    FileModeUtil.recursiveSetReadableExecutable(rm.getLibDir());
                }
                File rgBin = new File(runtimeDir, "dsh-app/node_modules/@vscode/ripgrep-linux-arm64/bin/rg");
                if (rgBin.isFile()) {
                    FileModeUtil.setExec(rgBin);
                }
                File dshBin = new File(runtimeDir, "dsh-app/node_modules/@deepseek-ai/dsh/lib/bin.js");
                if (dshBin.isFile()) {
                    FileModeUtil.setExec(dshBin);
                }
            }

            FileModeUtil.recursiveSetReadableExecutable(rm.getBinDir());
            writeMarker(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER, runtimeStamp);
            Log.i(TAG, "core runtime installation completed in " + (System.currentTimeMillis() - t0) + " ms");
        } else {
            Log.i(TAG, "runtime up to date (stamp matched); fast-path boot");
        }

        boolean webUpToDate = isWebUpToDate(rm, runtimeStamp);
        if (!upToDate || !webUpToDate) {
            try {
                AssetUnpacker.unpackTree(ctx.getAssets(), RuntimePaths.WEB_ASSET, rm.getWebDir());
                writeMarker(rm.getWebDir(), RuntimePaths.WEB_MARKER, runtimeStamp);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "cannot unpack assets/web into " + rm.getWebDir() + ": " + e.getMessage(), e);
            }
        }

        rm.writeNetworkConfig();
        rm.startNetworkMonitor();

        if (!upToDate || !webUpToDate || !ShellBridgeInstaller.isBridgeUpToDate(rm)) {
            OnboardingSeeder.seed(ctx, rm.getDshDir(), rm.getProjectsDir(), rm.getHttpsPort(), rm.getCsrfToken());
            ShellBridgeInstaller.installFallbackBridges(rm.getBinDir(), ctx);
            ShellBridgeInstaller.installOpenUrlShims(rm.getBinDir(), ctx);
            ensureUsrBinCoreLinks(ctx, rm.getBinDir());
            if (EnvInstaller.isInstalled(ctx)) {
                ShellBridgeInstaller.install(rm, !upToDate);
                ensureUsrBinCoreLinks(ctx, rm.getBinDir());
            }
        }
    }

    public static void ensureUsrBinCoreLinks(Context ctx, File runtimeBin) {
        try {
            File usrBin = new File(ctx.getFilesDir(), "usr/bin");
            if (!usrBin.isDirectory() && !usrBin.mkdirs()) {
                return;
            }
            for (String tool : new String[]{"dsh", "node", "xdg-user-dir"}) {
                File src = new File(runtimeBin, tool);
                File dst = new File(usrBin, tool);
                if (src.isFile()) {
                    try {
                        if (Files.isSymbolicLink(dst.toPath()) && !dst.exists()) {
                            Files.deleteIfExists(dst.toPath());
                        }
                        if (!Files.exists(dst.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            android.system.Os.symlink(src.getAbsolutePath(), dst.getAbsolutePath());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            File sdcardLink = new File(ctx.getFilesDir(), "sdcard");
            if (!Files.exists(sdcardLink.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                try {
                    android.system.Os.symlink("/storage/emulated/0", sdcardLink.getAbsolutePath());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isRuntimeUpToDate(RuntimeManager rm, String expectedStamp) {
        if (expectedStamp == null || expectedStamp.isEmpty()) {
            return false;
        }
        File marker = new File(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER);
        File core = new File(rm.getBinDir(), RuntimePaths.CORE_NAME);
        File ldLinux = new File(rm.getLibDir(), "ld-linux-aarch64.so.1");
        File nodeElf = new File(rm.getBinDir(), "node.elf");
        File dshEntry = new File(rm.getRuntimeDir(), "dsh-app/node_modules/@deepseek-ai/dsh/lib/bin.js");
        if (!marker.isFile() || !core.isFile() || !ldLinux.isFile() || !nodeElf.isFile() || !dshEntry.isFile()) {
            return false;
        }
        return isMarkerEqual(marker, expectedStamp);
    }

    private static boolean isWebUpToDate(RuntimeManager rm, String expectedStamp) {
        if (expectedStamp == null || expectedStamp.isEmpty()) {
            return false;
        }
        File webDir = rm.getWebDir();
        File marker = new File(webDir, RuntimePaths.WEB_MARKER);
        File bootJs = new File(webDir, "__ds_android_boot.js");
        File catalog = new File(webDir, "__ds_plugins_catalog.json");
        if (!marker.isFile() || !bootJs.isFile() || !catalog.isFile()) {
            return false;
        }
        return isMarkerEqual(marker, expectedStamp);
    }

    private static boolean isMarkerEqual(File marker, String expected) {
        try (FileInputStream fis = new FileInputStream(marker)) {
            String actual = IoUtil.readAll(fis, "UTF-8").trim();
            return expected.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getAssetManifestHash(Context ctx) {
        try (InputStream in = ctx.getAssets().open(RuntimePaths.MANIFEST_ASSET)) {
            return Sha256.of(in);
        } catch (Exception e) {
            Log.w(TAG, "cannot hash asset manifest: " + e.getMessage());
            return "";
        }
    }

    private static void writeMarker(File dir, String name, String content) {
        try {
            AtomicFileWriter.writeText(new File(dir, name), content + "\n");
        } catch (Exception e) {
            Log.w(TAG, "cannot write marker " + name + ": " + e.getMessage());
        }
    }

    private static void fastDiscardDir(RuntimeManager rm, File dir) throws IOException {
        Path path = dir.toPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        assertSafeToWipe(rm, dir);
        File parent = dir.getParentFile();
        if (parent != null) {
            final File stale = new File(parent, "." + dir.getName() + ".stale." + System.currentTimeMillis());
            if (dir.renameTo(stale)) {
                Thread cleaner = new Thread(() -> deleteTreeQuietly(stale), "StaleRuntimeCleaner");
                cleaner.setDaemon(true);
                cleaner.start();
                return;
            }
        }
        wipe(rm, dir);
    }

    private static void deleteTreeQuietly(File f) {
        try {
            Path p = f.toPath();
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        deleteTreeQuietly(k);
                    }
                }
            }
            Files.deleteIfExists(p);
        } catch (Throwable ignored) {
        }
    }

    private static void wipe(RuntimeManager rm, File dir) throws IOException {
        Path path = dir.toPath();
        if (Files.isSymbolicLink(path)) {
            File parent = dir.getParentFile();
            if (parent != null) {
                assertSafeToWipe(rm, parent);
            }
            Files.deleteIfExists(path);
            return;
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        assertSafeToWipe(rm, dir);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    wipe(rm, child);
                }
            }
        }
        if (!Files.deleteIfExists(path) && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("cannot delete " + dir);
        }
    }

    private static void assertSafeToWipe(RuntimeManager rm, File dir) throws IOException {
        File files = rm.appContext().getFilesDir();
        String filesPath = files.getCanonicalPath();
        String target = dir.getCanonicalPath();
        if (!target.startsWith(filesPath + File.separator)) {
            throw new IOException("refusing to wipe outside filesDir: " + dir);
        }
        for (String protectedRel : new String[]{".dsh", ".agents", ".config", ".local", ".gemini", "workspace", "Documents"}) {
            String protPath = new File(files, protectedRel).getCanonicalPath();
            if (target.equals(protPath)
                    || target.startsWith(protPath + File.separator)
                    || protPath.startsWith(target + File.separator)) {
                throw new IOException("refusing to wipe user data under " + protectedRel + ": " + dir);
            }
        }
    }
}
