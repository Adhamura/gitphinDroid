package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.net.NetUtil;
import io.github.adhamura.gitphindroid.server.GitphinService;
import io.github.adhamura.gitphindroid.server.NativeServer;
import io.github.adhamura.gitphindroid.ui.activitylog.ActivityLogActivity;
import io.github.adhamura.gitphindroid.ui.onboarding.OnboardingActivity;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;
import io.github.adhamura.gitphindroid.ui.pixel.UiScale;
import io.github.adhamura.gitphindroid.ui.settings.SettingsActivity;
import io.github.adhamura.gitphindroid.update.UpdateFlow;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.webkit.WebView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/** The main screen: top bar with the addresses, and the web UI of the server in a WebView.
 *  Intent extras: "open_path" (String, URL path and hash, for example "/#repos/NAME") loads that
 *  page once the server runs; "show_qr" (boolean) opens the Wi-Fi QR dialog. */
public class MainActivity extends Activity {
    static final String EXTRA_OPEN_PATH = "open_path", EXTRA_SHOW_QR = "show_qr";
    private static final int REQ_NOTIF = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private WebGestureLayout webHost;
    private TopBar topBar;
    private WebViewSetup webSetup;
    private Downloader downloader;
    private boolean loaded;
    private int loadedPort;
    private String pendingOpenPath;   // waits for the server
    private boolean pendingQr;
    private boolean builtLarge;
    private OnBackInvokedCallback backCallback;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, topBar.progress().isShown() ? 250 : 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        builtLarge = UiScale.large(this);
        buildUi();
        if (!Prefs.getOnboarded(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        } else if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        sendToService(GitphinService.ACTION_START);
        handleIntent(getIntent());
        registerBack();
        UpdateFlow.autoCheck(this);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Takes "open_path" and "show_qr" from the intent; the extras are removed so that a
     *  recreate of the activity does not do them again. */
    private void handleIntent(Intent i) {
        if (i == null) return;
        String p = i.getStringExtra(EXTRA_OPEN_PATH);
        if (p != null) {
            i.removeExtra(EXTRA_OPEN_PATH);
            pendingOpenPath = p.startsWith("/") ? p : "/" + p; // a path only: the host stays 127.0.0.1
        }
        if (i.getBooleanExtra(EXTRA_SHOW_QR, false)) {
            i.removeExtra(EXTRA_SHOW_QR);
            pendingQr = true;
        }
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT < 33) return; // older: onKeyDown below
        backCallback = () -> {
            if (web.canGoBack()) web.goBack();
            else moveTaskToBack(true);
        };
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }

    @Override protected void onResume() {
        super.onResume();
        if (UiScale.large(this) != builtLarge) { // the UI size was changed in the settings
            recreate();
            return;
        }
        if (loaded && loadedPort != Prefs.getPort(this)) loaded = false;
        handler.post(poll);
        web.onResume();
        if (pendingQr) {
            pendingQr = false;
            handler.post(() -> QrDialog.showWifi(this));
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(poll);
        web.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        web.destroy();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        topBar.relayout();
    }

    private void sendToService(String action) {
        Intent i = new Intent(this, GitphinService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException e) {
            PixelToast.show(this, "Cannot start service: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.px_chrome)); // the status bar area takes this color

        topBar = new TopBar(this, base -> QrDialog.show(this, base), this::reload,
                () -> startActivity(new Intent(this, SettingsActivity.class)),
                () -> startActivity(new Intent(this, ActivityLogActivity.class)));
        root.addView(topBar, Ui.match());

        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.page_bg));
        downloader = new Downloader(this);
        webSetup = new WebViewSetup(this, web, downloader, () -> loaded = false, // the poll loads it again
                () -> webHost.refreshDone());
        webHost = new WebGestureLayout(this, web, this::reload, web::goBack, web::canGoBack);
        webHost.setBackgroundColor(getColor(R.color.page_bg));
        root.addView(webHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Ui.applyInsets(root);
        setContentView(root);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (!webSetup.onActivityResult(req, res, data)) super.onActivityResult(req, res, data);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == Downloader.REQ_STORAGE) downloader.onPermissionResult(res);
    }

    // ---------------------------------------------------------------- state polling

    private void reload() {
        if (GitphinService.state == GitphinService.STATE_RUNNING && loaded) {
            web.reload();
        } else {
            if (GitphinService.state != GitphinService.STATE_STARTING) {
                sendToService(GitphinService.ACTION_START);
            }
            loaded = false;
        }
    }

    private void setState(String msg) {
        List<String[]> r = new ArrayList<>();
        r.add(new String[] {"State", msg});
        topBar.setRows(r);
    }

    private void tick() {
        int st = GitphinService.state;
        int port = Prefs.getPort(this);
        boolean running;
        try {
            running = NativeServer.nativeIsRunning();
        } catch (Throwable t) {
            setState("Native library missing: " + t.getMessage());
            return;
        }
        String pstate = topBar.progress().update(running);
        if (st == GitphinService.STATE_ERROR && GitphinService.error != null) {
            setState(GitphinService.error);
        } else if (running) {
            List<String[]> rows = new ArrayList<>(NetUtil.addressRows(this, port, Prefs.getLan(this), true));
            if ("Packing".equals(pstate)) rows.add(new String[] {"State", "Packing repos"});
            topBar.setRows(rows);
        } else if (st == GitphinService.STATE_STARTING) {
            setState("Starting...");
        } else {
            setState("Stopped");
        }
        topBar.applyIcons(topBar.progress().via());
        if (running && (!loaded || pendingOpenPath != null)) {
            loaded = true;
            loadedPort = port;
            String path = pendingOpenPath != null ? pendingOpenPath : "/";
            pendingOpenPath = null;
            web.loadUrl("http://127.0.0.1:" + port + path);
        }
    }
}
