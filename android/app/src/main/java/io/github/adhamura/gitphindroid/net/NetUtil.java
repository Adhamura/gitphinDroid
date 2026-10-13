package io.github.adhamura.gitphindroid.net;

import io.github.adhamura.gitphindroid.data.Prefs;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NetUtil {
    private NetUtil() {}

    /** Wi-Fi (or Ethernet) IPv4 address as text, or null when there is none. */
    public static String wifiIpv4(Context c) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                boolean ok = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
                LinkProperties lp = cm.getLinkProperties(n);
                if (ok && lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                            return a.getHostAddress();
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the interface scan
        }
        // Fallback: Wi-Fi hotspot or Wi-Fi without an active default route.
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = ni.getName();
                if (!ni.isUp() || !(name.startsWith("wlan") || name.startsWith("ap")
                        || name.startsWith("eth"))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // no address
        }
        return null;
    }

    /** One address a PC can use: label ("Wi-Fi", "USB", "Hotspot", "Ethernet") and IPv4 text. */
    static final class Addr {
        final String label, ip;
        Addr(String label, String ip) { this.label = label; this.ip = ip; }
    }

    /** Every IPv4 address on which a PC can reach the phone: Wi-Fi, USB tethering, hotspot,
     *  Ethernet. Mobile data and VPN interfaces are left out. */
    static List<Addr> pcAddresses() {
        List<Addr> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String label = labelOf(ni.getName());
                if (label == null) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        out.add(new Addr(label, a.getHostAddress()));
                    }
                }
            }
        } catch (Exception ignored) {
            // no address
        }
        return out;
    }

    private static String labelOf(String n) {
        if (n.startsWith("wlan")) return "Wi-Fi";
        if (n.startsWith("rndis") || n.startsWith("ncm") || n.startsWith("usb")) return "USB";
        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")) return "Hotspot";
        if (n.startsWith("eth")) return "Ethernet";
        return null;
    }

    /** Is the phone on a USB cable to a PC with USB debugging on (so adb forward can work)? */
    static boolean usbDebugCable(Context c) {
        boolean adb = android.provider.Settings.Global.getInt(
                c.getContentResolver(), android.provider.Settings.Global.ADB_ENABLED, 0) == 1;
        android.content.Intent st = c.registerReceiver(null,
                new android.content.IntentFilter("android.hardware.usb.action.USB_STATE"));
        boolean cable = st != null && st.getBooleanExtra("connected", false);
        return adb && cable;
    }

    /** The addresses as {label, value} rows, best first:
     *  Phone      http://127.0.0.1:port
     *  PC USB     http://127.0.0.1:port on the PC, through adb forward (gitphin-usb on the PC)
     *  PC tether  http://<tether ip>:port, no password
     *  PC Wi-Fi   http://<wifi ip>:port, only when Wi-Fi access is on (also "PC hotspot", "PC ether")
     *  User / Password   with Wi-Fi access on, an address shown and sign-in on (Password only withPassword)
     *  Sign-in    "off (open)" with Wi-Fi access on, an address shown and sign-in off */
    public static List<String[]> addressRows(Context c, int port, boolean wifiAccess, boolean withPassword) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Phone", "http://127.0.0.1:" + port});
        if (usbDebugCable(c)) {
            rows.add(new String[] {"PC USB", "http://127.0.0.1:" + port});
        }
        List<Addr> list = pcAddresses();
        boolean wifiShown = false;
        for (Addr a : list) {
            if (a.label.equals("USB")) {
                rows.add(new String[] {"PC tether", "http://" + a.ip + ":" + port});
            }
        }
        for (Addr a : list) {
            if (a.label.equals("USB") || !wifiAccess) continue;
            String l = a.label.equals("Hotspot") ? "PC hotspot"
                    : a.label.equals("Ethernet") ? "PC ether" : "PC Wi-Fi";
            rows.add(new String[] {l, "http://" + a.ip + ":" + port});
            wifiShown = true;
        }
        if (wifiShown) {
            if (Prefs.getWifiAuth(c)) {
                rows.add(new String[] {"User", Prefs.getUser(c)});
                if (withPassword) rows.add(new String[] {"Password", Prefs.getPassword(c)});
            } else {
                rows.add(new String[] {"Sign-in", "off (open)"});
            }
        }
        if (!wifiAccess) rows.add(new String[] {"Wi-Fi", "off"});
        return rows;
    }

    /** The same rows as text, one per line (for the notification). */
    public static String addressLines(Context c, int port, boolean wifiAccess, boolean withPassword) {
        StringBuilder sb = new StringBuilder();
        for (String[] r : addressRows(c, port, wifiAccess, withPassword)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(r[0]).append(": ").append(r[1]);
        }
        return sb.toString();
    }
}
