package com.cameracolorbar.app;

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
 * so we briefly open our own screen (allowed because we have
 * "Display over other apps"). That screen starts the color bar and closes itself.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        SharedPreferences prefs = ctx.getSharedPreferences(ColorBarService.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(ColorBarService.KEY_AUTOSTART, true)) return;

        // Only if the setup was finished.
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (!Settings.canDrawOverlays(ctx)) return;

        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(MainActivity.EXTRA_AUTOSTART, true);
        try {
            ctx.startActivity(open);
        } catch (Exception ignored) {
            // Some tablets block this. Then the user starts it by hand.
        }
    }
}
