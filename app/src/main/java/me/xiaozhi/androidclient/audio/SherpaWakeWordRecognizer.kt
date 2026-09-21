package me.xiaozhi.androidclient.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlin.concurrent.thread
import kotlin.math.max

private const val KWS_SAMPLE_RATE = 16_000
private const val KWS_FEATURE_DIM = 80
private const val KWS_MODEL_DIR =
    "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"
private const val KWS_PLACEHOLDER_KEYWORDS_FILE = "$KWS_MODEL_DIR/runtime-placeholder-keywords.txt"
private const val KWS_INTERVAL_MS = 100
private const val KWS_DETECTION_COOLDOWN_MS = 2_500L

/** 监听期间复查输入设备的间隔。见 [recheckInputDevice]。 */
private const val KWS_DEVICE_RECHECK_MS = 5_000L
private const val KWS_RELEASE_BEFORE_CAPTURE_MS = 350L
private const val KWS_REARM_RETRY_MS = 100L
private const val KWS_KEYWORDS_SCORE = 3.0f
private const val KWS_KEYWORDS_THRESHOLD = 0.25f
private const val LOG_TAG = "XiaozhiClient"

class SherpaWakeWordRecognizer(
    context: Context,
    private val onWakeWordDetected: (String) -> Boolean,
    private val onStatusChanged: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var keywordSpotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var configuredKeywords: String = ""
    private var released: Boolean = false
    private var lastDetectedAtMs: Long = 0L
    private var lastStatus: String = ""

    @Volatile
    private var shouldListen: Boolean = false

    @Volatile
    private var listeningRequested: Boolean = false

    /** 当前实际绑定的输入设备。用于判断"我是不是还绑在那个没焊咪头的板载麦上"。 */
    private var boundInputDevice: AudioDeviceInfo? = null

    /** 上一次复查输入设备的时刻。 */
    private var lastDeviceCheckAtMs: Long = 0L

    fun start(wakeWords: String) {
        if (released) {
            return
        }

        val keywords = normalizeKeywords(wakeWords)
        if (keywords.isBlank()) {
            stop(updateStatus = false)
            publishStatus("请先在设置里填写唤醒词")
            return
        }

        if (shouldListen && keywords == configuredKeywords) {
            return
        }

        stop(updateStatus = false)
        val oldThread = recordingThread
        if (oldThread?.isAlive == true) {
            runCatching { oldThread.join(500) }
            if (oldThread.isAlive) {
                publishStatus("离线唤醒重启中")
                return
            }
        }
        configuredKeywords = keywords
        listeningRequested = true
        shouldListen = true
        Log.d(LOG_TAG, "[KWS] start keywords=$keywords")
        publishStatus("正在初始化离线唤醒")

        recordingThread = thread(start = true, name = "sherpa-kws-listener") {
            runKwsLoop()
        }
    }

    fun stop(updateStatus: Boolean = false) {
        listeningRequested = false
        shouldListen = false
        runCatching { audioRecord?.stop() }
        if (updateStatus) {
            publishStatus("未启用")
        }
    }

    fun release() {
        released = true
        stop(updateStatus = false)
        runCatching { recordingThread?.join(500) }
        releaseAudioRecord()
        releaseStream()
        keywordSpotter?.release()
        keywordSpotter = null
    }

    private fun runKwsLoop() {
        val kws = ensureKeywordSpotter() ?: return
        val record = createAudioRecord() ?: run {
            publishError("启动离线唤醒失败：无法初始化麦克风")
            shouldListen = false
            return
        }

        val createdStream = runCatching { kws.createStream(configuredKeywords) }.getOrElse { error ->
            publishError("启动离线唤醒失败：${error.message.orEmpty()}")
            shouldListen = false
            releaseAudioRecord(record)
            return
        }

        if (createdStream.ptr == 0L) {
            publishError("启动离线唤醒失败：唤醒词格式不可用")
            shouldListen = false
            releaseAudioRecord(record)
            createdStream.release()
            return
        }

        if (!shouldListen || released) {
            releaseAudioRecord(record)
            createdStream.release()
            return
        }

        stream = createdStream
        audioRecord = record

        try {
            record.startRecording()
            publishStatus("正在监听唤醒词")
            processSamples(record, kws, createdStream)
        } catch (error: Exception) {
            if (shouldListen && !released) {
                publishError("离线唤醒异常：${error.message.orEmpty()}")
            }
        } finally {
            releaseAudioRecord(record)
            if (stream === createdStream) {
                stream = null
            }
            createdStream.release()
            if (recordingThread === Thread.currentThread()) {
                recordingThread = null
            }
        }
    }

    private fun ensureKeywordSpotter(): KeywordSpotter? {
        keywordSpotter?.let { return it }

        return runCatching {
            Log.d(
                LOG_TAG,
                "[KWS] config score=$KWS_KEYWORDS_SCORE threshold=$KWS_KEYWORDS_THRESHOLD",
            )
            KeywordSpotter(
                assetManager = appContext.assets,
                config = KeywordSpotterConfig(
                    featConfig = getFeatureConfig(
                        sampleRate = KWS_SAMPLE_RATE,
                        featureDim = KWS_FEATURE_DIM,
                    ),
                    modelConfig = getMobileKwsModelConfig(),
                    // This JNI version requires a non-empty keyword file even when
                    // every real keyword is supplied dynamically to createStream().
                    keywordsFile = KWS_PLACEHOLDER_KEYWORDS_FILE,
                    keywordsScore = KWS_KEYWORDS_SCORE,
                    keywordsThreshold = KWS_KEYWORDS_THRESHOLD,
                ),
            ).also { keywordSpotter = it }
        }.getOrElse { error ->
            publishError("加载离线唤醒模型失败：${error.message.orEmpty()}")
            null
        }
    }

    private fun processSamples(
        record: AudioRecord,
        kws: KeywordSpotter,
        stream: OnlineStream,
    ) {
        val bufferSize = KWS_SAMPLE_RATE * KWS_INTERVAL_MS / 1000
        val buffer = ShortArray(bufferSize)

        while (shouldListen && !released) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) {
                continue
            }

            // 监听期间定期复查输入设备：USB 麦克风可能在 App 启动之后才被系统枚举出来，
            // 而选择器只在"开录那一刻"看得到它。绑错了就在这里自愈。
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastDeviceCheckAtMs >= KWS_DEVICE_RECHECK_MS) {
                lastDeviceCheckAtMs = nowMs
                recheckInputDevice(record)
            }

            val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
            stream.acceptWaveform(samples, sampleRate = KWS_SAMPLE_RATE)

            while (shouldListen && kws.isReady(stream)) {
                kws.decode(stream)
                val keyword = kws.getResult(stream).keyword
                if (keyword.isNotBlank()) {
                    kws.reset(stream)
                    // **只有真的被受理了才退出监听循环。**
                    //
                    // 冷却期（KWS_DETECTION_COOLDOWN_MS）内命中的那一次会被
                    // handleDetectedKeyword 直接丢弃。旧写法在这里无条件 `return`，
                    // 于是录音线程退出了、而 shouldListen 仍是 true、界面还挂着
                    // 「正在监听唤醒词」—— 设备从此**再也唤不醒**，只能重启。
                    //
                    // 触发场景很普通：喊一遍没反应、紧接着再喊一遍（间隔 < 2.5 秒）。
                    // 底栏的手动麦克风键移除之后，唤醒词是唯一的启动方式，
                    // 这条就从"可能发生"变成"一旦发生就没救"。（评审 #19）
                    if (handleDetectedKeyword(keyword)) {
                        return
                    }
                }
            }
        }
    }

    /**
     * 处理一次唤醒词命中。
     *
     * @return **true 表示这次命中被真正受理**（已停掉录音、交给上层去开对话），
     *   调用方应当退出监听循环；**false 表示被冷却期抑制、什么都没做**，
     *   调用方必须**继续监听**，不能退出循环——否则线程结束了而状态还写着
     *   「正在监听」，设备就再也唤不醒了。
     */
    private fun handleDetectedKeyword(keyword: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDetectedAtMs < KWS_DETECTION_COOLDOWN_MS) {
            // 冷却期内：这是同一句话被重复识别，抑制它但保持监听。
            Log.d(LOG_TAG, "[KWS] 冷却期内重复命中，忽略并继续监听：$keyword")
            return false
        }
        lastDetectedAtMs = now
        shouldListen = false
        runCatching { audioRecord?.stop() }
        publishStatus("已唤醒：$keyword")
        mainHandler.postDelayed(
            {
                if (onWakeWordDetected(keyword)) {
                    restartAfterIgnoredDetection()
                }
            },
            KWS_RELEASE_BEFORE_CAPTURE_MS,
        )
        return true
    }

    private fun restartAfterIgnoredDetection() {
        if (released || !listeningRequested || shouldListen || configuredKeywords.isBlank()) {
            return
        }
        val oldThread = recordingThread
        if (oldThread?.isAlive == true) {
            mainHandler.postDelayed(::restartAfterIgnoredDetection, KWS_REARM_RETRY_MS)
            return
        }
        shouldListen = true
        Log.d(LOG_TAG, "[KWS] ignored keyword; rearming dynamic keyword stream")
        publishStatus("正在监听唤醒词")
        recordingThread = thread(start = true, name = "sherpa-kws-listener") {
            runKwsLoop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            KWS_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBufferSize, KWS_SAMPLE_RATE / 2 * 2)

        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )

        for (source in candidates) {
            val record = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(KWS_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            }.getOrNull()

            if (record?.state == AudioRecord.STATE_INITIALIZED) {
                applyPreferredInputDevice(record)
                Log.d(LOG_TAG, "[KWS] AudioRecord initialized source=$source bufferSize=$bufferSize")
                return record
            }
            record?.release()
        }

        return null
    }

    private fun applyPreferredInputDevice(record: AudioRecord) {
        // 走共享选择器：它会在"这一刻没有 USB/有线麦"时**等一小会儿**再退到板载麦。
        // 这里是 KWS 的录音线程（后台），阻塞等待是安全的。
        //
        // 曾经的写法是就地查一次 getDevices() 然后一路 `?:` 退到板载麦 —— 而 USB 麦
        // 常常在 App 启动之后才被枚举出来，于是唤醒器绑死在**没焊咪头**的板载通路上，
        // 设备彻底听不见，界面却还写着"正在监听唤醒词"。（2026-09-21 真机事故）
        val preferred = InputDeviceSelector.select(audioManager, preferBuiltin = useDeviceAec)
        preferred?.let { device ->
            runCatching { record.preferredDevice = device }
            boundInputDevice = device
            Log.d(LOG_TAG, "[KWS] preferred input device=${device.productName} type=${device.type}")
        } ?: Log.w(LOG_TAG, "[KWS] 没有可用的输入设备")
    }

    /**
     * 监听期间复查输入设备，**绑错了就就地切过去**。
     *
     * 为什么需要这一层：本机唯一能拾音的是 USB 摄像头麦（card 2），板载 codec 的采集通路
     * **没有焊咪头**（实测录到的是平坦噪声）。而 USB 音频设备常在 App 启动之后才被系统枚举出来
     * —— 实测抓到同一个进程里，启动时只看到板载麦、一分多钟后 USB 麦才出现。
     * [InputDeviceSelector] 已经在"选的那一刻"等了 2 秒，但如果 USB 麦是在 2 秒之后才出现的，
     * 唤醒器就会一直绑在聋的板载麦上，**界面却还写着「正在监听唤醒词」**。
     *
     * `AudioRecord.setPreferredDevice` 支持在录音过程中改路由，所以不需要重启录音线程：
     * 只要发现自己还绑在板载麦上、而 USB 麦已经出现，直接切过去就行。
     */
    private fun recheckInputDevice(record: AudioRecord) {
        val current = boundInputDevice
        // 已经绑在真正的麦克风上了，不用管。
        if (current != null && current.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) return

        val better = InputDeviceSelector.inputsOf(audioManager)
            .firstOrNull(InputDeviceSelector::isUsbInput) ?: return

        runCatching { record.preferredDevice = better }
        boundInputDevice = better
        Log.w(
            LOG_TAG,
            "[KWS] 之前绑在板载麦上（本机没有焊咪头，等于听不见），USB 麦克风出现后已自动切过去：" +
                "${better.productName} type=${better.type}",
        )
    }

    private fun normalizeKeywords(wakeWords: String): String {
        return SherpaKeywordCompiler.compileList(wakeWords)
    }

    private fun getMobileKwsModelConfig(): OnlineModelConfig {
        return OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$KWS_MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                decoder = "$KWS_MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                joiner = "$KWS_MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            ),
            tokens = "$KWS_MODEL_DIR/tokens.txt",
            modelType = "zipformer2",
        )
    }

    private fun releaseStream() {
        stream?.release()
        stream = null
    }

    private fun releaseAudioRecord(record: AudioRecord? = audioRecord) {
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
    }

    private fun publishStatus(status: String) {
        if (lastStatus == status) {
            return
        }
        lastStatus = status
        Log.d(LOG_TAG, "[KWS] $status")
        mainHandler.post { onStatusChanged(status) }
    }

    private fun publishError(message: String) {
        Log.e(LOG_TAG, "[KWS] $message")
        mainHandler.post { onError(message) }
    }
}
