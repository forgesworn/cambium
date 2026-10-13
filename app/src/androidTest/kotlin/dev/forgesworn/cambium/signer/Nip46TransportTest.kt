package dev.forgesworn.cambium.signer

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.cambium.pairing.BunkerUri
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.*
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real rust-nostr sockets, signing and NIP-44 against an in-process loopback fixture. */
@RunWith(AndroidJUnit4::class)
class Nip46TransportTest {
    @Test fun sdkHandshakePingAndAuthenticatedRelaySwitchReachTheWire() = runBlocking {
        LocalSignerRelay().use { relay ->
            val clientKeys = Keys.generate()
            val bunker = BunkerUri(relay.signer.publicKey().toHex(), listOf(relay.url), null)
            // ws is permitted only by this test's injected parser; production remains wss-only.
            val client = RustNostrHeartwoodClient { bunker }
            try {
                assertTrue(client.connect(bunker.toUriString(), clientKeys.secretKey().toHex()) is HeartwoodResult.Success)
                assertEquals(HeartwoodResult.Success("pong"), client.ping())
                assertEquals(RelaySwitchResult.Updated(listOf("wss://new.example")), client.switchRelays())
                assertTrue(relay.methods.containsAll(listOf("connect", "get_public_key", "ping", "switch_relays")))
                // Optional method refusals leave the authenticated signing session usable.
                relay.unsupported = true
                assertEquals(RelaySwitchResult.Unsupported, client.switchRelays())
                val signed = client.signEvent("""{"kind":1,"content":"local fixture"}""")
                assertTrue(signed is HeartwoodResult.Success)
                val event = Event.fromJson((signed as HeartwoodResult.Success).value)
                assertTrue(event.verify())
                assertEquals(relay.signer.publicKey().toHex(), event.author().toHex())
            } finally { client.disconnect() }
        }
    }

    private class LocalSignerRelay : Closeable {
        val signer = Keys.generate()
        private val outsider = Keys.generate()
        private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "ws://127.0.0.1:${server.localPort}"
        val methods = CopyOnWriteArrayList<String>()
        @Volatile var unsupported = false
        @Volatile private var closed = false
        private val peers = CopyOnWriteArrayList<Peer>()
        private val executor = Executors.newCachedThreadPool()

        init {
            executor.execute {
                while (!closed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    val peer = Peer(socket)
                    peers += peer
                    executor.execute { runCatching { peer.run() }; peer.close() }
                }
            }
        }

        private inner class Peer(private val socket: Socket) : Closeable {
            private val input = BufferedInputStream(socket.getInputStream())
            private val output = socket.getOutputStream()
            private val subscriptions = CopyOnWriteArrayList<String>()

            fun run() {
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    if (byte < 0 || headers.length > 8192) return
                    headers.append(byte.toChar())
                }
                val key = headers.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                output.flush()
                while (!closed) {
                    val first = input.read()
                    if (first < 0) return
                    val second = input.read()
                    var length = second and 127
                    if (length == 126) length = (input.read() shl 8) or input.read()
                    if (length == 127 || length > 65535) return
                    val mask = if ((second and 128) != 0) readBytes(4) else null
                    val payload = readBytes(length)
                    if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    when (first and 15) {
                        8 -> { frame(8, byteArrayOf()); return }
                        9 -> frame(10, payload)
                        1 -> handle(Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonArray)
                    }
                }
            }

            private fun readBytes(size: Int): ByteArray {
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val read = input.read(bytes, offset, size - offset)
                    if (read < 0) error("socket closed")
                    offset += read
                }
                return bytes
            }

            @Synchronized private fun frame(opcode: Int, bytes: ByteArray) {
                output.write(128 or opcode)
                if (bytes.size < 126) output.write(bytes.size) else {
                    output.write(126); output.write(bytes.size shr 8); output.write(bytes.size and 255)
                }
                output.write(bytes)
                output.flush()
            }

            fun send(value: JsonArray) = frame(1, value.toString().toByteArray())

            private fun handle(message: JsonArray) {
                when (message[0].jsonPrimitive.content) {
                    "REQ" -> {
                        val id = message[1].jsonPrimitive.content
                        subscriptions += id
                        send(buildJsonArray { add("EOSE"); add(id) })
                    }
                    "CLOSE" -> subscriptions.remove(message[1].jsonPrimitive.content)
                    "EVENT" -> {
                        val event = Event.fromJson(message[1].toString())
                        if (!event.verify()) return
                        send(buildJsonArray { add("OK"); add(event.id().toHex()); add(true); add("") })
                        val plain = nip44Decrypt(signer.secretKey(), event.author(), event.content())
                        val request = Json.parseToJsonElement(plain).jsonObject
                        val method = request.getValue("method").jsonPrimitive.content
                        val id = request.getValue("id").jsonPrimitive.content
                        methods += method
                        val result: JsonElement = when (method) {
                            "connect" -> JsonPrimitive("ack")
                            "get_public_key" -> JsonPrimitive(signer.publicKey().toHex())
                            "ping" -> JsonPrimitive("pong")
                            "switch_relays" -> JsonArray(listOf(JsonPrimitive("wss://new.example")))
                            "sign_event" -> {
                                val unsigned = request.getValue("params").jsonArray[0].jsonPrimitive.content
                                JsonPrimitive(UnsignedEvent.fromJson(unsigned).signWithKeys(signer).asJson())
                            }
                            else -> JsonNull
                        }
                        fun response(author: Keys = signer, replyId: String = id, recipient: PublicKey = event.author(), bad: Boolean = false): Event {
                            val body = buildJsonObject {
                                put("id", replyId)
                                if (unsupported && method == "switch_relays") put("error", "unsupported method")
                                else put("result", if (bad) JsonArray(listOf(JsonPrimitive("wss://attacker.example"))) else result)
                            }
                            return EventBuilder(Kind(24133u), nip44Encrypt(author.secretKey(), event.author(), body.toString(), Nip44Version.V2))
                                .tags(listOf(Tag.publicKey(recipient))).signWithKeys(author)
                        }
                        if (method == "switch_relays" && !unsupported) {
                            broadcast(response(author = outsider, bad = true))
                            broadcast(response(replyId = "wrong-id", bad = true))
                            broadcast(response(recipient = outsider.publicKey(), bad = true))
                        }
                        broadcast(response())
                    }
                }
            }

            fun event(event: Event) {
                for (id in subscriptions) runCatching {
                    send(buildJsonArray { add("EVENT"); add(id); add(Json.parseToJsonElement(event.asJson())) })
                }
            }
            override fun close() { runCatching { socket.close() }; peers.remove(this) }
        }

        private fun broadcast(event: Event) { peers.forEach { it.event(event) } }
        override fun close() {
            closed = true
            server.close()
            peers.toList().forEach { it.close() }
            executor.shutdown()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Fixture socket threads did not stop" }
        }
    }
}
