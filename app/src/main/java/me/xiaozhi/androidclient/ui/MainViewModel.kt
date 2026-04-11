package me.xiaozhi.androidclient.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.xiaozhi.androidclient.audio.XiaozhiAudioEngine
import me.xiaozhi.androidclient.data.AppPreferences
import me.xiaozhi.androidclient.data.StoredConfig
import me.xiaozhi.androidclient.integration.TermuxCommandEvents
import me.xiaozhi.androidclient.integration.TermuxCommandResult
import me.xiaozhi.androidclient.integration.TermuxRunner
import me.xiaozhi.androidclient.model.ActivationInfo
import me.xiaozhi.androidclient.model.ChatMessage
import me.xiaozhi.androidclient.model.ChatRole
import me.xiaozhi.androidclient.model.ConnectParams
import me.xiaozhi.androidclient.model.ConnectionStatus
import me.xiaozhi.androidclient.model.ListeningMode
import me.xiaozhi.androidclient.model.LogLine
import me.xiaozhi.androidclient.model.OtaRequest
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.network.OtaConfigService
import me.xiaozhi.androidclient.network.XiaozhiRealtimeClient
import okhttp3.OkHttpClient
import org.json.JSONObject

private const val APP_VERSION = "0.3.0"
private const val LOG_TAG = "XiaozhiClient"
private const val UNBURNED_SERIAL_NUMBER = "未烧录"
private const val WAKE_WORD_DISABLED = "未启用"
private const val WAKE_WORD_STANDBY = "待命中"
private const val DEFAULT_AUDIO_ROUTE = "媒体输出：扬声器 / 输入：机身麦克风"

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val storedConfig = preferences.load()

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val otaConfigService = OtaConfigService(okHttpClient)
    private val realtimeClient = XiaozhiRealtimeClient(okHttpClient)
    private val audioEngine = XiaozhiAudioEngine(application)
    private val termuxRunner = TermuxRunner(application)

    private var pendingActivationInfo: ActivationInfo? = null
    private var pendingListeningMode: ListeningMode? = null
    private var pendingWakePhrase: String? = null
    private val pendingTextPrompts = ArrayDeque<String>()

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState = _uiState.asStateFlow()

    init {
        audioEngine.setRouteStatusListener(::updateAudioRouteStatus)
        audioEngine.setDebugListener(::addLog)
        audioEngine.setDebugOptions(
            loggingEnabled = storedConfig.debugLoggingEnabled,
            wavDumpEnabled = storedConfig.debugWavDumpEnabled,
        )
        viewModelScope.launch {
            realtimeClient.events.collect(::handleRealtimeEvent)
        }
        viewModelScope.launch {
            TermuxCommandEvents.events.collect(::handleTermuxCommandResult)
        }
        addLog("客户端已就绪")
        addLog("当前使用未烧录设备接入模式")
    }

    fun updateOtaUrl(value: String) = updateAndPersist { copy(otaUrl = value) }

    fun updateDeviceId(value: String) = updateAndPersist { copy(deviceId = value) }

    fun updateClientId(value: String) = updateAndPersist { copy(clientId = value) }

    fun updateWebsocketUrl(value: String) = updateAndPersist { copy(websocketUrl = value) }

    fun updateAuthToken(value: String) = updateAndPersist { copy(authToken = value) }

    fun updateProtocolVersion(value: String) = updateAndPersist { copy(protocolVersion = value) }

    fun updateMcpPayload(value: String) = updateAndPersist { copy(mcpPayload = value) }

    fun updateDraftMessage(value: String) {
        updateState { copy(draftMessage = value) }
    }

    fun importAssistantAvatar(uri: Uri) {
        runCatching {
            val avatarDir = File(getApplication<Application>().filesDir, "avatar").apply { mkdirs() }
            val targetFile = File(avatarDir, "assistant-avatar")
            val resolver = getApplication<Application>().contentResolver
            resolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
            targetFile.absolutePath
        }.onSuccess { path ->
            updateAndPersist { copy(assistantAvatarPath = path) }
            addLog("已更新小智头像")
        }.onFailure { error ->
            addLog("更新头像失败：${error.message.orEmpty()}")
        }
    }

    fun updateWakeWordEnabled(enabled: Boolean) {
        updateAndPersist {
            copy(
                wakeWordEnabled = enabled,
                wakeWordStatus = if (enabled) WAKE_WORD_STANDBY else WAKE_WORD_DISABLED,
            )
        }
        addLog(if (enabled) "语音唤醒已开启" else "语音唤醒已关闭")
    }

    fun updateWakeWords(value: String) = updateAndPersist { copy(wakeWords = value) }

    fun updateTermuxEnabled(enabled: Boolean) {
        updateAndPersist {
            copy(
                termuxEnabled = enabled,
                pythonRuntimeStatus = termuxRunner.statusLabel(enabled),
                termuxApiStatus = termuxRunner.termuxApiStatusLabel(enabled),
            )
        }
        addLog(if (enabled) "已启用 Python/MCP 运行入口" else "已关闭 Python/MCP 运行入口")
    }

    fun updatePythonPath(value: String) = updateAndPersist { copy(pythonPath = value) }

    fun updatePythonScriptPath(value: String) = updateAndPersist { copy(pythonScriptPath = value) }

    fun updatePythonWorkdir(value: String) = updateAndPersist { copy(pythonWorkdir = value) }

    fun updateTermuxApiCommand(value: String) = updateAndPersist { copy(termuxApiCommand = value) }

    fun updateTermuxApiArguments(value: String) = updateAndPersist { copy(termuxApiArguments = value) }

    fun updateDebugLoggingEnabled(enabled: Boolean) {
        audioEngine.setDebugOptions(
            loggingEnabled = enabled,
            wavDumpEnabled = uiState.value.debugWavDumpEnabled,
        )
        updateAndPersist { copy(debugLoggingEnabled = enabled) }
        addLog(if (enabled) "已开启调试日志" else "已关闭调试日志")
    }

    fun updateDebugWavDumpEnabled(enabled: Boolean) {
        audioEngine.setDebugOptions(
            loggingEnabled = uiState.value.debugLoggingEnabled,
            wavDumpEnabled = enabled,
        )
        updateAndPersist { copy(debugWavDumpEnabled = enabled) }
        addLog(if (enabled) "已开启 TTS 音频导出" else "已关闭 TTS 音频导出")
    }

    fun updateWakeWordStatus(status: String) {
        updateState { copy(wakeWordStatus = status) }
    }

    fun refreshPythonRuntimeStatus() {
        updateState {
            copy(
                pythonRuntimeStatus = termuxRunner.statusLabel(termuxEnabled),
                termuxApiStatus = termuxRunner.termuxApiStatusLabel(termuxEnabled),
            )
        }
    }

    fun runPythonScript() {
        val state = uiState.value
        termuxRunner.runPythonScript(
            pythonPath = state.pythonPath,
            scriptPath = state.pythonScriptPath,
            workdir = state.pythonWorkdir,
        ).onSuccess { message ->
            refreshPythonRuntimeStatus()
            addLog(message)
        }.onFailure { error ->
            refreshPythonRuntimeStatus()
            addLog("启动 Python 失败：${error.message.orEmpty()}")
        }
    }

    fun runTermuxApiCommand() {
        val state = uiState.value
        termuxRunner.runTermuxApiCommand(
            commandPath = state.termuxApiCommand,
            arguments = state.termuxApiArguments,
            workdir = state.pythonWorkdir,
        ).onSuccess { message ->
            refreshPythonRuntimeStatus()
            addLog(message)
        }.onFailure { error ->
            refreshPythonRuntimeStatus()
            addLog("璋冪敤 termux-api 澶辫触锛?{error.message.orEmpty()}")
        }
    }

    fun updateAudioRouteStatus(status: String) {
        val normalized = status.ifBlank { DEFAULT_AUDIO_ROUTE }
        val previous = uiState.value.audioRouteStatus
        updateState { copy(audioRouteStatus = normalized) }
        if (previous != normalized) {
            addLog("音频路由更新：$normalized")
        }
    }

    fun onWakeWordDetected(phrase: String) {
        if (uiState.value.isRecording) {
            return
        }
        pendingWakePhrase = phrase
        pendingListeningMode = ListeningMode.REALTIME
        updateState { copy(wakeWordStatus = "已唤醒：$phrase") }
        addLog("检测到唤醒词：$phrase")
        ensureReadyForConversation(trigger = "唤醒词")
    }

    fun onMicrophonePermissionDenied(reason: String) {
        addLog("录音权限被拒绝：$reason")
        if (reason == "wake_word") {
            updateState {
                copy(
                    wakeWordEnabled = false,
                    wakeWordStatus = "需要麦克风权限",
                )
            }
            persist()
        }
    }

    fun fetchOfficialConfig() {
        persist()
        val state = uiState.value
        updateState {
            copy(
                connectionStatus = ConnectionStatus.FETCHING_CONFIG,
                activationPending = false,
            )
        }
        addLog("正在获取官方配置：${state.otaUrl}")

        viewModelScope.launch {
            otaConfigService.fetchConfig(
                OtaRequest(
                    otaUrl = state.otaUrl,
                    deviceId = state.deviceId,
                    clientId = state.clientId,
                    serialNumber = null,
                    appVersion = APP_VERSION,
                ),
            ).onSuccess { result ->
                pendingActivationInfo = result.activation

                val websocketConfig = result.websocket
                val activationPending = result.activation != null && websocketConfig == null

                updateState {
                    copy(
                        websocketUrl = websocketConfig?.url ?: websocketUrl,
                        authToken = websocketConfig?.token ?: authToken,
                        protocolVersion = (websocketConfig?.version
                            ?: protocolVersion.toIntOrNull()
                            ?: 1).toString(),
                        activationMessage = result.activation?.message.orEmpty(),
                        activationCode = result.activation?.code.orEmpty(),
                        activationPending = activationPending,
                        activated = websocketConfig != null,
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                    )
                }

                if (websocketConfig != null) {
                    addLog("已收到 WebSocket 配置：${websocketConfig.url}")
                } else {
                    addLog("OTA 返回中没有 WebSocket 配置")
                }

                if (result.activation != null && websocketConfig == null) {
                    addLog("需要激活设备：${result.activation.code.orEmpty()}")
                    if (result.activation.challenge.isNullOrBlank()) {
                        addLog("请先去 xiaozhi.me 完成激活，然后重新获取配置")
                    } else {
                        addLog("服务端要求 challenge 激活，但 Android 没有烧录 HMAC 密钥")
                    }
                }

                persist()

                if ((pendingWakePhrase != null || pendingTextPrompts.isNotEmpty()) && websocketConfig != null) {
                    connect()
                }
            }.onFailure { error ->
                updateState { copy(connectionStatus = ConnectionStatus.FAILED) }
                addLog("获取 OTA 配置失败：${error.message.orEmpty()}")
            }
        }
    }

    fun retryActivation() {
        val activationInfo = pendingActivationInfo
        if (activationInfo == null) {
            addLog("当前没有待处理的激活信息")
            return
        }
        if (activationInfo.challenge.isNullOrBlank()) {
            addLog("正在重新获取 OTA 配置")
            fetchOfficialConfig()
            return
        }
        addLog("challenge 激活需要已烧录的序列号和 HMAC，Android 客户端不可用")
    }

    fun connect() {
        val state = uiState.value
        if (state.connectionStatus == ConnectionStatus.CONNECTING) {
            addLog("连接正在进行中")
            return
        }
        if (state.connectionStatus == ConnectionStatus.CONNECTED) {
            addLog("WebSocket 已连接")
            flushPendingActions()
            return
        }
        val protocolVersion = state.protocolVersion.toIntOrNull()
        if (state.websocketUrl.isBlank()) {
            addLog("WebSocket 地址为空，请先获取官方配置")
            updateState { copy(connectionStatus = ConnectionStatus.FAILED) }
            return
        }
        if (protocolVersion == null) {
            addLog("协议版本必须是整数")
            updateState { copy(connectionStatus = ConnectionStatus.FAILED) }
            return
        }
        if (state.activationPending) {
            addLog("设备还没有完成激活")
            return
        }

        persist()
        updateState {
            copy(
                connectionStatus = ConnectionStatus.CONNECTING,
                sessionId = "",
                serverSampleRate = "",
                serverFrameDuration = "",
                lastIncomingType = "",
                lastSttText = "",
                lastTtsText = "",
                isAssistantSpeaking = false,
                isTurnActive = false,
            )
        }
        addLog("正在连接：${state.websocketUrl}")

        viewModelScope.launch {
            realtimeClient.connect(
                ConnectParams(
                    url = state.websocketUrl,
                    token = state.authToken,
                    protocolVersion = protocolVersion,
                    deviceId = state.deviceId,
                    clientId = state.clientId,
                ),
            ).onFailure { error ->
                updateState { copy(connectionStatus = ConnectionStatus.FAILED) }
                addLog("连接失败：${error.message.orEmpty()}")
            }
        }
    }

    fun disconnect() {
        clearPendingConversation()
        realtimeClient.disconnect()
        finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "已断开连接")
        audioEngine.clearPlayback {
            updateState { copy(isAssistantSpeaking = it) }
        }
        updateState {
            copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                sessionId = "",
                isAssistantSpeaking = false,
                isTurnActive = false,
            )
        }
    }

    fun startListening(mode: ListeningMode) {
        if (uiState.value.connectionStatus != ConnectionStatus.CONNECTED) {
            addLog("请先连接服务端")
            pendingListeningMode = mode
            ensureReadyForConversation(trigger = "录音")
            return
        }
        if (audioEngine.isCapturing()) {
            addLog("录音已经在进行中")
            return
        }

        interruptCurrentTurn(reason = "listen_start")
        pendingListeningMode = null

        if (!realtimeClient.sendStartListening(mode)) {
            return
        }

        audioEngine.startCapture(
            mode = mode,
            onEncodedFrame = realtimeClient::sendAudioFrame,
            onAutoStop = {
                finishListening(
                    sendStop = true,
                    stopCapture = false,
                    keepTurnActive = true,
                    reason = "自动模式检测到静音，已停止录音",
                )
            },
            onRecordingChanged = { isRecording ->
                updateState {
                    copy(
                        isRecording = isRecording,
                        isTurnActive = isRecording || isTurnActive,
                        activeListeningMode = if (isRecording) mode.wireValue else "",
                        wakeWordStatus = when {
                            wakeWordEnabled && isRecording -> "会话中"
                            wakeWordEnabled -> WAKE_WORD_STANDBY
                            else -> wakeWordStatus
                        },
                    )
                }
            },
            onError = { message ->
                addLog(message)
                updateState {
                    copy(
                        isRecording = false,
                        isTurnActive = false,
                        activeListeningMode = "",
                    )
                }
            },
        )
        addLog("已开始录音：${mode.wireValue}")
    }

    fun stopListening() {
        pendingListeningMode = null
        finishListening(sendStop = true, stopCapture = true, keepTurnActive = true, reason = "已停止录音")
    }

    fun abortSpeaking() {
        clearPendingConversation()
        finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "已请求打断")
        audioEngine.clearPlayback {
            updateState { copy(isAssistantSpeaking = it) }
        }
        updateState { copy(isAssistantSpeaking = false) }
        if (realtimeClient.sendAbort()) {
            addLog("已向服务端发送打断请求")
        }
    }

    fun sendDraftMessage() {
        val prompt = uiState.value.draftMessage.trim()
        if (prompt.isBlank()) {
            return
        }
        updateState { copy(draftMessage = "") }
        addChatMessage(ChatRole.USER, prompt)
        pendingTextPrompts.addLast(prompt)
        updateState { copy(isTurnActive = true) }
        addLog("准备发送文字消息")
        ensureReadyForConversation(trigger = "文字消息")
    }

    fun sendMcp() {
        if (realtimeClient.sendMcp(uiState.value.mcpPayload)) {
            addLog("已发送 MCP 请求")
        }
    }

    fun clearLogs() {
        updateState { copy(logs = emptyList()) }
        addLog("日志已清空")
    }

    override fun onCleared() {
        realtimeClient.disconnect(notify = false)
        audioEngine.release()
        super.onCleared()
    }

    private fun finishListening(
        sendStop: Boolean,
        stopCapture: Boolean,
        keepTurnActive: Boolean,
        reason: String,
    ) {
        if (stopCapture) {
            viewModelScope.launch {
                audioEngine.stopCapture()
            }
        }
        if (sendStop) {
            realtimeClient.sendStopListening()
        }
        updateState {
            copy(
                isRecording = false,
                isTurnActive = isAssistantSpeaking || keepTurnActive,
                activeListeningMode = "",
                wakeWordStatus = if (wakeWordEnabled) WAKE_WORD_STANDBY else wakeWordStatus,
            )
        }
        addLog(reason)
    }

    private fun handleRealtimeEvent(event: XiaozhiRealtimeClient.RealtimeEvent) {
        when (event) {
            is XiaozhiRealtimeClient.RealtimeEvent.Log -> addLog(event.message)

            is XiaozhiRealtimeClient.RealtimeEvent.Connected -> {
                audioEngine.configurePlayback(
                    sampleRate = event.hello.sampleRate,
                    frameDurationMs = event.hello.frameDuration,
                )
                updateState {
                    copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        sessionId = event.hello.sessionId.orEmpty(),
                        serverSampleRate = event.hello.sampleRate?.toString().orEmpty(),
                        serverFrameDuration = event.hello.frameDuration?.toString().orEmpty(),
                        lastIncomingType = "hello",
                        isTurnActive = pendingWakePhrase != null || pendingTextPrompts.isNotEmpty() || pendingListeningMode != null,
                    )
                }
                flushPendingActions()
            }

            is XiaozhiRealtimeClient.RealtimeEvent.JsonMessage -> {
                parseServerMessage(event.type, event.rawText)
            }

            is XiaozhiRealtimeClient.RealtimeEvent.BinaryMessage -> {
                updateState { copy(lastIncomingType = "binary") }
                audioEngine.playOpusFrame(
                    opusFrame = event.payload,
                    onPlaybackChanged = { isPlaying ->
                        updateState { copy(isAssistantSpeaking = isPlaying) }
                    },
                    onError = ::addLog,
                )
            }

            is XiaozhiRealtimeClient.RealtimeEvent.Disconnected -> {
                finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "Socket 已关闭")
                audioEngine.clearPlayback {
                    updateState { copy(isAssistantSpeaking = it) }
                }
                updateState {
                    copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        sessionId = "",
                        isAssistantSpeaking = false,
                        isTurnActive = false,
                    )
                }
                addLog("Socket 已关闭：${event.code} ${event.reason}")
            }

            is XiaozhiRealtimeClient.RealtimeEvent.Error -> {
                finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "实时通道异常")
                updateState {
                    copy(
                        connectionStatus = ConnectionStatus.FAILED,
                        isAssistantSpeaking = false,
                        isTurnActive = false,
                    )
                }
                addLog(event.message)
            }
        }
    }

    private fun parseServerMessage(type: String?, rawText: String) {
        updateState { copy(lastIncomingType = type.orEmpty()) }

        when (type) {
            "tts" -> handleTtsMessage(rawText)
            "stt" -> handleSttMessage(rawText)
            else -> addLog("<= $rawText")
        }
    }

    private fun handleTtsMessage(rawText: String) {
        addLog("<= $rawText")
        val root = parseJson(rawText) ?: return
        val state = root.optString("state")
        val text = root.optString("text")

        when (state) {
            "start" -> {
                audioEngine.beginPlaybackSession()
                updateState { copy(isAssistantSpeaking = true, isTurnActive = true) }
                val shouldReleaseCaptureForPlayback =
                    uiState.value.activeListeningMode != ListeningMode.REALTIME.wireValue ||
                        isBluetoothMicActive()
                if (shouldReleaseCaptureForPlayback) {
                    finishListening(
                        sendStop = false,
                        stopCapture = true,
                        keepTurnActive = true,
                        reason = if (isBluetoothMicActive()) {
                            "检测到蓝牙麦克风占用，播报前已释放录音以恢复媒体音质"
                        } else {
                            "服务端开始播报，已结束本地录音"
                        },
                    )
                }
            }

            "sentence_start" -> {
                if (text.isNotBlank()) {
                    updateState { copy(lastTtsText = text) }
                    addChatMessage(ChatRole.ASSISTANT, text)
                }
            }

            "stop" -> {
                audioEngine.finishPlayback {
                    updateState {
                        copy(
                            isAssistantSpeaking = it,
                            isTurnActive = false,
                        )
                    }
                    addLog("播报完成")
                }
            }
        }
    }

    private fun handleSttMessage(rawText: String) {
        addLog("<= $rawText")
        val text = parseJson(rawText)?.optString("text").orEmpty()
        if (text.isNotBlank() && text != uiState.value.lastSttText) {
            updateState { copy(lastSttText = text, isTurnActive = true) }
            addChatMessage(ChatRole.USER, text)
        }
    }

    private fun ensureReadyForConversation(trigger: String) {
        when (uiState.value.connectionStatus) {
            ConnectionStatus.CONNECTED -> flushPendingActions()
            ConnectionStatus.CONNECTING,
            ConnectionStatus.FETCHING_CONFIG -> addLog("$trigger 已排队，等待连接完成")
            else -> {
                if (uiState.value.websocketUrl.isBlank()) {
                    addLog("$trigger 需要先获取官方配置")
                    fetchOfficialConfig()
                } else {
                    addLog("$trigger 需要先建立连接")
                    connect()
                }
            }
        }
    }

    private fun flushPendingActions() {
        if (uiState.value.connectionStatus != ConnectionStatus.CONNECTED) {
            return
        }

        pendingWakePhrase?.let { phrase ->
            interruptCurrentTurn(reason = "wake_word_interrupt")
            if (!realtimeClient.sendDetectText(phrase)) {
                return
            }
            addLog("已上报唤醒词：$phrase")
            pendingWakePhrase = null
        }

        while (pendingTextPrompts.isNotEmpty()) {
            interruptCurrentTurn(reason = "text_interrupt")
            val nextPrompt = pendingTextPrompts.peekFirst() ?: break
            if (!realtimeClient.sendDetectText(nextPrompt)) {
                return
            }
            pendingTextPrompts.removeFirst()
            addLog("文字消息已发送")
        }

        pendingListeningMode?.let(::startListening)
    }

    private fun interruptCurrentTurn(reason: String) {
        var interrupted = false
        if (uiState.value.isRecording) {
            finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "已切换到新的输入")
            interrupted = true
        }
        if (uiState.value.isAssistantSpeaking) {
            audioEngine.clearPlayback {
                updateState { copy(isAssistantSpeaking = it) }
            }
            updateState { copy(isAssistantSpeaking = false) }
            interrupted = true
        }
        if (interrupted && uiState.value.connectionStatus == ConnectionStatus.CONNECTED) {
            realtimeClient.sendAbort(reason)
        }
    }

    private fun clearPendingConversation() {
        pendingListeningMode = null
        pendingWakePhrase = null
        pendingTextPrompts.clear()
        updateState { copy(isTurnActive = false) }
    }

    private fun isBluetoothMicActive(): Boolean {
        return uiState.value.audioRouteStatus.contains("蓝牙麦克风")
    }

    private fun addChatMessage(role: ChatRole, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return
        }
        val timestamp = timestamp()
        updateState {
            val lastMessage = chatMessages.lastOrNull()
            if (lastMessage?.role == role && lastMessage.text == trimmed) {
                this
            } else {
                copy(
                    chatMessages = (chatMessages + ChatMessage(
                        id = System.currentTimeMillis(),
                        role = role,
                        text = trimmed,
                        timestamp = timestamp,
                    )).takeLast(100),
                )
            }
        }
    }

    private fun parseJson(rawText: String): JSONObject? {
        return runCatching { JSONObject(rawText) }.getOrNull()
    }

    private fun handleTermuxCommandResult(result: TermuxCommandResult) {
        val label = result.label.ifBlank { "Termux" }
        val exitCode = result.exitCode?.toString() ?: "?"
        val errorCode = result.errorCode?.let { ", err=$it" }.orEmpty()
        addLog("$label 缁撴潫锛宔xit=$exitCode$errorCode")
        if (result.stdout.isNotBlank()) {
            addLog("$label stdout: ${result.stdout.trim()}")
        }
        if (result.stderr.isNotBlank()) {
            addLog("$label stderr: ${result.stderr.trim()}")
        }
        if (!result.errorMessage.isNullOrBlank()) {
            addLog("$label error: ${result.errorMessage}")
        }
    }

    private fun loadInitialState(): UiState {
        return UiState(
            otaUrl = storedConfig.otaUrl,
            deviceId = storedConfig.deviceId,
            clientId = storedConfig.clientId,
            serialNumber = UNBURNED_SERIAL_NUMBER,
            assistantAvatarPath = storedConfig.assistantAvatarPath,
            websocketUrl = storedConfig.websocketUrl,
            authToken = storedConfig.authToken,
            protocolVersion = storedConfig.protocolVersion,
            mcpPayload = storedConfig.mcpPayload,
            activated = storedConfig.websocketUrl.isNotBlank(),
            wakeWordEnabled = storedConfig.wakeWordEnabled,
            wakeWords = storedConfig.wakeWords,
            wakeWordStatus = if (storedConfig.wakeWordEnabled) WAKE_WORD_STANDBY else WAKE_WORD_DISABLED,
            termuxEnabled = storedConfig.termuxEnabled,
            pythonPath = storedConfig.pythonPath,
            pythonScriptPath = storedConfig.pythonScriptPath,
            pythonWorkdir = storedConfig.pythonWorkdir,
            pythonRuntimeStatus = termuxRunner.statusLabel(storedConfig.termuxEnabled),
            termuxApiCommand = storedConfig.termuxApiCommand,
            termuxApiArguments = storedConfig.termuxApiArguments,
            termuxApiStatus = termuxRunner.termuxApiStatusLabel(storedConfig.termuxEnabled),
            debugLoggingEnabled = storedConfig.debugLoggingEnabled,
            debugWavDumpEnabled = storedConfig.debugWavDumpEnabled,
        )
    }

    private fun persist() {
        preferences.save(
            StoredConfig(
                otaUrl = uiState.value.otaUrl,
                deviceId = uiState.value.deviceId,
                clientId = uiState.value.clientId,
                assistantAvatarPath = uiState.value.assistantAvatarPath,
                websocketUrl = uiState.value.websocketUrl,
                authToken = uiState.value.authToken,
                protocolVersion = uiState.value.protocolVersion,
                mcpPayload = uiState.value.mcpPayload,
                wakeWordEnabled = uiState.value.wakeWordEnabled,
                wakeWords = uiState.value.wakeWords,
                termuxEnabled = uiState.value.termuxEnabled,
                pythonPath = uiState.value.pythonPath,
                pythonScriptPath = uiState.value.pythonScriptPath,
                pythonWorkdir = uiState.value.pythonWorkdir,
                termuxApiCommand = uiState.value.termuxApiCommand,
                termuxApiArguments = uiState.value.termuxApiArguments,
                debugLoggingEnabled = uiState.value.debugLoggingEnabled,
                debugWavDumpEnabled = uiState.value.debugWavDumpEnabled,
            ),
        )
    }

    private fun addLog(message: String) {
        val timestamp = timestamp()
        Log.d(LOG_TAG, "[$timestamp] $message")
        updateState {
            copy(logs = (logs + LogLine(timestamp, message)).takeLast(300))
        }
    }

    private fun updateAndPersist(update: UiState.() -> UiState) {
        updateState(update)
        persist()
    }

    private fun updateState(update: UiState.() -> UiState) {
        _uiState.update(update)
    }

    private fun timestamp(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
    }
}
