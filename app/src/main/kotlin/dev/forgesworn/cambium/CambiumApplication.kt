package dev.forgesworn.cambium

import android.app.Application

/** No global state yet: [dev.forgesworn.cambium.pairing.PairingStore] is cheap enough to construct per use. */
class CambiumApplication : Application()
