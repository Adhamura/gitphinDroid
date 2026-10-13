/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.internal.runner.intent;
public class IntentMonitorImpl implements androidx.test.runner.intent.IntentMonitor {
    public void signalIntent(android.content.Intent i) {}
}
