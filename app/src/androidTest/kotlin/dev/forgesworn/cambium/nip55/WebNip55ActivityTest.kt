package dev.forgesworn.cambium.nip55

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.PairingStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.hamcrest.Matchers.not
import org.junit.After
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

        ActivityScenario.launch<SignerActivity>(intent).use {
            onView(withId(R.id.callerLabel)).check(matches(withText("Returns to")))
            onView(withId(R.id.appValue)).check(matches(withText("regress.atobitcoin.io")))
            onView(withId(R.id.methodValue)).check(matches(withText("sign_event")))
            onView(withId(R.id.kindValue)).check(matches(withText("27235")))
            onView(withId(R.id.webOneShotNote)).check(matches(isDisplayed()))
            onView(withId(R.id.denyAlwaysLink)).check(matches(not(isDisplayed())))
            onView(withId(R.id.approveButton)).check(matches(isDisplayed()))
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
