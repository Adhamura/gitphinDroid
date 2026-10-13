package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelDialog;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/** Settings card "Repos folder": choose the folder, ask for file access, move the repos. */
final class ReposFolderCard {
    static final int REQ_TREE = 2, REQ_STORAGE = 3;

    private final Activity a;
    private final Runnable restartServer;
    private final TextView folderPath, folderNote;
    private String pendingPath;       // picked folder that waits for the storage access
    private boolean waitingAccess;    // we sent the user to the system settings

    ReposFolderCard(Activity a, LinearLayout col, Runnable restartServer) {
        this.a = a;
        this.restartServer = restartServer;
        LinearLayout rc = Ui.card(col, "Repos folder").content();
        rc.addView(Ui.label(a, "CURRENT FOLDER"));
        folderPath = Ui.body(a, "", 14, a.getColor(R.color.px_fg));
        folderPath.setTextIsSelectable(true);
        rc.addView(folderPath);
        folderNote = Ui.body(a, "", 12, a.getColor(R.color.px_muted));
        folderNote.setPadding(0, Ui.dp(a, 4), 0, 0);
        rc.addView(folderNote);
        LinearLayout row = new LinearLayout(a);
        PixelButton choose = new PixelButton(a, "Choose folder");
        choose.setPrimary(true);
        choose.setOnClickListener(v -> chooseFolder());
        row.addView(choose, Ui.wrap());
        PixelButton useApp = new PixelButton(a, "Use app folder");
        useApp.setOnClickListener(v -> useAppFolder());
        row.addView(useApp, Ui.wrap());
        rc.addView(row, Ui.wrapTop(a, 4));
        refresh();
    }

    File appGitDir() {
        return new File(a.getFilesDir(), "ext/apps_data/gitphin/git");
    }

    File currentGitDir() {
        String s = Prefs.getGitDir(a);
        return s.isEmpty() ? appGitDir() : new File(s);
    }

    void refresh() {
        File dir = currentGitDir();
        folderPath.setText(dir.getAbsolutePath() + (Prefs.getGitDir(a).isEmpty() ? " (app folder)" : ""));
        File[] r = RepoMover.listRepos(dir);
        folderNote.setText(dir.isDirectory() ? "Repos in this folder: " + r.length
                : "The folder does not exist yet. It is made when the server starts.");
    }

    void onResume() {
        if (waitingAccess) {
            waitingAccess = false;
            String p = pendingPath;
            pendingPath = null;
            if (p != null) {
                if (hasAccess()) proceed(p);
                else error("All files access was not allowed. The folder was not changed.");
            }
        }
        refresh();
    }

    private void error(String msg) {
        PixelDialog.with(a).message(msg).primary("OK", null).show();
    }

    private void chooseFolder() {
        try {
            a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
        } catch (RuntimeException e) {
            error("No folder picker found on this phone.");
        }
    }

    private void useAppFolder() {
        if (Prefs.getGitDir(a).isEmpty()) {
            PixelToast.show(a, "The app folder is already in use.");
            return;
        }
        proceed("");
    }

    /** Tree uri to a real path, or null. */
    private static String treeToPath(Uri uri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(uri);
            if (id == null) return null;
            int c = id.indexOf(':');
            if (c < 0) return null;
            String vol = id.substring(0, c), sub = id.substring(c + 1);
            if (vol.equals("raw")) return sub.startsWith("/") ? sub : null;
            File base;
            if (vol.equals("primary")) base = Environment.getExternalStorageDirectory();
            else if (vol.matches("[A-Za-z0-9-]+")) base = new File("/storage/" + vol);
            else return null;
            File f = sub.isEmpty() ? base : new File(base, sub);
            return f.getAbsolutePath();
        } catch (RuntimeException e) {
            return null;
        }
    }

    void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_TREE || res != Activity.RESULT_OK || data == null || data.getData() == null) return;
        String path = treeToPath(data.getData());
        if (path == null) {
            error("This folder cannot be used: pick a folder on the phone storage or SD card");
            return;
        }
        proceed(path);
    }

    void onPermissionResult(int code) {
        if (code != REQ_STORAGE) return;
        String p = pendingPath;
        pendingPath = null;
        if (p != null && hasAccess()) proceed(p);
        else if (p != null) error("Storage access was not allowed. The folder was not changed.");
    }

    private boolean needsAccess(String path) {
        if (path.isEmpty()) return false;
        String priv = a.getFilesDir().getParent();
        return priv == null || !path.startsWith(priv);
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return a.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Check the access, test the folder, then ask about moving. */
    private void proceed(String path) {
        if (needsAccess(path) && !hasAccess()) {
            pendingPath = path;
            if (Build.VERSION.SDK_INT >= 30) {
                PixelDialog.with(a)
                        .title("All files access")
                        .message("The server reads and writes this folder with plain file paths. "
                                + "Android needs the \"All files access\" permission for that. "
                                + "Turn it on for Gitphin on the next screen, then come back.")
                        .button("Cancel", () -> pendingPath = null)
                        .primary("Open settings", this::openAllFilesSettings)
                        .onCancel(() -> pendingPath = null)
                        .show();
            } else {
                a.requestPermissions(new String[] {
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
            return;
        }
        if (!path.isEmpty()) {
            String err = RepoMover.testFolder(new File(path));
            if (err != null) {
                error("This folder cannot be used: " + err);
                return;
            }
        }
        File oldDir = currentGitDir();
        File newDir = path.isEmpty() ? appGitDir() : new File(path);
        if (RepoMover.sameFile(oldDir, newDir)) {
            Prefs.setGitDir(a, path.equals(appGitDir().getAbsolutePath()) ? "" : path);
            refresh();
            return;
        }
        int n = RepoMover.listRepos(oldDir).length;
        if (n == 0) {
            commit(path);
            return;
        }
        PixelDialog.with(a)
                .title("Move repos?")
                .message("Move " + n + " repos to the new folder?\n\n" + newDir.getAbsolutePath())
                .button("Keep them there", () -> commit(path))
                .primary("Move", () -> RepoMover.move(a, oldDir, newDir, () -> commit(path)))
                .show();
    }

    private void commit(String store) {
        Prefs.setGitDir(a, store);
        refresh();
        restartServer.run();
    }

    private void openAllFilesSettings() {
        waitingAccess = true;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (RuntimeException e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (RuntimeException e2) {
                waitingAccess = false;
                pendingPath = null;
                error("Cannot open the All files access settings.");
            }
        }
    }
}
