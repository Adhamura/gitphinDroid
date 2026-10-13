/* Stand-in for androidx.test, which is only on Google's Maven: just what Robolectric calls (see build.gradle). */
package androidx.test.runner.lifecycle;
public enum ApplicationStage { PRE_ON_CREATE, CREATED }
