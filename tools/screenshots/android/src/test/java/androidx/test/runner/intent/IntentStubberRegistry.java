/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.runner.intent;
public final class IntentStubberRegistry {
    public static boolean isLoaded() { return false; }
    public static IntentStubber getInstance() { return null; }
}
