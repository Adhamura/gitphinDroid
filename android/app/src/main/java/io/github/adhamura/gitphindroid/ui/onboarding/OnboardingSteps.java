package io.github.adhamura.gitphindroid.ui.onboarding;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.server.GitphinService;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelCard;
import io.github.adhamura.gitphindroid.ui.pixel.PixelCheck;
import io.github.adhamura.gitphindroid.ui.pixel.PixelIcon;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;
import io.github.adhamura.gitphindroid.ui.settings.StartCard;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** The four cards of the first-start guide. A button that did its job shows a pixel check mark
 *  (refresh() looks again, for example when the user comes back from a system screen). */
final class OnboardingSteps {
    static final int COUNT = 4;
    private static final int REQ_NOTIF = 10;

    private final Activity a;
    private final List<Runnable> refreshers = new ArrayList<>();
    private TextView creds;
    private PixelCheck lanCheck;

    OnboardingSteps(Activity a) {
        this.a = a;
    }

    View card(int i) {
        refreshers.clear();
        switch (i) {
            case 0: return welcome();
            case 1: return permissions();
            case 2: return usb();
            default: return wifi();
        }
    }

    void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    // ---------------------------------------------------------------- helpers

    private TextView text(LinearLayout parent, String t, int sp, int color, int topDp) {
        TextView tv = Ui.body(a, t, sp, color);
        LinearLayout.LayoutParams lp = Ui.match();
        lp.topMargin = Ui.dp(a, topDp);
        parent.addView(tv, lp);
        return tv;
    }

    private TextView text(LinearLayout parent, String t) {
        return text(parent, t, 14, a.getColor(R.color.px_fg), 8);
    }

