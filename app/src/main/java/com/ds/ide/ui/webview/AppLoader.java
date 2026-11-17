package com.ds.ide.ui.webview;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import com.ds.ide.bridge.AndroidBridge;
import com.ds.ide.core.CoreRuntimeState;
import com.ds.ide.runtime.RuntimePaths;
import com.ds.ide.theme.ThemeManager;

import java.io.File;

/**
 * Seeds the 30-day signed `dsh-auth-*` session cookie into WebView's CookieManager
 * and loads the local DeepSeek Harness loopback origin (`http://127.0.0.1:<port>/`).
 */
public final class AppLoader {

    private static final String TAG = "WebViewHost";

    private AppLoader() {
    }

    public static void load(Context context, WebView webView, ThemeManager theme,
                            AndroidBridge bridge) {
        int port = bridge.getEnginePort();
        if (theme != null) {
            theme.applyToWebView(webView);
        }

        String origin = "http://127.0.0.1:" + port;
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, false);
            }
            String rawSetCookie = CoreRuntimeState.sessionSetCookieRaw;
            if (rawSetCookie != null && !rawSetCookie.isEmpty()) {
                cm.setCookie(origin, rawSetCookie);
                cm.setCookie(origin + "/", rawSetCookie);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.flush();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cookie setup warning: " + e.getMessage());
        }

        String resolvedTheme = (theme != null) ? theme.getTheme() : "dark";
        if (!"light".equals(resolvedTheme)) {
            resolvedTheme = "dark";
        }

        LocalAssetServer.setHostThemeHint(resolvedTheme);
        bridge.setCoreOriginActive(true);
        try {
            File defaultWs = new RuntimePaths(context).getDefaultWorkspaceDir();
            if (defaultWs != null && !defaultWs.isDirectory()) {
                defaultWs.mkdirs();
            }
        } catch (Exception e) {
            Log.w(TAG, "default workspace mkdirs warning: " + e.getMessage());
        }
        webView.loadUrl(origin + "/");
    }
}
