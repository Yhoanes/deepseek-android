package com.ds.ide.ui.webview;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.ds.ide.oauth.OAuthManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles {@code window.open}: intercepts OAuth/external navigations once per popup
 * and guarantees immediate cleanup on {@code window.close()}, timeout, or renderer exit
 * so detached blank popups never leak or crash the host process.
 */
public final class PopupWindowHandler {

    private static final String TAG = "WebViewHost";
    private static final long POPUP_MAX_LIFETIME_MS = 15_000L;
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
        final AtomicBoolean destroyed = new AtomicBoolean(false);

        final Runnable cleanupPopup = () -> {
            if (!destroyed.compareAndSet(false, true)) {
                return;
            }
            try {
                popup.stopLoading();
            } catch (Throwable ignored) {
            }
            try {
                popup.setWebChromeClient(null);
                popup.destroy();
            } catch (Throwable ignored) {
            }
        };

        popup.getSettings().setJavaScriptEnabled(true);
        popup.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                mainHandler.post(cleanupPopup);
            }
        });
        popup.setWebViewClient(new WebViewClient() {
            private void dispatchOnce(WebView v, String url) {
                if (url == null || url.isEmpty() || "about:blank".equals(url)) {
                    return;
                }
                if (!dispatched.compareAndSet(false, true)) {
                    return;
                }
                Log.i(TAG, "popup -> external browser url=" + com.ds.ide.util.CoreUrl.stripUrlSecrets(url));
                oauth.openInBrowser(context, url);
                mainHandler.post(cleanupPopup);
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

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.w(TAG, "popup WebView renderer exited; cleaning up safely");
                }
                mainHandler.post(cleanupPopup);
                return true;
            }
        });

        mainHandler.postDelayed(cleanupPopup, POPUP_MAX_LIFETIME_MS);

        if (resultMsg != null && resultMsg.obj instanceof WebView.WebViewTransport) {
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
            resultMsg.sendToTarget();
        } else {
            mainHandler.post(cleanupPopup);
        }
        return true;
    }
}
