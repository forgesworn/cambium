package dev.forgesworn.cambium.ui

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.textfield.TextInputEditText
import dev.forgesworn.cambium.MainActivity
import dev.forgesworn.cambium.R
import dev.forgesworn.cambium.applock.AppLockStore
import dev.forgesworn.cambium.pairing.PairingStore
import dev.forgesworn.cambium.pairing.PermissionScope
import dev.forgesworn.cambium.pairing.PermissionScopeEditor
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PermissionScopeEditorTest {
    private lateinit var context: Context
    private var fixtureReady = false
    private var lockWasEnabled = false

    @Before
    fun prepareDisposableInstall() {
        context = ApplicationProvider.getApplicationContext()
        val pairings = PairingStore(context)
        assumeTrue("Use an empty disposable install", pairings.pairings().isEmpty())
        assumeTrue("Existing approvals must be preserved", pairings.allPermissions().isEmpty())
        lockWasEnabled = AppLockStore(context).isEnabled()
        fixtureReady = true
        AppLockStore(context).setEnabled(false)
    }

    @After
    fun restoreAppLock() {
        if (fixtureReady) AppLockStore(context).setEnabled(lockWasEnabled)
    }

    @Test
    fun savingAnEmptySigningKindSetWithoutChangesDoesNotBroadenSigning() {
        val saved = AtomicReference<PermissionScope?>()
        val initial = PermissionScope(setOf("sign_event", "nip44_decrypt"), emptySet())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { PermissionScopeEditor.show(it, initial) { scope -> saved.set(scope) } }
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
            onView(withText(R.string.save_button)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
            onView(withText(R.string.scope_sign)).inRoot(isDialog()).check(matches(not(isChecked())))
            onView(withText(R.string.scope_nip44_decrypt)).inRoot(isDialog()).check(matches(isChecked()))
            onView(withText(R.string.save_button)).inRoot(isDialog()).perform(click())
            val result = saved.get()
            assertNotNull("Save must return the visible selection", result)
            assertFalse(result!!.allows("sign_event", 1))
            assertFalse(result.allows("sign_event", 22242))
            assertFalse(result.allows("sign_event", null))
            assertTrue(result.allows("nip44_decrypt"))
        }
    }

    @Test
    fun malformedKindsKeepEditorOpenAndValidReplacementSelectsOnlyExactKinds() {
        val saved = AtomicReference<PermissionScope?>()
        val initial = PermissionScope(setOf("sign_event"), setOf(1))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { PermissionScopeEditor.show(it, initial) { scope -> saved.set(scope) } }
            onView(isAssignableFrom(TextInputEditText::class.java)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("1, invalid, 7"), closeSoftKeyboard())
            onView(withText(R.string.save_button)).inRoot(isDialog()).perform(click())
            assertNull("Invalid kinds cannot save a broader grant", saved.get())
            onView(withText(R.string.scope_kinds_invalid)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
            onView(withText(R.string.save_button)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
            onView(isAssignableFrom(TextInputEditText::class.java)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("1, 7, 22242"), closeSoftKeyboard())
            onView(withText(R.string.save_button)).inRoot(isDialog()).perform(click())
            val result = saved.get()
            assertNotNull(result)
            assertEquals(setOf("sign_event"), result!!.methods)
            assertEquals(setOf(1, 7, 22242), result.signEventKinds)
            assertTrue(result.allows("sign_event", 7))
            assertFalse(result.allows("sign_event", 4))
            assertFalse(result.allows("sign_event", null))
        }
    }
}
