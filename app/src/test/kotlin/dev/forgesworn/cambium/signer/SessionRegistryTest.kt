package dev.forgesworn.cambium.signer

import dev.forgesworn.cambium.pairing.Pairing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [SessionRegistry]'s invariants against a fake [HeartwoodClient] -- the reason the
 * registry takes a client factory at all. Concurrency here is gate-driven ([CompletableDeferred]
 * held open until the test releases it), never sleep-driven, so nothing is timing-sensitive:
 * `async(start = UNDISPATCHED)` runs each caller synchronously up to its first suspension, which
 * is *after* the queue slot is reserved, so queue-depth assertions are deterministic.
 */
class SessionRegistryTest {

    private val pairingA = pairing("a1".repeat(32))
    private val pairingB = pairing("b2".repeat(32))

    private fun pairing(pubkeyHex: String) = Pairing(
        signerPubkeyHex = pubkeyHex,
        relays = listOf("wss://relay.example"),
        secret = null,
        clientSecretKeyHex = "d".repeat(64),
        clientPublicKeyHex = "e".repeat(64),
    )

    private open class FakeClient : HeartwoodClient {
        override suspend fun connect(bunkerUri: String, clientSecretKeyHex: String): HeartwoodResult<String> =
            HeartwoodResult.Success("f".repeat(64))
        override suspend fun getPublicKey(): HeartwoodResult<String> = HeartwoodResult.Success("pubkey")
        override suspend fun signEvent(eventJson: String): HeartwoodResult<String> = HeartwoodResult.Success("signed")
        override suspend fun nip04Encrypt(pubkeyHex: String, content: String): HeartwoodResult<String> = HeartwoodResult.Success("enc")
        override suspend fun nip04Decrypt(pubkeyHex: String, content: String): HeartwoodResult<String> = HeartwoodResult.Success("dec")
        override suspend fun nip44Encrypt(pubkeyHex: String, content: String): HeartwoodResult<String> = HeartwoodResult.Success("enc")
        override suspend fun nip44Decrypt(pubkeyHex: String, content: String): HeartwoodResult<String> = HeartwoodResult.Success("dec")
        override fun disconnect() = Unit
    }

    private fun registry(
        factoryCalls: AtomicInteger = AtomicInteger(0),
        silentTimeoutMillis: Long = 15_000L,
        intentTimeoutMillis: Long = 20_000L,
        sessionMaxIdleMillis: Long = 5 * 60_000L,
        signCacheTtlMillis: Long = 60_000L,
        authCircuitBreakMillis: Long = 60_000L,
        elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        logWarning: (String) -> Unit = {},
        client: () -> HeartwoodClient = { FakeClient() },
    ) = SessionRegistry(
        clientFactory = { factoryCalls.incrementAndGet(); client() },
        logWarning = logWarning,
        silentTimeoutMillis = silentTimeoutMillis,
        intentTimeoutMillis = intentTimeoutMillis,
        sessionMaxIdleMillis = sessionMaxIdleMillis,
        signCacheTtlMillis = signCacheTtlMillis,
        authCircuitBreakMillis = authCircuitBreakMillis,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
    )

    private fun valueOf(outcome: HeartwoodOutcome<String>?): String? =
        (outcome?.result as? HeartwoodResult.Success)?.value

    private fun errorOf(outcome: HeartwoodOutcome<String>?): HeartwoodError? =
        (outcome?.result as? HeartwoodResult.Failure)?.error

    private fun decryptKey(payload: String) =
        CacheableDecrypt(CacheableDecrypt.Method.NIP44, "c3".repeat(32), payload)

    private fun signKey(payload: String) = CacheableSign(payload)

    // -- admission control --

