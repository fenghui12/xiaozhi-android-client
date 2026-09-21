package me.xiaozhi.androidclient

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import java.io.File
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.xiaozhi.androidclient.debug.DebugControlServer
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import coil.compose.AsyncImage
import me.xiaozhi.androidclient.audio.SherpaWakeWordRecognizer
import me.xiaozhi.androidclient.model.ChatMessage
import me.xiaozhi.androidclient.model.ChatRole
import me.xiaozhi.androidclient.model.ConnectionStatus
import me.xiaozhi.androidclient.model.ListeningMode
import me.xiaozhi.androidclient.model.LogLine
import me.xiaozhi.androidclient.model.RoleProfile
import me.xiaozhi.androidclient.util.ImageSampling
import me.xiaozhi.androidclient.model.ScheduledTaskUi
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.model.DigitalHumanSlot
import me.xiaozhi.androidclient.digitalhuman.CoverVideoView
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

// 配色：原方案是「淡紫底 + 亮绿气泡」，饱和度互相打架，看着像开发默认主题。
// 换成一套冷调、低饱和、层级清楚的方案——底浅、卡片白、用户气泡用一个明确的品牌蓝。
private val ChatBackground = Color(0xFFF1F5FA)
private val SettingsBackground = Color(0xFFF1F5FA)
private val UserBubble = Color(0xFF3E7BFA)
private val AssistantBubble = Color.White
private val HeaderTint = Color(0xFFE8F0FC)
private val BrandAccent = Color(0xFF3E7BFA)
private val BrandAccentSoft = Color(0xFFDCE8FF)

/**
 * 「打断」按钮的红。比 Material 默认的 error 色更饱和、更亮，
 * 因为它是数字人画面上唯一一个需要在「正在播报」的瞬间被一眼找到的控件。
 */
private val InterruptRed = Color(0xFFE53935)

/**
 * 全局文字放大系数。测试者第一条反馈就是“字体有点小”，
 * 这台设备在桌面上是隔着一臂距离看的，默认字号偏小。
 * 只放大 sp（文字），不改变 dp 布局尺寸，所以排版不会跟着散架。
 */
private const val UI_FONT_SCALE = 1.18f

/** 启动进度界面最多挡这么久，避免没网时把用户锁在 Loading 上。 */
private const val STARTUP_OVERLAY_TIMEOUT_MS = 25_000L

/** 自检界面最短显示时长——太短就读不到自检结论，等于没做自检。 */
private const val STARTUP_OVERLAY_MIN_MS = 2_500L

// 立绘解码的像素上限与采样率算法已挪到 util/ImageSampling.kt —— 那是纯函数，
// 可以在 JVM 单元测试里直接验证，不必起真机。这里不再保留同名常量，避免两份定义走偏。

/**
 * 「当前任务」列表的最大高度（约 3 行）。超出部分滚动查看。
 *
 * 目的是**给底部的麦克风和「打断」按钮留出位置**：定时任务条数没有上限，
 * 不限高的话任务面板会把底部操作条顶出屏幕，那两个最常用的按钮就点不到了。
 */
