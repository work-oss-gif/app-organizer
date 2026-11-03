package com.example.wifiauto;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Wakes up (a) when the daily alarm fires, to scan for saved networks, and
 * (b) after a reboot / time change, to set the next alarm again (Android forgets alarms on reboot).
 */
public class ScanReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "scan";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        Schedule.reschedule(app); // always line up the next scan first
        if (!Schedule.ACTION_SCAN.equals(intent.getAction())) return; // boot / time change: nothing more to do

        PendingResult pending = goAsync(); // lets us wait a few seconds for the scan to finish
        boolean foreground = intent.getBooleanExtra("test", false);
        start(app, pending, foreground);
    }

    @SuppressWarnings("deprecation")
    private void start(Context app, PendingResult pending, boolean foreground) {
        JSONArray saved = Networks.load(app);
        if (saved.length() == 0) { done(app, pending, "No saved networks yet. Add one in the app."); return; }

        // Re-send the list to Android, so its own auto-connect has fresh data.
        Networks.apply(app, saved);

        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            done(app, pending, "Wi-Fi is turned off. Turn it on so your phone can connect.");
            return;
        }
        if (onWifi(app)) { done(app, pending, "Already connected to Wi-Fi."); return; }

        Set<String> wanted = new HashSet<>();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject n = saved.optJSONObject(i);
            if (n != null) wanted.add(n.optString("ssid"));
        }

        Job job = new Job(app, pending, wm, wanted, canReadResults(app, foreground));
        job.begin();
    }

    private static boolean onWifi(Context app) {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    /** Android only shows Wi-Fi scan results to apps with location permission (and location switched on). */
    static boolean canReadResults(Context app, boolean foreground) {
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.isLocationEnabled()) return false;
        // When the app is closed we also need "Allow all the time".
        return foreground || app.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** One scan: ask Android to scan, wait for the answer (or give up after 9 s), report once. */
    private class Job extends BroadcastReceiver implements Runnable {
        final Context app;
        final PendingResult pending;
        final WifiManager wm;
        final Set<String> wanted;
        final boolean canRead;
        final Handler handler = new Handler(Looper.getMainLooper());
        boolean finished;

        Job(Context app, PendingResult pending, WifiManager wm, Set<String> wanted, boolean canRead) {
            this.app = app; this.pending = pending; this.wm = wm; this.wanted = wanted; this.canRead = canRead;
        }

        @SuppressWarnings("deprecation")
        void begin() {
            app.registerReceiver(this, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            boolean started = wm.startScan(); // Android limits how often apps may scan
            handler.postDelayed(this, started ? 9000 : 0); // not started: just use the last results
        }

        @Override public void onReceive(Context c, Intent i) { run(); } // scan finished

        @SuppressWarnings("deprecation")
        @Override public void run() {                                   // scan finished, or time ran out
            if (finished) return;
            finished = true;
            handler.removeCallbacks(this);
            try { app.unregisterReceiver(this); } catch (IllegalArgumentException ignored) { }

            String msg;
            if (!canRead) {
                msg = "Scan requested, but this app may not read the results. Your phone will still "
                        + "connect if a saved network is in range. Allow location \"all the time\" "
                        + "to see results here.";
            } else {
                Set<String> found = new LinkedHashSet<>();
                try {
                    for (ScanResult r : wm.getScanResults()) if (wanted.contains(r.SSID)) found.add(r.SSID);
                } catch (SecurityException e) { /* permission vanished: treat as nothing found */ }
                msg = found.isEmpty()
                        ? "No saved network in range right now."
                        : "In range: " + String.join(", ", found) + ". Your phone should connect automatically.";
            }
            done(app, pending, msg);
        }
    }

    // ---------- reporting ----------

    private static void done(Context app, PendingResult pending, String msg) {
        String stamp = new SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(new Date());
        Networks.prefs(app).edit().putString("last", stamp + " - " + msg).apply();
        notifyUser(app, msg);
        if (pending != null) pending.finish();
    }

    private static void notifyUser(Context app, String msg) {
        if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return; // no permission: the result is still saved and shown inside the app
        }
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Wi-Fi scan", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(app, 0, new Intent(app, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Wi-Fi scan")
                .setContentText(msg)
                .setStyle(new Notification.BigTextStyle().bigText(msg))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(1, n);
    }
}
