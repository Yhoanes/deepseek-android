package com.ds.ide.ui.launcher;

import com.ds.ide.ui.MainActivity;

/**
 * Dark-theme launcher entry. A real Activity (not an activity-alias) so the OS
 * starting window uses {@code SplashThemeDark}; the alias theme is ignored by
 * the system splash. Only one launcher activity is enabled at a time; see
 * {@code LauncherIconSwitcher}.
 */
public final class LauncherDarkActivity extends MainActivity {
}
