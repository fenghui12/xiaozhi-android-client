package me.xiaozhi.androidclient

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import java.io.File
import android.widget.VideoView
import android.view.ViewGroup
import android.graphics.Bitmap
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
import me.xiaozhi.androidclient.audio.SherpaWakeWordRecognizer
import me.xiaozhi.androidclient.model.ChatMessage
import me.xiaozhi.androidclient.model.ChatRole
import me.xiaozhi.androidclient.model.ConnectionStatus
import me.xiaozhi.androidclient.model.ListeningMode
import me.xiaozhi.androidclient.model.LogLine
import me.xiaozhi.androidclient.model.RoleProfile
import me.xiaozhi.androidclient.model.ScheduledTaskUi
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.model.DigitalHumanSlot
import me.xiaozhi.androidclient.digitalhuman.DigitalHumanAssetManager
import me.xiaozhi.androidclient.digitalhuman.LanVideoUploadServer
import me.xiaozhi.androidclient.digitalhuman.VideoUploadSession
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import me.xiaozhi.androidclient.model.hasCompleteDigitalHuman
import me.xiaozhi.androidclient.model.videoPath
import me.xiaozhi.androidclient.model.canRunWakeWordRecognizer
import me.xiaozhi.androidclient.ui.MainViewModel
import me.xiaozhi.androidclient.ui.theme.XiaozhiClientTheme

private enum class AppScreen { CHAT, SETTINGS }

private sealed interface PendingAudioAction {
    data class StartListening(val mode: ListeningMode) : PendingAudioAction
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
    var uploadSession by remember { mutableStateOf<VideoUploadSession?>(null) }
    var uploadSuccessMessage by remember { mutableStateOf<String?>(null) }
    val uploadServer = remember {
        LanVideoUploadServer(context, DigitalHumanAssetManager(context)) { role, slot, path ->
            viewModel.updateRoleVideoPath(role.id, slot, path)
            uploadSuccessMessage = "${role.displayName}的${slot.label}视频导入成功！"
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.CHAT) }
    var isForeground by remember { mutableStateOf(true) }
    var pendingAudioAction by remember { mutableStateOf<PendingAudioAction?>(null) }
    var playbackCooldownActive by remember { mutableStateOf(false) }
    var avatarRoleId by remember { mutableStateOf<String?>(null) }
    var videoImportTarget by remember { mutableStateOf<Pair<String, DigitalHumanSlot>?>(null) }

    val wakeWordRecognizer = remember(context) {
        SherpaWakeWordRecognizer(
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

            null -> Unit
        }
        pendingAudioAction = null
    }

