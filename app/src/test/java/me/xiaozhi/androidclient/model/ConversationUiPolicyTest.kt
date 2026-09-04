package me.xiaozhi.androidclient.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUiPolicyTest {
    @Test
    fun `role switch clears only conversation state`() {
        val role = RoleProfile(
            id = "xiaoming",
            displayName = "小明",
            deviceId = "device",
            clientId = "client",
            wakeWords = listOf("你好小明"),
        )
        val task = ScheduledTaskUi(
            id = "timer-1",
            kind = "定时提醒",
            message = "喝水",
            status = "计时中",
            remainingSeconds = 30,
        )
        val original = UiState(
            activeRoleId = role.id,
            roleProfiles = listOf(role),
            scheduledTasks = listOf(task),
            draftMessage = "未发送内容",
            lastSttText = "上一位用户说的话",
            lastTtsText = "上一位角色的回答",
            lastIncomingType = "tts",
            activeListeningMode = "realtime",
            isRecording = true,
            isAssistantSpeaking = true,
            isTurnActive = true,
            chatMessages = listOf(
                ChatMessage(1, ChatRole.USER, "旧消息", "09:00:00"),
            ),
        )

        val reset = original.resetConversationForRoleSwitch()

        assertTrue(reset.chatMessages.isEmpty())
        assertEquals("", reset.draftMessage)
        assertEquals("", reset.lastSttText)
        assertEquals("", reset.lastTtsText)
        assertEquals("", reset.lastIncomingType)
        assertEquals("", reset.activeListeningMode)
        assertFalse(reset.isRecording)
        assertFalse(reset.isAssistantSpeaking)
        assertFalse(reset.isTurnActive)
        assertEquals(listOf(role), reset.roleProfiles)
        assertEquals(listOf(task), reset.scheduledTasks)
    }

    @Test
    fun `wake word recognizer stays available during silent recovery`() {
        assertTrue(
            UiState(connectionStatus = ConnectionStatus.CONNECTED)
                .canRunWakeWordRecognizer(),
        )
        assertTrue(
            UiState(
                connectionStatus = ConnectionStatus.CONNECTING,
                isSilentTransportRecovery = true,
            ).canRunWakeWordRecognizer(),
        )
        assertFalse(
            UiState(connectionStatus = ConnectionStatus.CONNECTING)
                .canRunWakeWordRecognizer(),
        )
        assertFalse(
            UiState(connectionStatus = ConnectionStatus.FAILED)
                .canRunWakeWordRecognizer(),
        )
    }
}
