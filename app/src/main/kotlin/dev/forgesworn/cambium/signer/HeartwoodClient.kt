package dev.forgesworn.cambium.signer

import android.util.Log
import dev.forgesworn.cambium.pairing.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
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

/** Wraps a [HeartwoodResult] with whether it was answered from [DecryptCache] without ever
 * reaching the worker, or came from a real call against Heartwood -- [HeartwoodSession.trySilent]/
 * [HeartwoodSession.withClient] return this instead of a bare [HeartwoodResult] so a caller (the
 * activity log) can record an accurate signed-vs-answered-from-cache outcome rather than guessing. */
sealed interface HeartwoodOutcome<out T> {
    val result: HeartwoodResult<T>
    data class Cached<T>(override val result: HeartwoodResult<T>) : HeartwoodOutcome<T>
    data class Fresh<T>(override val result: HeartwoodResult<T>) : HeartwoodOutcome<T>
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
        heartwoodCatch {
            // NonCancellable: a live test showed a `withTimeoutOrNull` cancelling a coroutine
            // mid-FFI-call left the process wedged (native, no Java exception). rust-nostr's own
            // NostrConnect constructor already takes a Duration bounding each relay round trip
            // internally, so there is no need for a JVM-side timeout that could cancel this call
            // out from under the native code -- see HeartwoodSession for where callers actually
            // get bounded-wait behaviour instead.
            withContext(NonCancellable + Dispatchers.IO) {
                val uri = NostrConnectUri.parse(bunkerUri)
                val keys = Keys(SecretKey.parse(clientSecretKeyHex))
                val client = NostrConnect(uri, keys, CALL_TIMEOUT, RelayOptions())
                session?.close()
                session = client
                client.getPublicKey().toHex()
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
        return heartwoodCatch {
            // See the comment in connect(): never wrap a live FFI call in a JVM-cancellable timeout.
            withContext(NonCancellable + Dispatchers.IO) { block(client) }
        }
    }

    private companion object {
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

/** [Pairing.label] if the user set one, else a truncated npub -- the one place display falls
 * back, so `MainActivity`'s pairing list and connected-apps rows agree on what a pairing is called. */
fun Pairing.displayLabel(): String = label?.takeIf { it.isNotBlank() } ?: npubDisplay(signerPubkeyHex)

/**
 * The app's process-wide [SessionRegistry], shared by [dev.forgesworn.cambium.nip55.SignerActivity]
 * and [dev.forgesworn.cambium.nip55.SignerProvider], wired to the real [RustNostrHeartwoodClient]
 * and logcat. All the actual behaviour -- one worker per identity, per-identity admission control
 * and decrypt caching, atomic session creation -- lives in [SessionRegistry], which is pure Kotlin
 * and JVM-tested against a fake client; this object exists so the two NIP-55 surfaces agree on a
 * single instance without either owning it.
 */
object HeartwoodSession {
    private const val TAG = "HeartwoodSession"

    private val registry = SessionRegistry(
        clientFactory = ::RustNostrHeartwoodClient,
        logWarning = { Log.w(TAG, it) },
    )

    suspend fun trySilent(
        pairing: Pairing,
        cacheable: CacheableDecrypt? = null,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ): HeartwoodOutcome<String>? = registry.trySilent(pairing, cacheable, operation)

    suspend fun withClient(
        pairing: Pairing,
        cacheable: CacheableDecrypt? = null,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ): HeartwoodOutcome<String> = registry.withClient(pairing, cacheable, operation)

    /** See [SessionRegistry.shutdown]. */
    suspend fun shutdown(signerPubkeyHex: String) = registry.shutdown(signerPubkeyHex)

    /** See [SessionRegistry.shutdownAll]. */
    suspend fun shutdownAll() = registry.shutdownAll()
}
