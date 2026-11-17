package com.ds.ide.core;

import com.ds.ide.runtime.RuntimeConfig;

/** Cross-component state shared by the foreground service, the JS bridge, and the WebView UI. */
public final class CoreRuntimeState {

    private CoreRuntimeState() {
    }

    /** Monitor notified the instant {@code dsh web: http...} is captured on stdout. */
    public static final Object LAUNCH_URL_LOCK = new Object();

    /** Published once the DeepSeek Harness HTTP server answers on its loopback port and mints a session cookie. */
    public static volatile String serverUrl = null;
    public static volatile int httpsPort = RuntimeConfig.DEFAULT_HTTPS_PORT;
    public static volatile String csrfToken = null;

    /** Startup tokenized URL emitted on stdout (`dsh web: http://127.0.0.1:<port>/?token=...`). */
    public static volatile String launchUrl = null;
    /** Full Set-Cookie value (`dsh-auth-...=...; Max-Age=...; Path=/; HttpOnly; SameSite=Strict`) for WebView CookieManager. */
    public static volatile String sessionSetCookieRaw = null;
    /** `name=value` pair sent in `Cookie` request headers by LocalAssetServer. */
    public static volatile String sessionCookieHeader = null;
    public static volatile String sessionToken = null;

    /** Pre-fetched initial root HTML from CoreReadyProbe to avoid duplicate loopback HTTP round-trip. */
    private static volatile String cachedInitialHtml = null;

    /** Fail-closed startup error, surfaced to the UI instead of a hung splash. */
    public static volatile String lastError = null;
    /** True while an unexpected exit is being auto-restarted (transient, not fatal). */
    public static volatile boolean restarting = false;
    /** Bumped on every successful ready; the UI reloads the app when it changes. */
    public static volatile int readyGeneration = 0;

    /** Event-driven UI listener notified on ready/error state transitions (avoids 50ms UI spin-polling). */
    private static volatile Runnable stateListener = null;

    public static void setStateListener(Runnable listener) {
        stateListener = listener;
    }

    public static void notifyStateChanged() {
        Runnable l = stateListener;
        if (l != null) {
            com.ds.ide.util.MainThreadPost.post(l);
        }
    }

    public static synchronized void setCachedInitialHtml(String html) {
        cachedInitialHtml = html;
    }

    public static synchronized String consumeCachedInitialHtml() {
        String html = cachedInitialHtml;
        cachedInitialHtml = null;
        return html;
    }
}
