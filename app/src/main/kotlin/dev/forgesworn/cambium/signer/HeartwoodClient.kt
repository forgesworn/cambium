package dev.forgesworn.cambium.signer

import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.NostrSdkException
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayOptions
import rust.nostr.sdk.SecretKey
import rust.nostr.sdk.UnsignedEvent
import java.time.Duration

/**
 * This is the only file in Cambium that imports `rust.nostr.sdk`. Everything else talks to
 * [HeartwoodClient], so the NIP-46 implementation can be swapped without touching pairing
 * storage or the NIP-55 surface. [ClientKeys] lives here for the same reason: generating the
 * ephemeral client keypair is also a rust-nostr operation, so [dev.forgesworn.cambium.pairing.PairingStore]
 * calls into it here rather than importing rust-nostr types itself.
 */
sealed interface HeartwoodResult<out T> {
    data class Success<T>(val value: T) : HeartwoodResult<T>
    data class Failure(val error: HeartwoodError) : HeartwoodResult<Nothing>
}

sealed interface HeartwoodError {
    /** [HeartwoodClient.connect] has not been called (or failed) this session. */
    data object NotConnected : HeartwoodError
    /** No response within the per-call timeout: relay unreachable, or Heartwood offline/unplugged. */
    data object Timeout : HeartwoodError
    /** The bunker URI or a hex key/pubkey argument was malformed. */
    data class InvalidInput(val message: String) : HeartwoodError
    /** rust-nostr raised a protocol-level error (relay rejection, decrypt failure, etc.). */
    data class Protocol(val message: String) : HeartwoodError
}

private inline fun <T> heartwoodCatch(block: () -> T): HeartwoodResult<T> = try {
    HeartwoodResult.Success(block())
} catch (e: TimeoutCancellationException) {
    HeartwoodResult.Failure(HeartwoodError.Timeout)
} catch (e: NostrSdkException) {
    HeartwoodResult.Failure(HeartwoodError.Protocol(e.message ?: "rust-nostr error"))
} catch (e: IllegalArgumentException) {
    HeartwoodResult.Failure(HeartwoodError.InvalidInput(e.message ?: "invalid input"))
}

/** Small Kotlin interface so the NIP-46 transport can be swapped or faked in tests. */
interface HeartwoodClient {
    /**
     * Establishes a NIP-46 session against the bunker URI Sapwood produced, using [clientSecretKeyHex]
     * as our ephemeral client key. Confirms the handshake with a `get_public_key` round trip and
     * returns the signer's hex pubkey on success.
     */
    suspend fun connect(bunkerUri: String, clientSecretKeyHex: String): HeartwoodResult<String>

    suspend fun getPublicKey(): HeartwoodResult<String>

    /** [eventJson] is an unsigned event; returns the same event as JSON with `sig` and `id` filled in. */
    suspend fun signEvent(eventJson: String): HeartwoodResult<String>

    suspend fun nip04Encrypt(pubkeyHex: String, content: String): HeartwoodResult<String>
    suspend fun nip04Decrypt(pubkeyHex: String, content: String): HeartwoodResult<String>
    suspend fun nip44Encrypt(pubkeyHex: String, content: String): HeartwoodResult<String>
    suspend fun nip44Decrypt(pubkeyHex: String, content: String): HeartwoodResult<String>

    /** Releases the underlying relay session. Safe to call when not connected. */
    fun disconnect()
}

class RustNostrHeartwoodClient : HeartwoodClient {

    private var session: NostrConnect? = null

    override suspend fun connect(bunkerUri: String, clientSecretKeyHex: String): HeartwoodResult<String> =
        withTimeout(CALL_TIMEOUT_MILLIS) {
            heartwoodCatch {
                withContext(Dispatchers.IO) {
                    val uri = NostrConnectUri.parse(bunkerUri)
                    val keys = Keys(SecretKey.parse(clientSecretKeyHex))
                    val client = NostrConnect(uri, keys, CALL_TIMEOUT, RelayOptions())
                    session?.close()
                    session = client
                    client.getPublicKey().toHex()
                }
            }
        }

    override suspend fun getPublicKey(): HeartwoodResult<String> = withSession { client ->
        client.getPublicKey().toHex()
    }

    override suspend fun signEvent(eventJson: String): HeartwoodResult<String> = withSession { client ->
        val unsigned = UnsignedEvent.fromJson(eventJson)
        client.signEvent(unsigned).asJson()
    }

    override suspend fun nip04Encrypt(pubkeyHex: String, content: String): HeartwoodResult<String> =
        withSession { client -> client.nip04Encrypt(PublicKey.parse(pubkeyHex), content) }

