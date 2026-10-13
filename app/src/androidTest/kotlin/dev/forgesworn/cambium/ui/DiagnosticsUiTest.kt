package dev.forgesworn.cambium.ui

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import java.io.File
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.applock.AppLockStore
import dev.forgesworn.cambium.log.ActivityLogIdentity
import dev.forgesworn.cambium.log.ActivityLogActivity
import dev.forgesworn.cambium.log.ActivityLogEntry
import dev.forgesworn.cambium.log.ActivityLogStore
import dev.forgesworn.cambium.pairing.PairingStore
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsUiTest {
    private lateinit var context: Context
    private lateinit var log: ActivityLogStore
    private var fixtureReady = false
    private var logWasEnabled = true
    private var lockWasEnabled = false
    private val privateCaller = "web:https://private-diagnostics.example.test"
    private val privateIdentity = "bunker://fixture?secret=keep-this-private"

    @Before
    fun prepareDisposableInstall() {
        context = ApplicationProvider.getApplicationContext()
        log = ActivityLogStore.getInstance(context)
        val pairings = PairingStore(context)
        assumeTrue("Use an empty disposable install", pairings.pairings().isEmpty())
        assumeTrue("Existing approvals must be preserved", pairings.allPermissions().isEmpty())
        assumeTrue("Existing activity must be preserved", log.entries().isEmpty())
        logWasEnabled = log.isEnabled()
        lockWasEnabled = AppLockStore(context).isEnabled()
        fixtureReady = true
        log.setEnabled(true)
        AppLockStore(context).setEnabled(false)
    }

    @After
    fun removeOwnSeededEntries() {
        if (!fixtureReady) return
        log.clear()
        log.setEnabled(logWasEnabled)
        AppLockStore(context).setEnabled(lockWasEnabled)
    }

    @Test
    fun filteredReviewShowsSafeDiagnosticsAndCancelStartsNoExternalActivity() {
        val firstReference = log.identityReferenceFor("a".repeat(64))!!
        val secondReference = log.identityReferenceFor("b".repeat(64))!!
        log.append(ActivityLogEntry(1000, privateCaller, "sign_event", 1, privateIdentity,
            ActivityLogEntry.Outcome.FAILED, ActivityLogEntry.FailureCategory.TIMEOUT, 1234,
            identityReference = firstReference))
        log.append(ActivityLogEntry(2000, "org.example.other", "sign_event", 7, privateIdentity,
            ActivityLogEntry.Outcome.SIGNED, durationMillis = 987, identityReference = firstReference))
        log.append(ActivityLogEntry(3000, "org.example.other", "nip44_decrypt", identityLabel = privateIdentity,
            outcome = ActivityLogEntry.Outcome.FAILED, failureCategory = ActivityLogEntry.FailureCategory.BUSY,
            durationMillis = 9999, identityReference = secondReference))
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (log.entries().size != 3 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertEquals("Asynchronous fixture writes must be visible before launch", 3, log.entries().size)
        val firstGroupPosition = ActivityLogIdentity.groupsFor(log.entries())
            .indexOfFirst { it.key == "reference:$firstReference" } + 1
        val selectedIdentity = context.getString(R.string.diagnostics_identity_numbered, privateIdentity, firstGroupPosition)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_CHOOSER).apply { addAction(Intent.ACTION_SEND) }
        val externalLaunches = Instrumentation.ActivityMonitor(filter, null, true)
        instrumentation.addMonitor(externalLaunches)
        try {
            ActivityScenario.launch(ActivityLogActivity::class.java).use {
                onView(withId(R.id.identityFilter)).perform(scrollTo(), click())
                onData(equalTo(selectedIdentity)).perform(click())
                onView(withId(R.id.outcomeFilter)).perform(scrollTo(), click())
                onData(equalTo(context.getString(R.string.activity_log_outcome_failed))).perform(click())
                onView(withId(R.id.logDiagnosticsValue)).perform(scrollTo()).check(matches(withText("Timeout · 1234 ms")))
                capture("diagnostics-filtered-log")
                onView(withId(R.id.shareReportButton)).perform(scrollTo(), click())
                onView(allOf(withId(android.R.id.message), isDisplayed())).inRoot(isDialog()).check { view, failure ->
                    if (failure != null) throw failure
                    val report = (view as TextView).text.toString()
                    assertTrue(report.contains("Entries: 1"))
                    assertTrue(report.contains("FAILED | TIMEOUT | 1234"))
                    assertTrue(report.contains("app-1 | identity-1"))
                    assertFalse(report.contains(privateCaller))
                    assertFalse(report.contains(privateIdentity))
                    assertFalse(report.contains("keep-this-private"))
                    assertFalse(report.contains(firstReference))
                    assertFalse(report.contains(secondReference))
                    assertFalse(report.contains("9999"))
                    assertFalse(report.contains("987"))
                }
                capture("diagnostics-report")
                onView(withText(android.R.string.cancel)).inRoot(isDialog()).check { view, failure ->
                    if (failure != null) throw failure
                    captureGeometry(view ?: error("Report Cancel control was not found"))
                }
                onView(withText(R.string.diagnostics_share)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
                onView(withText(android.R.string.cancel)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
                assertEquals("Review itself must not share", 0, externalLaunches.hits)
                onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
                instrumentation.waitForIdleSync()
                assertEquals("Cancelling the preview must not open a receiving app", 0, externalLaunches.hits)
                onView(withId(R.id.shareReportButton)).perform(scrollTo()).check(matches(isDisplayed()))
                assertEquals("Review must not remove stored entries", 3, log.entries().size)
            }
        } finally {
            instrumentation.removeMonitor(externalLaunches)
        }
    }

    private fun captureGeometry(button: View) {
        val geometry = buildString {
            var current: View? = button
            var depth = 0
            while (current != null && depth++ < 10) {
                val rect = Rect()
                current.getGlobalVisibleRect(rect)
                appendLine("${current.javaClass.simpleName} id=${current.id} " +
                    "size=${current.width}x${current.height} measured=${current.measuredWidth}x${current.measuredHeight} " +
                    "position=${current.left},${current.top},${current.right},${current.bottom} " +
                    "minimumHeight=${current.minimumHeight} layoutHeight=${current.layoutParams?.height} visible=$rect")
                current = current.parent as? View
            }
        }
        android.util.Log.i("CambiumUiGeometry", geometry)
        if (InstrumentationRegistry.getArguments().getString("capture_ui") != "true") return
        val folder = File(context.filesDir, "ui-review").apply { mkdirs() }
        File(folder, "diagnostics-report-geometry.txt").writeText(geometry)
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture_ui") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(250, 3000)
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = File(context.filesDir, "ui-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

}
