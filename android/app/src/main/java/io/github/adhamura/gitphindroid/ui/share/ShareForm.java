package io.github.adhamura.gitphindroid.ui.share;

import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.net.GitApi;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelCard;
import io.github.adhamura.gitphindroid.ui.pixel.PixelDialog;
import io.github.adhamura.gitphindroid.ui.pixel.PixelIcon;
import io.github.adhamura.gitphindroid.ui.pixel.PixelProgressBar;
import io.github.adhamura.gitphindroid.ui.pixel.PixelTextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The form of the share screen: the Files, Where and Commit cards, the pickers and the branch loading. */
final class ShareForm {
    private static final String FILE_ICON = "12:fe083082883c8049f48049f48049f4804ffc";

    private final ShareActivity act;
    private final ShareItems shared;
    private final LinearLayout formArea;

    PixelButton repoBtn, branchBtn, saveBtn;
    EditText folderEdit, nameEdit, msgEdit, authorEdit, emailEdit;
    LinearLayout progressBox;
    PixelProgressBar bar;
    TextView progressText;

    List<String> repos = new ArrayList<>();
    String repo;
    List<String> branches = new ArrayList<>();
    String branch = "";
    final Map<String, String> tips = new HashMap<>(); // branch name -> sha
    private int refsSeq;
    boolean refsLoading;
    boolean saving;

    ShareForm(ShareActivity act, ShareItems shared, LinearLayout formArea) {
        this.act = act;
        this.shared = shared;
        this.formArea = formArea;
    }

    private int dp(int v) {
        return act.dp(v);
    }

    private int getColor(int id) {
        return act.getColor(id);
    }

