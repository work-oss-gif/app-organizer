package com.roomcolorwallpaper.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Android sends this a message once the tablet has finished starting up.
 *
 * Android does not allow the camera to be turned on directly from here,
 * so (like Camera Color Bar) we briefly open our own screen. That is only allowed
 * with "Display over other apps". The screen starts the 10-minute updates and closes itself.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        SharedPreferences p = ColorMaker.prefs(ctx);
        if (!p.getBoolean(ColorMaker.KEY_AUTO, false)) return;                     // auto is off
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (!Settings.canDrawOverlays(ctx)) return;                                 // not allowed to open the screen

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(MainActivity.EXTRA_AUTOSTART, true);
        try {
            ctx.startActivity(open);
        } catch (Exception ignored) {
            // Some tablets block this. Then open the app once by hand.
        }
    }
}
