package com.ds.ide.runtime;

import android.content.Context;

import com.ds.ide.core.CoreEnvironment;
import com.ds.ide.core.CoreRuntimeState;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeManager {

    public static final String CORE_VERSION = RuntimeConfig.CORE_VERSION;

    private final Context appContext;
    private final String csrfToken;
    private final RuntimePaths paths;
    private final NetworkMonitorHolder network;
    private final AtomicBoolean envInstallStarted = new AtomicBoolean(false);

    public RuntimeManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.csrfToken = UUID.randomUUID().toString();
        this.paths = new RuntimePaths(context);
        this.network = new NetworkMonitorHolder(context);
    }

    public Context appContext() {
        return appContext;
    }

    public int getHttpsPort() {
        return RuntimeConfig.httpsPort(appContext);
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public File getRuntimeDir() {
        return paths.getRuntimeDir();
    }

    public File getWebDir() {
        return paths.getWebDir();
    }

    public File getBinDir() {
        return paths.getBinDir();
    }

    public File getLibDir() {
        return paths.getLibDir();
    }

    public File getNodeLibDir() {
        return paths.getNodeLibDir();
    }

    public File getCertFile() {
        return paths.getCertFile();
    }

    public File getDshDir() {
        return paths.getDshDir();
    }

    public File getOcConfigDir() {
        return paths.getOcConfigDir();
    }

    public File getOcDataDir() {
        return paths.getOcDataDir();
    }

    public File getGeminiDir() {
        return paths.getGeminiDir();
    }

    public File getAppDataDir() {
        return paths.getAppDataDir();
    }

    public File getProjectsDir() {
        return paths.getProjectsDir();
    }

    public File getDefaultWorkspaceDir() {
        return paths.getDefaultWorkspaceDir();
    }

    public synchronized void installCore() {
        CoreInstaller.installCore(this);
    }

    public void installEnvInBackground() {
        EnvInstallScheduler.schedule(this, envInstallStarted);
    }

    /** Command to boot the standalone DeepSeek Harness Web/Desktop server on loopback. */
    public String[] coreCommand() {
        File core = new File(getBinDir(), RuntimePaths.CORE_NAME);
        if (!core.isFile()) {
            throw new IllegalStateException("DeepSeek Harness launcher missing: " + core);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(core.getAbsolutePath());
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(String.valueOf(getHttpsPort()));
        cmd.add("--no-open");
        return cmd.toArray(new String[0]);
    }

    public void applyEnvironment(ProcessBuilder pb) {
        CoreEnvironment.apply(this, pb);
    }

    public void startNetworkMonitor() {
        network.start();
    }

    public synchronized void refreshNetwork() {
        writeNetworkConfig();
        if (CoreRuntimeState.serverUrl != null) {
            android.util.Log.d("RuntimeManager", "network changed; rewrote DNS config (host stays up)");
        } else {
            android.util.Log.d("RuntimeManager", "network changed; DNS config refreshed");
        }
    }

    public synchronized void writeNetworkConfig() {
        NetworkConfigWriter.write(appContext);
    }
}
