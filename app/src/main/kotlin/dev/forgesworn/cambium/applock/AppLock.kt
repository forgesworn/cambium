package dev.forgesworn.cambium.applock

/**
 * Pure Kotlin (no Android): whether a fresh biometric/device-credential authentication is needed
 * right now, given when Cambium was last unlocked. Kept separate from [AppLockStore]'s
 * `SharedPreferences` I/O and [AppLockPrompt]'s `BiometricPrompt` plumbing so the actual decision
 * -- "is the grace window still open" -- is JVM-testable on its own.
 */
object AppLock {
    /** ~1 minute: long enough that a rotation or a quick switch to another app and back does not
     * re-prompt, short enough that walking away and coming back later does. */
    const val GRACE_WINDOW_MILLIS = 60_000L

    /** `null` [lastAuthenticatedAtMillis] means "never authenticated this install" -- always
     * requires authentication. A negative [nowMillis] - [lastAuthenticatedAtMillis] (clock set
     * backwards) is treated as the grace window having expired, not as still being within it --
     * the safe direction to be wrong in. */
    fun requiresAuthentication(
        lastAuthenticatedAtMillis: Long?,
        nowMillis: Long,
        graceWindowMillis: Long = GRACE_WINDOW_MILLIS,
    ): Boolean {
        if (lastAuthenticatedAtMillis == null) return true
        val elapsed = nowMillis - lastAuthenticatedAtMillis
        return elapsed !in 0..graceWindowMillis
    }
}
