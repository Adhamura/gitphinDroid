package shots;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** The JNI server for the screenshots: running, with a status the test sets. */
@Implements(className = "io.github.adhamura.gitphindroid.server.NativeServer", isInAndroidSdk = false)
public class ShadowNativeServer {
    public static String status = "{\"running\":true,\"requests\":42,\"last\":\"GET /api/info\",\"activity\":0,"
            + "\"bind\":\"0.0.0.0\",\"port\":8080,\"via\":\"usb\",\"prog\":{\"show\":false,\"state\":\"\"}}";

    @Implementation protected static void __staticInitializer__() {}
    @Implementation protected static int nativeStart(String root, String bind, int port, int wifi, String user, String pw) { return 0; }
    @Implementation protected static void nativeSetGitDir(String p) {}
    @Implementation protected static void nativeStop() {}
    @Implementation protected static boolean nativeIsRunning() { return true; }
    @Implementation protected static String nativeStatus() { return status; }
    @Implementation protected static void nativeSetDevice(String n, String v, int b, boolean c) {}
}
