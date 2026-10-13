/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.internal.runner.lifecycle;
import androidx.test.runner.lifecycle.*;
public class ApplicationLifecycleMonitorImpl implements ApplicationLifecycleMonitor {
    public void signalLifecycleChange(android.app.Application a, ApplicationStage s) {}
}
