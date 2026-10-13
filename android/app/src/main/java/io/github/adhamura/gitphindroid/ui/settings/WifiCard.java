package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.ui.pixel.PixelButton;
import io.github.adhamura.gitphindroid.ui.pixel.PixelCheck;
import io.github.adhamura.gitphindroid.ui.pixel.PixelDialog;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.app.Activity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings card "Wi-Fi access": allow, sign-in, user, password, PC name. */
final class WifiCard {
    boolean origLan, origAuth;
    String origPw, origUser, origName;
    final PixelCheck lanCheck, authCheck;
    final EditText userEdit, pwEdit, nameEdit;
    private final LinearLayout authBox;
    private final TextView warn;

    WifiCard(Activity a, LinearLayout col, Runnable onChange) {
        origLan = Prefs.getLan(a);
        origPw = Prefs.getPassword(a);
        origAuth = Prefs.getWifiAuth(a);
        origUser = Prefs.getUser(a);
        origName = Prefs.getMdnsName(a);
        LinearLayout wc = Ui.card(col, "Wi-Fi access").content();
        lanCheck = new PixelCheck(a, "Allow Wi-Fi access");
        lanCheck.setChecked(origLan);
        wc.addView(lanCheck, Ui.match());
        authCheck = new PixelCheck(a, "Ask for user name and password");
        authCheck.setChecked(origAuth);
        authCheck.setEnabled(origLan);
        wc.addView(authCheck, Ui.match());
        authBox = new LinearLayout(a);
        authBox.setOrientation(LinearLayout.VERTICAL);
        authBox.addView(Ui.label(a, "USER NAME (LETTERS, DIGITS, . _ -)"));
        int noSugg = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        userEdit = Ui.input(a, noSugg);
        userEdit.setText(origUser);
        authBox.addView(userEdit, Ui.fieldLp(a));
        authBox.addView(Ui.label(a, "PASSWORD"));
        pwEdit = Ui.input(a, noSugg);
        pwEdit.setText(origPw);
        authBox.addView(pwEdit, Ui.fieldLp(a));
        PixelButton newPw = new PixelButton(a, "New password");
        newPw.setOnClickListener(v -> pwEdit.setText(Prefs.generatePassword()));
        authBox.addView(newPw, Ui.wrapTop(a, 4));
        wc.addView(authBox, Ui.match());
        wc.addView(Ui.label(a, "PC NAME (gitphin.local)"));
        nameEdit = Ui.input(a, noSugg);
        nameEdit.setText(origName);
        wc.addView(nameEdit, Ui.fieldLp(a));
        warn = Ui.body(a, "", 14, a.getColor(R.color.px_bad));
        warn.setPadding(0, Ui.dp(a, 8), 0, 0);
        wc.addView(warn);

        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int x, int c, int d) { }
            @Override public void afterTextChanged(Editable e) { onChange.run(); }
        };
        pwEdit.addTextChangedListener(tw);
        userEdit.addTextChangedListener(tw);
        nameEdit.addTextChangedListener(tw);
        lanCheck.setOnChange(onChange);
        authCheck.setOnChange(() -> {
            if (!authCheck.isChecked()) {
                authCheck.setChecked(true); // Cancel keeps it on
                PixelDialog.with(a)
                        .title("Open access?")
                        .message("Anyone on this Wi-Fi network will be able to read, change and delete your repos without a password.")
                        .button("Cancel", null)
                        .primary("Turn off sign-in", () -> {
                            authCheck.setChecked(false);
                            onChange.run();
                        })
                        .show();
            }
            onChange.run();
        });
    }

    String cleanName() {
        return nameEdit.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The guide may have changed the Wi-Fi switch: take it over so we do not undo it. */
    void onResume(Activity a) {
        boolean p = Prefs.getLan(a);
        if (p != origLan) {
            origLan = p;
            lanCheck.setChecked(p);
        }
    }

    /** Updates the parts that depend on the switches. */
    void refresh() {
        boolean ask = authCheck.isChecked();
        authCheck.setEnabled(lanCheck.isChecked());
        authBox.setVisibility(ask ? View.VISIBLE : View.GONE);
        warn.setText(ask
                ? "Anyone on this Wi-Fi who knows the user name and password can read and change your repos."
                : "No sign-in: anyone on this Wi-Fi can read and change your repos.");
    }
}
