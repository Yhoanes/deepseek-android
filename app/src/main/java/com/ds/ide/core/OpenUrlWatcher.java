package com.ds.ide.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ds.ide.bridge.NotificationBridge;
import com.ds.ide.oauth.OAuthManager;
import com.ds.ide.ui.MainActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Event-driven URL & notification IPC watcher (`inotify` FileObserver + private broadcast).
 * Zero background polling loops: sleeps with 0% CPU when idle.
 */
public final class OpenUrlWatcher {

    private static final String TAG = "OpenUrlWatcher";
    public static final String QUEUE_FILE_NAME = ".open_url_queue";
    public static final String NOTIFY_QUEUE_FILE_NAME = ".notify_queue";

    private static volatile OpenUrlWatcher activeInstance;

    private final Context appContext;
    private final OAuthManager oauth = new OAuthManager();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();
    private final Object notifyLock = new Object();
    private final File queueFile;
    private final File notifyQueueFile;

    private volatile String lastDispatchedUrl = "";
    private volatile long lastDispatchedTime = 0;
    private FileObserver queueObserver;

    private final BroadcastReceiver openUrlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String url = intent.getStringExtra("url");
            if (url != null && !url.trim().isEmpty()) {
                Log.i(TAG, "broadcast ACTION_OPEN_URL received");
                dispatchIfValid(url);
            }
        }
    };

    public OpenUrlWatcher(Context context) {
        this.appContext = context.getApplicationContext();
        this.queueFile = new File(this.appContext.getFilesDir(), QUEUE_FILE_NAME);
        this.notifyQueueFile = new File(this.appContext.getFilesDir(), NOTIFY_QUEUE_FILE_NAME);
    }

    public static void drainActiveQueuesAsync() {
        final OpenUrlWatcher inst = activeInstance;
        if (inst == null) return;
        Thread t = new Thread(inst::drainAll, "OpenUrlDrainOnce");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("deprecation")
    public void start() {
        activeInstance = this;
        try {
            if (queueFile.isFile()) {
                queueFile.delete();
            }
            NotificationBridge.syncActiveSnapshot(appContext);
        } catch (Throwable ignored) {
        }
        try {
            IntentFilter filter = new IntentFilter("com.ds.ide.ACTION_OPEN_URL");
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(openUrlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(openUrlReceiver, filter,
                        appContext.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", null);
            }
            Log.i(TAG, "registered private ACTION_OPEN_URL receiver");
        } catch (Throwable t) {
            Log.w(TAG, "failed to register ACTION_OPEN_URL receiver: " + t.getMessage());
        }
        try {
            final int mask = FileObserver.CLOSE_WRITE | FileObserver.MODIFY
                    | FileObserver.MOVED_TO | FileObserver.CREATE;
            queueObserver = new FileObserver(appContext.getFilesDir().getAbsolutePath(), mask) {
                @Override
                public void onEvent(int event, String path) {
                    if (QUEUE_FILE_NAME.equals(path)) {
                        drainQueueFile();
                    } else if (NOTIFY_QUEUE_FILE_NAME.equals(path)) {
                        drainNotifyQueueFile();
                    }
                }
            };
            queueObserver.startWatching();
            drainNotifyQueueFile();
        } catch (Throwable t) {
            Log.w(TAG, "FileObserver setup notice: " + t.getMessage());
        }
    }

    public void drainAll() {
        drainQueueFile();
        drainNotifyQueueFile();
    }

    private void drainNotifyQueueFile() {
        if (!notifyQueueFile.isFile() || notifyQueueFile.length() == 0) {
            return;
        }
        List<String> lines = new ArrayList<>();
        synchronized (notifyLock) {
            if (!notifyQueueFile.isFile() || notifyQueueFile.length() == 0) {
                return;
            }
            File snap = new File(notifyQueueFile.getParentFile(), NOTIFY_QUEUE_FILE_NAME + ".drain");
            snap.delete();
            File readTarget = notifyQueueFile.renameTo(snap) ? snap : notifyQueueFile;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(readTarget), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        lines.add(trimmed);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                readTarget.delete();
            }
        }
        for (String raw : lines) {
            try {
                JSONObject obj = new JSONObject(raw);
                String action = obj.optString("action", "post");
                if ("cancel".equals(action)) {
                    NotificationBridge.cancelLocal(appContext, obj.optString("tag", ""));
                } else {
                    NotificationBridge.postLocal(
                            appContext,
                            obj.optString("title", ""),
                            obj.optString("message", ""),
                            obj.optString("tag", ""));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void stop() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        try {
            appContext.unregisterReceiver(openUrlReceiver);
        } catch (Throwable ignored) {
        }
        try {
            if (queueObserver != null) {
                queueObserver.stopWatching();
                queueObserver = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void drainQueueFile() {
        if (!queueFile.isFile() || queueFile.length() == 0) {
            return;
        }
        List<String> urls = new ArrayList<>();
        synchronized (queueLock) {
            if (!queueFile.isFile() || queueFile.length() == 0) {
                return;
            }
            File snap = new File(queueFile.getParentFile(), QUEUE_FILE_NAME + ".drain");
            snap.delete();
            File readTarget = queueFile.renameTo(snap) ? snap : queueFile;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(readTarget), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        urls.add(trimmed);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                readTarget.delete();
            }
        }
        for (String u : urls) {
            dispatchIfValid(u);
        }
    }

    /** Detects explicit OPEN_URL prefixes emitted by xdg-open / CLI shims on core stdout. */
    public void handleStreamLine(String line) {
        if (line == null) {
            return;
        }
        for (String prefix : new String[]{"DS_OPEN_URL=", "AGY_OPEN_URL="}) {
            int at = line.indexOf(prefix);
            if (at >= 0) {
                String url = line.substring(at + prefix.length()).trim();
                dispatchIfValid(url);
                return;
            }
        }
    }

    public void dispatchIfValid(String url) {
        if (url == null) return;
        url = url.trim();
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) return;

        long now = System.currentTimeMillis();
        if (url.equals(lastDispatchedUrl) && (now - lastDispatchedTime) < 1500) {
            return;
        }
        lastDispatchedUrl = url;
        lastDispatchedTime = now;
        Log.i(TAG, "dispatching external URL: " + url);
        openUrl(url);
    }

    private void openUrl(final String url) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Context target = (MainActivity.activeActivity != null)
                        ? MainActivity.activeActivity
                        : appContext;
                boolean opened = oauth.openInBrowser(target, url);
                if (!opened) {
                    Log.w(TAG, "no browser accepted the url: " + url);
                }
            }
        });
    }
}
