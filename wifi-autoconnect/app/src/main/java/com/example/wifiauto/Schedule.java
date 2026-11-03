package com.example.wifiauto;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * The daily scan times. Each weekday has an on/off switch and a time (minutes after midnight).
 * An Android "alarm" wakes the app at the next chosen time, the app scans, then sets the next alarm.
 */
final class Schedule {
    static final String ACTION_SCAN = "com.example.wifiauto.SCAN";

    // Day numbers follow Android's Calendar: 0 = Sunday ... 6 = Saturday.
    static final String[] NAMES = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    /** Order shown on screen: Monday first. */
    static final int[] ORDER = {1, 2, 3, 4, 5, 6, 0};

    private Schedule() {}

    static boolean enabled(Context c, int day) {
        return Networks.prefs(c).getBoolean("on_" + day, false);
    }

    static int minutes(Context c, int day) {
        return Networks.prefs(c).getInt("min_" + day, 8 * 60); // default 08:00
    }

    static void setEnabled(Context c, int day, boolean on) {
        Networks.prefs(c).edit().putBoolean("on_" + day, on).apply();
    }

    static void setMinutes(Context c, int day, int minutes) {
        Networks.prefs(c).edit().putInt("min_" + day, minutes).apply();
    }

    static String fmt(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Milliseconds timestamp of the next scan, or -1 if no day is switched on. */
    static long next(Context c) {
        long now = System.currentTimeMillis();
        // Look today, tomorrow ... up to the same weekday next week. The first hit is the earliest.
        for (int offset = 0; offset <= 7; offset++) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, offset);
            int day = cal.get(Calendar.DAY_OF_WEEK) - 1;
            if (!enabled(c, day)) continue;
            int m = minutes(c, day);
            cal.set(Calendar.HOUR_OF_DAY, m / 60);
            cal.set(Calendar.MINUTE, m % 60);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() > now) return cal.getTimeInMillis();
        }
        return -1;
    }

    static String describe(Context c) {
        long t = next(c);
        if (t < 0) return "No scans scheduled. Switch on a day below.";
        return "Next scan: " + new SimpleDateFormat("EEEE HH:mm", Locale.getDefault()).format(t);
    }

    /** (Re)sets the alarm for the next scan. Safe to call any time. */
    static void reschedule(Context c) {
        Context app = c.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(app, 1,
                new Intent(app, ScanReceiver.class).setAction(ACTION_SCAN),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long t = next(app);
        if (t < 0) { am.cancel(pi); return; }
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi); // a few minutes late is possible
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        }
    }
}
