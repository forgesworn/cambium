package dev.forgesworn.cambium.applock

import android.content.Context

/**
 * Persists the app-lock toggle and the last-authenticated timestamp in a small plain
 * `SharedPreferences`, independent of `PairingStore` -- this is a device-local UI gate, not
 * pairing state, and holds no secret worth Keystore encryption (a timestamp and a boolean).
 * Off by default: unlike the activity log, this changes what the user has to do to open the app,
 * so it is opt-in, not opt-out.
 *
 * One shared timestamp, not one per activity: authenticating from `MainActivity` also covers a
 * `SignerActivity` approval-sheet prompt within the same grace window, and vice versa, since both
 * construct their own `AppLockStore` instance backed by the same underlying preferences file.
 */
class AppLockStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** `null` if Cambium has never been unlocked this install (or since [AppLock] was last
     * disabled and re-enabled -- enabling does not reset this, so a recent unlock still counts). */
    fun lastAuthenticatedAtMillis(): Long? = prefs.getLong(KEY_LAST_AUTHENTICATED, NEVER).takeIf { it != NEVER }

    fun recordAuthenticated(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_AUTHENTICATED, atMillis).apply()
    }

    // The combined "should we gate right now" check lives in AppLockPrompt.requiresAuthenticationNow,
    // not here -- it also needs canAuthenticate's fail-open safety check, which needs a Context
    // this class deliberately does not keep a reference to beyond what SharedPreferences needs.

    private companion object {
        const val PREFS_NAME = "cambium_app_lock"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_AUTHENTICATED = "last_authenticated_at_millis"
        const val NEVER = -1L
    }
}
