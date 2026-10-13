/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.runner.intent;
public final class IntentMonitorRegistry { public static void registerInstance(IntentMonitor m) {} }
