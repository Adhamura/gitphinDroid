package io.github.adhamura.gitphindroid.widget;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.server.GitphinService;
import io.github.adhamura.gitphindroid.server.ServiceState;
import io.github.adhamura.gitphindroid.ui.main.MainActivity;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick-settings tile: shows the server state, a tap starts or stops it. */
public class GitphinTileService extends TileService {

    public static void refresh(Context c) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, GitphinTileService.class));
        } catch (RuntimeException ignored) {
            // tile not added
        }
    }

    @Override public void onStartListening() {
        update();
    }

    @Override public void onTileAdded() {
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        int st = GitphinService.state;
        boolean on = st == GitphinService.STATE_RUNNING || st == GitphinService.STATE_STARTING;
        t.setLabel(getString(R.string.tile_label));
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_gitphin));
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(ServiceState.longLabel());
        t.updateTile();
    }

    @Override public void onClick() {
        int st = GitphinService.state;
        boolean on = st == GitphinService.STATE_RUNNING || st == GitphinService.STATE_STARTING;
        try {
            Intent i = new Intent(this, GitphinService.class)
                    .setAction(on ? GitphinService.ACTION_STOP : GitphinService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException e) {
            openApp();
        }
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 5, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
