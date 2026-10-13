package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.server.NativeServer;
import io.github.adhamura.gitphindroid.ui.pixel.PixelDialog;

import android.app.Activity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** File work of the repos folder: list, test, and move the repos with a progress dialog. */
final class RepoMover {
    private RepoMover() {}

    static File[] listRepos(File dir) {
        File[] r = dir.listFiles((d, n) -> n.endsWith(".git") && new File(d, n).isDirectory());
        return r == null ? new File[0] : r;
    }

    static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Exception e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /** Make the folder, write and delete a test file. Returns an error text or null. */
    static String testFolder(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return "the folder cannot be made";
            File t = new File(dir, ".gitphin-test-" + System.nanoTime());
            try (OutputStream o = new FileOutputStream(t)) {
                o.write(1);
            }
            if (!t.delete()) return "the folder is not writable";
            return null;
        } catch (Exception e) {
            return "the folder is not writable (" + e.getMessage() + ")";
        }
    }

    /** Stops the server, copies every repo, deletes the old copy, then runs commit. */
    static void move(Activity a, File oldDir, File newDir, Runnable commit) {
        String o = oldDir.getAbsolutePath() + "/", n = newDir.getAbsolutePath() + "/";
        if (n.startsWith(o) || o.startsWith(n)) {
            PixelDialog.with(a).message("The old and the new folder are inside each other. Pick another folder.")
                    .primary("OK", null).show();
            return;
        }
        final PixelDialog.Progress progress = PixelDialog.progress(a, "Moving repos");
        progress.setMessage("Stopping the server...");
        new Thread(() -> {
            try {
                if (NativeServer.nativeIsRunning()) NativeServer.nativeStop();
            } catch (Throwable ignored) {
                // library missing: nothing to stop
            }
            List<String> skipped = new ArrayList<>(), failed = new ArrayList<>();
            int moved = 0;
            File[] repos = listRepos(oldDir);
            for (int i = 0; i < repos.length; i++) {
                File src = repos[i];
                final String msg = "Moving " + (i + 1) + "/" + repos.length + ": " + src.getName();
                final int done = i, total = repos.length;
                a.runOnUiThread(() -> { progress.setMessage(msg); progress.setProgress(done, total); });
                File dst = new File(newDir, src.getName());
                if (dst.exists()) { skipped.add(src.getName()); continue; }
                try {
                    int c = copyTree(src, dst);
                    if (c != countFiles(dst)) throw new java.io.IOException("file count differs");
                    deleteTree(src);
                    moved++;
                } catch (Exception e) {
                    deleteTree(dst);
                    failed.add(src.getName() + " (" + e.getMessage() + ")");
                }
            }
            final int movedF = moved;
            a.runOnUiThread(() -> {
                progress.dismiss();
                commit.run();
                StringBuilder sb = new StringBuilder("Moved " + movedF + " of " + repos.length + " repos.");
                if (!skipped.isEmpty()) sb.append("\n\nSkipped (name exists in the new folder, kept in the old one):\n")
                        .append(String.join("\n", skipped));
                if (!failed.isEmpty()) sb.append("\n\nFailed (kept in the old folder):\n")
                        .append(String.join("\n", failed));
                PixelDialog.with(a).title("Move done").message(sb.toString())
                        .primary("OK", null).show();
            });
        }).start();
    }

    /** Copies a tree; returns the number of files. */
    private static int copyTree(File src, File dst) throws java.io.IOException {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) throw new java.io.IOException("cannot make " + dst.getName());
            int n = 0;
            File[] kids = src.listFiles();
            if (kids == null) throw new java.io.IOException("cannot read " + src.getName());
            for (File k : kids) n += copyTree(k, new File(dst, k.getName()));
            return n;
        }
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        return 1;
    }

    private static int countFiles(File f) {
        if (!f.isDirectory()) return 1;
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += countFiles(k);
        return n;
    }

    private static void deleteTree(File f) {
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
