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
class PairingStore(context: Context, private val nowMillis: () -> Long = System::currentTimeMillis) {

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

    fun pairings(): List<Pairing> = synchronized(STORE_LOCK) { readPairings() }

    fun isPaired(): Boolean = pairings().isNotEmpty()

    /**
     * Returns Cambium's ephemeral NIP-46 client keypair, generating and persisting one on first
     * call. Callers should get this key *before* attempting a connection and pass the exact same
     * key into [addPairing] afterwards -- otherwise a successfully-tested key could be discarded
     * in favour of a fresh, never-tested one generated inside [addPairing].
     */
    fun ensureClientKeys(): ClientKeys.Generated = synchronized(STORE_LOCK) {
        val existingSecret = prefs.getString(KEY_CLIENT_SECRET, null)
        if (existingSecret != null) {
            val publicHex = prefs.getString(KEY_CLIENT_PUBKEY, null) ?: ClientKeys.publicKeyHexFor(existingSecret)
            return@synchronized ClientKeys.Generated(existingSecret, publicHex)
        }
        val generated = ClientKeys.generate()
        prefs.edit()
            .putString(KEY_CLIENT_SECRET, generated.secretKeyHex)
            .putString(KEY_CLIENT_PUBKEY, generated.publicKeyHex)
            .apply()
        generated
    }

    /**
     * Adds [bunkerUri] as a new pairing, or updates the existing entry in place if we are already
     * paired with that identity (same `signerPubkeyHex`) -- re-pairing (e.g. to refresh relays)
     * rather than duplicating. [label] is optional; omitting it (or passing blank) on a re-pair
     * keeps whatever label the entry already had, so refreshing a pairing never silently clears a
     * name the user chose. Call [ensureClientKeys] first is not required -- this calls it itself.
     */
    fun addPairing(bunkerUri: BunkerUri, label: String? = null): Pairing = synchronized(STORE_LOCK) {
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
        updated
    }

    /** Removes one pairing. Any app bound to that identity (see [AppPermission.boundIdentityPubkeyHex])
     * has its permission forgotten too, back to "ask" -- a remembered approval bound to an
     * identity that no longer exists must never be reinterpreted as approval for a different one
     * (see [IdentityRouting]). */
    fun removePairing(signerPubkeyHex: String) = synchronized(STORE_LOCK) {
        val pairings = readPairings().filterNot { it.signerPubkeyHex == signerPubkeyHex }
        val permissions = readPermissions().filterNot { (_, permission) -> permission.boundIdentityPubkeyHex == signerPubkeyHex }
        prefs.edit()
            .putString(KEY_PAIRINGS_JSON, Json.encodeToString(pairings))
            .putString(KEY_APP_PERMISSIONS_JSON, Json.encodeToString(permissions))
            .apply()
    }

    /** A blank label restores the public-key display. Never changes identity or credentials. */
    fun renamePairing(signerPubkeyHex: String, label: String?): Boolean = synchronized(STORE_LOCK) {
        val existing = readPairings()
        if (existing.none { it.signerPubkeyHex == signerPubkeyHex }) return@synchronized false
        writePairings(existing.map {
            if (it.signerPubkeyHex == signerPubkeyHex) it.copy(label = label?.trim()?.takeIf(String::isNotEmpty)) else it
        })
        true
    }

    /** Persists an authenticated relay change only if the pairing still matches the connection
     * that returned it. Caller validates relay policy; a newer re-pair wins over a stale reply. */
    fun updatePairingRelays(
        expected: Pairing,
        validatedRelays: List<String>,
    ): Boolean = synchronized(STORE_LOCK) {
        if (validatedRelays.isEmpty()) return@synchronized false
        val existing = readPairings()
        val pairing = existing.firstOrNull { it.signerPubkeyHex == expected.signerPubkeyHex } ?: return@synchronized false
        if (pairing.copy(label = null) != expected.copy(label = null)) return@synchronized false
        val previousJson = prefs.getString(KEY_PAIRINGS_JSON, null)
        val updated = existing.map { if (it == pairing) it.copy(relays = validatedRelays) else it }
        // Relay transport must change only after durable storage has accepted the new list.
        // commit() also changes SharedPreferences' memory before attempting disk I/O, so a
        // failed write needs its old in-memory value restored before this lock is released.
        val committed = runCatching {
            prefs.edit().putString(KEY_PAIRINGS_JSON, Json.encodeToString(updated)).commit()
        }.getOrDefault(false)
        if (!committed) {
            val rollback = prefs.edit()
            if (previousJson == null) rollback.remove(KEY_PAIRINGS_JSON)
            else rollback.putString(KEY_PAIRINGS_JSON, previousJson)
            rollback.apply()
        }
        committed
    }