    val avatarPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val roleId = avatarRoleId
        if (uri != null && roleId != null) {
            viewModel.importRoleAvatar(roleId, uri)
        }
        avatarRoleId = null
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val target = videoImportTarget
        if (uri != null && target != null) viewModel.importRoleVideo(target.first, target.second, uri)
        videoImportTarget = null
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
        state.roleWakeWords,
        state.isRecording,
        state.isAssistantSpeaking,
        state.isTurnActive,
        state.connectionStatus,
        state.isSilentTransportRecovery,
        playbackCooldownActive,
        isForeground,
    ) {
        val shouldRun = isForeground &&
            state.wakeWordEnabled &&
            !state.isRecording &&
            !state.isAssistantSpeaking &&
            !state.isTurnActive &&
            !playbackCooldownActive &&
            state.canRunWakeWordRecognizer()
        if (shouldRun) wakeWordRecognizer.start(state.roleWakeWords.ifBlank { state.wakeWords })
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

    XiaozhiScreen(
        state = latestState,
        currentScreen = currentScreen,
        onOpenSettings = { currentScreen = AppScreen.SETTINGS },
        onBackToChat = { currentScreen = AppScreen.CHAT },
        onPickRoleAvatar = { roleId ->
            avatarRoleId = roleId
            avatarPickerLauncher.launch("image/*")
        },
        onPickRoleVideo = { roleId, slot ->
            videoImportTarget = roleId to slot
            videoPickerLauncher.launch("video/*")
        },
        onStartListening = requestMicrophoneForMode,
        onStopListening = viewModel::stopListening,
        onSendDraft = viewModel::sendDraftMessage,
        onDraftChanged = viewModel::updateDraftMessage,
        onSelectRole = viewModel::selectRole,
        onAddRole = viewModel::addRole,
        onUpdateRole = viewModel::updateRole,
        onDeleteRole = viewModel::deleteRole,
        onStartVideoUpload = { roleId, slot ->
            state.roleProfiles.firstOrNull { it.id == roleId }?.let { role ->
                runCatching { uploadSession = uploadServer.start(role, slot) }
            }
        },
    )
    if (uploadSession != null) {
        UploadQrDialog(
            uploadSession = uploadSession!!,
            successMessage = uploadSuccessMessage,
            onDismiss = {
                uploadServer.stop()
                uploadSession = null
                uploadSuccessMessage = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XiaozhiScreen(
    state: UiState,
    currentScreen: AppScreen,
    onOpenSettings: () -> Unit,
    onBackToChat: () -> Unit,
    onPickRoleAvatar: (String) -> Unit,
    onPickRoleVideo: (String, DigitalHumanSlot) -> Unit,
    onStartListening: (ListeningMode) -> Unit,
    onStopListening: () -> Unit,
    onSendDraft: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSelectRole: (String) -> Unit,
    onAddRole: (String, String) -> Unit,
    onUpdateRole: (String, String, String) -> Unit,
    onDeleteRole: (String) -> Unit,
    onStartVideoUpload: (String, DigitalHumanSlot) -> Unit,
) {
    Scaffold(
        containerColor = if (currentScreen == AppScreen.SETTINGS) SettingsBackground else ChatBackground,
        topBar = {
            if (currentScreen == AppScreen.SETTINGS) {
                TopAppBar(
                    title = { Text("角色设置", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBackToChat) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回聊天")
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
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onSendDraft = onSendDraft,
                onDraftChanged = onDraftChanged,
            )

            AppScreen.SETTINGS -> SettingsScreen(
                state = state,
                padding = padding,
                onSelectRole = onSelectRole,
                onPickRoleAvatar = onPickRoleAvatar,
                onPickRoleVideo = onPickRoleVideo,
                onAddRole = onAddRole,
                onUpdateRole = onUpdateRole,
                onDeleteRole = onDeleteRole,
                onStartVideoUpload = onStartVideoUpload,
            )
        }
    }
}

@Composable
private fun ChatScreen(
    state: UiState,
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
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
        )

        if (state.activationPending) {
            ActivationBanner(
                activationCode = state.activationCode,
                activationMessage = state.activationMessage,
            )
        }

        if (state.scheduledTasks.isNotEmpty()) {
            CurrentTasksPanel(tasks = state.scheduledTasks)
        }

        if (state.activeRoleDigitalHumanReady) {
            DigitalHumanPanel(state = state, modifier = Modifier.weight(1f))
        } else {
            ChatMessageList(
                messages = state.chatMessages,
                assistantAvatarPath = state.activeRoleAvatarPath,
                assistantAvatarText = state.activeRoleName.takeLast(1).ifBlank { "智" },
                listState = listState,
                modifier = Modifier.weight(1f),
            )
        }

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
private fun DigitalHumanPanel(state: UiState, modifier: Modifier = Modifier) {
    val path = when {
        state.isAssistantSpeaking -> state.activeRoleSpeakingVideoPath
        state.isRecording || state.isTurnActive -> state.activeRoleListeningVideoPath
        else -> state.activeRoleIdleVideoPath
    }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            VideoView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnPreparedListener { player ->
                    player.isLooping = true
                    player.setVolume(0f, 0f)
                    start()
                }
            }
        },
        update = { view ->
            if (path.isBlank() || !File(path).exists()) {
                view.stopPlayback()
            } else if (view.tag != path) {
                view.tag = path
                view.setVideoPath(path)
                view.start()
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

@Composable
private fun UploadQrDialog(
    uploadSession: VideoUploadSession,
    successMessage: String?,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(uploadSession.url) {
        val matrix = MultiFormatWriter().encode(uploadSession.url, BarcodeFormat.QR_CODE, 720, 720)
        Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).also { image ->
            for (x in 0 until 720) for (y in 0 until 720) {
                image.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手机扫码导入${uploadSession.slot.label}") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (successMessage != null) {
                    Surface(
                        color = Color(0xFFECFDF5),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "🎉 $successMessage",
                            color = Color(0xFF065F46),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "视频上传二维码",
                    modifier = Modifier.size(240.dp),
                )
                Text(
                    "手机连接同一 WiFi 扫码，选择 MP4 视频直接上传",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (successMessage != null) "完成并关闭" else "关闭")
            }
        },
    )
}

@Composable
private fun CurrentTasksPanel(tasks: List<ScheduledTaskUi>) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "当前任务",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = tasks.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tasks.forEachIndexed { index, task ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                CurrentTaskRow(task)
            }
        }
    }
}

@Composable
private fun CurrentTaskRow(task: ScheduledTaskUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (task.kind == "监督提醒") Icons.Default.Visibility else Icons.Default.Timer,
            contentDescription = null,
            tint = if (task.kind == "监督提醒") Color(0xFFB45309) else Color(0xFF2563EB),
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = task.message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${task.kind} · ${task.status}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = task.remainingSeconds?.let(::formatTaskCountdown) ?: "--",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatTaskCountdown(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes, seconds)
        else -> "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun SettingsScreen(
    state: UiState,
    padding: PaddingValues,
    onSelectRole: (String) -> Unit,
    onPickRoleAvatar: (String) -> Unit,
    onPickRoleVideo: (String, DigitalHumanSlot) -> Unit,
    onAddRole: (String, String) -> Unit,
    onUpdateRole: (String, String, String) -> Unit,
    onDeleteRole: (String) -> Unit,
    onStartVideoUpload: (String, DigitalHumanSlot) -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsBackground)
            .padding(padding)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        RoleProfilesCard(
            state = state,
            onSelectRole = onSelectRole,
            onPickRoleAvatar = onPickRoleAvatar,
            onPickRoleVideo = onPickRoleVideo,
            onStartVideoUpload = onStartVideoUpload,
            onAddRole = onAddRole,
            onUpdateRole = onUpdateRole,
            onDeleteRole = onDeleteRole,
        )
    }
}

@Composable
private fun ChatHeaderCard(
    state: UiState,
    onOpenSettings: () -> Unit,
) {
    val headline = when {
        state.isAssistantSpeaking -> "讲话中"
        state.isRecording -> "聆听中"
        state.isTurnActive -> "聆听中"
        state.connectionStatus == ConnectionStatus.CONNECTED -> "待机中"
        state.isSilentTransportRecovery -> "待机中"
        state.activationPending -> "待激活"
        state.connectionStatus == ConnectionStatus.FETCHING_CONFIG -> "获取配置中"
        state.connectionStatus == ConnectionStatus.CONNECTING -> "连接中"
        state.connectionStatus == ConnectionStatus.FAILED -> "连接失败"
        else -> "未连接"
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
                        avatarPath = state.activeRoleAvatarPath,
                        fallbackText = state.activeRoleName.takeLast(1).ifBlank { "智" },
                        modifier = Modifier.size(56.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "语音 AI 交互",
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
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "打开设置")
                }
            }
        }
    }
}

