package com.ds.ide.oauth;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import com.ds.ide.ui.MainActivity;
import com.ds.ide.ui.launcher.LauncherDarkActivity;
import com.ds.ide.ui.launcher.LauncherLightActivity;

/**
 * Zero-UI deep-link trampoline (`deepseek://oauth-success`) that pulls the
 * running DeepSeek workspace back in front of the external browser immediately
 * after Google / DeepSeek Platform OAuth finishes.
 */
public final class OAuthCallbackActivity extends Activity {

    private static final String TAG = "OAuthCallback";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "OAuth callback trampoline invoked: "
                + (getIntent() != null ? getIntent().getDataString() : "null"));
        bringAppToFront(this);
        finish();
        overridePendingTransition(0, 0);
    }

    /**
     * Brings the active DeepSeek task and WebView to the foreground from either
     * the browser deep-link trampoline or CoreProcessSupervisor's OAuth signal.
     */
    public static void bringAppToFront(final Context context) {
        if (context == null) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        MAIN.post(() -> {
            MainActivity active = MainActivity.activeActivity;
            if (active != null && !active.isFinishing() && !active.isDestroyed()) {
                try {
                    ActivityManager am = (ActivityManager) appCtx.getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.moveTaskToFront(active.getTaskId(), ActivityManager.MOVE_TASK_NO_USER_ACTION);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "moveTaskToFront notice: " + t.getMessage());
                }
            }
            try {
                Class<?> targetClass = (active != null && !active.isFinishing() && !active.isDestroyed())
                        ? active.getClass()
                        : resolveEnabledLauncherClass(appCtx);
                Intent launch = new Intent(appCtx, targetClass);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                context.startActivity(launch);
            } catch (Throwable t) {
                Log.w(TAG, "bringAppToFront startActivity notice: " + t.getMessage());
            }
            if (active != null) {
                WebView wv = active.getWebView();
                if (wv != null) {
                    try {
                        wv.onResume();
                        wv.resumeTimers();
                        wv.evaluateJavascript(
                                "try{window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'));}catch(e){}",
                                null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private static Class<?> resolveEnabledLauncherClass(Context appCtx) {
        try {
            PackageManager pm = appCtx.getPackageManager();
            int lightState = pm.getComponentEnabledSetting(
                    new ComponentName(appCtx, LauncherLightActivity.class));
            if (lightState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return LauncherLightActivity.class;
            }
        } catch (Throwable ignored) {
        }
        return LauncherDarkActivity.class;
    }
}
