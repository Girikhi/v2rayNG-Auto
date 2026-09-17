package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerHealthRecord
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.ManualConfigMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerHealthMemoryTest {
    @Test
    fun keySurvivesSubscriptionRefreshAndRename() {
        val original = profile().apply {
            subscriptionId = "old-account"
            remarks = "Old server name"
        }
        val refreshed = original.copy(
            subscriptionId = "new-account",
            remarks = "Server 1",
            addedTime = original.addedTime + 10_000L,
        )

        assertEquals(ServerHealthMemory.key(original), ServerHealthMemory.key(refreshed))
    }

    @Test
    fun keySeparatesDifferentCredentialsAndVariantsOnTheSameIp() {
        val original = profile()
        val otherCredential = original.copy(password = "different-user-id")
        val fragment = original.copy(manualMode = ManualConfigMode.FRAGMENT)

        assertNotEquals(ServerHealthMemory.key(original), ServerHealthMemory.key(otherCredential))
        assertNotEquals(ServerHealthMemory.key(original), ServerHealthMemory.key(fragment))
    }

    @Test
    fun rememberedWorkingServersComeFirstAndRecentFailuresLast() {
        val now = 2_000_000_000L
        val working = ServerHealthRecord(120L, now - 1_000L)
        val failed = ServerHealthRecord(-1L, now - 1_000L)

        assertEquals(0, ServerHealthMemory.priority(working, now))
        assertEquals(1, ServerHealthMemory.priority(null, now))
        assertEquals(2, ServerHealthMemory.priority(failed, now))
    }

    @Test
    fun staleResultsAreRememberedButNotTrustedForFastSelection() {
        val now = 2_000_000_000L
        val stale = ServerHealthRecord(
            delayMillis = 120L,
            testedAtMillis = now - ServerHealthMemory.SUCCESS_REUSE_MILLIS - 1L,
        )

        assertFalse(ServerHealthMemory.isReusable(stale, now))
        assertEquals(1, ServerHealthMemory.priority(stale, now))
        assertFalse(ServerHealthMemory.canSkipSocketPreflight(stale, now))
    }

    @Test
    fun recentSuccessSkipsTheRedundantTcpPreflight() {
        val now = 2_000_000_000L
        val recent = ServerHealthRecord(90L, now - 30_000L)

        assertTrue(ServerHealthMemory.canSkipSocketPreflight(recent, now))
    }

    private fun profile() = ProfileItem(configType = EConfigType.VLESS).apply {
        server = "104.16.10.20"
        serverPort = "443"
        network = "ws"
        security = "tls"
        sni = "example.com"
        host = "example.com"
        path = "/connect"
        password = "user-id"
        fingerPrint = "chrome"
        manualMode = ManualConfigMode.ORIGINAL
    }
}