@Composable
private fun RoleProfilesCard(
    state: UiState,
    onSelectRole: (String) -> Unit,
    onPickRoleAvatar: (String) -> Unit,
    onPickRoleVideo: (String, DigitalHumanSlot) -> Unit,
    onStartVideoUpload: (String, DigitalHumanSlot) -> Unit,
    onAddRole: (String, String) -> Unit,
    onUpdateRole: (String, String, String) -> Unit,
    onDeleteRole: (String) -> Unit,
) {
    var editingRoleId by remember { mutableStateOf<String?>(null) }
    var addingRole by remember { mutableStateOf(false) }
    val editingRole = state.roleProfiles.firstOrNull { it.id == editingRoleId }

    SettingsCard {
        state.roleProfiles.forEachIndexed { index, role ->
            RoleProfileRow(
                role = role,
                active = role.id == state.activeRoleId,
                onSelect = { onSelectRole(role.id) },
                onEdit = { editingRoleId = role.id },
                onDelete = { onDeleteRole(role.id) },
            )
            if (index < state.roleProfiles.lastIndex) HorizontalDivider()
        }
        HorizontalDivider()
        TextButton(
            onClick = { addingRole = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("添加角色")
        }
    }

    if (editingRole != null || addingRole) {
        RoleEditorDialog(
            role = editingRole,
            onPickAvatar = { editingRole?.id?.let(onPickRoleAvatar) },
            onPickVideo = { slot -> editingRole?.id?.let { onPickRoleVideo(it, slot) } },
            onStartVideoUpload = { slot -> editingRole?.id?.let { onStartVideoUpload(it, slot) } },
            onDismiss = {
                editingRoleId = null
                addingRole = false
            },
            onSave = { name, wakeWords ->
                val role = editingRole
                if (role == null) onAddRole(name, wakeWords)
                else onUpdateRole(role.id, name, wakeWords)
                editingRoleId = null
                addingRole = false
            },
        )
    }
}

@Composable
private fun RoleProfileRow(
    role: RoleProfile,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantAvatar(
                avatarPath = role.avatarPath,
                fallbackText = role.displayName.takeLast(1),
                modifier = Modifier.size(46.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(role.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    text = when {
                        active -> "当前使用 · ${role.wakeWords.joinToString("、")}"
                        role.isBound -> "已绑定 · ${role.wakeWords.joinToString("、")}"
                        else -> "未绑定 · ${role.wakeWords.joinToString("、")}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) StatusTag(text = "当前")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("编辑") }
            OutlinedButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun RoleEditorDialog(
    role: RoleProfile?,
    onPickAvatar: () -> Unit,
    onPickVideo: (DigitalHumanSlot) -> Unit,
    onStartVideoUpload: (DigitalHumanSlot) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(role?.id) { mutableStateOf(role?.displayName.orEmpty()) }
    var wakeWords by remember(role?.id) { mutableStateOf(role?.wakeWords?.joinToString(", ").orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (role == null) "添加角色" else "编辑角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (role != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistantAvatar(
                            avatarPath = role.avatarPath,
                            fallbackText = role.displayName.takeLast(1),
                            modifier = Modifier.size(64.dp),
                        )
                        OutlinedButton(onClick = onPickAvatar) { Text("更换头像") }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    singleLine = true,
                )
                if (role != null) {
                    Text("角色形象视频", fontWeight = FontWeight.SemiBold)
                    DigitalHumanSlot.entries.forEach { slot ->
                        val configured = role.videoPath(slot).isNotBlank() && File(role.videoPath(slot)).exists()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (configured) "${slot.label} · 已配置" else "${slot.label} · 未配置")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { onPickVideo(slot) }) { Text("本机") }
                                OutlinedButton(onClick = { onStartVideoUpload(slot) }) { Text("扫码") }
                            }
                        }
                    }
                    Text(
                        text = if (role.hasCompleteDigitalHuman()) "四段视频已齐全，将启用角色形象" else "需要配置完整四段视频才会启用角色形象",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = wakeWords,
                    onValueChange = { wakeWords = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("唤醒词") },
                    supportingText = { Text("多个唤醒词用逗号分隔") },
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, wakeWords) },
                enabled = name.isNotBlank() && wakeWords.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
                    state.isRecording -> "正在聆听，说完会自动结束"
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
                    enabled = state.isRecording || (!state.isAssistantSpeaking && !state.isTurnActive),
                    onClick = {
                        if (state.isRecording) onStopListening()
                        else onStartListening(ListeningMode.AUTO)
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
                    enabled = state.draftMessage.isNotBlank() &&
                        !state.isRecording &&
                        !state.isAssistantSpeaking &&
                        !state.isTurnActive,
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
    assistantAvatarText: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val displayMessages = if (messages.isEmpty()) {
        listOf(
            ChatMessage(
                id = -1,
                role = ChatRole.ASSISTANT,
                text = "你好，我在。你可以直接对我说话。",
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
            ChatBubble(
                message = message,
                assistantAvatarPath = assistantAvatarPath,
                assistantAvatarText = assistantAvatarText,
            )
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    assistantAvatarPath: String,
    assistantAvatarText: String,
) {
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
                    fallbackText = assistantAvatarText,
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
    fallbackText: String = "智",
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
                    text = fallbackText,
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
