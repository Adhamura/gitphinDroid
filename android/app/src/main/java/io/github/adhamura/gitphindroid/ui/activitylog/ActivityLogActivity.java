package io.github.adhamura.gitphindroid.ui.activitylog;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.ActivityLog;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelCard;
import io.github.adhamura.gitphindroid.ui.pixel.PixelDialog;
import io.github.adhamura.gitphindroid.ui.pixel.PixelIcon;
import io.github.adhamura.gitphindroid.ui.pixel.PixelTextView;
import io.github.adhamura.gitphindroid.widget.WidgetRenderer;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Recent activity, grouped by day, in the pixel style of the settings screen. */
public class ActivityLogActivity extends Activity {
    private LinearLayout col;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long shownVersion = -1;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (shownVersion != ActivityLog.version) rebuild();
            handler.postDelayed(this, 2000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), dp(8), dp(8), dp(16));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.px_page));
        sv.addView(col);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.px_chrome));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(12), dp(6));
        PixelButton back = new PixelButton(this, "Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, wrap());
        PixelTextView title = new PixelTextView(this);
        title.setScale(3);
        title.setColor(getColor(R.color.px_on_chrome));
        title.setText("Activity");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = dp(12);
        bar.addView(title, tl);
        PixelButton clear = new PixelButton(this, "Clear");
        clear.setOnClickListener(v -> confirmClear());
        bar.addView(clear, wrap());
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View border = new View(this);
        border.setBackgroundColor(getColor(R.color.px_on_chrome));
        root.addView(border, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, bt;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
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
        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void confirmClear() {
        PixelDialog.with(this)
                .title("Clear activity?")
                .message("This removes the whole list.")
                .button("Cancel", null)
                .destructive("Clear", () -> {
                    ActivityLog.clear(this);
                    rebuild();
                })
                .show();
    }

    private void rebuild() {
        shownVersion = ActivityLog.version;
        col.removeAllViews();
        List<ActivityLog.Entry> list = ActivityLog.all(this);
        if (list.isEmpty()) {
            PixelCard c = newCard("Recent");
            TextView t = text("No activity yet. Pushes, pulls and server events show here.", 14,
                    getColor(R.color.px_fg));
            c.content().addView(t);
            return;
        }
        String curDay = null;
        PixelCard card = null;
        for (ActivityLog.Entry e : list) {
            String day = dayLabel(e.time);
            if (!day.equals(curDay)) {
                curDay = day;
                card = newCard(day);
            }
            card.content().addView(row(e), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private View row(ActivityLog.Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        PixelIcon icon = new PixelIcon(this);
        icon.setIcon(WidgetRenderer.iconFor(e.via));
        icon.setScale(1.5f);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        il.rightMargin = dp(10);
        row.addView(icon, il);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        PixelTextView kind = new PixelTextView(this);
        kind.setScale(1.5f);
        kind.setColor(getColor(R.color.px_muted));
        kind.setText(kindLabel(e.kind));
        head.addView(kind, wrap());
        if (!e.repo.isEmpty()) {
            TextView repo = text(e.repo, 15, getColor(R.color.px_fg));
            repo.setTypeface(null, android.graphics.Typeface.BOLD);
            repo.setSingleLine(true);
            repo.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams rl = wrap();
            rl.leftMargin = dp(8);
            head.addView(repo, rl);
        }
        mid.addView(head, wrap());
        String detail = detail(e);
        if (!detail.isEmpty()) {
            TextView d = text(detail, 12, getColor(R.color.px_muted));
            d.setMaxLines(2);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(d, wrap());
        }
        row.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        PixelTextView time = new PixelTextView(this);
        time.setScale(1.5f);
        time.setColor(getColor(R.color.px_muted));
        time.setText(timeLabel(e.time));
        LinearLayout.LayoutParams tl = wrap();
        tl.leftMargin = dp(8);
        row.addView(time, tl);

        if (!e.repo.isEmpty()) {
            StateListDrawable sd = new StateListDrawable();
            sd.addState(new int[] {android.R.attr.state_pressed}, new ColorDrawable(getColor(R.color.px_card2)));
            row.setBackground(sd);
            row.setClickable(true);
            row.setOnClickListener(v -> startActivity(ActivityLog.openIntent(this, "/#repos/" + e.repo)));
        }
        return row;
    }

    private static String detail(ActivityLog.Entry e) {
        StringBuilder sb = new StringBuilder(e.text);
        String size = e.objects > 0 ? e.objects + " obj" : "";
        String bytes = ActivityLog.size(e.bytes);
        if (!bytes.isEmpty()) size += (size.isEmpty() ? "" : ", ") + bytes;
        if (!size.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append(size);
        return sb.toString();
    }

    private static String kindLabel(String k) {
        switch (k) {
            case "push": return "Push";
            case "pull": return "Pull";
            case "upload": return "Upload";
            default: return "Server";
        }
    }

    private static String timeLabel(long t) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(t);
    }

    private static String dayLabel(long t) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (sameDay(now, c)) return "Today";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(now, c)) return "Yesterday";
        return new SimpleDateFormat("EEE d MMM yyyy", Locale.US).format(t);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private PixelCard newCard(String title) {
        PixelCard c = new PixelCard(this, title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        col.addView(c, lp);
        return c;
    }

    private TextView text(String t, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
