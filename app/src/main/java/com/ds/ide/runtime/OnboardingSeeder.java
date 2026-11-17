package com.ds.ide.runtime;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.ds.ide.util.AtomicFileWriter;
import com.ds.ide.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Seeds DeepSeek Harness Android user configuration on launch:
 *  - ~/AGENTS.md & /storage/emulated/0/Documents/DeepSeek/AGENTS.md
 *  - ~/.dsh/skills/{android-environment,docx,pdf,pdf-reading,pptx,xlsx,frontend-design,draft-design-system}
 *  - ~/.dsh/{rules,plugins-hub}
 *  - Convenience symlinks in $HOME (~/Storage -> /storage/emulated/0, ~/Documents, ~/Projects)
 *  - ~/.gitconfig (safe.directory = *)
 */
public final class OnboardingSeeder {

    private static final String TAG = "OnboardingSeeder";

    private OnboardingSeeder() {
    }

    public static void seed(Context context, File dshDir, File projectsDir, int port, String csrfToken) {
        try {
            File filesDir = context.getFilesDir();
            mkdirsQuiet(dshDir);
            mkdirsQuiet(projectsDir);
            mkdirsQuiet(new File(dshDir, "skills"));
            mkdirsQuiet(new File(dshDir, "rules"));
            mkdirsQuiet(new File(dshDir, "plugins-hub"));

            seedBundledAgentsMd(context, new File(filesDir, "AGENTS.md"));

            try {
                File extRoot = Environment.getExternalStorageDirectory();
                File defaultWs = new File(extRoot, "Documents/DeepSeek");
                if ((defaultWs.isDirectory() || defaultWs.mkdirs()) && defaultWs.canWrite()) {
                    File wsAgentsMd = new File(defaultWs, "AGENTS.md");
                    if (!wsAgentsMd.isFile()) {
                        seedBundledAgentsMd(context, wsAgentsMd);
                    }
                }
                ensureStorageSymlink(new File(filesDir, "Storage"), extRoot);
                ensureStorageSymlink(new File(filesDir, "Documents"), new File(extRoot, "Documents"));
                ensureStorageSymlink(new File(filesDir, "Projects"), defaultWs);
            } catch (Throwable ignored) {
            }

            seedOfficialBundledSkills(filesDir, new File(dshDir, "skills"));
            ensureGitSafeDirectory(filesDir);
        } catch (Exception e) {
            Log.w(TAG, "OnboardingSeeder notice: " + e.getMessage());
        }
    }

    private static void ensureStorageSymlink(File link, File target) {
        try {
            if (target != null && !link.exists()) {
                android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            }
        } catch (Throwable ignored) {
        }
    }

    private static void seedBundledAgentsMd(Context ctx, File dst) {
        try (InputStream in = ctx.getAssets().open(RuntimePaths.SEED_ASSET + "/AGENTS.md")) {
            String raw = IoUtil.readAll(in, "UTF-8");
            String pkg = ctx.getPackageName();
            if (!"com.ds.ide".equals(pkg)) {
                raw = raw.replace("com.ds.ide", pkg);
            }
            if (!dst.isFile() || dst.length() == 0) {
                AtomicFileWriter.writeText(dst, raw);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot seed AGENTS.md: " + e.getMessage());
        }
    }

    private static void seedOfficialBundledSkills(File filesDir, File skillsDstDir) {
        File bundledRoot = new File(filesDir, "runtime/bundled-skills");
        if (!bundledRoot.isDirectory()) {
            return;
        }
        File[] children = bundledRoot.listFiles();
        if (children == null) {
            return;
        }
        for (File skillDir : children) {
            if (!skillDir.isDirectory()) continue;
            File dst = new File(skillsDstDir, skillDir.getName());
            if (!dst.exists()) {
                copyRecursiveQuiet(skillDir, dst);
            }
        }
    }

    private static void copyRecursiveQuiet(File src, File dst) {
        if (src.isDirectory()) {
            mkdirsQuiet(dst);
            File[] items = src.listFiles();
            if (items != null) {
                for (File it : items) {
                    copyRecursiveQuiet(it, new File(dst, it.getName()));
                }
            }
        } else if (src.isFile()) {
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void ensureGitSafeDirectory(File filesDir) {
        try {
            File gitconfig = new File(filesDir, ".gitconfig");
            if (!gitconfig.isFile()) {
                AtomicFileWriter.writeText(gitconfig, "[safe]\n\tdirectory = *\n");
            } else {
                try (FileInputStream fis = new FileInputStream(gitconfig)) {
                    String txt = IoUtil.readAll(fis, "UTF-8");
                    if (!txt.contains("directory = *")) {
                        AtomicFileWriter.writeText(gitconfig, txt + "\n[safe]\n\tdirectory = *\n");
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void mkdirsQuiet(File d) {
        if (d != null && !d.isDirectory()) {
            d.mkdirs();
        }
    }
}
