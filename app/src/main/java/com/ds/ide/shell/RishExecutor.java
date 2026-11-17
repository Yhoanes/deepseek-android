package com.ds.ide.shell;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a shell command through rish (agy-adb-bridge or Shizuku) with a strict MIUI-safe timeout. */
public final class RishExecutor {

    public static final long DEFAULT_TIMEOUT_MS = 2500L;
    private static final int MAX_OUTPUT_BYTES = 262_144;

    private final Context appContext;
    private final RishLocator locator;

    public RishExecutor(Context context) {
        this.appContext = context.getApplicationContext();
        this.locator = new RishLocator(context);
    }

    public String exec(String cmd) {
        return exec(cmd, DEFAULT_TIMEOUT_MS);
    }

    /** Runs `cmd` via rish bounded by `timeoutMs`. Returns combined stdout/stderr; errors start with "error:". */
    public String exec(String cmd, long timeoutMs) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return "error: empty command";
        }
        File rish = locator.rishBinary();
        if (!rish.isFile()) {
            return "error: bundled rish not found at " + rish.getAbsolutePath();
        }
        if (!rish.canExecute()) {
            return "error: rish not executable: " + rish.getAbsolutePath()
                    + " (ADB Bridge / Shizuku unavailable)";
        }
        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        ProcessBuilder pb = new ProcessBuilder(
                rish.getAbsolutePath(),
                "-c",
                "env -u LD_LIBRARY_PATH " + cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.remove("LD_LIBRARY_PATH");
        env.put("RISH_APPLICATION_ID", appContext.getPackageName());
        pb.directory(appContext.getFilesDir());
        Process process = null;
        try {
            process = pb.start();
            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {
            }
            final InputStream pis = process.getInputStream();
            final java.io.ByteArrayOutputStream outBuf = new java.io.ByteArrayOutputStream(2048);
            Thread readerThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = pis.read(buf)) != -1) {
                        synchronized (outBuf) {
                            if (outBuf.size() < MAX_OUTPUT_BYTES) {
                                int keep = Math.min(n, MAX_OUTPUT_BYTES - outBuf.size());
                                outBuf.write(buf, 0, keep);
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        pis.close();
                    } catch (Exception ignored) {
                    }
                }
            }, "RishStreamDrain");
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                try {
                    pis.close();
                } catch (Exception ignored) {
                }
                readerThread.join(300);
                return "error: rish timed out after " + effectiveTimeout + "ms";
            }
            readerThread.join(500);
            int code = process.exitValue();
            String text;
            synchronized (outBuf) {
                text = new String(outBuf.toByteArray(), StandardCharsets.UTF_8).trim();
            }
            if (code != 0 && text.isEmpty()) {
                return "error: rish exited " + code;
            }
            return text;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
