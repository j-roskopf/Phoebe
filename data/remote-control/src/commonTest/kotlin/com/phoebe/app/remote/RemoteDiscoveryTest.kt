package com.phoebe.app.remote

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteDiscoveryTest {

    @Test
    fun testDiscoveryQuerySerialization() {
        val query = DiscoveryQuery(protocolVersion = 1)
        val json = RemoteJson.encodeToString(DiscoveryQuery.serializer(), query)
        val decoded = RemoteJson.decodeFromString(DiscoveryQuery.serializer(), json)
        assertEquals(query, decoded)
    }

    @Test
    fun testDiscoveryResponseSerialization() {
        val payload = DiscoveryResponsePayload(
            name = "Living Room Mac",
            deviceId = "mac-unique-id",
            tcpPort = 8765,
            protocolVersion = 1,
        )
        val json = RemoteJson.encodeToString(DiscoveryResponsePayload.serializer(), payload)
        val decoded = RemoteJson.decodeFromString(DiscoveryResponsePayload.serializer(), json)
        assertEquals(payload, decoded)
    }

    @Test
    fun testDiscoveredHostModel() {
        val host = DiscoveredHost(
            name = "Living Room Mac",
            deviceId = "mac-unique-id",
            hostAddress = "192.168.1.100",
            tcpPort = 8765,
            protocolVersion = 1,
        )
        val json = RemoteJson.encodeToString(DiscoveredHost.serializer(), host)
        val decoded = RemoteJson.decodeFromString(DiscoveredHost.serializer(), json)
        assertEquals(host, decoded)
    }
}
