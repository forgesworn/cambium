package dev.forgesworn.cambium.signer

import android.util.Log
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.Pairing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.NostrSdkException
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayOptions
import rust.nostr.sdk.SecretKey
import rust.nostr.sdk.UnsignedEvent
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 * Registry of per-identity Heartwood sessions, keyed by signer pubkey, shared by
 * [dev.forgesworn.cambium.nip55.SignerActivity] and [dev.forgesworn.cambium.nip55.SignerProvider].
 * Cambium pairs more than one Heartwood identity from 0.3.0 on, and each identity's NIP-46
 * connection, admission control and decrypt cache must stay fully isolated from every other
 * identity's -- a burst of requests against identity A must never shed or slow down identity B,
 * and a cached decrypt for A must never leak into B's answers. [Session] (below) is the exact
 * single-pairing design this object used to *be* before 0.3.0, now instantiated once per signer
 * pubkey instead of once for the whole app; every invariant it documents still holds, just scoped
 * to one identity's worker instead of the app's only worker.
 */
object HeartwoodSession {
    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun trySilent(
        pairing: Pairing,
        cacheable: CacheableDecrypt? = null,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ): HeartwoodOutcome<String>? = sessionFor(pairing).trySilent(pairing, cacheable, operation)

    suspend fun withClient(
        pairing: Pairing,
        cacheable: CacheableDecrypt? = null,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ): HeartwoodOutcome<String> = sessionFor(pairing).withClient(pairing, cacheable, operation)

    /** Drops one identity's session and decrypt cache. Call after removing that one pairing, and
     * after refreshing an existing pairing's connection details (relays/secret), so the next call
     * reconnects against what was just persisted rather than reusing a stale client. */
    suspend fun shutdown(signerPubkeyHex: String) {
        sessions.remove(signerPubkeyHex)?.shutdown()
    }

    /** Drops every identity's session and decrypt cache. Call on a full reset (`PairingStore.clearAll`). */
    suspend fun shutdownAll() {
        val all = sessions.keys.mapNotNull { sessions.remove(it) }
        all.forEach { it.shutdown() }
    }

    private fun sessionFor(pairing: Pairing): Session = sessions.getOrPut(pairing.signerPubkeyHex) { Session() }

    /**
     * One dedicated worker + decrypt cache for exactly one Heartwood identity. Two problems
     * surfaced in a live test against a real device under load (Amethyst bursting ~10 provider
     * queries while the user typed a reply), back when this design served the app's one and only
     * pairing directly:
     *
     * 1. **Concurrency.** A mutex-guarded design still let a caller's own timeout
     *    (`withTimeoutOrNull` in the content provider) cancel the *same coroutine* that was in the
     *    middle of an FFI call, because the mutex only serialised entry into the critical section
     *    -- it did not stop the calling coroutine's own cancellation from reaching straight
     *    through it into `operation(...)`. Two requests came back `Protocol(unauthorised)`
     *    (consistent with a half-torn-down rust-nostr call), and the process died outright once
     *    (no Java exception -- suspected native wedge from a cancelled in-flight call).
     * 2. **Queueing.** Everything shared one lock with no admission control, so a burst of silent
     *    queries piled up behind a 1.5-2s relay round trip and the tail ones blew their timeout,
     *    falling back to the visible intent -- a popup storm of its own, just delayed.
     *
     * The fix: every call against this identity is handed to exactly one dedicated worker
     * coroutine, running on its own single-thread dispatcher, in a [CoroutineScope] with no
     * parent -- nothing a caller does can ever cancel work already handed to it. A caller gets
     * its result via a [CompletableDeferred] and waits on that (a safe cancellation point: giving
     * up just stops waiting, the queued job runs to completion regardless, which still warms the
     * session for next time). [trySilent] additionally sheds load: if [MAX_QUEUED] calls are
     * already queued or running *for this identity*, it refuses new silent-path work immediately
     * instead of adding a fourth -- admission control is per identity, so a burst against one
     * paired signer can never shed a request against another. [withClient] (the intent path)
     * always queues -- the user is already looking at a progress overlay -- but shares this same
     * worker, so a popup can never run concurrently with a silent-path call against the same
     * identity either.
     *
     * A third live-use finding: Amethyst re-requests the same nip04/nip44 decrypt repeatedly
     * while browsing, including content that deterministically cannot decrypt (legacy "Could not
     * decrypt" items) -- each retry otherwise costs a full round trip, and there is no reason to
     * ask Heartwood the same deterministic question twice. Both [trySilent] and [withClient]
     * consult [DecryptCache] *before* touching the queue at all when the caller passes a
     * [CacheableDecrypt]; a hit answers instantly without ever reaching the worker. [decryptCache]
     * is instantiated once per [Session] -- i.e. once per identity -- which is what makes it
     * partitioned per pairing: a decrypt cached while talking to identity A can never answer a
     * request routed to identity B, since B has its own, entirely separate cache instance.
     */
    private class Session {
        private val workerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "heartwood-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        // No parent job: nothing outside this session can cancel work already handed to the worker.
        private val workerScope = CoroutineScope(SupervisorJob() + workerDispatcher)

