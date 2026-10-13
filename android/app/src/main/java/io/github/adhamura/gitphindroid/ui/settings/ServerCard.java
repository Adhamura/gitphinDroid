package io.github.adhamura.gitphindroid.ui.settings;

import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Settings card "Server": the port. */
final class ServerCard {
    int origPort;
    private final EditText portEdit;

    ServerCard(Activity a, LinearLayout col) {
        origPort = Prefs.getPort(a);
        LinearLayout sc = Ui.card(col, "Server").content();
        sc.addView(Ui.label(a, "PORT (1024-65535)"));
        portEdit = Ui.input(a, InputType.TYPE_CLASS_NUMBER);
        portEdit.setText(String.valueOf(origPort));
        sc.addView(portEdit, Ui.fieldLp(a));
    }

    EditText edit() { return portEdit; }

    void setOrigPort(int p) { origPort = p; }

    /** The port typed, or -1 when it is not valid. */
    int parsePort() {
        try {
            int p = Integer.parseInt(portEdit.getText().toString().trim());
            return (p >= 1024 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The port typed, or the saved one when the text is not valid. */
    int portOrOrig() {
        int p = parsePort();
        return p < 0 ? origPort : p;
    }
}
