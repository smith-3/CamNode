package com.fragmind.camnode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodecTest {
    private val household = Household(
        id = HouseholdId("6a1f"),
        name = "Casa & jardín",
        secret = HouseholdSecret("AbCdEfGhIjKlMnOp_-12"),
        ownerKey = NodeKeys.generate().publicKey,
    )

    @Test
    fun roundTripsHouseholdAndSeedsIncludingIpv6() {
        val seeds = listOf(
            NodeEndpoint("100.101.102.103"),
            NodeEndpoint("fd7a:115c:a1e0::1", controlPort = 1000, rtspPort = 2000),
            NodeEndpoint("living-room.tail1234.ts.net"),
        )
        val invite = Invite(household, seeds)

        val decoded = InviteCodec.decode(InviteCodec.encode(invite))

        assertEquals(InviteParseResult.Valid(invite), decoded)
    }

    @Test
    fun rejectsForeignLinksAndMissingFields() {
        assertEquals(InviteParseResult.Invalid(InviteProblem.NOT_CAMNODE), InviteCodec.decode("https://example.com/join"))
        assertEquals(InviteParseResult.Invalid(InviteProblem.INCOMPLETE), InviteCodec.decode("camnode://join?v=1&id=x&name=y"))
        assertEquals(InviteParseResult.Invalid(InviteProblem.UNSUPPORTED_VERSION), InviteCodec.decode("camnode://join?v=2&id=x&name=y&secret=AbCdEfGhIjKlMnOp"))
    }

    @Test
    fun secretIsNeverPrintedInLogs() {
        assertEquals("HouseholdSecret(***)", household.secret.toString())
    }
}
