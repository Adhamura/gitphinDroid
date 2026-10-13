package io.github.adhamura.gitphindroid.ui.share;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.net.GitApi;
import io.github.adhamura.gitphindroid.server.GitphinService;
import io.github.adhamura.gitphindroid.server.NativeServer;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelDialog;
import io.github.adhamura.gitphindroid.ui.pixel.PixelProgressBar;
import io.github.adhamura.gitphindroid.ui.pixel.PixelTextView;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** "Share into a repo": commits the files shared by another app into a Gitphin repo, one commit per file. */
public class ShareActivity extends Activity implements ShareSaver.Host {
    private static final String PREFS = "gitphin";

    GitApi api;
    private ShareItems shared;
    private ShareForm form;
    private ShareSaver saver;

    private LinearLayout page;          // column inside the scroll view
    private LinearLayout startArea;
    private LinearLayout formArea;
    private volatile boolean gone;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        api = new GitApi(this);
        shared = new ShareItems(this, getIntent());
        buildShell();
        form = new ShareForm(this, shared, formArea);
        saver = new ShareSaver(api, getContentResolver(), shared.usable, form.tips, this);
        startAndLoad();
    }

    @Override protected void onDestroy() {
        gone = true;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- shell

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildShell() {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(8), dp(8), dp(8), dp(16));

        startArea = new LinearLayout(this);
        startArea.setOrientation(LinearLayout.VERTICAL);
        startArea.setPadding(dp(4), dp(12), dp(4), dp(12));
        PixelTextView st = new PixelTextView(this);
        st.setScale(2);
        st.setColor(getColor(R.color.px_fg));
        st.setText("Starting Gitphin...");
        startArea.addView(st);
        PixelProgressBar sb = new PixelProgressBar(this, 12, getColor(R.color.px_fg));
        sb.set(-1, true);
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbl.topMargin = dp(12);
        startArea.addView(sb, sbl);
        page.addView(startArea);

        formArea = new LinearLayout(this);
        formArea.setOrientation(LinearLayout.VERTICAL);
        formArea.setVisibility(View.GONE);
        page.addView(formArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.px_page));
        sv.setFillViewport(true);
        sv.addView(page);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.px_chrome));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(6), dp(12), dp(6));
        PixelButton cancel = new PixelButton(this, "Cancel");
        cancel.setOnClickListener(v -> finish());
        top.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        PixelTextView title = new PixelTextView(this);
        title.setScale(3);
        title.setColor(getColor(R.color.px_on_chrome));
        title.setText("Save to repo");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(12);
        top.addView(title, tl);
        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View border = new View(this);
        border.setBackgroundColor(getColor(R.color.px_on_chrome));
        root.addView(border, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, bt;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = in.left; t = in.top; r = in.right; bt = in.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                bt = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, bt);
            return insets;
        });
        setContentView(root);
    }

    // ---------------------------------------------------------------- helpers

    @Override public void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!gone && !isFinishing()) r.run();
        });
    }

    void fail(String title, String msg, boolean quit) {
        PixelDialog.with(this).title(title).message(msg).cancelable(!quit)
                .primary("OK", quit ? this::finish : null).show();
    }

    SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override public boolean isGone() {
        return gone;
    }


    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    // ---------------------------------------------------------------- server start and load

    private void startAndLoad() {
        new Thread(() -> {
            String err = ensureServer();
            if (err != null) {
                ui(() -> fail("Cannot start Gitphin", err, true));
                return;
            }
            GitApi.Result r = api.get("/api/git/repos");
            if (!r.ok) {
                ui(() -> fail("Cannot read repos", r.error, true));
                return;
            }
            List<String> names = new ArrayList<>();
            JSONArray arr = r.json.optJSONArray("repos");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    String n = o == null ? "" : o.optString("name", "");
                    if (!n.isEmpty()) names.add(n);
                }
            }
            ui(() -> {
                form.repos = names;
                startArea.setVisibility(View.GONE);
                formArea.setVisibility(View.VISIBLE);
                form.build();
            });
        }, "share-load").start();
    }

    /** Returns null when the server runs, else an error text. */
    private String ensureServer() {
        if (isUp()) return null;
        Intent i = new Intent(this, GitphinService.class).setAction(GitphinService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException e) {
            return "Cannot start the service: " + e.getMessage();
        }
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end && !gone) {
            if (isUp()) return null;
            if (GitphinService.state == GitphinService.STATE_ERROR) {
                String e = GitphinService.error;
                return e == null || e.isEmpty() ? "The server reported an error." : e;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return "Interrupted.";
            }
        }
        return isUp() ? null : "The server did not start in 10 seconds.";
    }

    private static boolean isUp() {
        try {
            return GitphinService.state == GitphinService.STATE_RUNNING && NativeServer.nativeIsRunning();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- save

    void onSave() {
        ShareForm f = form;
        if (f.saving || f.refsLoading || f.branch.isEmpty()) return;
        final String folder = ShareSaver.cleanFolder(f.folderEdit.getText().toString());
        if (folder == null) {
            fail("Bad folder", "The folder cannot contain \"..\" or \".\".", false);
            return;
        }
        final List<String> paths = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < shared.usable.size(); i++) {
            String n = shared.usable.get(i).name;
            if (f.nameEdit != null) {
                n = f.nameEdit.getText().toString().trim();
                if (n.isEmpty() || n.contains("/") || n.contains("\\") || n.equals("..") || n.equals(".")) {
                    fail("Bad file name", "Use a file name without \"/\" and without \"..\".", false);
                    return;
                }
            } else {
                n = ShareSaver.uniqueName(n, seen);
            }
            seen.add(n);
            names.add(n);
            paths.add(folder.isEmpty() ? n : folder + "/" + n);
        }
        final String message = f.msgEdit.getText().toString().trim();
        final String author = f.authorEdit.getText().toString().trim();
        final String email = f.emailEdit.getText().toString().trim();
        prefs().edit().putString("share_repo", f.repo).putString("share_folder", folder)
                .putString("share_author", author).putString("share_email", email).apply();

        f.saving = true;
        f.saveBtn.setEnabled(false);
        f.progressBox.setVisibility(View.VISIBLE);
        f.bar.set(0, true);
        final String theRepo = f.repo, theBranch = f.branch;
        new Thread(() -> saver.run(theRepo, theBranch, folder, paths, names, message, author, email),
                "share-save").start();
    }

    @Override public void progress(String text, int percent) {
        form.progressText.setText(text);
        form.bar.set(percent, true);
    }

    @Override public void finished(String theRepo, String folder, int saved, int total, List<String> failures) {
        form.saving = false;
        form.saveBtn.setEnabled(true);
        form.progressBox.setVisibility(View.GONE);
        if (failures.isEmpty()) {
            PixelToast.show(this, "Saved " + saved + (saved == 1 ? " file" : " files") + " to "
                    + theRepo + (folder.isEmpty() ? "" : "/" + folder));
            finish();
            return;
        }
        StringBuilder sb = new StringBuilder("Saved " + saved + " of " + total + ".\n");
        for (String fl : failures) sb.append("\n").append(fl);
        fail("Some files failed", sb.toString(), false);
    }
}