    /** Full reset: every pairing, every app permission, the keep-alive toggle, and Cambium's own
     * client keypair. A future re-pair generates a fresh client keypair -- the same "forget
     * everything" behaviour unpairing the single 0.2.x pairing had. */
    fun clearAll() = synchronized(STORE_LOCK) {
        prefs.edit().clear().apply()
        migrationChecked = false
    }

    /** Remembers local consent for an existing pairing. ONCE preserves any earlier remembered
     * choice and never writes a grant: its caller authorises just the current visible request. */
    fun approve(
        packageName: String,
        identityPubkeyHex: String,
        lifetime: ApprovalLifetime = ApprovalLifetime.ALWAYS,
        scope: PermissionScope? = null,
    ): Boolean = synchronized(STORE_LOCK) {
        if (readPairings().none { it.signerPubkeyHex == identityPubkeyHex }) return@synchronized false
        lifetime.permission(identityPubkeyHex, scope, nowMillis())?.let { setPermission(packageName, it) }
        true
    }

    /** Consent from an open sheet covers the exact connection the user saw. A re-pair or
     * removal invalidates that sheet before any remembered choice is written. */
    fun approveIfPairingCurrent(
        packageName: String,
        expectedPairing: Pairing,
        lifetime: ApprovalLifetime = ApprovalLifetime.ALWAYS,
        scope: PermissionScope? = null,
    ): Boolean = synchronized(STORE_LOCK) {
        val current = readPairings().firstOrNull { it.signerPubkeyHex == expectedPairing.signerPubkeyHex }
            ?: return@synchronized false
        if (current.copy(label = null) != expectedPairing.copy(label = null)) return@synchronized false
        approve(packageName, current.signerPubkeyHex, lifetime, scope)
    }

    /** Native consent must also match the active remembered choice shown by the sheet. Null
     * means the sheet showed no active choice, rather than skipping the comparison. A newer
     * deny, scope change or identity binding wins even over a one-shot biometric callback. */
    fun approveIfSheetCurrent(
        packageName: String,
        expectedPairing: Pairing,
        expectedPermission: AppPermission?,
        lifetime: ApprovalLifetime = ApprovalLifetime.ALWAYS,
        scope: PermissionScope? = null,
    ): Boolean = synchronized(STORE_LOCK) {
        val current = readPermissions()[packageName]?.takeIf { it.isActive(nowMillis()) }
        if (current?.state == AppPermissionState.DENIED) return@synchronized false
        if (current != expectedPermission) return@synchronized false
        approveIfPairingCurrent(packageName, expectedPairing, lifetime, scope)
    }

    /** Call only after explicit user confirmation. An expired or denied entry cannot be revived.
     * The existing expiry and scope are preserved exactly, rather than granting fresh consent.
     * Dialog callers pass [expected] so a newer remembered choice cannot be overwritten. */
    fun rebindApproval(
        packageName: String,
        identityPubkeyHex: String,
        expected: AppPermission? = null,
    ): Boolean = synchronized(STORE_LOCK) {
        if (readPairings().none { it.signerPubkeyHex == identityPubkeyHex }) return@synchronized false
        val permission = readPermissions()[packageName] ?: return@synchronized false
        if (expected != null && permission != expected) return@synchronized false
        if (permission.state != AppPermissionState.APPROVED || !permission.isActive(nowMillis())) return@synchronized false
        setPermission(packageName, permission.copy(boundIdentityPubkeyHex = identityPubkeyHex))
        true
    }

