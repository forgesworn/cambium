package dev.forgesworn.cambium.nip55

import android.app.Activity
import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.applock.AppLockStore
import dev.forgesworn.cambium.log.ActivityLogStore
import dev.forgesworn.cambium.pairing.AppPermissionState
import dev.forgesworn.cambium.pairing.BunkerUri
import dev.forgesworn.cambium.pairing.PairingStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Disposable emulator tests with a real separate caller APK/result recipient. Public-key requests
 * use only the pairing record, never a real signer or relay. IPC avoids assuming the fixture
 * activity and target instrumentation share a process.
 */
@RunWith(AndroidJUnit4::class)
class NativeBatchActivityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fixturePubkey = "a".repeat(64)
    private lateinit var context: Context
    private lateinit var callerContext: Context
    private lateinit var pairings: PairingStore
    private lateinit var log: ActivityLogStore
    private lateinit var monitor: Instrumentation.ActivityMonitor
    private var signer: Activity? = null
    private var fixtureReady = false
    private var receiverReady = false
    private var lockWasEnabled = false
    private var logWasEnabled = true
    private val nonce = UUID.randomUUID().toString()
    private val commandAction get() = "dev.forgesworn.cambium.test.BATCH_COMMAND.$nonce"
    private val resultAction get() = "dev.forgesworn.cambium.test.BATCH_RESULT.$nonce"
    private val resultLatch = CountDownLatch(1)
    @Volatile private var commandLatch: CountDownLatch? = null
    @Volatile private var resultCode: Int? = null
    @Volatile private var resultData: Intent? = null
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra("command_ack", false)) {
                commandLatch?.countDown()
                return
            }
            this@NativeBatchActivityTest.resultCode = intent.getIntExtra("result_code", Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val receivedResult = intent.getParcelableExtra<Intent>("result_data")
            this@NativeBatchActivityTest.resultData = receivedResult
            resultLatch.countDown()
        }
    }

    @Before
    fun prepareEmptyDisposableInstall() {
        context = ApplicationProvider.getApplicationContext()
        callerContext = instrumentation.context
        pairings = PairingStore(context)
        log = ActivityLogStore.getInstance(context)
        assumeTrue("Use an empty disposable emulator install", pairings.pairings().isEmpty())
        assumeTrue("Existing app choices must be preserved", pairings.allPermissions().isEmpty())
        assumeTrue("Existing log must be preserved", log.entries().isEmpty())
        lockWasEnabled = AppLockStore(context).isEnabled()
        logWasEnabled = log.isEnabled()
        fixtureReady = true
        AppLockStore(context).setEnabled(false)
        log.setEnabled(false)
        pairings.addPairing(BunkerUri(fixturePubkey, listOf("wss://relay.example"), "batch-test"), "Batch test signer")
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(resultReceiver, IntentFilter(resultAction), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(resultReceiver, IntentFilter(resultAction))
        }
        receiverReady = true
        monitor = instrumentation.addMonitor(SignerActivity::class.java.name, null, false)
    }

    @After
    fun removeOnlyFixtures() {
        if (!fixtureReady) return
        signer?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        sendCommand("finish")
        instrumentation.waitForIdleSync()
        if (::monitor.isInitialized) instrumentation.removeMonitor(monitor)
        if (receiverReady) context.unregisterReceiver(resultReceiver)
        pairings.forget(callerContext.packageName)
        pairings.removePairing(fixturePubkey)
        log.setEnabled(logWasEnabled)
        AppLockStore(context).setEnabled(lockWasEnabled)
    }

    @Test
    fun orderedQueuedRequestsEachNeedConsentAndDoNotPersistOneShotAuthority() {
        launchCaller("first")
        assertApproval()
        sendCommand("request", "second")
        sendCommand("request", "third")
        chooseOnce()
        approve()
        assertUnattributedApproval()
        assertPosition("2")
        approve()
        assertUnattributedApproval()
        assertPosition("3")
        approve()
        val rows = awaitResults()
        assertEquals(listOf("first", "second", "third"), rows.map { it["id"]!!.jsonPrimitive.content })
        for (row in rows) {
            assertEquals(context.packageName, row["package"]!!.jsonPrimitive.content)
            assertTrue(row["result"]!!.jsonPrimitive.content.startsWith("npub1"))
            assertFalse(row["rejected"]?.jsonPrimitive?.content == "true")
        }
        assertNull(pairings.permission(callerContext.packageName))
    }

    @Test
    fun partialRejectionReturnsBothOrderedResults() {
        launchCaller("allowed")
        assertApproval()
        sendCommand("request", "declined")
        chooseOnce()
        approve()
        assertUnattributedApproval()
        onView(withId(R.id.declineButton)).perform(scrollTo(), click())
        val rows = awaitResults()
        assertEquals(listOf("allowed", "declined"), rows.map { it["id"]!!.jsonPrimitive.content })
        assertTrue(rows[0]["result"]!!.jsonPrimitive.content.startsWith("npub1"))
        assertEquals("true", rows[1]["rejected"]!!.jsonPrimitive.content)
        assertNull(rows[1]["result"])
        assertNull(pairings.permission(callerContext.packageName))
    }

    @Test
    fun queuedUnattributableRequestCannotBorrowOrChangeRememberedInitialGrant() {
        launchCaller("remembered")
        assertApproval()
        sendCommand("request", "unattributed")
        // The first request deliberately keeps the default remembered approval.
        approve()
        val remembered = pairings.permission(callerContext.packageName)
        assertNotNull(remembered)
        assertEquals(AppPermissionState.APPROVED, remembered!!.state)
        assertUnattributedApproval()
        assertEquals(1L, resultLatch.count)
        approve()
        assertEquals(2, awaitResults().size)
        assertEquals(remembered, pairings.permission(callerContext.packageName))
        assertEquals(setOf(callerContext.packageName), pairings.allPermissions().keys)
    }

    @Test
    fun newerDenialRejectsStaleConsentAndQueuedUnattributedRequests() {
        launchCaller("blocked-before-approval")
        assertApproval()
        sendCommand("request", "blocked-unattributed")
        pairings.deny(callerContext.packageName)
        approve()
        val rows = awaitResults()
        assertEquals(listOf("blocked-before-approval", "blocked-unattributed"),
            rows.map { it["id"]!!.jsonPrimitive.content })
        for (row in rows) {
            assertEquals("true", row["rejected"]!!.jsonPrimitive.content)
            assertNull(row["result"])
            assertNull(row["signature"])
        }
        assertEquals(AppPermissionState.DENIED, pairings.permission(callerContext.packageName)!!.state)
    }

    @Test
    fun api35AttributedSameCallerUsesRememberedGrantWithoutAnotherConsentSheet() {
        assumeTrue("Authenticated relaunch callers require Android API 35", Build.VERSION.SDK_INT >= 35)
        launchCaller("first-approved")
        assertApproval()
        sendCommand("request", "attributed", shareIdentity = true)
        // Keep a timed, restricted grant, so silent reuse cannot broaden it to the defaults.
        onView(withId(R.id.approvalLifetimePicker)).perform(scrollTo(), click())
        onData(equalTo(context.getString(R.string.approval_hour))).perform(click())
        onView(withId(R.id.approvalScopePicker)).perform(scrollTo(), click())
        onData(equalTo(context.getString(R.string.approval_scope_current))).perform(click())
        approve()
        val remembered = pairings.permission(callerContext.packageName)
        assertNotNull(remembered)
        assertNotNull(remembered!!.expiresAtMillis)
        assertEquals(setOf("get_public_key"), remembered.scope!!.methods)
        assertFalse(remembered.allows("sign_event", 1))
        val rows = awaitResults()
        assertEquals(listOf("first-approved", "attributed"), rows.map { it["id"]!!.jsonPrimitive.content })
        for (row in rows) assertTrue(row["result"]!!.jsonPrimitive.content.startsWith("npub1"))
        // No second tap was made: a consent sheet would leave this result latch unresolved.
        assertEquals(remembered, pairings.permission(callerContext.packageName))
        assertEquals(setOf(callerContext.packageName), pairings.allPermissions().keys)
    }

    @Test
    fun backDeclinesCurrentBatchSheetWithoutLosingEarlierOrderedResults() {
        launchCaller("allowed-before-back")
        assertApproval()
        sendCommand("request", "declined-by-back")
        chooseOnce()
        approve()
        assertUnattributedApproval()
        pressBack()
        val rows = awaitResults()
        assertEquals(listOf("allowed-before-back", "declined-by-back"), rows.map { it["id"]!!.jsonPrimitive.content })
        assertTrue(rows[0]["result"]!!.jsonPrimitive.content.startsWith("npub1"))
        assertEquals("true", rows[1]["rejected"]!!.jsonPrimitive.content)
        assertNull(rows[1]["result"])
        assertNull(pairings.permission(callerContext.packageName))
    }

    @Test
    fun nonBatchReusedIntentCannotCancelOriginalRequest() {
        launchCaller("original")
        assertApproval()
        // SINGLE_TOP delivers to the open instance, but missing CLEAR_TOP does not opt into batching.
        sendCommand("request", "ignored", Intent.FLAG_ACTIVITY_SINGLE_TOP)
        instrumentation.waitForIdleSync()
        assertApproval()
        assertEquals(1L, resultLatch.count)
        chooseOnce()
        approve()
        val rows = awaitResults()
        assertEquals(listOf("original"), rows.map { it["id"]!!.jsonPrimitive.content })
        assertNull(pairings.permission(callerContext.packageName))
    }

    private fun launchCaller(id: String) {
        context.startActivity(Intent().apply {
            component = ComponentName(callerContext.packageName, NativeBatchCallerActivity::class.java.name)
            // Give each test a fresh caller instance and nonce receiver, even after a failed cold launch.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("target_package", context.packageName)
            putExtra("command_action", commandAction)
            putExtra("result_action", resultAction)
            putExtra("native_id", id)
        })
        signer = monitor.waitForActivityWithTimeout(20000)
        assertNotNull("Fixture must launch the signer with a result recipient", signer)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertEquals(callerContext.packageName, signer!!.callingPackage) }
    }

    private fun sendCommand(command: String, id: String = "", flags: Int = BATCH_FLAGS, shareIdentity: Boolean = false) {
        if (!::callerContext.isInitialized) return
        val acknowledgement = if (command == "request") CountDownLatch(1) else null
        commandLatch = acknowledgement
        context.sendBroadcast(Intent(commandAction).setPackage(callerContext.packageName).apply {
            putExtra("command", command)
            putExtra("native_id", id)
            putExtra("native_flags", flags)
            putExtra("share_identity", shareIdentity)
        })
        if (acknowledgement != null) {
            assertTrue("Fixture must submit the queued request", acknowledgement.await(15, TimeUnit.SECONDS))
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertApproval() {
        onView(withId(R.id.approveButton)).perform(scrollTo()).check(matches(isDisplayed()))
    }

    private fun assertUnattributedApproval() {
        assertApproval()
        onView(withId(R.id.batchCallerNote)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.nativePermissionControls)).check(matches(not(isDisplayed())))
    }

    private fun assertPosition(position: String) {
        instrumentation.runOnMainSync {
            val text = signer!!.findViewById<TextView>(R.id.batchPosition).text.toString()
            assertEquals(context.getString(R.string.batch_request_position, position.toInt(), 3), text)
        }
    }

    private fun chooseOnce() {
        onView(withId(R.id.approvalLifetimePicker)).perform(scrollTo(), click())
        onData(equalTo(context.getString(R.string.approval_once))).perform(click())
    }

    private fun approve() = onView(withId(R.id.approveButton)).perform(scrollTo(), click())

    private fun awaitResults(): List<kotlinx.serialization.json.JsonObject> {
        assertTrue("Caller must receive one batched result", resultLatch.await(15, TimeUnit.SECONDS))
        assertEquals(Activity.RESULT_OK, resultCode)
        val json = resultData?.getStringExtra("results")
        assertNotNull("Native batch result must contain the results array", json)
        return Json.parseToJsonElement(json!!).jsonArray.map { it.jsonObject }
    }

    private companion object {
        const val BATCH_FLAGS = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
}

/**
 * Separate APK fixture. AGP keeps the Kotlin runtime in the target APK, so this standalone
 * component deliberately uses nullable framework parameters and Java member calls only.
 * Its compiled bytecode must contain no kotlin.jvm.internal or kotlin.* helper calls.
 */
class NativeBatchCallerActivity : Activity() {
    private var targetPackage: String? = null
    private var resultAction: String? = null
    private var commandReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = getIntent().getStringExtra("target_package")
        resultAction = getIntent().getStringExtra("result_action")
        val commandAction = getIntent().getStringExtra("command_action")
        if (targetPackage === null || resultAction === null || commandAction === null) {
            finish()
            return
        }
        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, command: Intent?) {
                if (command === null) return
                val commandName = command.getStringExtra("command")
                if ("request".equals(commandName)) {
                    if (Build.VERSION.SDK_INT >= 35 && command.getBooleanExtra("share_identity", false)) {
                        val options: ActivityOptions? = ActivityOptions.makeBasic()
                        if (options !== null) {
                            options.setShareIdentityEnabled(true)
                            startActivity(request(command.getStringExtra("native_id"),
                                command.getIntExtra("native_flags", 0)), options.toBundle())
                        }
                    } else {
                        startActivity(request(command.getStringExtra("native_id"), command.getIntExtra("native_flags", 0)))
                    }
                    val acknowledgement = Intent(resultAction)
                    acknowledgement.setPackage(targetPackage)
                    acknowledgement.putExtra("command_ack", true)
                    sendBroadcast(acknowledgement)
                } else if ("finish".equals(commandName)) {
                    finish()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, IntentFilter(commandAction), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(commandReceiver, IntentFilter(commandAction))
        }
        receiverRegistered = true
        @Suppress("DEPRECATION")
        startActivityForResult(request(getIntent().getStringExtra("native_id"),
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), 44)
    }

    private fun request(id: String?, flags: Int): Intent? {
        val target = targetPackage
        if (target === null) return null
        val nativeRequest = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        nativeRequest.setComponent(ComponentName(target, "dev.forgesworn.cambium.nip55.SignerActivity"))
        nativeRequest.setFlags(flags)
        nativeRequest.putExtra("type", "get_public_key")
        nativeRequest.putExtra("id", id)
        return nativeRequest
    }

    @Deprecated("Framework callback required for the real native result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 44) {
            val response = Intent(resultAction)
            response.setPackage(targetPackage)
            response.putExtra("result_code", resultCode)
            response.putExtra("result_data", data)
            sendBroadcast(response)
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(commandReceiver)
        super.onDestroy()
    }
}
