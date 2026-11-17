package com.ds.ide.core;

import com.ds.ide.install.EnvInstaller;
import com.ds.ide.runtime.RuntimeManager;

import java.io.File;
import java.util.Map;

/** Builds the clean Android ARM64 ProcessBuilder environment for DeepSeek Harness Host. */
public final class CoreEnvironment {

    private CoreEnvironment() {
    }

    public static void apply(RuntimeManager runtime, ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        File filesDir = runtime.appContext().getFilesDir();
        File bin = runtime.getBinDir();
        File cert = runtime.getCertFile();
        File dshDir = runtime.getDshDir();
        File appData = runtime.getOcDataDir();
        File prefix = EnvInstaller.prefix(runtime.appContext());
        File prefixBin = new File(prefix, "bin");
        File tmpDir = new File(prefix, "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
        }
        File localBin = new File(filesDir, ".local/bin");
        if (!localBin.isDirectory()) {
            localBin.mkdirs();
        }

        env.put("HOME", filesDir.getAbsolutePath());
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("TMPDIR", tmpDir.getAbsolutePath());
        env.put("DSH_HOME", dshDir.getAbsolutePath());
        env.put("DSH_PERMISSION_MODE", "danger-full-access");
        env.put("DSH_CLIENT_VERSION", RuntimeManager.CORE_VERSION);
        env.put("DSH_ANDROID_PKG", runtime.appContext().getPackageName());
        env.put("DSH_OAUTH_SCHEME", com.ds.ide.BuildConfig.OAUTH_SCHEME);

        File defaultWs = runtime.getDefaultWorkspaceDir();
        if (defaultWs != null && defaultWs.isDirectory()) {
            env.put("DSH_DEFAULT_WORKSPACE", defaultWs.getAbsolutePath());
        }

        env.put("XDG_CONFIG_HOME", new File(filesDir, ".config").getAbsolutePath());
        env.put("XDG_DATA_HOME", new File(filesDir, ".local/share").getAbsolutePath());
        env.put("XDG_STATE_HOME", new File(filesDir, ".local/state").getAbsolutePath());
        File cacheRoot = new File(filesDir, ".cache");
        File v8CacheDir = new File(cacheRoot, "v8-compile-cache");
        if (!v8CacheDir.isDirectory()) {
            v8CacheDir.mkdirs();
        }
        env.put("XDG_CACHE_HOME", cacheRoot.getAbsolutePath());
        env.put("NODE_COMPILE_CACHE", v8CacheDir.getAbsolutePath());
        env.put("ANTIGRAVITY_APP_DATA_DIR", appData.getAbsolutePath());
        env.put("GHIDRA_HEADLESS_MAXMEM", "768M");

        env.put("SSL_CERT_FILE", cert.getAbsolutePath());
        env.put("SSL_CERT_DIR", cert.getParentFile().getAbsolutePath());
        env.put("CURL_CA_BUNDLE", cert.getAbsolutePath());
        env.put("GIT_SSL_CAINFO", cert.getAbsolutePath());
        env.put("NODE_EXTRA_CA_CERTS", cert.getAbsolutePath());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        File gitCore = new File(prefix, "libexec/git-core");
        if (gitCore.isDirectory()) {
            env.put("GIT_EXEC_PATH", gitCore.getAbsolutePath());
        }

        File opensslCnf = new File(prefix, "etc/tls/openssl.cnf");
        if (opensslCnf.isFile()) {
            env.put("OPENSSL_CONF", opensslCnf.getAbsolutePath());
        }

        String sysPath = env.get("PATH");
        String basePath = prefixBin.getAbsolutePath()
                + File.pathSeparator + bin.getAbsolutePath()
                + File.pathSeparator + localBin.getAbsolutePath();
        env.put("PATH", (sysPath == null || sysPath.isEmpty())
                ? basePath + ":/system/bin"
                : basePath + File.pathSeparator + sysPath);

        File bashBin = new File(prefixBin, "bash");
        env.put("SHELL", bashBin.isFile() ? bashBin.getAbsolutePath() : new File(bin, "bash").getAbsolutePath());
        env.put("BROWSER", new File(bin, "xdg-open").getAbsolutePath());
        String chromeShim = new File(bin, "chrome").getAbsolutePath();
        env.put("CHROME_PATH", chromeShim);
        env.put("PUPPETEER_EXECUTABLE_PATH", chromeShim);
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "C.UTF-8");
        File preloadMjs = new File(runtime.getRuntimeDir(), "dsh-app/android-preload.mjs");
        env.put("NODE_OPTIONS", "--import " + preloadMjs.getAbsolutePath());
        env.remove("LD_PRELOAD");
        env.remove("LD_LIBRARY_PATH");
    }
}
