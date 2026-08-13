package dev.forgesworn.cambium.nip55

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.PairingStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebNip55ActivityTest {
    private val fixturePubkey = "a".repeat(64)
    private lateinit var context: Context
    private lateinit var pairingStore: PairingStore
    private var addedFixturePairing = false

    @Before
    fun seedPairing() {
        context = ApplicationProvider.getApplicationContext()
        pairingStore = PairingStore(context)
        if (pairingStore.pairings().isEmpty()) {
            pairingStore.addPairing(
                BunkerUri(
                    signerPubkeyHex = fixturePubkey,
                    relays = listOf("wss://relay.example"),
                    secret = "device-test",
                ),
                label = "Device test signer",
            )
            addedFixturePairing = true
        }
    }

    @After
    fun removeFixturePairing() {
        if (addedFixturePairing) pairingStore.removePairing(fixturePubkey)
    }

    @Test
    fun regressRequestIsRenderedAsOneShotWebsiteApproval() {
        val unsignedEvent = """{"kind":27235,"created_at":1786615200,"tags":[["u","https://regress.atobitcoin.io/api/auth"],["method","POST"]],"content":""}"""
        val callback = "https://regress.atobitcoin.io/?signedEvent="
        val uri = "nostrsigner:${encode(unsignedEvent)}" +
            "?type=sign_event" +
            "&callbackUrl=${encode(callback)}" +
            "&returnType=event" +
            "&compressionType=none" +
            "&appName=Regress"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(context.packageName)
        }

        ActivityScenario.launch<SignerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Returns to", activity.findViewById<TextView>(R.id.callerLabel).text.toString())
                assertEquals("regress.atobitcoin.io", activity.findViewById<TextView>(R.id.appValue).text.toString())
                assertEquals("sign_event", activity.findViewById<TextView>(R.id.methodValue).text.toString())
                assertEquals("27235", activity.findViewById<TextView>(R.id.kindValue).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.webOneShotNote).visibility)
                assertNotEquals(View.VISIBLE, activity.findViewById<View>(R.id.denyAlwaysLink).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.approveButton).visibility)
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