    @Test
    fun `a silent burst beyond the queue depth is shed immediately, and the queued work still completes`() = runBlocking {
        val registry = registry()
        try {
            val gate = CompletableDeferred<Unit>()
            val occupying = (1..3).map { i ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    registry.trySilent(pairingA) { gate.await(); HeartwoodResult.Success("done-$i") }
                }
            }

            assertNull(registry.trySilent(pairingA) { HeartwoodResult.Success("shed") })

            gate.complete(Unit)
            occupying.forEachIndexed { index, deferred ->
                val outcome = deferred.await()
                assertTrue(outcome is HeartwoodOutcome.Fresh)
                assertEquals("done-${index + 1}", valueOf(outcome))
            }
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `a full queue on one identity never sheds a request against another`() = runBlocking {
        val registry = registry()
        try {
            val gate = CompletableDeferred<Unit>()
            val occupying = (1..3).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    registry.trySilent(pairingA) { gate.await(); HeartwoodResult.Success("a") }
                }
            }
            assertNull(registry.trySilent(pairingA) { HeartwoodResult.Success("shed") })

            val other = registry.trySilent(pairingB) { HeartwoodResult.Success("b") }
            assertTrue(other is HeartwoodOutcome.Fresh)
            assertEquals("b", valueOf(other))

            gate.complete(Unit)
            occupying.forEach { it.await() }
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `the intent path is bounded by the same admission limit`() = runBlocking {
        val registry = registry()
        try {
            val gate = CompletableDeferred<Unit>()
            val occupying = (1..3).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    registry.trySilent(pairingA) { gate.await(); HeartwoodResult.Success("a") }
                }
            }
            val intent = registry.withClient(pairingA) { HeartwoodResult.Success("intent") }

            assertEquals(HeartwoodError.Busy, errorOf(intent))
            gate.complete(Unit)
            occupying.forEach { it.await() }
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `interactive work keeps a reserved slot and runs before queued background work`() = runBlocking {
        val registry = registry()
        try {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                registry.trySilent(pairingA, priority = HeartwoodRequestPriority.BACKGROUND) {
                    started.complete(Unit)
                    gate.await()
                    order += "active-background"
                    HeartwoodResult.Success("active")
                }
            }
            started.await()
            val queuedBackground = async(start = CoroutineStart.UNDISPATCHED) {
                registry.trySilent(pairingA, priority = HeartwoodRequestPriority.BACKGROUND) {
                    order += "queued-background"
                    HeartwoodResult.Success("background")
                }
            }

            assertNull(registry.trySilent(pairingA, priority = HeartwoodRequestPriority.AUTH) {
                HeartwoodResult.Success("shed-auth")
            })
            val interactive = async(start = CoroutineStart.UNDISPATCHED) {
                registry.trySilent(pairingA, priority = HeartwoodRequestPriority.INTERACTIVE) {
                    order += "interactive"
                    HeartwoodResult.Success("interactive")
                }
            }

            gate.complete(Unit)
            assertEquals("active", valueOf(active.await()))
            assertEquals("interactive", valueOf(interactive.await()))
            assertEquals("background", valueOf(queuedBackground.await()))
            assertEquals(listOf("active-background", "interactive", "queued-background"), order)
        } finally {
            registry.shutdownAll()
        }
    }

    // -- caller cancellation safety --

