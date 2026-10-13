package dev.forgesworn.cambium

import android.app.Application
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.signer.HeartwoodSession

/** Authenticated relay updates are persisted in every entry point, including provider-only use. */
class CambiumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        HeartwoodSession.configureRelayUpdates { expected, relays ->
            PairingStore(this).updatePairingRelays(expected, relays)
        }
    }
}
