package com.addblocker.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences("addblocker", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("start_on_boot", false)) return;
        Intent service = new Intent(context, AdBlockVpnService.class).setAction(AdBlockVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception ignored) {
            prefs.edit().putBoolean("running", false).apply();
        }
    }
}
