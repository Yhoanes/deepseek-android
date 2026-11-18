package com.ds.ide.ui.webview;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;

import com.ds.ide.core.CoreRuntimeState;
import com.ds.ide.util.IoUtil;
import com.ds.ide.util.LoopbackHost;
import com.ds.ide.util.MimeTypes;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Hybrid loopback asset & capability-token injection layer for DeepSeek on Android:
 *  1. Neutralizes any ServiceWorker registration so fresh native boot scripts always run.
 *  2. Serves Android helper assets (`/__ds_android_boot.js`, `/__ds_plugins_catalog.json`,
 *     `/deepseek-icon.png`) directly from `filesDir/web/`.
 *  3. Passes `/plugins/??*`, `/assets/*`, `/oauth/callback`, RPCs, and WebSockets directly to DeepSeek Host.
 *  4. Intercepts main-frame document (`/` or `/index.html`) with the authenticated `dsh-auth-*`
 *     cookie to inject `Promise.withResolvers` safety polyfill, frozen `window.Android` facade,
 *     `window.__DS_HOST_THEME__`, and 256-bit `window.__DS_BRIDGE_TOKEN__`.
 */
public final class LocalAssetServer {

    private static final String TAG = "WebViewHost";
    private static volatile String hostThemeHint = "dark";

    public static void setHostThemeHint(String mode) {
        if (mode != null && !mode.isEmpty()) {
            hostThemeHint = mode;
        }
    }

    public WebResourceResponse intercept(Context context, Uri uri, String method,
                                         int enginePort, boolean isForMainFrame,
                                         String bridgeAuthToken) {
        return intercept(context, uri, method, enginePort, isForMainFrame, bridgeAuthToken, null);
    }

    public WebResourceResponse intercept(Context context, Uri uri, String method,
                                         int enginePort, boolean isForMainFrame,
                                         String bridgeAuthToken, String rawBindingName) {
        if (uri == null || !"GET".equalsIgnoreCase(method)) {
            return null;
        }
        if (!LoopbackHost.isCoreOrigin(uri, enginePort)) {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }

        // 1. Neutralize PWA ServiceWorker if ever requested
        if ("/sw.js".equals(path)) {
            String unregSw = "self.addEventListener('install',function(){self.skipWaiting();});"
                    + "self.addEventListener('activate',function(e){e.waitUntil("
                    + "caches.keys().then(function(k){return Promise.all(k.map(function(n){return caches.delete(n);}));})"
                    + ".then(function(){return self.registration.unregister();}));});";
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
                    headers, new ByteArrayInputStream(unregSw.getBytes(StandardCharsets.UTF_8)));
        }

        // 2. Serve custom Android bridge assets under /__ds_* or /deepseek-icon.png from filesDir/web
        if (path.startsWith("/__ds_") || "/deepseek-icon.png".equals(path)) {
            String relPath = path.substring(1);
            File webDir = new File(context.getFilesDir(), "web");
            File target = new File(webDir, relPath);
            try {
                String canonicalWeb = webDir.getCanonicalPath();
                String canonicalTarget = target.getCanonicalPath();
                if (!canonicalTarget.startsWith(canonicalWeb + File.separator)) {
                    return null;
                }
                if (target.isFile() && target.canRead()) {
                    String mime = MimeTypes.getMimeType(target.getName());
                    String encoding = MimeTypes.isTextMime(mime) ? "UTF-8" : null;
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    headers.put("X-Content-Type-Options", "nosniff");
                    InputStream bis = new BufferedInputStream(new FileInputStream(target), 32 * 1024);
                    return new WebResourceResponse(mime, encoding, 200, "OK", headers, bis);
                }
            } catch (Exception e) {
                Log.w(TAG, "failed serving " + path, e);
            }
            return null;
        }

        // 3. Intercept root HTML document (`/` or `/index.html`)
        if (isForMainFrame && ("/".equals(path) || "/index.html".equals(path))) {
            return fetchAndInjectIndexHtml(uri, bridgeAuthToken, rawBindingName);
        }

        return null;
    }

    private WebResourceResponse fetchAndInjectIndexHtml(Uri uri, String bridgeAuthToken, String rawBindingName) {
        HttpURLConnection conn = null;
        try {
            String html = null;
            String query = uri.getQuery();
            if (query == null || query.isEmpty()) {
                html = CoreRuntimeState.consumeCachedInitialHtml();
            }
            if (html == null || html.isEmpty()) {
                conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(5000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");

                String cookieHdr = CoreRuntimeState.sessionCookieHeader;
                if (cookieHdr == null || cookieHdr.isEmpty()) {
                    try {
                        cookieHdr = CookieManager.getInstance().getCookie(uri.toString());
                    } catch (Throwable ignored) {
                    }
                }
                if (cookieHdr != null && !cookieHdr.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookieHdr);
                }

                int status = conn.getResponseCode();
                String contentType = conn.getContentType();
                if (status != 200 || contentType == null || !contentType.toLowerCase().contains("text/html")) {
                    return null;
                }
                try (InputStream in = conn.getInputStream()) {
                    html = IoUtil.readAll(in, "UTF-8");
                }
            }
            String tokenInit = (bridgeAuthToken != null && !bridgeAuthToken.isEmpty())
                    ? "window.__DS_BRIDGE_TOKEN__=" + JSONObject.quote(bridgeAuthToken) + ";"
                    : "";
            String rawBinding = (rawBindingName != null && !rawBindingName.isEmpty())
                    ? JSONObject.quote(rawBindingName)
                    : "\"Android\"";

            String facadeScript = "\n    <script>(function(){"
                    + "if(typeof Promise.withResolvers!=='function'){"
                    + "Promise.withResolvers=function(){var res,rej,p=new Promise(function(a,b){res=a;rej=b;});return{promise:p,resolve:res,reject:rej};};"
                    + "}"
                    + "window.__DS_HOST_THEME__=" + JSONObject.quote(hostThemeHint) + ";"
                    + tokenInit
                    + "var rn=" + rawBinding + ";"
                    + "var raw=window[rn];"
                    + "if(raw){"
                    + "try{delete window[rn];}catch(e){window[rn]=undefined;}"
                    + "var facade={};"
                    + "for(var k in raw){"
                    + "(function(m){if(typeof raw[m]==='function'){facade[m]=function(){return raw[m].apply(raw,arguments);};}})(k);"
                    + "}"
                    + "try{"
                    + "Object.freeze(facade);"
                    + "Object.defineProperty(window,'Android',{value:facade,writable:false,configurable:false,enumerable:true});"
                    + "Object.defineProperty(window,'AndroidBridge',{value:facade,writable:false,configurable:false,enumerable:true});"
                    + "}catch(e){window.Android=facade;window.AndroidBridge=facade;}"
                    + "}"
                    + "try{var cs=document.currentScript;if(cs){cs.textContent='';if(cs.parentNode)cs.parentNode.removeChild(cs);}}catch(e){}"
                    + "})();</script>\n";

            String headInjection = facadeScript
                    + (html.contains("/__ds_android_boot.js") ? "" : "    <script src=\"/__ds_android_boot.js\"></script>\n");
            int headIdx = html.indexOf("<head>");
            if (headIdx >= 0) {
                html = html.substring(0, headIdx + 6) + headInjection + html.substring(headIdx + 6);
            } else {
                html = headInjection + html;
            }

            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("X-Content-Type-Options", "nosniff");
            return new WebResourceResponse(
                    "text/html", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            Log.w(TAG, "fetchAndInjectIndexHtml fallback: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