    override suspend fun nip04Decrypt(pubkeyHex: String, content: String): HeartwoodResult<String> =
        withSession { client -> client.nip04Decrypt(PublicKey.parse(pubkeyHex), content) }

    override suspend fun nip44Encrypt(pubkeyHex: String, content: String): HeartwoodResult<String> =
        withSession { client -> client.nip44Encrypt(PublicKey.parse(pubkeyHex), content) }

    override suspend fun nip44Decrypt(pubkeyHex: String, content: String): HeartwoodResult<String> =
        withSession { client -> client.nip44Decrypt(PublicKey.parse(pubkeyHex), content) }

    override fun disconnect() {
        session?.close()
        session = null
    }

    private suspend fun withSession(block: suspend (NostrConnect) -> String): HeartwoodResult<String> {
        val client = session ?: return HeartwoodResult.Failure(HeartwoodError.NotConnected)
        return withTimeout(CALL_TIMEOUT_MILLIS) {
            heartwoodCatch { withContext(Dispatchers.IO) { block(client) } }
        }
    }

    private companion object {
        const val CALL_TIMEOUT_MILLIS = 20_000L
        val CALL_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}

/** Ephemeral NIP-46 client keypair generation, kept next to the rest of the rust-nostr surface. */
object ClientKeys {
    data class Generated(val secretKeyHex: String, val publicKeyHex: String)

    fun generate(): Generated {
        val keys = Keys.generate()
        return Generated(keys.secretKey().toHex(), keys.publicKey().toHex())
    }

    fun publicKeyHexFor(secretKeyHex: String): String =
        Keys(SecretKey.parse(secretKeyHex)).publicKey().toHex()
}

/** Bech32 "npub" form of a hex pubkey for display; falls back to a truncated hex string. */
fun npubDisplay(pubkeyHex: String): String = runCatching {
    PublicKey.parse(pubkeyHex).toBech32()
}.getOrElse { "${pubkeyHex.take(8)}…${pubkeyHex.takeLast(8)}" }

/**
 * Application-scoped, lazily-connected Heartwood session, shared by [dev.forgesworn.cambium.MainActivity]
 * (after pairing), [dev.forgesworn.cambium.nip55.SignerActivity] and
 * [dev.forgesworn.cambium.nip55.SignerProvider]. A live test against a real device showed building a
 * fresh `NostrConnect` (and its relay pool) per request cost multiple seconds each; this object
 * holds exactly one connected client for the process and reuses it. Guarded by a mutex since the
 * content provider and the activity can both call in from different threads at once.
 */
object HeartwoodSession {
    private val mutex = Mutex()
    private var client: RustNostrHeartwoodClient? = null
    private var connectedSignerPubkey: String? = null

    /**
     * Runs [operation] against a connected client for [pairing], reconnecting first if the held
     * session is missing or paired to a different signer. If [operation] itself fails, reconnects
     * once and retries before surfacing the failure -- covers a relay dropping the connection
     * between requests.
     */
    suspend fun withClient(
        pairing: Pairing,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ): HeartwoodResult<String> = mutex.withLock {
        val cachedClient = client
        val connected = if (cachedClient != null && connectedSignerPubkey == pairing.signerPubkeyHex) {
            HeartwoodResult.Success(cachedClient)
        } else {
            reconnectLocked(pairing)
        }

        val active = when (connected) {
            is HeartwoodResult.Success -> connected.value
            is HeartwoodResult.Failure -> return@withLock connected
        }

        val result = operation(active)
        if (result is HeartwoodResult.Success) return@withLock result

        val retried = when (val reconnected = reconnectLocked(pairing)) {
            is HeartwoodResult.Success -> reconnected.value
            is HeartwoodResult.Failure -> return@withLock result
        }
        operation(retried)
    }

    /** Drops the held session. Call on unpair, and after persisting a freshly-tested pairing. */
    suspend fun shutdown() = mutex.withLock {
        client?.disconnect()
        client = null
        connectedSignerPubkey = null
    }

    /** Must only be called while holding [mutex]. */
    private suspend fun reconnectLocked(pairing: Pairing): HeartwoodResult<HeartwoodClient> {
        client?.disconnect()
        client = null
        connectedSignerPubkey = null

        val fresh = RustNostrHeartwoodClient()
        val bunkerUri = BunkerUri(pairing.signerPubkeyHex, pairing.relays, pairing.secret).toUriString()
        return when (val connected = fresh.connect(bunkerUri, pairing.clientSecretKeyHex)) {
            is HeartwoodResult.Success -> {
                client = fresh
                connectedSignerPubkey = pairing.signerPubkeyHex
                HeartwoodResult.Success(fresh)
            }
            is HeartwoodResult.Failure -> connected
        }
    }
}
