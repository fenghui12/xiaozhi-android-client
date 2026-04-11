package me.xiaozhi.androidclient

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import me.xiaozhi.androidclient.audio.WakeWordRecognizer
import me.xiaozhi.androidclient.model.ChatMessage
import me.xiaozhi.androidclient.model.ChatRole
import me.xiaozhi.androidclient.model.ConnectionStatus
import me.xiaozhi.androidclient.model.ListeningMode
import me.xiaozhi.androidclient.model.LogLine
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.ui.MainViewModel
import me.xiaozhi.androidclient.ui.theme.XiaozhiClientTheme

private enum class AppScreen { CHAT, SETTINGS }

private sealed interface PendingAudioAction {
    data class StartListening(val mode: ListeningMode) : PendingAudioAction
    data object EnableWakeWord : PendingAudioAction
}

private val ChatBackground = Color(0xFFF7F4F9)
private val SettingsBackground = Color(0xFFF4EFF7)
private val UserBubble = Color(0xFF93E26A)
private val AssistantBubble = Color.White
private val HeaderTint = Color(0xFFF1E9FB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XiaozhiClientTheme { XiaozhiApp() } }
    }
}

@Composable
private fun XiaozhiApp() {
    val viewModel: MainViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val latestState by rememberUpdatedState(state)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.CHAT) }
    var isForeground by remember { mutableStateOf(true) }
    var pendingAudioAction by remember { mutableStateOf<PendingAudioAction?>(null) }
    var playbackCooldownActive by remember { mutableStateOf(false) }

    val wakeWordRecognizer = remember(context) {
        WakeWordRecognizer(
            context = context,
            onWakeWordDetected = viewModel::onWakeWordDetected,
            onStatusChanged = viewModel::updateWakeWordStatus,
            onError = viewModel::updateWakeWordStatus,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        when (val action = pendingAudioAction) {
            is PendingAudioAction.StartListening -> {
                if (granted) viewModel.startListening(action.mode)
                else viewModel.onMicrophonePermissionDenied("capture")
            }

            PendingAudioAction.EnableWakeWord -> {
                if (granted) viewModel.updateWakeWordEnabled(true)
                else viewModel.onMicrophonePermissionDenied("wake_word")
            }

            null -> Unit
        }
        pendingAudioAction = null
    }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.importAssistantAvatar(uri)
        }
    }

    DisposableEffect(lifecycleOwner, wakeWordRecognizer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    isForeground = true
                    viewModel.refreshPythonRuntimeStatus()
                }

                Lifecycle.Event.ON_STOP -> {
                    isForeground = false
                    wakeWordRecognizer.stop(updateStatus = false)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            wakeWordRecognizer.release()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPythonRuntimeStatus()
    }

    LaunchedEffect(state.isAssistantSpeaking) {
        if (state.isAssistantSpeaking) {
            playbackCooldownActive = true
        } else if (playbackCooldownActive) {
            delay(1800L)
            playbackCooldownActive = false
        }
    }

    LaunchedEffect(
        state.wakeWordEnabled,
        state.wakeWords,
        state.isRecording,
        state.isAssistantSpeaking,
        state.isTurnActive,
        state.connectionStatus,
        playbackCooldownActive,
        isForeground,
    ) {
        val shouldRun = isForeground &&
            state.wakeWordEnabled &&
            !state.isRecording &&
            !state.isAssistantSpeaking &&
            !state.isTurnActive &&
            !playbackCooldownActive &&
            state.connectionStatus != ConnectionStatus.CONNECTING
        if (shouldRun) wakeWordRecognizer.start(state.wakeWords)
        else wakeWordRecognizer.stop(updateStatus = false)
    }

    val requestMicrophoneForMode: (ListeningMode) -> Unit = { mode ->
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startListening(mode)
        } else {
            pendingAudioAction = PendingAudioAction.StartListening(mode)
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val requestWakeWordEnable: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            viewModel.updateWakeWordEnabled(false)
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.updateWakeWordEnabled(true)
            } else {
                pendingAudioAction = PendingAudioAction.EnableWakeWord
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    XiaozhiScreen(
        state = latestState,
        currentScreen = currentScreen,
        onOpenSettings = { currentScreen = AppScreen.SETTINGS },
        onBackToChat = { currentScreen = AppScreen.CHAT },
        onPickAssistantAvatar = { avatarPickerLauncher.launch("image/*") },
        onFetchOfficialConfig = viewModel::fetchOfficialConfig,
        onRetryActivation = viewModel::retryActivation,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onStartListening = requestMicrophoneForMode,
        onStopListening = viewModel::stopListening,
        onSendDraft = viewModel::sendDraftMessage,
        onDraftChanged = viewModel::updateDraftMessage,
        onSendMcp = viewModel::sendMcp,
        onClearLogs = viewModel::clearLogs,
        onOtaUrlChanged = viewModel::updateOtaUrl,
        onDeviceIdChanged = viewModel::updateDeviceId,
        onClientIdChanged = viewModel::updateClientId,
        onWebsocketUrlChanged = viewModel::updateWebsocketUrl,
        onTokenChanged = viewModel::updateAuthToken,
        onProtocolVersionChanged = viewModel::updateProtocolVersion,
        onMcpPayloadChanged = viewModel::updateMcpPayload,
        onWakeWordEnabledChanged = requestWakeWordEnable,
        onWakeWordsChanged = viewModel::updateWakeWords,
        onTermuxEnabledChanged = viewModel::updateTermuxEnabled,
        onPythonPathChanged = viewModel::updatePythonPath,
        onPythonScriptPathChanged = viewModel::updatePythonScriptPath,
        onPythonWorkdirChanged = viewModel::updatePythonWorkdir,
        onTermuxApiCommandChanged = viewModel::updateTermuxApiCommand,
        onTermuxApiArgumentsChanged = viewModel::updateTermuxApiArguments,
        onDebugLoggingEnabledChanged = viewModel::updateDebugLoggingEnabled,
        onDebugWavDumpEnabledChanged = viewModel::updateDebugWavDumpEnabled,
        onRunPythonScript = viewModel::runPythonScript,
        onRunTermuxApiCommand = viewModel::runTermuxApiCommand,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XiaozhiScreen(
    state: UiState,
    currentScreen: AppScreen,
    onOpenSettings: () -> Unit,
    onBackToChat: () -> Unit,
    onPickAssistantAvatar: () -> Unit,
    onFetchOfficialConfig: () -> Unit,
    onRetryActivation: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStartListening: (ListeningMode) -> Unit,
    onStopListening: () -> Unit,
    onSendDraft: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendMcp: () -> Unit,
    onClearLogs: () -> Unit,
    onOtaUrlChanged: (String) -> Unit,
    onDeviceIdChanged: (String) -> Unit,
    onClientIdChanged: (String) -> Unit,
    onWebsocketUrlChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onProtocolVersionChanged: (String) -> Unit,
    onMcpPayloadChanged: (String) -> Unit,
    onWakeWordEnabledChanged: (Boolean) -> Unit,
    onWakeWordsChanged: (String) -> Unit,
    onTermuxEnabledChanged: (Boolean) -> Unit,
    onPythonPathChanged: (String) -> Unit,
    onPythonScriptPathChanged: (String) -> Unit,
    onPythonWorkdirChanged: (String) -> Unit,
    onTermuxApiCommandChanged: (String) -> Unit,
    onTermuxApiArgumentsChanged: (String) -> Unit,
    onDebugLoggingEnabledChanged: (Boolean) -> Unit,
    onDebugWavDumpEnabledChanged: (Boolean) -> Unit,
    onRunPythonScript: () -> Unit,
    onRunTermuxApiCommand: () -> Unit,
) {
    Scaffold(
        containerColor = if (currentScreen == AppScreen.SETTINGS) SettingsBackground else ChatBackground,
        topBar = {
            if (currentScreen == AppScreen.SETTINGS) {
                TopAppBar(
                    title = { Text("配置中心", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBackToChat) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回聊天")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                )
            }
        },
    ) { padding ->
        when (currentScreen) {
            AppScreen.CHAT -> ChatScreen(
                state = state,
                padding = padding,
                onOpenSettings = onOpenSettings,
                onPickAssistantAvatar = onPickAssistantAvatar,
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onSendDraft = onSendDraft,
                onDraftChanged = onDraftChanged,
            )

            AppScreen.SETTINGS -> SettingsScreen(
                state = state,
                padding = padding,
                onFetchOfficialConfig = onFetchOfficialConfig,
                onRetryActivation = onRetryActivation,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onSendMcp = onSendMcp,
                onClearLogs = onClearLogs,
                onOtaUrlChanged = onOtaUrlChanged,
                onDeviceIdChanged = onDeviceIdChanged,
                onClientIdChanged = onClientIdChanged,
                onWebsocketUrlChanged = onWebsocketUrlChanged,
                onTokenChanged = onTokenChanged,
                onProtocolVersionChanged = onProtocolVersionChanged,
                onMcpPayloadChanged = onMcpPayloadChanged,
                onWakeWordEnabledChanged = onWakeWordEnabledChanged,
                onWakeWordsChanged = onWakeWordsChanged,
                onTermuxEnabledChanged = onTermuxEnabledChanged,
                onPythonPathChanged = onPythonPathChanged,
                onPythonScriptPathChanged = onPythonScriptPathChanged,
                onPythonWorkdirChanged = onPythonWorkdirChanged,
                onTermuxApiCommandChanged = onTermuxApiCommandChanged,
                onTermuxApiArgumentsChanged = onTermuxApiArgumentsChanged,
                onDebugLoggingEnabledChanged = onDebugLoggingEnabledChanged,
                onDebugWavDumpEnabledChanged = onDebugWavDumpEnabledChanged,
                onRunPythonScript = onRunPythonScript,
                onRunTermuxApiCommand = onRunTermuxApiCommand,
            )
        }
    }
}

@Composable
private fun ChatScreen(
    state: UiState,
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
    onPickAssistantAvatar: () -> Unit,
    onStartListening: (ListeningMode) -> Unit,
    onStopListening: () -> Unit,
    onSendDraft: () -> Unit,
    onDraftChanged: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.chatMessages.size) {
        if (state.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(state.chatMessages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatBackground)
            .padding(padding)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatHeaderCard(
            state = state,
            onOpenSettings = onOpenSettings,
            onPickAssistantAvatar = onPickAssistantAvatar,
        )

        if (state.activationPending) {
            ActivationBanner(
                activationCode = state.activationCode,
                activationMessage = state.activationMessage,
            )
        }

        ChatMessageList(
            messages = state.chatMessages,
            assistantAvatarPath = state.assistantAvatarPath,
            listState = listState,
            modifier = Modifier.weight(1f),
        )

        ComposerCard(
            state = state,
            onDraftChanged = onDraftChanged,
            onSendDraft = onSendDraft,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
        )
    }
}

@Composable
private fun SettingsScreen(
    state: UiState,
    padding: PaddingValues,
    onFetchOfficialConfig: () -> Unit,
    onRetryActivation: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSendMcp: () -> Unit,
    onClearLogs: () -> Unit,
    onOtaUrlChanged: (String) -> Unit,
    onDeviceIdChanged: (String) -> Unit,
    onClientIdChanged: (String) -> Unit,
    onWebsocketUrlChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onProtocolVersionChanged: (String) -> Unit,
    onMcpPayloadChanged: (String) -> Unit,
    onWakeWordEnabledChanged: (Boolean) -> Unit,
    onWakeWordsChanged: (String) -> Unit,
    onTermuxEnabledChanged: (Boolean) -> Unit,
    onPythonPathChanged: (String) -> Unit,
    onPythonScriptPathChanged: (String) -> Unit,
    onPythonWorkdirChanged: (String) -> Unit,
    onTermuxApiCommandChanged: (String) -> Unit,
    onTermuxApiArgumentsChanged: (String) -> Unit,
    onDebugLoggingEnabledChanged: (Boolean) -> Unit,
    onDebugWavDumpEnabledChanged: (Boolean) -> Unit,
    onRunPythonScript: () -> Unit,
    onRunTermuxApiCommand: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var developerExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsBackground)
            .padding(padding)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingsSummaryCard(state = state)

        SectionTitle("官方接入")
        SettingsCard {
            SettingsField(
                icon = "🌐",
                title = "OTA 地址",
                subtitle = "官方配置入口",
                value = state.otaUrl,
                onValueChange = onOtaUrlChanged,
            )
            HorizontalDivider()
            SettingsField(
                icon = "🧩",
                title = "设备 ID",
                subtitle = "握手头里的 Device-Id",
                value = state.deviceId,
                onValueChange = onDeviceIdChanged,
            )
            HorizontalDivider()
            SettingsField(
                icon = "🆔",
                title = "客户端 ID",
                subtitle = "握手头里的 Client-Id",
                value = state.clientId,
                onValueChange = onClientIdChanged,
            )
            HorizontalDivider()
            SettingsField(
                icon = "🔗",
                title = "WebSocket 地址",
                subtitle = "官方实时会话地址",
                value = state.websocketUrl,
                onValueChange = onWebsocketUrlChanged,
            )
            HorizontalDivider()
            SettingsField(
                icon = "🔐",
                title = "授权 Token",
                subtitle = "OTA 返回的 Bearer Token",
                value = state.authToken,
                onValueChange = onTokenChanged,
            )
            HorizontalDivider()
            SettingsField(
                icon = "🧾",
                title = "协议版本",
                subtitle = "官方 WebSocket 二进制协议版本",
                value = state.protocolVersion,
                onValueChange = onProtocolVersionChanged,
                keyboardType = KeyboardType.Number,
            )
            HorizontalDivider()
            SettingsActionRow(
                primaryLabel = "获取配置",
                onPrimaryClick = onFetchOfficialConfig,
                secondaryLabel = if (state.activationPending) "激活后重试" else "连接",
                onSecondaryClick = if (state.activationPending) onRetryActivation else onConnect,
                tertiaryLabel = if (state.activationPending) null else "断开",
                onTertiaryClick = if (state.activationPending) null else onDisconnect,
            )
        }

        SectionTitle("语音体验")
        SettingsCard {
            SettingsSwitch(
                icon = "🎙️",
                title = "语音唤醒",
                subtitle = state.wakeWordStatus.ifBlank { "未开启" },
                checked = state.wakeWordEnabled,
                onCheckedChange = onWakeWordEnabledChanged,
            )
            HorizontalDivider()
            SettingsField(
                icon = "🗣️",
                title = "唤醒词",
                subtitle = "多个唤醒词用英文逗号分隔",
                value = state.wakeWords,
                onValueChange = onWakeWordsChanged,
            )
            HorizontalDivider()
            SettingsStatus(
                icon = "🔊",
                title = "当前音频路由",
                subtitle = state.audioRouteStatus,
            )
            HorizontalDivider()
            SettingsStatus(
                icon = "📡",
                title = "连接状态",
                subtitle = state.connectionStatus.toChineseText(),
            )
        }

        SectionTitle("本地扩展")
        SettingsCard {
            SettingsSwitch(
                icon = "🧰",
                title = "Python / MCP 运行入口",
                subtitle = state.pythonRuntimeStatus.ifBlank { "未启用" },
                checked = state.termuxEnabled,
                onCheckedChange = onTermuxEnabledChanged,
            )
            if (state.termuxEnabled) {
                HorizontalDivider()
                SettingsField(
                    icon = "🐍",
                    title = "Python 可执行文件",
                    subtitle = "推荐使用 Termux 里的 Python",
                    value = state.pythonPath,
                    onValueChange = onPythonPathChanged,
                )
                HorizontalDivider()
                SettingsField(
                    icon = "📄",
                    title = "Python 脚本",
                    subtitle = "要启动的本地 MCP Python 文件",
                    value = state.pythonScriptPath,
                    onValueChange = onPythonScriptPathChanged,
                )
                HorizontalDivider()
                SettingsField(
                    icon = "📁",
                    title = "工作目录",
                    subtitle = "可留空，默认由 Termux 决定",
                    value = state.pythonWorkdir,
                    onValueChange = onPythonWorkdirChanged,
                )
                HorizontalDivider()
                SettingsAction(
                    icon = "▶️",
                    title = "启动本地 Python",
                    subtitle = "通过 Termux 拉起脚本，用于本地 MCP 服务",
                    actionLabel = "运行",
                    onClick = onRunPythonScript,
                )
                HorizontalDivider()
                SettingsField(
                    icon = "📲",
                    title = "Termux API 命令",
                    subtitle = state.termuxApiStatus.ifBlank { "未启用" },
                    value = state.termuxApiCommand,
                    onValueChange = onTermuxApiCommandChanged,
                )
                HorizontalDivider()
                SettingsField(
                    icon = "⌨️",
                    title = "Termux API 参数",
                    subtitle = "空格分隔，支持引号",
                    value = state.termuxApiArguments,
                    onValueChange = onTermuxApiArgumentsChanged,
                )
                HorizontalDivider()
                SettingsAction(
                    icon = "⚙️",
                    title = "执行 termux-api",
                    subtitle = "例如 termux-battery-status 或 termux-toast",
                    actionLabel = "执行",
                    onClick = onRunTermuxApiCommand,
                )
            }
        }

        SectionTitle("开发者工具")
        DeveloperToolsCard(
            expanded = developerExpanded,
            onToggle = { developerExpanded = !developerExpanded },
            state = state,
            onDebugLoggingEnabledChanged = onDebugLoggingEnabledChanged,
            onDebugWavDumpEnabledChanged = onDebugWavDumpEnabledChanged,
            onMcpPayloadChanged = onMcpPayloadChanged,
            onSendMcp = onSendMcp,
            onClearLogs = onClearLogs,
        )
    }
}

@Composable
private fun ChatHeaderCard(
    state: UiState,
    onOpenSettings: () -> Unit,
    onPickAssistantAvatar: () -> Unit,
) {
    val headline = when {
        state.isAssistantSpeaking -> "说话中"
        state.isRecording -> "聆听中"
        else -> "空闲"
    }

    Surface(
        color = Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderTint)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistantAvatar(
                        avatarPath = state.assistantAvatarPath,
                        modifier = Modifier.size(56.dp),
                        onClick = onPickAssistantAvatar,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "小智 AI",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = headline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilledTonalIconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }
        }
    }
}

