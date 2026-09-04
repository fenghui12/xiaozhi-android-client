package me.xiaozhi.androidclient.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoleWakeWordMatcherTest {
    private val xiaozhi = role("xiaozhi", "我是小智", "小智小智")
    private val peiqi = role("peiqi", "你好佩奇")

    @Test
    fun `configured phrase resolves to its owner independent of active role`() {
        assertEquals("xiaozhi", RoleWakeWordMatcher.findOwner(listOf(xiaozhi, peiqi), "我是小智")?.id)
        assertEquals("peiqi", RoleWakeWordMatcher.findOwner(listOf(xiaozhi, peiqi), "你好佩奇")?.id)
    }

    @Test
    fun `unassigned stale phrase does not fall back to a role`() {
        assertNull(RoleWakeWordMatcher.findOwner(listOf(xiaozhi, peiqi), "你好小智"))
    }

    @Test
    fun `partial phrase does not claim a role`() {
        assertNull(RoleWakeWordMatcher.findOwner(listOf(xiaozhi, peiqi), "小智"))
    }

    private fun role(id: String, vararg wakeWords: String) = RoleProfile(
        id = id,
        displayName = id,
        deviceId = "device-$id",
        clientId = "client-$id",
        wakeWords = wakeWords.toList(),
    )
}
