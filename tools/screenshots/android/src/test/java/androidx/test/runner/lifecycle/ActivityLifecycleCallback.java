/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.runner.lifecycle;
public interface ActivityLifecycleCallback { void onActivityLifecycleChanged(android.app.Activity a, Stage s); }
