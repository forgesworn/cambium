package dev.forgesworn.cambium.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.forgesworn.cambium.pairing.PairingStore

/**
 * Restarts [HeartwoodKeepAliveService] after a reboot, but only if the user had actually turned
 * the keep-warm toggle on and Cambium is still paired -- a fresh install or an unpaired device
 * gets no background service at all. `BOOT_COMPLETED` is a protected broadcast (only the system
 * can send it), so this can safely be `exported="true"` in the manifest.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pairingStore = PairingStore(context)
        if (pairingStore.isPaired() && pairingStore.isKeepAliveEnabled()) {
            HeartwoodKeepAliveService.start(context)
        }
    }
}
