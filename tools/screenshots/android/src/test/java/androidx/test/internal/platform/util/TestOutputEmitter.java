/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.internal.platform.util;
public final class TestOutputEmitter { public static void dumpThreadStates(String s) {} }
