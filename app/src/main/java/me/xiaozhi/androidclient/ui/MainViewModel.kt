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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.xiaozhi.androidclient.audio.XiaozhiAudioEngine
import me.xiaozhi.androidclient.camera.CameraVisionTool
import me.xiaozhi.androidclient.data.AppPreferences
import me.xiaozhi.androidclient.data.RoleProfileRepository
import me.xiaozhi.androidclient.data.StoredConfig
import me.xiaozhi.androidclient.util.ImageSampling
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
import me.xiaozhi.androidclient.audio.uploadDuringPlayback
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

/** 超过这个字数就不再直发 listen/detect，改走短暗号 + self.message.current 取回。 */
private const val USER_TEXT_DIRECT_LIMIT = 12
private const val USER_TEXT_CODE_PHRASE = "【文字消息】"

/** 形如 “% self.timer.set…”“self.camera.take_photo…” 的模型工具调用回显。 */
private val TOOL_CALL_ARTIFACT = Regex("^self\\.[a-zA-Z]+\\.[a-zA-Z]+\\s*[({]?")
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

/**
 * 监督提醒播报完之后，隔多久调摄像头核验。
 * 要给用户留出实际去做那件事的时间（比如"做十个深蹲"）。
 */
private const val SUPERVISION_VERIFY_DELAY_SECONDS = 60

/**
 * 一个监督任务最多核验几次。
 *
 * **必须设上限**：`nextSupervisionRetrySeconds()` 只把重试间隔封顶在 30 分钟，
 * 不限制次数。而核验"未完成/无法确认"会一直重排，于是任务失败时会**每 30 分钟
 * 调一次摄像头、永远不停**，既扰民又耗电。超过这个次数就停止跟踪并明确告诉用户。
 */
private const val MAX_SUPERVISION_CHECKS = 5

/** 输入设备健康巡检间隔。摄像头 USB 掉线要能被较快发现，但也不必每秒查。 */
private const val MIC_CHECK_INTERVAL_MS = 5_000L

/**
 * 立绘落盘时的长边上限。
 *
 * 屏幕只有 800×1280，2048 已经比屏幕长边宽出 60%，按 Crop 铺满绰绰有余；
 * 再大只是白占磁盘和解码时间。2026-09-21 实测：不设上限时一张手机照片
 * 会在磁盘上落下 14.85 MB。
 */
private const val PORTRAIT_MAX_LONG_SIDE = 2048

/** 头像落盘时的长边上限。头像在列表里只显示成一个小圆圈，512 足够。 */
private const val AVATAR_MAX_LONG_SIDE = 512

