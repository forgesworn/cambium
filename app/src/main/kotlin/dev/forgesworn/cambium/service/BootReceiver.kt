package dev.forgesworn.cambium.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.unlock.UnlockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts [HeartwoodKeepAliveService] after a reboot, but only if the user had actually turned
 * the keep-warm toggle on and Cambium is still paired -- a fresh install or an unpaired device
 * gets no background service at all. A board set up for phone unlock also starts it (see
 * [HeartwoodKeepAliveService]): a phone that restarts abroad must still hear its board's lock
 * messages. `BOOT_COMPLETED` is a protected broadcast (only the system
 * can send it), so this can safely be `exported="true"` in the manifest.
 *
 * `PairingStore`'s first read does a synchronous Keystore-backed EncryptedSharedPreferences init,
 * and `BOOT_COMPLETED` delivery is the worst possible window to block the main thread on that --
 * every other receiver and the rest of the boot sequence is contending for it too. `goAsync()`
 * extends the receiver's lifetime past `onReceive` returning so the actual read (and the service
 * start) can happen on a background dispatcher instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // An update kills the running service like a reboot does, and would otherwise leave phone
        // unlock deaf until the phone next restarts.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pairingStore = PairingStore(context)
                if ((pairingStore.isPaired() && pairingStore.isKeepAliveEnabled()) || UnlockStore(context).hasEnrolments()) {
                    HeartwoodKeepAliveService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
