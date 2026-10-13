package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelCheck;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings card "Start": start with the phone, background running, app settings. */
public final class StartCard {
    private final Activity a;
    private final TextView battNote;
    private final PixelButton battBtn;

    StartCard(Activity a, LinearLayout col) {
        this.a = a;
        LinearLayout stc = Ui.card(col, "Start").content();
        PixelCheck bootCheck = new PixelCheck(a, "Start with the phone");
        bootCheck.setChecked(Prefs.getStartOnBoot(a));
        bootCheck.setOnChange(() -> Prefs.setStartOnBoot(a, bootCheck.isChecked()));
        stc.addView(bootCheck, Ui.match());
        battNote = Ui.body(a, "", 14, a.getColor(R.color.px_fg));
        battNote.setPadding(0, Ui.dp(a, 8), 0, 0);
        stc.addView(battNote);
        battBtn = new PixelButton(a, "Allow background running");
        battBtn.setPrimary(true);
        battBtn.setOnClickListener(v -> askIgnoreBattery(a));
        stc.addView(battBtn, Ui.wrapTop(a, 4));
        TextView xiaomi = Ui.body(a, "Xiaomi/HyperOS: also set Battery saver to 'No restrictions' and turn on "
                + "Autostart for Gitphin in the phone's app settings.", 12, a.getColor(R.color.px_muted));
        xiaomi.setPadding(0, Ui.dp(a, 8), 0, 0);
        stc.addView(xiaomi);
        PixelButton appSettings = new PixelButton(a, "App settings");
        appSettings.setOnClickListener(v -> openAppSettings(a));
        stc.addView(appSettings, Ui.wrapTop(a, 4));
        refresh();
    }

    public static boolean batteryAllowed(Activity a) {
        try {
            PowerManager pm = (PowerManager) a.getSystemService(Activity.POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(a.getPackageName());
        } catch (RuntimeException ignored) {
            return true; // unknown: treat as allowed
        }
    }

    public static void openAppSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (RuntimeException e) {
            PixelToast.show(a, "Cannot open the app settings.");
        }
    }

    public static void askIgnoreBattery(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (RuntimeException e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException e2) {
                PixelToast.show(a, "Cannot open the battery settings.");
            }
        }
    }

    void refresh() {
        boolean ok = batteryAllowed(a);
        battNote.setText(ok ? "Background running: allowed"
                : "The phone may stop Gitphin in the background to save battery.");
        battBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
    }
}
