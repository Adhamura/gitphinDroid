package io.github.adhamura.gitphindroid.update;

/** Turns the Markdown of a release into a few lines of plain text. */
final class ReleaseNotes {
    private ReleaseNotes() {}

    static String plain(String markdown, int maxLines) {
        if (markdown == null) return "";
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        boolean more = false;
        for (String raw : markdown.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.startsWith("```") || s.matches("[-*_=]{3,}")) continue;
            s = s.replaceAll("^#{1,6}\\s*", "")
                    .replaceAll("^[-*+]\\s+", "- ")
                    .replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                    .replaceAll("[*_`~]{1,3}", "")
                    .replaceAll("^>\\s?", "")
                    .trim();
            if (s.isEmpty()) continue;
            if (lines == maxLines) {
                more = true;
                break;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
            lines++;
        }
        if (more) sb.append("\n...");
        return sb.toString();
    }
}
