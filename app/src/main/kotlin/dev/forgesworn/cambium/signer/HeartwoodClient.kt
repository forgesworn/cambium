package dev.forgesworn.cambium.signer

import android.util.Log
import dev.forgesworn.cambium.nip55.normaliseUnsignedEvent
import dev.forgesworn.cambium.pairing.BunkerUriParser
import dev.forgesworn.cambium.pairing.BunkerUriResult
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.NostrSdkException
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayOptions
import rust.nostr.sdk.SecretKey
import rust.nostr.sdk.UnsignedEvent
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientMessage
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.Kind
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.Nip44Version
import rust.nostr.sdk.nip44Encrypt
import rust.nostr.sdk.nip44Decrypt

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

/** Wraps a [HeartwoodResult] with whether it was answered from [DecryptCache] or [SignCache]
 * without reaching Heartwood, or came from a real call -- [HeartwoodSession.trySilent]/
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
    /** This identity already has the maximum number of running or queued requests. */
    data object Busy : HeartwoodError
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
    // Logged because the NIP-55 surface deliberately answers callers without error detail
    // (silent path: no UI at all) -- without this line a protocol failure is invisible
    // everywhere, which is what made the Primal created_at interop bug expensive to find.
    Log.w("HeartwoodSession", "rust-nostr call failed", e)
    HeartwoodResult.Failure(heartwoodErrorForSdkMessage(e.message ?: "rust-nostr error"))
} catch (e: IllegalArgumentException) {
    Log.w("HeartwoodSession", "rust-nostr rejected input", e)
    HeartwoodResult.Failure(HeartwoodError.InvalidInput(e.message ?: "invalid input"))
}

internal fun heartwoodErrorForSdkMessage(message: String): HeartwoodError =
    if (message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true)) {
        HeartwoodError.Timeout
    } else {
        HeartwoodError.Protocol(message)
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

    /** A fresh authenticated contact; production must not use the SDK's cached public key. */
    suspend fun ping(): HeartwoodResult<String> = getPublicKey()

    /** Optional NIP-46 method. A refusal never invalidates an otherwise working session. */
    suspend fun switchRelays(): RelaySwitchResult = RelaySwitchResult.Unchanged

    /** [eventJson] is an unsigned event; returns the same event as JSON with `sig` and `id` filled in. */
    suspend fun signEvent(eventJson: String): HeartwoodResult<String>

    suspend fun nip04Encrypt(pubkeyHex: String, content: String): HeartwoodResult<String>
    suspend fun nip04Decrypt(pubkeyHex: String, content: String): HeartwoodResult<String>
    suspend fun nip44Encrypt(pubkeyHex: String, content: String): HeartwoodResult<String>
    suspend fun nip44Decrypt(pubkeyHex: String, content: String): HeartwoodResult<String>

    /** Releases the underlying relay session. Safe to call when not connected. */
    fun disconnect()
}

