package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.qr.QrCode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Draws a QR code: square modules, no anti-aliasing, 4-module quiet zone. Always dark on
 *  light, in both themes, so cameras read it. */
public final class PixelQrView extends View {
    private static final int DARK = 0xFF14110D, LIGHT = 0xFFFFF4E0;
    private final Paint paint = new Paint();
    private final float d;
    private boolean[][] modules;

    PixelQrView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
    }

    void setText(String text) {
        modules = QrCode.encode(text);
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        int side = Math.min(Math.round(260 * d), MeasureSpec.getSize(w));
        setMeasuredDimension(side, side);
    }

    @Override protected void onDraw(Canvas cv) {
        if (modules == null) return;
        int n = modules.length + 8;
        int px = Math.max(1, Math.min(getWidth(), getHeight()) / n);
        int total = px * n;
        int ox = (getWidth() - total) / 2, oy = (getHeight() - total) / 2;
        paint.setColor(LIGHT);
        cv.drawRect(ox, oy, ox + total, oy + total, paint);
        paint.setColor(DARK);
        for (int y = 0; y < modules.length; y++) {
            for (int x = 0; x < modules.length; x++) {
                if (!modules[y][x]) continue;
                float l = ox + (x + 4) * px, t = oy + (y + 4) * px;
                cv.drawRect(l, t, l + px, t + px, paint);
            }
        }
    }

    /** Dialog "Scan to open". baseUrl is "http://IP:PORT"; user is null when sign-in is off. */
    public static void showDialog(Context c, String baseUrl, String user, String password) {
        final String browser = baseUrl + "/";
        final String hostPart = baseUrl.startsWith("http://") ? baseUrl.substring(7) : baseUrl;
        final String clone = "http://" + (user != null ? user + ":" + password + "@" : "")
                + hostPart + "/git/";
        final boolean creds = user != null;

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        final PixelQrView qr = new PixelQrView(c);
        box.addView(qr, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final TextView url = new TextView(c);
        url.setTextSize(UiScale.sp(c, 13));
        url.setTextColor(c.getColor(R.color.px_fg));
        url.setTextIsSelectable(true);
        url.setGravity(Gravity.CENTER);
        int top = Math.round(8 * d(c));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = top;
        box.addView(url, ulp);
        final TextView warn = new TextView(c);
        warn.setTextSize(UiScale.sp(c, 12));
        warn.setTextColor(c.getColor(R.color.px_bad));
        warn.setText("Contains the password");
        warn.setGravity(Gravity.CENTER);
        warn.setVisibility(View.GONE);
        box.addView(warn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final PixelButton toggle = new PixelButton(c, "QR for git clone");
        final boolean[] cloneMode = {false};
        Runnable apply = () -> {
            String s = cloneMode[0] ? clone : browser;
            try {
                qr.setText(s);
            } catch (IllegalArgumentException e) {
                PixelToast.show(c, "The address is too long for a QR code");
            }
            url.setText(s);
            warn.setVisibility(cloneMode[0] && creds ? View.VISIBLE : View.GONE);
            toggle.setLabel(cloneMode[0] ? "QR for browser" : "QR for git clone");
        };
        toggle.setOnClickListener(v -> {
            cloneMode[0] = !cloneMode[0];
            apply.run();
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = top;
        box.addView(toggle, tlp);
        apply.run();

        PixelDialog.with(c).title("Scan to open").content(box).primary("Close", null).show();
    }

    private static float d(Context c) { return c.getResources().getDisplayMetrics().density; }
}
