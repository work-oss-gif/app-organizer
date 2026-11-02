package com.example.wifiauto;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Wi-Fi Auto Connect.
 *
 * Android does not let an app silently join any Wi-Fi it likes. The official way (Android 10+)
 * is a "network suggestion": the app tells Android "please join this network when it is in range"
 * and the phone then connects by itself, every time, without you opening the app again.
 * This app stores your networks and hands them to Android as suggestions.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "wifi";
    private static final String KEY = "networks";
    private static final int WPA2 = 0, WPA3 = 1, OPEN = 2;

    private EditText ssidField, passField;
    private RadioGroup typeGroup;
    private CheckBox hiddenBox;
    private String status = "";
    // Keeps what you typed if the screen is rebuilt (rotation, dark-mode switch).
    private String draftSsid = "", draftPass = "";
    private int draftType = WPA2;
    private boolean draftHidden = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        build(); // dark mode toggled or screen rotated: redraw with the right colors
    }

    // ---------- colors ----------

    private boolean isDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
    private int bg()      { return isDark() ? 0xFF121417 : 0xFFF5F6F8; }
    private int card()    { return isDark() ? 0xFF1E2126 : 0xFFFFFFFF; }
    private int text()    { return isDark() ? 0xFFE8EAED : 0xFF1B1D21; }
    private int subtext() { return isDark() ? 0xFF9AA0A6 : 0xFF5F6368; }
    private int line()    { return isDark() ? 0xFF3A3F47 : 0xFFD0D4DA; }
    private int accent()  { return isDark() ? 0xFF6EA0FF : 0xFF1A5FE0; }
    private int onAccent(){ return isDark() ? 0xFF0B1220 : 0xFFFFFFFF; }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    // ---------- screen ----------

    private void build() {
        // Remember anything typed before we throw the old views away.
        if (ssidField != null) {
            draftSsid = ssidField.getText().toString();
            draftPass = passField.getText().toString();
            draftHidden = hiddenBox.isChecked();
            int id = typeGroup.getCheckedRadioButtonId();
            draftType = id == 1 ? WPA3 : id == 2 ? OPEN : WPA2;
        }

        getWindow().setStatusBarColor(bg());
        getWindow().setNavigationBarColor(bg());
        int flags = 0;
        if (!isDark()) {
            flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(28), dp(16), dp(24));
        scroll.addView(root);

        root.addView(label("Wi-Fi Auto Connect", 26, text(), true));
        root.addView(label("Add your networks once. Your phone will then join them by itself "
                + "whenever they are in range.", 14, subtext(), false), lp(0, 4, 0, 16));

        // --- add-network card ---
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(16), dp(16), dp(16));
        form.setBackground(box(card(), line()));
        root.addView(form, lp(0, 0, 0, 0));

        form.addView(label("Add a network", 16, text(), true));

        ssidField = input("Network name (SSID)", false);
        ssidField.setText(draftSsid);
        form.addView(ssidField, lp(0, 10, 0, 0));

        passField = input("Password", true);
        passField.setText(draftPass);
        form.addView(passField, lp(0, 10, 0, 0));

        final CheckBox show = new CheckBox(this);
        show.setText("Show password");
        show.setTextColor(subtext());
        show.setOnCheckedChangeListener((v, on) -> {
            passField.setInputType(InputType.TYPE_CLASS_TEXT | (on
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passField.setSelection(passField.getText().length());
        });
        form.addView(show);

        form.addView(label("Security", 13, subtext(), false), lp(0, 8, 0, 0));
        typeGroup = new RadioGroup(this);
        typeGroup.setOrientation(LinearLayout.HORIZONTAL);
        typeGroup.addView(radio("WPA2", 0));
        typeGroup.addView(radio("WPA3", 1));
        typeGroup.addView(radio("Open", 2));
        typeGroup.check(draftType == WPA3 ? 1 : draftType == OPEN ? 2 : 0);
        form.addView(typeGroup);

        hiddenBox = new CheckBox(this);
        hiddenBox.setText("Hidden network");
        hiddenBox.setTextColor(subtext());
        hiddenBox.setChecked(draftHidden);
        form.addView(hiddenBox);

        Button save = button("Save & auto-connect", true);
        save.setOnClickListener(v -> onSave());
        form.addView(save, lp(0, 12, 0, 0));

        if (!status.isEmpty()) {
            root.addView(label(status, 14, text(), false), lp(4, 12, 4, 0));
        }

        // --- saved networks ---
        root.addView(label("Saved networks", 16, text(), true), lp(4, 24, 0, 8));
        JSONArray all = load();
        if (all.length() == 0) {
            root.addView(label("Nothing saved yet.", 14, subtext(), false), lp(4, 0, 0, 0));
        }
        for (int i = 0; i < all.length(); i++) {
            final int index = i;
            JSONObject n = all.optJSONObject(i);
            if (n == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(8), dp(10));
            row.setBackground(box(card(), line()));

            LinearLayout names = new LinearLayout(this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.addView(label(n.optString("ssid"), 16, text(), true));
            String kind = n.optInt("type") == WPA3 ? "WPA3" : n.optInt("type") == OPEN ? "Open" : "WPA2";
            if (n.optBoolean("hidden")) kind += " · hidden";
            names.addView(label(kind, 13, subtext(), false));
            row.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));

            Button remove = button("Remove", false);
            remove.setOnClickListener(v -> onRemove(index));
            row.addView(remove);
            root.addView(row, lp(0, 0, 0, 8));
        }

        // --- help ---
        Button settings = button("Open Wi-Fi settings", false);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        root.addView(settings, lp(0, 16, 0, 0));

        root.addView(label("How it works: the first time a saved network is in range, Android asks "
                + "you to allow it (a notification or a pop-up). Tap Allow once and it connects "
                + "automatically from then on. If your phone is already on another Wi-Fi it will "
                + "stay there; Android only switches when it is not connected.",
                12, subtext(), false), lp(4, 16, 4, 0));

        setContentView(scroll);
    }

    // ---------- small view helpers ----------

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(text());
        e.setHintTextColor(subtext());
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(box(bg(), line()));
        e.setInputType(InputType.TYPE_CLASS_TEXT | (password
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        return e;
    }

    private RadioButton radio(String s, int id) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(s);
        r.setTextColor(text());
        return r;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(primary ? onAccent() : accent());
        b.setBackground(primary ? box(accent(), 0) : box(0x00000000, 0));
        return b;
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    // ---------- saving ----------

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private JSONArray load() {
        try { return new JSONArray(prefs().getString(KEY, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private void store(JSONArray a) { prefs().edit().putString(KEY, a.toString()).apply(); }

    private void onSave() {
        String ssid = ssidField.getText().toString().trim();
        String pass = passField.getText().toString();
        int id = typeGroup.getCheckedRadioButtonId();
        int type = id == 1 ? WPA3 : id == 2 ? OPEN : WPA2;

        if (ssid.isEmpty()) { status = "Please type the network name."; build(); return; }
        if (type != OPEN && (pass.length() < 8 || pass.length() > 63)) {
            status = "The password must be 8 to 63 characters for WPA2 / WPA3.";
            build();
            return;
        }

        // Replace an existing entry with the same name, otherwise add a new one.
        JSONArray old = load();
        JSONArray next = new JSONArray();
        try {
            for (int i = 0; i < old.length(); i++) {
                if (!ssid.equals(old.getJSONObject(i).optString("ssid"))) next.put(old.getJSONObject(i));
            }
            JSONObject n = new JSONObject();
            n.put("ssid", ssid);
            n.put("pass", type == OPEN ? "" : pass);
            n.put("type", type);
            n.put("hidden", hiddenBox.isChecked());
            next.put(n);
        } catch (JSONException e) {
            status = "Could not save: " + e.getMessage();
            build();
            return;
        }
        store(next);

        draftSsid = "";
        draftPass = "";
        draftHidden = false;
        draftType = WPA2;
        ssidField = null; // so build() does not copy the old text back into the drafts

        status = apply(next);
        build();
    }

    private void onRemove(int index) {
        JSONArray old = load();
        JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) if (i != index) next.put(old.opt(i));
        store(next);
        ssidField = null;
        status = apply(next);
        build();
    }

    // ---------- talking to Android ----------

    /** Replaces all of this app's suggestions with the saved list. Returns a message for the screen. */
    private String apply(JSONArray all) {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm == null) return "This phone has no Wi-Fi.";

        // Start clean so a changed password never conflicts with the old one.
        List<WifiNetworkSuggestion> existing = wm.getNetworkSuggestions();
        if (!existing.isEmpty()) wm.removeNetworkSuggestions(existing);

        List<WifiNetworkSuggestion> list = new ArrayList<>();
        try {
            for (int i = 0; i < all.length(); i++) {
                JSONObject n = all.getJSONObject(i);
                WifiNetworkSuggestion.Builder b = new WifiNetworkSuggestion.Builder()
                        .setSsid(n.getString("ssid"))
                        .setIsHiddenSsid(n.optBoolean("hidden"));
                int type = n.optInt("type");
                if (type == WPA2) b.setWpa2Passphrase(n.getString("pass"));
                else if (type == WPA3) b.setWpa3Passphrase(n.getString("pass"));
                list.add(b.build());
            }
        } catch (JSONException | IllegalArgumentException e) {
            return "A saved network has invalid details: " + e.getMessage();
        }
        if (list.isEmpty()) return "No networks left. Auto-connect is off.";

        switch (wm.addNetworkSuggestions(list)) {
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS:
                return "Saved. When a saved network is in range, Android will ask you to allow it "
                        + "(one time) and then connect automatically.";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED:
                return "Android is blocking this app from suggesting networks. Open your phone's "
                        + "Settings > Apps > Special access > Wi-Fi control (wording varies) and "
                        + "allow Wi-Fi Auto Connect.";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP:
                return "Too many networks saved. Remove a few and try again.";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED:
                return "Android did not allow adding networks right now. Try again in a moment.";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID:
                return "Android rejected the network details. Check the name, password and security type.";
            default:
                return "Android reported an internal error. Please try again.";
        }
    }
}
