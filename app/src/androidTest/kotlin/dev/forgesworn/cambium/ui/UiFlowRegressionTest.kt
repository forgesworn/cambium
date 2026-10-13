package dev.forgesworn.cambium.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeEncoder
import dev.forgesworn.cambium.MainActivity
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.applock.AppLockStore
import dev.forgesworn.cambium.log.ActivityLogActivity
import dev.forgesworn.cambium.log.ActivityLogEntry
import dev.forgesworn.cambium.log.ActivityLogStore
import dev.forgesworn.cambium.nip55.SignerActivity
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.ApprovalLifetime
import dev.forgesworn.cambium.pairing.PermissionScope
import dev.forgesworn.cambium.signer.ConnectionStatus
import dev.forgesworn.cambium.signer.HeartwoodSession
import kotlinx.coroutines.runBlocking
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.unlock.EnrolmentCode
import java.io.File
import java.net.URLEncoder
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real controls and permission store without contacting a hardware signer.
 * Run on a disposable emulator install; fixtures never replace an existing pairing or log. */
@RunWith(AndroidJUnit4::class)
class UiFlowRegressionTest {
    private val desk = "a".repeat(64)
    private val travel = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val approvedApp = "dev.cambium.ui.fixture.approved"
    private val deniedApp = "dev.cambium.ui.fixture.denied"
    private lateinit var context: Context
    private lateinit var pairings: PairingStore
    private lateinit var log: ActivityLogStore
    private var fixtureReady = false
    private var lockWasEnabled = false
    private var logWasEnabled = true

    @Before
    fun prepareDisposableInstall() {
        context = ApplicationProvider.getApplicationContext()
        pairings = PairingStore(context)
        log = ActivityLogStore.getInstance(context)
        assumeTrue("Use an empty emulator install", pairings.pairings().isEmpty())
        assumeTrue("Existing app choices must be preserved", pairings.allPermissions().isEmpty())
        assumeTrue("Existing activity must be preserved", log.entries().isEmpty())
        lockWasEnabled = AppLockStore(context).isEnabled()
        logWasEnabled = log.isEnabled()
        AppLockStore(context).setEnabled(false)
        log.setEnabled(true)
        fixtureReady = true
    }

    @After
    fun removeOnlyFixtures() {
        if (!fixtureReady) return
        pairings.removePairing(desk)
        pairings.removePairing(travel)
        pairings.forget(context.packageName)
        pairings.forget(approvedApp)
        pairings.forget(deniedApp)
        log.clear()
        log.setEnabled(logWasEnabled)
        AppLockStore(context).setEnabled(lockWasEnabled)
    }

