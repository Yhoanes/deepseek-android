package com.ds.ide.runtime;

import android.util.Log;

import com.ds.ide.install.EnvInstaller;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 2: extract the bionic toolchain and regenerate runtime/bin bridges on a
 * daemon thread, in parallel with core startup. On daily warm launches, both
 * {@link EnvInstaller#install} and {@link ShellBridgeInstaller#isBridgeUpToDate}
 * take {@code < 1 ms} so zero flash I/O contends with Node/V8 startup.
 */
public final class EnvInstallScheduler {

    private static final String TAG = "EnvInstallScheduler";

    private EnvInstallScheduler() {
    }

    public static void schedule(final RuntimeManager rm, final AtomicBoolean started) {
        if (!started.compareAndSet(false, true)) {
            Log.i(TAG, "env install already started; skipping duplicate");
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean changed = EnvInstaller.install(rm.appContext());
                    if (changed || !ShellBridgeInstaller.isBridgeUpToDate(rm)) {
                        ShellBridgeInstaller.install(rm, changed);
                        Log.i(TAG, "env toolchain ready; runtime/bin bridges refreshed");
                    } else {
                        Log.i(TAG, "env toolchain & bridges already up to date");
                    }
                } catch (Throwable e) {
                    started.set(false);
                    Log.e(TAG, "env install failed; core continues without bionic tools: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
                }
            }
        }, "EnvInstallThread");
        thread.setDaemon(true);
        thread.start();
    }
}
