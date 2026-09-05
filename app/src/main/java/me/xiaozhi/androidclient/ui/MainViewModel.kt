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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.xiaozhi.androidclient.audio.XiaozhiAudioEngine
import me.xiaozhi.androidclient.camera.CameraVisionTool
import me.xiaozhi.androidclient.data.AppPreferences
import me.xiaozhi.androidclient.data.RoleProfileRepository
import me.xiaozhi.androidclient.data.StoredConfig
import me.xiaozhi.androidclient.integration.TermuxCommandEvents
import me.xiaozhi.androidclient.integration.TermuxCommandResult
import me.xiaozhi.androidclient.integration.TermuxRunner
import me.xiaozhi.androidclient.integration.NanoSerialBridge
import me.xiaozhi.androidclient.mcp.McpCameraServer
import me.xiaozhi.androidclient.model.ActivationInfo
import me.xiaozhi.androidclient.model.ChatMessage
import me.xiaozhi.androidclient.model.ChatRole
import me.xiaozhi.androidclient.model.ConnectParams
import me.xiaozhi.androidclient.model.ConnectionStatus
import me.xiaozhi.androidclient.model.ListeningMode
import me.xiaozhi.androidclient.model.LogLine
import me.xiaozhi.androidclient.model.OtaRequest
import me.xiaozhi.androidclient.model.RoleProfile
import me.xiaozhi.androidclient.model.DigitalHumanSlot
import me.xiaozhi.androidclient.model.hasCompleteDigitalHuman
import me.xiaozhi.androidclient.model.RoleWakeWordMatcher
import me.xiaozhi.androidclient.model.ScheduledTaskUi
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.model.resetConversationForRoleSwitch
import me.xiaozhi.androidclient.network.NetworkTimeSynchronizer
import me.xiaozhi.androidclient.network.OtaConfigService
import me.xiaozhi.androidclient.network.XiaozhiRealtimeClient
import me.xiaozhi.androidclient.scheduling.ReminderConversationState
import me.xiaozhi.androidclient.scheduling.ReminderDeliveryAction
import me.xiaozhi.androidclient.scheduling.ReminderDeliveryPolicy
import me.xiaozhi.androidclient.scheduling.ReminderScheduler
import me.xiaozhi.androidclient.scheduling.ReminderKind
import me.xiaozhi.androidclient.scheduling.ScheduledReminder
import me.xiaozhi.androidclient.scheduling.SupervisionPhase
import me.xiaozhi.androidclient.scheduling.SupervisionPolicy
import me.xiaozhi.androidclient.scheduling.SupervisionVisionDecisionParser
import me.xiaozhi.androidclient.scheduling.SupervisionVisionStatus
import okhttp3.OkHttpClient
import org.json.JSONObject

private const val APP_VERSION = "0.3.0"
private const val LOG_TAG = "XiaozhiClient"
private const val UNBURNED_SERIAL_NUMBER = "未烧录"
private const val WAKE_WORD_DISABLED = "未启用"
private const val WAKE_WORD_STANDBY = "待命中"
private const val DEFAULT_AUDIO_ROUTE = "媒体输出：扬声器 / 输入：机身麦克风"
private const val AUTO_START_DELAY_MS = 600L
private const val ACTIVATION_POLL_INTERVAL_MS = 4_000L
private const val ACTIVATION_POLL_MAX_ATTEMPTS = 90
private const val AUTO_RECONNECT_DELAY_MS = 1_500L
private const val GOODBYE_DISCONNECT_WINDOW_MS = 5_000L
private const val REMINDER_LISTENING_GRACE_MS = 3_000L
private const val REMINDER_CURRENT_TURN_GRACE_MS = 5_000L
private const val REMINDER_SEND_RETRY_MS = 1_500L
private const val ACTIVE_GREETING_CODE_PHRASE = "【主动招呼】"
private const val TIMER_CODE_PHRASE = "【定时提醒】"
private const val SUPERVISION_START_CODE_PHRASE = "【监督提醒】"
private const val SUPERVISION_RESULT_CODE_PHRASE = "【监督结果】"

