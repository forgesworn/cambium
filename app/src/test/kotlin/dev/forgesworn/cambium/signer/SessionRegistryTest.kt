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
        client: () -> HeartwoodClient = { FakeClient() },
    ) = SessionRegistry(
        clientFactory = { factoryCalls.incrementAndGet(); client() },
        silentTimeoutMillis = silentTimeoutMillis,
    )

    private fun valueOf(outcome: HeartwoodOutcome<String>?): String? =
        (outcome?.result as? HeartwoodResult.Success)?.value

    private fun errorOf(outcome: HeartwoodOutcome<String>?): HeartwoodError? =
        (outcome?.result as? HeartwoodResult.Failure)?.error

    private fun decryptKey(payload: String) =
        CacheableDecrypt(CacheableDecrypt.Method.NIP44, "c3".repeat(32), payload)

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
    fun `the intent path always queues rather than shedding`() = runBlocking {
        val registry = registry()
        try {
            val gate = CompletableDeferred<Unit>()
            val occupying = (1..3).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    registry.trySilent(pairingA) { gate.await(); HeartwoodResult.Success("a") }
                }
            }
            val intent = async(start = CoroutineStart.UNDISPATCHED) {
                registry.withClient(pairingA) { HeartwoodResult.Success("intent") }
            }

            gate.complete(Unit)
            assertEquals("intent", valueOf(intent.await()))
            occupying.forEach { it.await() }
        } finally {
            registry.shutdownAll()
        }
    }

    // -- caller cancellation safety --

    @Test
    fun `a caller abandoning its wait does not cancel work already handed to the worker`() = runBlocking {
        val registry = registry(silentTimeoutMillis = 150L)
        try {
            val gate = CompletableDeferred<Unit>()
            val ran = CompletableDeferred<Unit>()
            val outcome = registry.trySilent(pairingA) {
                gate.await()
                ran.complete(Unit)
                HeartwoodResult.Success("late")
            }
            assertNull(outcome) // the caller gave up at its timeout...

            gate.complete(Unit)
            withTimeout(5_000L) { ran.await() } // ...but the job ran to completion regardless
        } finally {
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
    fun `deterministic decrypt failures are cached, transient failures are retried`() = runBlocking {
        val registry = registry()
        try {
            // A failing operation is invoked twice per submission -- the worker retries once
            // against a fresh connection -- so these assert relative counts (did a second
            // *submission* reach the worker at all), not absolute invocation totals.
            val deterministicKey = decryptKey("legacy undecryptable")
            val deterministicCalls = AtomicInteger(0)
            val deterministic: suspend (HeartwoodClient) -> HeartwoodResult<String> = {
                deterministicCalls.incrementAndGet()
                HeartwoodResult.Failure(HeartwoodError.Protocol("Decryption failed: bad ciphertext"))
            }
            registry.trySilent(pairingA, deterministicKey, deterministic)
            val callsAfterFirst = deterministicCalls.get()
            val cached = registry.trySilent(pairingA, deterministicKey, deterministic)
            assertTrue(cached is HeartwoodOutcome.Cached)
            assertEquals(callsAfterFirst, deterministicCalls.get())

            val transientKey = decryptKey("policy blocked")
            val transientCalls = AtomicInteger(0)
            val transient: suspend (HeartwoodClient) -> HeartwoodResult<String> = {
                transientCalls.incrementAndGet()
                HeartwoodResult.Failure(HeartwoodError.Protocol("unauthorised"))
            }
            registry.trySilent(pairingA, transientKey, transient)
            val transientAfterFirst = transientCalls.get()
            val retried = registry.trySilent(pairingA, transientKey, transient)
            assertTrue(retried is HeartwoodOutcome.Fresh)
            assertTrue(transientCalls.get() > transientAfterFirst)
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
