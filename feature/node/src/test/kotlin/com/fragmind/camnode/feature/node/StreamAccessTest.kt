package com.fragmind.camnode.feature.node

import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.RtspCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAccessTest {
    private var now = 1_000L
    private val tickets = TicketBook(clock = { now }, validityMillis = 10_000)
    private val viewer = NodeId("viewer")
    private val allowed = mutableSetOf(viewer)
    private val secured = StreamAccess(legacySecret = null, canView = { it in allowed })

    @Test
    fun eachViewerGetsAStableTicketOfItsOwn() {
        val first = tickets.issue(viewer)
        assertEquals(first, tickets.issue(viewer))
        assertNotEquals(first.password, tickets.issue(NodeId("other")).password)
        assertTrue(secured.accepts(tickets, first.user, first.password))
    }

    @Test
    fun wrongPasswordsExpiredTicketsAndRevokedViewersAreRefused() {
        val ticket = tickets.issue(viewer)
        assertFalse(secured.accepts(tickets, ticket.user, "adivinanza"))
        allowed.clear()
        assertFalse(secured.accepts(tickets, ticket.user, ticket.password))
        allowed += viewer
        now += 10_001
        assertFalse(secured.accepts(tickets, ticket.user, ticket.password))
    }

    @Test
    fun theSharedSecretOnlyOpensStreamsInLegacyHouseholds() {
        val secret = HouseholdSecret.generate()
        assertFalse(secured.accepts(tickets, RtspCredentials.USER, secret.value))
        assertTrue(StreamAccess(secret, canView = { false }).accepts(tickets, RtspCredentials.USER, secret.value))
    }
}
