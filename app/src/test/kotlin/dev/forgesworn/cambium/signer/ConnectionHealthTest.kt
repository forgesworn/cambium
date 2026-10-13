package dev.forgesworn.cambium.signer

import dev.forgesworn.cambium.pairing.Pairing
import kotlinx.coroutines.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ConnectionHealthTest {
    private val pairing = Pairing("a".repeat(64), listOf("wss://old.example"), null, "b".repeat(64), "c".repeat(64))
    private open class Fake : HeartwoodClient {
        override suspend fun connect(bunkerUri: String, clientSecretKeyHex: String) = HeartwoodResult.Success("a".repeat(64))
        override suspend fun getPublicKey() = HeartwoodResult.Success("key")
        override suspend fun ping(): HeartwoodResult<String> = HeartwoodResult.Success("pong")
        override suspend fun signEvent(eventJson: String) = HeartwoodResult.Success("signed")
        override suspend fun nip04Encrypt(pubkeyHex: String, content: String) = HeartwoodResult.Success("encrypted")
        override suspend fun nip04Decrypt(pubkeyHex: String, content: String) = HeartwoodResult.Success("decrypted")
        override suspend fun nip44Encrypt(pubkeyHex: String, content: String) = HeartwoodResult.Success("encrypted")
        override suspend fun nip44Decrypt(pubkeyHex: String, content: String) = HeartwoodResult.Success("decrypted")
        override fun disconnect() = Unit
    }

    @Test fun `explicit check makes fresh contact and stores safe failure without losing last success`() = runBlocking {
        var fail = false
        var clock = 100L
        val registry = SessionRegistry(clientFactory = { object : Fake() {
            override suspend fun ping(): HeartwoodResult<String> = if (fail)
                HeartwoodResult.Failure(HeartwoodError.Protocol("denied secret payload must not appear"))
                else HeartwoodResult.Success("pong")
        } }, wallClockMillis = { clock })
        try {
            registry.testConnection(pairing)
            assertEquals(ConnectionStatus.CONNECTED, registry.health.value[pairing.signerPubkeyHex]?.status)
            assertEquals(100L, registry.health.value[pairing.signerPubkeyHex]?.lastSuccessfulContactMillis)
            clock = 200L
            fail = true
            registry.testConnection(pairing)
            val health = registry.health.value.getValue(pairing.signerPubkeyHex)
            assertEquals(ConnectionFailure.REFUSED, health.errorCategory)
            assertEquals(ConnectionStatus.CONNECTED, health.status)
            assertEquals(100L, health.lastSuccessfulContactMillis)
            assertFalse(health.toString().contains("secret payload"))
        } finally { registry.shutdownAll() }
        assertTrue(registry.health.value.isEmpty())
    }

    @Test fun `reconnect waits for active work and closes it before creating replacement`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val clients = AtomicInteger()
        val registry = SessionRegistry(clientFactory = {
            val id = clients.incrementAndGet()
            events += "create$id"
            object : Fake() { override fun disconnect() { events += "close$id" } }
        })
        try {
            val active = async { registry.withClient(pairing) { started.complete(Unit); release.await(); events += "finished"; HeartwoodResult.Success("signed") } }
            started.await()
            val reconnect = async(start = CoroutineStart.UNDISPATCHED) { registry.reconnect(pairing) }
            assertEquals(1, clients.get())
            release.complete(Unit)
            active.await()
            assertIs<HeartwoodResult.Success<String>>(reconnect.await().result)
            assertTrue(events.indexOf("finished") < events.indexOf("close1"))
            assertTrue(events.indexOf("close1") < events.indexOf("create2"))
        } finally { registry.shutdownAll() }
    }

    @Test fun `validated signer relay update persists then routes future work to new transport`() = runBlocking {
        val uris = mutableListOf<String>()
        var persisted: Pairing? = null
        val switches = AtomicInteger()
        val registry = SessionRegistry(clientFactory = { object : Fake() {
            override suspend fun connect(bunkerUri: String, clientSecretKeyHex: String): HeartwoodResult.Success<String> {
                uris += bunkerUri
                return HeartwoodResult.Success("key")
            }
            override suspend fun switchRelays(): RelaySwitchResult {
                switches.incrementAndGet()
                return RelaySwitchResult.Updated(listOf("wss://new.example"))
            }
        } }, persistRelayUpdate = { expected, relays -> assertEquals(pairing, expected); persisted = expected.copy(relays = relays); true })
        try {
            registry.testConnection(pairing)
            assertEquals(listOf("wss://new.example"), persisted?.relays)
            assertEquals(2, uris.size)
            assertTrue(uris.last().contains("new.example"))
            assertEquals(RelayUpdateStatus.UPDATED, registry.health.value.getValue(pairing.signerPubkeyHex).relayUpdate)
            registry.reconnect(pairing) // an already-queued snapshot still uses the accepted update
            assertTrue(uris.last().contains("new.example"))
            assertEquals(1, switches.get())
        } finally { registry.shutdownAll() }
    }

    @Test fun `stale persistence refusal prevents work and unsupported signer remains usable`() = runBlocking {
        val operations = AtomicInteger()
        val switches = AtomicInteger()
        var proposal: RelaySwitchResult = RelaySwitchResult.Updated(listOf("wss://new.example"))
        val registry = SessionRegistry(clientFactory = { object : Fake() {
            override suspend fun switchRelays(): RelaySwitchResult { switches.incrementAndGet(); return proposal }
        } }, persistRelayUpdate = { _, _ -> false })
        try {
            val rejected = registry.withClient(pairing) { operations.incrementAndGet(); it.signEvent("event") }
            assertIs<HeartwoodResult.Failure>(rejected.result)
            assertEquals(0, operations.get())
            registry.shutdownAll()
            proposal = RelaySwitchResult.Unsupported
            assertIs<HeartwoodResult.Success<String>>(registry.withClient(pairing) { it.signEvent("event") }.result)
            registry.reconnect(pairing)
            assertEquals(2, switches.get()) // one attempt in each distinct session, none on reconnect
            assertEquals(RelayUpdateStatus.UNSUPPORTED, registry.health.value.getValue(pairing.signerPubkeyHex).relayUpdate)
        } finally { registry.shutdownAll() }
    }

    @Test fun `pairing contact after shutdown records observation without a worker and local reads cannot refresh it`() = runBlocking {
        var clock = 100L
        val clients = AtomicInteger()
        val registry = SessionRegistry(clientFactory = { clients.incrementAndGet(); Fake() }, wallClockMillis = { clock })
        try {
            registry.testConnection(pairing)
            registry.shutdown(pairing.signerPubkeyHex)
            assertTrue(registry.health.value.isEmpty())
            clock = 200L
            registry.recordPairingContact(pairing.signerPubkeyHex)
            assertEquals(1, clients.get()) // Recording contact did not create a replacement worker/client.
            val observed = registry.health.value.getValue(pairing.signerPubkeyHex)
            assertEquals(ConnectionStatus.CONNECTED, observed.status)
            assertEquals(200L, observed.lastSuccessfulContactMillis)
            assertNull(observed.errorCategory)
            clock = 300L
            repeat(3) { assertEquals(observed, registry.health.value.getValue(pairing.signerPubkeyHex)) }
            assertEquals(1, clients.get())

            // The same bounded store serves handshake observations even without any session.
            repeat(80) { registry.recordPairingContact(it.toString(16).padStart(64, '0')) }
            assertEquals(64, registry.health.value.size)
            assertEquals(1, clients.get())
        } finally { registry.shutdownAll() }
    }

    @Test fun `cache hits preserve last contact and recording a handshake preserves relay outcome`() = runBlocking {
        var clock = 100L
        val registry = SessionRegistry(clientFactory = { object : Fake() {
            override suspend fun switchRelays() = RelaySwitchResult.Unsupported
        } }, wallClockMillis = { clock })
        val key = CacheableDecrypt(CacheableDecrypt.Method.NIP44, "counterparty", "ciphertext")
        try {
            registry.withClient(pairing, cacheable = key) { it.nip44Decrypt("counterparty", "ciphertext") }
            clock = 200L
            assertIs<HeartwoodOutcome.Cached<String>>(registry.withClient(pairing, cacheable = key) {
                error("A cached decrypt must not contact the signer")
            })
            assertEquals(100L, registry.health.value.getValue(pairing.signerPubkeyHex).lastSuccessfulContactMillis)
            registry.recordPairingContact(pairing.signerPubkeyHex)
            val observed = registry.health.value.getValue(pairing.signerPubkeyHex)
            assertEquals(200L, observed.lastSuccessfulContactMillis)
            assertEquals(RelayUpdateStatus.UNSUPPORTED, observed.relayUpdate)
        } finally { registry.shutdownAll() }
    }

    @Test fun `draining removed worker cannot overwrite or erase a newer pairing contact`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var clock = 100L
        val registry = SessionRegistry(clientFactory = { Fake() }, wallClockMillis = { clock })
        try {
            val active = async {
                registry.withClient(pairing) {
                    started.complete(Unit)
                    release.await()
                    HeartwoodResult.Success("old result")
                }
            }
            started.await()
            val shutdown = async(start = CoroutineStart.UNDISPATCHED) { registry.shutdown(pairing.signerPubkeyHex) }
            clock = 200L
            registry.recordPairingContact(pairing.signerPubkeyHex)
            clock = 300L
            release.complete(Unit)
            active.await()
            shutdown.await()
            assertEquals(200L, registry.health.value.getValue(pairing.signerPubkeyHex).lastSuccessfulContactMillis)
        } finally { release.complete(Unit); registry.shutdownAll() }
    }


    @Test fun `cancelled reset still closes every removed worker and cannot resurrect health`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val startedA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>()
        val closed = AtomicInteger()
        val registry = SessionRegistry(clientFactory = { object : Fake() {
            override fun disconnect() { closed.incrementAndGet() }
        } })
        try {
            val a = async { registry.withClient(pairing) { startedA.complete(Unit); release.await(); HeartwoodResult.Success("a") } }
            val b = async { registry.withClient(pairing.copy(signerPubkeyHex = "d".repeat(64))) {
                startedB.complete(Unit); release.await(); HeartwoodResult.Success("b")
            } }
            startedA.await()
            startedB.await()
            val reset = async(start = CoroutineStart.UNDISPATCHED) { registry.shutdownAll() }
            reset.cancel()
            release.complete(Unit)
            a.await()
            b.await()
            reset.join()
            assertEquals(2, closed.get())
            assertTrue(registry.health.value.isEmpty())
        } finally { release.complete(Unit); registry.shutdownAll() }
    }


    @Test fun `throwing relay persistence closes fresh client without forwarding or moving transport`() = runBlocking {
        val connectedUris = mutableListOf<String>()
        val disconnected = AtomicInteger()
        val forwarded = AtomicInteger()
        val registry = SessionRegistry(clientFactory = { object : Fake() {
            override suspend fun connect(bunkerUri: String, clientSecretKeyHex: String): HeartwoodResult.Success<String> {
                connectedUris += bunkerUri
                return HeartwoodResult.Success("key")
            }
            override suspend fun switchRelays() = RelaySwitchResult.Updated(listOf("wss://new.example"))
            override fun disconnect() { disconnected.incrementAndGet() }
        } }, persistRelayUpdate = { _, _ -> throw IllegalStateException("private storage failure") })
        try {
            val result = registry.withClient(pairing) {
                forwarded.incrementAndGet()
                it.signEvent("event")
            }.result
            assertEquals(HeartwoodResult.Failure(HeartwoodError.NotConnected), result)
            assertEquals(1, disconnected.get())
            assertEquals(0, forwarded.get())
            assertEquals(1, connectedUris.size)
            assertTrue(connectedUris.single().contains("old.example"))
            assertFalse(connectedUris.any { it.contains("new.example") })
            val health = registry.health.value.getValue(pairing.signerPubkeyHex)
            assertEquals(ConnectionFailure.NOT_CONNECTED, health.errorCategory)
            assertFalse(health.toString().contains("private storage failure"))
        } finally { registry.shutdownAll() }
        assertEquals(1, disconnected.get()) // No leaked connected client remained for shutdown to find.
    }

}
