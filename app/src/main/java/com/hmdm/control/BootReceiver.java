package com.hmdm.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restarts the connection supervisor after the device reboots, so an
 * unattended device comes back on its own without anyone launching the app.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        // LOCKED_BOOT_COMPLETED is intentionally not handled: it fires before
        // credential-encrypted storage is unlocked, and our settings (room id,
        // server URL) live there. BOOT_COMPLETED arrives once storage is
        // available, which is what we need.
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Log.i(Const.LOG_TAG, "BootReceiver: starting ConnectionSupervisor after " + action);
            ConnectionSupervisor.start(context.getApplicationContext());
        }
    }
}
