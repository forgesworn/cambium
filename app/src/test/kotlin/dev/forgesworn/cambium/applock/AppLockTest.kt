package dev.forgesworn.cambium.applock

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {

    @Test
    fun `never authenticated this install always requires authentication`() {
        assertTrue(AppLock.requiresAuthentication(lastAuthenticatedAtMillis = null, nowMillis = 1_000L))
    }

    @Test
    fun `authenticating just now does not require authentication again immediately`() {
        assertFalse(AppLock.requiresAuthentication(lastAuthenticatedAtMillis = 1_000L, nowMillis = 1_000L))
    }

    @Test
    fun `within the grace window does not require authentication`() {
        val lastAuthenticatedAt = 1_000L
        val now = lastAuthenticatedAt + AppLock.GRACE_WINDOW_MILLIS - 1
        assertFalse(AppLock.requiresAuthentication(lastAuthenticatedAt, now))
    }

    @Test
    fun `exactly at the grace window boundary does not require authentication`() {
        val lastAuthenticatedAt = 1_000L
        val now = lastAuthenticatedAt + AppLock.GRACE_WINDOW_MILLIS
        assertFalse(AppLock.requiresAuthentication(lastAuthenticatedAt, now))
    }

    @Test
    fun `just past the grace window requires authentication`() {
        val lastAuthenticatedAt = 1_000L
        val now = lastAuthenticatedAt + AppLock.GRACE_WINDOW_MILLIS + 1
        assertTrue(AppLock.requiresAuthentication(lastAuthenticatedAt, now))
    }

    @Test
    fun `a custom grace window is honoured instead of the default`() {
        val lastAuthenticatedAt = 1_000L
        assertFalse(AppLock.requiresAuthentication(lastAuthenticatedAt, lastAuthenticatedAt + 5_000L, graceWindowMillis = 10_000L))
        assertTrue(AppLock.requiresAuthentication(lastAuthenticatedAt, lastAuthenticatedAt + 15_000L, graceWindowMillis = 10_000L))
    }

    @Test
    fun `a clock set backwards is treated as the grace window having expired, not still open`() {
        val lastAuthenticatedAt = 10_000L
        val now = 5_000L // now is before lastAuthenticatedAt
        assertTrue(AppLock.requiresAuthentication(lastAuthenticatedAt, now))
    }
}
