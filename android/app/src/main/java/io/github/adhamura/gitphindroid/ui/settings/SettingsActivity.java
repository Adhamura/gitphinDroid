package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.server.GitphinService;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** The settings screen: assembles the cards and saves the server settings when it is left. */
public class SettingsActivity extends Activity {
    private ServerCard server;
    private WifiCard wifi;
    private ReposFolderCard repos;
    private StartCard start;
    private CollectorCard collector;
    private WebhooksCard hooks;
    private HowToCard howTo;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 16));

        server = new ServerCard(this, col);
        wifi = new WifiCard(this, col, this::refreshHowTo);
        repos = new ReposFolderCard(this, col, this::restartServer);
        start = new StartCard(this, col);
        collector = new CollectorCard(this, col);
        hooks = new WebhooksCard(this, col);
        new DisplayCard(this, col);
        howTo = new HowToCard(this, col, server, wifi, repos);
        new AboutCard(this, col);

        server.edit().addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int x, int c, int d) { }
            @Override public void afterTextChanged(Editable e) { refreshHowTo(); }
        });

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.px_page));
        sv.addView(col);
        setContentView(Ui.screen(this, "Settings", sv));
        refreshHowTo();
    }

    private void refreshHowTo() {
        if (howTo != null) howTo.refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        collector.refresh(this);
        start.refresh();
        wifi.onResume(this);
        repos.onResume();
        refreshHowTo();
        hooks.refresh();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        repos.onActivityResult(req, res, data);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == CollectorCard.REQ_LOC) collector.refresh(this);
        else repos.onPermissionResult(code);
    }

    private void restartServer() {
        Intent i = new Intent(this, GitphinService.class).setAction(GitphinService.ACTION_RESTART);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException e) {
            PixelToast.show(this, "Cannot start service: " + e.getMessage());
        }
    }

    @Override protected void onPause() {
        super.onPause();
        int port = server.parsePort();
        if (port < 0) {
            PixelToast.show(this, "Invalid port: kept " + server.origPort);
            port = server.origPort;
        }
        boolean lan = wifi.lanCheck.isChecked();
        boolean auth = wifi.authCheck.isChecked();
        String pw = wifi.pwEdit.getText().toString().trim();
        if (pw.isEmpty()) pw = wifi.origPw;
        String user = wifi.userEdit.getText().toString().trim();
        if (!user.matches("[A-Za-z0-9._-]+")) {
            if (auth || !user.isEmpty()) {
                PixelToast.show(this, user.isEmpty() ? "User name is empty: kept " + wifi.origUser
                        : "User name may have letters, digits, . _ - only: kept " + wifi.origUser);
            }
            user = wifi.origUser;
        }
        String name = wifi.cleanName();
        if (!name.matches("[a-z0-9-]{1,30}")) {
            PixelToast.show(this, "PC name may have a-z, 0-9, - only (1-30): kept " + wifi.origName);
            name = wifi.origName;
        }
        if (port == server.origPort && lan == wifi.origLan && auth == wifi.origAuth
                && pw.equals(wifi.origPw) && user.equals(wifi.origUser) && name.equals(wifi.origName)) return;
        Prefs.setMdnsName(this, name);
        wifi.origName = name;
        Prefs.save(this, port, lan, auth, user, pw);
        server.setOrigPort(port);
        wifi.origLan = lan;
        wifi.origAuth = auth;
        wifi.origPw = pw;
        wifi.origUser = user;
        restartServer();
    }
}
