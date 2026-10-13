package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.server.NativeServer;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.app.Activity;
import android.widget.LinearLayout;

/** Settings card "Collector": the location permission for the Wi-Fi name. */
final class CollectorCard {
    static final int REQ_LOC = 1;
    private final PixelButton locBtn;

    CollectorCard(Activity a, LinearLayout col) {
        LinearLayout cc = Ui.card(col, "Collector").content();
        cc.addView(Ui.body(a, "The Collector tab shows this phone's data. The Wi-Fi name needs the location permission.",
                14, a.getColor(R.color.px_fg)));
        locBtn = new PixelButton(a, "Allow location (Wi-Fi name)");
        locBtn.setPrimary(true);
        locBtn.setOnClickListener(v -> a.requestPermissions(new String[] {
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC));
        cc.addView(locBtn, Ui.wrapTop(a, 4));
        refresh(a);
    }

    void refresh(Activity a) {
        boolean ok = NativeServer.hasLocation(a);
        locBtn.setEnabled(!ok);
        locBtn.setLabel(ok ? "Location allowed" : "Allow location (Wi-Fi name)");
    }
}
