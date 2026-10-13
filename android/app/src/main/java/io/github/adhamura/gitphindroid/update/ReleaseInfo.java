package io.github.adhamura.gitphindroid.update;

/** One GitHub release, as far as the update check needs it. */
public final class ReleaseInfo {
    /** Version without the leading "v", for example "1.2.3". */
    public final String version;
    public final String notes;
    public final String pageUrl;
    /** The APK asset; apkUrl is null when the release has none. */
    public final String apkUrl;
    public final long apkSize;

    ReleaseInfo(String version, String notes, String pageUrl, String apkUrl, long apkSize) {
        this.version = version;
        this.notes = notes;
        this.pageUrl = pageUrl;
        this.apkUrl = apkUrl;
        this.apkSize = apkSize;
    }
}
