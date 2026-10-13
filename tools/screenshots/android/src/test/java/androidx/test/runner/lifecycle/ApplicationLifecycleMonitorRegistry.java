/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.runner.lifecycle;
public final class ApplicationLifecycleMonitorRegistry {
    public static void registerInstance(ApplicationLifecycleMonitor x) {}
}