/** 压缩落盘时 JPEG 的质量。90 在照片上看不出损失，体积约为无损的十分之一。 */
private const val IMAGE_JPEG_QUALITY = 90

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

    /**
     * 串行化监督核验：同一时刻只允许一次摄像头核验在跑。
     *
     * 用 Mutex 而不是"忙就丢弃"的守卫，是因为丢弃会让任务永久卡死（见
     * [startSupervisionVerification] 的注释）。排队等待的一方在拿到锁之后
     * 还会再确认一次任务身份，所以等待期间任务被取消也不会拍到错的照片。
     */
    private val supervisionVerificationMutex = kotlinx.coroutines.sync.Mutex()

    /** 输入设备健康巡检。每 [MIC_CHECK_INTERVAL_MS] 查一次，结果直接显示在底栏。 */
    private var micHealthJob: Job? = null

    /** 掉线警告只打一次，避免每几秒刷一条同样的日志。 */
    private var microphoneWarningLogged: Boolean = false
    private var userRequestedDisconnect: Boolean = false
    private var conversationLoopActive: Boolean = false
    private var scheduledResumeCancelledByUser: Boolean = false
    private var ignoreLifecycleGoodbyeAfterScheduledDelivery: Boolean = false
    private var appUpdateCheckJob: Job? = null

    /**
     * 上一次读取附加角色是否**失败**（区别于"确实没有附加角色"）。
     *
     * 置位期间 [saveAdditionalProfiles] 会拒绝一切全量写入——因为此时内存里的角色列表
     * 并不代表磁盘上的真实内容，写回去就等于用残缺快照覆盖用户配置。
     * 一次成功的读取会自动把它清掉。
     */
    private var additionalProfilesLoadFailed: Boolean = false

    private val pendingTextPrompts = ArrayDeque<String>()

    /**
     * 服务端对 listen/detect 通道有长度限制（实测 16 字通过、22 字被拒，
     * 返回 `Detect is only for wake words, do not send long texts.`）。
     * 用户在输入框里打的长句因此会静默失败——所以超过阈值的文字不发原文，
     * 改发短暗号，由模型调用 self.message.current 取回原话。
     */
    private val pendingUserMessages = ArrayDeque<String>()

    /** 诊断用：本轮播报是否已记录过"上行通道已打开"，避免刷屏。 */
    private var playbackUplinkLogged = false
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
        pendingUserMessage = ::takePendingUserMessage,
        log = ::addLog,
    )

    init {
        audioEngine.setRouteStatusListener(::updateAudioRouteStatus)
        startMicrophoneWatch()
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
        val app = getApplication<Application>()
        // 与立绘同理：复制 + 校验走 IO 线程，配置写入回主线程。
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val avatarDir = File(app.filesDir, "avatar").apply { mkdirs() }
                    val targetFile = File(avatarDir, "role-${role.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
                    replaceImageAtomically(targetFile, AVATAR_MAX_LONG_SIDE) { temp ->
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取所选图片")
                    }
                }
            }
            outcome.onSuccess { path ->
                applyAvatarPath(role, path)
                reloadRoleProfiles()
                addLog("已更新${role.displayName}头像")
            }.onFailure { error ->
                addLog("更新头像失败：${error.message.orEmpty()}")
            }
        }
    }

    /**
     * 角色立绘：一张静态大图。四段视频没配齐时，用它当角色形象。
     * 测试者问过“不能一个角色一个配图吗”，这就是那个配图。
     */
    fun importRolePortrait(roleId: String, uri: Uri) {
        val role = roleProfiles.firstOrNull { it.id == roleId } ?: return
        val app = getApplication<Application>()
        // 复制 + 校验都放 IO 线程。用户选的可能是十几 MB 的原图，
        // 在主线程做这些 I/O 会和界面抢时间（实测导入 14.85 MB 的图时，
        // 触摸响应、WebSocket 重连、音频采集全部停摆）。
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val target = portraitFileFor(role.id).apply { parentFile?.mkdirs() }
                    replaceImageAtomically(target, PORTRAIT_MAX_LONG_SIDE) { temp ->
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取所选图片")
                    }
                }
            }
            outcome.onSuccess { path ->
                applyPortraitPath(role, path)
                reloadRoleProfiles()
                addLog("已更新${role.displayName}立绘")
            }.onFailure { error ->
                addLog("更新立绘失败：${error.message.orEmpty()}")
            }
        }
    }

    /**
     * 从本地文件导入立绘。扫码上传与开发期调试接口共用这条路径。
     *
     * 返回 `Result` 而不是消息字符串：扫码上传那条路要据此决定给用户**绿色还是红色**反馈，
     * 靠"消息里有没有『失败』两个字"来判断太脆。
     */
    fun importRolePortraitFromFile(roleId: String, source: File): Result<String> {
        val role = roleProfiles.firstOrNull { it.id == roleId }
            ?: return Result.failure(IllegalArgumentException("角色不存在：$roleId"))
        return runCatching {
            val target = portraitFileFor(role.id).apply { parentFile?.mkdirs() }
            replaceImageAtomically(target, PORTRAIT_MAX_LONG_SIDE) { temp ->
                source.inputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            }
        }.map { path ->
            applyPortraitPath(role, path)
            reloadRoleProfiles()
            "已更新${role.displayName}立绘"
        }
    }

    /**
     * 从本地文件导入头像。与立绘同理，供扫码上传使用。
     *
     * 头像和立绘走的是两套落盘目录与两个压缩上限（512 / 2048），别合并。
     */
    fun importRoleAvatarFromFile(roleId: String, source: File): Result<String> {
        val role = roleProfiles.firstOrNull { it.id == roleId }
            ?: return Result.failure(IllegalArgumentException("角色不存在：$roleId"))
        val app = getApplication<Application>()
        return runCatching {
            val avatarDir = File(app.filesDir, "avatar").apply { mkdirs() }
            val targetFile = File(avatarDir, "role-${role.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
            replaceImageAtomically(targetFile, AVATAR_MAX_LONG_SIDE) { temp ->
                source.inputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            }
        }.map { path ->
            applyAvatarPath(role, path)
            reloadRoleProfiles()
            "已更新${role.displayName}头像"
        }
    }

    /**
     * 把图片写进临时文件、**校验通过后**再原子替换正式文件，返回正式文件路径。
     *
     * 为什么不能直接往目标文件写：`target.outputStream()` 会先把正式文件**截断**，
     * 之后只要复制中途出问题（选到损坏图片、磁盘满、读取异常）或者根本不是图片，
     * 返回的虽然是"失败"——但用户**原来那张能用的图已经被毁了**，
     * 而且配置里还指向那个坏文件。换素材反而把素材弄丢，是最不能接受的一类 bug。
     *
     * 顺带把头像也纳入"必须是能解码的图片"这条校验：之前头像入口完全没有校验，
     * 任何文件都能被写进去并写进配置。
     */
    private fun replaceImageAtomically(target: File, maxLongSide: Int, write: (File) -> Unit): String {
        val temp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        try {
            write(temp)
            require(isDecodableImage(temp)) { "所选文件不是可识别的图片" }
            // 压缩落盘。**失败就算了**：宁可原样存着，也绝不能因为"顺手优化"把用户的图弄丢 ——
            // 所以只 runCatching，任何异常都不影响后面把它原子换上。
            runCatching { shrinkImageIfNeeded(temp, maxLongSide) }
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                target.delete()
                if (!temp.renameTo(target)) error("替换图片文件失败")
            }
            return target.absolutePath
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /**
     * 判断文件是不是一张真的能解码的图片。
     *
     * **绝对不能用 `BitmapFactory.decodeFile(path)` 来判**——那是按**原始分辨率**解码，
     * 位图字节数 = 宽 × 高 × 4。2026-09-19 真机实测：导入一张 18540×23437 的 JPEG，
     * 需要一次性分配 **1.74 GB**，而这块 RK3568 板子 `MemTotal` 只有 **1.92 GB**。
     * 后果是整机被打进 zram 换页（`SwapFree` 掉 304 MB、10170 次 major 缺页、
     * `kswapd0` 占 26% CPU），主线程卡死 **5.1 秒**触发 ANR——
     * 一次"导入立绘"就能让整台设备瘫痪几秒。
     *
     * 改成两步之后，内存占用与图片原始大小**完全脱钩**：
     *   1. `inJustDecodeBounds` 只读文件头，拿不到宽高就说明根本不是图片；
     *   2. 再用一个足够大的 `inSampleSize` 真解一次，确认像素数据没坏
     *      （只看文件头的话，被截断的图会漏过去），此时位图已被压到几百像素见方。
     */
    private fun isDecodableImage(file: File): Boolean {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        // 采样率由 util/ImageSampling 统一给出（纯函数、有单元测试），
        // 不再在这里内联一份 —— 两处各写一份算术，迟早会走偏。
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.probeSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = runCatching {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull() ?: return false
        // recycle 放进 finally：紧跟 `?: return` 之后写的话，协程若在这个窗口被取消
        // （用户在导入大图途中返回/切角色 → onCleared 取消 viewModelScope），
        // 控制流会在下一个挂起点抛 CancellationException，recycle 被跳过，
        // 位图只能等 GC —— 而在 1.92 GB 的板子上"等 GC"正是这次要避免的事。
        return try {
            true
        } finally {
            decoded.recycle()
        }
    }

    /**
     * 图片过大时**原地**等比缩到长边不超过 [maxLongSide]。
     *
     * 为什么值得做：立绘是**原图直存**的。2026-09-21 真机实测，用户从手机导入一张
     * 4000×6000 的照片，磁盘上就落下 **14.85 MB**——而屏幕只有 800×1280，
     * 多出来的像素只带来解码开销和存储占用。这块板子存储和内存都紧。
     *
     * 三条安全约束：
     *  1. **本来就不大就一个字节都不动**（不重编码，避免无谓的画质损失）；
     *  2. 用 [ImageSampling.fitSampleSize] 先粗采样再精确缩放，
     *     避免"为了存缩略图反而按原尺寸解码"；
     *  3. 有 alpha 通道时存 PNG，否则存 JPEG —— 头像可能是带透明的图，
     *     一律转 JPEG 会把透明区域变黑。
     *
     * 调用方用 `runCatching` 包着：**这里失败就保留原图**，绝不因此弄丢用户的素材。
     */
    private fun shrinkImageIfNeeded(file: File, maxLongSide: Int) {
        if (maxLongSide <= 0) return
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return
        if (maxOf(width, height) <= maxLongSide) return

        val sample = ImageSampling.fitSampleSize(width, height, maxLongSide)
        val decoded = android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return

        val longSide = maxOf(decoded.width, decoded.height)
        val scaled = if (longSide > maxLongSide) {
            val ratio = maxLongSide.toFloat() / longSide
            android.graphics.Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        // 压到**另一个**临时文件，校验通过后再换过去。
        //
        // 绝不能写成 `file.outputStream()`：那会先把已经校验过的文件**截断**，
        // 一旦 compress 中途失败（磁盘满、编码异常）或返回值是 false，
        // 留下的就是残文件；而上层是 `runCatching { shrinkImageIfNeeded(...) }` 吞异常，
        // 随后照样把它 move 到正式路径 —— **用户原来那张能用的图被毁掉，HTTP 还回成功**。
        // 这个坑是代码评审指出来的，实测路径确认成立。
        val shrunk = File(file.parentFile, "${file.name}.shrunk-${System.nanoTime()}")
        try {
            val format = if (scaled.hasAlpha()) {
                android.graphics.Bitmap.CompressFormat.PNG
            } else {
                android.graphics.Bitmap.CompressFormat.JPEG
            }
            shrunk.outputStream().use { out ->
                require(scaled.compress(format, IMAGE_JPEG_QUALITY, out)) { "图片压缩失败" }
            }
            // 压缩产物本身也得是能解码的图片，否则宁可用未经压缩的原图。
            require(isDecodableImage(shrunk)) { "压缩产物不可解码" }
            java.nio.file.Files.move(
                shrunk.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            // 无论成败都清掉侧文件：成功时它已经被 move 走（delete 无副作用），
            // 失败时留着就是垃圾。
            shrunk.delete()
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    fun clearRolePortrait(roleId: String) {
        val role = roleProfiles.firstOrNull { it.id == roleId } ?: return
        deleteFileIfOwned(role.portraitPath)
        applyPortraitPath(role, "")
        reloadRoleProfiles()
        addLog("已清除${role.displayName}立绘")
    }

    private fun applyPortraitPath(role: RoleProfile, path: String) {
        if (role.id == RoleProfileRepository.DEFAULT_ROLE_ID) {
            updateAndPersist {
                copy(
                    assistantPortraitPath = path,
                    activeRolePortraitPath = if (activeRoleId == role.id) path else activeRolePortraitPath,
                )
            }
        } else {
            saveAdditionalProfiles(
                roleProfiles.filterNot(::isPrimaryRole).map { profile ->
                    if (profile.id == role.id) profile.copy(portraitPath = path) else profile
                },
            )
        }
    }

    fun importRoleVideo(roleId: String, slot: DigitalHumanSlot, uri: Uri) {
        val role = roleProfiles.firstOrNull { it.id == roleId } ?: return
        digitalHumanAssets.importVideo(role, slot, uri).onSuccess { path ->
            updateRoleVideoPath(roleId, slot, path)
            addLog("已更新${role.displayName}${slot.label}")
        }.onFailure { error -> addLog("导入${slot.label}失败：${error.message.orEmpty()}") }
    }

    /**
     * 供开发期调试接口使用：把指定目录下的 idle/greeting/listening/speaking 四段 mp4
     * 走与扫码上传完全相同的导入路径（校验 + 拷贝 + 落库），用于自动化回归数字人。
     */
    fun importDemoMediaFromDirectory(roleId: String, directory: File): String {
        val role = roleProfiles.firstOrNull { it.id == roleId } ?: return "角色不存在：$roleId"
        var imported = 0
        val failures = mutableListOf<String>()
        DigitalHumanSlot.entries.forEach { slot ->
            val source = File(directory, "${slot.wireName}.mp4")
            if (!source.exists()) {
                failures.add("${slot.wireName}(文件缺失)")
                return@forEach
            }
            digitalHumanAssets.importVideoFile(role, slot, source)
                .onSuccess { path ->
                    updateRoleVideoPath(roleId, slot, path)
                    imported++
                }
                .onFailure { error -> failures.add("${slot.wireName}(${error.message})") }
        }
        val summary = buildString {
            append("导入 $imported/${DigitalHumanSlot.entries.size} 段")
            if (failures.isNotEmpty()) append("，失败：").append(failures.joinToString("、"))
        }
        addLog("调试导入数字人素材：$summary")
        return summary
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

    /**
     * 清掉某一段形象视频（只这一段）。
     *
     * 数字人素材是用户一段段配出来的，删除必须能**逐段**做——
     * 原来的入口是数字人画面右上角一个垃圾桶图标，一下把四段视频 + 头像 + 立绘全删了，
     * 而且没有任何确认。现在那个入口已经拿掉，改在角色编辑面板里逐段删。
     */
    fun clearRoleVideo(roleId: String, slot: DigitalHumanSlot) {
        val role = roleProfiles.firstOrNull { it.id == roleId } ?: return
        val removed = digitalHumanAssets.deleteSlot(role, slot)
        updateRoleVideoPath(roleId, slot, "")
        addLog(
            if (removed) "已删除${role.displayName}的${slot.label}"
            else "已清空${role.displayName}的${slot.label}（文件本来就不在）",
        )
    }

    /** 清掉某个角色的头像。 */
    fun clearRoleAvatar(roleId: String) {
        val role = roleProfiles.firstOrNull { it.id == roleId } ?: return
        deleteFileIfOwned(role.avatarPath)
        applyAvatarPath(role, "")
        reloadRoleProfiles()
        addLog("已清除${role.displayName}头像")
    }

    /** 头像路径落库（主角色走 prefs，附加角色走 roles.json）——与导入共用同一条路。 */
    private fun applyAvatarPath(role: RoleProfile, path: String) {
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
    }

    /**
     * 清空某个角色的数字人四段视频与头像。
     * 测试者上传素材后在界面上找不到任何撤销入口，最后只能把整个角色删掉才恢复。
     */
    fun clearRoleMedia(roleId: String) {
        val target = roleProfiles.firstOrNull { it.id == roleId }
        digitalHumanAssets.deleteRoleAssets(roleId)
        target?.let {
            deleteFileIfOwned(it.avatarPath)
            deleteFileIfOwned(it.portraitPath)
        }
        if (roleId == RoleProfileRepository.DEFAULT_ROLE_ID) {
            updateAndPersist {
                copy(
                    idleVideoPath = "",
                    greetingVideoPath = "",
                    listeningVideoPath = "",
                    speakingVideoPath = "",
                    assistantAvatarPath = "",
                    activeRoleAvatarPath = "",
                    assistantPortraitPath = "",
                    activeRolePortraitPath = "",
                )
            }
        } else {
            saveAdditionalProfiles(roleProfiles.filterNot(::isPrimaryRole).map { profile ->
                if (profile.id != roleId) {
                    profile
                } else {
                    profile.copy(
                        avatarPath = "",
                        portraitPath = "",
                        idleVideoPath = "",
                        greetingVideoPath = "",
                        listeningVideoPath = "",
                        speakingVideoPath = "",
                    )
                }
            })
        }
        reloadRoleProfiles()
        addLog("已清空${target?.displayName ?: "该角色"}的数字人素材与头像")
    }

    fun checkForAppUpdate(silent: Boolean = false) {
        if (_uiState.value.isDownloadingUpdate) return
        if (appUpdateCheckJob?.isActive == true) {
            // 关键：**手动点击优先**。
            //
            // 启动时会自动跑一次静默检查（silent = true），它不设置 isCheckingUpdate，
            // 所以界面上看不出它在跑；而静默检查用的第一个候选源在部分网络下会挂住很久。
            // 以前这里是无条件 return，于是用户在静默检查期间点「检查更新」——
            // 按钮不变、状态栏空白，看起来就是"点了完全没反应"（用户 2026-09-16 反馈的
            // "卡在那里"）。现在手动点击会把在跑的静默检查取消掉，立刻用自己的节奏重跑。
            if (silent) return
            addLog("手动检查更新：取消正在进行的后台检查，立即重新检查")
            appUpdateCheckJob?.cancel()
        }
        appUpdateCheckJob = viewModelScope.launch {
            // 记住"我是哪个 Job"。下面的 finally 必须靠它判断自己是不是**当前**那一个。
            val self = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            if (!silent) {
                _uiState.update { it.copy(isCheckingUpdate = true, updateCheckStatus = "正在检查更新...") }
            }
            try {
                val currentCode = _uiState.value.appVersionCode
                when (val result = appUpdateManager.checkForUpdate(currentCode)) {
                    is me.xiaozhi.androidclient.ota.UpdateCheckResult.UpToDate -> {
                        if (!silent) {
                            _uiState.update { it.copy(updateCheckStatus = "已是最新版本 (v${it.appVersionName})", availableUpdate = null) }
                            addLog("检查更新：当前已是最新版本")
                        }
                    }
                    is me.xiaozhi.androidclient.ota.UpdateCheckResult.HasUpdate -> {
                        _uiState.update { it.copy(updateCheckStatus = "发现新版本 v${result.info.versionName}", availableUpdate = result.info) }
                        addLog("发现新版本：v${result.info.versionName} (${result.info.releaseNotes.replace('\n', ' ')})")
                    }
                    is me.xiaozhi.androidclient.ota.UpdateCheckResult.Error -> {
                        if (!silent) {
                            _uiState.update { it.copy(updateCheckStatus = "检查更新失败: ${result.message}") }
                            addLog("检查更新失败：${result.message}")
                        }
                    }
                }
            } finally {
                // **只有自己还是"当前那次检查"时才清状态。**
                //
                // 手动点击会 cancel 掉在跑的静默检查再起一个新的。而 cancel() 是异步的：
                // 旧任务稍后才会走到这里，此时 appUpdateCheckJob 已经指向**新**任务了。
                // 如果这里无条件清空，就会把新任务的忙状态和 Job 引用一起抹掉——
                // 界面提前结束"检查中"，之后也识别不到正在跑的检查。这是 v1.2.5 引入的回归。
                if (appUpdateCheckJob === self) {
                    _uiState.update { it.copy(isCheckingUpdate = false) }
                    appUpdateCheckJob = null
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

            appUpdateManager.downloadApk(updateInfo) { apkFile ->
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
        // **不再自动切到新角色。**
        //
        // 切角色会把本地聊天记录即时清空（见项目的角色与唤醒规则），
        // 所以"自动切换"等于「用户只是新建了一个角色，没点任何东西，聊天记录就没了」——
        // 这是一个有数据后果的动作，不该无提示地发生。
        // 2026-09-21 真机实测（用户模拟测试）：新建角色后当前角色被静默换掉、
        // 聊天记录被清空，测试者原话是"用户没被告知"。
        //
        // 现在改为：新角色安静地出现在列表里，用户点它才切过去（那是一次显式操作）。
        addLog("已新增角色：$name（点它即可切换过去）")
    }

    /**
     * 开发期调试用：确保存在第二个角色，并把 demo 素材导入当前活动角色。
     * 用于回归测试者反馈的「两个角色各配了动图却无法自由切换」。
     */
    fun debugPrepareSecondRole(directory: File): String {
        if (roleProfiles.none { !isPrimaryRole(it) }) {
            addRole("小B", "你好小B")
        }
        val targetId = activeRoleId.ifBlank { RoleProfileRepository.DEFAULT_ROLE_ID }
        val imported = importDemoMediaFromDirectory(targetId, directory)
        return "角色=${activeRoleProfile().displayName}($targetId)，$imported"
    }

    /** 开发期调试用：在已有角色之间轮换。 */
    fun debugCycleRole(): String {
        val ids = roleProfiles.map { it.id }
        if (ids.size < 2) return "当前只有 ${ids.size} 个角色，无法切换"
        val nextIndex = (ids.indexOf(activeRoleId) + 1).mod(ids.size)
        val next = ids[nextIndex]
        val name = roleProfiles.firstOrNull { it.id == next }?.displayName ?: next
        selectRole(next)
        return "已切到 $name($next)"
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
            val avatarPath = uiState.value.assistantAvatarPath
            val portraitPath = uiState.value.assistantPortraitPath
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
            digitalHumanAssets.deleteRoleAssets(roleId)
            deleteFileIfOwned(avatarPath)
            deleteFileIfOwned(portraitPath)
            deleteFileIfOwned(portraitFileFor(roleId).absolutePath)
            // **必须从磁盘重新读一遍，不能只把内存清空。**
            // roles.json 里还有附加角色，内存清空之后只要用户新增一个角色，
            // 就会拿「空列表 + 新角色」去全量覆盖 roles.json，
            // 把磁盘上其余角色的绑定信息和素材路径一起抹掉。
            reloadRoleProfiles()
            addLog("已删除角色：小智")
            userRequestedDisconnect = false
            // 还有附加角色就切过去，别把界面停在"一个角色都没有"的状态。
            roleProfiles.firstOrNull()?.let { selectRole(it.id) }
            return
        }
        val removedRole = roleProfiles.firstOrNull { it.id == roleId }
        val remaining = roleProfiles.filterNot { it.id == roleId }
        // 统一排除主角色：其他调用点都传 filterNot(::isPrimaryRole)，
        // 只有这里传过全量列表，会把主角色也写进 roles.json（加载时被过滤掉，
        // 所以没暴露出来，但属于脏数据）。
        saveAdditionalProfiles(remaining.filterNot(::isPrimaryRole))
        // 内存列表**当场同步**。否则删除之后、reloadRoleProfiles() 之前，
        // 只要这个角色的绑定回调（updateRoleBindingStatus）回来，它就会遍历
        // 这份还带着已删角色的旧列表并保存——把刚删掉的角色又写回磁盘，
        // 而它的素材其实已经删了，变成一个指向空文件的幽灵角色。
        roleProfiles = remaining
        removedRole?.let { deleteRoleFiles(it) }
        if (activeRoleId == roleId) {
            val next = remaining.firstOrNull { it.id != roleId }
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
            addLog("调用 termux-api 失败：${error.message.orEmpty()}")
        }
    }

    /**
     * 每隔几秒查一次"摄像头麦克风还在不在"，结果写进 UiState，由底栏常驻显示。
     *
     * 为什么必须常驻、而不是只在开机时查一次：USB 摄像头**会掉线**（实测 dmesg：
     * `usb 7-1: can't read configurations, error -71`，重插 + 重启整机之后才回来）。
     * 掉了之后设备既听不见也看不见，而界面上原本**只有一句「正在监听唤醒词」**——
     * 用户完全不知道程序停在哪，只能靠猜。现在掉线会直接写在屏幕上，让人去查 USB。
     */
    private fun startMicrophoneWatch() {
        micHealthJob?.cancel()
        micHealthJob = viewModelScope.launch {
            while (true) {
                val health = runCatching { audioEngine.inputHealth() }.getOrNull()
                if (health != null) {
                    val ready = health.externalMicPresent
                    updateState {
                        copy(
                            microphoneReady = ready,
                            microphoneMessage = if (ready) "" else health.message,
                        )
                    }
                    if (!ready && !microphoneWarningLogged) {
                        microphoneWarningLogged = true
                        addLog("⚠ ${health.message}")
                    } else if (ready) {
                        microphoneWarningLogged = false
                    }
                }
                delay(MIC_CHECK_INTERVAL_MS)
            }
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
            portraitPath = state.assistantPortraitPath,
            isBound = state.activated || state.websocketUrl.isNotBlank(),
            bindingCode = state.activationCode,
        )
        val result = roleProfileRepository.loadAdditionalProfiles()
        val hasPrimary = primaryProfile.displayName.isNotBlank() && primaryProfile.wakeWords.isNotEmpty() && primaryProfile.deviceId.isNotBlank()

        // 读取**失败**和读取到**空**是两回事，绝不能混为一谈。
        //
        // `loadAdditionalProfiles()` 在 roles.json 损坏或读不出来时会返回空列表 + warning。
        // 旧代码把这个失败直接当成"没有附加角色"，用空列表替换了内存里的角色列表；
        // 之后用户只要新增一个角色，就会拿"空列表 + 新角色"去**全量覆盖** roles.json，
        // 把磁盘上原本好好的角色配置全部抹掉——和之前那次「用户素材被清空」是同一类事故。
        //
        // 现在的处理：读取失败时**保留上一次已知有效的内存快照**，并置位
        // `additionalProfilesLoadFailed` 冻结写入，直到某次读取成功才解冻。
        additionalProfilesLoadFailed = result.warning != null
        if (additionalProfilesLoadFailed) {
            addLog("附加角色读取失败，已保留上次快照并冻结角色配置写入：${result.warning}")
        }
        val additionalProfiles = if (additionalProfilesLoadFailed) {
            roleProfiles.filterNot(::isPrimaryRole)
        } else {
            result.profiles.filter { it.id != primaryProfile.id }
        }

        roleProfiles = (if (hasPrimary) listOf(primaryProfile) else emptyList()) + additionalProfiles
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
                activeRolePortraitPath = activeRoleProfile().portraitPath,
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
                portraitPath = uiState.value.assistantPortraitPath,
                idleVideoPath = uiState.value.idleVideoPath,
                greetingVideoPath = uiState.value.greetingVideoPath,
                listeningVideoPath = uiState.value.listeningVideoPath,
                speakingVideoPath = uiState.value.speakingVideoPath,
            )

    private fun isPrimaryRole(profile: RoleProfile): Boolean =
        profile.id == RoleProfileRepository.DEFAULT_ROLE_ID

    private fun saveAdditionalProfiles(profiles: List<RoleProfile>) {
        // 冻结闸门：上一次读取失败时，内存里的列表并不代表磁盘上的真实内容，
        // 这时做全量写入等于**用残缺的快照覆盖用户的配置**。
        // 宁可这一次改动不生效（并明确告诉用户），也不能把数据写没了。
        if (additionalProfilesLoadFailed) {
            addLog("角色配置此前读取失败，为避免覆盖磁盘上的原有配置，本次写入已被拒绝。请重启应用后再试。")
            return
        }
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
                // 这里**不能**再用 state.isRecording 当条件：本地静音检测自动停录时走的是
                // finishListening(stopCapture = false, ...)，采集并没有停，但那个标志位会被
                // 清成 false。用它做门禁会导致「采集在跑、帧却被丢掉」，语音打断永远不成立。
                // 这个回调被调用本身就意味着采集正在进行，所以只需要判断播报状态：
                //   uploadDuringPlayback —— 回声已被消掉，播报期间继续发，用户说话服务端才收得到；
                //   否则 —— 播报期间必须停发，否则设备自己的声音会被当成人声，自己打断自己。
                val shouldUpload = !state.isAssistantSpeaking || uploadDuringPlayback
                if (shouldUpload) {
                    val sent = realtimeClient.sendAudioFrame(frame)
                    // 诊断用：确认播报期间上行通道真的在流动。语音打断失败时，
                    // 这条日志能立刻区分「音频没发出去」和「服务端没响应」。
                    if (state.isAssistantSpeaking && !playbackUplinkLogged) {
                        playbackUplinkLogged = true
                        addLog("播报期间已开始持续上行音频（语音打断通道已打开）")
                    }
                    sent
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
        // 打断的语义是「别说了，听我说」——所以打断之后必须落回**聆听中**，
        // 而不是甩回待机、逼用户重新喊一次唤醒词。（用户 2026-09-15 反馈：
        // "你的打断是直接一下把它打到待机中了…但我想要的不是打到待机，而是切回聆听中"。）
        //
        // conversationLoopActive 是这里唯一的关键开关，它同时管两件事：
        //   1. resumeConversationListeningAfterPlayback() 靠它决定播报结束后继续听还是 setNanoState("IDLE")；
        //   2. startListening() 的门禁 (isTurnActive && !isRecording && !conversationLoopActive)
        //      会因为它是 true 而放行——否则打断后立刻起听会被「请等小智说完再开始下一句」挡回来。
        // 之前这里写的是 false，于是打断必然落到待机，正是用户遇到的现象。
        conversationLoopActive = true
        scheduledResumeCancelledByUser = true
        ignoreLifecycleGoodbyeAfterScheduledDelivery = false
        clearPendingConversation()
        finishListening(
            sendStop = false,
            stopCapture = true,
            keepTurnActive = false,
            reason = "已请求打断，准备继续聆听",
        )
        audioEngine.clearPlayback { updateState { copy(isAssistantSpeaking = it) } }
        updateState { copy(isAssistantSpeaking = false) }
        if (realtimeClient.sendAbort()) {
            addLog("已向服务端发送打断请求")
        }
        // 这里刻意**不再** setNanoState("IDLE")：落回聆听后由 startListening() 推进状态。
        // resumeConversationListeningAfterPlayback() 内部有 300ms 延迟，
        // 正好留出 stopCapture() 收尾和 [abort] 到达服务端的时间。
        resumeConversationListeningAfterPlayback()
    }

    /**
     * 供开发期调试接口使用：等价于用户在输入框里打下这段文字并按下发送。
     * 刻意复用 updateDraftMessage + sendDraftMessage 这条完全相同的路径，
     * 不绕过“设备正忙”等任何门控，否则自动测出来的结论不代表用户会遇到的路径。
     * 返回一句人可读的结果，供断言。
     */
    fun submitExternalMessage(text: String): String {
        val prompt = text.trim()
        if (prompt.isBlank()) return "文本为空，未发送"
        val state = uiState.value
        if (state.isRecording || state.isAssistantSpeaking || state.isTurnActive) {
            return "设备正忙，未发送"
        }
        updateDraftMessage(prompt)
        sendDraftMessage()
        return "已发送"
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
        pendingTextPrompts.addLast(wireTextFor(prompt))
        updateState { copy(isTurnActive = true) }
        setNanoState("PROCESSING")
        addLog("准备发送文字消息")
        ensureReadyForConversation(trigger = "文字消息")
    }

    /**
     * 决定这条用户文字用什么形式发给服务端：
     * 短句直发原文；长句压成短暗号，正文留在本地等模型来取。
     */
    private fun wireTextFor(prompt: String): String {
        if (prompt.length <= USER_TEXT_DIRECT_LIMIT) return prompt
        synchronized(pendingUserMessages) { pendingUserMessages.addLast(prompt) }
        addLog("文字超过 ${USER_TEXT_DIRECT_LIMIT} 字，改发短暗号并由模型取回原文（共 ${prompt.length} 字）")
        return USER_TEXT_CODE_PHRASE
    }

    /** 供 MCP 工具 self.message.current 取回用户刚打的原话。 */
    fun takePendingUserMessage(): String? = synchronized(pendingUserMessages) {
        pendingUserMessages.pollFirst()
    }

    /** 判断一段模型输出是不是工具调用回显，而不是真正要对用户说的话。 */
    private fun isToolCallArtifact(text: String): Boolean {
        val normalized = text.trim().removePrefix("%").trim()
        return TOOL_CALL_ARTIFACT.containsMatchIn(normalized)
    }

    fun sendMcp() {
        if (realtimeClient.sendMcp(uiState.value.mcpPayload)) {
            addLog("已发送 MCP 请求")
        }
    }

    private fun handleScheduledReminderDue(reminder: ScheduledReminder) {
        // 监督任务的"到点"有**两种完全不同的含义**，必须分开处理：
        //   COUNTDOWN → WAITING_FOR_ACK：该提醒用户了，发【监督】让模型开口提醒；
        //   VERIFICATION_SCHEDULED → VERIFYING：该核实做没做了，**直接调摄像头**。
        //
        // 旧代码不区分阶段，核验到点又发一遍【监督】文本，于是摄像头核验永远不会发生
        // ——`startSupervisionVerification()` 写得很完整却成了一个没有调用点的死函数。
        if (reminder.kind == ReminderKind.SUPERVISION &&
            reminder.supervisionPhase == SupervisionPhase.VERIFYING
        ) {
            startSupervisionVerification(reminder)
            return
        }
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
        val current = reminderScheduler.activeSupervision()
        if (current?.id != reminder.id || current.supervisionPhase != SupervisionPhase.VERIFYING) {
            addLog("忽略已失效的监督核验：${reminder.message}")
            return
        }
        // 这里曾经是「已经在执行就 return」的**丢弃式**守卫，会造成任务永久卡死：
        // `ReminderScheduler.tick()` 会**一次性**把所有到期任务推进到 VERIFYING 并清空
        // dueAtEpochMs，然后才逐个回调 onDue；而 `viewModelScope.launch` 立即返回一个
        // 活跃 Job。于是第二个到期任务被守卫丢弃，而它既不会再到期、也没有任何重排路径
        // （重排只发生在 retryOrGiveUpSupervision 里，那要拿到摄像头结果才会被调用），
        // 结果是**永久停在「正在拍照核验」**。
        // 设备重启后 `ReminderScheduler.start()` 会把所有 VERIFYING 归一成"立即到期"，
        // 所以"两条以上监督任务同时到期"完全可达，第二条必然被丢弃。
        // （这条守卫是旧代码，但在此之前 startSupervisionVerification 是死代码、
        //   永远不会被调用；是 v1.2.6 把这个函数接通，才让潜伏缺陷变成活的。）
        // 现在改成 Mutex 排队：后来的等前面的跑完再跑，一次都不丢。
        supervisionVerificationJob = viewModelScope.launch {
            supervisionVerificationMutex.withLock { runSupervisionVerification(reminder) }
        }
    }

    /**
     * 真正跑一次摄像头核验。
     *
     * 与 [startSupervisionVerification] 分开是为了让"排队"成立：排队等待期间任务可能
     * 已被取消或换掉，所以**进入时还要再确认一次任务身份**，不能只信排出时的相位快照。
     */
    private suspend fun runSupervisionVerification(reminder: ScheduledReminder) {
        if (reminderScheduler.activeSupervision()?.id != reminder.id) {
            addLog("排队的监督核验已失效，跳过：${reminder.message}")
            return
        }
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
            return
        }

        val promptText = when (status) {
            SupervisionVisionStatus.COMPLETED -> {
                reminderScheduler.completeSupervision(reminder.id)
                addLog("摄像头已确认监督任务完成：${reminder.message}")
                // listen/detect is a wake-word channel; full task text is rejected as long text.
                "${SUPERVISION_RESULT_CODE_PHRASE}已完成"
            }
            SupervisionVisionStatus.NOT_COMPLETED -> {
                if (retryOrGiveUpSupervision(reminder, "确认任务尚未完成")) {
                    "${SUPERVISION_RESULT_CODE_PHRASE}未完成"
                } else {
                    "${SUPERVISION_RESULT_CODE_PHRASE}未完成，核验次数已用尽"
                }
            }
            SupervisionVisionStatus.UNCERTAIN -> {
                val reason = result.exceptionOrNull()?.message.orEmpty()
                val detail = "无法确认" + reason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
                if (retryOrGiveUpSupervision(reminder, detail)) {
                    "${SUPERVISION_RESULT_CODE_PHRASE}无法确认"
                } else {
                    "${SUPERVISION_RESULT_CODE_PHRASE}无法确认，核验次数已用尽"
                }
            }
        }
        enqueueScheduledPrompt("${reminder.id}-result-${reminder.checkCount}", ReminderKind.SUPERVISION, promptText)
    }

    /**
     * 监督提醒播报结束后的收尾。
     *
     * **播报结束不等于任务完成**——监督任务的语义是「到点提醒 → 留时间给用户去做 →
     * 调摄像头核实」。所以这里要转入「安排核验」，而不是把任务删掉。
     * 旧代码在播报结束时无条件 `completeSupervision()`，后果是：用户还没来得及做、
     * 任务就已经被删了，而且摄像头核验永远不会发生。
     */
    private fun scheduleSupervisionVerificationAfterDelivery(reminderId: String) {
        if (reminderScheduler.scheduleSupervisionVerification(reminderId, SUPERVISION_VERIFY_DELAY_SECONDS)) {
            addLog("监督提醒已播报，${SUPERVISION_VERIFY_DELAY_SECONDS} 秒后调摄像头核验")
        } else {
            addLog("监督任务状态不允许安排核验，已跳过（可能已被取消）")
        }
    }

    /**
     * 核验没通过时：还能再试就排下一次，次数用尽就停止跟踪。
     * 返回 true 表示「已安排下一次核验」。
     *
     * 必须设次数上限：`nextSupervisionRetrySeconds()` 只把**间隔**封顶在 30 分钟、
     * 不限制次数，所以任务一直判定未完成时会**每 30 分钟调一次摄像头、永远不停**，
     * 既扰民又耗电。
     */
    private fun retryOrGiveUpSupervision(reminder: ScheduledReminder, reason: String): Boolean {
        if (reminder.checkCount >= MAX_SUPERVISION_CHECKS) {
            reminderScheduler.completeSupervision(reminder.id)
            addLog("监督任务核验 $MAX_SUPERVISION_CHECKS 次仍未完成，停止跟踪：${reminder.message}")
            return false
        }
        val retrySeconds = nextSupervisionRetrySeconds(reminder.checkCount)
        // 必须看返回值：phase 不满足 WAITING_FOR_ACK / VERIFYING 时
        // scheduleSupervisionVerification 会返回 false（**并没有安排任何核验**）。
        // 旧写法无条件 return true 并打"N 秒后再次核验"，于是两件事同时发生：
        // 调用方据 true 回报给模型"稍后会再核验"（实际不会），日志也在撒谎。
        // 相邻的 scheduleSupervisionVerificationAfterDelivery 反而正确区分了两种情况，
        // 说明这里是遗漏而非设计。
        if (!reminderScheduler.scheduleSupervisionVerification(reminder.id, retrySeconds)) {
            addLog("监督任务状态已变化，无法安排再次核验：${reminder.message}")
            return false
        }
        addLog("摄像头$reason，$retrySeconds 秒后再次核验：${reminder.message}")
        return true
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
            ReminderKind.SUPERVISION -> scheduleSupervisionVerificationAfterDelivery(deliveredPrompt.reminderId)
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
                playbackUplinkLogged = false
                audioEngine.beginPlaybackSession()
                updateState { copy(isAssistantSpeaking = true, isTurnActive = true) }
                setNanoState("SPEAKING")
                // 语音打断（barge-in）的前提是「播报期间麦克风继续工作」——否则用户说话
                // 服务端根本收不到，只能靠点屏幕打断。
                // 回声由服务端消除：客户端在 hello 里声明 features.aec = true，服务端知道
                // 自己发过什么 TTS，用它当参考把回声减掉。
                // 蓝牙麦是例外：占着麦克风会明显劣化媒体音质，那种情况仍然释放。
                if (isBluetoothMicActive()) {
                    finishListening(
                        sendStop = false,
                        stopCapture = true,
                        keepTurnActive = true,
                        reason = "检测到蓝牙麦克风占用，播报前已释放录音以恢复媒体音质",
                    )
                } else {
                    addLog("播报开始，保持录音以支持语音打断")
                }
            }

            "sentence_start" -> {
                if (text.isNotBlank()) {
                    updateState { copy(lastTtsText = text) }
                    // 模型偶尔把自己的工具调用当成一句话吐出来（形如 “% self.timer.set…”），
                    // 这是协议噪声，不该出现在聊天气泡里。
                    if (isToolCallArtifact(text)) {
                        addLog("已过滤疑似工具调用回显：${text.take(60)}")
                    } else {
                        addChatMessage(ChatRole.ASSISTANT, text)
                    }
                }
            }

            "stop" -> {
                val deliveredPrompt = finishScheduledDelivery("提醒播报完成")
                when (deliveredPrompt?.reminderKind) {
                    ReminderKind.TIMER -> reminderScheduler.completeTimer(deliveredPrompt.reminderId)
                    ReminderKind.SUPERVISION -> scheduleSupervisionVerificationAfterDelivery(deliveredPrompt.reminderId)
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
            // 语音打断：播报期间服务端还能识别出用户在说话，说明它已经把自己的回声消掉了
            // （客户端在 hello 里声明了 features.aec）。这时本地必须**立刻**掐掉正在播的
            // 音频——走正常的 tts stop 是等缓冲放完，用户会听到旧语音盖住新一轮。
            if (uiState.value.isAssistantSpeaking) {
                addLog("检测到语音打断，立即停止播报")
                audioEngine.clearPlayback {
                    updateState { copy(isAssistantSpeaking = it) }
                }
                updateState { copy(isAssistantSpeaking = false) }
            }
            ignoreLifecycleGoodbyeAfterScheduledDelivery = false
            coordinateSupervisionFromUserSpeech(text)
            updateState { copy(lastSttText = text, isTurnActive = true) }
            // 本地短暗号（【定时提醒】【监督】【文字消息】…）会被服务端回显成 STT，
            // 再当作用户发言加一遍的话，用户会看到自己刚打的整句话变成了「【文字消息】」。
            if (isLocalCodePhrase(text)) {
                addLog("已忽略本地短暗号的 STT 回显：$text")
            } else {
                addChatMessage(ChatRole.USER, text)
            }
        }
    }

    /** 判断是不是设备自己发出去的短暗号。 */
    private fun isLocalCodePhrase(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length <= 12 && trimmed.startsWith("【") && trimmed.endsWith("】")
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
        // 服务端的拒绝事件以前只落到日志里，界面上一个字都没有——
        // 用户正常打一句话发出去、被服务端拒了，看起来就是“石沉大海”。
        val serverMessage = root.optString("message").orEmpty()
        if (serverMessage.isNotBlank()) {
            val friendly = when {
                serverMessage.contains("Detect is only for wake words", ignoreCase = true) ->
                    "这句话没能发出去：文字太长，服务端只接受短句。请说得短一点。"
                else -> "服务端提示：$serverMessage"
            }
            addChatMessage(ChatRole.SYSTEM, friendly)
        }
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
            assistantPortraitPath = storedConfig.assistantPortraitPath,
            // 这四个数字人视频路径以前漏读了。后果很重：每次冷启动状态里都是空值，
            // reloadRoleProfiles() 按空值重建主角色，紧接着的 persist() 又把空值写回磁盘，
            // 于是用户上传的素材配置被永久抹掉——文件还在，App 却再也找不到。
            // 测试者反馈的「两个角色各配了动图却无法切换」就是这个。
            idleVideoPath = storedConfig.idleVideoPath,
            greetingVideoPath = storedConfig.greetingVideoPath,
            listeningVideoPath = storedConfig.listeningVideoPath,
            speakingVideoPath = storedConfig.speakingVideoPath,
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
                assistantPortraitPath = uiState.value.assistantPortraitPath,
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

    /**
     * 一个角色立绘文件的**唯一命名规则**。
     *
     * 抽出来是因为它有三个使用点（导入、删除、清理），各写一份迟早会走偏——
     * 2026-09-21 真机实测就吃过这个亏：删除角色的两条路径都清理了头像和四段视频，
     * **唯独漏了立绘**，于是删完角色后 `portraits/role-<id>.jpg` 变成 14.85 MB 的孤儿文件，
     * 而这块板子存储本来就紧。
     */
    private fun portraitFileFor(roleId: String): File {
        val safeId = roleId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(File(getApplication<Application>().filesDir, "portraits"), "role-$safeId.jpg")
    }

    /**
     * 删掉一个角色在磁盘上的**全部**专属素材。
     *
     * 除了配置里记着的那几个路径，还按角色 id 再算一遍立绘路径——
     * 配置与磁盘不一致时（换过素材、或历史遗留）才不会留下孤儿文件。
     */
    private fun deleteRoleFiles(role: RoleProfile) {
        digitalHumanAssets.deleteRoleAssets(role.id)
        deleteFileIfOwned(role.avatarPath)
        deleteFileIfOwned(role.portraitPath)
        deleteFileIfOwned(portraitFileFor(role.id).absolutePath)
    }

    private fun deleteFileIfOwned(path: String) {
        if (path.isBlank()) return
        val file = File(path)
        val appFiles = getApplication<Application>().filesDir.canonicalFile
        runCatching {
            if (file.canonicalFile.toPath().startsWith(appFiles.toPath())) {
                file.delete()
            }
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