class RustNostrHeartwoodClient internal constructor(
    private val parseTransport: (String) -> BunkerUri?,
) : HeartwoodClient {
    constructor() : this({ (BunkerUriParser.parse(it) as? BunkerUriResult.Valid)?.uri })

    private var session: NostrConnect? = null
    private var transport: BunkerUri? = null
    private var transportKeys: Keys? = null
    /** The user identity proved by this exact NIP-46 session's connect handshake. */
    private var signerPubkeyHex: String? = null

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
                session?.close()
                session = null
                signerPubkeyHex = null
                // The handshake proves the board is reachable and which identity answers, and
                // needs no press on the board, so it keeps the short bound: an unplugged board or
                // a dead relay is reported in HANDSHAKE_TIMEOUT. rust-nostr fixes one timeout per
                // client, so the session that carries approval-bearing requests is a second
                // client with the long bound, connecting on its first request.
                val probe = NostrConnect(uri, keys, HANDSHAKE_TIMEOUT, RelayOptions())
                val connectedPubkeyHex = try {
                    probe.getPublicKey().toHex()
                } finally {
                    probe.close()
                }
                session = NostrConnect(uri, keys, CALL_TIMEOUT, RelayOptions())
                signerPubkeyHex = connectedPubkeyHex
                transport = parseTransport(bunkerUri)
                transportKeys = keys
                connectedPubkeyHex
            }
        }

    override suspend fun getPublicKey(): HeartwoodResult<String> = withSession { client ->
        client.getPublicKey().toHex()
    }

    override suspend fun ping(): HeartwoodResult<String> {
        val response = rawRpc("ping", 8_000L)
            ?: return HeartwoodResult.Failure(HeartwoodError.Timeout)
        return if ((response["result"] as? JsonPrimitive)?.content == "pong" &&
            (response["error"] == null || response["error"] == JsonNull)) {
            HeartwoodResult.Success("pong")
        } else HeartwoodResult.Failure(HeartwoodError.Protocol("Signer declined connection check"))
    }

    override suspend fun switchRelays(): RelaySwitchResult =
        rawRpc("switch_relays", 3_000L)?.let(RelaySwitch::parse) ?: RelaySwitchResult.Unavailable

    /** SDK 0.44.8 exposes no generic NostrConnect RPC. This narrow channel uses its audited
     * crypto and relay client, the same connection keys and exact bunker author. The notification
     * FFI is ended by shutdown, never cancellation. Only the Kotlin deferred wait times out.
     * sendMsgTo queues the signed event without waiting for an unrelated relay OK acknowledgement. */
    private suspend fun rawRpc(method: String, waitMillis: Long): JsonObject? {
        val remote = transport ?: return null
        val keys = transportKeys ?: return null
        if (session == null) return null
        return withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                coroutineScope {
                    val relayClient = Client(NostrSigner.keys(keys))
                    var listener: kotlinx.coroutines.Job? = null
                    try {
                        // The same ephemeral NIP-46 client identity already used on these relays.
                        relayClient.automaticAuthentication(true)
                        val relays = remote.relays.take(RelaySwitch.MAX_RELAYS).map(RelayUrl::parse)
                        relays.forEach { relayClient.addRelay(it) }
                        relayClient.connect()
                        relayClient.waitForConnection(Duration.ofMillis(waitMillis))
                        val remoteKey = PublicKey.parse(remote.signerPubkeyHex)
                        val requestId = UUID.randomUUID().toString()
                        val response = CompletableDeferred<JsonObject>()
                        listener = launch(start = CoroutineStart.UNDISPATCHED) {
                            runCatching {
                                relayClient.handleNotifications(object : HandleNotification {
                                    override suspend fun handle(relayUrl: RelayUrl, subscriptionId: String, event: Event) {
                                        if (response.isCompleted) return
                                        runCatching {
                                            Nip46Envelope.openResponse(event, keys, remote.signerPubkeyHex, requestId)
                                                ?.let(response::complete)
                                        }
                                    }
                                    override suspend fun handleMsg(relayUrl: RelayUrl, msg: RelayMessage) = Unit
                                })
                            }
                        }
                        // A normal subscription survives EOSE, unlike SDK streamEvents().
                        relayClient.subscribe(Filter().kind(Kind(24133u)).author(remoteKey)
                            .pubkey(keys.publicKey()).since(Timestamp.fromSecs(
                                (System.currentTimeMillis() / 1000L - 30L).toULong())), null)
                        val request = Nip46Envelope.request(keys, remoteKey, requestId, method)
                        relayClient.sendMsgTo(relays, ClientMessage.event(request))
                        withTimeoutOrNull(waitMillis) { response.await() }
                    } finally {
                        relayClient.shutdown()
                        listener?.join()
                        relayClient.close()
                    }
                }
            }.getOrNull()
        }
    }

    override suspend fun signEvent(eventJson: String): HeartwoodResult<String> {
        val connectedPubkeyHex = signerPubkeyHex
            ?: return HeartwoodResult.Failure(HeartwoodError.NotConnected)
        return withSession { client ->
            // NIP-55's web example omits pubkey, created_at and tags; Amber/Primal-compatible
            // defaults are applied from this session's handshake identity before rust-nostr parses it.
            val unsigned = UnsignedEvent.fromJson(
                normaliseUnsignedEvent(
                    eventJson,
                    signerPubkeyHex = connectedPubkeyHex,
                    nowEpochSeconds = System.currentTimeMillis() / 1000L,
                ),
            )
            client.signEvent(unsigned).asJson()
        }
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
        signerPubkeyHex = null
        transport = null
        transportKeys = null
    }

    private suspend fun withSession(block: suspend (NostrConnect) -> String): HeartwoodResult<String> {
        val client = session ?: return HeartwoodResult.Failure(HeartwoodError.NotConnected)
        return heartwoodCatch {
            // See the comment in connect(): never wrap a live FFI call in a JVM-cancellable timeout.
            withContext(NonCancellable + Dispatchers.IO) { block(client) }
        }
    }

    private companion object {
        /** Bounds the connect handshake, which no press on the board can delay: it answers at
         * once or not at all, so an unreachable board is known within this. */
        val HANDSHAKE_TIMEOUT: Duration = Duration.ofSeconds(20)
        /** Bounds each NIP-46 round trip of the session inside rust-nostr, approval included.
         * Heartwood holds a sign request on its screen for 30 seconds before answering "timeout"
         * itself, so this must outlast that window with room for relay delivery, or a press late
         * in the window is cut off here and the board's signature arrives to nobody. The caller's
         * own wait is [SessionRegistry.INTENT_TIMEOUT_MILLIS]; this stays just under it so the
         * board's own answer, a signature or its "timeout", reaches the caller first. */
        val CALL_TIMEOUT: Duration = Duration.ofSeconds(40)
    }
}