    /** Edits an active approval after explicit user confirmation. Null lifetime preserves its
     * existing end time; a selected lifetime deliberately starts a new period. ONCE is not stored. */
    fun updateApproval(
        packageName: String,
        identityPubkeyHex: String,
        lifetime: ApprovalLifetime? = null,
        scope: PermissionScope? = null,
        expected: AppPermission? = null,
    ): Boolean = synchronized(STORE_LOCK) {
        if (lifetime == ApprovalLifetime.ONCE) return@synchronized false
        if (readPairings().none { it.signerPubkeyHex == identityPubkeyHex }) return@synchronized false
        val previous = readPermissions()[packageName] ?: return@synchronized false
        if (expected != null && previous != expected) return@synchronized false
        if (previous.state != AppPermissionState.APPROVED || !previous.isActive(nowMillis())) return@synchronized false
        val updated = if (lifetime == null) previous.copy(boundIdentityPubkeyHex = identityPubkeyHex, scope = scope)
            else lifetime.permission(identityPubkeyHex, scope, nowMillis()) ?: return@synchronized false
        setPermission(packageName, updated)
        true
    }

    /** Explicit duration confirmation edits only the entry the dialog displayed. Its exact
     * identity and operation scope survive renewal; a newer deny, rebind or scope edit wins.
     * Expired approvals may be renewed explicitly, while ONCE restores asking on each request. */
    fun changeApprovalLifetimeIfCurrent(
        packageName: String,
        expectedPermission: AppPermission,
        lifetime: ApprovalLifetime,
    ): Boolean = synchronized(STORE_LOCK) {
        if (expectedPermission.state != AppPermissionState.APPROVED) return@synchronized false
        val previous = readPermissions()[packageName] ?: return@synchronized false
        if (previous != expectedPermission) return@synchronized false
        val identity = previous.boundIdentityPubkeyHex ?: return@synchronized false
        if (readPairings().none { it.signerPubkeyHex == identity }) return@synchronized false
        setPermission(packageName, lifetime.permission(identity, previous.scope, nowMillis()))
        true
    }

    /** Remembers that [packageName] must never use the paired signer without asking again.
     * Denial is not identity-specific -- it blocks the app outright -- so it carries no binding.
     * Clears any previous approval and binding. */
    fun deny(packageName: String) =
        setPermission(packageName, AppPermission(AppPermissionState.DENIED, boundIdentityPubkeyHex = null))

    /** Forgets any remembered choice for [packageName]: back to "ask" next time. */
    fun forget(packageName: String) = setPermission(packageName, null)

    private fun setPermission(packageName: String, permission: AppPermission?) = synchronized(STORE_LOCK) {
        val current = readPermissions()
        writePermissions(if (permission == null) current - packageName else current + (packageName to permission))
    }

    fun permission(packageName: String): AppPermission? = synchronized(STORE_LOCK) {
        readPermissions()[packageName]?.takeIf { it.isActive(nowMillis()) }
    }

    /** All remembered choices, for the connected-apps list in `MainActivity`. */
    fun allPermissions(): Map<String, AppPermission> = synchronized(STORE_LOCK) { readPermissions() }

    /** Persisted independent of any one pairing, not tied to any one activity/service instance --
     * read by [dev.forgesworn.cambium.service.HeartwoodKeepAliveService], [dev.forgesworn.cambium.service.BootReceiver]
     * and `MainActivity`'s toggle. Defaults to off: this is an opt-in battery trade-off. */
    fun isKeepAliveEnabled(): Boolean = synchronized(STORE_LOCK) { prefs.getBoolean(KEY_KEEP_ALIVE_ENABLED, false) }

    fun setKeepAliveEnabled(enabled: Boolean) = synchronized(STORE_LOCK) {
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
        // Activities and provider instantiate separate stores. One process-wide lock protects
        // every read-modify-write, including removal, migration, rename and approval changes.
        val STORE_LOCK = Any()
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