    /** A button with a check mark at its right; the mark shows while done is true. */
    private PixelButton action(LinearLayout parent, String label, boolean primary, Runnable click,
                               BooleanSupplier done) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        PixelButton b = new PixelButton(a, label);
        b.setPrimary(primary);
        b.setOnClickListener(v -> click.run());
        row.addView(b, Ui.wrap());
        PixelIcon ok = new PixelIcon(a);
        ok.setIcon(PixelIcon.CHECK);
        ok.setScale(2);
        ok.setColor(a.getColor(R.color.px_ok));
        ok.setContentDescription("Done");
        LinearLayout.LayoutParams ip = Ui.wrap();
        ip.leftMargin = Ui.dp(a, 8);
        row.addView(ok, ip);
        refreshers.add(() -> ok.setVisibility(done.getAsBoolean() ? View.VISIBLE : View.INVISIBLE));
        parent.addView(row, Ui.wrapTop(a, 4));
        return b;
    }

    private void open(Intent i, String fail) {
        try {
            a.startActivity(i);
        } catch (RuntimeException e) {
            PixelToast.show(a, fail);
        }
    }

    // ---------------------------------------------------------------- 1 welcome

    private View welcome() {
        PixelCard c = new PixelCard(a, "1/4  What is Gitphin");
        LinearLayout b = c.content();
        text(b, "Gitphin is a Git server with a web page, and it runs on this phone. "
                + "Your repos live here.");
        text(b, "You can reach it in three ways:");
        text(b, "- from this phone, in this app.\n- from a PC over a USB cable.\n"
                + "- from a PC or phone on the same Wi-Fi (you must allow it).");
        text(b, "This guide shows what to turn on. It takes one minute. You can skip it and "
                + "open it again later from the Settings.", 12, a.getColor(R.color.px_muted), 12);
        return c;
    }

    // ---------------------------------------------------------------- 2 permissions

    private boolean notifOk() {
        return Build.VERSION.SDK_INT < 33
                || a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean xiaomi() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    private View permissions() {
        PixelCard c = new PixelCard(a, "2/4  Phone permissions");
        LinearLayout b = c.content();
        text(b, "Notifications: the server shows its address and a Stop button in a notification.");
        action(b, "Allow notifications", true, () -> {
            if (Build.VERSION.SDK_INT >= 33) {
                a.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }, this::notifOk);
        text(b, "Background running: without it the phone may stop the server to save battery.");
        action(b, "Allow background running", true, () -> StartCard.askIgnoreBattery(a),
                () -> StartCard.batteryAllowed(a));
        if (xiaomi()) {
            text(b, "Xiaomi / HyperOS: open the app settings, turn on Autostart and set Battery saver "
                    + "to No restrictions. The app cannot see these two, so tick Done when you are ready.");
            PixelButton app = new PixelButton(a, "App settings");
            app.setOnClickListener(v -> StartCard.openAppSettings(a));
            b.addView(app, Ui.wrapTop(a, 4));
            PixelCheck done = new PixelCheck(a, "Done");
            done.setChecked(Prefs.getAutostartDone(a));
            done.setOnChange(() -> Prefs.setAutostartDone(a, done.isChecked()));
            b.addView(done, Ui.wrapTop(a, 4));
        }
        return c;
    }

    // ---------------------------------------------------------------- 3 USB

    private boolean adbOn() {
        try {
            return Settings.Global.getInt(a.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private View usb() {
        PixelCard c = new PixelCard(a, "3/4  PC over USB");
        LinearLayout b = c.content();
        text(b, "The best way for a PC: no password, fast. It needs three things.");
        text(b, "1. Turn on USB debugging in the Developer options.");
        action(b, "Developer options", true, () -> {
            try {
                a.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (RuntimeException e) {
                open(new Intent(Settings.ACTION_SETTINGS), "Cannot open the settings.");
            }
        }, this::adbOn);
        text(b, "2. Install the PC helper. On the PC, run this command:");
        TextView cmd = Ui.body(a, "gitphinDroid/pc/install.sh", 13, a.getColor(R.color.px_fg));
        cmd.setTextIsSelectable(true);
        cmd.setTypeface(android.graphics.Typeface.MONOSPACE);
        cmd.setPadding(Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(a.getColor(R.color.px_code));
        g.setStroke(Ui.dp(a, 2), a.getColor(R.color.px_bd));
        cmd.setBackground(g);
        b.addView(cmd, Ui.wrapTop(a, 4));
        text(b, "3. Plug in the cable and open http://127.0.0.1:" + Prefs.getPort(a)
                + " in the browser on the PC.");
        return c;
    }

    // ---------------------------------------------------------------- 4 Wi-Fi

    private View wifi() {
        PixelCard c = new PixelCard(a, "4/4  Wi-Fi (optional)");
        LinearLayout b = c.content();
        text(b, "With Wi-Fi access on, other devices on the same Wi-Fi can open Gitphin. "
                + "It is off by default. You can change it later in the Settings.");
        lanCheck = new PixelCheck(a, "Allow Wi-Fi access");
        lanCheck.setChecked(Prefs.getLan(a));
        lanCheck.setOnChange(() -> {
            Prefs.setLan(a, lanCheck.isChecked());
            restartServer();
            showCreds();
        });
        b.addView(lanCheck, Ui.wrapTop(a, 8));
        creds = text(b, "", 14, a.getColor(R.color.px_fg), 8);
        creds.setTextIsSelectable(true);
        showCreds();
        refreshers.add(() -> {
            lanCheck.setChecked(Prefs.getLan(a));
            showCreds();
        });
        return c; // "Finish" is the button of the bottom bar
    }

    private void showCreds() {
        if (!Prefs.getLan(a)) {
            creds.setText("Wi-Fi access is off.");
        } else if (Prefs.getWifiAuth(a)) {
            creds.setText("User name: " + Prefs.getUser(a) + "\nPassword: " + Prefs.getPassword(a));
        } else {
            creds.setText("No sign-in: anyone on this Wi-Fi can read and change your repos.");
        }
    }

    private void restartServer() {
        Intent i = new Intent(a, GitphinService.class).setAction(GitphinService.ACTION_RESTART);
        try {
            if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i);
            else a.startService(i);
        } catch (RuntimeException e) {
            PixelToast.show(a, "Cannot start service: " + e.getMessage());
        }
    }
}