    @Test
    fun freshInstallCanValidateAPastedLink() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.pairedSection)).check(matches(not(isDisplayed())))
            capture("home-fresh")
            onView(withId(R.id.scanButton)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.bunkerInput)).perform(scrollTo(), replaceText("invalid pairing link"), closeSoftKeyboard())
            onView(withId(R.id.pairButton)).perform(scrollTo(), click())
            onView(withId(R.id.pairingError)).check(matches(isDisplayed()))
            onView(withId(R.id.pairButton)).check(matches(isEnabled()))
            assertTrue(pairings.pairings().isEmpty())
            capture("pairing-error")
        }
    }

    @Test
    fun forgettingOneAppPreservesOtherChoicesAndSigners() {
        seedSigners()
        pairings.approve(approvedApp, desk)
        pairings.deny(deniedApp)
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.pairedSection)).check(matches(isDisplayed()))
            capture("home-paired")
            onView(allOf(withId(R.id.forgetButton), withParent(hasDescendant(withText(approvedApp)))))
                .perform(scrollTo(), click())
            assertNull(pairings.permission(approvedApp))
            assertEquals(AppPermissionState.DENIED, pairings.permission(deniedApp)?.state)
            assertEquals(setOf(desk, travel), pairings.pairings().map { it.signerPubkeyHex }.toSet())
        }
    }

    @Test
    fun unpairRequiresConfirmationAndClearsOnlyItsBoundApps() {
        seedSigners()
        pairings.approve(approvedApp, desk)
        pairings.deny(deniedApp)
        ActivityScenario.launch(MainActivity::class.java).use {
            val deskButton = allOf(withId(R.id.pairingUnpairButton), withParent(hasDescendant(withText("Desk Heartwood"))))
            onView(deskButton).perform(scrollTo(), click())
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
            assertEquals(2, pairings.pairings().size)
            assertEquals(desk, pairings.permission(approvedApp)?.boundIdentityPubkeyHex)
            onView(deskButton).perform(scrollTo(), click())
            onView(withText(R.string.unpair_button)).inRoot(isDialog()).perform(click())
            assertEquals(listOf(travel), pairings.pairings().map { it.signerPubkeyHex })
            assertNull(pairings.permission(approvedApp))
            assertEquals(AppPermissionState.DENIED, pairings.permission(deniedApp)?.state)
        }
    }

    @Test
    fun nativeApprovalRemembersTheSelectedIdentity() {
        seedSigners()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            launchNativeRequest(scenario)
            onView(withId(R.id.nativeRememberNote)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.webOneShotNote)).check(matches(not(isDisplayed())))
            onView(withId(R.id.identityPicker)).perform(scrollTo(), click())
            onData(equalTo("Travel Heartwood")).perform(click())
            assertIdentityLabelFits("Travel Heartwood")
            assertDecisionLabelsFit()
            capture("approval-native")
            onView(withId(R.id.approveButton)).perform(click())
            assertEquals(AppPermissionState.APPROVED, pairings.permission(context.packageName)?.state)
            assertEquals(travel, pairings.permission(context.packageName)?.boundIdentityPubkeyHex)
        }
    }

    @Test
    fun singleSignerIsNamedWithoutOfferingAnIdentityChoice() {
        pairings.addPairing(BunkerUri(desk, listOf("wss://relay.example"), "ui-fixture"), "Desk Heartwood")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            launchNativeRequest(scenario)
            onView(withId(R.id.singleIdentityValue)).perform(scrollTo())
                .check(matches(allOf(isDisplayed(), withText("Desk Heartwood"))))
            onView(withId(R.id.identityPicker)).check(matches(not(isDisplayed())))
            onView(withId(R.id.declineButton)).perform(scrollTo(), click())
            assertTrue(pairings.allPermissions().isEmpty())
        }
    }

    @Test
    fun declineIsOneShotAndAlwaysDenyIsRemembered() {
        seedSigners()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            launchNativeRequest(scenario)
            onView(withId(R.id.declineButton)).perform(scrollTo(), click())
            assertNull(pairings.permission(context.packageName))
            launchNativeRequest(scenario)
            onView(withId(R.id.denyAlwaysLink)).perform(scrollTo(), click())
            assertEquals(AppPermissionState.DENIED, pairings.permission(context.packageName)?.state)
            launchNativeRequest(scenario, expectApproval = false)
            onView(withId(R.id.approveButton)).check(doesNotExist())
        }
    }

    @Test
    fun websiteConsentStaysOneShotAndBothDecisionsAreReachable() {
        seedSigners()
        val event = """{"kind":27235,"content":""}"""
        val callback = URLEncoder.encode("https://example.test/?signedEvent=", "UTF-8")
        val payload = URLEncoder.encode(event, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            "nostrsigner:$payload?type=sign_event&callbackUrl=$callback&returnType=event&compressionType=none",
        )).setPackage(context.packageName)
        ActivityScenario.launch<SignerActivity>(intent).use {
            onView(withId(R.id.webOneShotNote)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.nativeRememberNote)).check(matches(not(isDisplayed())))
            onView(withId(R.id.denyAlwaysLink)).check(matches(not(isDisplayed())))
            assertIdentityLabelFits("Desk Heartwood")
            assertDecisionLabelsFit()
            capture("approval-web")
            onView(withId(R.id.declineButton)).perform(scrollTo(), click())
            assertTrue(pairings.allPermissions().isEmpty())
        }
    }

    @Test
    fun enrolmentQrStillDecodesAfterFittingTheScreen() {
        val code = EnrolmentCode(desk, "01".repeat(16), "Review phone", listOf(
            "wss://relay.example", "wss://second-relay.example", "wss://longer-relay-for-layout-review.example",
        )).encode()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Render the actual enrolment layout without provisioning a key or using relays.
            scenario.onActivity { activity ->
                activity.setContentView(R.layout.activity_unlock_enrol)
                activity.findViewById<TextView>(R.id.enrolTitle).text = activity.getString(R.string.enrol_title, "Desk Heartwood")
                for (id in listOf(R.id.enrolBody, R.id.enrolScanButton, R.id.enrolShowCodeButton)) {
                    activity.findViewById<View>(id).visibility = View.GONE
                }
                activity.findViewById<View>(R.id.enrolShowCodeBody).visibility = View.VISIBLE
                activity.findViewById<ImageView>(R.id.enrolQr).apply {
                    setImageBitmap(BarcodeEncoder().encodeBitmap(code, BarcodeFormat.QR_CODE, 720, 720))
                    visibility = View.VISIBLE
                }
                activity.findViewById<TextView>(R.id.enrolCode).apply { text = code; visibility = View.VISIBLE }
                activity.findViewById<TextView>(R.id.enrolWords).apply {
                    text = "amber birch cedar drift ember"
                    visibility = View.VISIBLE
                }
                activity.findViewById<View>(R.id.enrolWordsHint).visibility = View.VISIBLE
                activity.findViewById<View>(R.id.enrolCopyButton).visibility = View.VISIBLE
                activity.findViewById<TextView>(R.id.enrolStatus).setText(R.string.enrol_waiting)
            }
            onView(withId(R.id.enrolQr)).perform(scrollTo()).check { view, failure ->
                if (failure != null) throw failure
                val image = view as ImageView
                val parent = image.parent as View
                assertTrue(image.width <= parent.width - parent.paddingLeft - parent.paddingRight)
                val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                image.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                assertEquals(code, MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
                bitmap.recycle()
            }
            capture("phone-enrolment-layout")
            onView(withId(R.id.enrolDoneButton)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun logClearRequiresConfirmationAndDoesNotChangePairings() {
        seedSigners()
        for (outcome in ActivityLogEntry.Outcome.entries) {
            log.append(ActivityLogEntry(System.currentTimeMillis(), approvedApp, "sign_event", 1, "Desk Heartwood", outcome))
        }
        ActivityScenario.launch(ActivityLogActivity::class.java).use {
            onView(allOf(withId(R.id.logOutcomeValue), withText(R.string.activity_log_outcome_failed)))
                .perform(scrollTo()).check(matches(isDisplayed()))
            capture("activity-log")
            onView(withId(R.id.clearButton)).perform(scrollTo(), click())
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
            assertFalse(log.entries().isEmpty())
            onView(withId(R.id.clearButton)).perform(scrollTo(), click())
            onView(withText(R.string.activity_log_clear_button)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.logEmpty)).perform(scrollTo()).check(matches(isDisplayed()))
            assertTrue(log.entries().isEmpty())
            assertEquals(2, pairings.pairings().size)
        }
    }

    @Test
    fun pairedSignerCanBeTestedWithoutPretendingItIsReachable() {
        pairings.addPairing(BunkerUri(desk, listOf("wss://127.0.0.1:1"), "ui-fixture"), "Desk Heartwood")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onView(withId(R.id.pairingHealthValue)).perform(scrollTo())
                    .check(matches(withText(containsString(context.getString(R.string.connection_unknown)))))
                onView(withId(R.id.pairingTestButton)).perform(scrollTo(), click())
                    .check(matches(not(isEnabled())))
                val deadline = SystemClock.uptimeMillis() + 30_000
                while (HeartwoodSession.health.value[desk]?.status != ConnectionStatus.UNAVAILABLE &&
                    SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(ConnectionStatus.UNAVAILABLE, HeartwoodSession.health.value[desk]?.status)
                assertNull(HeartwoodSession.health.value[desk]?.lastSuccessfulContactMillis)
                // Worker state can change before the lifecycle collector has drawn it. Await the
                // actual outcome on screen, rather than treating an idle queue as a rendered frame.
                val reason = context.getString(R.string.connection_error_timeout)
                val renderDeadline = SystemClock.uptimeMillis() + 5_000
                var rendered = false
                while (!rendered && SystemClock.uptimeMillis() < renderDeadline) {
                    scenario.onActivity { activity ->
                        rendered = activity.findViewById<TextView>(R.id.pairingHealthValue)?.text?.contains(reason) == true &&
                            activity.findViewById<View>(R.id.pairingTestButton)?.isEnabled == true
                    }
                    if (!rendered) SystemClock.sleep(25)
                }
                assertTrue("Connection failure must be rendered with recovery available", rendered)
                onView(withId(R.id.pairingHealthValue)).perform(scrollTo())
                    .check(matches(withText(containsString(reason))))
                onView(withId(R.id.pairingTestButton)).perform(scrollTo()).check(matches(isEnabled()))
                capture("connection-unavailable")
            }
        } finally {
            runBlocking { HeartwoodSession.shutdown(desk) }
        }
    }

    @Test
    fun onceApprovalAnswersWithoutRememberingNativeApp() {
        seedSigners()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            launchNativeRequest(scenario)
            onView(withId(R.id.approvalLifetimePicker)).perform(scrollTo(), click())
            onData(equalTo(context.getString(R.string.approval_once))).perform(click())
            onView(withId(R.id.approveButton)).perform(scrollTo(), click())
            assertNull(pairings.permission(context.packageName))
            launchNativeRequest(scenario)
            onView(withId(R.id.declineButton)).perform(scrollTo(), click())
        }
    }

    @Test
    fun hourlyApprovalCanBeRestrictedToCurrentOperation() {
        seedSigners()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            launchNativeRequest(scenario)
            onView(withId(R.id.approvalLifetimePicker)).perform(scrollTo(), click())
            onData(equalTo(context.getString(R.string.approval_hour))).perform(click())
            onView(withId(R.id.approvalScopePicker)).perform(scrollTo(), click())
            onData(equalTo(context.getString(R.string.approval_scope_current))).perform(click())
            onView(withId(R.id.approveButton)).perform(scrollTo(), click())
            val grant = pairings.permission(context.packageName)!!
            assertTrue(grant.expiresAtMillis!! > System.currentTimeMillis())
            assertTrue(grant.expiresAtMillis!! <= System.currentTimeMillis() + 3_600_000)
            assertFalse(grant.allows("sign_event", 1))
            assertTrue(grant.allows("get_public_key"))
        }
    }

    @Test
    fun renameAndConfirmedRebindKeepCredentialsScopeAndExpiry() {
        seedSigners()
        val scope = PermissionScope(setOf("nip44_decrypt"))
        pairings.approve(approvedApp, desk, ApprovalLifetime.HOUR, scope)
        val expiry = pairings.permission(approvedApp)!!.expiresAtMillis
        val before = pairings.pairings().first { it.signerPubkeyHex == desk }
        ActivityScenario.launch(MainActivity::class.java).use {
            val rename = allOf(withId(R.id.pairingRenameButton), withParent(hasDescendant(withText("Desk Heartwood"))))
            onView(rename).perform(scrollTo(), click())
            onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(com.google.android.material.textfield.TextInputEditText::class.java))
                .inRoot(isDialog()).perform(replaceText("Office signer"), closeSoftKeyboard())
            onView(withText(R.string.save_button)).inRoot(isDialog()).perform(click())
            val after = pairings.pairings().first { it.signerPubkeyHex == desk }
            assertEquals(before.copy(label = "Office signer"), after)
            onView(allOf(withId(R.id.editAppButton), withParent(hasDescendant(withText(approvedApp)))))
                .perform(scrollTo(), click())
            onView(withText(R.string.app_edit_identity)).inRoot(isDialog()).perform(click())
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
            onView(withText(R.string.save_button)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
            capture("identity-management")
            onData(equalTo("Travel Heartwood")).inRoot(isDialog()).perform(click())
            onView(withText(R.string.save_button)).inRoot(isDialog()).perform(click())
            assertEquals(desk, pairings.permission(approvedApp)!!.boundIdentityPubkeyHex)
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
            onView(withText(R.string.save_button)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
            capture("identity-management-confirmation")
            onView(withText(R.string.save_button)).inRoot(isDialog()).perform(click())
            assertEquals(travel, pairings.permission(approvedApp)!!.boundIdentityPubkeyHex)
            assertEquals(scope, pairings.permission(approvedApp)!!.scope)
            assertEquals(expiry, pairings.permission(approvedApp)!!.expiresAtMillis)
        }
    }

    private fun seedSigners() {
        pairings.addPairing(BunkerUri(desk, listOf("wss://relay.example"), "ui-fixture"), "Desk Heartwood")
        pairings.addPairing(BunkerUri(travel, listOf("wss://relay.example"), "ui-fixture"), "Travel Heartwood")
    }

    private fun assertDecisionLabelsFit() {
        for (id in listOf(R.id.declineButton, R.id.approveButton)) {
            onView(withId(id)).perform(scrollTo()).check(matches(isDisplayed())).check { view, failure ->
                if (failure != null) throw failure
                val layout = (view as TextView).layout
                assertEquals("A decision label must fit without splitting its word", 1, layout.lineCount)
                assertEquals(0, layout.getEllipsisCount(0))
            }
        }
    }

    private fun assertIdentityLabelFits(label: String) {
        onView(allOf(withId(android.R.id.text1), withText(label))).perform(scrollTo()).check { view, failure ->
            if (failure != null) throw failure
            val text = view as TextView
            val layout = text.layout
            for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
            val requiredHeight = layout.height + text.compoundPaddingTop + text.compoundPaddingBottom
            assertTrue("Identity needs $requiredHeight px but has ${text.height} px", requiredHeight <= text.height)
            val parent = text.parent as View
            assertTrue("Identity must fit inside its picker", text.bottom <= parent.height - parent.paddingBottom)
        }
    }

    @Suppress("DEPRECATION")
    private fun launchNativeRequest(scenario: ActivityScenario<MainActivity>, expectApproval: Boolean = true) {
        // Wait for the caller's window before launching a second activity from it.
        onView(withId(R.id.statusValue)).check(matches(isDisplayed()))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(SignerActivity::class.java.name, null, false)
        try {
            scenario.onActivity { activity ->
                activity.startActivityForResult(Intent(activity, SignerActivity::class.java)
                    .setData(Uri.parse("nostrsigner:"))
                    .putExtra("type", "get_public_key"), 55)
            }
            val signer = monitor.waitForActivityWithTimeout(10_000)
                ?: throw AssertionError("SignerActivity did not start")
            // startActivityForResult queues the hand-off. Espresso can otherwise select the
            // still-focused caller's root before the approval window is ready on a slow emulator.
            val deadline = SystemClock.uptimeMillis() + 10_000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    ready = if (expectApproval) {
                        signer.hasWindowFocus() && signer.findViewById<View>(R.id.approveButton)?.isShown == true
                    } else {
                        signer.isDestroyed
                    }
                }
                if (!ready) SystemClock.sleep(25)
            }
            assertTrue("Signer request did not reach its expected window state", ready)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture_ui") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // A ScrollView can still be settling after Espresso's main-thread queue is idle.
        instrumentation.uiAutomation.waitForIdle(250, 3000)
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = File(context.filesDir, "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
