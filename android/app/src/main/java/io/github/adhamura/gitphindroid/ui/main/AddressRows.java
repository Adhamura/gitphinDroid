package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.ui.pixel.PixelIcon;
import io.github.adhamura.gitphindroid.ui.pixel.PixelTextView;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;
import io.github.adhamura.gitphindroid.ui.pixel.UiScale;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The address rows of the top bar in two forms: the full rows, and one compact line (link icons
 *  and the main URL). Both are always built, the bar shows one of them. */
final class AddressRows {
    interface QrHandler { void show(String baseUrl); }

    private final Context c;
    private final QrHandler qr;
    /** The full rows. */
    final LinearLayout rowsBox;
    /** The compact line: four icon slots and the main URL. */
    final LinearLayout compact;
    private final PixelIcon[] slots = new PixelIcon[4];
    private final TextView mainUrl;
    private final List<String> rowKeys = new ArrayList<>();
    private final List<PixelIcon> rowIcons = new ArrayList<>();
    private List<String[]> shownRows;

    AddressRows(Context c, QrHandler qr) {
        this.c = c;
        this.qr = qr;
        rowsBox = new LinearLayout(c);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setGravity(Gravity.CENTER_VERTICAL);

        compact = new LinearLayout(c);
        compact.setGravity(Gravity.CENTER_VERTICAL);
        compact.setClickable(true);
        // the four slots keep their room, so the line does not jump when a link comes
        String[] names = {"Phone", "USB", "USB tether", "Wi-Fi"};
        String[] specs = {PixelIcon.PHONE, PixelIcon.USB, PixelIcon.USB, PixelIcon.WIFI};
        for (int i = 0; i < 4; i++) {
            slots[i] = new PixelIcon(c);
            slots[i].setIcon(specs[i]);
            slots[i].setScale(1);
            slots[i].setContentDescription(names[i]);
            slots[i].setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams p = Ui.wrap();
            p.rightMargin = Ui.dp(c, 4);
            compact.addView(slots[i], p);
        }
        mainUrl = new TextView(c);
        mainUrl.setTextSize(UiScale.sp(c, 13));
        mainUrl.setTypeface(Typeface.DEFAULT_BOLD);
        mainUrl.setTextColor(c.getColor(R.color.px_on_chrome2));
        mainUrl.setSingleLine(true);
        mainUrl.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams up = Ui.weight(1f);
        up.leftMargin = Ui.dp(c, 4);
        compact.addView(mainUrl, up);
    }

    private static boolean sameRows(List<String[]> a, List<String[]> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;
        return true;
    }

    void setRows(List<String[]> rows) {
        if (sameRows(rows, shownRows)) return;
        shownRows = rows;
        buildFull(rows);
        buildCompact(rows);
    }

    private void buildCompact(List<String[]> rows) {
        boolean[] has = new boolean[4];
        String url = null, phone = null;
        for (String[] r : rows) {
            int s = LinkInfo.slotOf(r[0]);
            if (s >= 0) has[s] = true;
            if (LinkInfo.isWifiRow(r[0]) && url == null) url = r[1];
            if (r[0].equals("Phone")) phone = r[1];
        }
        if (url == null) url = phone;
        if (url == null) url = rows.isEmpty() ? "" : rows.get(0)[1]; // a state text
        for (int i = 0; i < 4; i++) slots[i].setTag(has[i]);
        mainUrl.setText(url.startsWith("http://") ? url.substring(7) : url);
    }

    private void buildFull(List<String[]> rows) {
        rowsBox.removeAllViews();
        rowKeys.clear();
        rowIcons.clear();
        int c1 = c.getColor(R.color.px_on_chrome), c2 = c.getColor(R.color.px_on_chrome2);
        int keyW = Ui.dp(c, UiScale.large(c) ? 116 : 72);
        for (int i = 0; i < rows.size(); i++) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            PixelTextView k = new PixelTextView(c);
            k.setScale(1);
            k.setColor(c1);
            k.setText(rows.get(i)[0]);
            row.addView(k, new LinearLayout.LayoutParams(keyW, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView v = new TextView(c);
            v.setTextSize(UiScale.sp(c, 13));
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setTextColor(c2);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            v.setTextIsSelectable(true);
            v.setText(rows.get(i)[1]);
            row.addView(v, Ui.weight(1f));
            PixelIcon ic = new PixelIcon(c);
            ic.setIcon(PixelIcon.USB); // reserves the width and height
            ic.setScale(1);
            ic.setColor(c1);
            ic.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams icp = Ui.wrap();
            icp.leftMargin = Ui.dp(c, 4);
            row.addView(ic, icp);
            if (LinkInfo.isWifiRow(rows.get(i)[0])) {
                final String base = rows.get(i)[1];
                View.OnClickListener open = x -> qr.show(base);
                row.setOnClickListener(open);
                v.setOnClickListener(open);
            }
            rowKeys.add(rows.get(i)[0]);
            rowIcons.add(ic);
            LinearLayout.LayoutParams lp = Ui.match();
            if (i > 0) lp.topMargin = Ui.dp(c, 2);
            rowsBox.addView(row, lp);
        }
    }

    /** Every link shows its icon while the link is there; the link of the transfer going on,
     *  or of the last request served, is bright, the others are dim. */
    void applyIcons(String via) {
        int bright = c.getColor(R.color.px_on_chrome), dim = c.getColor(R.color.px_muted);
        for (int i = 0; i < rowIcons.size(); i++) {
            PixelIcon ic = rowIcons.get(i);
            String label = rowKeys.get(i);
            String spec = LinkInfo.rowIcon(label);
            if (spec == null) {
                ic.setVisibility(View.INVISIBLE);
                continue;
            }
            boolean inUse = LinkInfo.rowMatches(label, via);
            ic.setIcon(spec);
            ic.setColor(inUse ? bright : dim);
            ic.setContentDescription(inUse ? LinkInfo.viaName(via) + " (in use)" : label);
            ic.setVisibility(View.VISIBLE);
        }
        boolean[] inUse = new boolean[4];
        for (int i = 0; i < rowKeys.size(); i++) {
            int s = LinkInfo.slotOf(rowKeys.get(i));
            if (s >= 0 && LinkInfo.rowMatches(rowKeys.get(i), via)) inUse[s] = true;
        }
        for (int i = 0; i < 4; i++) {
            boolean present = Boolean.TRUE.equals(slots[i].getTag());
            slots[i].setVisibility(present ? View.VISIBLE : View.INVISIBLE);
            slots[i].setColor(inUse[i] ? bright : dim);
        }
    }
}