private val TASK_LIST_MAX_HEIGHT = 186.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让 App 能盖在锁屏之上显示，并在开机后点亮屏幕：
        // 测试机上出现过“开机后黑屏、要上滑解锁才进得去”。
        // 这两个 API 是 API 27 起才有的，而 minSdk 是 26，所以必须判断版本；
        // manifest 里对应的两个属性在低版本上会被系统忽略，不会崩。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableEdgeToEdge()
        setContent {
            XiaozhiClientTheme {
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density,
                        fontScale = baseDensity.fontScale * UI_FONT_SCALE,
                    ),
                ) {
                    XiaozhiApp()
                }
            }
        }
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
    var portraitRoleId by remember { mutableStateOf<String?>(null) }
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
    val portraitPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val roleId = portraitRoleId
        if (uri != null && roleId != null) {
            viewModel.importRolePortrait(roleId, uri)
        }
        portraitRoleId = null
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

    // 开发期调试接口：只在 debug 构建里存在，绑定 127.0.0.1，
    // 从开发机用 `adb forward tcp:8898 tcp:8898` 访问，不暴露到局域网。
    if (BuildConfig.DEBUG) {
        val debugServer = remember {
            DebugControlServer(
                stateJson = { stateToJson(latestState) },
                logsJson = { since -> logsToJson(latestState, since) },
                onSendText = { text ->
                    val result = viewModel.submitExternalMessage(text)
                    """{"ok":true,"result":${DebugControlServer.quote(result)}}"""
                },
                onAction = { name ->
                    val result = when (name) {
                        "listen" -> {
                            requestMicrophoneForMode(ListeningMode.AUTO)
                            "已请求开始聆听"
                        }
                        "stop" -> {
                            viewModel.stopListening()
                            "已停止录音"
                        }
                        "abort" -> {
                            viewModel.abortSpeaking()
                            "已请求打断播报"
                        }
                        "settings" -> {
                            currentScreen = AppScreen.SETTINGS
                            "已切到设置"
                        }
                        "chat" -> {
                            currentScreen = AppScreen.CHAT
                            "已切到聊天"
                        }
                        "clear_media" -> {
                            viewModel.clearRoleMedia(latestState.activeRoleId)
                            "已清空当前角色的数字人素材"
                        }
                        "import_demo_media" -> {
                            val dir = File(context.getExternalFilesDir(null), "demo_media")
                            viewModel.importDemoMediaFromDirectory(latestState.activeRoleId, dir)
                        }
                        "prepare_second_role" -> {
                            val dir = File(context.getExternalFilesDir(null), "demo_media")
                            viewModel.debugPrepareSecondRole(dir)
                        }
                        "cycle_role" -> viewModel.debugCycleRole()
                        "import_demo_portrait" -> {
                            val file = File(context.getExternalFilesDir(null), "demo_media/portrait.jpg")
                            viewModel.importRolePortraitFromFile(latestState.activeRoleId, file)
                        }
                        "clear_portrait" -> {
                            viewModel.clearRolePortrait(latestState.activeRoleId)
                            "已清除当前角色立绘"
                        }
                        else -> "未知动作：$name"
                    }
                    """{"ok":true,"result":${DebugControlServer.quote(result)}}"""
                },
            )
        }
        DisposableEffect(Unit) {
            debugServer.start()
            onDispose { debugServer.stop() }
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
        onPickRolePortrait = { roleId ->
            portraitRoleId = roleId
            portraitPickerLauncher.launch("image/*")
        },
        onPickRoleVideo = { roleId, slot ->
            videoImportTarget = roleId to slot
            videoPickerLauncher.launch("video/*")
        },
        onSelectRole = viewModel::selectRole,
        onAddRole = viewModel::addRole,
        onUpdateRole = viewModel::updateRole,
        onDeleteRole = viewModel::deleteRole,
        onCheckForUpdate = viewModel::checkForAppUpdate,
        onStartUpdate = viewModel::startDownloadAndInstallUpdate,
        onStartVideoUpload = { roleId, slot ->
            latestState.roleProfiles.firstOrNull { it.id == roleId }?.let { role ->
                runCatching {
                    uploadSession = uploadServer.start(role, slot)
                }.onFailure { error ->
                    android.util.Log.e("LanVideoUpload", "Failed to start upload server", error)
                }
            } ?: run {
                android.util.Log.e("LanVideoUpload", "Role not found for id: $roleId")
            }
        },
        onAbortSpeaking = viewModel::abortSpeaking,
        onClearRoleAvatar = { roleId -> viewModel.clearRoleAvatar(roleId) },
        onClearRolePortrait = { roleId -> viewModel.clearRolePortrait(roleId) },
        onClearRoleVideo = { roleId, slot -> viewModel.clearRoleVideo(roleId, slot) },
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

/** 当前应该播放哪一段数字人视频——判定逻辑与 DigitalHumanPanel 保持一致。 */
private fun digitalHumanVideoName(state: UiState): String {
    val path = when {
        state.isAssistantSpeaking -> state.activeRoleSpeakingVideoPath
        state.isRecording || state.isTurnActive -> state.activeRoleListeningVideoPath
        else -> state.activeRoleIdleVideoPath
    }
    return path.substringAfterLast('/')
}

/** 调试接口 /state 的快照：只挑断言需要的字段，不做全量序列化。 */private fun stateToJson(state: UiState): String {
    val messages = JSONArray()
    state.chatMessages.forEach { message ->
        messages.put(
            JSONObject()
                .put("role", message.role.name)
                .put("text", message.text)
                .put("at", message.timestamp),
        )
    }
    val tasks = JSONArray()
    state.scheduledTasks.forEach { task ->
        tasks.put(
            JSONObject()
                .put("id", task.id)
                .put("kind", task.kind)
                .put("message", task.message)
                .put("status", task.status)
                .put("remainingSeconds", task.remainingSeconds ?: -1L),
        )
    }
    return JSONObject()
        .put("connectionStatus", state.connectionStatus.name)
        .put("isRecording", state.isRecording)
        .put("isAssistantSpeaking", state.isAssistantSpeaking)
        .put("isTurnActive", state.isTurnActive)
        .put("activeRoleId", state.activeRoleId)
        .put("activeRoleName", state.activeRoleName)
        .put("digitalHumanReady", state.activeRoleDigitalHumanReady)
        .put("digitalHumanVideo", digitalHumanVideoName(state))
        .put("wakeWordStatus", state.wakeWordStatus)
        .put("lastSttText", state.lastSttText)
        .put("lastTtsText", state.lastTtsText)
        .put("versionName", state.appVersionName)
        .put("versionCode", state.appVersionCode)
        .put("chatMessages", messages)
        .put("scheduledTasks", tasks)
        .toString()
}

/** 调试接口 /log?since=N 的增量日志。 */
private fun logsToJson(state: UiState, since: Int): String {
    val array = JSONArray()
    state.logs.drop(since.coerceAtLeast(0)).forEach { line ->
        array.put(JSONObject().put("at", line.timestamp).put("message", line.message))
    }
    return JSONObject()
        .put("total", state.logs.size)
        .put("since", since)
        .put("lines", array)
        .toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XiaozhiScreen(
    state: UiState,
    currentScreen: AppScreen,
    onOpenSettings: () -> Unit,
    onBackToChat: () -> Unit,
    onPickRoleAvatar: (String) -> Unit,
    onPickRolePortrait: (String) -> Unit,
    onPickRoleVideo: (String, DigitalHumanSlot) -> Unit,
    onSelectRole: (String) -> Unit,
    onAddRole: (String, String) -> Unit,
    onUpdateRole: (String, String, String) -> Unit,
    onDeleteRole: (String) -> Unit,
    onCheckForUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
    onStartVideoUpload: (String, DigitalHumanSlot) -> Unit,
    onAbortSpeaking: () -> Unit,
    onClearRoleAvatar: (String) -> Unit,
    onClearRolePortrait: (String) -> Unit,
    onClearRoleVideo: (String, DigitalHumanSlot) -> Unit,
) {
    var everConnected by rememberSaveable { mutableStateOf(false) }
    var startupGraceElapsed by rememberSaveable { mutableStateOf(false) }
    // 自检界面至少要让人看清自检结果。实测 App 只要一两秒就连上了，
    // "连上就让位"会让整层一闪而过——那几行自检结论根本来不及读，等于没做自检。
    var startupMinShown by remember { mutableStateOf(false) }
    LaunchedEffect(state.connectionStatus) {
        if (state.connectionStatus == ConnectionStatus.CONNECTED) everConnected = true
    }
    LaunchedEffect(Unit) {
        delay(STARTUP_OVERLAY_MIN_MS)
        startupMinShown = true
    }
    LaunchedEffect(Unit) {
        delay(STARTUP_OVERLAY_TIMEOUT_MS)
        startupGraceElapsed = true
    }
    // 用户看到麦克风自检失败的提示后，可以主动收起这层继续用（避免卡死）。
    var startupOverlayDismissed by rememberSaveable { mutableStateOf(false) }

    // 连上过一次、或者等了足够久（没网也不能永远挡着界面），就让位给正常界面。
    //
    // **但麦克风自检没通过时不让位。** 实测 App 连得太快，"连上就让位"会让这层只闪
    // 不到两秒——那行红色的「没有检测到摄像头麦克风」根本来不及看，等于没提示。
    // 而麦克风掉了意味着这台设备**完全没法交互**（它唯一的麦克风在 USB 摄像头上），
    // 正是最该把话说清楚的时候。想继续用的人可以点「仍然继续」。
    val showStartupOverlay = !startupOverlayDismissed &&
        (
            !startupMinShown ||
                (!everConnected && !startupGraceElapsed) ||
                !state.microphoneReady
            )

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
        Box(modifier = Modifier.fillMaxSize()) {
            when (currentScreen) {
                AppScreen.CHAT -> ChatScreen(
                    state = state,
                    padding = padding,
                    onOpenSettings = onOpenSettings,
                    onAbortSpeaking = onAbortSpeaking,
                )

                AppScreen.SETTINGS -> SettingsScreen(
                    state = state,
                    padding = padding,
                    onSelectRole = onSelectRole,
                    onPickRoleAvatar = onPickRoleAvatar,
                    onPickRolePortrait = onPickRolePortrait,
                    onPickRoleVideo = onPickRoleVideo,
                    onAddRole = onAddRole,
                    onUpdateRole = onUpdateRole,
                    onDeleteRole = onDeleteRole,
                    onCheckForUpdate = onCheckForUpdate,
                    onStartUpdate = onStartUpdate,
                    onStartVideoUpload = onStartVideoUpload,
                    onClearRoleAvatar = onClearRoleAvatar,
                    onClearRolePortrait = onClearRolePortrait,
                    onClearRoleVideo = onClearRoleVideo,
                )
            }

            // 冷启动期间盖一层明确的进度界面：测试者把“开机后什么都没有”
            // 直接理解成了死机，第一反应是拔电源。
            if (showStartupOverlay) {
                StartupLoadingOverlay(
                    state = state,
                    // 只有"麦克风坏了且不是刚开机那几秒"才给出口，
                    // 免得正常启动时冒出一个多余的按钮。
                    onContinueAnyway = if (!state.microphoneReady && startupMinShown) {
                        { startupOverlayDismissed = true }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 启动自检期间的全屏进度提示；连上过一次或超时后自动让位。 */
@Composable
private fun StartupLoadingOverlay(state: UiState, onContinueAnyway: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CircularProgressIndicator(color = BrandAccent, strokeWidth = 4.dp)
            Text(
                text = "小智正在启动",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when (state.connectionStatus) {
                    ConnectionStatus.FETCHING_CONFIG -> "正在获取云端配置…"
                    ConnectionStatus.CONNECTING -> "正在连接小智服务…"
                    ConnectionStatus.FAILED -> "网络暂时不通，正在重试…"
                    else -> "正在开机自检…"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 摄像头麦克风自检。**这是"能不能交互"的前提**：本机唯一的麦克风就在
            // USB 摄像头上，它掉了设备既听不见也看不见。以前这种情况界面上只有一句
            // 「正在连接小智服务…」，用户根本不知道程序停在哪，只能猜。现在直接写出来。
            if (state.microphoneReady) {
                Text(
                    text = "麦克风：已就绪 ✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF16A34A),
                )
            } else {
                Surface(
                    color = Color(0xFFFEF2F2),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = "⚠ ${state.microphoneMessage.ifBlank { "没有检测到摄像头麦克风" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB91C1C),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            if (onContinueAnyway != null) {
                Text(
                    text = "接好摄像头后这行提示会自动消失，不用重启",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onContinueAnyway) { Text("仍然继续") }
            } else {
                Text(
                    text = "首次启动需要联网自检，通常几秒钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatScreen(
    state: UiState,
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
    onAbortSpeaking: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 数字人就绪时是否盖住聊天区。用户可以随时切回聊天——
    // 之前这里没有开关，上传视频后界面“回不去”，测试者最后靠删角色才恢复。
    var showDigitalHuman by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(state.chatMessages.size) {
        if (state.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(state.chatMessages.lastIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 角色形象有两条路：四段视频齐全就播动图，否则有立绘就放静态大图。
        val hasCharacterVisual =
            state.activeRoleDigitalHumanReady || state.activeRolePortraitPath.isNotBlank()
        val digitalHumanVisible = hasCharacterVisual && showDigitalHuman
        if (digitalHumanVisible) {
            // 角色画面铺满整屏（含状态栏区域），控制条与输入框浮在画面之上。
            // 之前它只占「被顶栏和输入框挤扁」的那块区域，还要按比例内缩，
            // 角色显得明显偏小。
            if (state.activeRoleDigitalHumanReady) {
                DigitalHumanPanel(state = state, modifier = Modifier.fillMaxSize())
            } else {
                PortraitPanel(path = state.activeRolePortraitPath, modifier = Modifier.fillMaxSize())
            }
            if (state.isAssistantSpeaking) {
                // 播报中：点画面任意处立刻打断，不用先找按钮。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onAbortSpeaking() },
                )
            }
        } else {
            Image(
                painter = painterResource(R.drawable.app_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        if (digitalHumanVisible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text(
                        text = digitalHumanStatusLabel(state),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                DigitalHumanControlBar(
                    onShowChat = { showDigitalHuman = false },
                    onOpenSettings = onOpenSettings,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (state.scheduledTasks.isNotEmpty()) {
                CurrentTasksPanel(tasks = state.scheduledTasks)
            }
        } else {
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

            ChatMessageList(
                messages = state.chatMessages,
                assistantAvatarPath = state.activeRoleAvatarPath,
                assistantAvatarText = state.activeRoleName.takeLast(1).ifBlank { "智" },
                listState = listState,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.activeRoleDigitalHumanReady || state.activeRolePortraitPath.isNotBlank()) {
            if (!showDigitalHuman) {
                DigitalHumanSwitchChip(
                    label = if (state.activeRoleDigitalHumanReady) "切到数字人画面" else "查看角色立绘",
                    onClick = { showDigitalHuman = true },
                )
            }
        }

        ComposerCard(
            state = state,
            onAbortSpeaking = onAbortSpeaking,
        )
        }
    }
}

/** 数字人画面左上角的状态胶囊文案。 */
private fun digitalHumanStatusLabel(state: UiState): String = when {
    state.isAssistantSpeaking -> "讲话中"
    state.isRecording || state.isTurnActive -> "聆听中"
    state.connectionStatus == ConnectionStatus.CONNECTED -> "待机中"
    state.connectionStatus == ConnectionStatus.FETCHING_CONFIG -> "获取配置中"
    state.connectionStatus == ConnectionStatus.CONNECTING -> "连接中"
    state.connectionStatus == ConnectionStatus.FAILED -> "连接失败"
    else -> "未连接"
}

/**
 * 数字人画面右上角的辅助操作条：切回聊天 / 设置 / 清空素材。
 *
 * 「打断」**不在**这里——它已经挪到底部那颗大号红色按钮上（见 [ComposerCard]）。
 * 原先它挤在这个小药丸里，测试者反馈「太偏了，在角落上不容易按到」。
 */
@Composable
private fun DigitalHumanControlBar(
    onShowChat: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.42f),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onShowChat) {
                Text("聊天", color = Color.White)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
            }
        }
    }
}

/** 聊天视图下回到数字人画面 / 立绘的入口。 */
@Composable
private fun DigitalHumanSwitchChip(label: String, onClick: () -> Unit) {
    Surface(
        color = HeaderTint,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 角色立绘：四段视频没配齐时用的一张静态大图。
 * 测试者问过“不能一个角色一个配图吗”，这就是那个配图。
 */
@Composable
private fun PortraitPanel(path: String, modifier: Modifier = Modifier) {
    // 立绘是用户自己上传的照片，分辨率可能非常大——8000x6000 的照片按 ARGB_8888
    // 全尺寸解码约 183 MiB。直接用 BitmapFactory.decodeFile 有两重风险：
    //   1. 在组合线程上同步解码大图，界面会卡住；
    //   2. 位图超过硬件 Canvas 的绘制上限时抛 "Canvas: trying to draw too large bitmap"，
    //      而这里的 runCatching 只包得住**解码**、包不住**绘制**，所以照样会崩。
    // 立绘本来就按 Crop 铺满全屏显示，超过屏幕的部分根本看不见，按屏幕尺寸下采样即可。
    //
    // 解码必须放到 IO 线程：实测一张 18540x23437 / 14.85 MB 的 JPEG，
    // 即使下采样到 1158x1464，libjpeg 仍要通读整个文件，放在组合线程会直接冻住界面。
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        decodeFailed = false
        val decoded = withContext(Dispatchers.IO) {
            runCatching { decodeSampledBitmap(path, maxWidthPx = 1080, maxHeightPx = 1920) }.getOrNull()
        }
        bitmap = decoded
        decodeFailed = decoded == null
    }
    Box(
        modifier = modifier.background(Color(0xFF0B1016)),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        when {
            shown != null -> Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 解码期间保持纯色底，不要把"无法读取"当作加载态闪一下。
            !decodeFailed -> Unit
            else -> Text("立绘无法读取", color = Color.White)
        }
    }
}

/**
 * 按目标尺寸采样解码图片。两步走：先只读边界（`inJustDecodeBounds`，不解码像素），
 * 由 [ImageSampling] 算出 `inSampleSize`，再真正解码。
 *
 * 采样算术刻意放在 [ImageSampling] 里而不是内联在这里——它是"防止大图把整机拖垮"
 * 的核心逻辑，必须能在 JVM 单元测试里直接验证（见 `ImageSamplingTest`）。
 */
private fun decodeSampledBitmap(path: String, maxWidthPx: Int, maxHeightPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val sample = ImageSampling.portraitSampleSize(bounds.outWidth, bounds.outHeight, maxWidthPx, maxHeightPx)

    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable
private fun DigitalHumanPanel(state: UiState, modifier: Modifier = Modifier) {
    val path = when {
        state.isAssistantSpeaking -> state.activeRoleSpeakingVideoPath
        state.isRecording || state.isTurnActive -> state.activeRoleListeningVideoPath
        else -> state.activeRoleIdleVideoPath
    }
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
            FrameLayout(context).apply {
                clipChildren = true
                addView(
                    CoverVideoView(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            player.setVolume(0f, 0f)
                            start()
                            // 只把尺寸告诉控件，缩放交给 CoverVideoView.onMeasure 去算。
                            // 它每次父容器重新测量都会带着**当前**尺寸重算一遍，
                            // 所以不存在"回调时机不对就把画面永久定死在错误尺寸"的问题
                            // （旧写法在 onPrepared 里算一次绝对像素，客户机上出现过
                            //  数字人缩到屏幕三分之一且再也回不来的情况）。
                            setSourceSize(player.videoWidth, player.videoHeight)
                        }
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
            }
        },
        update = { container ->
            val video = container.getChildAt(0) as? CoverVideoView ?: return@AndroidView
            // 用「路径 + 最后修改时间」做标识：同一个槽位重传视频时路径字符串不变，
            // 只比路径会导致画面不刷新，用户会以为重传没生效。
            val stamp = if (path.isBlank()) "" else "$path@${File(path).lastModified()}"
            if (path.isBlank() || !File(path).exists()) {
                video.stopPlayback()
            } else if (video.tag != stamp) {
                video.tag = stamp
                video.setVideoPath(path)
                video.start()
            }
        },
        onRelease = { container -> (container.getChildAt(0) as? CoverVideoView)?.stopPlayback() },
    )
}

@Composable
private fun UploadQrDialog(
    uploadSession: VideoUploadSession,
    successMessage: String?,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(uploadSession.url) {
        runCatching {
            val matrix = MultiFormatWriter().encode(uploadSession.url, BarcodeFormat.QR_CODE, 720, 720)
            Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).also { image ->
                for (x in 0 until 720) for (y in 0 until 720) {
                    image.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }.getOrElse { error ->
            android.util.Log.e("UploadQrDialog", "Failed to generate QR bitmap", error)
            null
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
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "视频上传二维码",
                        modifier = Modifier.size(240.dp),
                    )
                } else {
                    Text("无法生成二维码，请检查网络", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    text = uploadSession.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
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
            // 任务列表限高 + 可滚动。
            //
            // 定时任务**条数没有上限**（只有监督任务限 5 个），而这里是逐条全展开、
            // 既不限高也不滚动的。条数一多，外层 Column 的固定高度子项加起来就会超出
            // 可用高度，**把底部的 ComposerCard 顶出屏幕**——麦克风和「打断」都点不到了，
            // 而那是这台设备最常用的两个按钮。这里把列表限制在几行以内并允许滚动，
            // 保证底部操作条永远有位置。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = TASK_LIST_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                    CurrentTaskRow(task)
                }
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
    onPickRolePortrait: (String) -> Unit,
    onPickRoleVideo: (String, DigitalHumanSlot) -> Unit,
    onAddRole: (String, String) -> Unit,
    onUpdateRole: (String, String, String) -> Unit,
    onDeleteRole: (String) -> Unit,
    onCheckForUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
    onStartVideoUpload: (String, DigitalHumanSlot) -> Unit,
    onClearRoleAvatar: (String) -> Unit,
    onClearRolePortrait: (String) -> Unit,
    onClearRoleVideo: (String, DigitalHumanSlot) -> Unit,
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
            onPickRolePortrait = onPickRolePortrait,
            onPickRoleVideo = onPickRoleVideo,
            onStartVideoUpload = onStartVideoUpload,
            onClearRoleAvatar = onClearRoleAvatar,
            onClearRolePortrait = onClearRolePortrait,
            onClearRoleVideo = onClearRoleVideo,
            onAddRole = onAddRole,
            onUpdateRole = onUpdateRole,
            onDeleteRole = onDeleteRole,
        )

        AppUpdateCard(
            state = state,
            onCheckForUpdate = onCheckForUpdate,
            onStartUpdate = onStartUpdate,
        )
    }
}

@Composable
private fun AppUpdateCard(
    state: UiState,
    onCheckForUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "系统与软件更新",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "当前版本：v${state.appVersionName} (Build ${state.appVersionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onCheckForUpdate,
                    enabled = !state.isCheckingUpdate && !state.isDownloadingUpdate,
                ) {
                    Text(if (state.isCheckingUpdate) "检查中..." else "检查更新")
                }
            }

            if (state.updateCheckStatus.isNotBlank()) {
                Text(
                    text = state.updateCheckStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.availableUpdate != null) {
                Surface(
                    color = Color(0xFFF0FDF4),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "🎉 发现新版本 v${state.availableUpdate.versionName}",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF166534),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.availableUpdate.releaseNotes.isNotBlank()) {
                            Text(
                                text = state.availableUpdate.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF15803D),
                            )
                        }

                        if (state.isDownloadingUpdate) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = { state.downloadProgressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "下载更新中 ${state.downloadProgressPercent}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF166534),
                                )
                            }
                        } else {
                            Button(
                                onClick = onStartUpdate,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("立即下载并安装更新")
                            }
                        }
                    }
                }
            }
        }
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
    onPickRolePortrait: (String) -> Unit,
    onPickRoleVideo: (String, DigitalHumanSlot) -> Unit,
    onStartVideoUpload: (String, DigitalHumanSlot) -> Unit,
    onAddRole: (String, String) -> Unit,
    onUpdateRole: (String, String, String) -> Unit,
    onDeleteRole: (String) -> Unit,
    onClearRoleAvatar: (String) -> Unit,
    onClearRolePortrait: (String) -> Unit,
    onClearRoleVideo: (String, DigitalHumanSlot) -> Unit,
) {
    var editingRoleId by remember { mutableStateOf<String?>(null) }
    var addingRole by remember { mutableStateOf(false) }
    // 待确认删除的角色。删除是**不可撤销**的，而且会连带丢掉这个角色的
    // 唤醒词、立绘、头像和四段形象视频——所以不能一点就走。
    // 2026-09-21 用户模拟测试的原话：「删除和编辑并排、同样大小、同样样式，
    // 只差文字——误触成本不对等」，实测一次点击角色就没了。
    var pendingDelete by remember { mutableStateOf<RoleProfile?>(null) }
    val editingRole = state.roleProfiles.firstOrNull { it.id == editingRoleId }

    SettingsCard {
        state.roleProfiles.forEachIndexed { index, role ->
            RoleProfileRow(
                role = role,
                active = role.id == state.activeRoleId,
                onSelect = { onSelectRole(role.id) },
                onEdit = { editingRoleId = role.id },
                onDelete = { pendingDelete = role },
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

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除角色「${target.displayName}」？") },
            text = {
                Text(
                    "这个角色的唤醒词、立绘、头像和形象视频都会一起删掉，删了没法恢复。" +
                        "如果只是暂时不想用它，可以留着不切换。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteRole(target.id)
                    },
                ) {
                    Text("删除", color = InterruptRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (editingRole != null || addingRole) {
        RoleEditorDialog(
            role = editingRole,
            onPickAvatar = { editingRole?.id?.let(onPickRoleAvatar) },
            onPickPortrait = { editingRole?.id?.let(onPickRolePortrait) },
            onClearAvatar = { editingRole?.id?.let(onClearRoleAvatar) },
            onClearPortrait = { editingRole?.id?.let(onClearRolePortrait) },
            onClearVideo = { slot -> editingRole?.id?.let { onClearRoleVideo(it, slot) } },
            onPickVideo = { slot -> editingRole?.id?.let { onPickRoleVideo(it, slot) } },
            onStartVideoUpload = { slot ->
                val targetRole = editingRole
                android.util.Log.d("LanVideoUpload", "RoleEditorDialog click 扫码: slot=$slot, editingRole=$targetRole")
                targetRole?.id?.let { id ->
                    onStartVideoUpload(id, slot)
                } ?: run {
                    android.util.Log.e("LanVideoUpload", "editingRole is null when clicking 扫码")
                }
            },
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
    onPickPortrait: () -> Unit,
    onPickVideo: (DigitalHumanSlot) -> Unit,
    onStartVideoUpload: (DigitalHumanSlot) -> Unit,
    onClearAvatar: () -> Unit,
    onClearPortrait: () -> Unit,
    onClearVideo: (DigitalHumanSlot) -> Unit,
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
                        if (role.avatarPath.isNotBlank() && File(role.avatarPath).exists()) {
                            TextButton(onClick = onClearAvatar) {
                                Text("清除", color = InterruptRed)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (role.portraitPath.isNotBlank() && File(role.portraitPath).exists()) {
                                "角色立绘 · 已配置"
                            } else {
                                "角色立绘 · 未配置"
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onPickPortrait) { Text("选择立绘") }
                            if (role.portraitPath.isNotBlank() && File(role.portraitPath).exists()) {
                                TextButton(onClick = onClearPortrait) {
                                    Text("删除", color = InterruptRed)
                                }
                            }
                        }
                    }
                    Text(
                        text = "四段形象视频没配齐时，用这张立绘当角色形象",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                                // 逐段删除。原来只有一个"清空全部"的垃圾桶入口，
                                // 一次把四段视频 + 头像 + 立绘全删掉，用户说"成本太大"。
                                if (configured) {
                                    TextButton(onClick = { onClearVideo(slot) }) {
                                        Text("删除", color = InterruptRed)
                                    }
                                }
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
    onAbortSpeaking: () -> Unit,
) {
    val isSpeaking = state.isAssistantSpeaking
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
            // 整条底栏只有一行：麦克风 · 字幕 · 打断圆键。
            //
            // 原先这里还有一条单独占一行的状态提示，而中间那颗「打断」横贯整行。
            // 用户反馈那颗按钮"太大、太占空间，其实没有意义"——它占着整行宽度却不承载
            // 任何信息。现在把它缩成最右边的圆键，让出来的宽度给字幕，
            // 状态提示也一并合进字幕区，于是**整体少了一行**。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 字幕区：显示「此刻在说什么」。
                //   * 播报中 → 小智正在说的那一句；
                //   * 聆听中 → 刚识别到的用户原话（还没识别出来就提示"正在聆听"）；
                //   * 待机   → 小智最后说过的一句；一句都没有时提示怎么叫它。
                // 客户之前问过"为什么有动画的角色反而不显示文字"——这条就是那个文字。
                val subtitle = when {
                    // **麦克风掉线要盖过一切。**
                    // 此时设备既听不见也看不见，其他任何文案（「正在聆听」「正在播放」）
                    // 说的都不是真的——用户会一直以为它在听，其实它已经聋了。
                    // 实测过：USB 摄像头掉线（dmesg: error -71）时界面原本只有
                    // 「正在监听唤醒词」，用户只能靠猜。
                    !state.microphoneReady ->
                        "⚠ ${state.microphoneMessage.ifBlank { "没有检测到摄像头麦克风，请检查 USB 连接" }}"
                    // **播报要排在聆听前面。**
                    // 播报期间麦克风是继续工作的（为了支持语音打断），所以 isRecording 也为 true。
                    // 顺序反了就会出现同一屏上顶栏写「讲话中」、底部写「正在聆听」的自相矛盾
                    // （2026-09-21 用户模拟测试把它当成一处矛盾提示报了出来）。
                    state.isAssistantSpeaking -> state.lastTtsText.ifBlank { "正在播报…" }
                    state.isRecording -> state.lastSttText.ifBlank { "正在聆听…" }
                    state.lastTtsText.isNotBlank() -> state.lastTtsText
                    // 待机且还没说过话：**必须告诉用户怎么开始**。
                    // 底栏原来的麦克风键已经去掉了（这台设备是语音优先的，客户也明确要求
                    // 屏幕上不要有需要手动点的入口），于是唤醒词成了唯一的启动方式——
                    // 那就把它写出来，否则新用户面对一块安静的黑屏无从下手。
                    else -> state.roleProfiles
                        .firstOrNull { it.id == state.activeRoleId }
                        ?.wakeWords
                        ?.firstOrNull()
                        ?.let { "说「$it」叫我" }
                        .orEmpty()
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            !state.microphoneReady -> Color(0xFFB91C1C)
                            state.isAssistantSpeaking -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (!state.microphoneReady) FontWeight.Bold else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 打断：一颗红圆键，只在播报中才亮起来。
                //
                // 它的位置与尺寸是两次反馈的折中：最初是右上角小药丸里的 TextButton，
                // 测试者说「太偏了不容易按到」，于是做成横贯整行的大按钮；
                // 之后又反馈「这么大其实没有意义、太占空间」。
                // 现在缩成 52dp 的圆键 —— 位置仍在最右、目标仍远大于 48dp 的最小可点区域，
                // 拇指一样够得到，而让出来的宽度给了字幕。
                //
                // 图标用 ×（Close）而不是停止方块：播报期间旁边已经没有任何方块图标了，
                // 但 × 在红色圆底上表达「别说了」更直白，也不会被误认成"停止录音"。
                FilledIconButton(
                    onClick = onAbortSpeaking,
                    enabled = isSpeaking,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = InterruptRed,
                        contentColor = Color.White,
                        // 待机态保留一颗淡红底的键：用户平时就知道它在这儿，
                        // 播报时它一亮起来，肌肉记忆立刻接上。
                        disabledContainerColor = InterruptRed.copy(alpha = 0.14f),
                        disabledContentColor = InterruptRed,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "打断")
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
                        contentColor = Color.White,
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
