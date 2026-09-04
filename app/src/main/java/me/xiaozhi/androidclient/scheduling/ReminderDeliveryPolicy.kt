package me.xiaozhi.androidclient.scheduling

enum class ReminderDeliveryAction {
    WAIT_FOR_CONNECTION,
    WAIT_FOR_ACTIVE_DELIVERY,
    WAIT_FOR_ASSISTANT,
    INTERRUPT_CURRENT_TURN_AFTER_GRACE,
    PAUSE_LISTENING_AFTER_GRACE,
    SEND_NOW,
}

data class ReminderConversationState(
    val connected: Boolean,
    val isRecording: Boolean,
    val isAssistantSpeaking: Boolean,
    val isTurnActive: Boolean,
    val hasActiveDelivery: Boolean,
)

object ReminderDeliveryPolicy {
    fun decide(state: ReminderConversationState): ReminderDeliveryAction = when {
        !state.connected -> ReminderDeliveryAction.WAIT_FOR_CONNECTION
        state.hasActiveDelivery -> ReminderDeliveryAction.WAIT_FOR_ACTIVE_DELIVERY
        state.isAssistantSpeaking -> ReminderDeliveryAction.WAIT_FOR_ASSISTANT
        state.isRecording -> ReminderDeliveryAction.PAUSE_LISTENING_AFTER_GRACE
        state.isTurnActive -> ReminderDeliveryAction.INTERRUPT_CURRENT_TURN_AFTER_GRACE
        else -> ReminderDeliveryAction.SEND_NOW
    }
}
