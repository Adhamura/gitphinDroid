package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelCheck;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;
import io.github.adhamura.gitphindroid.update.UpdateChecker;
import io.github.adhamura.gitphindroid.update.UpdateFlow;
import io.github.adhamura.gitphindroid.update.UpdatePrefs;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings card "About": version, update source, update check. */
final class AboutCard {
    AboutCard(Activity a, LinearLayout col) {
        LinearLayout c = Ui.card(col, "About").content();
        c.addView(Ui.body(a, "Gitphin Droid " + UpdateChecker.installedVersion(a), 14, a.getColor(R.color.px_fg)));
        TextView src = Ui.body(a, "Updates come from github.com/Adhamura/gitphinDroid",
                12, a.getColor(R.color.px_muted));
        src.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 8));
        c.addView(src);
        PixelCheck auto = new PixelCheck(a, "Check for updates automatically");
        auto.setChecked(UpdatePrefs.getAuto(a));
        auto.setOnChange(() -> UpdatePrefs.setAuto(a, auto.isChecked()));
        c.addView(auto, Ui.match());
        PixelButton check = new PixelButton(a, "Check for updates");
        check.setOnClickListener(v -> UpdateFlow.manualCheck(a));
        c.addView(check, Ui.wrapTop(a, 8));
    }
}
