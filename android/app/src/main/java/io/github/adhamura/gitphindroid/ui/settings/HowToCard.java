package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.net.NetUtil;
import io.github.adhamura.gitphindroid.ui.onboarding.OnboardingActivity;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelQrView;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.app.Activity;
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings card "How to connect": the addresses for this setup, QR, and the guide. */
final class HowToCard {
    private final Activity a;
    private final ServerCard server;
    private final WifiCard wifi;
    private final ReposFolderCard repos;
    private final TextView howTo;

    HowToCard(Activity a, LinearLayout col, ServerCard server, WifiCard wifi, ReposFolderCard repos) {
        this.a = a;
        this.server = server;
        this.wifi = wifi;
        this.repos = repos;
        LinearLayout hc = Ui.card(col, "How to connect").content();
        howTo = Ui.body(a, "", 14, a.getColor(R.color.px_fg));
        howTo.setTextIsSelectable(true);
        hc.addView(howTo);
        PixelButton qrBtn = new PixelButton(a, "Show QR code");
        qrBtn.setOnClickListener(v -> showQr());
        hc.addView(qrBtn, Ui.wrapTop(a, 8));
        PixelButton guide = new PixelButton(a, "Show the guide again");
        guide.setOnClickListener(v -> a.startActivity(new Intent(a, OnboardingActivity.class)));
        hc.addView(guide, Ui.wrapTop(a, 4));
        TextView applyNote = Ui.body(a, "Changes apply when you leave this screen.", 12, a.getColor(R.color.px_muted));
        applyNote.setPadding(0, Ui.dp(a, 8), 0, 0);
        hc.addView(applyNote);
    }

    private void showQr() {
        if (!wifi.lanCheck.isChecked()) {
            PixelToast.show(a, "Turn on Wi-Fi access first");
            return;
        }
        String ip = NetUtil.wifiIpv4(a);
        if (ip == null) {
            PixelToast.show(a, "No Wi-Fi address found. Connect to Wi-Fi first.");
            return;
        }
        String user = wifi.userEdit.getText().toString().trim();
        if (user.isEmpty()) user = wifi.origUser;
        String pw = wifi.pwEdit.getText().toString().trim();
        if (pw.isEmpty()) pw = wifi.origPw;
        PixelQrView.showDialog(a, "http://" + ip + ":" + server.portOrOrig(),
                wifi.authCheck.isChecked() ? user : null, pw);
    }

    void refresh() {
        wifi.refresh();
        int p = server.portOrOrig();
        String pw = wifi.pwEdit.getText().toString().trim();
        String user = wifi.userEdit.getText().toString().trim();
        if (user.isEmpty()) user = wifi.origUser;
        boolean lanOn = wifi.lanCheck.isChecked(), ask = wifi.authCheck.isChecked();
        StringBuilder sb = new StringBuilder();
        int noteStart = -1, noteEnd = -1;
        sb.append("On this phone: http://127.0.0.1:").append(p).append("/\n");
        sb.append("\nPC over USB cable (best, no password):\n")
                .append("1. Turn on USB debugging (Developer options).\n")
                .append("2. On the PC, install gitphin-usb (gitphinDroid/pc/install.sh).\n")
                .append("3. Plug in the cable. On the PC use http://127.0.0.1:").append(p)
                .append("/\n   git clone http://127.0.0.1:").append(p).append("/git/<name>.git\n");
        sb.append("\nPC over USB without PC software (no password):\n")
                .append("Turn on USB tethering. The address is shown in the top bar.\n");
        if (lanOn) {
            String ip = NetUtil.wifiIpv4(a);
            String host = ip != null ? ip : "<wifi-ip>";
            sb.append("\nPC on the same Wi-Fi:\nBrowser: http://").append(host).append(':').append(p)
                    .append("/\n");
            String cred = "";
            if (ask) {
                sb.append("(user name: ").append(user).append(", password: ").append(pw).append(")\n");
                cred = user + ":" + pw + "@";
            }
            sb.append("Clone: git clone http://").append(cred).append(host)
                    .append(':').append(p).append("/git/<name>.git");
            String nm = wifi.cleanName();
            if (!nm.matches("[a-z0-9-]{1,30}")) nm = wifi.origName;
            sb.append("\n\nBy name: http://").append(nm).append(".local:").append(p).append("/\n");
            noteStart = sb.length();
            sb.append("Works on most PCs (Windows 10+, macOS, Linux with Avahi). If it does not, use the IP address.");
            noteEnd = sb.length();
            if (!ask) sb.append("\n\nNo sign-in: anyone on this Wi-Fi can read and change your repos.");
            if (ip == null) sb.append("\n\nNo Wi-Fi address found. Connect to Wi-Fi first.");
        } else {
            sb.append("\nWi-Fi access is off: Wi-Fi clients are refused.");
        }
        sb.append("\n\nRepos are stored in: ").append(repos.currentGitDir().getAbsolutePath());
        if (Prefs.getGitDir(a).isEmpty()) sb.append("\nUninstalling the app deletes them.");
        SpannableString out = new SpannableString(sb);
        if (noteStart >= 0) {
            out.setSpan(new ForegroundColorSpan(a.getColor(R.color.px_muted)), noteStart, noteEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new RelativeSizeSpan(0.85f), noteStart, noteEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        howTo.setText(out);
    }
}
