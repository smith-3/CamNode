package com.fragmind.camnode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NodeEndpointTest {
    @Test
    fun classifiesTailscaleAndLanAddresses() {
        assertEquals(NetworkKind.TAILNET, NodeEndpoint("100.101.1.2").network)
        assertEquals(NetworkKind.TAILNET, NodeEndpoint("fd7a:115c:a1e0::9").network)
        assertEquals(NetworkKind.TAILNET, NodeEndpoint("sala.tail1234.ts.net").network)
        assertEquals(NetworkKind.LAN, NodeEndpoint("192.168.77.163").network)
    }

    @Test
    fun prefersSameWifiOverTailscale() {
        val tailnet = NodeEndpoint("100.101.1.2")
        val lan = NodeEndpoint("192.168.1.20")
        assertEquals(listOf(lan, tailnet), listOf(tailnet, lan, tailnet).byPreference())
    }

    @Test
    fun discoveryTagIsStablePerHouseholdAndHidesTheSecret() {
        val home = Household(HouseholdId("casa"), "Casa", HouseholdSecret.generate())
        val sameHomeNewSecret = home.copy(secret = HouseholdSecret.generate())
        val otherHome = home.copy(id = HouseholdId("oficina"))

        assertEquals(home.discoveryTag, sameHomeNewSecret.discoveryTag)
        assertNotEquals(home.discoveryTag, otherHome.discoveryTag)
        assertEquals(12, home.discoveryTag.length)
    }
}
