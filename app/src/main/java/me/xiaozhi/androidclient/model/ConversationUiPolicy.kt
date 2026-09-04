package me.xiaozhi.androidclient.model

fun UiState.resetConversationForRoleSwitch(): UiState = copy(
    chatMessages = emptyList(),
    draftMessage = "",
    lastSttText = "",
    lastTtsText = "",
    lastIncomingType = "",
    activeListeningMode = "",
    isRecording = false,
    isAssistantSpeaking = false,
    isTurnActive = false,
)

fun UiState.canRunWakeWordRecognizer(): Boolean =
    connectionStatus == ConnectionStatus.CONNECTED || isSilentTransportRecovery
