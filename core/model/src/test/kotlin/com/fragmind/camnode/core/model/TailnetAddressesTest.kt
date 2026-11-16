package com.fragmind.camnode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailnetAddressesTest {
    @Test
    fun prefersTailnetIpv4OverLanAndIpv6() {
        val candidates = listOf("192.168.1.20", "fd7a:115c:a1e0::5%tun0", "100.88.1.2", "10.0.0.4")
        assertEquals("100.88.1.2", TailnetAddresses.pick(candidates))
    }

    @Test
    fun fallsBackToTailnetIpv6() {
        assertEquals("fd7a:115c:a1e0::5", TailnetAddresses.pick(listOf("192.168.1.20", "fd7a:115c:a1e0::5")))
    }

    @Test
    fun ignoresPublicHundredBlockOutsideCgnat() {
        assertNull(TailnetAddresses.pick(listOf("100.128.0.1", "100.63.255.255")))
    }
}
