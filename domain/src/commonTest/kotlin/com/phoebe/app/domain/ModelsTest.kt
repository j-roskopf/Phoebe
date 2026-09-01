package com.phoebe.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ModelsTest {

    private fun session(
        token: String = "token",
        userName: String = "listener",
        userId: String? = null,
        providerType: MediaProviderType = MediaProviderType.Plex,
        serverId: String? = null,
    ): PlexSession = PlexSession(
        token = token,
        userName = userName,
        userId = userId,
        providerType = providerType,
        selectedServer = serverId?.let { PlexServer(id = it, name = "Server", uri = "http://host", owned = true) },
    )

    @Test
    fun remoteAccountIdentityNullWhenSessionMissingOrSignedOut() {
        assertNull((null as PlexSession?).remoteAccountIdentity())
        assertNull(session(token = "").remoteAccountIdentity())
    }

    @Test
    fun remoteAccountIdentityNullWhenNoUserIdentity() {
        assertNull(session(userName = "", userId = null).remoteAccountIdentity())
    }

    @Test
    fun remoteAccountIdentityPrefersUserIdOverUserName() {
        val byId = session(userId = "acct-1", userName = "listener").remoteAccountIdentity()
        val byName = session(userId = null, userName = "listener").remoteAccountIdentity()
        assertNotEquals(byId, byName)
    }

    @Test
    fun remoteAccountIdentityMatchesForSameAccountAndServer() {
        val a = session(userId = "acct-1", serverId = "server-1").remoteAccountIdentity()
        val b = session(userId = "acct-1", serverId = "server-1").remoteAccountIdentity()
        assertEquals(a, b)
    }

    @Test
    fun remoteAccountIdentityDiffersAcrossServersForSameUsername() {
        val serverOne = session(userId = null, userName = "listener", serverId = "server-1").remoteAccountIdentity()
        val serverTwo = session(userId = null, userName = "listener", serverId = "server-2").remoteAccountIdentity()
        assertNotEquals(serverOne, serverTwo)
    }

    @Test
    fun remoteAccountIdentityDiffersAcrossProviders() {
        val plex = session(userId = "acct-1", providerType = MediaProviderType.Plex).remoteAccountIdentity()
        val jellyfin = session(userId = "acct-1", providerType = MediaProviderType.Jellyfin).remoteAccountIdentity()
        assertNotEquals(plex, jellyfin)
    }
}
