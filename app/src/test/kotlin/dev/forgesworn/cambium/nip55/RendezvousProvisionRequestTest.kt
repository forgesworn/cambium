package dev.forgesworn.cambium.nip55

import dev.forgesworn.cambium.pairing.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RendezvousProvisionRequestTest {
    private val now = 1_900_000_000L
    private val persona = "ab".repeat(32)
    private val target = "cd".repeat(32)
    private fun json(at: Long = now) = """{"v":1,"expectedPersona":"$persona","targetDevice":"$target","index":7,"nonce":"AAECAwQFBgcICQoLDA0ODw","expiresAt":${at + 300}}"""
    private fun request() = requireNotNull(RendezvousProvisionRequest.read(json(), now))
    private fun envelope() = buildJsonObject {
        put("v", 1); put("p", persona); put("d", target); put("rz", "ef".repeat(32)); put("u", "rendezvous")
        put("i", 7); put("n", request().nonce); put("e", now + 300)
        put("c", Base64.getEncoder().encodeToString(ByteArray(99).also { it[0] = 2 }))
    }.toString()
    @Test fun `public contract has exact numeric positional Heartwood wire`() {
        val r = request()
        assertEquals("""["$target",7,"AAECAwQFBgcICQoLDA0ODw",1900000300]""", r.params().toString())
        assertFalse(r.params()[1].jsonPrimitive.isString)
        assertFalse(r.params()[3].jsonPrimitive.isString)
        assertTrue(r.acceptsEnvelope(envelope(), now))
    }
    @Test fun `malformed alternate extra stale and overlong inputs refuse`() {
        for (raw in listOf(json().replace("\"index\":7", "\"index\":\"7\""), json().replace("\"index\":7", "\"index\":4294967296"),
            json().replace("AAECAwQFBgcICQoLDA0ODw", "AAECAwQFBgcICQoLDA0ODx"), json().replace("\"v\":1", "\"v\":1,\"v\":1"),
            json().replace("\"v\":1", "\"v\":1,\"extra\":0"), json(now - 301), json(now + 301), " ".repeat(1025))) {
            assertNull(raw.take(100), RendezvousProvisionRequest.read(raw, now))
        }
        for (changed in listOf(envelope().replace(persona, target), envelope().replace("\"i\":7", "\"i\":8"),
            envelope().replace("1900000300", "1900000301"), envelope().replace("\"rendezvous\"", "\"other\""))) assertFalse(request().acceptsEnvelope(changed, now))
    }
    @Test fun `visible approval shows every original public binding without ciphertext`() {
        val r = request()
        val presentation = RequestPresenter.describe(Nip55Request.ProvisionRendezvous("one-shot-request-id", persona, r))
        for (value in listOf(persona, target, r.index.toString(), r.nonce, r.expiresAt.toString())) assertTrue(presentation.summary.contains(value))
        assertNull(presentation.details)
        assertFalse(presentation.summary.contains("ciphertext"))
    }
    @Test fun `remembered permission cannot forward or choose another identity`() {
        val p = Pairing(persona, listOf("wss://example.invalid"), null, "11".repeat(32), "22".repeat(32))
        val r = Nip55Request.ProvisionRendezvous("one-shot-request-id", persona, request())
        val approved = AppPermission(AppPermissionState.APPROVED, persona)
        assertEquals(IntentGate.Plan.AskUser, IntentGate.plan(approved, listOf(p), r, true))
        assertTrue(IntentGate.plan(approved, listOf(p), r, false) is IntentGate.Plan.Reject)
        assertTrue(IntentGate.plan(AppPermission(AppPermissionState.DENIED, null), listOf(p), r, true) is IntentGate.Plan.Reject)
        assertTrue(IntentGate.plan(approved, listOf(p.copy(signerPubkeyHex = target)), r, true) is IntentGate.Plan.Reject)
    }
    @Test fun `one shot approval rechecks caller intent job pairing expiry and rewind`() {
        var current = true
        val approval = ProvisionApproval(request()) { current }
        assertTrue(approval.dispatch(now))
        assertFalse(approval.dispatch(now))
        current = false
        assertFalse(approval.release(envelope(), now))
        current = true
        assertFalse(approval.release(envelope(), now + 301))
        assertFalse(approval.release(envelope(), now))
        val second = ProvisionApproval(request()) { true }
        assertTrue(second.dispatch(now))
        assertTrue(second.release(envelope(), now))
        assertFalse(second.release(envelope(), now))
    }
}
