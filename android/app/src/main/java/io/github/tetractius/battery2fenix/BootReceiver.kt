package io.github.tetractius.battery2fenix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the forwarding service after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                BatteryForwardService.start(context)
            } catch (e: Exception) {
                // Some Android versions restrict starting foreground services
                // from boot; the user can reopen the app instead.
                Log.w("BootReceiver", "Could not start service on boot", e)
            }
        }
    }
}
