package dev.forgesworn.cambium.unlock

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.biometric.BiometricManager
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps each board's slot secret S sealed under its own Android Keystore AES key that only a
 * **strong biometric** releases, once per use: no device-credential fallback (a shoulder-surfed
 * PIN must not unlock a board), no validity window, invalidated when the enrolled biometrics
 * change, and StrongBox-backed where the phone has one. The key never leaves the secure hardware;
 * Cambium only ever gets a [Cipher] that the system unlocks inside a `BiometricPrompt` bound to it
 * through a `CryptoObject`.
 *
 * Sealing at enrolment needs the fingerprint too (a symmetric Keystore key that requires
 * authentication requires it for every operation), which doubles as the owner confirming the
 * enrolment on the phone.
 */
object SlotSecretVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun canUse(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun newAlias(): String = "cambium-unlock-${UUID.randomUUID()}"

    /** Creates the key and returns a cipher ready to seal, to hand to the biometric prompt. */
    fun encryptCipher(alias: String): Cipher {
        val key = createKey(alias)
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** `iv || ciphertext`, base64, from a cipher the prompt has unlocked. */
    fun seal(unlocked: Cipher, secret: ByteArray): String =
        Base64.encodeToString(unlocked.iv + unlocked.doFinal(secret), Base64.NO_WRAP)

    sealed interface Opening {
        data class Ready(val cipher: Cipher, val ciphertext: ByteArray) : Opening
        /** The biometrics changed (or the key was removed): S is gone for good; re-enrol. */
        data object KeyGone : Opening
    }

    fun decryptCipher(alias: String, sealedB64: String): Opening {
        val blob = runCatching { Base64.decode(sealedB64, Base64.NO_WRAP) }.getOrNull()
        val key = keyStore().getKey(alias, null) as? SecretKey
        if (blob == null || blob.size <= IV_LEN || key == null) return Opening.KeyGone
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
            }
            Opening.Ready(cipher, blob.copyOfRange(IV_LEN, blob.size))
        } catch (e: KeyPermanentlyInvalidatedException) {
            Opening.KeyGone
        }
    }

    fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun createKey(alias: String): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(alias, strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // No secure element on this phone: the TEE-backed key below is the next best.
            }
        }
        return generate(alias, strongBox = false)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    // -1: every use needs a fresh biometric, and only a biometric.
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }
}