    private PixelCard card(String title) {
        PixelCard c = new PixelCard(act, title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        formArea.addView(c, lp);
        return c;
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    private LinearLayout.LayoutParams wrapLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    private View label(String t) {
        PixelTextView tv = new PixelTextView(act);
        tv.setScale(1);
        tv.setColor(getColor(R.color.px_muted));
        tv.setText(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView body(String t, int sp, int color) {
        TextView tv = new TextView(act);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private GradientDrawable box(int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(getColor(R.color.px_code));
        g.setStroke(dp(2), stroke);
        return g;
    }

    private EditText input(int type) {
        EditText e = new EditText(act);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setTextColor(getColor(R.color.px_fg));
        e.setMinHeight(dp(32));
        e.setPadding(dp(8), dp(4), dp(8), dp(4));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[] {android.R.attr.state_focused}, box(getColor(R.color.px_focus)));
        sl.addState(new int[] {}, box(getColor(R.color.px_bd)));
        e.setBackground(sl);
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cur = new GradientDrawable();
            cur.setColor(getColor(R.color.px_fg));
            cur.setSize(dp(2), dp(18));
            e.setTextCursorDrawable(cur);
        }
        return e;
    }

    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;

    void build() {
        int cMuted = getColor(R.color.px_muted), cFg = getColor(R.color.px_fg), cBad = getColor(R.color.px_bad);

        // ---- Files
        PixelCard files = card("Files");
        LinearLayout fc = files.content();
        if (shared.items.isEmpty()) {
            fc.addView(body("Nothing to save. The other app shared no file and no text.", 14, cBad));
        }
        for (ShareItems.Item it : shared.items) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            PixelIcon ic = new PixelIcon(act);
            ic.setIcon(FILE_ICON);
            ic.setScale(2);
            if (it.tooLarge) ic.setColor(cBad);
            row.addView(ic, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView n = body(it.name, 14, it.tooLarge ? cBad : cFg);
            col.addView(n);
            TextView s = body(it.tooLarge ? ShareItems.human(it.size) + " - too large (max 32 MB), skipped" : ShareItems.human(it.size),
                    12, it.tooLarge ? cBad : cMuted);
            col.addView(s);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cl.leftMargin = dp(12);
            row.addView(col, cl);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rl.topMargin = dp(6);
            fc.addView(row, rl);
        }

        if (repos.isEmpty()) {
            PixelCard where = card("Where");
            where.content().addView(body(
                    "No repos yet. Push a repo first or create one in the app.", 14, cFg));
            return;
        }
        if (shared.usable.isEmpty()) return; // nothing can be saved: only Cancel

        // ---- Where
        PixelCard where = card("Where");
        LinearLayout wc = where.content();
        String last = act.prefs().getString("share_repo", "");
        repo = repos.contains(last) ? last : repos.get(0);
        wc.addView(label("REPO"));
        repoBtn = new PixelButton(act, repo);
        repoBtn.setOnClickListener(v -> {
            if (saving) return;
            pick("Choose repo", repos, repos.indexOf(repo), i -> {
                if (!repos.get(i).equals(repo)) {
                    repo = repos.get(i);
                    repoBtn.setLabel(repo);
                    loadRefs();
                }
            });
        });
        wc.addView(repoBtn, wrapLp(4));
        wc.addView(label("BRANCH"));
        branchBtn = new PixelButton(act, "...");
        branchBtn.setOnClickListener(v -> {
            if (saving || refsLoading || branches.isEmpty()) return;
            pick("Choose branch", branches, branches.indexOf(branch), i -> {
                branch = branches.get(i);
                branchBtn.setLabel(branch);
            });
        });
        wc.addView(branchBtn, wrapLp(4));
        wc.addView(label("FOLDER (EMPTY = REPO ROOT)"));
        folderEdit = input(TEXT);
        folderEdit.setText(act.prefs().getString("share_folder", "inbox"));
        wc.addView(folderEdit, fieldLp());
        if (shared.usable.size() == 1) {
            wc.addView(label("FILE NAME"));
            nameEdit = input(TEXT);
            nameEdit.setText(shared.usable.get(0).name);
            wc.addView(nameEdit, fieldLp());
        }
        TextView note = body("A file with the same name is replaced.", 12, cMuted);
        note.setPadding(0, dp(8), 0, 0);
        wc.addView(note);

        // ---- Commit
        PixelCard commit = card("Commit");
        LinearLayout cc = commit.content();
        cc.addView(label("MESSAGE"));
        msgEdit = input(InputType.TYPE_CLASS_TEXT);
        msgEdit.setText(shared.usable.size() == 1 ? "Add " + shared.usable.get(0).name : "Add " + shared.usable.size() + " files");
        cc.addView(msgEdit, fieldLp());
        cc.addView(label("AUTHOR NAME"));
        authorEdit = input(TEXT);
        authorEdit.setText(act.prefs().getString("share_author", "me"));
        cc.addView(authorEdit, fieldLp());
        cc.addView(label("AUTHOR EMAIL"));
        emailEdit = input(TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailEdit.setText(act.prefs().getString("share_email", "me@phone.local"));
        cc.addView(emailEdit, fieldLp());

        // ---- Save + progress
        progressBox = new LinearLayout(act);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setVisibility(View.GONE);
        progressText = body("", 14, cFg);
        progressBox.addView(progressText);
        bar = new PixelProgressBar(act, 12, cFg);
        bar.set(0, true);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(8);
        progressBox.addView(bar, bl);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pl.bottomMargin = dp(8);
        formArea.addView(progressBox, pl);

        saveBtn = new PixelButton(act, "Save");
        saveBtn.setPrimary(true);
        saveBtn.setEnabled(false);
        saveBtn.setOnClickListener(v -> act.onSave());
        formArea.addView(saveBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        loadRefs();
    }

    /** Opens a pixel dialog with one button per choice. */
    private void pick(String title, List<String> options, int cur, Consumer<Integer> done) {
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] holder = new Dialog[1];
        for (int i = 0; i < options.size(); i++) {
            final int idx = i;
            PixelButton b = new PixelButton(act, options.get(i));
            b.setPrimary(i == cur);
            b.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                done.accept(idx);
            });
            list.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        holder[0] = PixelDialog.with(act).title(title).content(list).button("Cancel", null).show();
    }

    // ---------------------------------------------------------------- refs

    void loadRefs() {
        final int seq = ++refsSeq;
        final String name = repo;
        refsLoading = true;
        branchBtn.setLabel("...");
        saveBtn.setEnabled(false);
        new Thread(() -> {
            GitApi.Result r = act.api.get("/api/git/refs?name=" + GitApi.enc(name));
            act.ui(() -> {
                if (seq != refsSeq) return; // an older answer
                refsLoading = false;
                if (!r.ok) {
                    branches = new ArrayList<>();
                    branch = "";
                    branchBtn.setLabel("(error)");
                    act.fail("Cannot read branches", r.error, false);
                    return;
                }
                applyRefs(r.json);
                repoBtn.setLabel(repo);
                branchBtn.setLabel(branch);
                saveBtn.setEnabled(true);
            });
        }, "share-refs").start();
    }

    /** Fills branches and tips from a refs answer; keeps the chosen branch when it still exists. */
    private void applyRefs(JSONObject j) {
        String keep = branch;
        List<String> list = new ArrayList<>();
        tips.clear();
        JSONArray arr = j.optJSONArray("refs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String n = o.optString("name", "");
                if (!n.startsWith("refs/heads/")) continue;
                String short_ = n.substring(11);
                list.add(short_);
                tips.put(short_, o.optString("sha", ShareSaver.ZERO));
            }
        }
        String head = j.optString("head", "");
        if (head.startsWith("refs/heads/")) head = head.substring(11);
        if (list.isEmpty()) {
            // empty repo: the first commit creates the branch
            list.add(head.isEmpty() ? "main" : head);
        }
        branches = list;
        if (list.contains(keep)) branch = keep;
        else if (!head.isEmpty() && list.contains(head)) branch = head;
        else if (list.contains("main")) branch = "main";
        else if (list.contains("master")) branch = "master";
        else branch = list.get(0);
    }
}
