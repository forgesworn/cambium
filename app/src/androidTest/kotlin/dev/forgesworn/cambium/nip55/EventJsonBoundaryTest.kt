package dev.forgesworn.cambium.nip55

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import rust.nostr.sdk.Keys
import rust.nostr.sdk.UnsignedEvent

/** Exercises the native rust-nostr parser that host-JVM tests cannot load. */
@RunWith(AndroidJUnit4::class)
class EventJsonBoundaryTest {

    @Test
    fun minimalNip55WebEventCrossesTheRustNostrBoundary() {
        val signerPubkeyHex = Keys.generate().publicKey().toHex()
        val prepared = normaliseUnsignedEvent(
            eventJson = """{"kind":1,"content":"test"}""",
            signerPubkeyHex = signerPubkeyHex,
            nowEpochSeconds = 1720000123,
        )

        assertNotNull(UnsignedEvent.fromJson(prepared))
    }
}