        private var client: RustNostrHeartwoodClient? = null
        private val queueDepth = AtomicInteger(0)
        private val decryptCache = DecryptCache()
        private val shedCount = AtomicInteger(0)
        private val lastShedLogAt = AtomicLong(0L)

        private sealed interface Message {
            data class Call(
                val pairing: Pairing,
                val operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
                val deferred: CompletableDeferred<HeartwoodResult<String>>,
            ) : Message

            data class Shutdown(val deferred: CompletableDeferred<Unit>) : Message
        }

        private val inbox = Channel<Message>(capacity = Channel.UNLIMITED)

        init {
            workerScope.launch {
                for (message in inbox) {
                    when (message) {
                        is Message.Call -> {
                            val result = runCatching { deliver(message.pairing, message.operation) }
                                .getOrElse { e -> HeartwoodResult.Failure(HeartwoodError.Protocol(e.message ?: "worker error")) }
                            queueDepth.decrementAndGet()
                            message.deferred.complete(result)
                        }
                        is Message.Shutdown -> {
                            client?.disconnect()
                            client = null
                            message.deferred.complete(Unit)
                            // End the consumer: this Session is being discarded (see shutdown()),
                            // and before 0.3.0's per-identity sessions a worker never needed to
                            // die -- now each unpair/re-pair replaces the Session, so a worker
                            // that lived on would leak its thread (and pin this object) forever.
                            break
                        }
                    }
                }
            }
        }

        suspend fun trySilent(
            pairing: Pairing,
            cacheable: CacheableDecrypt?,
            operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
        ): HeartwoodOutcome<String>? {
            cachedResult(cacheable)?.let { return HeartwoodOutcome.Cached(it) }

            if (!reserveSlot()) {
                recordShed()
                return null
            }
            val result = submitAndAwait(pairing, SILENT_TIMEOUT_MILLIS, operation)
            recordCacheOutcome(cacheable, result)
            return result?.let { HeartwoodOutcome.Fresh(it) }
        }

        suspend fun withClient(
            pairing: Pairing,
            cacheable: CacheableDecrypt?,
            operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
        ): HeartwoodOutcome<String> {
            cachedResult(cacheable)?.let { return HeartwoodOutcome.Cached(it) }

            queueDepth.incrementAndGet()
            val result = submitAndAwait(pairing, INTENT_TIMEOUT_MILLIS, operation)
            recordCacheOutcome(cacheable, result)
            return HeartwoodOutcome.Fresh(result ?: HeartwoodResult.Failure(HeartwoodError.Timeout))
        }

        suspend fun shutdown() {
            decryptCache.clear()
            val deferred = CompletableDeferred<Unit>()
            runCatching { inbox.send(Message.Shutdown(deferred)) }
                .onSuccess { deferred.await() }
            // Release the dedicated thread. HeartwoodSession removes this Session from its map
            // before calling here, so no new caller can reach it; a caller that grabbed the
            // reference just before removal fails its send harmlessly (see submitAndAwait).
            inbox.close()
            workerScope.cancel()
            workerDispatcher.close()
        }

        private fun cachedResult(cacheable: CacheableDecrypt?): HeartwoodResult<String>? {
            val key = cacheable ?: return null
            return when (val cached = decryptCache.get(key)) {
                is CachedOutcome.Success -> HeartwoodResult.Success(cached.value)
                is CachedOutcome.DeterministicFailure -> HeartwoodResult.Failure(HeartwoodError.Protocol(cached.message))
                null -> null
            }
        }

