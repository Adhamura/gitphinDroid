package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.ui.pixel.PixelIcon;

/** Names and icons of the links (phone, USB, tether, Wi-Fi) and the rules that tie a row to one. */
final class LinkInfo {
    private LinkInfo() {}

    static String iconFor(String via) {
        switch (via) {
            case "usb": case "tether": return PixelIcon.USB;
            case "wifi": return PixelIcon.WIFI;
            case "phone": return PixelIcon.PHONE;
            default: return null;
        }
    }

    static String viaName(String via) {
        switch (via) {
            case "usb": return "USB";
            case "tether": return "USB tether";
            case "wifi": return "Wi-Fi";
            case "phone": return "Phone";
            default: return "";
        }
    }

    /** Is the row the one of the link in use? */
    static boolean rowMatches(String label, String via) {
        switch (via) {
            case "usb": return label.equals("PC USB");
            case "tether": return label.equals("PC tether");
            case "wifi": return label.equals("PC Wi-Fi") || label.equals("PC hotspot");
            case "phone": return label.equals("Phone");
            default: return false;
        }
    }

    /** The link icon of a row, or null for rows that are not a link (Password, State, ...). */
    static String rowIcon(String label) {
        switch (label) {
            case "Phone": return PixelIcon.PHONE;
            case "PC USB": case "PC tether": return PixelIcon.USB;
            case "PC Wi-Fi": case "PC hotspot": case "PC ether": return PixelIcon.WIFI;
            default: return null;
        }
    }

    /** Slot of the row in the compact line: 0 phone, 1 USB, 2 tether, 3 Wi-Fi; -1 = none. */
    static int slotOf(String label) {
        switch (label) {
            case "Phone": return 0;
            case "PC USB": return 1;
            case "PC tether": return 2;
            case "PC Wi-Fi": case "PC hotspot": case "PC ether": return 3;
            default: return -1;
        }
    }

    static boolean isWifiRow(String label) {
        return label.equals("PC Wi-Fi") || label.equals("PC hotspot") || label.equals("PC ether");
    }
}
