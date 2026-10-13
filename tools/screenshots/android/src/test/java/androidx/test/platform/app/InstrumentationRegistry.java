/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.platform.app;
public final class InstrumentationRegistry {
    private static android.app.Instrumentation inst;
    public static android.app.Instrumentation getInstrumentation() { return inst; }
    public static void registerInstance(android.app.Instrumentation i, android.os.Bundle b) { inst = i; }
}
