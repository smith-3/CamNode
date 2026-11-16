package com.fragmind.camnode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private val salt = PinHash.newSalt()
    private val locked = AppLockState(pinHash = PinHash.hash("2580", salt), salt = salt)

    @Test
    fun onlyTheSaltedHashIsKeptAndItMatchesOnlyThePin() {
        assertFalse(locked.pinHash!!.contains("2580"))
        assertNotEquals(PinHash.hash("2580", PinHash.newSalt()), locked.pinHash)
        assertTrue(PinHash.matches("2580", salt, locked.pinHash!!))
        assertFalse(PinHash.matches("2581", salt, locked.pinHash!!))
    }

    @Test
    fun theRightPinUnlocksAndClearsFailures() {
        val (_, afterFailure) = PinThrottle.check(locked, "0000", nowMillis = 0)
        val (result, after) = PinThrottle.check(afterFailure, "2580", nowMillis = 1)
        assertEquals(PinCheck.Granted, result)
        assertEquals(0, after.failedAttempts)
    }

    @Test
    fun fiveFailuresLockOutAndEachRoundDoublesTheWait() {
        var state = locked
        repeat(4) { attempt ->
            val (result, next) = PinThrottle.check(state, "0000", nowMillis = 0)
            assertEquals(PinCheck.Denied(4 - attempt), result)
            state = next
        }
        val (fifth, lockedOut) = PinThrottle.check(state, "0000", nowMillis = 0)
        assertEquals(PinCheck.LockedOut(30_000), fifth)

        val (duringLockout, _) = PinThrottle.check(lockedOut, "2580", nowMillis = 29_999)
        assertTrue("even the right PIN waits", duringLockout is PinCheck.LockedOut)

        state = lockedOut
        repeat(5) { state = PinThrottle.check(state, "0000", nowMillis = 30_000).second }
        assertEquals(30_000 + 60_000, state.lockedUntilMillis)
    }
}
