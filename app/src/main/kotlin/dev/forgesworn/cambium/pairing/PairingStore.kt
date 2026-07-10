package dev.forgesworn.cambium.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.forgesworn.cambium.signer.ClientKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One paired Heartwood identity: a bunker URI's connect slot, and Cambium's own ephemeral NIP-46
 * client identity used to talk to it. [label] is a user-chosen display name, optionally set when
 * pairing; `null` means no custom label, so callers fall back to a truncated npub (see
 * `signer.displayLabel`). All pairings currently share one client keypair (see
 * [PairingStore.ensureClientKeys]) -- there is one Cambium client identity presented to every
 * paired bunker, not one per identity -- so [clientSecretKeyHex]/[clientPublicKeyHex] are the same
 * value across every entry; kept per-[Pairing] anyway so call sites that only hold one `Pairing`
 * (`HeartwoodSession`, `MainActivity`'s disposable pairing-test client) don't need a second lookup.
 */
@Serializable
data class Pairing(
    val signerPubkeyHex: String,
    val relays: List<String>,
    val secret: String?,
    val clientSecretKeyHex: String,
    val clientPublicKeyHex: String,
    val label: String? = null,
)

/** A calling app's remembered choice, plus -- for an approval -- which paired identity it was
 * approved for. Absence of any [AppPermission] (`null` from [PairingStore.permission]) means
 * "ask": the approval sheet has not been shown yet, or the user has not made a lasting choice.
 * [boundIdentityPubkeyHex] is only meaningful for [AppPermissionState.APPROVED] -- a denial blocks
 * the app outright, regardless of identity, so it carries no binding (see [PairingStore.deny]). */
@Serializable
data class AppPermission(
    val state: AppPermissionState,
    val boundIdentityPubkeyHex: String?,
)

@Serializable
enum class AppPermissionState { APPROVED, DENIED }

/**
 * Persists every paired Heartwood identity and the per-app permission set in Android
 * Keystore-backed EncryptedSharedPreferences. Both are stored as a single encrypted JSON blob per
 * concern ([KEY_PAIRINGS_JSON], [KEY_APP_PERMISSIONS_JSON]) rather than one preference key per
 * field/entry -- simpler than juggling N indexed keys for a list, and EncryptedSharedPreferences
 * already encrypts the whole value regardless of its internal shape.
 *
 * [ensureMigrated] transparently upgrades 0.2.x's single-pairing schema (flat keys: signer pubkey,
 * relays, secret, plus `allowed_packages`/`denied_packages` StringSets) to the list-of-pairings
 * schema on first read after an upgrade -- see [PairingMigration] for the actual transform, kept
 * pure and separately testable. The old keys are removed in the same atomic edit that writes the
 * migrated JSON, so a process death mid-migration cannot leave both schemas half-written; a
 * partially-written legacy state (missing any of the required fields) is treated as nothing to
 * migrate rather than guessed at.
 */
class PairingStore(context: Context) {

    private val appContext = context.applicationContext
    private var migrationChecked = false

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

    fun pairings(): List<Pairing> = readPairings()

    fun pairingFor(signerPubkeyHex: String): Pairing? = readPairings().firstOrNull { it.signerPubkeyHex == signerPubkeyHex }

    fun isPaired(): Boolean = pairings().isNotEmpty()

    /**
     * Returns Cambium's ephemeral NIP-46 client keypair, generating and persisting one on first
     * call. Callers should get this key *before* attempting a connection and pass the exact same
     * key into [addPairing] afterwards -- otherwise a successfully-tested key could be discarded
     * in favour of a fresh, never-tested one generated inside [addPairing].
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

    /**
     * Adds [bunkerUri] as a new pairing, or updates the existing entry in place if we are already
     * paired with that identity (same `signerPubkeyHex`) -- re-pairing (e.g. to refresh relays)
     * rather than duplicating. [label] is optional; omitting it (or passing blank) on a re-pair
     * keeps whatever label the entry already had, so refreshing a pairing never silently clears a
     * name the user chose. Call [ensureClientKeys] first is not required -- this calls it itself.
     */
    fun addPairing(bunkerUri: BunkerUri, label: String? = null): Pairing {
        val clientKeys = ensureClientKeys()
        val existing = readPairings()
        val previousLabel = existing.firstOrNull { it.signerPubkeyHex == bunkerUri.signerPubkeyHex }?.label
        val updated = Pairing(
            signerPubkeyHex = bunkerUri.signerPubkeyHex,
            relays = bunkerUri.relays,
            secret = bunkerUri.secret,
            clientSecretKeyHex = clientKeys.secretKeyHex,
            clientPublicKeyHex = clientKeys.publicKeyHex,
            label = label?.takeIf { it.isNotBlank() } ?: previousLabel,
        )
        writePairings(existing.filterNot { it.signerPubkeyHex == bunkerUri.signerPubkeyHex } + updated)
        return updated
    }

    /** Removes one pairing. Any app bound to that identity (see [AppPermission.boundIdentityPubkeyHex])
     * has its permission forgotten too, back to "ask" -- a remembered approval bound to an
     * identity that no longer exists must never be reinterpreted as approval for a different one
     * (see [IdentityRouting]). */
    fun removePairing(signerPubkeyHex: String) {
        writePairings(readPairings().filterNot { it.signerPubkeyHex == signerPubkeyHex })
        writePermissions(readPermissions().filterNot { (_, permission) -> permission.boundIdentityPubkeyHex == signerPubkeyHex })
    }

    /** Full reset: every pairing, every app permission, the keep-alive toggle, and Cambium's own
     * client keypair. A future re-pair generates a fresh client keypair -- the same "forget
     * everything" behaviour unpairing the single 0.2.x pairing had. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** Remembers that [packageName] may use [identityPubkeyHex] without asking again. Clears any
     * previous denial -- approved and denied are mutually exclusive. */
    fun approve(packageName: String, identityPubkeyHex: String) =
        setPermission(packageName, AppPermission(AppPermissionState.APPROVED, identityPubkeyHex))

    /** Remembers that [packageName] must never use the paired signer without asking again.
     * Denial is not identity-specific -- it blocks the app outright -- so it carries no binding.
     * Clears any previous approval and binding. */
    fun deny(packageName: String) =
        setPermission(packageName, AppPermission(AppPermissionState.DENIED, boundIdentityPubkeyHex = null))

    /** Forgets any remembered choice for [packageName]: back to "ask" next time. */
    fun forget(packageName: String) = setPermission(packageName, null)

    private fun setPermission(packageName: String, permission: AppPermission?) {
        val current = readPermissions()
        writePermissions(if (permission == null) current - packageName else current + (packageName to permission))
    }

    fun permission(packageName: String): AppPermission? = readPermissions()[packageName]

    /** All remembered choices, for the connected-apps list in `MainActivity`. */
    fun allPermissions(): Map<String, AppPermission> = readPermissions()

    /** Persisted independent of any one pairing, not tied to any one activity/service instance --
     * read by [dev.forgesworn.cambium.service.HeartwoodKeepAliveService], [dev.forgesworn.cambium.service.BootReceiver]
     * and `MainActivity`'s toggle. Defaults to off: this is an opt-in battery trade-off. */
    fun isKeepAliveEnabled(): Boolean = prefs.getBoolean(KEY_KEEP_ALIVE_ENABLED, false)

    fun setKeepAliveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled).apply()
    }

    private fun readPairings(): List<Pairing> {
        ensureMigrated()
        val json = prefs.getString(KEY_PAIRINGS_JSON, null) ?: return emptyList()
        return runCatching { Json.decodeFromString<List<Pairing>>(json) }.getOrDefault(emptyList())
    }

    private fun writePairings(pairings: List<Pairing>) {
        prefs.edit().putString(KEY_PAIRINGS_JSON, Json.encodeToString(pairings)).apply()
    }

    private fun readPermissions(): Map<String, AppPermission> {
        ensureMigrated()
        val json = prefs.getString(KEY_APP_PERMISSIONS_JSON, null) ?: return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, AppPermission>>(json) }.getOrDefault(emptyMap())
    }

    private fun writePermissions(permissions: Map<String, AppPermission>) {
        prefs.edit().putString(KEY_APP_PERMISSIONS_JSON, Json.encodeToString(permissions)).apply()
    }

    /** Runs at most once per [PairingStore] instance (checked in-memory, not persisted): once
     * [KEY_PAIRINGS_JSON] exists -- written either by a completed migration or by a pairing added
     * under the new schema directly -- there is nothing left to migrate, ever again. */
    private fun ensureMigrated() {
        if (migrationChecked) return
        migrationChecked = true
        if (prefs.contains(KEY_PAIRINGS_JSON)) return

        val legacySignerPubkey = prefs.getString(KEY_SIGNER_PUBKEY, null) ?: return // fresh install
        val legacyRelays = prefs.getString(KEY_RELAYS, null)?.split(RELAY_DELIMITER)?.filter { it.isNotBlank() }
        val legacyClientSecret = prefs.getString(KEY_CLIENT_SECRET, null)
        val legacyClientPubkey = prefs.getString(KEY_CLIENT_PUBKEY, null)
        if (legacyRelays.isNullOrEmpty() || legacyClientSecret == null || legacyClientPubkey == null) {
            return // incomplete legacy state -- nothing sound to migrate
        }

        val migrated = PairingMigration.migrate(
            PairingMigration.Legacy(
                signerPubkeyHex = legacySignerPubkey,
                relays = legacyRelays,
                secret = prefs.getString(KEY_SECRET, null),
                clientSecretKeyHex = legacyClientSecret,
                clientPublicKeyHex = legacyClientPubkey,
                approvedPackages = prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()).orEmpty(),
                deniedPackages = prefs.getStringSet(KEY_DENIED_PACKAGES, emptySet()).orEmpty(),
            )
        )

        // One editor, one apply(): the new JSON and the removal of every old key land atomically,
        // so a process death mid-migration cannot leave the store readable under neither schema.
        prefs.edit()
            .putString(KEY_PAIRINGS_JSON, Json.encodeToString(migrated.pairings))
            .putString(KEY_APP_PERMISSIONS_JSON, Json.encodeToString(migrated.permissions))
            .remove(KEY_SIGNER_PUBKEY)
            .remove(KEY_RELAYS)
            .remove(KEY_SECRET)
            .remove(KEY_ALLOWED_PACKAGES)
            .remove(KEY_DENIED_PACKAGES)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "cambium_pairing"
        const val KEY_PAIRINGS_JSON = "pairings_json"
        const val KEY_APP_PERMISSIONS_JSON = "app_permissions_json"
        const val KEY_CLIENT_SECRET = "client_secret_key_hex"
        const val KEY_CLIENT_PUBKEY = "client_public_key_hex"
        const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
        const val RELAY_DELIMITER = "\n"

        // 0.2.x's single-pairing schema -- read only by ensureMigrated(), then removed.
        const val KEY_SIGNER_PUBKEY = "signer_pubkey_hex"
        const val KEY_RELAYS = "relays"
        const val KEY_SECRET = "secret"
        const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        const val KEY_DENIED_PACKAGES = "denied_packages"
    }
}
