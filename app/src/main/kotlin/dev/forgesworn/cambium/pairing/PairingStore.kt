package dev.forgesworn.cambium.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.forgesworn.cambium.signer.ClientKeys

/** The single active pairing: the paired Heartwood, and Cambium's own ephemeral NIP-46 identity. */
data class Pairing(
    val signerPubkeyHex: String,
    val relays: List<String>,
    val secret: String?,
    val clientSecretKeyHex: String,
    val clientPublicKeyHex: String,
)

/**
 * Persists the single Heartwood pairing and the per-app approval set in Android
 * Keystore-backed EncryptedSharedPreferences. Cambium supports exactly one paired signer
 * in v1 (see design doc, "multi-device: v1 pairs ONE Heartwood").
 */
class PairingStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun current(): Pairing? {
        val signerPubkey = prefs.getString(KEY_SIGNER_PUBKEY, null) ?: return null
        val relaysRaw = prefs.getString(KEY_RELAYS, null) ?: return null
        val clientSecret = prefs.getString(KEY_CLIENT_SECRET, null) ?: return null
        val clientPubkey = prefs.getString(KEY_CLIENT_PUBKEY, null) ?: return null

        return Pairing(
            signerPubkeyHex = signerPubkey,
            relays = relaysRaw.split(RELAY_DELIMITER).filter { it.isNotBlank() },
            secret = prefs.getString(KEY_SECRET, null),
            clientSecretKeyHex = clientSecret,
            clientPublicKeyHex = clientPubkey,
        )
    }

    fun isPaired(): Boolean = current() != null

    /**
     * Returns Cambium's ephemeral NIP-46 client keypair, generating and persisting one on first
     * call. Callers should get this key *before* attempting a connection and pass the exact same
     * key into [save] afterwards -- otherwise a successfully-tested key could be discarded in
     * favour of a fresh, never-tested one generated inside [save].
     */
    fun ensureClientKeys(): ClientKeys.Generated {
        val existingSecret = prefs.getString(KEY_CLIENT_SECRET, null)
        if (existingSecret != null) {
            val publicHex = prefs.getString(KEY_CLIENT_PUBKEY, null) ?: ClientKeys.publicKeyHexFor(existingSecret)
            return ClientKeys.Generated(existingSecret, publicHex)
        }
        val generated = ClientKeys.generate()
        prefs.edit()
            .putString(KEY_CLIENT_SECRET, generated.secretKeyHex)
            .putString(KEY_CLIENT_PUBKEY, generated.publicKeyHex)
            .apply()
        return generated
    }

    /** Persists [bunkerUri] as the active pairing. Call [ensureClientKeys] first (see its doc). */
    fun save(bunkerUri: BunkerUri): Pairing {
        ensureClientKeys()
        prefs.edit()
            .putString(KEY_SIGNER_PUBKEY, bunkerUri.signerPubkeyHex)
            .putString(KEY_RELAYS, bunkerUri.relays.joinToString(RELAY_DELIMITER))
            .putString(KEY_SECRET, bunkerUri.secret)
            .apply()

        return current() ?: error("Pairing was just written but could not be read back")
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Remembers that [packageName] may use the paired signer without asking again. */
    fun approve(packageName: String) {
        val updated = approvedPackages() + packageName
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, updated).apply()
    }

    fun isApproved(packageName: String): Boolean = approvedPackages().contains(packageName)

    private fun approvedPackages(): Set<String> = prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()) ?: emptySet()

    private companion object {
        const val PREFS_NAME = "cambium_pairing"
        const val KEY_SIGNER_PUBKEY = "signer_pubkey_hex"
        const val KEY_RELAYS = "relays"
        const val KEY_SECRET = "secret"
        const val KEY_CLIENT_SECRET = "client_secret_key_hex"
        const val KEY_CLIENT_PUBKEY = "client_public_key_hex"
        const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        const val RELAY_DELIMITER = "\n"
    }
}
