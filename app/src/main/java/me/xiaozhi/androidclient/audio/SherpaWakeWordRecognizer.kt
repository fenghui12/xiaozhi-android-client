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

            val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
            stream.acceptWaveform(samples, sampleRate = KWS_SAMPLE_RATE)

            while (shouldListen && kws.isReady(stream)) {
                kws.decode(stream)
                val keyword = kws.getResult(stream).keyword
                if (keyword.isNotBlank()) {
                    kws.reset(stream)
                    handleDetectedKeyword(keyword)
                    return
                }
            }
        }
    }

    private fun handleDetectedKeyword(keyword: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDetectedAtMs < KWS_DETECTION_COOLDOWN_MS) {
            return
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val preferred = devices.firstOrNull(::isUsbInputDevice)
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: devices.firstOrNull()
        preferred?.let { device ->
            runCatching { record.preferredDevice = device }
            Log.d(LOG_TAG, "[KWS] preferred input device=${device.productName} type=${device.type}")
        }
    }

    private fun isUsbInputDevice(device: AudioDeviceInfo): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            (device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE)
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
