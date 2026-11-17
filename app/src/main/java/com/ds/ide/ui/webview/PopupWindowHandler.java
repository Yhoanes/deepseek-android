package com.ds.ide.ui.webview;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.ds.ide.oauth.OAuthManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles {@code window.open}: intercepts OAuth/external navigations once per popup
 * and immediately tears down the temporary detached WebView so duplicate browser tabs
 * can never fire.
 */
public final class PopupWindowHandler {

    private static final String TAG = "WebViewHost";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Context context;
    private final OAuthManager oauth;

    public PopupWindowHandler(Context context, OAuthManager oauth) {
        this.context = context;
        this.oauth = oauth;
    }

    public boolean handle(WebView parent, boolean isDialog, boolean isUserGesture,
                          Message resultMsg) {
        Log.i(TAG, "onCreateWindow isDialog=" + isDialog + " isUserGesture=" + isUserGesture);
        final WebView popup = new WebView(context);
        final AtomicBoolean dispatched = new AtomicBoolean(false);
        popup.getSettings().setJavaScriptEnabled(true);
        popup.setWebViewClient(new WebViewClient() {
            private void dispatchOnce(WebView v, String url) {
                if (url == null || url.isEmpty() || "about:blank".equals(url)) {
                    return;
                }
                if (!dispatched.compareAndSet(false, true)) {
                    return;
                }
                Log.i(TAG, "popup -> external browser url=" + com.ds.ide.util.CoreUrl.stripUrlSecrets(url));
                try {
                    v.stopLoading();
                } catch (Throwable ignored) {
                }
                oauth.openInBrowser(context, url);
                mainHandler.post(() -> {
                    try {
                        v.destroy();
                    } catch (Throwable ignored) {
                    }
                });
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest request) {
                String url = request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null;
                if (url != null && !url.isEmpty() && !"about:blank".equals(url)) {
                    dispatchOnce(v, url);
                    return true;
                }
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url != null && !url.isEmpty() && !"about:blank".equals(url)) {
                    dispatchOnce(v, url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (url != null && !url.isEmpty() && !"about:blank".equals(url)) {
                    dispatchOnce(v, url);
                }
            }
        });
        if (resultMsg != null && resultMsg.obj instanceof WebView.WebViewTransport) {
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
            resultMsg.sendToTarget();
        }
        return true;
    }
}
