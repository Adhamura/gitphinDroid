package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.ui.pixel.PixelCheck;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;
import io.github.adhamura.gitphindroid.ui.pixel.UiScale;

import android.app.Activity;
import android.widget.LinearLayout;

/** Settings card "Display": UI size Normal / Large. A change applies at once. */
final class DisplayCard {
    DisplayCard(Activity a, LinearLayout col) {
        LinearLayout dc = Ui.card(col, "Display").content();
        dc.addView(Ui.label(a, "UI SIZE"));
        boolean large = UiScale.large(a);
        PixelCheck normal = new PixelCheck(a, "Normal");
        PixelCheck big = new PixelCheck(a, "Large");
        normal.setChecked(!large);
        big.setChecked(large);
        normal.setOnChange(() -> choose(a, "normal"));
        big.setOnChange(() -> choose(a, "large"));
        dc.addView(normal, Ui.match());
        dc.addView(big, Ui.match());
        dc.addView(Ui.body(a, "Large makes the buttons, the pixel text and the web page bigger.", 12,
                a.getColor(R.color.px_muted)));
    }

    private static void choose(Activity a, String size) {
        if (Prefs.getUiSize(a).equals(size)) {
            a.recreate(); // keeps the radio look right when the checked one was tapped
            return;
        }
        Prefs.setUiSize(a, size);
        a.recreate();
    }
}
