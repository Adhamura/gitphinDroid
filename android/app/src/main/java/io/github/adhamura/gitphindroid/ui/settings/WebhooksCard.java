package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelDialog;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;
import io.github.adhamura.gitphindroid.webhook.WebhookSender;

import android.app.Activity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Settings card "Webhooks": URLs, secret, test. */
final class WebhooksCard {
    private final Activity a;
    private final LinearLayout hookList;
    private final EditText hookEdit;

    WebhooksCard(Activity a, LinearLayout col) {
        this.a = a;
        LinearLayout hc = Ui.card(col, "Webhooks").content();
        hc.addView(Ui.body(a, "After a push changes a branch or tag, the phone sends a JSON message to these URLs.",
                14, a.getColor(R.color.px_fg)));
        hookList = new LinearLayout(a);
        hookList.setOrientation(LinearLayout.VERTICAL);
        hc.addView(hookList, Ui.match());
        hc.addView(Ui.label(a, "NEW URL (http:// OR https://)"));
        hookEdit = Ui.input(a, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hc.addView(hookEdit, Ui.fieldLp(a));
        PixelButton addHook = new PixelButton(a, "Add");
        addHook.setPrimary(true);
        addHook.setOnClickListener(v -> addWebhook());
        hc.addView(addHook, Ui.wrapTop(a, 4));
        hc.addView(Ui.label(a, "SECRET (OPTIONAL, SIGNS EACH MESSAGE)"));
        EditText secretEdit = Ui.input(a, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        secretEdit.setText(Prefs.getWebhookSecret(a));
        secretEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int s, int c, int d) { }
            @Override public void onTextChanged(CharSequence x, int s, int c, int d) { }
            @Override public void afterTextChanged(Editable e) {
                Prefs.setWebhookSecret(a, e.toString().trim());
            }
        });
        hc.addView(secretEdit, Ui.fieldLp(a));
        TextView sigNote = Ui.body(a, "With a secret, the header X-Gitphin-Signature-256 holds sha256= and the "
                + "HMAC-SHA256 of the body (same as GitHub).", 12, a.getColor(R.color.px_muted));
        sigNote.setPadding(0, Ui.dp(a, 4), 0, 0);
        hc.addView(sigNote);
        PixelButton testHook = new PixelButton(a, "Send test");
        testHook.setOnClickListener(v -> sendWebhookTest());
        hc.addView(testHook, Ui.wrapTop(a, 8));
        refresh();
    }

    private static String ago(long t) {
        return new java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.US).format(new java.util.Date(t));
    }

    void refresh() {
        hookList.removeAllViews();
        List<String> urls = Prefs.getWebhooks(a);
        WebhookSender ws = WebhookSender.get(a);
        if (urls.isEmpty()) {
            TextView none = Ui.body(a, "No URLs yet.", 12, a.getColor(R.color.px_muted));
            none.setPadding(0, Ui.dp(a, 8), 0, 0);
            hookList.addView(none);
        }
        for (String u : urls) {
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, Ui.dp(a, 8), 0, 0);
            TextView url = Ui.body(a, u, 14, a.getColor(R.color.px_fg));
            url.setTextIsSelectable(true);
            item.addView(url);
            WebhookSender.Status st = ws.lastResult(u);
            String text;
            int color;
            if (st == null || st.time == 0) {
                text = "Not sent yet";
                color = a.getColor(R.color.px_muted);
            } else if (st.ok()) {
                text = "OK " + st.code + ", " + ago(st.time);
                color = a.getColor(R.color.px_muted);
            } else {
                text = "Failed: " + st.error + ", " + ago(st.time)
                        + (st.retrying ? " (try " + st.tries + ", retrying)" : " (try " + st.tries + ")");
                color = a.getColor(R.color.px_bad);
            }
            item.addView(Ui.body(a, text, 12, color));
            PixelButton rm = new PixelButton(a, "Remove");
            rm.setOnClickListener(v -> {
                List<String> cur = Prefs.getWebhooks(a);
                cur.remove(u);
                Prefs.setWebhooks(a, cur);
                ws.forget(u);
                refresh();
            });
            item.addView(rm, Ui.wrapTop(a, 4));
            hookList.addView(item, Ui.match());
        }
    }

    private void addWebhook() {
        String u = hookEdit.getText().toString().trim();
        boolean ok = u.matches("(?i)https?://[^\\s/?#]+[^\\s]*");
        if (ok) {
            try {
                ok = new java.net.URI(u).getHost() != null;
            } catch (java.net.URISyntaxException e) {
                ok = false;
            }
        }
        if (!ok) {
            PixelToast.show(a, "Enter a URL that starts with http:// or https://");
            return;
        }
        List<String> cur = Prefs.getWebhooks(a);
        if (cur.contains(u)) {
            PixelToast.show(a, "This URL is already in the list.");
            return;
        }
        cur.add(u);
        Prefs.setWebhooks(a, cur);
        hookEdit.setText("");
        refresh();
    }

    private void sendWebhookTest() {
        if (Prefs.getWebhooks(a).isEmpty()) {
            PixelToast.show(a, "Add a URL first.");
            return;
        }
        PixelToast.show(a, "Sending test...");
        WebhookSender.get(a).sendTest((urls, results) -> {
            if (a.isDestroyed()) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < urls.size(); i++) {
                WebhookSender.Status st = results.get(i);
                sb.append(urls.get(i)).append('\n');
                sb.append(st == null ? "  no result" : st.ok() ? "  OK " + st.code : "  Failed: " + st.error).append("\n\n");
            }
            PixelDialog.with(a).title("Webhook test").message(sb.toString().trim())
                    .primary("OK", null).show();
            refresh();
        });
    }
}
