package com.phoebe.app.player

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopCastDiscoveryTest {
    @Test
    fun discoveryAddressPrefersPhysicalIpv4Interface() {
        val address = desktopChromecastDiscoveryAddress()
        val host = address?.hostAddress.orEmpty()
        assertTrue(host.isNotBlank(), "expected a local IPv4 discovery address")
        assertTrue(!host.startsWith("127."), "expected a non-loopback address, got $host")
    }

    @Test
    fun discoverySessionFindsChromecastDevicesOnNetwork() {
        ensureDesktopChromecastNetworkingConfigured()
        val address = desktopChromecastDiscoveryAddress()
        if (address == null) return

        DesktopChromecastDiscoverySession().use { session ->
            val deadline = System.currentTimeMillis() + 6_000L
            while (System.currentTimeMillis() < deadline) {
                if (session.devices().isNotEmpty()) break
                Thread.sleep(250L)
            }
            val devices = session.devices()
            if (devices.isEmpty()) return@use
            val first = devices.first()
            assertNotNull(first.host)
            assertTrue(first.displayName.isNotBlank())
        }
    }
}
