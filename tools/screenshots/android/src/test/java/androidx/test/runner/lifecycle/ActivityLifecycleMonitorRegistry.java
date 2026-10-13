/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.runner.lifecycle;
public final class ActivityLifecycleMonitorRegistry {
    private static ActivityLifecycleMonitor m;
    public static ActivityLifecycleMonitor getInstance() { return m; }
    public static void registerInstance(ActivityLifecycleMonitor x) { m = x; }
}
