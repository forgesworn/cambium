package dev.forgesworn.cambium.nip55

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativeBatchTest {
    @Test fun `signing result is signature with original correlation id and package`() {
        val signature = "b".repeat(128)
        val request = Nip55Request.SignEvent("signed-id", null, "{\"kind\":1,\"content\":\"note\"}")
        val value = "{\"kind\":1,\"content\":\"note\",\"sig\":\"$signature\"}"
        assertEquals(NativeSignerResult("signer", "signed-id", signature, signature),
            NativeBatchResultBuilder.success(request, value, "signer"))
        assertNull(NativeBatchResultBuilder.success(request, "{\"sig\":\"invalid\"}", "signer"))
        assertNull(NativeBatchResultBuilder.success(request, "{\"sig\":${"1".repeat(128)}}", "signer"))
        assertNull(NativeBatchResultBuilder.success(request, "{}", "signer"))
    }
    @Test fun `crypto batch result returns exact value without treating it as an event`() {
        val request = Nip55Request.Nip44Decrypt("message-id", null, "a".repeat(64), "ciphertext")
        assertEquals(NativeSignerResult("signer", "message-id", "plaintext"),
            NativeBatchResultBuilder.success(request, "plaintext", "signer"))
    }
    @Test fun `results retain order ids and partial rejections`() {
        val batch = NativeBatchResults()
        assertTrue(batch.append(NativeSignerResult("signer", "first", "signature", "signature")))
        assertTrue(batch.append(NativeSignerResult("signer", "second", rejected = true)))
        val rows = Json.parseToJsonElement(batch.toJson()).jsonArray
        assertEquals(listOf("first", "second"), rows.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals("true", rows[1].jsonObject.getValue("rejected").jsonPrimitive.content)
    }
    @Test fun `collection bounds bytes and number of requests without dropping earlier results`() {
        val small = NativeBatchResults(160)
        assertTrue(small.append(NativeSignerResult("signer", "kept", "ok")))
        assertFalse(small.append(NativeSignerResult("signer", "large", "x".repeat(500))))
        assertEquals(1, small.size)
        val batch = NativeBatchResults()
        repeat(NativeBatchResults.MAX_REQUESTS) { assertTrue(batch.append(NativeSignerResult("signer", "$it"))) }
        assertFalse(batch.append(NativeSignerResult("signer", "overflow")))
        assertEquals(NativeBatchResults.MAX_REQUESTS, batch.size)
    }
    @Test fun `batch permission requires real sender attribution and matching result recipient`() {
        assertEquals(BatchCallerGate.Decision.REMEMBERED_PERMISSION, BatchCallerGate.decide("app", "app"))
        assertEquals(BatchCallerGate.Decision.ONE_SHOT, BatchCallerGate.decide("app", null))
        assertEquals(BatchCallerGate.Decision.REJECT, BatchCallerGate.decide("app", "other"))
        assertEquals(BatchCallerGate.Decision.REJECT, BatchCallerGate.decide(null, "app"))
    }
    @Test fun `batch collections cannot leak between callers`() {
        val first = NativeBatchResults()
        first.append(NativeSignerResult("signer", "private-id", "private-result"))
        assertEquals("[]", NativeBatchResults().toJson())
    }
}