/** Shared with Android boundary tests: the native SDK supplies every cryptographic operation. */
internal object Nip46Envelope {
    fun request(keys: Keys, remote: PublicKey, id: String, method: String): Event {
        val content = nip44Encrypt(keys.secretKey(), remote, RelaySwitch.request(id, method), Nip44Version.V2)
        return EventBuilder(Kind(24133u), content).tags(listOf(Tag.publicKey(remote))).signWithKeys(keys)
    }

    fun openResponse(event: Event, keys: Keys, signerHex: String, requestId: String): JsonObject? = runCatching {
        if (event.kind().asU16().toInt() != 24133 || event.author().toHex() != signerHex ||
            !event.verify() || event.content().length > RelaySwitch.MAX_RESPONSE_CHARS * 2) return null
        val recipients = event.tags().toVec().mapNotNull { tag ->
            tag.asVec().takeIf { it.firstOrNull() == "p" }?.getOrNull(1)
        }
        val clientHex = keys.publicKey().toHex()
        if (clientHex !in recipients) return null
        val plain = nip44Decrypt(keys.secretKey(), PublicKey.parse(signerHex), event.content())
        RelaySwitch.correlatedResponse(plain, requestId, event.author().toHex(), signerHex, recipients, clientHex)
    }.getOrNull()
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
 * Bech32 "npub" form of a hex pubkey for the NIP-55 wire. Amber answers `get_public_key`
 * with an npub and clients depend on that exact shape -- Primal's login input (verified
 * against its source and live behaviour, July 2026) treats a raw 64-hex result as a
 * candidate *private* key and derives a phantom identity from it -- so the npub form is
 * load-bearing, not cosmetic. Unlike [npubDisplay] there is no truncated fallback: a value
 * that does not parse must fail loudly rather than go out on the wire; callers only pass
 * keys already validated at pairing time.
 */
fun npubWire(pubkeyHex: String): String = PublicKey.parse(pubkeyHex).toBech32()

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

    @Volatile private var relayUpdateHandler: (Pairing, List<String>) -> Boolean = { _, _ -> false }

    fun configureRelayUpdates(handler: (Pairing, List<String>) -> Boolean) {
        relayUpdateHandler = handler
    }

    private val registry = SessionRegistry(
        clientFactory = ::RustNostrHeartwoodClient,
        persistRelayUpdate = { pairing, relays -> relayUpdateHandler(pairing, relays) },
        logWarning = { Log.w(TAG, it) },
    )

    val health get() = registry.health
    fun recordPairingContact(signerPubkeyHex: String) = registry.recordPairingContact(signerPubkeyHex)
    suspend fun testConnection(pairing: Pairing) = registry.testConnection(pairing)
    suspend fun reconnect(pairing: Pairing) = registry.reconnect(pairing)

    suspend fun trySilent(
        pairing: Pairing,
        cacheable: CacheableDecrypt? = null,
        priority: HeartwoodRequestPriority = HeartwoodRequestPriority.INTERACTIVE,
        cacheableSign: CacheableSign? = null,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ): HeartwoodOutcome<String>? = registry.trySilent(pairing, cacheable, priority, cacheableSign, operation)

    suspend fun withClient(
        pairing: Pairing,
        cacheable: CacheableDecrypt? = null,
        priority: HeartwoodRequestPriority = HeartwoodRequestPriority.INTERACTIVE,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ): HeartwoodOutcome<String> = registry.withClient(pairing, cacheable, priority, operation)

    /** See [SessionRegistry.shutdown]. */
    suspend fun shutdown(signerPubkeyHex: String) = registry.shutdown(signerPubkeyHex)

    /** See [SessionRegistry.shutdownAll]. */
    suspend fun shutdownAll() = registry.shutdownAll()
}
