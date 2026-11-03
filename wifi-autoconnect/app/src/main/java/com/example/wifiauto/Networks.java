package com.example.wifiauto;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Saved Wi-Fi networks, plus the code that hands them to Android as "network suggestions". */
final class Networks {
    static final int WPA2 = 0, WPA3 = 1, OPEN = 2;
    private static final String PREFS = "wifi";
    private static final String KEY = "networks";

    private Networks() {}

    /** The one settings file the whole app shares (networks, schedule, last scan result). */
    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONArray load(Context c) {
        try { return new JSONArray(prefs(c).getString(KEY, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    static void store(Context c, JSONArray a) {
        prefs(c).edit().putString(KEY, a.toString()).apply();
    }

    /** Replaces all of this app's suggestions with the saved list. Returns a message for the screen. */
    static String apply(Context c, JSONArray all) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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
