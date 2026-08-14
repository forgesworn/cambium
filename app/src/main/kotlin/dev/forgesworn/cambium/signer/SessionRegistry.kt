package dev.forgesworn.cambium.signer

import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.Pairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Scheduling class for one identity's bounded Heartwood worker. */
enum class HeartwoodRequestPriority(internal val rank: Int) {
    INTERACTIVE(0),
    AUTH(1),
    BACKGROUND(2),
    MAINTENANCE(3),
}

/**
 * Registry of per-identity Heartwood sessions, keyed by signer pubkey. Cambium pairs more than
 * one Heartwood identity from 0.3.0 on, and each identity's NIP-46 connection, admission control
 * and request caches must stay fully isolated from every other identity's -- a burst of requests
 * against identity A must never shed or slow down identity B, and a cached decrypt for A must
 * never leak into B's answers. [Session] (below) is the exact single-pairing design
 * `HeartwoodSession` used to *be* before 0.3.0, now instantiated once per signer pubkey instead
 * of once for the whole app; every invariant it documents still holds, just scoped to one
 * identity's worker instead of the app's only worker.
 *
 * Pure Kotlin -- no Android, no rust-nostr: the NIP-46 client is constructed via [clientFactory]
 * and log lines go through [logWarning], so every invariant here (one worker per identity,
 * per-identity admission control, cache partitioning, atomic session creation, callers unable to
 * cancel in-flight work) is exercised by `SessionRegistryTest` on the host JVM against a fake
 * client. [HeartwoodSession] is the app's process-wide instance, wired to
 * [RustNostrHeartwoodClient] and logcat.
 */
