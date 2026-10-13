/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.internal.runner.lifecycle;
import androidx.test.runner.lifecycle.*;
import android.app.Activity;
import java.util.*;
public class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
    private final Map<Activity, Stage> stages = new WeakHashMap<>();
    private final List<ActivityLifecycleCallback> cbs = new ArrayList<>();
    public void signalLifecycleChange(Stage s, Activity a) {
        stages.put(a, s);
        for (ActivityLifecycleCallback c : new ArrayList<>(cbs)) c.onActivityLifecycleChanged(a, s);
    }
    public void addLifecycleCallback(ActivityLifecycleCallback c) { cbs.add(c); }
    public void removeLifecycleCallback(ActivityLifecycleCallback c) { cbs.remove(c); }
    public Stage getLifecycleStageOf(Activity a) { return stages.get(a); }
    public Collection<Activity> getActivitiesInStage(Stage s) {
        List<Activity> r = new ArrayList<>();
        for (Map.Entry<Activity, Stage> e : stages.entrySet()) if (e.getValue() == s) r.add(e.getKey());
        return r;
    }
}
