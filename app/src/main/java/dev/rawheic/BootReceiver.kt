package dev.rawheic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the camera-attach foreground service after reboot so the app is
 * "attached" to the Samsung camera pipeline without user interaction.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.attachOnBoot(context)) {
            CameraAttachService.start(context)
        }
    }
}
