package io.github.adhamura.gitphindroid.ui.onboarding;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.ui.main.MainActivity;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelDots;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** First-start guide: four steps with Back / Next / Skip and a step indicator. Shown once
 *  from MainActivity, and again from the settings. */
public class OnboardingActivity extends Activity {
    private OnboardingSteps steps;
    private LinearLayout stepBox;
    private PixelDots dots;
    private PixelButton back, skip, next;
    private int step;
    private OnBackInvokedCallback backCallback;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.setOnboarded(this, true); // shown once, whatever the user does
        steps = new OnboardingSteps(this);

        stepBox = new LinearLayout(this);
        stepBox.setOrientation(LinearLayout.VERTICAL);
        stepBox.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        dots = new PixelDots(this);
        LinearLayout.LayoutParams dp = Ui.wrap();
        dp.gravity = Gravity.CENTER_HORIZONTAL;
        dp.bottomMargin = Ui.dp(this, 12);
        stepBox.addView(dots, dp);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.px_page));
        sv.addView(stepBox);

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackgroundColor(getColor(R.color.px_chrome));
        nav.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        back = new PixelButton(this, "Back");
        back.setOnClickListener(v -> go(-1));
        nav.addView(back, Ui.wrap());
        skip = new PixelButton(this, "Skip");
        skip.setOnClickListener(v -> finishGuide());
        nav.addView(skip, Ui.wrap());
        nav.addView(Ui.spacer(this));
        next = new PixelButton(this, "Next");
        next.setPrimary(true);
        next.setOnClickListener(v -> go(1));
        nav.addView(next, Ui.wrap());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(sv, Ui.fillV());
        body.addView(nav, Ui.match());
        setContentView(Ui.screen(this, "Welcome to Gitphin", body, false));
        show(0);
        registerBack();
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT < 33) return; // older: onKeyDown
        backCallback = () -> go(-1);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            go(-1);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void go(int delta) {
        if (step + delta < 0) {
            finishGuide();
            return;
        }
        if (step + delta >= OnboardingSteps.COUNT) {
            finishGuide();
            return;
        }
        show(step + delta);
    }

    private void show(int i) {
        step = i;
        while (stepBox.getChildCount() > 1) stepBox.removeViewAt(1);
        stepBox.addView(steps.card(i), Ui.match());
        dots.set(OnboardingSteps.COUNT, i);
        boolean last = i == OnboardingSteps.COUNT - 1;
        back.setVisibility(i == 0 ? View.INVISIBLE : View.VISIBLE);
        skip.setVisibility(last ? View.GONE : View.VISIBLE);
        next.setLabel(last ? "Finish" : "Next");
        steps.refresh();
    }

    private void finishGuide() {
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        steps.refresh(); // the user may have changed a setting in the system screens
    }

    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        steps.refresh();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        steps.refresh();
    }
}
