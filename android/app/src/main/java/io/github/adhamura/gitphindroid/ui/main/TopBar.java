package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelIcon;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;
import io.github.adhamura.gitphindroid.ui.pixel.UiScale;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.List;

/** The top bar of the main screen. Idle, the addresses are one line (link icons, main URL,
 *  buttons); a tap opens the full rows. On wide screens the rows, the transfer block and the
 *  buttons share one row. The bar is built again when the fold choice or the width changes. */
final class TopBar extends LinearLayout {
    private final AddressRows addr;
    private final TransferProgress prog;
    private final PixelButton reload, settings, activity;
    private final FrameLayout chevron;
    private final PixelIcon chevronIcon;

    TopBar(Context c, AddressRows.QrHandler qr, Runnable onReload, Runnable onSettings,
           Runnable onActivity) {
        super(c);
        setOrientation(VERTICAL);
        setBackgroundColor(c.getColor(R.color.px_chrome));
        addr = new AddressRows(c, qr);
        prog = new TransferProgress(c);
        reload = button(c, "Reload", onReload);
        settings = button(c, "Settings", onSettings);
        activity = button(c, "Activity", onActivity);
        chevronIcon = new PixelIcon(c);
        chevronIcon.setColor(c.getColor(R.color.px_on_chrome));
        chevron = new FrameLayout(c);
        chevron.setClickable(true);
        chevron.addView(chevronIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        chevron.setOnClickListener(v -> toggle());
        addr.compact.setOnClickListener(v -> toggle());
        rebuild();
    }

    private static PixelButton button(Context c, String label, Runnable r) {
        PixelButton b = new PixelButton(c, label);
        b.setSmall(true);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    TransferProgress progress() { return prog; }

    void setRows(List<String[]> rows) { addr.setRows(rows); }

    void applyIcons(String via) { addr.applyIcons(via); }

    private void toggle() {
        Prefs.setBarExpanded(getContext(), !Prefs.getBarExpanded(getContext()));
        rebuild();
    }

    /** Call when the screen size changed. */
    void relayout() { rebuild(); }

    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(getContext());
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout buttons(boolean vertical) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(vertical ? VERTICAL : HORIZONTAL);
        l.setGravity(vertical ? Gravity.END : Gravity.CENTER_VERTICAL);
        l.addView(reload, Ui.wrap());
        l.addView(settings, Ui.wrap());
        l.addView(activity, Ui.wrap());
        return l;
    }

    private void rebuild() {
        Context c = getContext();
        View[] parts = {addr.rowsBox, addr.compact, prog.box, reload, settings, activity, chevron};
        for (View v : parts) detach(v);
        removeAllViews();

        boolean wide = UiScale.wide(c), expanded = Prefs.getBarExpanded(c);
        boolean large = UiScale.large(c);
        boolean oneLine = c.getResources().getConfiguration().screenWidthDp >= (large ? 640 : 360);
        prog.setWide(wide);
        chevronIcon.setIcon(expanded ? PixelIcon.CHEV_UP : PixelIcon.CHEV_DOWN);
        chevronIcon.setScale(2);
        chevron.setContentDescription(expanded ? "Fold the addresses" : "Show all addresses");
        int pad = Ui.dp(c, 12), vpad = Ui.dp(c, 6);
        float f = UiScale.factor(c);
        chevron.setMinimumWidth(Ui.dp(c, 32 * f));
        chevron.setMinimumHeight(Ui.dp(c, 28 * f));

        LinearLayout bar = hbox();
        bar.setPadding(pad, vpad, pad, vpad);
        boolean progBelow = !wide;
        if (expanded) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setGravity(Gravity.END);
            col.addView(chevron, Ui.wrap());
            col.addView(buttons(true), Ui.wrap());
            LinearLayout.LayoutParams rp = Ui.weight(1f);
            rp.rightMargin = Ui.dp(c, 8);
            bar.addView(addr.rowsBox, rp);
            if (wide) bar.addView(prog.box, Ui.weight(1f));
            bar.addView(col, Ui.wrap());
            addView(bar, Ui.match());
        } else if (wide || oneLine) {
            bar.addView(addr.compact, Ui.weight(1f));
            if (wide) bar.addView(prog.box, Ui.weight(1.2f));
            LinearLayout.LayoutParams bp = Ui.wrap();
            bp.leftMargin = Ui.dp(c, 6);
            bar.addView(buttons(false), bp);
            bar.addView(chevron, Ui.wrap());
            addView(bar, Ui.match());
        } else {
            bar.addView(addr.compact, Ui.weight(1f));
            bar.addView(chevron, Ui.wrap());
            addView(bar, Ui.match());
            LinearLayout second = hbox();
            second.setGravity(Gravity.END);
            second.setPadding(pad, 0, pad, vpad);
            second.addView(buttons(false), Ui.wrap());
            addView(second, Ui.match());
        }
        if (progBelow) addView(prog.box, Ui.match());
        View border = new View(c);
        border.setBackgroundColor(c.getColor(R.color.px_on_chrome));
        addView(border, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 2)));
    }
}