private data class ScheduledPrompt(
    val reminderId: String,
    val reminderKind: ReminderKind,
    val text: String,
    val resumeListeningAfterDelivery: Boolean = true,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val storedConfig = preferences.load()
    private val roleProfileRepository = RoleProfileRepository(application)
    private val digitalHumanAssets = me.xiaozhi.androidclient.digitalhuman.DigitalHumanAssetManager(application)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    val appUpdateManager = me.xiaozhi.androidclient.ota.AppUpdateManager(application, okHttpClient)
    private val otaConfigService = OtaConfigService(okHttpClient)
    private val realtimeClient = XiaozhiRealtimeClient(okHttpClient)
    private val networkTimeSynchronizer = NetworkTimeSynchronizer()
    private val audioEngine = XiaozhiAudioEngine(application)
    private val termuxRunner = TermuxRunner(application)
    private val cameraVisionTool = CameraVisionTool(
        context = application,
        okHttpClient = okHttpClient,
        deviceId = { activeRoleProfile().deviceId },
        clientId = { activeRoleProfile().clientId },
    )
    private var pendingActivationInfo: ActivationInfo? = null
    private var pendingListeningMode: ListeningMode? = null
    private var pendingWakePhrase: String? = null
    private var activationPollingJob: Job? = null
    private var reconnectJob: Job? = null
    private var goodbyeDisconnectWindowJob: Job? = null
    private var scheduledDeliveryJob: Job? = null
    private var supervisionVerificationJob: Job? = null
    private var userRequestedDisconnect: Boolean = false
    private var conversationLoopActive: Boolean = false
    private var scheduledResumeCancelledByUser: Boolean = false
    private var ignoreLifecycleGoodbyeAfterScheduledDelivery: Boolean = false
    private val pendingTextPrompts = ArrayDeque<String>()
    private val pendingScheduledPrompts = ArrayDeque<ScheduledPrompt>()
    private var activeScheduledPrompt: ScheduledPrompt? = null
    private var roleProfiles: List<RoleProfile> = emptyList()
    private var activeRoleId: String = storedConfig.activeRoleId
    private var roleSwitchGeneration: Int = 0
    private var lastNanoState: String = "IDLE"

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState = _uiState.asStateFlow()
    private val nanoSerialBridge by lazy {
        NanoSerialBridge(
            onLineReceived = ::handleNanoLine,
            onStatus = ::addLog,
        )
    }
    // Construct the scheduler only after queues and UI state are initialized.
    // Restored tasks can be due immediately and invoke this ViewModel during startup.
    private val reminderScheduler = ReminderScheduler(
        application,
        viewModelScope,
        ::handleScheduledReminderDue,
        ::handleReminderSnapshot,
    )
    private val mcpCameraServer = McpCameraServer(
        cameraVisionTool = cameraVisionTool,
        realtimeClient = realtimeClient,
        scope = viewModelScope,
        reminderScheduler = reminderScheduler,
        isCurrentSession = { sessionId ->
            uiState.value.connectionStatus == ConnectionStatus.CONNECTED &&
                uiState.value.sessionId == sessionId
        },
        isScheduledDeliveryInProgress = {
            activeScheduledPrompt != null
        },
        isInitialSupervisionReminderInProgress = {
            activeScheduledPrompt?.text?.startsWith(SUPERVISION_START_CODE_PHRASE) == true
        },
        log = ::addLog,
    )

    init {
        audioEngine.setRouteStatusListener(::updateAudioRouteStatus)
        audioEngine.setDebugListener(::addLog)
        audioEngine.setDebugOptions(
            loggingEnabled = storedConfig.debugLoggingEnabled,
            wavDumpEnabled = storedConfig.debugWavDumpEnabled,
        )
        reloadRoleProfiles()
        viewModelScope.launch {
            realtimeClient.events.collect(::handleRealtimeEvent)
        }
        viewModelScope.launch {
            TermuxCommandEvents.events.collect(::handleTermuxCommandResult)
        }
        addLog("客户端已就绪")
        addLog("当前使用未烧录设备接入模式")
        nanoSerialBridge.start()
        reminderScheduler.start()
        viewModelScope.launch {
            delay(AUTO_START_DELAY_MS)
            networkTimeSynchronizer.awaitValidSystemTime(::addLog)
            autoStartDeviceSession()
            // 开机自动静默检测是否有新版本发布
            delay(3000)
            checkForAppUpdate(silent = true)
        }
    }

    fun updateOtaUrl(value: String) = updateAndPersist { copy(otaUrl = value) }

    fun updateDeviceId(value: String) {
        updateAndPersist { copy(deviceId = value) }
        reloadRoleProfiles()
    }

    fun updateClientId(value: String) {
        updateAndPersist { copy(clientId = value) }
        reloadRoleProfiles()
    }

    fun updateWebsocketUrl(value: String) = updateAndPersist { copy(websocketUrl = value) }

    fun updateAuthToken(value: String) = updateAndPersist { copy(authToken = value) }

    fun updateProtocolVersion(value: String) = updateAndPersist { copy(protocolVersion = value) }

    fun updateMcpPayload(value: String) = updateAndPersist { copy(mcpPayload = value) }

    fun updateDraftMessage(value: String) {
        updateState { copy(draftMessage = value) }
    }

    fun importRoleAvatar(roleId: String, uri: Uri) {
        val role = roleProfiles.firstOrNull { it.id == roleId } ?: return
        runCatching {
            val avatarDir = File(getApplication<Application>().filesDir, "avatar").apply { mkdirs() }
            val targetFile = File(avatarDir, "role-${role.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
            val resolver = getApplication<Application>().contentResolver
            resolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取所选图片")
            targetFile.absolutePath
        }.onSuccess { path ->
            if (role.id == RoleProfileRepository.DEFAULT_ROLE_ID) {
                updateAndPersist {
                    copy(
                        assistantAvatarPath = path,
                        activeRoleAvatarPath = if (activeRoleId == role.id) path else activeRoleAvatarPath,
                    )
                }
            } else {
                saveAdditionalProfiles(
                    roleProfiles.filterNot(::isPrimaryRole).map { profile ->
                        if (profile.id == role.id) profile.copy(avatarPath = path) else profile
                    },
                )
            }
            reloadRoleProfiles()
            addLog("已更新${role.displayName}头像")
        }.onFailure { error ->
            addLog("更新头像失败：${error.message.orEmpty()}")
        }
    }

    fun importRoleVideo(roleId: String, slot: DigitalHumanSlot, uri: Uri) {
        val role = roleProfiles.firstOrNull { it.id == roleId } ?: return
        digitalHumanAssets.importVideo(role, slot, uri).onSuccess { path ->
            updateRoleVideoPath(roleId, slot, path)
            addLog("已更新${role.displayName}${slot.label}")
        }.onFailure { error -> addLog("导入${slot.label}失败：${error.message.orEmpty()}") }
    }

    fun updateRoleVideoPath(roleId: String, slot: DigitalHumanSlot, path: String) {
        if (roleId == RoleProfileRepository.DEFAULT_ROLE_ID) {
            updateAndPersist {
                when (slot) {
                    DigitalHumanSlot.IDLE -> copy(idleVideoPath = path)
                    DigitalHumanSlot.GREETING -> copy(greetingVideoPath = path)
                    DigitalHumanSlot.LISTENING -> copy(listeningVideoPath = path)
                    DigitalHumanSlot.SPEAKING -> copy(speakingVideoPath = path)
                }
            }
        } else {
            saveAdditionalProfiles(roleProfiles.filterNot(::isPrimaryRole).map { profile ->
                if (profile.id != roleId) profile else when (slot) {
                    DigitalHumanSlot.IDLE -> profile.copy(idleVideoPath = path)
                    DigitalHumanSlot.GREETING -> profile.copy(greetingVideoPath = path)
                    DigitalHumanSlot.LISTENING -> profile.copy(listeningVideoPath = path)
                    DigitalHumanSlot.SPEAKING -> profile.copy(speakingVideoPath = path)
                }
            })
        }
        reloadRoleProfiles()
    }

    fun checkForAppUpdate(silent: Boolean = false) {
        if (_uiState.value.isCheckingUpdate || _uiState.value.isDownloadingUpdate) return
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(isCheckingUpdate = true, updateCheckStatus = "正在检查更新...") }
            }
            val currentCode = _uiState.value.appVersionCode
            when (val result = appUpdateManager.checkForUpdate(currentCode)) {
                is me.xiaozhi.androidclient.ota.UpdateCheckResult.UpToDate -> {
                    if (!silent) {
                        _uiState.update { it.copy(isCheckingUpdate = false, updateCheckStatus = "已是最新版本 (v${it.appVersionName})", availableUpdate = null) }
                        addLog("检查更新：当前已是最新版本")
                    }
                }
                is me.xiaozhi.androidclient.ota.UpdateCheckResult.HasUpdate -> {
                    _uiState.update { it.copy(isCheckingUpdate = false, updateCheckStatus = "发现新版本 v${result.info.versionName}", availableUpdate = result.info) }
                    addLog("发现新版本：v${result.info.versionName} (${result.info.releaseNotes.replace('\n', ' ')})")
                }
                is me.xiaozhi.androidclient.ota.UpdateCheckResult.Error -> {
                    if (!silent) {
                        _uiState.update { it.copy(isCheckingUpdate = false, updateCheckStatus = "检查更新失败: ${result.message}") }
                        addLog("检查更新失败：${result.message}")
                    }
                }
            }
        }
    }

    fun startDownloadAndInstallUpdate() {
        val updateInfo = _uiState.value.availableUpdate ?: return
        if (_uiState.value.isDownloadingUpdate) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDownloadingUpdate = true, downloadProgressPercent = 0) }
            val downloadJob = launch {
                appUpdateManager.downloadState.collect { progress ->
                    when (progress) {
                        is me.xiaozhi.androidclient.ota.DownloadProgressState.Downloading -> {
                            _uiState.update { it.copy(downloadProgressPercent = progress.progressPercent) }
                        }
                        is me.xiaozhi.androidclient.ota.DownloadProgressState.Verifying -> {
                            _uiState.update { it.copy(updateCheckStatus = "正在校验安装包完整性...") }
                        }
                        is me.xiaozhi.androidclient.ota.DownloadProgressState.ReadyToInstall -> {
                            _uiState.update { it.copy(updateCheckStatus = "下载完成，正在安装...") }
                        }
                        is me.xiaozhi.androidclient.ota.DownloadProgressState.Failed -> {
                            _uiState.update { it.copy(isDownloadingUpdate = false, updateCheckStatus = "更新失败: ${progress.error}") }
                            addLog("升级下载失败: ${progress.error}")
                        }
                        else -> {}
                    }
                }
            }

            val downloadResult = appUpdateManager.downloadApk(updateInfo) { apkFile ->
                addLog("安装包校验通过，启动安装...")
                val installed = appUpdateManager.installApk(apkFile)
                if (!installed) {
                    addLog("调用系统安装失败，请检查安装权限")
                }
            }

            downloadJob.cancel()
            _uiState.update { it.copy(isDownloadingUpdate = false) }
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

    fun updateWakeWords(value: String) {
        updateAndPersist { copy(wakeWords = value) }
        reloadRoleProfiles()
    }

    fun addRole(displayName: String, wakeWords: String) {
        val name = displayName.trim()
        val phrases = parseWakeWords(wakeWords)
        if (name.isBlank() || phrases.isEmpty()) {
            addLog("角色名称和唤醒词不能为空")
            return
        }
        val role = RoleProfile(
            id = "role-${UUID.randomUUID()}",
            displayName = name,
            deviceId = generateRoleDeviceId(),
            clientId = UUID.randomUUID().toString(),
            wakeWords = phrases,
            isBound = false,
        )
        saveAdditionalProfiles(roleProfiles.filterNot(::isPrimaryRole) + role)
        reloadRoleProfiles()
        selectRole(role.id)
    }

    fun updateRole(roleId: String, displayName: String, wakeWords: String) {
        val name = displayName.trim()
        val phrases = parseWakeWords(wakeWords)
        if (name.isBlank() || phrases.isEmpty()) {
            addLog("角色名称和唤醒词不能为空")
            return
        }
        if (roleId == RoleProfileRepository.DEFAULT_ROLE_ID) {
            updateState {
                copy(
                    primaryRoleName = name,
                    wakeWords = phrases.joinToString(", "),
                    activeRoleName = if (activeRoleId == roleId) name else activeRoleName,
                )
            }
            persist()
        } else {
            saveAdditionalProfiles(
                roleProfiles.filterNot(::isPrimaryRole).map { profile ->
                    if (profile.id == roleId) {
                        profile.copy(displayName = name, wakeWords = phrases)
                    } else {
                        profile
                    }
                },
            )
        }
        reloadRoleProfiles()
    }

    fun deleteRole(roleId: String) {
        if (roleId == RoleProfileRepository.DEFAULT_ROLE_ID) {
            userRequestedDisconnect = true
            cancelReconnect()
            realtimeClient.disconnect(notify = false)
            updateAndPersist {
                copy(
                    primaryRoleName = "",
                    wakeWords = "",
                    deviceId = "",
                    clientId = "",
                    websocketUrl = "",
                    authToken = "",
                    activeRoleId = "",
                    connectionStatus = ConnectionStatus.DISCONNECTED,
                    activated = false,
                    activeRoleName = "",
                    roleProfiles = emptyList(),
                )
            }
            preferences.save(storedConfig.copy(primaryRoleName = "", wakeWords = "", deviceId = "", clientId = "", websocketUrl = "", authToken = "", activeRoleId = ""))
            roleProfiles = emptyList()
            addLog("已删除角色：小智")
            userRequestedDisconnect = false
            return
        }
        saveAdditionalProfiles(roleProfiles.filterNot { it.id == roleId })
        if (activeRoleId == roleId) {
            val next = roleProfiles.firstOrNull { it.id != roleId }
            if (next != null) selectRole(next.id) else {
                activeRoleId = ""
                updateAndPersist { copy(activeRoleId = "", activeRoleName = "", roleProfiles = emptyList()) }
                reloadRoleProfiles()
            }
        } else {
            reloadRoleProfiles()
        }
    }

    fun selectRole(roleId: String) {
        val targetRole = roleProfiles.firstOrNull { it.id == roleId } ?: return
        if (targetRole.id == activeRoleId && uiState.value.connectionStatus == ConnectionStatus.CONNECTED) {
            return
        }
        switchToRole(targetRole)
    }

    fun refreshRoleBinding(roleId: String) {
        val targetRole = roleProfiles.firstOrNull { it.id == roleId } ?: return
        if (targetRole.id != activeRoleId) {
            switchToRole(targetRole)
        } else {
            fetchOfficialConfig()
        }
    }

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

    /** Returns true when an ignored detection should immediately rearm KWS. */
    fun onWakeWordDetected(phrase: String): Boolean {
        val state = uiState.value
        if (state.isRecording || state.isAssistantSpeaking || state.isTurnActive) {
            addLog("当前会话未结束，忽略唤醒词：$phrase")
            return false
        }
        val targetRole = profileForWakeWord(phrase)
        if (targetRole == null) {
            updateState { copy(wakeWordStatus = WAKE_WORD_STANDBY) }
            addLog("忽略未分配给任何角色的唤醒词：$phrase")
            return true
        }
        if (targetRole.id != activeRoleId) {
            switchRoleAndContinueWake(targetRole, phrase)
            return false
        }
        conversationLoopActive = true
        setNanoState("PROCESSING")
        pendingWakePhrase = phrase
        pendingListeningMode = ListeningMode.REALTIME
        updateState {
            copy(
                isTurnActive = true,
                wakeWordStatus = "已唤醒：$phrase",
            )
        }
        addLog("检测到唤醒词：$phrase")
        ensureReadyForConversation(trigger = "唤醒词")
        return false
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
        if (uiState.value.connectionStatus == ConnectionStatus.FETCHING_CONFIG) {
            addLog("正在获取官方配置，请稍候")
            return
        }
        persistActiveRoleIfPrimary()
        val state = uiState.value
        val role = activeRoleProfile()
        val generation = roleSwitchGeneration
        updateState {
            copy(
                connectionStatus = ConnectionStatus.FETCHING_CONFIG,
                activationPending = false,
            )
        }
        addLog("正在获取 ${role.displayName} 的官方配置：${state.otaUrl}")

        viewModelScope.launch {
            otaConfigService.fetchConfig(
                OtaRequest(
                    otaUrl = state.otaUrl,
                    deviceId = role.deviceId,
                    clientId = role.clientId,
                    serialNumber = null,
                    appVersion = APP_VERSION,
                ),
            ).onSuccess { result ->
                if (generation != roleSwitchGeneration || role.id != activeRoleId) {
                    return@onSuccess
                }
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
                updateRoleBindingStatus(
                    roleId = role.id,
                    isBound = websocketConfig != null,
                    bindingCode = result.activation?.code.orEmpty(),
                )

                if (websocketConfig != null) {
                    stopActivationPolling()
                    addLog("已收到 WebSocket 配置：${websocketConfig.url}")
                    connect()
                } else {
                    addLog("OTA 返回中没有 WebSocket 配置")
                }

                if (result.activation != null && websocketConfig == null) {
                    addLog("需要激活设备：${result.activation.code.orEmpty()}")
                    if (result.activation.challenge.isNullOrBlank()) {
                        addLog("请先去 xiaozhi.me 完成激活，客户端会自动轮询配置")
                        startActivationPolling()
                    } else {
                        stopActivationPolling()
                        addLog("服务端要求 challenge 激活，但 Android 没有烧录 HMAC 密钥")
                    }
                }

                persistActiveRoleIfPrimary()

            }.onFailure { error ->
                if (generation != roleSwitchGeneration || role.id != activeRoleId) {
                    return@onFailure
                }
                val canUseCachedConfig = role.id == RoleProfileRepository.DEFAULT_ROLE_ID &&
                    state.websocketUrl.isNotBlank() && !state.activationPending
                updateState {
                    copy(
                        connectionStatus = if (canUseCachedConfig) {
                            ConnectionStatus.DISCONNECTED
                        } else {
                            ConnectionStatus.FAILED
                        },
                    )
                }
                addLog("获取 OTA 配置失败：${error.message.orEmpty()}")
                if (canUseCachedConfig) {
                    addLog("OTA 暂不可用，改用本地缓存配置连接服务端")
                    connect()
                }
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

    private fun autoStartDeviceSession() {
        val state = uiState.value
        when {
            state.otaUrl.isNotBlank() -> {
                addLog("启动后刷新官方配置")
                fetchOfficialConfig()
            }

            state.websocketUrl.isNotBlank() && !state.activationPending -> {
                addLog("未配置 OTA 地址，使用本地 WebSocket 配置连接服务端")
                connect()
            }

            else -> Unit
        }
    }

    private fun reloadRoleProfiles() {
        val state = uiState.value
        val primaryProfile = RoleProfile(
            id = RoleProfileRepository.DEFAULT_ROLE_ID,
            displayName = state.primaryRoleName,
            deviceId = state.deviceId,
            clientId = state.clientId,
            wakeWords = parseWakeWords(state.wakeWords),
            avatarPath = state.assistantAvatarPath,
            idleVideoPath = state.idleVideoPath,
            greetingVideoPath = state.greetingVideoPath,
            listeningVideoPath = state.listeningVideoPath,
            speakingVideoPath = state.speakingVideoPath,
            isBound = state.activated || state.websocketUrl.isNotBlank(),
            bindingCode = state.activationCode,
        )
        val result = roleProfileRepository.loadAdditionalProfiles()
        val hasPrimary = primaryProfile.displayName.isNotBlank() && primaryProfile.wakeWords.isNotEmpty() && primaryProfile.deviceId.isNotBlank()
        roleProfiles = (if (hasPrimary) listOf(primaryProfile) else emptyList()) + result.profiles.filter { it.id != primaryProfile.id }
        if (roleProfiles.none { it.id == activeRoleId }) {
            activeRoleId = roleProfiles.firstOrNull()?.id.orEmpty()
        }
        updateState {
            copy(
                roleWakeWords = roleProfiles
                    .flatMap(RoleProfile::wakeWords)
                    .distinct()
                    .joinToString(", "),
                activeRoleName = activeRoleProfile().displayName,
                activeRoleAvatarPath = activeRoleProfile().avatarPath,
                activeRoleDigitalHumanReady = activeRoleProfile().hasCompleteDigitalHuman(),
                activeRoleIdleVideoPath = activeRoleProfile().idleVideoPath,
                activeRoleGreetingVideoPath = activeRoleProfile().greetingVideoPath,
                activeRoleListeningVideoPath = activeRoleProfile().listeningVideoPath,
                activeRoleSpeakingVideoPath = activeRoleProfile().speakingVideoPath,
                activeRoleId = activeRoleId,
                roleProfiles = this@MainViewModel.roleProfiles,
            )
        }
        result.warning?.let(::addLog)
        addLog("已加载角色：${roleProfiles.joinToString { it.displayName }}")
    }

    private fun profileForWakeWord(phrase: String): RoleProfile? {
        return RoleWakeWordMatcher.findOwner(roleProfiles, phrase)
    }

    private fun activeRoleProfile(): RoleProfile =
        roleProfiles.firstOrNull { it.id == activeRoleId }
            ?: RoleProfile(
                id = RoleProfileRepository.DEFAULT_ROLE_ID,
                displayName = uiState.value.primaryRoleName,
                deviceId = uiState.value.deviceId,
                clientId = uiState.value.clientId,
                wakeWords = parseWakeWords(uiState.value.wakeWords),
                avatarPath = uiState.value.assistantAvatarPath,
                idleVideoPath = uiState.value.idleVideoPath,
                greetingVideoPath = uiState.value.greetingVideoPath,
                listeningVideoPath = uiState.value.listeningVideoPath,
                speakingVideoPath = uiState.value.speakingVideoPath,
            )

    private fun isPrimaryRole(profile: RoleProfile): Boolean =
        profile.id == RoleProfileRepository.DEFAULT_ROLE_ID

    private fun saveAdditionalProfiles(profiles: List<RoleProfile>) {
        roleProfileRepository.saveAdditionalProfiles(profiles)
    }

    private fun updateRoleBindingStatus(roleId: String, isBound: Boolean, bindingCode: String) {
        if (roleId != RoleProfileRepository.DEFAULT_ROLE_ID) {
            saveAdditionalProfiles(
                roleProfiles.filterNot(::isPrimaryRole).map { profile ->
                    if (profile.id == roleId) {
                        profile.copy(isBound = isBound, bindingCode = bindingCode)
                    } else {
                        profile
                    }
                },
            )
        }
        reloadRoleProfiles()
    }

    private fun generateRoleDeviceId(): String {
        val bytes = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        return bytes.joinToString(":") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
    }

    private fun switchToRole(targetRole: RoleProfile) {
        roleSwitchGeneration += 1
        userRequestedDisconnect = true
        conversationLoopActive = false
        scheduledResumeCancelledByUser = true
        ignoreLifecycleGoodbyeAfterScheduledDelivery = false
        setNanoState("IDLE")
        cancelReconnect()
        cancelExpectedGoodbyeDisconnect()
        clearPendingConversation()
        clearRoleConversation()
        stopActivationPolling()
        pendingActivationInfo = null
        audioEngine.clearPlayback { updateState { copy(isAssistantSpeaking = it) } }
        realtimeClient.disconnect(notify = false)
        activeRoleId = targetRole.id
        updateState {
            copy(
                activeRoleName = targetRole.displayName,
                activeRoleAvatarPath = targetRole.avatarPath,
                activeRoleDigitalHumanReady = targetRole.hasCompleteDigitalHuman(),
                activeRoleIdleVideoPath = targetRole.idleVideoPath,
                activeRoleGreetingVideoPath = targetRole.greetingVideoPath,
                activeRoleListeningVideoPath = targetRole.listeningVideoPath,
                activeRoleSpeakingVideoPath = targetRole.speakingVideoPath,
                activeRoleId = targetRole.id,
                connectionStatus = ConnectionStatus.DISCONNECTED,
                sessionId = "",
                websocketUrl = "",
                authToken = "",
                activated = false,
                activationPending = false,
                activationCode = targetRole.bindingCode,
                isAssistantSpeaking = false,
                isTurnActive = false,
                isSilentTransportRecovery = false,
                wakeWordStatus = "正在切换到${targetRole.displayName}",
            )
        }
        addLog("正在切换到角色：${targetRole.displayName}")
        persist()
        userRequestedDisconnect = false
        fetchOfficialConfig()
    }

    private fun switchRoleAndContinueWake(targetRole: RoleProfile, phrase: String) {
        roleSwitchGeneration += 1
        userRequestedDisconnect = true
        conversationLoopActive = true
        setNanoState("PROCESSING")
        cancelReconnect()
        cancelExpectedGoodbyeDisconnect()
        clearPendingConversation()
        clearRoleConversation()
        stopActivationPolling()
        pendingActivationInfo = null
        pendingWakePhrase = phrase
        pendingListeningMode = ListeningMode.REALTIME
        audioEngine.clearPlayback { updateState { copy(isAssistantSpeaking = it) } }
        realtimeClient.disconnect(notify = false)
        activeRoleId = targetRole.id
        updateState {
            copy(
                activeRoleName = targetRole.displayName,
                activeRoleAvatarPath = targetRole.avatarPath,
                activeRoleId = targetRole.id,
                connectionStatus = ConnectionStatus.DISCONNECTED,
                sessionId = "",
                websocketUrl = "",
                authToken = "",
                activated = false,
                activationPending = false,
                isAssistantSpeaking = false,
                isTurnActive = true,
                isSilentTransportRecovery = false,
                wakeWordStatus = "正在切换到${targetRole.displayName}",
            )
        }
        addLog("识别到 $phrase，正在切换到角色：${targetRole.displayName}")
        persist()
        userRequestedDisconnect = false
        fetchOfficialConfig()
    }

    private fun persistActiveRoleIfPrimary() {
        if (activeRoleId == RoleProfileRepository.DEFAULT_ROLE_ID) {
            persist()
        }
    }

    private fun parseWakeWords(raw: String): List<String> = raw
        .split(',', '，', ';', '；', '\n')
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun startActivationPolling() {
        if (activationPollingJob?.isActive == true) {
            return
        }
        activationPollingJob = viewModelScope.launch {
            repeat(ACTIVATION_POLL_MAX_ATTEMPTS) { attempt ->
                delay(ACTIVATION_POLL_INTERVAL_MS)
                val currentState = uiState.value
                if (currentState.activated) {
                    return@launch
                }
                if (currentState.connectionStatus == ConnectionStatus.FETCHING_CONFIG) {
                    return@repeat
                }
                addLog("自动检查设备激活状态：${attempt + 1}/$ACTIVATION_POLL_MAX_ATTEMPTS")
                fetchOfficialConfig()
            }
            addLog("自动激活检查已暂停，请确认设备码后手动重试")
        }
    }

    private fun stopActivationPolling() {
        activationPollingJob?.cancel()
        activationPollingJob = null
    }

    fun connect() {
        userRequestedDisconnect = false
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

        persistActiveRoleIfPrimary()
        val role = activeRoleProfile()
        val generation = roleSwitchGeneration
        val roleId = role.id
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
        addLog("正在连接 ${role.displayName}：${state.websocketUrl}")

        viewModelScope.launch {
            realtimeClient.connect(
                ConnectParams(
                    url = state.websocketUrl,
                    token = state.authToken,
                    protocolVersion = protocolVersion,
                    deviceId = role.deviceId,
                    clientId = role.clientId,
                ),
            ).onSuccess {
                if (generation != roleSwitchGeneration || roleId != activeRoleId) {
                    realtimeClient.disconnect(notify = false)
                    return@onSuccess
                }
            }.onFailure { error ->
                if (generation != roleSwitchGeneration || roleId != activeRoleId) {
                    return@onFailure
                }
                updateState {
                    copy(
                        connectionStatus = ConnectionStatus.FAILED,
                        isSilentTransportRecovery = false,
                    )
                }
                addLog("连接失败：${error.message.orEmpty()}")
            }
        }
    }

    fun disconnect() {
        userRequestedDisconnect = true
        conversationLoopActive = false
        scheduledResumeCancelledByUser = true
        ignoreLifecycleGoodbyeAfterScheduledDelivery = false
        cancelReconnect()
        cancelExpectedGoodbyeDisconnect()
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
                isSilentTransportRecovery = false,
            )
        }
        setNanoState("IDLE")
    }

    fun startListening(mode: ListeningMode) {
        if (uiState.value.connectionStatus != ConnectionStatus.CONNECTED) {
            addLog("请先连接服务端")
            pendingListeningMode = mode
            ensureReadyForConversation(trigger = "录音")
            return
        }
        if (
            uiState.value.isAssistantSpeaking ||
            (uiState.value.isTurnActive && !uiState.value.isRecording && !conversationLoopActive)
        ) {
            addLog("请等小智说完再开始下一句")
            return
        }
        if (audioEngine.isCapturing()) {
            addLog("录音已经在进行中")
            return
        }

        pendingListeningMode = null

        if (!realtimeClient.sendStartListening(mode)) {
            return
        }

        audioEngine.startCapture(
            mode = mode,
            onEncodedFrame = { frame ->
                val state = uiState.value
                if (state.isRecording && !state.isAssistantSpeaking) {
                    realtimeClient.sendAudioFrame(frame)
                } else {
                    true
                }
            },
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
                if (isRecording) {
                    setNanoState("LISTENING")
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
                setNanoState("IDLE")
            },
        )
        addLog("已开始录音：${mode.wireValue}")
    }

    fun stopListening() {
        pendingListeningMode = null
        conversationLoopActive = false
        scheduledResumeCancelledByUser = true
        ignoreLifecycleGoodbyeAfterScheduledDelivery = false
        finishListening(sendStop = true, stopCapture = true, keepTurnActive = true, reason = "已停止录音")
    }

    fun abortSpeaking() {
        conversationLoopActive = false
        scheduledResumeCancelledByUser = true
        ignoreLifecycleGoodbyeAfterScheduledDelivery = false
        clearPendingConversation()
        finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "已请求打断")
        audioEngine.clearPlayback {
            updateState { copy(isAssistantSpeaking = it) }
        }
        updateState { copy(isAssistantSpeaking = false) }
        if (realtimeClient.sendAbort()) {
            addLog("已向服务端发送打断请求")
        }
        setNanoState("IDLE")
    }

    fun sendDraftMessage() {
        val prompt = uiState.value.draftMessage.trim()
        if (prompt.isBlank()) {
            return
        }
        if (uiState.value.isRecording || uiState.value.isAssistantSpeaking || uiState.value.isTurnActive) {
            addLog("请等小智说完再发送下一句")
            return
        }
        updateState { copy(draftMessage = "") }
        addChatMessage(ChatRole.USER, prompt)
        conversationLoopActive = true
        scheduledResumeCancelledByUser = false
        pendingTextPrompts.addLast(prompt)
        updateState { copy(isTurnActive = true) }
        setNanoState("PROCESSING")
        addLog("准备发送文字消息")
        ensureReadyForConversation(trigger = "文字消息")
    }

    fun sendMcp() {
        if (realtimeClient.sendMcp(uiState.value.mcpPayload)) {
            addLog("已发送 MCP 请求")
        }
    }

    private fun handleScheduledReminderDue(reminder: ScheduledReminder) {
        val text = when {
            reminder.kind == ReminderKind.SUPERVISION ->
                "【监督】"
            else -> TIMER_CODE_PHRASE
        }
        enqueueScheduledPrompt(reminder.id, reminder.kind, text)
    }

    private fun handleReminderSnapshot(reminders: List<ScheduledReminder>) {
        val now = System.currentTimeMillis()
        val tasks = reminders.map { reminder ->
            val remaining = reminder.dueAtEpochMs?.let { dueAt ->
                ((dueAt - now).coerceAtLeast(0L) + 999L) / 1_000L
            }
            val status = when {
                reminder.deliveryPending -> "等待连接后播报"
                else -> when (reminder.supervisionPhase) {
                    SupervisionPhase.COUNTDOWN -> "等待首次提醒"
                    SupervisionPhase.WAITING_FOR_ACK -> "等待你的回应"
                    SupervisionPhase.VERIFICATION_SCHEDULED -> "等待摄像头核验"
                    SupervisionPhase.VERIFYING -> "正在拍照核验"
                    null -> "等待提醒"
                }
            }
            ScheduledTaskUi(
                id = reminder.id,
                kind = if (reminder.kind == ReminderKind.TIMER) "定时提醒" else "监督提醒",
                message = reminder.message,
                status = status,
                remainingSeconds = remaining,
            )
        }.sortedWith(compareBy(nullsLast()) { it.remainingSeconds })
        updateState { copy(scheduledTasks = tasks) }
    }

    private fun enqueueScheduledPrompt(reminderId: String, kind: ReminderKind, text: String) {
        if (activeScheduledPrompt?.reminderId == reminderId ||
            pendingScheduledPrompts.any { it.reminderId == reminderId }
        ) {
            return
        }
        pendingScheduledPrompts.addLast(ScheduledPrompt(reminderId, kind, text))
        val label = text.substringAfter('】').ifBlank { text }
        addLog("任务到期，已进入优先提醒队列：$label")
        coordinateScheduledPromptDelivery()
    }

    private fun startSupervisionVerification(reminder: ScheduledReminder) {
        if (supervisionVerificationJob?.isActive == true) {
            addLog("监督核验已经在执行，忽略重复触发：${reminder.message}")
            return
        }
        val current = reminderScheduler.activeSupervision()
        if (current?.id != reminder.id || current.supervisionPhase != SupervisionPhase.VERIFYING) {
            addLog("忽略已失效的监督核验：${reminder.message}")
            return
        }
        supervisionVerificationJob = viewModelScope.launch {
            addLog("监督核验到点，设备开始调用摄像头：${reminder.message}")
            val result = runCatching {
                cameraVisionTool.takePhotoAndExplain(supervisionVisionQuestion(reminder.message))
            }
            val status = result.getOrNull()
                ?.let(SupervisionVisionDecisionParser::parse)
                ?.status
                ?: SupervisionVisionStatus.UNCERTAIN
            result.getOrNull()?.let { response ->
                addLog("监督视觉返回：${response.replace('\n', ' ').take(160)}")
            }
            if (reminderScheduler.activeSupervision()?.id != reminder.id) {
                addLog("监督任务已变化，丢弃本次摄像头结果")
                supervisionVerificationJob = null
                return@launch
            }

            val promptText = when (status) {
                SupervisionVisionStatus.COMPLETED -> {
                    reminderScheduler.completeSupervision(reminder.id)
                    addLog("摄像头已确认监督任务完成：${reminder.message}")
                    // listen/detect is a wake-word channel; full task text is rejected as long text.
                    "${SUPERVISION_RESULT_CODE_PHRASE}已完成"
                }
                SupervisionVisionStatus.NOT_COMPLETED -> {
                    val retrySeconds = nextSupervisionRetrySeconds(reminder.checkCount)
                    reminderScheduler.scheduleSupervisionVerification(reminder.id, retrySeconds)
                    addLog("摄像头确认任务尚未完成，$retrySeconds 秒后再次核验：${reminder.message}")
                    "${SUPERVISION_RESULT_CODE_PHRASE}未完成"
                }
                SupervisionVisionStatus.UNCERTAIN -> {
                    val retrySeconds = nextSupervisionRetrySeconds(reminder.checkCount)
                    reminderScheduler.scheduleSupervisionVerification(reminder.id, retrySeconds)
                    val reason = result.exceptionOrNull()?.message.orEmpty()
                    addLog("摄像头无法确认任务，$retrySeconds 秒后重试${reason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
                    "${SUPERVISION_RESULT_CODE_PHRASE}无法确认"
                }
            }
            supervisionVerificationJob = null
            enqueueScheduledPrompt("${reminder.id}-result-${reminder.checkCount}", ReminderKind.SUPERVISION, promptText)
        }
    }

    private fun supervisionVisionQuestion(message: String): String =
        "任务：$message。只判断当前画面能否明确证明用户已经完成该任务。" +
            "只输出一行 STATUS: COMPLETED、STATUS: NOT_COMPLETED 或 STATUS: UNCERTAIN。" +
            "无法明确证明时必须输出 STATUS: UNCERTAIN。"

    private fun nextSupervisionRetrySeconds(checkCount: Int): Int =
        SupervisionPolicy.nextRetrySeconds(checkCount)

    private fun coordinateScheduledPromptDelivery() {
        if (pendingScheduledPrompts.isEmpty()) return
        val state = uiState.value
        when (ReminderDeliveryPolicy.decide(
            ReminderConversationState(
                connected = state.connectionStatus == ConnectionStatus.CONNECTED,
                isRecording = state.isRecording,
                isAssistantSpeaking = state.isAssistantSpeaking,
                isTurnActive = state.isTurnActive,
                hasActiveDelivery = activeScheduledPrompt != null,
            ),
        )) {
            ReminderDeliveryAction.PAUSE_LISTENING_AFTER_GRACE -> {
                scheduleListeningPauseForReminder()
                return
            }
            ReminderDeliveryAction.INTERRUPT_CURRENT_TURN_AFTER_GRACE -> {
                scheduleCurrentTurnInterruptionForReminder()
                return
            }
            ReminderDeliveryAction.SEND_NOW -> Unit
            else -> return
        }

        cancelScheduledDeliveryJob()
        val prompt = pendingScheduledPrompts.removeFirst()
        scheduledResumeCancelledByUser = false
        ignoreLifecycleGoodbyeAfterScheduledDelivery = false
        activeScheduledPrompt = prompt
        pendingTextPrompts.addFirst(prompt.text)
        updateState { copy(isTurnActive = true) }
        setNanoState("PROCESSING")
        addLog("到期任务已进入文字消息队列：${prompt.text}")
        flushPendingActions()
    }

    private fun scheduleListeningPauseForReminder() {
        if (scheduledDeliveryJob?.isActive == true) return
        addLog("连续聆听中，到期提醒最多等待 3 秒后插播")
        scheduledDeliveryJob = viewModelScope.launch {
            delay(REMINDER_LISTENING_GRACE_MS)
            scheduledDeliveryJob = null
            if (pendingScheduledPrompts.isEmpty() || activeScheduledPrompt != null) {
                return@launch
            }
            if (uiState.value.isRecording) {
                finishListening(
                    sendStop = true,
                    stopCapture = true,
                    keepTurnActive = false,
                    reason = "到期提醒优先，已暂停连续聆听",
                )
                // Let the server finish the old audio turn before injecting the text event.
                delay(500L)
            }
            coordinateScheduledPromptDelivery()
        }
    }

    private fun scheduleCurrentTurnInterruptionForReminder() {
        if (scheduledDeliveryJob?.isActive == true) return
        addLog("当前回合尚未结束，到期提醒最多等待 5 秒")
        scheduledDeliveryJob = viewModelScope.launch {
            delay(REMINDER_CURRENT_TURN_GRACE_MS)
            scheduledDeliveryJob = null
            if (pendingScheduledPrompts.isEmpty() || activeScheduledPrompt != null) {
                return@launch
            }

            val state = uiState.value
            if (
                state.connectionStatus == ConnectionStatus.CONNECTED &&
                state.isTurnActive &&
                !state.isRecording &&
                !state.isAssistantSpeaking
            ) {
                conversationLoopActive = false
                pendingListeningMode = null
                updateState { copy(isTurnActive = false) }
                realtimeClient.sendAbort("到期提醒优先")
                setNanoState("PROCESSING")
                addLog("到期提醒优先，已结束停滞的旧回合")
                delay(500L)
            }
            coordinateScheduledPromptDelivery()
        }
    }

    private fun scheduleScheduledDeliveryRetry() {
        if (scheduledDeliveryJob?.isActive == true) return
        scheduledDeliveryJob = viewModelScope.launch {
            delay(REMINDER_SEND_RETRY_MS)
            scheduledDeliveryJob = null
            coordinateScheduledPromptDelivery()
        }
    }

    private fun cancelScheduledDeliveryJob() {
        scheduledDeliveryJob?.cancel()
        scheduledDeliveryJob = null
    }

    private fun finishScheduledDelivery(reason: String, requeue: Boolean = false): ScheduledPrompt? {
        val prompt = activeScheduledPrompt ?: return null
        activeScheduledPrompt = null
        if (requeue && pendingScheduledPrompts.none { it.reminderId == prompt.reminderId }) {
            pendingScheduledPrompts.addFirst(prompt)
        }
        addLog("到期任务投递结束：$reason")
        return prompt
    }

    fun clearLogs() {
        updateState { copy(logs = emptyList()) }
        addLog("日志已清空")
    }

    override fun onCleared() {
        userRequestedDisconnect = true
        conversationLoopActive = false
        scheduledResumeCancelledByUser = true
        ignoreLifecycleGoodbyeAfterScheduledDelivery = false
        cancelScheduledDeliveryJob()
        supervisionVerificationJob?.cancel()
        cancelReconnect()
        cancelExpectedGoodbyeDisconnect()
        realtimeClient.disconnect(notify = false)
        nanoSerialBridge.close()
        audioEngine.release()
        super.onCleared()
    }

    private fun handleNanoLine(rawLine: String) {
        viewModelScope.launch {
            val line = rawLine.trim()
            when {
                line.startsWith("PERSON_NEAR,") -> {
                    val distanceMm = line.substringAfter(',').toIntOrNull()
                    handlePersonNear(distanceMm)
                }

                line.startsWith("NANO_READY,") -> {
                    addLog("Nano 控制器已就绪：$line")
                    nanoSerialBridge.resendDeviceState()
                }

                line == "PONG" || line == "PROXIMITY_ARMED" || line.startsWith("DISTANCE,") -> Unit
                line.startsWith("NANO_ERROR,") -> addLog("Nano 报错：$line")
            }
        }
    }

    private fun handlePersonNear(distanceMm: Int?) {
        val state = uiState.value
        if (state.isRecording || state.isAssistantSpeaking || state.isTurnActive) {
            nanoSerialBridge.sendLine("EVENT_REJECTED")
            addLog("检测到人靠近，但当前会话未结束，本次不触发")
            return
        }

        conversationLoopActive = true
        setNanoState("PROCESSING")
        pendingTextPrompts.addLast(ACTIVE_GREETING_CODE_PHRASE)
        updateState {
            copy(
                isTurnActive = true,
                wakeWordStatus = "检测到人靠近",
            )
        }
        val distanceLabel = distanceMm?.let { "（${it}mm）" }.orEmpty()
        addLog("检测到人持续靠近$distanceLabel，触发主动招呼和摄像头分析")
        ensureReadyForConversation(trigger = "主动招呼")
    }

    private fun setNanoState(state: String) {
        if (state == lastNanoState) {
            return
        }
        lastNanoState = state
        nanoSerialBridge.setDeviceState(state)
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
        when {
            uiState.value.isAssistantSpeaking -> setNanoState("SPEAKING")
            keepTurnActive -> setNanoState("PROCESSING")
            else -> setNanoState("IDLE")
        }
        addLog(reason)
    }

    private fun handleRealtimeEvent(event: XiaozhiRealtimeClient.RealtimeEvent) {
        when (event) {
            is XiaozhiRealtimeClient.RealtimeEvent.Log -> addLog(event.message)

            is XiaozhiRealtimeClient.RealtimeEvent.Connected -> {
                cancelReconnect()
                cancelExpectedGoodbyeDisconnect()
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
                        isSilentTransportRecovery = false,
                        // A pending listening mode is an action to start after reconnect, not an
                        // active turn. Otherwise startListening rejects its own queued action.
                        isTurnActive = pendingWakePhrase != null || pendingTextPrompts.isNotEmpty(),
                    )
                }
                flushPendingActions()
                coordinateScheduledPromptDelivery()
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
                val recoverSilently = consumeExpectedGoodbyeDisconnect()
                finishScheduledDelivery("连接已断开，等待重试", requeue = true)
                val wasSpeaking = uiState.value.isAssistantSpeaking
                conversationLoopActive = false
                finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "Socket 已关闭")
                audioEngine.clearPlayback { isPlaying ->
                    updateState {
                        copy(
                            isAssistantSpeaking = isPlaying,
                            isTurnActive = isPlaying,
                        )
                    }
                    if (!isPlaying) {
                        setNanoState("IDLE")
                    }
                }
                updateState {
                    copy(
                        connectionStatus = ConnectionStatus.DISCONNECTED,
                        sessionId = "",
                        isAssistantSpeaking = wasSpeaking,
                        isTurnActive = wasSpeaking,
                        isSilentTransportRecovery = recoverSilently,
                    )
                }
                addLog("Socket 已关闭：${event.code} ${event.reason}")
                scheduleReconnect(
                    reason = if (recoverSilently) "会话已收尾，正在后台恢复连接" else "Socket 已关闭",
                    delayMs = if (recoverSilently) 0L else AUTO_RECONNECT_DELAY_MS,
                )
            }

            is XiaozhiRealtimeClient.RealtimeEvent.Error -> {
                cancelExpectedGoodbyeDisconnect()
                finishScheduledDelivery("实时通道异常，等待重试", requeue = true)
                conversationLoopActive = false
                finishListening(sendStop = false, stopCapture = true, keepTurnActive = false, reason = "实时通道异常")
                audioEngine.clearPlayback {
                    updateState { copy(isAssistantSpeaking = it) }
                }
                updateState {
                    copy(
                        connectionStatus = ConnectionStatus.FAILED,
                        isAssistantSpeaking = false,
                        isTurnActive = false,
                        isSilentTransportRecovery = false,
                    )
                }
                addLog(event.message)
                setNanoState("IDLE")
                scheduleReconnect("实时通道异常")
            }
        }
    }

    private fun parseServerMessage(type: String?, rawText: String) {
        updateState { copy(lastIncomingType = type.orEmpty()) }

        when (type) {
            "tts" -> handleTtsMessage(rawText)
            "stt" -> handleSttMessage(rawText)
            "listen" -> handleListenMessage(rawText)
            "goodbye" -> handleGoodbyeMessage(rawText)
            "alert" -> handleAlertMessage(rawText)
            "mcp" -> {
                if (!mcpCameraServer.handleIncomingMcp(rawText)) {
                    addLog("<= $rawText")
                }
            }
            else -> addLog("<= $rawText")
        }
    }

    private fun handleListenMessage(rawText: String) {
        addLog("<= $rawText")
        val root = parseJson(rawText) ?: return
        if (!isCurrentSessionMessage(root)) {
            return
        }

        when (root.optString("state")) {
            "stop" -> {
                pendingListeningMode = null
                val scheduledDeliveryActive = activeScheduledPrompt != null
                val shouldKeepTurnActive = scheduledDeliveryActive || conversationLoopActive
                finishListening(
                    sendStop = false,
                    stopCapture = true,
                    keepTurnActive = shouldKeepTurnActive,
                    reason = if (shouldKeepTurnActive) {
                        "服务端停止监听，正在等待回复"
                    } else {
                        "服务端停止监听，已回到待机"
                    },
                )
                updateState { copy(isTurnActive = isAssistantSpeaking || shouldKeepTurnActive) }
                if (!uiState.value.isAssistantSpeaking) {
                    if (shouldKeepTurnActive) {
                        setNanoState("PROCESSING")
                    } else {
                        setNanoState("IDLE")
                        coordinateScheduledPromptDelivery()
                    }
                }
            }
        }
    }

    private fun handleGoodbyeMessage(rawText: String) {
        addLog("<= $rawText")
        val root = parseJson(rawText)
        if (!isCurrentSessionMessage(root)) {
            return
        }
        armExpectedGoodbyeDisconnect()

        val deliveredPrompt = finishScheduledDelivery("收到 goodbye")
        when (deliveredPrompt?.reminderKind) {
            ReminderKind.TIMER -> reminderScheduler.completeTimer(deliveredPrompt.reminderId)
            ReminderKind.SUPERVISION -> reminderScheduler.completeSupervision(deliveredPrompt.reminderId)
            null -> Unit
        }
        val shouldResume = (deliveredPrompt?.resumeListeningAfterDelivery == true ||
            ignoreLifecycleGoodbyeAfterScheduledDelivery) && !scheduledResumeCancelledByUser
        ignoreLifecycleGoodbyeAfterScheduledDelivery = false
        scheduledResumeCancelledByUser = false
        clearPendingConversation()
        conversationLoopActive = shouldResume
        finishListening(
            sendStop = false,
            stopCapture = true,
            keepTurnActive = false,
            reason = "收到服务端 goodbye，已结束本轮会话",
        )

        if (uiState.value.isAssistantSpeaking) {
            audioEngine.finishPlayback { isPlaying ->
                updateState {
                    copy(
                        isAssistantSpeaking = isPlaying,
                        isTurnActive = false,
                    )
                }
                if (!isPlaying) {
                    coordinateScheduledPromptDelivery()
                    if (shouldResume && pendingScheduledPrompts.isEmpty()) {
                        addLog("任务播报结束，忽略内部 goodbye 并继续聆听")
                        resumeConversationListeningAfterPlayback()
                    } else {
                        setNanoState("IDLE")
                        addLog("goodbye 后播报收尾完成，已回到待机")
                    }
                }
            }
        } else {
            updateState {
                copy(
                    isAssistantSpeaking = false,
                    isTurnActive = false,
                )
            }
            coordinateScheduledPromptDelivery()
            if (shouldResume && pendingScheduledPrompts.isEmpty()) {
                addLog("任务播报结束，忽略内部 goodbye 并继续聆听")
                resumeConversationListeningAfterPlayback()
            } else {
                setNanoState("IDLE")
                addLog("已回到待机")
            }
        }
    }

    private fun handleTtsMessage(rawText: String) {
        addLog("<= $rawText")
        val root = parseJson(rawText) ?: return
        if (!isCurrentSessionMessage(root)) {
            addLog("忽略旧会话 TTS：${root.optString("session_id")}")
            return
        }
        val state = root.optString("state")
        val text = root.optString("text")

        when (state) {
            "start" -> {
                cancelScheduledDeliveryJob()
                audioEngine.beginPlaybackSession()
                updateState { copy(isAssistantSpeaking = true, isTurnActive = true) }
                setNanoState("SPEAKING")
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

            "sentence_start" -> {
                if (text.isNotBlank()) {
                    updateState { copy(lastTtsText = text) }
                    addChatMessage(ChatRole.ASSISTANT, text)
                }
            }

            "stop" -> {
                val deliveredPrompt = finishScheduledDelivery("提醒播报完成")
                when (deliveredPrompt?.reminderKind) {
                    ReminderKind.TIMER -> reminderScheduler.completeTimer(deliveredPrompt.reminderId)
                    ReminderKind.SUPERVISION -> reminderScheduler.completeSupervision(deliveredPrompt.reminderId)
                    null -> Unit
                }
                if (deliveredPrompt?.resumeListeningAfterDelivery == true && !scheduledResumeCancelledByUser) {
                    conversationLoopActive = true
                    ignoreLifecycleGoodbyeAfterScheduledDelivery = true
                }
                scheduledResumeCancelledByUser = false
                audioEngine.finishPlayback {
                    updateState {
                        copy(
                            isAssistantSpeaking = it,
                            isTurnActive = when {
                                it -> true
                                pendingScheduledPrompts.isNotEmpty() -> false
                                else -> conversationLoopActive
                            },
                        )
                    }
                    addLog("播报完成")
                    if (!it) {
                        coordinateScheduledPromptDelivery()
                        when {
                            activeScheduledPrompt != null || pendingScheduledPrompts.isNotEmpty() -> Unit
                            conversationLoopActive -> resumeConversationListeningAfterPlayback()
                            else -> setNanoState("IDLE")
                        }
                    }
                }
            }
        }
    }

    private fun resumeConversationListeningAfterPlayback() {
        if (!conversationLoopActive) {
            return
        }
        viewModelScope.launch {
            delay(300)
            val state = uiState.value
            if (
                conversationLoopActive &&
                state.connectionStatus == ConnectionStatus.CONNECTED &&
                !state.isRecording &&
                !state.isAssistantSpeaking &&
                activeScheduledPrompt == null &&
                pendingScheduledPrompts.isEmpty()
            ) {
                addLog("播报结束，继续聆听")
                startListening(ListeningMode.REALTIME)
            }
        }
    }

    private fun handleSttMessage(rawText: String) {
        addLog("<= $rawText")
        val root = parseJson(rawText) ?: return
        if (!isCurrentSessionMessage(root)) {
            addLog("忽略旧会话 STT：${root.optString("session_id")}")
            return
        }
        val text = root.optString("text").orEmpty()
        if (text.isNotBlank() && text != uiState.value.lastSttText) {
            ignoreLifecycleGoodbyeAfterScheduledDelivery = false
            coordinateSupervisionFromUserSpeech(text)
            updateState { copy(lastSttText = text, isTurnActive = true) }
            addChatMessage(ChatRole.USER, text)
        }
    }

    private fun coordinateSupervisionFromUserSpeech(text: String) {
        if (text.startsWith("【")) return
        val current = reminderScheduler.activeSupervision() ?: return
        val normalized = text.replace(" ", "")
        if (listOf("取消监督", "取消任务", "停止监督", "不用监督", "不做了", "算了").any(normalized::contains)) {
            if (reminderScheduler.cancelSupervision()) {
                addLog("已根据用户语音取消监督任务：${current.message}")
            }
            return
        }
        if (current.supervisionPhase == SupervisionPhase.WAITING_FOR_ACK) {
            if (reminderScheduler.scheduleSupervisionVerification(current.id, 60)) {
                addLog("用户已回应首次监督提醒，60 秒后进行第一次核验")
            }
        }
    }

    private fun handleAlertMessage(rawText: String) {
        addLog("<= $rawText")
        val root = parseJson(rawText) ?: return
        if (!isCurrentSessionMessage(root)) return
        val retryTimer = activeScheduledPrompt?.reminderKind == ReminderKind.TIMER
        finishScheduledDelivery("服务端拒绝事件", requeue = retryTimer)
        updateState { copy(isTurnActive = false) }
        setNanoState("IDLE")
        if (retryTimer) scheduleScheduledDeliveryRetry() else coordinateScheduledPromptDelivery()
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

    private fun scheduleReconnect(reason: String, delayMs: Long = AUTO_RECONNECT_DELAY_MS) {
        if (userRequestedDisconnect || uiState.value.websocketUrl.isBlank() || uiState.value.activationPending) {
            return
        }
        if (reconnectJob?.isActive == true) {
            return
        }
        reconnectJob = viewModelScope.launch {
            addLog("$reason，稍后自动重连")
            delay(delayMs)
            val status = uiState.value.connectionStatus
            if (!userRequestedDisconnect &&
                status != ConnectionStatus.CONNECTED &&
                status != ConnectionStatus.CONNECTING
            ) {
                connect()
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun armExpectedGoodbyeDisconnect() {
        goodbyeDisconnectWindowJob?.cancel()
        goodbyeDisconnectWindowJob = viewModelScope.launch {
            delay(GOODBYE_DISCONNECT_WINDOW_MS)
            goodbyeDisconnectWindowJob = null
        }
    }

    private fun consumeExpectedGoodbyeDisconnect(): Boolean {
        val expected = goodbyeDisconnectWindowJob?.isActive == true
        goodbyeDisconnectWindowJob?.cancel()
        goodbyeDisconnectWindowJob = null
        return expected
    }

    private fun cancelExpectedGoodbyeDisconnect() {
        goodbyeDisconnectWindowJob?.cancel()
        goodbyeDisconnectWindowJob = null
    }

    private fun flushPendingActions() {
        if (uiState.value.connectionStatus != ConnectionStatus.CONNECTED) {
            return
        }

        pendingWakePhrase?.let { phrase ->
            if (!realtimeClient.sendDetectText(phrase)) {
                return
            }
            addLog("已上报唤醒词：$phrase")
            pendingWakePhrase = null
        }

        while (pendingTextPrompts.isNotEmpty()) {
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

    private fun clearRoleConversation() {
        updateState(UiState::resetConversationForRoleSwitch)
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

    private fun isCurrentSessionMessage(root: JSONObject?): Boolean {
        root ?: return true
        val messageSessionId = root.optString("session_id").orEmpty()
        val currentSessionId = uiState.value.sessionId
        return messageSessionId.isBlank() || currentSessionId.isBlank() || messageSessionId == currentSessionId
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
            activeRoleId = storedConfig.activeRoleId,
            wakeWordEnabled = storedConfig.wakeWordEnabled,
            wakeWords = storedConfig.wakeWords,
            primaryRoleName = storedConfig.primaryRoleName,
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
                idleVideoPath = uiState.value.idleVideoPath,
                greetingVideoPath = uiState.value.greetingVideoPath,
                listeningVideoPath = uiState.value.listeningVideoPath,
                speakingVideoPath = uiState.value.speakingVideoPath,
                websocketUrl = uiState.value.websocketUrl,
                authToken = uiState.value.authToken,
                protocolVersion = uiState.value.protocolVersion,
                mcpPayload = uiState.value.mcpPayload,
                wakeWordEnabled = uiState.value.wakeWordEnabled,
                wakeWords = uiState.value.wakeWords,
                primaryRoleName = uiState.value.primaryRoleName,
                activeRoleId = uiState.value.activeRoleId,
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
