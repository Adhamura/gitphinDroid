package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.net.NetUtil;
import io.github.adhamura.gitphindroid.ui.pixel.PixelQrView;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;

import android.content.Context;

import java.util.List;

/** Opens the QR dialog for the Wi-Fi address of the phone. */
final class QrDialog {
    private QrDialog() {}

    /** The QR of a given base URL (for example a row of the address bar). */
    static void show(Context c, String base) {
        if (!Prefs.getLan(c)) {
            PixelToast.show(c, "Turn on Wi-Fi access first");
            return;
        }
        boolean auth = Prefs.getWifiAuth(c);
        PixelQrView.showDialog(c, base, auth ? Prefs.getUser(c) : null,
                auth ? Prefs.getPassword(c) : null);
    }

    /** The QR of the Wi-Fi URL, found now. Toasts when Wi-Fi access is off or there is no address. */
    static void showWifi(Context c) {
        if (!Prefs.getLan(c)) {
            PixelToast.show(c, "Turn on Wi-Fi access first");
            return;
        }
        List<String[]> rows = NetUtil.addressRows(c, Prefs.getPort(c), true, false);
        for (String[] r : rows) {
            if (LinkInfo.isWifiRow(r[0])) {
                show(c, r[1]);
                return;
            }
        }
        PixelToast.show(c, "No Wi-Fi address found. Connect to Wi-Fi first.");
    }
}
