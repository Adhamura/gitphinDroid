package io.github.adhamura.gitphindroid.update;

/** Compares versions like "1.2.3" or "v1.2.3" (a "-suffix" is ignored). */
final class Semver {
    private Semver() {}

    /** The three numbers, or null when the text is not a version. */
    static int[] parse(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] parts = s.split("\\.");
        if (parts.length < 1 || parts.length > 3) return null;
        int[] v = new int[3];
        try {
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].matches("\\d{1,6}")) return null;
                v[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return v;
    }

    /** True when {@code candidate} is a higher version than {@code current}. False if either is unreadable. */
    static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate), b = parse(current);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }
}
