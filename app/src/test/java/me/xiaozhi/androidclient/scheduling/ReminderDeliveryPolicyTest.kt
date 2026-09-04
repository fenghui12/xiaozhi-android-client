package me.xiaozhi.androidclient.scheduling

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderDeliveryPolicyTest {
    @Test
    fun `idle connected session sends immediately`() {
        assertDecision(ReminderDeliveryAction.SEND_NOW)
    }

    @Test
    fun `continuous listening gets a grace period before pausing`() {
        assertDecision(
            ReminderDeliveryAction.PAUSE_LISTENING_AFTER_GRACE,
            isRecording = true,
            isTurnActive = true,
        )
    }

    @Test
    fun `assistant playback finishes before reminder`() {
        assertDecision(
            ReminderDeliveryAction.WAIT_FOR_ASSISTANT,
            isAssistantSpeaking = true,
            isTurnActive = true,
        )
    }

    @Test
    fun `stalled current turn gets a grace period before interruption`() {
        assertDecision(
            ReminderDeliveryAction.INTERRUPT_CURRENT_TURN_AFTER_GRACE,
            isTurnActive = true,
        )
    }

    @Test
    fun `active reminder prevents a second simultaneous delivery`() {
        assertDecision(
            ReminderDeliveryAction.WAIT_FOR_ACTIVE_DELIVERY,
            hasActiveDelivery = true,
        )
    }

    @Test
    fun `disconnected reminder stays queued`() {
        assertDecision(
            ReminderDeliveryAction.WAIT_FOR_CONNECTION,
            connected = false,
        )
    }

    private fun assertDecision(
        expected: ReminderDeliveryAction,
        connected: Boolean = true,
        isRecording: Boolean = false,
        isAssistantSpeaking: Boolean = false,
        isTurnActive: Boolean = false,
        hasActiveDelivery: Boolean = false,
    ) {
        assertEquals(
            expected,
            ReminderDeliveryPolicy.decide(
                ReminderConversationState(
                    connected = connected,
                    isRecording = isRecording,
                    isAssistantSpeaking = isAssistantSpeaking,
                    isTurnActive = isTurnActive,
                    hasActiveDelivery = hasActiveDelivery,
                ),
            ),
        )
    }
}
