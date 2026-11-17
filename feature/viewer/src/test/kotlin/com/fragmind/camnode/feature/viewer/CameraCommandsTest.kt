package com.fragmind.camnode.feature.viewer

import com.fragmind.camnode.core.model.NodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraCommandsTest {
    private val first = NodeId("first")
    private val second = NodeId("second")

    @Test
    fun pendingIsImmediateAndDuplicateTouchesDoNotSendAnotherRequest() = runTest {
        val commands = CameraCommands(backgroundScope)
        val reply = CompletableDeferred<CameraCommandState>()
        var requests = 0
        assertTrue(commands.start(first) { requests++; reply.await() })
        assertEquals(CameraCommandState.PENDING, commands.states.value[first])
        assertFalse(commands.start(first) { requests++; CameraCommandState.CONFIRMED })
        runCurrent()
        assertEquals(1, requests)
        reply.complete(CameraCommandState.CONFIRMED)
        runCurrent()
        assertEquals(CameraCommandState.CONFIRMED, commands.states.value[first])
    }

    @Test
    fun aSlowCameraDoesNotBlockAnotherAndTimesOutWithoutRetryingTheMutation() = runTest {
        val commands = CameraCommands(backgroundScope)
        var requests = 0
        commands.start(first) { requests++; awaitCancellation() }
        commands.start(second) { CameraCommandState.CONFIRMED }
        runCurrent()
        assertEquals(CameraCommandState.CONFIRMED, commands.states.value[second])
        advanceTimeBy(9_999)
        assertEquals(CameraCommandState.PENDING, commands.states.value[first])
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CameraCommandState.UNCONFIRMED, commands.states.value[first])
        assertEquals(1, requests)
        assertTrue(commands.start(first) { CameraCommandState.CONFIRMED })
        runCurrent()
        assertEquals(CameraCommandState.CONFIRMED, commands.states.value[first])
    }

    @Test
    fun aPollStartedBeforeTheCommandCannotOverwriteItsConfirmation() = runTest {
        val commands = CameraCommands(backgroundScope)
        val before = commands.revision(first)
        commands.start(first) { CameraCommandState.CONFIRMED }
        val during = commands.revision(first)
        assertFalse(commands.acceptsPoll(first, before))
        assertFalse(commands.acceptsPoll(first, during))
        runCurrent()
        assertFalse(commands.acceptsPoll(first, before))
        assertFalse(commands.acceptsPoll(first, during))
        assertTrue(commands.acceptsPoll(first, commands.revision(first)))
    }

    @Test
    fun aConfirmedChangeWithASlowVideoTicketRemainsConfirmedAndCanBeQueried() = runTest {
        val commands = CameraCommands(backgroundScope)
        var acknowledged = false
        var requests = 0
        commands.start(first, onTimeout = { if (acknowledged) CameraCommandState.RECONNECTING else CameraCommandState.UNCONFIRMED }) {
            requests++
            acknowledged = true
            awaitCancellation()
        }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(CameraCommandState.RECONNECTING, commands.states.value[first])
        assertEquals(1, requests)
        assertTrue(commands.start(first) { CameraCommandState.CONFIRMED })
        runCurrent()
        assertEquals(CameraCommandState.CONFIRMED, commands.states.value[first])
    }

    @Test
    fun changingHouseholdsCancelsOldRequestsAndAllowsANewRequestForTheSameId() = runTest {
        val commands = CameraCommands(backgroundScope)
        var cancelled = false
        commands.start(first) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        runCurrent()
        val oldPoll = commands.revision(second)
        commands.clear()
        assertFalse(commands.acceptsPoll(second, oldPoll))
        assertTrue(commands.states.value.isEmpty())
        commands.start(first) { CameraCommandState.CONFIRMED }
        runCurrent()
        assertTrue(cancelled)
        assertEquals(CameraCommandState.CONFIRMED, commands.states.value[first])
    }
}
