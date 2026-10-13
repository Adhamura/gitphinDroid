package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.server.NativeServer;
import io.github.adhamura.gitphindroid.ui.pixel.PixelIcon;
import io.github.adhamura.gitphindroid.ui.pixel.PixelProgressBar;
import io.github.adhamura.gitphindroid.ui.pixel.PixelTextView;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;
import io.github.adhamura.gitphindroid.ui.pixel.UiScale;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONObject;

/** The transfer block of the top bar: state, percent, bars, line, rate. */
final class TransferProgress {
    private final Context c;
    final LinearLayout box;
    private final PixelTextView state, pct, line, rate, viaText;
    private final PixelProgressBar bar, sub;
    private final PixelIcon icon;
    private String via = "";
    private boolean shown;

    TransferProgress(Context c) {
        this.c = c;
        int c1 = c.getColor(R.color.px_on_chrome), c2 = c.getColor(R.color.px_on_chrome2);
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(View.GONE);
        setWide(false);

        LinearLayout r1 = new LinearLayout(c);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        r1.setMinimumHeight(Ui.dp(c, 20 * UiScale.factor(c)));
        LinearLayout stateBox = new LinearLayout(c);
        stateBox.setGravity(Gravity.CENTER_VERTICAL);
        icon = new PixelIcon(c);
        icon.setScale(2);
        icon.setColor(c1);
        icon.setVisibility(View.GONE);
        LinearLayout.LayoutParams ip = Ui.wrap();
        ip.rightMargin = Ui.dp(c, 6);
        stateBox.addView(icon, ip);
        state = pixel(2, c1);
        stateBox.addView(state, Ui.wrap());
        viaText = pixel(1, c2);
        viaText.setVisibility(View.GONE);
        LinearLayout.LayoutParams vp = Ui.wrap();
        vp.leftMargin = Ui.dp(c, 6);
        stateBox.addView(viaText, vp);
        r1.addView(stateBox, Ui.weight(1f));
        pct = pixel(2, c1);
        r1.addView(pct, Ui.wrap());
        box.addView(r1, Ui.match());

        bar = new PixelProgressBar(c, 10, c1);
        box.addView(bar, Ui.wrapTopMatch(c, 4));
        sub = new PixelProgressBar(c, 4, c1);
        sub.setVisibility(View.GONE);
        box.addView(sub, Ui.wrapTopMatch(c, 4));

        LinearLayout r3 = new LinearLayout(c);
        line = pixel(1, c2);
        r3.addView(line, Ui.weight(1f));
        rate = pixel(1, c2);
        r3.addView(rate, Ui.wrap());
        box.addView(r3, Ui.wrapTopMatch(c, 4));
    }

    private PixelTextView pixel(float scale, int color) {
        PixelTextView t = new PixelTextView(c);
        t.setScale(scale);
        t.setColor(color);
        return t;
    }

    /** Wide screens put the block at the right of the address rows: less padding. */
    void setWide(boolean wide) {
        if (wide) box.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 4), 0);
        else box.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 6));
    }

    boolean isShown() { return shown; }

    String via() { return via; }

    /** Reads the status; returns the "prog" state text ("" when the block is shown or none). */
    String update(boolean running) {
        boolean show = false;
        String st = "";
        if (running) {
            try {
                JSONObject s = new JSONObject(NativeServer.nativeStatus());
                via = s.optString("via", "");
                JSONObject pr = s.optJSONObject("prog");
                if (pr != null) {
                    st = pr.optString("state", "");
                    show = pr.optBoolean("show", false);
                    if (show) fill(pr, st);
                }
            } catch (Exception | Error ignored) {
                // status not readable: show nothing
            }
        }
        shown = show;
        box.setVisibility(show ? View.VISIBLE : View.GONE);
        return show ? "" : st;
    }

    private void fill(JSONObject pr, String st) {
        boolean live = pr.optBoolean("live", false);
        int p = pr.optInt("pct", -1), sb = pr.optInt("sub", -1);
        state.setText(st);
        pct.setText(live && p >= 0 ? p + "%" : "");
        bar.set(p, live);
        sub.setVisibility(sb >= 0 ? View.VISIBLE : View.GONE);
        sub.set(sb, live);
        line.setText(pr.optString("line", ""));
        rate.setText(pr.optString("rate", ""));
        String spec = LinkInfo.iconFor(via);
        boolean has = spec != null;
        if (has) {
            icon.setIcon(spec);
            icon.setContentDescription(LinkInfo.viaName(via));
            viaText.setText(LinkInfo.viaName(via));
        }
        icon.setVisibility(has ? View.VISIBLE : View.GONE);
        viaText.setVisibility(has ? View.VISIBLE : View.GONE);
    }
}
