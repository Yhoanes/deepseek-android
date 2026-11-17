package com.ds.ide.ui.download;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Handles file downloads initiated from the WebView with explicit runtime storage permission checks.
 */
public final class FileDownloader {

    private static final String TAG = "FileDownloader";
    public static final int REQUEST_STORAGE_DOWNLOAD = 2001;

    public static class DownloadTask {
        public final String url;
        public final String userAgent;
        public final String contentDisposition;
        public final String mimeType;
        public final long contentLength;
        public final String base64Data; // set if blob download
        public final String bridgeToken;

        public DownloadTask(String url, String userAgent, String contentDisposition,
                            String mimeType, long contentLength, String base64Data, String bridgeToken) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.contentLength = contentLength;
            this.base64Data = base64Data;
            this.bridgeToken = bridgeToken;
        }
    }

    private static DownloadTask pendingTask;

    private FileDownloader() {
    }

    private static String tr(String es, String en) {
        String lang = java.util.Locale.getDefault().getLanguage();
        return (lang != null && lang.startsWith("es")) ? es : en;
    }

    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private static String sanitizeDownloadName(String rawName) {
        if (rawName == null) {
            return "download_" + System.currentTimeMillis();
        }
        String clean = new File(rawName.trim()).getName();
        clean = clean.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (clean.startsWith(".")) {
            clean = clean.substring(1).trim();
        }
        if (clean.isEmpty() || ".".equals(clean) || "..".equals(clean)) {
            return "download_" + System.currentTimeMillis();
        }
        return clean;
    }

    public static void requestStoragePermission(Activity activity, DownloadTask task) {
        pendingTask = task;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(activity, tr("Se requiere permiso de archivos para descargar",
                    "Storage permission required to download"), Toast.LENGTH_SHORT).show();
            activity.requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQUEST_STORAGE_DOWNLOAD);
        } else {
            executeTask(activity, null, task);
        }
    }

    public static void onPermissionResult(Activity activity, WebView webView, int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_STORAGE_DOWNLOAD) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            if (pendingTask != null) {
                DownloadTask task = pendingTask;
                pendingTask = null;
                executeTask(activity, webView, task);
            }
        } else {
            pendingTask = null;
            Toast.makeText(activity, tr("Permiso de archivos denegado",
                    "Storage permission denied"), Toast.LENGTH_LONG).show();
        }
    }

    public static void handleDownload(Activity activity, WebView webView, String url,
                                      String userAgent, String contentDisposition,
                                      String mimeType, long contentLength, String bridgeToken) {
        DownloadTask task = new DownloadTask(url, userAgent, contentDisposition, mimeType, contentLength, null, bridgeToken);
        if (!hasStoragePermission(activity)) {
            requestStoragePermission(activity, task);
        } else {
            executeTask(activity, webView, task);
        }
    }

    public static void handleBlobData(Activity activity, String base64Data, String filename, String mimeType) {
        DownloadTask task = new DownloadTask(null, null, filename, mimeType, 0, base64Data, null);
        if (!hasStoragePermission(activity)) {
            requestStoragePermission(activity, task);
        } else {
            executeTask(activity, null, task);
        }
    }

    private static void executeTask(final Activity activity, final WebView webView, final DownloadTask task) {
        if (task == null || activity == null) {
            return;
        }

        // Case 1: Base64 blob data
        if (task.base64Data != null) {
            saveBase64ToDownloads(activity, task.base64Data, task.contentDisposition, task.mimeType);
            return;
        }

        if (task.url == null || task.url.trim().isEmpty()) {
            return;
        }

        final String rawUrl = task.url.trim();

        // Case 2: blob: URL -> extract data via JS in WebView
        if (rawUrl.startsWith("blob:") || rawUrl.startsWith("data:")) {
            if (webView != null && task.bridgeToken != null) {
                String safeName = sanitizeDownloadName(URLUtil.guessFileName(rawUrl, task.contentDisposition, task.mimeType));
                extractBlobViaJs(webView, rawUrl, safeName, task.mimeType, task.bridgeToken);
            } else {
                Toast.makeText(activity, tr("No se puede descargar sin WebView activa",
                        "Cannot download without active WebView"), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // Case 3: Loopback (127.0.0.1 / localhost) -> direct internal download to avoid DownloadManager TLS errors
        Uri uri = Uri.parse(rawUrl);
        String host = uri.getHost();
        if (com.ds.ide.util.LoopbackHost.isLoopback(host)) {
            downloadDirect(activity, rawUrl, task.contentDisposition, task.mimeType);
            return;
        }

        // Case 4: Standard HTTP / HTTPS -> enqueue in system DownloadManager
        try {
            DownloadManager.Request request = new DownloadManager.Request(uri);
            if (task.mimeType != null && !task.mimeType.isEmpty()) {
                request.setMimeType(task.mimeType);
            }
            String fileName = sanitizeDownloadName(URLUtil.guessFileName(rawUrl, task.contentDisposition, task.mimeType));
            request.setTitle(fileName);
            request.setDescription(tr("Descargando archivo", "Downloading file"));
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            if (task.userAgent != null && !task.userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", task.userAgent);
            }
            String cookies = CookieManager.getInstance().getCookie(rawUrl);
            if (cookies != null && !cookies.isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(activity, tr("Descargando: ", "Downloading: ") + fileName, Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Download enqueued: " + fileName + " url=" + rawUrl);
            }
        } catch (Exception e) {
            Log.w(TAG, "DownloadManager failed, falling back to direct download: " + e.getMessage());
            downloadDirect(activity, rawUrl, task.contentDisposition, task.mimeType);
        }
    }

    private static void extractBlobViaJs(WebView webView, String blobUrl, String filename,
                                         String mimeType, String bridgeToken) {
        String qTok = org.json.JSONObject.quote(bridgeToken != null ? bridgeToken : "");
        String qUrl = org.json.JSONObject.quote(blobUrl != null ? blobUrl : "");
        String qFile = org.json.JSONObject.quote(filename != null ? filename : "download");
        String qMime = org.json.JSONObject.quote(mimeType != null ? mimeType : "");
        String js = "(function() {" +
                "  try {" +
                "    var xhr = new XMLHttpRequest();" +
                "    xhr.open('GET', " + qUrl + ", true);" +
                "    xhr.responseType = 'blob';" +
                "    xhr.onload = function() {" +
                "      if (xhr.status === 200 || xhr.status === 0) {" +
                "        var reader = new FileReader();" +
                "        reader.onloadend = function() {" +
                "          if (window.Android && window.Android.saveBlobAuth) {" +
                "            window.Android.saveBlobAuth(" + qTok + ", reader.result, " + qFile + ", " + qMime + ");" +
                "          }" +
                "        };" +
                "        reader.readAsDataURL(xhr.response);" +
                "      }" +
                "    };" +
                "    xhr.send();" +
                "  } catch(e) {" +
                "    console.error('Failed to extract blob', e);" +
                "  }" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private static File openDownloadOutputTarget(Context context, String name) {
        try {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs()) && downloadsDir.canWrite()) {
                return getUniqueFile(new File(downloadsDir, name));
            }
        } catch (Throwable ignored) {
        }
        File extAppDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (extAppDir != null && (extAppDir.exists() || extAppDir.mkdirs())) {
            return getUniqueFile(new File(extAppDir, name));
        }
        return getUniqueFile(new File(context.getCacheDir(), name));
    }

    public static void saveBase64ToDownloads(final Context context, final String dataUrlOrBase64,
                                             final String filename, final String mimeType) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String base64 = dataUrlOrBase64;
                    int comma = base64.indexOf(',');
                    if (comma >= 0) {
                        base64 = base64.substring(comma + 1);
                    }
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

                    String name = sanitizeDownloadName(filename);
                    File target = openDownloadOutputTarget(context, name);

                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        fos.write(bytes);
                        fos.flush();
                    }

                    final File savedFile = target;
                    MediaScannerConnection.scanFile(context, new String[]{savedFile.getAbsolutePath()}, null, null);

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, tr("Guardado en Descargas: ", "Saved to Downloads: ") + savedFile.getName(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.i(TAG, "Blob saved to: " + savedFile.getAbsolutePath());
                } catch (final Exception e) {
                    Log.e(TAG, "Failed to save blob", e);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, tr("Error al guardar archivo: ", "Failed to save file: ") + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static void downloadDirect(final Activity activity, final String fileUrl,
                                       final String contentDisposition, final String mimeType) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL u = new URL(fileUrl);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 DeepSeek-Android");
                    conn.connect();

                    if (conn.getResponseCode() >= 400) {
                        throw new RuntimeException("HTTP " + conn.getResponseCode());
                    }

                    String disp = conn.getHeaderField("Content-Disposition");
                    if (disp == null || disp.isEmpty()) {
                        disp = contentDisposition;
                    }
                    String type = conn.getContentType();
                    if (type == null || type.isEmpty()) {
                        type = mimeType;
                    }

                    String fileName = sanitizeDownloadName(URLUtil.guessFileName(fileUrl, disp, type));
                    File target = openDownloadOutputTarget(activity, fileName);

                    try (InputStream in = conn.getInputStream();
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        out.flush();
                    }

                    final File savedFile = target;
                    MediaScannerConnection.scanFile(activity, new String[]{savedFile.getAbsolutePath()}, null, null);

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity, tr("Guardado en Descargas: ", "Saved to Downloads: ") + savedFile.getName(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.i(TAG, "Direct download saved: " + savedFile.getAbsolutePath());
                } catch (final Exception e) {
                    Log.e(TAG, "Direct download failed", e);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity, tr("Error de descarga: ", "Download failed: ") + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static File getUniqueFile(File file) {
        if (!file.exists()) {
            return file;
        }
        String parent = file.getParent();
        String name = file.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int count = 1;
        while (file.exists()) {
            file = new File(parent, base + " (" + count + ")" + ext);
            count++;
        }
        return file;
    }
}
