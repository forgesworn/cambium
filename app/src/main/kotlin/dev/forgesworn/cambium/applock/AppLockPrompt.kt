package dev.forgesworn.cambium.applock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper over `androidx.biometric` so `MainActivity` and `SignerActivity` don't each
 * hand-roll the same `BiometricPrompt` boilerplate. [ALLOWED_AUTHENTICATORS] is
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` -- whichever the device actually has enrolled, never a
 * weak/convenience-class biometric alone -- which is also why [PromptInfo.Builder] never calls
 * `setNegativeButtonText`: that call is only valid for a biometric-only prompt (no
 * `DEVICE_CREDENTIAL`), since `DEVICE_CREDENTIAL` already gives the system prompt its own
 * cancel/fallback affordance, and androidx.biometric throws if both are set at once.
 *
 * [canAuthenticate] takes a plain [Context] (`BiometricManager.from` only needs one), not a
 * [FragmentActivity], deliberately: both `MainActivity` and `SignerActivity` must check it
 * *before* trusting [AppLockStore.isEnabled] to decide whether to lock at all -- if the device
 * loses its screen lock after the toggle was switched on, gating must fail open rather than lock
 * the user out of their own app with no way back in.
 */
object AppLockPrompt {
    const val ALLOWED_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Whether the device has anything enrolled -- biometric or a screen lock -- that
     * [authenticate] could actually prompt for. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** The one true "should we gate right now" check: [AppLockStore.isEnabled], [canAuthenticate]
     * (fail-open if the device no longer has anything enrolled -- see the class doc), and
     * [AppLock.requiresAuthentication]'s grace window, combined so `MainActivity` and
     * `SignerActivity` never have to re-derive this themselves. */
    fun requiresAuthenticationNow(context: Context, store: AppLockStore): Boolean =
        store.isEnabled() &&
            canAuthenticate(context) &&
            AppLock.requiresAuthentication(store.lastAuthenticatedAtMillis(), System.currentTimeMillis())

    /**
     * Shows the system authentication prompt. [onFailure] fires once, for a *terminal* outcome
     * (the user cancelled, too many attempts, or another non-recoverable error) -- a single wrong
     * fingerprint alone does not call it, since the system prompt already lets the user retry
     * without Cambium needing to re-show it itself.
     */
    fun authenticate(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFailure: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }
}
