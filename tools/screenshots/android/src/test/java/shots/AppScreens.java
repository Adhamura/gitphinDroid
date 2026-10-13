package shots;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.ui.activitylog.ActivityLogActivity;
import io.github.adhamura.gitphindroid.ui.main.MainActivity;
import io.github.adhamura.gitphindroid.ui.onboarding.OnboardingActivity;
import io.github.adhamura.gitphindroid.ui.settings.SettingsActivity;
import io.github.adhamura.gitphindroid.ui.share.ShareActivity;

import static org.robolectric.Shadows.shadowOf;

/** Screenshots of the app's own screens (real Activities and views, drawn by Robolectric with
 *  native graphics). The JNI server is a shadow (ShadowNativeServer); the WebView area of the
 *  main screen shows a screenshot of the real web UI (-Dshots.web=<dir> from capture.cjs). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 36, qualifiers = "w412dp-h892dp-xhdpi", shadows = ShadowNativeServer.class,
        instrumentedPackages = {"io.github.adhamura.gitphindroid"})
public class AppScreens {
    static final String PW = "Tq7mWc2xKp9R";
    static final long NOW = System.currentTimeMillis();
    Context ctx;

    @Before public void prefs() {
        ctx = RuntimeEnvironment.getApplication();
        Prefs.save(ctx, 8080, true, true, "gitphin", PW);
        Prefs.setOnboarded(ctx, true);
        Prefs.setBarExpanded(ctx, true);
        ctx.getSharedPreferences("gitphin_update", Context.MODE_PRIVATE).edit().putBoolean("auto", false).commit();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_FINE_LOCATION");
        ShadowNativeServer.status = status("usb", "{\"show\":false,\"state\":\"\"}");
        io.github.adhamura.gitphindroid.server.GitphinService.state =
                io.github.adhamura.gitphindroid.server.GitphinService.STATE_RUNNING;
    }

    static String status(String via, String prog) {
        return "{\"running\":true,\"requests\":42,\"last\":\"GET /git/weather-station.git/info/refs\",\"activity\":0,"
                + "\"bind\":\"0.0.0.0\",\"port\":8080,\"via\":\"" + via + "\",\"prog\":" + prog + "}";
    }

    /** The rows NetUtil.addressRows() makes on a phone on a USB cable (adb), USB tethering and Wi-Fi. */
    static List<String[]> rows() {
        List<String[]> r = new ArrayList<>();
        r.add(new String[] {"Phone", "http://127.0.0.1:8080"});
        r.add(new String[] {"PC USB", "http://127.0.0.1:8080"});
        r.add(new String[] {"PC tether", "http://10.42.0.129:8080"});
        r.add(new String[] {"PC Wi-Fi", "http://192.168.1.42:8080"});
        r.add(new String[] {"User", "gitphin"});
        r.add(new String[] {"Password", PW});
        return r;
    }

    // ---------------------------------------------------------------- helpers

    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = (o instanceof Class ? (Class<?>) o : o.getClass()).getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o instanceof Class ? null : o, args);
    }

    static void idle() { shadowOf(Looper.getMainLooper()).idle(); }

    static Bitmap draw(View root) {
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        return b;
    }

    static void save(Bitmap b, String name) throws Exception {
        File f = new File(System.getProperty("shots.out"), name + ".png");
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        System.out.println("wrote " + f);
    }

    static void noCursor(View v) {
        if (v instanceof android.widget.EditText) ((android.widget.EditText) v).setCursorVisible(false);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) noCursor(g.getChildAt(i));
        }
    }

    static void save(Activity a, String name) throws Exception {
        idle();
        noCursor(a.getWindow().getDecorView());
        idle();
        save(draw(a.getWindow().getDecorView()), name);
    }

    /** The dialog shown last, drawn centered over a dimmed screen. */
    static Bitmap overDialog(Bitmap under, Dialog d) {
        View v = d.getWindow().getDecorView();
        int w = under.getWidth() - 48;
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(under.getHeight(), View.MeasureSpec.AT_MOST));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap out = under.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(out);
        c.drawColor(0x99000000);
        c.save();
        c.translate((out.getWidth() - v.getWidth()) / 2f, (out.getHeight() - v.getHeight()) / 2f);
        v.draw(c);
        c.restore();
        return out;
    }

    static void replaceText(View v, String from, String to) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().contains(from)) ((TextView) v).setText(t.toString().replace(from, to));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) replaceText(g.getChildAt(i), from, to);
        }
    }

    /** Robolectric's data folder is a temp folder: show the folder the phone uses. */
    void phonePaths(View root) {
        String data = ctx.getFilesDir().getParentFile().getAbsolutePath();
        replaceText(root, data, "/data/user/0/io.github.adhamura.gitphindroid");
    }

    void demoRepoFolders() {
        File git = new File(ctx.getFilesDir(), "ext/apps_data/gitphin/git");
        for (String n : new String[] {"dotfiles", "garden-app", "notes", "pcb-badge", "weather-station"}) {
            new File(git, n + ".git").mkdirs();
        }
    }

    /** MainActivity with the demo address rows and, in the WebView area, the web UI screenshot. */
    Activity main(String webShot) throws Exception {
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        Object bar = field(a, "topBar");
        call(bar, "setRows", new Class<?>[] {List.class}, rows());
        Object prog = call(bar, "progress", new Class<?>[0]);
        call(prog, "update", new Class<?>[] {boolean.class}, true);
        call(bar, "applyIcons", new Class<?>[] {String.class}, call(prog, "via", new Class<?>[0]));
        ViewGroup host = (ViewGroup) field(a, "webHost");
        File png = new File(System.getProperty("shots.web"), webShot + ".png");
        Bitmap web = BitmapFactory.decodeFile(png.getAbsolutePath());
        ImageView iv = new ImageView(a);
        iv.setImageBitmap(web);
        iv.setScaleType(ImageView.ScaleType.MATRIX);
        host.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        idle();
        android.graphics.Matrix m = new android.graphics.Matrix();
        float k = host.getWidth() / (float) web.getWidth();   // the page at the WebView width, from the top
        m.setScale(k, k);
        iv.setImageMatrix(m);
        idle();
        return a;
    }

    // ---------------------------------------------------------------- screens

    @Test public void mainScreen() throws Exception {
        save(main("phone-repos"), "main");
    }

    @Test @Config(qualifiers = "+night")
    public void pushing() throws Exception {
        Prefs.setBarExpanded(ctx, false);
        ShadowNativeServer.status = status("wifi", "{\"show\":true,\"live\":true,\"state\":\"Pushing\",\"pct\":64,"
                + "\"sub\":-1,\"line\":\"318 obj, 2.4 MB\",\"rate\":\"1.9 MB/s\"}");
        save(main("phone-dark"), "push");
    }

    @Test public void qr() throws Exception {
        Activity a = main("phone-repos");
        Bitmap under = draw(a.getWindow().getDecorView());
        call(Class.forName("io.github.adhamura.gitphindroid.ui.main.QrDialog"), "show",
                new Class<?>[] {Context.class, String.class}, a, "http://192.168.1.42:8080");
        idle();
        save(overDialog(under, ShadowDialog.getLatestDialog()), "qr");
    }

    @Test public void settings() throws Exception {
        demoRepoFolders();
        Activity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        idle();
        phonePaths(a.getWindow().getDecorView());
        save(a, "settings");
    }

    @Test public void activity() throws Exception {
        JSONArray log = new JSONArray();
        Object[][] e = {
            {2, "push", "weather-station", "usb", "main: 3f2a91c -> 95b7e04 | Sensor: 4x pressure oversampling", 0, 0},
            {3, "push", "weather-station", "usb", "", 7, 4300},
            {18, "pull", "garden-app", "wifi", "", 142, 1_260_000},
            {47, "upload", "notes", "phone", "2026-10-12 standup.md", 1, 2100},
            {95, "push", "pcb-badge", "tether", "main: new -> 1c0de42 | Initial commit", 0, 0},
            {96, "push", "pcb-badge", "tether", "", 24, 880_000},
            {180, "pull", "dotfiles", "usb", "", 36, 52_000},
            {600, "server", "", "", "Server started on port 8080", 0, 0},
        };
        for (Object[] r : e) {
            JSONObject o = new JSONObject();
            o.put("time", NOW - ((Integer) r[0]) * 60_000L);
            o.put("kind", r[1]);
            o.put("repo", r[2]);
            o.put("via", r[3]);
            o.put("text", r[4]);
            o.put("objects", r[5]);
            o.put("bytes", ((Number) r[6]).longValue());
            log.put(o);
        }
        ctx.getSharedPreferences("gitphin_activity", Context.MODE_PRIVATE).edit().putString("log", log.toString()).commit();
        save(Robolectric.buildActivity(ActivityLogActivity.class).setup().get(), "activity");
    }

    @Test public void guide() throws Exception {
        Activity a = Robolectric.buildActivity(OnboardingActivity.class).setup().get();
        call(a, "show", new Class<?>[] {int.class}, 2);   // 3/4 PC over USB
        save(a, "guide");
        call(a, "show", new Class<?>[] {int.class}, 3);   // 4/4 Wi-Fi
        save(a, "guide-wifi");
    }

    @Test public void share() throws Exception {
        Prefs.save(ctx, Integer.getInteger("shots.port", 8097), true, true, "gitphin", PW);
        File dir = new File(ctx.getCacheDir(), "shared");
        dir.mkdirs();
        String[] names = {"IMG_20261012_181502.jpg", "wiring-notes.md"};
        ArrayList<Uri> uris = new ArrayList<>();
        for (String n : names) {
            File f = new File(dir, n);
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(new byte[n.endsWith(".jpg") ? 1_843_200 : 2_400]); }
            uris.add(Uri.parse("content://shots.files/" + n));
        }
        DemoFiles.dir = dir;
        Robolectric.buildContentProvider(DemoFiles.class).create("shots.files");
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        Activity a = Robolectric.buildActivity(ShareActivity.class, i).setup().get();
        View form = (View) field(a, "formArea");
        for (int k = 0; k < 100 && form.getVisibility() != View.VISIBLE; k++) { Thread.sleep(100); idle(); }   // the repo list comes from the server
        idle();
        save(a, "share");
    }

    @Test public void widget() throws Exception {
        JSONArray log = new JSONArray();
        JSONObject o = new JSONObject();
        o.put("time", NOW - 120_000L);
        o.put("kind", "push");
        o.put("repo", "weather-station");
        o.put("via", "usb");
        o.put("text", "main: 3f2a91c -> 95b7e04 | Sensor: 4x pressure oversampling");
        log.put(o);
        ctx.getSharedPreferences("gitphin_activity", Context.MODE_PRIVATE).edit().putString("log", log.toString()).commit();
        Class<?> r = Class.forName("io.github.adhamura.gitphindroid.widget.WidgetRenderer");
        Bitmap b = (Bitmap) call(r, "render", new Class<?>[] {Context.class, int.class, int.class}, ctx, 300, 110);
        Bitmap out = Bitmap.createBitmap(b.getWidth() + 64, b.getHeight() + 64, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF3B4A5C);
        c.drawBitmap(b, 32, 32, new Paint());
        save(out, "widget");
    }
}
