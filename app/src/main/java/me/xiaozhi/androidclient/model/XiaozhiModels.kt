package me.xiaozhi.androidclient.model

import org.json.JSONObject

data class OtaRequest(
    val otaUrl: String,
    val deviceId: String,
    val clientId: String,
    val serialNumber: String?,
    val appVersion: String,
)

data class WebsocketConfig(
    val url: String,
    val token: String?,
    val version: Int,
)

data class ActivationInfo(
    val message: String?,
    val code: String?,
    val challenge: String?,
    val timeoutMs: Int?,
)

data class OtaConfigResult(
    val websocket: WebsocketConfig?,
    val activation: ActivationInfo?,
    val rawBody: String,
)

data class ConnectParams(
    val url: String,
    val token: String?,
    val protocolVersion: Int,
    val deviceId: String,
    val clientId: String,
)

data class ServerHello(
    val sessionId: String?,
    val sampleRate: Int?,
    val frameDuration: Int?,
    val raw: JSONObject,
)

enum class ListeningMode(val wireValue: String) {
    AUTO("auto"),
    MANUAL("manual"),
    REALTIME("realtime"),
}

enum class ConnectionStatus {
    DISCONNECTED,
    FETCHING_CONFIG,
    ACTIVATING,
    CONNECTING,
    CONNECTED,
    FAILED,
}

data class LogLine(
    val timestamp: String,
    val message: String,
)

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val timestamp: String,
)

data class ScheduledTaskUi(
    val id: String,
    val kind: String,
    val message: String,
    val status: String,
    val remainingSeconds: Long?,
)

data class UiState(
    val otaUrl: String = "",
    val deviceId: String = "",
    val clientId: String = "",
    val serialNumber: String = "",
    val assistantAvatarPath: String = "",
    val idleVideoPath: String = "",
    val greetingVideoPath: String = "",
    val listeningVideoPath: String = "",
    val speakingVideoPath: String = "",
    val activeRoleAvatarPath: String = "",
    val activeRoleDigitalHumanReady: Boolean = false,
    val activeRoleIdleVideoPath: String = "",
    val activeRoleGreetingVideoPath: String = "",
    val activeRoleListeningVideoPath: String = "",
    val activeRoleSpeakingVideoPath: String = "",
    val websocketUrl: String = "",
    val authToken: String = "",
    val protocolVersion: String = "1",
    val mcpPayload: String = "",
    val activationMessage: String = "",
    val activationCode: String = "",
    val activationPending: Boolean = false,
    val activated: Boolean = false,
    val sessionId: String = "",
    val serverSampleRate: String = "",
    val serverFrameDuration: String = "",
    val lastIncomingType: String = "",
    val lastSttText: String = "",
    val lastTtsText: String = "",
    val audioRouteStatus: String = "媒体输出：扬声器 / 输入：机身麦克风",
    val isRecording: Boolean = false,
    val isAssistantSpeaking: Boolean = false,
    val isTurnActive: Boolean = false,
    val activeListeningMode: String = "",
    val wakeWordEnabled: Boolean = false,
    val wakeWords: String = "",
    val roleWakeWords: String = "",
    val activeRoleName: String = "小智",
    val primaryRoleName: String = "小智",
    val activeRoleId: String = "xiaozhi",
    val roleProfiles: List<RoleProfile> = emptyList(),
    val wakeWordStatus: String = "",
    val termuxEnabled: Boolean = false,
    val pythonPath: String = "",
    val pythonScriptPath: String = "",
    val pythonWorkdir: String = "",
    val pythonRuntimeStatus: String = "",
    val termuxApiCommand: String = "",
    val termuxApiArguments: String = "",
    val termuxApiStatus: String = "",
    val debugLoggingEnabled: Boolean = false,
    val debugWavDumpEnabled: Boolean = false,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isSilentTransportRecovery: Boolean = false,
    val draftMessage: String = "",
    val chatMessages: List<ChatMessage> = emptyList(),
    val scheduledTasks: List<ScheduledTaskUi> = emptyList(),
    val appVersionName: String = "1.2.1",
    val appVersionCode: Int = 5,
    val updateCheckStatus: String = "",
    val availableUpdate: me.xiaozhi.androidclient.ota.OtaVersionInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val downloadProgressPercent: Int = 0,
    val logs: List<LogLine> = emptyList(),
)
