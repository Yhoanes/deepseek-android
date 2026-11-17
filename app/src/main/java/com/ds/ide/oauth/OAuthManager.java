package com.ds.ide.oauth;

import android.content.Context;
import android.util.Log;

/**
 * OAuth is delegated to an external browser (Google blocks WebView login).
 * Includes a global debounce so rapid clicks or overlapping WebView callbacks
 * never launch duplicate browser tabs.
 */
public final class OAuthManager {

    private static final long DEBOUNCE_MS = 1200L;
    private static volatile String lastOpenedUrl = "";
    private static volatile long lastOpenedAt = 0L;

    private final AuthUrlMatcher matcher = new AuthUrlMatcher();
    private final CustomTabLauncher launcher = new CustomTabLauncher();

    /** True when the URL must leave the WebView and go to the system browser. */
    public boolean isAuthUrl(String url) {
        return matcher.isAuthUrl(url);
    }

    /**
     * Intercepts an auth URL. Returns true if it was handled (opened externally),
     * false if the WebView should load it normally.
     */
    public boolean intercept(Context context, String url) {
        boolean auth = matcher.isAuthUrl(url);
        String safeUrl = com.ds.ide.util.CoreUrl.stripUrlSecrets(url);
        Log.i(OAuthConstants.TAG, "intercept isAuthUrl=" + auth + " url=" + safeUrl);
        if (!auth) {
            return false;
        }
        Log.i(OAuthConstants.TAG, "isAuthUrl matched -> external browser: " + safeUrl);
        return openInBrowser(context, url);
    }

    /** Opens the URL in the user's browser with 1.2s duplicate suppression. */
    public boolean openInBrowser(Context context, String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String normalized = url.trim();
        long now = System.currentTimeMillis();
        synchronized (OAuthManager.class) {
            if (normalized.equals(lastOpenedUrl) && (now - lastOpenedAt) < DEBOUNCE_MS) {
                Log.i(OAuthConstants.TAG, "suppressed duplicate browser launch within " + DEBOUNCE_MS + "ms");
                return true;
            }
            lastOpenedUrl = normalized;
            lastOpenedAt = now;
        }
        return launcher.openInBrowser(context, normalized);
    }
}