    @Test
    fun `an active call can finish and populate cache after its caller times out`() = runBlocking {
        val registry = registry(silentTimeoutMillis = 150L)
        try {
            val gate = CompletableDeferred<Unit>()
            val ran = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val key = decryptKey("late ciphertext")
            val outcome = registry.trySilent(pairingA, key) {
                calls.incrementAndGet()
                gate.await()
                ran.complete(Unit)
                HeartwoodResult.Success("late")
            }
            assertNull(outcome) // the caller gave up at its timeout...

            gate.complete(Unit)
            withTimeout(5_000L) { ran.await() } // ...but the job ran to completion regardless
            registry.withClient(pairingA) { HeartwoodResult.Success("barrier") }

            val cached = registry.trySilent(pairingA, key) {
                calls.incrementAndGet()
                HeartwoodResult.Success("must not run")
            }
            assertTrue(cached is HeartwoodOutcome.Cached)
            assertEquals("late", valueOf(cached))
            assertEquals(1, calls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `a queued call is skipped when its caller times out before it starts`() = runBlocking {
        val registry = registry(silentTimeoutMillis = 150L)
        try {
            val gate = CompletableDeferred<Unit>()
            val blocker = async(start = CoroutineStart.UNDISPATCHED) {
                registry.withClient(pairingA) {
                    gate.await()
                    HeartwoodResult.Success("blocker")
                }
            }
            val queuedCalls = AtomicInteger(0)
            assertNull(registry.trySilent(pairingA) {
                queuedCalls.incrementAndGet()
                HeartwoodResult.Success("stale")
            })

            gate.complete(Unit)
            assertEquals("blocker", valueOf(blocker.await()))
            registry.withClient(pairingA) { HeartwoodResult.Success("barrier") }
            assertEquals(0, queuedCalls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `an expired caller is not retried after reconnect finishes`() = runBlocking {
        val connects = AtomicInteger(0)
        val retryConnectStarted = CompletableDeferred<Unit>()
        val releaseRetryConnect = CompletableDeferred<Unit>()
        val operationCalls = AtomicInteger(0)
        val registry = registry(
            silentTimeoutMillis = 150L,
            client = {
                object : FakeClient() {
                    override suspend fun connect(
                        bunkerUri: String,
                        clientSecretKeyHex: String,
                    ): HeartwoodResult<String> {
                        if (connects.incrementAndGet() == 2) {
                            retryConnectStarted.complete(Unit)
                            releaseRetryConnect.await()
                        }
                        return HeartwoodResult.Success("f".repeat(64))
                    }
                }
            },
        )
        try {
            val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                registry.trySilent(pairingA) {
                    operationCalls.incrementAndGet()
                    HeartwoodResult.Failure(HeartwoodError.Timeout)
                }
            }

            retryConnectStarted.await()
            assertNull(outcome.await())
            releaseRetryConnect.complete(Unit)
            registry.withClient(pairingA) { HeartwoodResult.Success("barrier") }

            assertEquals(1, operationCalls.get())
            assertEquals(2, connects.get())
        } finally {
            releaseRetryConnect.complete(Unit)
            registry.shutdownAll()
        }
    }

    // -- decrypt cache --

    @Test
    fun `a repeated decrypt answers from cache without reaching the worker again`() = runBlocking {
        val registry = registry()
        try {
            val calls = AtomicInteger(0)
            val key = decryptKey("ciphertext")

            val first = registry.trySilent(pairingA, key) { calls.incrementAndGet(); HeartwoodResult.Success("plain") }
            assertTrue(first is HeartwoodOutcome.Fresh)
            assertEquals("plain", valueOf(first))

            val second = registry.trySilent(pairingA, key) { calls.incrementAndGet(); HeartwoodResult.Success("never") }
            assertTrue(second is HeartwoodOutcome.Cached)
            assertEquals("plain", valueOf(second))
            assertEquals(1, calls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `identical decrypts queued together share the first completed result`() = runBlocking {
        val registry = registry()
        try {
            val key = decryptKey("same in-flight ciphertext")
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                registry.trySilent(pairingA, key, HeartwoodRequestPriority.BACKGROUND) {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    gate.await()
                    HeartwoodResult.Success("shared plain")
                }
            }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                registry.trySilent(pairingA, key, HeartwoodRequestPriority.BACKGROUND) {
                    calls.incrementAndGet()
                    HeartwoodResult.Success("must not run")
                }
            }

            gate.complete(Unit)
            assertTrue(first.await() is HeartwoodOutcome.Fresh)
            assertTrue(second.await() is HeartwoodOutcome.Cached)
            assertEquals(1, calls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `identical auth signatures in flight share one Heartwood call`() = runBlocking {
        val registry = registry()
        try {
            val key = signKey("same unsigned kind 22242 event")
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val calls = AtomicInteger(0)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                registry.trySilent(pairingA, priority = HeartwoodRequestPriority.AUTH, cacheableSign = key) {
                    calls.incrementAndGet()
                    started.complete(Unit)
                    gate.await()
                    HeartwoodResult.Success("signed auth")
                }
            }
            started.await()
            val duplicates = (1..5).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    registry.trySilent(pairingA, priority = HeartwoodRequestPriority.AUTH, cacheableSign = key) {
                        calls.incrementAndGet()
                        HeartwoodResult.Success("must not run")
                    }
                }
            }

            gate.complete(Unit)
            assertTrue(first.await() is HeartwoodOutcome.Fresh)
            duplicates.forEach { duplicate ->
                assertTrue(duplicate.await() is HeartwoodOutcome.Cached)
            }
            assertEquals(1, calls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `an auth signature cache entry expires and is signed again`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000L)
        val registry = registry(
            signCacheTtlMillis = 5_000L,
            elapsedRealtimeMillis = now::get,
        )
        try {
            val key = signKey("expiring unsigned kind 22242 event")
            val calls = AtomicInteger(0)
            val operation: suspend (HeartwoodClient) -> HeartwoodResult<String> = {
                HeartwoodResult.Success("signed-${calls.incrementAndGet()}")
            }

            assertEquals("signed-1", valueOf(registry.trySilent(pairingA, cacheableSign = key, operation = operation)))
            now.addAndGet(5_001L)
            assertEquals("signed-2", valueOf(registry.trySilent(pairingA, cacheableSign = key, operation = operation)))
            assertEquals(2, calls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `an auth transport timeout is not retried and opens a per-identity circuit`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000L)
        val warnings = mutableListOf<String>()
        val factoryCalls = AtomicInteger(0)
        val registry = registry(
            factoryCalls = factoryCalls,
            authCircuitBreakMillis = 5_000L,
            elapsedRealtimeMillis = now::get,
            logWarning = { warnings += it },
        )
        try {
            val authCalls = AtomicInteger(0)
            val first = registry.trySilent(
                pairingA,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = signKey("timing out auth"),
            ) {
                authCalls.incrementAndGet()
                HeartwoodResult.Failure(HeartwoodError.Timeout)
            }

            assertEquals(HeartwoodError.Timeout, errorOf(first))
            assertEquals(1, authCalls.get()) // AUTH never consumes a second SDK timeout by retrying
            assertEquals(1, factoryCalls.get())
            assertEquals(1, warnings.size)

            val duringCircuit = registry.trySilent(
                pairingA,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = signKey("different auth during cooldown"),
            ) {
                authCalls.incrementAndGet()
                HeartwoodResult.Success("must not run")
            }
            assertNull(duringCircuit)
            assertEquals(1, authCalls.get())

            val interactive = registry.trySilent(pairingA) { HeartwoodResult.Success("foreground") }
            assertEquals("foreground", valueOf(interactive))
            assertEquals(2, factoryCalls.get()) // user work can rebuild the discarded connection

            now.addAndGet(5_001L)
            val afterCooldown = registry.trySilent(
                pairingA,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = signKey("auth after cooldown"),
            ) {
                authCalls.incrementAndGet()
                HeartwoodResult.Success("signed after cooldown")
            }
            assertEquals("signed after cooldown", valueOf(afterCooldown))
            assertEquals(2, authCalls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `cached auth still answers while the auth circuit is open`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000L)
        val registry = registry(
            authCircuitBreakMillis = 5_000L,
            elapsedRealtimeMillis = now::get,
        )
        try {
            val cachedKey = signKey("previously signed auth")
            assertEquals(
                "cached signature",
                valueOf(
                    registry.trySilent(
                        pairingA,
                        priority = HeartwoodRequestPriority.AUTH,
                        cacheableSign = cachedKey,
                    ) { HeartwoodResult.Success("cached signature") }
                ),
            )

            registry.trySilent(
                pairingA,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = signKey("auth that opens circuit"),
            ) { HeartwoodResult.Failure(HeartwoodError.NotConnected) }

            val calls = AtomicInteger(0)
            val cached = registry.trySilent(
                pairingA,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = cachedKey,
            ) {
                calls.incrementAndGet()
                HeartwoodResult.Success("must not run")
            }
            assertTrue(cached is HeartwoodOutcome.Cached)
            assertEquals("cached signature", valueOf(cached))
            assertEquals(0, calls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `an auth circuit on one identity does not suppress another identity`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000L)
        val registry = registry(
            authCircuitBreakMillis = 5_000L,
            elapsedRealtimeMillis = now::get,
        )
        try {
            registry.trySilent(
                pairingA,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = signKey("identity A auth"),
            ) { HeartwoodResult.Failure(HeartwoodError.Timeout) }

            val other = registry.trySilent(
                pairingB,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = signKey("identity B auth"),
            ) { HeartwoodResult.Success("identity B signed") }
            assertEquals("identity B signed", valueOf(other))
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `an auth policy refusal does not open the transport circuit`() = runBlocking {
        val registry = registry()
        try {
            val refused = registry.trySilent(
                pairingA,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = signKey("refused auth"),
            ) { HeartwoodResult.Failure(HeartwoodError.Protocol("unauthorised")) }
            assertEquals(HeartwoodError.Protocol("unauthorised"), errorOf(refused))

            val next = registry.trySilent(
                pairingA,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = signKey("next auth"),
            ) { HeartwoodResult.Success("next signed") }
            assertEquals("next signed", valueOf(next))
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `an auth connection timeout opens the circuit before the operation can run`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000L)
        val factoryCalls = AtomicInteger(0)
        val registry = registry(
            factoryCalls = factoryCalls,
            authCircuitBreakMillis = 5_000L,
            elapsedRealtimeMillis = now::get,
            client = {
                object : FakeClient() {
                    override suspend fun connect(
                        bunkerUri: String,
                        clientSecretKeyHex: String,
                    ): HeartwoodResult<String> = HeartwoodResult.Failure(HeartwoodError.Timeout)
                }
            },
        )
        try {
            val operationCalls = AtomicInteger(0)
            val failed = registry.trySilent(
                pairingA,
                priority = HeartwoodRequestPriority.AUTH,
                cacheableSign = signKey("connect timeout"),
            ) {
                operationCalls.incrementAndGet()
                HeartwoodResult.Success("unreachable")
            }
            assertEquals(HeartwoodError.Timeout, errorOf(failed))
            assertEquals(0, operationCalls.get())
            assertEquals(1, factoryCalls.get())

            assertNull(
                registry.trySilent(
                    pairingA,
                    priority = HeartwoodRequestPriority.AUTH,
                    cacheableSign = signKey("blocked by connect timeout"),
                ) { HeartwoodResult.Success("must not connect") }
            )
            assertEquals(1, factoryCalls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `only one distinct auth is admitted while interactive work keeps its reserved slot`() = runBlocking {
        val registry = registry()
        try {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val activeAuth = async(start = CoroutineStart.UNDISPATCHED) {
                registry.trySilent(
                    pairingA,
                    priority = HeartwoodRequestPriority.AUTH,
                    cacheableSign = signKey("active auth"),
                ) {
                    started.complete(Unit)
                    gate.await()
                    order += "auth"
                    HeartwoodResult.Success("signed auth")
                }
            }
            started.await()

            assertNull(
                registry.trySilent(
                    pairingA,
                    priority = HeartwoodRequestPriority.AUTH,
                    cacheableSign = signKey("different auth"),
                ) { HeartwoodResult.Success("must not run") }
            )
            val interactive = async(start = CoroutineStart.UNDISPATCHED) {
                registry.trySilent(pairingA) {
                    order += "interactive"
                    HeartwoodResult.Success("foreground")
                }
            }

            gate.complete(Unit)
            assertEquals("signed auth", valueOf(activeAuth.await()))
            assertEquals("foreground", valueOf(interactive.await()))
            assertEquals(listOf("auth", "interactive"), order)
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `the cache is partitioned per identity, never shared across pairings`() = runBlocking {
        val registry = registry()
        try {
            val key = decryptKey("same ciphertext, same counterparty")
            registry.trySilent(pairingA, key) { HeartwoodResult.Success("a-plain") }

            val calls = AtomicInteger(0)
            val other = registry.trySilent(pairingB, key) { calls.incrementAndGet(); HeartwoodResult.Success("b-plain") }
            assertTrue(other is HeartwoodOutcome.Fresh)
            assertEquals("b-plain", valueOf(other))
            assertEquals(1, calls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `deterministic and policy failures are not retried, while timeouts are`() = runBlocking {
        val registry = registry()
        try {
            val deterministicKey = decryptKey("legacy undecryptable")
            val deterministicCalls = AtomicInteger(0)
            val deterministic: suspend (HeartwoodClient) -> HeartwoodResult<String> = {
                deterministicCalls.incrementAndGet()
                HeartwoodResult.Failure(HeartwoodError.Protocol("Decryption failed: bad ciphertext"))
            }
            registry.trySilent(pairingA, deterministicKey, operation = deterministic)
            val callsAfterFirst = deterministicCalls.get()
            val cached = registry.trySilent(pairingA, deterministicKey, operation = deterministic)
            assertTrue(cached is HeartwoodOutcome.Cached)
            assertEquals(1, callsAfterFirst)
            assertEquals(callsAfterFirst, deterministicCalls.get())

            val transientKey = decryptKey("policy blocked")
            val transientCalls = AtomicInteger(0)
            val transient: suspend (HeartwoodClient) -> HeartwoodResult<String> = {
                transientCalls.incrementAndGet()
                HeartwoodResult.Failure(HeartwoodError.Protocol("unauthorised"))
            }
            registry.trySilent(pairingA, transientKey, operation = transient)
            val transientAfterFirst = transientCalls.get()
            val secondRefusal = registry.trySilent(pairingA, transientKey, operation = transient)
            assertTrue(secondRefusal is HeartwoodOutcome.Fresh)
            assertEquals(1, transientAfterFirst)
            assertEquals(2, transientCalls.get())

            val timeoutCalls = AtomicInteger(0)
            registry.trySilent(pairingA) {
                timeoutCalls.incrementAndGet()
                HeartwoodResult.Failure(HeartwoodError.Timeout)
            }
            assertEquals(2, timeoutCalls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    // -- session lifecycle --

    @Test
    fun `a concurrent first-time burst constructs exactly one client`() = runBlocking {
        val factoryCalls = AtomicInteger(0)
        val registry = registry(factoryCalls)
        try {
            val gate = CompletableDeferred<Unit>()
            val burst = (1..3).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    registry.trySilent(pairingA) { gate.await(); HeartwoodResult.Success("ok") }
                }
            }
            gate.complete(Unit)
            burst.forEach { assertEquals("ok", valueOf(it.await())) }
            assertEquals(1, factoryCalls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `shutdown drops the identity's session and cache, so the next call starts fresh`() = runBlocking {
        val factoryCalls = AtomicInteger(0)
        val registry = registry(factoryCalls)
        try {
            val key = decryptKey("cached before shutdown")
            registry.trySilent(pairingA, key) { HeartwoodResult.Success("old plain") }
            assertEquals(1, factoryCalls.get())

            registry.shutdown(pairingA.signerPubkeyHex)

            val calls = AtomicInteger(0)
            val after = registry.trySilent(pairingA, key) { calls.incrementAndGet(); HeartwoodResult.Success("fresh plain") }
            assertTrue(after is HeartwoodOutcome.Fresh) // the cache went with the session
            assertEquals("fresh plain", valueOf(after))
            assertEquals(1, calls.get())
            assertEquals(2, factoryCalls.get()) // and a fresh client was constructed
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `a transport timeout invalidates the failed client before the next request`() = runBlocking {
        val factoryCalls = AtomicInteger(0)
        val registry = registry(factoryCalls)
        try {
            val operationCalls = AtomicInteger(0)
            val first = registry.trySilent(pairingA) {
                operationCalls.incrementAndGet()
                HeartwoodResult.Failure(HeartwoodError.Timeout)
            }
            assertEquals(HeartwoodError.Timeout, errorOf(first))
            assertEquals(2, operationCalls.get()) // one retry while this caller is still waiting
            assertEquals(2, factoryCalls.get())

            val next = registry.trySilent(pairingA) { HeartwoodResult.Success("fresh") }
            assertEquals("fresh", valueOf(next))
            assertEquals(3, factoryCalls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `an idle session reconnects before running the next operation`() = runBlocking {
        val now = java.util.concurrent.atomic.AtomicLong(1_000L)
        val factoryCalls = AtomicInteger(0)
        val registry = registry(
            factoryCalls = factoryCalls,
            sessionMaxIdleMillis = 5_000L,
            elapsedRealtimeMillis = now::get,
        )
        try {
            assertEquals("first", valueOf(registry.trySilent(pairingA) { HeartwoodResult.Success("first") }))
            assertEquals(1, factoryCalls.get())

            now.addAndGet(4_999L)
            assertEquals("warm", valueOf(registry.trySilent(pairingA) { HeartwoodResult.Success("warm") }))
            assertEquals(1, factoryCalls.get())

            now.addAndGet(5_001L)
            assertEquals("fresh", valueOf(registry.trySilent(pairingA) { HeartwoodResult.Success("fresh") }))
            assertEquals(2, factoryCalls.get())
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `shutdownAll drops every identity at once`() = runBlocking {
        val registry = registry()
        try {
            val keyA = decryptKey("for A")
            val keyB = decryptKey("for B")
            registry.trySilent(pairingA, keyA) { HeartwoodResult.Success("a") }
            registry.trySilent(pairingB, keyB) { HeartwoodResult.Success("b") }

            registry.shutdownAll()

            assertTrue(registry.trySilent(pairingA, keyA) { HeartwoodResult.Success("a2") } is HeartwoodOutcome.Fresh)
            assertTrue(registry.trySilent(pairingB, keyB) { HeartwoodResult.Success("b2") } is HeartwoodOutcome.Fresh)
        } finally {
            registry.shutdownAll()
        }
    }

    @Test
    fun `a failed connection surfaces as the call's failure`() = runBlocking {
        val registry = registry(client = {
            object : FakeClient() {
                override suspend fun connect(bunkerUri: String, clientSecretKeyHex: String): HeartwoodResult<String> =
                    HeartwoodResult.Failure(HeartwoodError.Timeout)
            }
        })
        try {
            val operationRan = AtomicInteger(0)
            val outcome = registry.trySilent(pairingA) { operationRan.incrementAndGet(); HeartwoodResult.Success("unreachable") }
            assertTrue(outcome is HeartwoodOutcome.Fresh)
            assertEquals(HeartwoodError.Timeout, errorOf(outcome))
            assertEquals(0, operationRan.get()) // never ran: there was no connection to run against
        } finally {
            registry.shutdownAll()
        }
    }
}