class SessionRegistry(
    private val clientFactory: () -> HeartwoodClient,
    private val logWarning: (String) -> Unit = {},
    private val silentTimeoutMillis: Long = SILENT_TIMEOUT_MILLIS,
    private val intentTimeoutMillis: Long = INTENT_TIMEOUT_MILLIS,
    private val sessionMaxIdleMillis: Long = SESSION_MAX_IDLE_MILLIS,
    private val signCacheTtlMillis: Long = SIGN_CACHE_TTL_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun trySilent(
        pairing: Pairing,
        cacheable: CacheableDecrypt? = null,
        priority: HeartwoodRequestPriority = HeartwoodRequestPriority.INTERACTIVE,
        cacheableSign: CacheableSign? = null,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ): HeartwoodOutcome<String>? = sessionFor(pairing).trySilent(pairing, cacheable, priority, cacheableSign, operation)

    suspend fun withClient(
        pairing: Pairing,
        cacheable: CacheableDecrypt? = null,
        priority: HeartwoodRequestPriority = HeartwoodRequestPriority.INTERACTIVE,
        operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
    ): HeartwoodOutcome<String> = sessionFor(pairing).withClient(pairing, cacheable, priority, operation)

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

    // ConcurrentHashMap, but computeIfAbsent specifically -- not Kotlin's getOrPut extension,
    // which is plain get-then-put with no atomicity of its own even on a concurrent map. Two
    // threads racing getOrPut for the same brand-new identity could each construct a Session
    // (spinning up its own worker thread) before either put() runs; the loser's Session -- and
    // its thread -- would then be silently overwritten in the map and leaked, since nothing ever
    // held a reference to shut it down again. computeIfAbsent is atomic per key: the second
    // caller blocks until the first's factory has already returned and been stored.
    private fun sessionFor(pairing: Pairing): Session =
        sessions.computeIfAbsent(pairing.signerPubkeyHex) { signerPubkeyHex -> Session(signerPubkeyHex) }

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
     * its result via a [CompletableDeferred] and waits on that (a safe cancellation point). If a
     * caller gives up while its call is still queued, the worker skips that stale call; an
     * operation already running is allowed to finish safely and can still warm the decrypt cache,
     * but is never retried after its caller has gone away. [trySilent] additionally sheds load.
     * At most [MAX_QUEUED] calls can be queued or running *for this identity*, and background/AUTH
     * traffic can use only [MAX_NON_INTERACTIVE_QUEUED] of those slots so an interactive sign or
     * encryption request still has room to enter the priority queue. Admission control is per
     * identity, so a burst against one paired signer can never shed a request against another.
     * [withClient] (the intent path) uses
     * the same bound instead of creating an unlimited second queue, and shares this worker so a
     * popup can never run concurrently with a silent-path call against the same identity either.
     *
     * A third live-use finding: Amethyst re-requests the same nip04/nip44 decrypt repeatedly
     * while browsing, including content that deterministically cannot decrypt (legacy "Could not
     * decrypt" items) -- each retry otherwise costs a full round trip, and there is no reason to
     * ask Heartwood the same deterministic question twice. Both [trySilent] and [withClient]
     * consult [DecryptCache] *before* touching the queue at all when the caller passes a
     * [CacheableDecrypt]; a hit answers instantly without ever reaching the worker. The worker
     * checks again when queued work starts, so identical decrypts submitted together share the
     * first completed result instead of each calling Heartwood. [decryptCache]
     * is instantiated once per [Session] -- i.e. once per identity -- which is what makes it
     * partitioned per pairing: a decrypt cached while talking to identity A can never answer a
     * request routed to identity B, since B has its own, entirely separate cache instance.
     * Exact NIP-42 duplicates additionally share one of the bounded [signLocks] before admission
     * and reuse [signCache] briefly. That cache is also per [Session], and callers must explicitly
     * opt an event into it; the NIP-55 provider does so only for kind 22242.
     */
    private inner class Session(signerPubkeyHex: String) {
        // Short, log-friendly tag identifying which identity this Session belongs to -- the
        // registry's map key lives in the sessions map, not here, so without this a Session has
        // no way to name itself in its own log lines (see recordShed).
        private val tag = signerPubkeyHex.take(8)

        private val workerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "heartwood-worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        // No parent job: nothing outside this session can cancel work already handed to the worker.
        private val workerScope = CoroutineScope(SupervisorJob() + workerDispatcher)

        private var client: HeartwoodClient? = null
        private var lastHealthyAtMillis: Long? = null
        private val queueDepth = AtomicInteger(0)
        private val decryptCache = DecryptCache()
        private val signCache = SignCache(
            ttlMillis = signCacheTtlMillis,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
        )
        // Exact duplicate AUTH calls share a stripe before admission. This is bounded state (not
        // one lock per event), and 256 stripes make unrelated challenges colliding negligible.
        private val signLocks = Array(SIGN_LOCK_STRIPES) { Mutex() }
        private val shedCount = AtomicInteger(0)
        private val lastShedLogAt = AtomicLong(0L)
        private val nextSequence = AtomicLong(0L)
        private val accepting = AtomicBoolean(true)
        private val queueLifecycle = Any()

        private val inbox = PriorityBlockingQueue<Message>(
            INITIAL_QUEUE_CAPACITY,
            compareBy<Message> { it.priorityRank }.thenBy { it.sequence },
        )

        init {
            workerScope.launch {
                while (true) {
                    val message = inbox.take()
                    when (message) {
                        is Message.Call -> {
                            val completed = if (message.tryStart()) {
                                val cached = cachedResult(message.cacheable, message.cacheableSign)
                                if (cached != null) {
                                    CompletedCall(cached, fromCache = true)
                                } else {
                                    val result = runCatching {
                                        deliver(message.pairing, message.operation) { message.waiterActive.get() }
                                    }.getOrElse { e ->
                                        HeartwoodResult.Failure(HeartwoodError.Protocol(e.message ?: "worker error"))
                                    }.also { recordCacheOutcome(message.cacheable, message.cacheableSign, it) }
                                    CompletedCall(result, fromCache = false)
                                }
                            } else {
                                CompletedCall(HeartwoodResult.Failure(HeartwoodError.Timeout), fromCache = false)
                            }
                            queueDepth.decrementAndGet()
                            message.deferred.complete(completed)
                        }
                        is Message.Shutdown -> {
                            discardClient()
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
            priority: HeartwoodRequestPriority,
            cacheableSign: CacheableSign?,
            operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
        ): HeartwoodOutcome<String>? {
            cachedResult(cacheable, cacheableSign)?.let { return HeartwoodOutcome.Cached(it) }

            if (cacheableSign != null) {
                // Bound the lock wait and the actual Heartwood request together. A follower whose
                // duplicate is already in flight waits here without consuming a queue slot; once
                // the leader completes, it reads the exact signed result from signCache.
                return withTimeoutOrNull(silentTimeoutMillis) {
                    signLockFor(cacheableSign).withLock {
                        cachedResult(cacheable, cacheableSign)?.let {
                            return@withLock HeartwoodOutcome.Cached(it)
                        }
                        trySilentUncoalesced(pairing, cacheable, priority, cacheableSign, operation)
                    }
                }
            }

            return trySilentUncoalesced(pairing, cacheable, priority, null, operation)
        }

        private suspend fun trySilentUncoalesced(
            pairing: Pairing,
            cacheable: CacheableDecrypt?,
            priority: HeartwoodRequestPriority,
            cacheableSign: CacheableSign?,
            operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
        ): HeartwoodOutcome<String>? {
            if (!reserveSlot(priority)) {
                recordShed(priority)
                return null
            }
            val completed = submitAndAwait(
                pairing,
                cacheable,
                cacheableSign,
                priority,
                silentTimeoutMillis,
                operation,
            )
                ?: return null
            return if (completed.fromCache) {
                HeartwoodOutcome.Cached(completed.result)
            } else {
                HeartwoodOutcome.Fresh(completed.result)
            }
        }

        suspend fun withClient(
            pairing: Pairing,
            cacheable: CacheableDecrypt?,
            priority: HeartwoodRequestPriority,
            operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
        ): HeartwoodOutcome<String> {
            cachedResult(cacheable, cacheableSign = null)?.let { return HeartwoodOutcome.Cached(it) }

            if (!reserveSlot(priority)) {
                recordShed(priority)
                return HeartwoodOutcome.Fresh(HeartwoodResult.Failure(HeartwoodError.Busy))
            }
            val completed = submitAndAwait(
                pairing,
                cacheable,
                cacheableSign = null,
                priority,
                intentTimeoutMillis,
                operation,
            )
                ?: return HeartwoodOutcome.Fresh(HeartwoodResult.Failure(HeartwoodError.Timeout))
            return if (completed.fromCache) {
                HeartwoodOutcome.Cached(completed.result)
            } else {
                HeartwoodOutcome.Fresh(completed.result)
            }
        }

        suspend fun shutdown() {
            decryptCache.clear()
            signCache.clear()
            val deferred = CompletableDeferred<Unit>()
            val queued = synchronized(queueLifecycle) {
                if (accepting.compareAndSet(true, false)) {
                    inbox.put(Message.Shutdown(nextSequence.getAndIncrement(), deferred))
                    true
                } else {
                    false
                }
            }
            if (queued) deferred.await()
            // Release the dedicated thread. The registry removes this Session from its map before
            // calling here, so no new caller can reach it; a caller that grabbed the reference
            // just before removal fails its send harmlessly (see submitAndAwait).
            workerScope.cancel()
            workerDispatcher.close()
        }

        private fun cachedResult(
            cacheable: CacheableDecrypt?,
            cacheableSign: CacheableSign?,
        ): HeartwoodResult<String>? {
            cacheableSign?.let { sign ->
                signCache.get(sign)?.let { return HeartwoodResult.Success(it) }
            }
            val decrypt = cacheable ?: return null
            return when (val cached = decryptCache.get(decrypt)) {
                is CachedOutcome.Success -> HeartwoodResult.Success(cached.value)
                is CachedOutcome.DeterministicFailure -> HeartwoodResult.Failure(HeartwoodError.Protocol(cached.message))
                null -> null
            }
        }

        /**
         * Populates the cache from a live outcome. [result] is `null` on a timeout -- never
         * cached, since that is transient. A failure is only cached when it is a deterministic
         * "cannot decrypt this" answer (see [isDeterministicDecryptFailure]); anything else
         * (queue-full, connect errors, an "unauthorised"/policy refusal) is not cached.
         */
        private fun recordCacheOutcome(
            cacheable: CacheableDecrypt?,
            cacheableSign: CacheableSign?,
            result: HeartwoodResult<String>?,
        ) {
            if (cacheableSign != null && result is HeartwoodResult.Success) {
                signCache.put(cacheableSign, result.value)
            }
            val key = cacheable ?: return
            when (result) {
                null -> Unit
                is HeartwoodResult.Success -> decryptCache.putSuccess(key, result.value)
                is HeartwoodResult.Failure -> if (isDeterministicDecryptFailure(result.error)) {
                    decryptCache.putDeterministicFailure(key, (result.error as HeartwoodError.Protocol).message)
                }
            }
        }

        private fun signLockFor(request: CacheableSign): Mutex {
            val index = (request.unsignedEventJson.hashCode() and Int.MAX_VALUE) % signLocks.size
            return signLocks[index]
        }

        private fun reserveSlot(priority: HeartwoodRequestPriority): Boolean {
            val limit = when (priority) {
                HeartwoodRequestPriority.INTERACTIVE -> MAX_QUEUED
                HeartwoodRequestPriority.AUTH,
                HeartwoodRequestPriority.BACKGROUND,
                -> MAX_NON_INTERACTIVE_QUEUED
                HeartwoodRequestPriority.MAINTENANCE -> MAX_MAINTENANCE_QUEUED
            }
            while (true) {
                val current = queueDepth.get()
                if (current >= limit) return false
                if (queueDepth.compareAndSet(current, current + 1)) return true
            }
        }

        /** Rate-limited to about once a minute so a sustained burst doesn't spam logcat, but
         * still gives future tuning a read on how often silent-path admission control is
         * actually shedding, per identity. */
        private fun recordShed(priority: HeartwoodRequestPriority) {
            val count = shedCount.incrementAndGet()
            val now = System.currentTimeMillis()
            val last = lastShedLogAt.get()
            if (now - last >= SHED_LOG_INTERVAL_MILLIS && lastShedLogAt.compareAndSet(last, now)) {
                logWarning(
                    "shed ($tag): admission full for $priority x$count in the last minute " +
                        "(depth=${queueDepth.get()}, MAX_QUEUED=$MAX_QUEUED)"
                )
                shedCount.set(0)
            }
        }

        private suspend fun submitAndAwait(
            pairing: Pairing,
            cacheable: CacheableDecrypt?,
            cacheableSign: CacheableSign?,
            priority: HeartwoodRequestPriority,
            timeoutMillis: Long,
            operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
        ): CompletedCall? {
            val deferred = CompletableDeferred<CompletedCall>()
            val message = Message.Call(
                pairing = pairing,
                cacheable = cacheable,
                cacheableSign = cacheableSign,
                priority = priority,
                sequence = nextSequence.getAndIncrement(),
                operation = operation,
                deferred = deferred,
            )
            // Enqueueing fails only when this Session was shut down between the caller resolving it
            // and reaching here -- answer like a timeout: the
            // caller retries against the fresh Session the registry creates on its next call. The
            // slot reserved by trySilent/withClient is normally released by the worker; there is
            // no worker any more, so release it here.
            val sent = synchronized(queueLifecycle) {
                if (accepting.get()) {
                    inbox.put(message)
                    true
                } else {
                    false
                }
            }
            if (!sent) {
                queueDepth.decrementAndGet()
                return null
            }
            return try {
                withTimeoutOrNull(timeoutMillis) { deferred.await() }.also { result ->
                    if (result == null) message.abandonWait()
                }
            } catch (cancelled: CancellationException) {
                message.abandonWait()
                throw cancelled
            }
        }

        /** Runs on the worker thread only: reconnects if there is no healthy held client, then
         * runs [operation], retrying once against a fresh connection only for a transport timeout
         * or lost session while the caller is still waiting. A transport failure always discards
         * its client, even after the caller's wait has expired, so the next request can never
         * inherit the session which just failed. The caller is checked again after reconnecting:
         * reconnect itself is a relay round trip and the caller may expire during it; in that case
         * the newly healthy connection is retained for the next request but the abandoned
         * operation is not performed a second time. There is no
         * "does the cached client match this pairing" check here the way the pre-0.3.0 single
         * global session needed -- this [Session] only ever serves the one identity it was
         * created for, so any cached [client] already matches [pairing] by construction. A
         * pairing's relays/secret changing (a re-pair) instead goes through [shutdown] via the
         * registry, which drops this whole [Session] so the next call starts fresh. */
        private suspend fun deliver(
            pairing: Pairing,
            operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
            callerIsWaiting: () -> Boolean,
        ): HeartwoodResult<String> {
            val connected = healthyClient()?.let { HeartwoodResult.Success(it) } ?: reconnect(pairing)

            val active = when (connected) {
                is HeartwoodResult.Success -> connected.value
                is HeartwoodResult.Failure -> return connected
            }

            val result = operation(active)
            val failure = when (result) {
                is HeartwoodResult.Success -> {
                    markHealthy()
                    return result
                }
                is HeartwoodResult.Failure -> result
            }
            val retryable = shouldReconnectAndRetry(failure.error)
            if (retryable) discardClient()
            if (!callerIsWaiting() || !retryable) return failure

            val retried = when (val reconnected = reconnect(pairing)) {
                is HeartwoodResult.Success -> reconnected.value
                is HeartwoodResult.Failure -> return result
            }
            if (!callerIsWaiting()) return failure

            return operation(retried).also { retryResult ->
                when (retryResult) {
                    is HeartwoodResult.Success -> markHealthy()
                    is HeartwoodResult.Failure -> if (shouldReconnectAndRetry(retryResult.error)) {
                        discardClient()
                    }
                }
            }
        }

        /** A relay-backed NIP-46 client is cheap to retain while it is demonstrably active, but
         * after a long idle its WebSocket may have been silently removed by Android, a VPN, or a
         * relay. Rebuilding before the first user operation avoids spending the whole SDK timeout
         * discovering that fact. The keep-alive service naturally keeps this timestamp fresh. */
        private fun healthyClient(): HeartwoodClient? {
            val held = client ?: return null
            val lastHealthy = lastHealthyAtMillis ?: return null
            if (elapsedRealtimeMillis() - lastHealthy <= sessionMaxIdleMillis) return held
            discardClient()
            return null
        }

        private fun markHealthy() {
            lastHealthyAtMillis = elapsedRealtimeMillis()
        }

        /** Runs on the worker thread only. */
        private fun discardClient() {
            client?.disconnect()
            client = null
            lastHealthyAtMillis = null
        }

        private fun shouldReconnectAndRetry(error: HeartwoodError): Boolean = when (error) {
            HeartwoodError.NotConnected, HeartwoodError.Timeout -> true
            HeartwoodError.Busy,
            is HeartwoodError.InvalidInput,
            is HeartwoodError.Protocol,
            -> false
        }

        /** Runs on the worker thread only. */
        private suspend fun reconnect(pairing: Pairing): HeartwoodResult<HeartwoodClient> {
            discardClient()

            val fresh = clientFactory()
            val bunkerUri = BunkerUri(pairing.signerPubkeyHex, pairing.relays, pairing.secret).toUriString()
            return when (val connected = fresh.connect(bunkerUri, pairing.clientSecretKeyHex)) {
                is HeartwoodResult.Success -> {
                    client = fresh
                    markHealthy()
                    HeartwoodResult.Success(fresh)
                }
                is HeartwoodResult.Failure -> {
                    fresh.disconnect()
                    connected
                }
            }
        }
    }

    private sealed interface Message {
        val priorityRank: Int
        val sequence: Long

        data class Call(
            val pairing: Pairing,
            val cacheable: CacheableDecrypt?,
            val cacheableSign: CacheableSign?,
            val priority: HeartwoodRequestPriority,
            override val sequence: Long,
            val operation: suspend (HeartwoodClient) -> HeartwoodResult<String>,
            val deferred: CompletableDeferred<CompletedCall>,
        ) : Message {
            override val priorityRank: Int = priority.rank
            private val state = AtomicInteger(CALL_PENDING)
            val waiterActive = AtomicBoolean(true)

            fun tryStart(): Boolean = state.compareAndSet(CALL_PENDING, CALL_STARTED)

            fun abandonWait() {
                waiterActive.set(false)
                state.compareAndSet(CALL_PENDING, CALL_ABANDONED)
            }
        }

        data class Shutdown(
            override val sequence: Long,
            val deferred: CompletableDeferred<Unit>,
        ) : Message {
            override val priorityRank: Int = Int.MAX_VALUE
        }
    }

    private data class CompletedCall(
        val result: HeartwoodResult<String>,
        val fromCache: Boolean,
    )

    private companion object {
        const val MAX_QUEUED = 3
        const val MAX_NON_INTERACTIVE_QUEUED = MAX_QUEUED - 1
        const val MAX_MAINTENANCE_QUEUED = 1
        const val INITIAL_QUEUE_CAPACITY = 4
        const val SILENT_TIMEOUT_MILLIS = 15_000L
        const val INTENT_TIMEOUT_MILLIS = 20_000L
        const val SESSION_MAX_IDLE_MILLIS = 5 * 60_000L
        const val SIGN_CACHE_TTL_MILLIS = 60_000L
        const val SIGN_LOCK_STRIPES = 256
        const val SHED_LOG_INTERVAL_MILLIS = 60_000L
        const val CALL_PENDING = 0
        const val CALL_STARTED = 1
        const val CALL_ABANDONED = 2
    }
}