@Composable
private fun ComposerCard(
    state: UiState,
    onDraftChanged: (String) -> Unit,
    onSendDraft: () -> Unit,
    onStartListening: (ListeningMode) -> Unit,
    onStopListening: () -> Unit,
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(30.dp),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isRecording || state.isAssistantSpeaking || state.lastSttText.isNotBlank()) {
                val hint = when {
                    state.isRecording -> "正在收音，松开或点停止结束"
                    state.isAssistantSpeaking -> "正在播报回复"
                    state.lastSttText.isNotBlank() -> state.lastSttText
                    else -> ""
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = {
                        if (state.isRecording) onStopListening()
                        else onStartListening(ListeningMode.MANUAL)
                    },
                ) {
                    Icon(
                        imageVector = if (state.isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (state.isRecording) "停止录音" else "语音输入",
                    )
                }
                OutlinedTextField(
                    value = state.draftMessage,
                    onValueChange = onDraftChanged,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendDraft() }),
                )
                FilledIconButton(
                    onClick = onSendDraft,
                    enabled = state.draftMessage.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    assistantAvatarPath: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val displayMessages = if (messages.isEmpty()) {
        listOf(
            ChatMessage(
                id = -1,
                role = ChatRole.ASSISTANT,
                text = "你好，我在。你可以直接对我说话，也可以打字。",
                timestamp = "",
            ),
        )
    } else {
        messages
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(displayMessages, key = { it.id }) { message ->
            ChatBubble(message = message, assistantAvatarPath = assistantAvatarPath)
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, assistantAvatarPath: String) {
    when (message.role) {
        ChatRole.SYSTEM -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ChatRole.USER -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        color = UserBubble,
                        contentColor = Color(0xFF14210A),
                        shape = RoundedCornerShape(20.dp, 8.dp, 20.dp, 20.dp),
                    ) {
                        Text(
                            text = message.text,
                            modifier = Modifier
                                .widthIn(max = 288.dp)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        )
                    }
                    if (message.timestamp.isNotBlank()) {
                        Text(
                            text = message.timestamp,
                            modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ChatRole.ASSISTANT -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                AssistantAvatar(
                    avatarPath = assistantAvatarPath,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Surface(
                        color = AssistantBubble,
                        shape = RoundedCornerShape(8.dp, 20.dp, 20.dp, 20.dp),
                        shadowElevation = 1.dp,
                    ) {
                        Text(
                            text = message.text,
                            modifier = Modifier
                                .widthIn(max = 288.dp)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        )
                    }
                    if (message.timestamp.isNotBlank()) {
                        Text(
                            text = message.timestamp,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantAvatar(
    avatarPath: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val containerModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    Surface(
        modifier = containerModifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
    ) {
        if (avatarPath.isNotBlank() && File(avatarPath).exists()) {
            AsyncImage(
                model = File(avatarPath),
                contentDescription = "小智头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "智",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ActivationBanner(
    activationCode: String,
    activationMessage: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "设备尚未激活",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "请前往 xiaozhi.me 控制台完成激活。",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (activationCode.isNotBlank()) {
                Text(
                    text = "激活码：$activationCode",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (activationMessage.isNotBlank()) {
                Text(
                    text = activationMessage,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SettingsSummaryCard(state: UiState) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "当前会话",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(text = state.connectionStatus.toChineseText(), status = state.connectionStatus)
                if (state.sessionId.isNotBlank()) {
                    StatusTag(text = "Session ${state.sessionId.take(8)}")
                }
            }
            TwoColumnInfo(
                leftTitle = "服务器采样率",
                leftValue = state.serverSampleRate.ifBlank { "未建立" },
                rightTitle = "帧时长",
                rightValue = state.serverFrameDuration.ifBlank { "--" },
            )
            TwoColumnInfo(
                leftTitle = "音频路由",
                leftValue = state.audioRouteStatus,
                rightTitle = "唤醒状态",
                rightValue = state.wakeWordStatus.ifBlank { "未开启" },
                compact = true,
            )
        }
    }
}

@Composable
private fun TwoColumnInfo(
    leftTitle: String,
    leftValue: String,
    rightTitle: String,
    rightValue: String,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoCell(
            title = leftTitle,
            value = leftValue,
            modifier = Modifier.weight(1f),
            compact = compact,
        )
        InfoCell(
            title = rightTitle,
            value = rightValue,
            modifier = Modifier.weight(1f),
            compact = compact,
        )
    }
}

@Composable
private fun InfoCell(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsStatus(
    icon: String,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsAction(
    icon: String,
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onClick) { Text(actionLabel) }
    }
}

@Composable
private fun SettingsSwitch(
    icon: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsField(
    icon: String,
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    minLines: Int = 1,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Text(text = icon)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            minLines = minLines,
            readOnly = readOnly,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(18.dp),
        )
    }
}

@Composable
private fun SettingsActionRow(
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String?,
    onSecondaryClick: (() -> Unit)?,
    tertiaryLabel: String? = null,
    onTertiaryClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onPrimaryClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(primaryLabel)
        }
        if (secondaryLabel != null && onSecondaryClick != null) {
            OutlinedButton(
                onClick = onSecondaryClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(secondaryLabel)
            }
        }
        if (tertiaryLabel != null && onTertiaryClick != null) {
            OutlinedButton(
                onClick = onTertiaryClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tertiaryLabel)
            }
        }
    }
}

@Composable
private fun DeveloperToolsCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    state: UiState,
    onDebugLoggingEnabledChanged: (Boolean) -> Unit,
    onDebugWavDumpEnabledChanged: (Boolean) -> Unit,
    onMcpPayloadChanged: (String) -> Unit,
    onSendMcp: () -> Unit,
    onClearLogs: () -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "开发者工具", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "调试日志、TTS 音频导出和 MCP 调试都收在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (expanded) {
            HorizontalDivider()
            SettingsSwitch(
                icon = "🪵",
                title = "调试日志",
                subtitle = "仅在需要排查问题时开启",
                checked = state.debugLoggingEnabled,
                onCheckedChange = onDebugLoggingEnabledChanged,
            )
            HorizontalDivider()
            SettingsSwitch(
                icon = "🎧",
                title = "TTS 音频导出",
                subtitle = "把播报 PCM 导出为 WAV 文件用于排查",
                checked = state.debugWavDumpEnabled,
                onCheckedChange = onDebugWavDumpEnabledChanged,
            )
            HorizontalDivider()
            SettingsField(
                icon = "🧪",
                title = "MCP 请求",
                subtitle = "发送到当前会话的 MCP JSON 负载",
                value = state.mcpPayload,
                onValueChange = onMcpPayloadChanged,
                singleLine = false,
                minLines = 5,
            )
            HorizontalDivider()
            SettingsActionRow(
                primaryLabel = "发送 MCP",
                onPrimaryClick = onSendMcp,
                secondaryLabel = "清空日志",
                onSecondaryClick = onClearLogs,
            )
            if (state.debugLoggingEnabled) {
                LogPane(lines = state.logs)
            }
        }
    }
}

@Composable
private fun LogPane(lines: List<LogLine>) {
    val body = if (lines.isEmpty()) {
        "暂无日志。"
    } else {
        lines.takeLast(160).joinToString(separator = "\n") { "[${it.timestamp}] ${it.message}" }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
    ) {
        SelectionContainer {
            Text(
                text = body,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    status: ConnectionStatus,
) {
    val containerColor = when (status) {
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
        ConnectionStatus.FETCHING_CONFIG,
        ConnectionStatus.ACTIVATING,
        ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (status) {
        ConnectionStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.onTertiaryContainer
        ConnectionStatus.CONNECTING,
        ConnectionStatus.FETCHING_CONFIG,
        ConnectionStatus.ACTIVATING -> MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun StatusTag(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.9f),
        shape = CircleShape,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ConnectionStatus.toChineseText(): String {
    return when (this) {
        ConnectionStatus.DISCONNECTED -> "未连接"
        ConnectionStatus.FETCHING_CONFIG -> "获取配置中"
        ConnectionStatus.ACTIVATING -> "激活中"
        ConnectionStatus.CONNECTING -> "连接中"
        ConnectionStatus.CONNECTED -> "已连接"
        ConnectionStatus.FAILED -> "连接失败"
    }
}
