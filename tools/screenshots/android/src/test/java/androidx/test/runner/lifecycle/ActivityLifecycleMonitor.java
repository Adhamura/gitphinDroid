/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.runner.lifecycle;
public interface ActivityLifecycleMonitor {
    void addLifecycleCallback(ActivityLifecycleCallback c);
    void removeLifecycleCallback(ActivityLifecycleCallback c);
    Stage getLifecycleStageOf(android.app.Activity a);
    java.util.Collection<android.app.Activity> getActivitiesInStage(Stage s);
}