        /**
         * Populates the cache from a live outcome. [result] is `null` on a timeout -- never
         * cached, since that is transient. A failure is only cached when it is a deterministic
         * "cannot decrypt this" answer (see [isDeterministicDecryptFailure]); anything else
         * (queue-full, connect errors, an "unauthorised"/policy refusal) is repairable and must
         * be retried.
         */
        private fun recordCacheOutcome(cacheable: CacheableDecrypt?, result: HeartwoodResult<String>?) {
            val key = cacheable ?: return
            when (result) {
                null -> Unit
                is HeartwoodResult.Success -> decryptCache.putSuccess(key, result.value)
                is HeartwoodResult.Failure -> if (isDeterministicDecryptFailure(result.error)) {
                    decryptCache.putDeterministicFailure(key, (result.error as HeartwoodError.Protocol).message)
                }
            }
        }

        private fun reserveSlot(): Boolean {
            while (true) {
                val current = queueDepth.get()
                if (current >= MAX_QUEUED) return false
                if (queueDepth.compareAndSet(current, current + 1)) return true
            }
        }

        /** Rate-limited to about once a minute so a sustained burst doesn't spam logcat, but
         * still gives future tuning a read on how often silent-path admission control is
         * actually shedding, per identity. */
        private fun recordShed() {
            val count = shedCount.incrementAndGet()
            val now = System.currentTimeMillis()
            val last = lastShedLogAt.get()
            if (now - last >= SHED_LOG_INTERVAL_MILLIS && lastShedLogAt.compareAndSet(last, now)) {
                Log.w(TAG, "shed: queue full x$count in the last minute (MAX_QUEUED=$MAX_QUEUED)")
                shedCount.set(0)
            }
        }

        private suspend fun submitAndAwait(
            pairing: Pairing,
            timeoutMillis: Long,
            operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
        ): HeartwoodResult<String>? {
            val deferred = CompletableDeferred<HeartwoodResult<String>>()
            // The send fails only when this Session was shut down between the caller resolving it
            // and reaching here (the inbox is closed in shutdown()) -- answer like a timeout: the
            // caller retries against the fresh Session the registry creates on its next call. The
            // slot reserved by trySilent/withClient is normally released by the worker; there is
            // no worker any more, so release it here.
            val sent = runCatching { inbox.send(Message.Call(pairing, operation, deferred)) }
            if (sent.isFailure) {
                queueDepth.decrementAndGet()
                return null
            }
            // Only this wait is cancellable -- the job itself runs on the worker regardless of
            // whether we give up on it here (see the class doc for why that matters).
            return withTimeoutOrNull(timeoutMillis) { deferred.await() }
        }

        /** Runs on the worker thread only: reconnects if there is no held client yet, then runs
         * [operation], retrying once against a fresh connection if it fails. There is no
         * "does the cached client match this pairing" check here the way the pre-0.3.0 single
         * global session needed -- this [Session] only ever serves the one identity it was
         * created for, so any cached [client] already matches [pairing] by construction. A
         * pairing's relays/secret changing (a re-pair) instead goes through [HeartwoodSession.shutdown],
         * which drops this whole [Session] so the next call starts fresh. */
        private suspend fun deliver(
            pairing: Pairing,
            operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
        ): HeartwoodResult<String> {
            val connected = client?.let { HeartwoodResult.Success(it) } ?: reconnect(pairing)

            val active = when (connected) {
                is HeartwoodResult.Success -> connected.value
                is HeartwoodResult.Failure -> return connected
            }

            val result = operation(active)
            if (result is HeartwoodResult.Success) return result

            val retried = when (val reconnected = reconnect(pairing)) {
                is HeartwoodResult.Success -> reconnected.value
                is HeartwoodResult.Failure -> return result
            }
            return operation(retried)
        }

        /** Runs on the worker thread only. */
        private suspend fun reconnect(pairing: Pairing): HeartwoodResult<HeartwoodClient> {
            client?.disconnect()
            client = null

            val fresh = RustNostrHeartwoodClient()
            val bunkerUri = BunkerUri(pairing.signerPubkeyHex, pairing.relays, pairing.secret).toUriString()
            return when (val connected = fresh.connect(bunkerUri, pairing.clientSecretKeyHex)) {
                is HeartwoodResult.Success -> {
                    client = fresh
                    HeartwoodResult.Success(fresh)
                }
                is HeartwoodResult.Failure -> connected
            }
        }

        private companion object {
            const val TAG = "HeartwoodSession"
            const val MAX_QUEUED = 3
            const val SILENT_TIMEOUT_MILLIS = 15_000L
            const val INTENT_TIMEOUT_MILLIS = 20_000L
            const val SHED_LOG_INTERVAL_MILLIS = 60_000L
        }
    }
}
