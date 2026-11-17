package com.ds.ide.core;

import android.util.Base64;
import android.util.Log;

import com.ds.ide.util.IoUtil;
import com.ds.ide.util.ProcessUtils;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Exchanges DeepSeek Harness's one-time startup launch URL (`/?token=...`) for its
 * 30-day signed `dsh-auth-*` session cookie and pre-fetches `GET /` in one pass.
 */
public final class CoreReadyProbe {

    private static final String TAG = "CoreReadyProbe";
    public static final long READY_TIMEOUT_MS = 60_000L;

    private CoreReadyProbe() {
    }

    public static String basicAuthHeader(String password) {
        String raw = "deepseek:" + (password != null ? password : "");
        return "Basic " + Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public static boolean probeOnce(int port, String csrf) {
        String launchUrl = CoreRuntimeState.launchUrl;
        if (launchUrl == null || launchUrl.isEmpty()) {
            synchronized (CoreRuntimeState.LAUNCH_URL_LOCK) {
                launchUrl = CoreRuntimeState.launchUrl;
                if (launchUrl == null || launchUrl.isEmpty()) {
                    try {
                        CoreRuntimeState.LAUNCH_URL_LOCK.wait(120);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    launchUrl = CoreRuntimeState.launchUrl;
                }
            }
            if (launchUrl == null || launchUrl.isEmpty()) {
                return false;
            }
        }

        // Step 1: Exchange launch token -> 30-day signed Set-Cookie if not exchanged yet
        if (CoreRuntimeState.sessionCookieHeader == null) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(launchUrl).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(2500);
                int status = conn.getResponseCode();
                if (status == 302 || status == 303 || status == 200) {
                    String setCookie = extractAuthSetCookie(conn.getHeaderFields());
                    if (setCookie != null && !setCookie.isEmpty()) {
                        CoreRuntimeState.sessionSetCookieRaw = setCookie;
                        int semi = setCookie.indexOf(';');
                        CoreRuntimeState.sessionCookieHeader = semi > 0 ? setCookie.substring(0, semi).trim() : setCookie.trim();
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "token exchange retry: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        // Step 2: Verify authenticated GET / returns 200 OK text/html and cache initial HTML
        String cookieHdr = CoreRuntimeState.sessionCookieHeader;
        if (cookieHdr != null && !cookieHdr.isEmpty()) {
            HttpURLConnection rootConn = null;
            try {
                rootConn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
                rootConn.setInstanceFollowRedirects(false);
                rootConn.setRequestMethod("GET");
                rootConn.setConnectTimeout(1500);
                rootConn.setReadTimeout(2500);
                rootConn.setRequestProperty("Cookie", cookieHdr);
                rootConn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                if (rootConn.getResponseCode() == 200) {
                    String ct = rootConn.getContentType();
                    if (ct != null && ct.toLowerCase().contains("text/html")) {
                        try (InputStream in = rootConn.getInputStream()) {
                            String html = IoUtil.readAll(in, "UTF-8");
                            if (html != null && !html.isEmpty()) {
                                CoreRuntimeState.setCachedInitialHtml(html);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }
            } catch (Exception ignored) {
            } finally {
                if (rootConn != null) {
                    rootConn.disconnect();
                }
            }
        }

        ProcessUtils.sleep(50);
        return false;
    }

    private static String extractAuthSetCookie(Map<String, List<String>> headers) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && "set-cookie".equalsIgnoreCase(e.getKey()) && e.getValue() != null) {
                for (String c : e.getValue()) {
                    if (c != null && c.startsWith("dsh-auth-")) {
                        return c;
                    }
                }
                if (!e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
        }
        return null;
    }

    public static void warmDefaultWorkspace(int port, String password, File workspaceDir) {
        if (workspaceDir != null && !workspaceDir.isDirectory()) {
            workspaceDir.mkdirs();
        }
    }

    public static String mintSessionToken(int port, String password) {
        return CoreRuntimeState.sessionCookieHeader != null ? CoreRuntimeState.sessionCookieHeader : password;
    }
}
