package com.ds.ide.shell;

import android.content.Context;
import android.util.Log;

/**
 * Rootless shell access through `rish` (agy-adb-bridge plugin in usr/bin/rish or Shizuku in runtime/bin/rish).
 * No Shizuku API library is linked; `rish` is executed with a strict timeout watchdog.
 */
public final class ShizukuBridge {

    private static final String TAG = "ShizukuBridge";
    private static final long STARTUP_DEFER_MS = 15_000L;
    private static volatile boolean tweaksAppliedThisProcess = false;

    private final Context appContext;
    private final RishLocator locator;
    private final RishExecutor executor;

    public ShizukuBridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.locator = new RishLocator(context);
        this.executor = new RishExecutor(context);
    }

    public boolean isAvailable() {
        java.io.File rish = locator.rishBinary();
        return rish.exists() && rish.canExecute();
    }

    /** Runs `cmd` via rish with default 2.5s timeout. Returns combined stdout/stderr; errors start with "error:". */
    public String exec(String cmd) {
        return executor.exec(cmd);
    }

    /** Runs `cmd` via rish with explicit timeout in milliseconds. */
    public String exec(String cmd, long timeoutMs) {
        return executor.exec(cmd, timeoutMs);
    }

    /**
     * Deferred, once-per-process background pass via `rish` so `app_process` never contends
     * with `node.elf` cold start, and skips global `device_config` writes when already set.
     */
    public void applyBackgroundSurvivabilityTweaksAsync() {
        if (tweaksAppliedThisProcess) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(STARTUP_DEFER_MS);
                    if (tweaksAppliedThisProcess || !isAvailable()) {
                        return;
                    }
                    String pkg = appContext.getPackageName();
                    String cmd = "dumpsys deviceidle whitelist +" + pkg + " >/dev/null 2>&1 || true; "
                            + "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true; "
                            + "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true; "
                            + "if [ \"$(settings get global settings_enable_monitor_phantom_procs 2>/dev/null)\" != \"false\" ]; then "
                            + "device_config set_sync_disabled_for_tests persistent >/dev/null 2>&1 || true; "
                            + "device_config put activity_manager max_phantom_processes 2147483647 >/dev/null 2>&1 || true; "
                            + "settings put global settings_enable_monitor_phantom_procs false >/dev/null 2>&1 || true; "
                            + "fi";
                    String res = exec(cmd, 4000L);
                    if (res != null && res.startsWith("error:")) {
                        Log.d(TAG, "Shizuku survivability tweaks skipped (" + res + ")");
                    } else {
                        tweaksAppliedThisProcess = true;
                        Log.i(TAG, "Applied background survivability whitelist for " + pkg);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    Log.d(TAG, "Shizuku survivability tweaks error: " + e.getMessage());
                }
            }
        }, "ShizukuTweaksThread");
        t.setDaemon(true);
        t.start();
    }
}

