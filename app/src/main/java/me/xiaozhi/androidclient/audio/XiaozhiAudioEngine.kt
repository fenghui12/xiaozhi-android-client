package me.xiaozhi.androidclient.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.xiaozhi.androidclient.model.ListeningMode

private const val INPUT_SAMPLE_RATE = 16000
private const val INPUT_CHANNELS = 1
private const val INPUT_FRAME_DURATION_MS = 60
private const val INPUT_FRAME_SIZE = INPUT_SAMPLE_RATE * INPUT_FRAME_DURATION_MS / 1000
private const val DEFAULT_OUTPUT_SAMPLE_RATE = 24000
private const val DEFAULT_OUTPUT_FRAME_DURATION_MS = 60
private const val FALLBACK_PLAYBACK_SAMPLE_RATE = 48000
private const val MAX_PLAYBACK_GAIN = 4.0f
private const val TARGET_PLAYBACK_PEAK = 14000
private const val MIN_DYNAMIC_GAIN_PEAK = 256
private const val GAIN_ATTACK = 0.18f
private const val GAIN_RELEASE = 0.08f
private const val PLAYBACK_BUFFER_FRAMES = 20
private const val PLAYBACK_START_THRESHOLD_FRAMES = 3
private const val DEBUG_WAV_NAME = "last_tts.wav"
private const val MAX_OPUS_PACKET_BYTES = 4096
private const val AUTO_STOP_SILENCE_FRAMES = 12
private const val AUTO_STOP_THRESHOLD = 700.0
private const val DEFAULT_AUDIO_ROUTE = "媒体输出：扬声器 / 输入：机身麦克风"

class XiaozhiAudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val playbackScope = CoroutineScope(SupervisorJob() + playbackDispatcher)
    private val playbackMutex = Mutex()
    private val captureMutex = Mutex()
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshAudioRouteStatus()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshAudioRouteStatus()
        }
    }

    private var captureJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var decoder: OpusDecoder? = null
    private var streamSampleRate: Int = DEFAULT_OUTPUT_SAMPLE_RATE
    private var decoderSampleRate: Int = DEFAULT_OUTPUT_SAMPLE_RATE
    private var decoderFrameDurationMs: Int = DEFAULT_OUTPUT_FRAME_DURATION_MS
    private var playbackSampleRate: Int = FALLBACK_PLAYBACK_SAMPLE_RATE
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var routeStatusListener: ((String) -> Unit)? = null
    private var debugListener: ((String) -> Unit)? = null
    private var debugLoggingEnabled: Boolean = false
    private var debugWavDumpEnabled: Boolean = false
    private var lastRouteStatus: String = DEFAULT_AUDIO_ROUTE
    private var deviceCallbackRegistered: Boolean = false
    private var debugWavWriter: PcmWavWriter? = null
    private var playbackFrameCount: Int = 0
    private var lastPlaybackPeak: Int = 0
    private var lastPlaybackGain: Float = 1.0f
    private var smoothedPlaybackGain: Float = 1.0f
    private var playbackStarted: Boolean = false
    private var maxPlaybackPeak: Int = 0
    private var playbackPeakTotal: Long = 0
    private var lowPeakPlaybackFrames: Int = 0
    private var playbackFramesWritten: Long = 0

    @Volatile
    private var isCaptureRunning: Boolean = false

    init {
        playbackSampleRate = resolvePlaybackSampleRate()
        decoderSampleRate = resolveDecoderSampleRate(playbackSampleRate)
        registerDeviceCallback()
        refreshAudioRouteStatus()
    }

    fun setRouteStatusListener(listener: (String) -> Unit) {
        routeStatusListener = listener
        listener(lastRouteStatus)
    }

    fun setDebugListener(listener: (String) -> Unit) {
        debugListener = listener
    }

    fun setDebugOptions(loggingEnabled: Boolean, wavDumpEnabled: Boolean) {
        debugLoggingEnabled = loggingEnabled
        debugWavDumpEnabled = wavDumpEnabled
        if (!wavDumpEnabled) {
            closeDebugWavWriter()
        }
    }

    fun configurePlayback(sampleRate: Int?, frameDurationMs: Int?) {
        val normalizedRate = when (sampleRate) {
            8000, 12000, 16000, 24000, 48000 -> sampleRate
            else -> DEFAULT_OUTPUT_SAMPLE_RATE
        }
        val normalizedDuration = frameDurationMs?.takeIf { it in listOf(10, 20, 40, 60) }
            ?: DEFAULT_OUTPUT_FRAME_DURATION_MS
        if (normalizedRate == streamSampleRate && normalizedDuration == decoderFrameDurationMs) {
            return
        }

        playbackScope.launch {
            playbackMutex.withLock {
                streamSampleRate = normalizedRate
                decoderFrameDurationMs = normalizedDuration
                playbackSampleRate = resolvePlaybackSampleRate()
                decoderSampleRate = resolveDecoderSampleRate(playbackSampleRate)
                releasePlaybackLocked()
            }
        }
    }

    fun beginPlaybackSession() {
        playbackFrameCount = 0
        lastPlaybackPeak = 0
        lastPlaybackGain = 1.0f
        smoothedPlaybackGain = 1.0f
        playbackStarted = false
        maxPlaybackPeak = 0
        playbackPeakTotal = 0
        lowPeakPlaybackFrames = 0
        playbackFramesWritten = 0
        closeDebugWavWriter()
        refreshAudioRouteStatus()
    }

    fun startCapture(
        mode: ListeningMode,
        onEncodedFrame: (ByteArray) -> Boolean,
        onAutoStop: () -> Unit,
        onRecordingChanged: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (captureJob != null) {
            return
        }

        captureJob = scope.launch {
            captureMutex.withLock {
                val encoder = try {
                    OpusEncoder(
                        INPUT_SAMPLE_RATE,
                        INPUT_CHANNELS,
                        OpusApplication.OPUS_APPLICATION_VOIP,
                    ).apply {
                        setBitrate(24000)
                        setComplexity(8)
                    }
                } catch (error: OpusException) {
                    onError("创建 Opus 编码器失败：${error.message.orEmpty()}")
                    captureJob = null
                    return@withLock
                }

                val audioRecord = createAudioRecord()
                if (audioRecord == null) {
                    onError("初始化麦克风失败")
                    captureJob = null
                    return@withLock
                }

                attachAudioEffects(audioRecord)
                applyPreferredInputDevice(audioRecord)
                refreshAudioRouteStatus()

                val pcmFrame = ShortArray(INPUT_FRAME_SIZE)
                val encodedBuffer = ByteArray(MAX_OPUS_PACKET_BYTES)
                var speechDetected = mode != ListeningMode.AUTO
                var silenceFrames = 0

                try {
                    audioRecord.startRecording()
                    isCaptureRunning = true
                    onRecordingChanged(true)

                    while (isActive) {
                        val readSamples = audioRecord.read(
                            pcmFrame,
                            0,
                            pcmFrame.size,
                            AudioRecord.READ_BLOCKING,
                        )
                        if (readSamples <= 0) {
                            onError("读取麦克风失败：$readSamples")
                            break
                        }

                        if (readSamples < pcmFrame.size) {
                            for (index in readSamples until pcmFrame.size) {
                                pcmFrame[index] = 0
                            }
                        }

                        if (mode == ListeningMode.AUTO) {
                            val rms = calculateRms(pcmFrame)
                            if (rms >= AUTO_STOP_THRESHOLD) {
                                speechDetected = true
                                silenceFrames = 0
                            } else if (speechDetected) {
                                silenceFrames += 1
                            }
                        }

                        val encodedSize = encoder.encode(
                            pcmFrame,
                            0,
                            INPUT_FRAME_SIZE,
                            encodedBuffer,
                            0,
                            encodedBuffer.size,
                        )
                        if (encodedSize > 0) {
                            val sent = onEncodedFrame(encodedBuffer.copyOf(encodedSize))
                            if (!sent) {
                                onError("发送编码后的音频帧失败")
                                break
                            }
                        }

                        if (mode == ListeningMode.AUTO && speechDetected && silenceFrames >= AUTO_STOP_SILENCE_FRAMES) {
                            onAutoStop()
                            break
                        }
                    }
                } catch (error: Exception) {
                    onError("录音失败：${error.message.orEmpty()}")
                } finally {
                    releaseAudioEffects()
                    try {
                        audioRecord.stop()
                    } catch (_: IllegalStateException) {
                    }
                    audioRecord.release()
                    isCaptureRunning = false
                    onRecordingChanged(false)
                    captureJob = null
                    refreshAudioRouteStatus()
                }
            }
        }
    }

    suspend fun stopCapture() {
        captureJob?.cancelAndJoin()
        captureJob = null
        isCaptureRunning = false
        refreshAudioRouteStatus()
    }

    fun isCapturing(): Boolean = isCaptureRunning

    fun playOpusFrame(
        opusFrame: ByteArray,
        onPlaybackChanged: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        playbackScope.launch {
            playbackMutex.withLock {
                try {
                    val frameSize = decoderSampleRate * decoderFrameDurationMs / 1000
                    val outputRate = playbackSampleRate
                    val activeDecoder = decoder ?: OpusDecoder(decoderSampleRate, 1).also { decoder = it }
                    val outputFrameSize = max(1, frameSize * outputRate / decoderSampleRate)
                    val track = audioTrack ?: createAudioTrack(outputRate, outputFrameSize).also {
                        it.setVolume(1.0f)
                        audioTrack = it
                    }
                    val pcmBuffer = ShortArray(frameSize)
                    val decodedSamples = activeDecoder.decode(
                        opusFrame,
                        0,
                        opusFrame.size,
                        pcmBuffer,
                        0,
                        frameSize,
                        false,
                    )
                    if (decodedSamples <= 0) {
                        return@withLock
                    }
                    val resampledBuffer = resampleMonoPcm(
                        input = pcmBuffer,
                        inputSize = decodedSamples,
                        inputSampleRate = decoderSampleRate,
                        outputSampleRate = outputRate,
                    )
                    val inputPeak = measurePeak(resampledBuffer)
                    val gain = computePlaybackGain(inputPeak)
                    val boostedBuffer = applyGain(resampledBuffer, gain)
                    val stereoBuffer = monoToStereo(boostedBuffer)
                    ensureDebugWavWriter(outputRate)
                    debugWavWriter?.write(stereoBuffer)
                    playbackFrameCount += 1
                    lastPlaybackPeak = inputPeak
                    lastPlaybackGain = gain
                    maxPlaybackPeak = max(maxPlaybackPeak, inputPeak)
                    playbackPeakTotal += inputPeak
                    if (inputPeak < MIN_DYNAMIC_GAIN_PEAK) {
                        lowPeakPlaybackFrames += 1
                    }
                    if (!playbackStarted && playbackFrameCount >= PLAYBACK_START_THRESHOLD_FRAMES) {
                        track.play()
                        playbackStarted = true
                    }
                    track.write(stereoBuffer, 0, stereoBuffer.size, AudioTrack.WRITE_BLOCKING)
                    playbackFramesWritten += stereoBuffer.size / 2L
                    if (playbackFrameCount == 1) {
                        emitDebug(
                            "audio_playback: stream=${streamSampleRate}Hz decoder=${decoderSampleRate}Hz output=${outputRate}Hz frame=$decodedSamples peak=$inputPeak gain=${"%.2f".format(gain)}",
                        )
                    }
                    if (playbackStarted) {
                        onPlaybackChanged(true)
                    }
                } catch (error: Exception) {
                    onError("音频播放失败：${error.message.orEmpty()}")
                    releasePlaybackLocked()
                }
            }
        }
    }

    fun finishPlayback(onPlaybackChanged: (Boolean) -> Unit = {}) {
        playbackScope.launch {
            playbackMutex.withLock {
                audioTrack?.let { track ->
                    try {
                        if (playbackFrameCount > 0 && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play()
                        }
                        waitForPlaybackDrain(track)
                        track.pause()
                        track.flush()
                    } catch (_: IllegalStateException) {
                    }
                }
                emitPlaybackSummary()
                closeDebugWavWriter()
                onPlaybackChanged(false)
                refreshAudioRouteStatus()
            }
        }
    }

    fun clearPlayback(onPlaybackChanged: (Boolean) -> Unit = {}) {
        playbackScope.launch {
            playbackMutex.withLock {
                audioTrack?.let { track ->
                    try {
                        track.pause()
                        track.flush()
                    } catch (_: IllegalStateException) {
                    }
                }
                emitPlaybackSummary()
                closeDebugWavWriter()
                onPlaybackChanged(false)
                refreshAudioRouteStatus()
            }
        }
    }

    fun release(onPlaybackChanged: (Boolean) -> Unit = {}) {
        scope.launch {
            stopCapture()
            playbackScope.launch {
                playbackMutex.withLock {
                    releasePlaybackLocked()
                    onPlaybackChanged(false)
                }
            }
            unregisterDeviceCallback()
            refreshAudioRouteStatus()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val bufferSize = max(
            AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            INPUT_FRAME_SIZE * 8,
        )

        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )

        for (source in candidates) {
            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(INPUT_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    applyPreferredInputDevice(record)
                    Log.d("XiaozhiClient", "[CAPTURE] AudioRecord initialized source=$source bufferSize=$bufferSize")
                    return record
                }
                record.release()
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun attachAudioEffects(audioRecord: AudioRecord) {
        releaseAudioEffects()
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply {
                enabled = true
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)?.apply {
                enabled = true
            }
        }
    }

    private fun releaseAudioEffects() {
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    private fun createAudioTrack(sampleRate: Int, frameSize: Int): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBufferSize, frameSize * 2 * 2 * PLAYBACK_BUFFER_FRAMES)
        val attributesBuilder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            attributesBuilder.setSpatializationBehavior(
                AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER,
            )
        }
        return AudioTrack.Builder()
            .setAudioAttributes(attributesBuilder.build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun releasePlaybackLocked() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: IllegalStateException) {
        }
        closeDebugWavWriter()
        audioTrack = null
        decoder = null
    }

    private fun applyPreferredInputDevice(audioRecord: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        findPreferredInputDevice()?.let { device ->
            runCatching { audioRecord.preferredDevice = device }
            Log.d("XiaozhiClient", "[CAPTURE] preferred input device=${device.productName} type=${device.type}")
        }
    }

    private fun refreshAudioRouteStatus() {
        publishRouteStatus(
            "媒体输出：${describeOutputDevice(findPreferredOutputDevice())} / 输入：${describeInputDevice(findPreferredInputDevice())}",
        )
    }

    private fun findPreferredInputDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.firstOrNull(::isUsbInputDevice)
            ?: devices.firstOrNull(::isWiredInputDevice)
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: devices.firstOrNull(::isBluetoothInputDevice)
            ?: devices.firstOrNull()
    }

    private fun findPreferredOutputDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.firstOrNull(::isBluetoothMediaOutputDevice)
            ?: devices.firstOrNull(::isWiredOutputDevice)
            ?: devices.firstOrNull(::isSpeakerDevice)
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            ?: devices.firstOrNull()
    }

    private fun describeOutputDevice(device: AudioDeviceInfo?): String {
        return when {
            device == null -> "扬声器"
            isBluetoothMediaOutputDevice(device) -> "蓝牙耳机"
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
            isWiredOutputDevice(device) -> "有线耳机"
            device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
            isSpeakerDevice(device) -> "扬声器"
            else -> "扬声器"
        }
    }

    private fun describeInputDevice(device: AudioDeviceInfo?): String {
        return when {
            device == null -> "机身麦克风"
            isBluetoothInputDevice(device) -> "蓝牙麦克风"
            isUsbInputDevice(device) -> "USB 麦克风"
            isWiredInputDevice(device) -> "耳机麦克风"
            device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "机身麦克风"
            else -> "机身麦克风"
        }
    }

    private fun isBluetoothMediaOutputDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
    }

    private fun isBluetoothInputDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun isWiredOutputDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun isWiredInputDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun isUsbInputDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE
    }

    private fun isSpeakerDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    private fun publishRouteStatus(route: String) {
        val normalized = route.ifBlank { DEFAULT_AUDIO_ROUTE }
        if (lastRouteStatus == normalized) {
            return
        }
        lastRouteStatus = normalized
        routeStatusListener?.invoke(normalized)
    }

    private fun registerDeviceCallback() {
        if (deviceCallbackRegistered) {
            return
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        deviceCallbackRegistered = true
    }

    private fun unregisterDeviceCallback() {
        if (!deviceCallbackRegistered) {
            return
        }
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        deviceCallbackRegistered = false
    }

    private fun calculateRms(frame: ShortArray): Double {
        var sum = 0.0
        for (sample in frame) {
            sum += abs(sample.toInt()).toDouble()
        }
        return sum / frame.size
    }

    private fun resolvePlaybackSampleRate(): Int {
        return audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it >= 16000 }
            ?: FALLBACK_PLAYBACK_SAMPLE_RATE
    }

    private fun resolveDecoderSampleRate(outputSampleRate: Int): Int {
        return when {
            outputSampleRate >= 48000 -> 48000
            outputSampleRate >= 24000 -> 24000
            outputSampleRate >= 16000 -> 16000
            outputSampleRate >= 12000 -> 12000
            else -> 8000
        }
    }

    private fun resampleMonoPcm(
        input: ShortArray,
        inputSize: Int,
        inputSampleRate: Int,
        outputSampleRate: Int,
    ): ShortArray {
        if (inputSize <= 0) {
            return ShortArray(0)
        }
        if (inputSampleRate == outputSampleRate) {
            return input.copyOf(inputSize)
        }

        val outputSize = max(1, (inputSize.toDouble() * outputSampleRate / inputSampleRate).roundToInt())
        val output = ShortArray(outputSize)
        val step = inputSampleRate.toDouble() / outputSampleRate
        for (index in 0 until outputSize) {
            val sourceIndex = index * step
            val left = sourceIndex.toInt().coerceIn(0, inputSize - 1)
            val right = min(left + 1, inputSize - 1)
            val ratio = sourceIndex - left
            val interpolated = input[left] + (input[right] - input[left]) * ratio
            output[index] = interpolated.roundToInt().toShort()
        }
        return output
    }

    private fun measurePeak(input: ShortArray): Int {
        var peak = 0
        for (sample in input) {
            peak = max(peak, abs(sample.toInt()))
        }
        return peak
    }

    private fun computePlaybackGain(peak: Int): Float {
        if (peak < MIN_DYNAMIC_GAIN_PEAK) {
            return smoothedPlaybackGain
        }
        val desiredGain = min(MAX_PLAYBACK_GAIN, max(1f, TARGET_PLAYBACK_PEAK.toFloat() / peak))
        val smoothing = if (desiredGain > smoothedPlaybackGain) GAIN_ATTACK else GAIN_RELEASE
        smoothedPlaybackGain += (desiredGain - smoothedPlaybackGain) * smoothing
        return smoothedPlaybackGain
    }

    private fun applyGain(input: ShortArray, gain: Float): ShortArray {
        if (input.isEmpty() || gain <= 1.01f) {
            return input
        }

        val output = ShortArray(input.size)
        for (index in input.indices) {
            output[index] = (input[index] * gain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return output
    }

    private fun monoToStereo(input: ShortArray): ShortArray {
        if (input.isEmpty()) {
            return ShortArray(0)
        }
        val output = ShortArray(input.size * 2)
        var outputIndex = 0
        for (sample in input) {
            output[outputIndex++] = sample
            output[outputIndex++] = sample
        }
        return output
    }

    private fun ensureDebugWavWriter(sampleRate: Int) {
        if (!debugWavDumpEnabled) {
            return
        }
        if (debugWavWriter != null) {
            return
        }
        val debugDir = File(appContext.cacheDir, "audio-debug").apply { mkdirs() }
        val debugFile = File(debugDir, DEBUG_WAV_NAME)
        debugWavWriter = runCatching { PcmWavWriter(debugFile, sampleRate, 2) }
            .onFailure { emitDebug("audio_debug: failed to create wav dump: ${it.message.orEmpty()}") }
            .getOrNull()
    }

    private fun closeDebugWavWriter() {
        debugWavWriter?.runCatching { close() }
        debugWavWriter = null
    }

    private fun emitPlaybackSummary() {
        if (playbackFrameCount <= 0) {
            return
        }
        val debugFile = File(File(appContext.cacheDir, "audio-debug"), DEBUG_WAV_NAME)
        val avgPeak = playbackPeakTotal.toDouble() / playbackFrameCount.toDouble()
        val underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioTrack?.underrunCount ?: -1
        } else {
            -1
        }
        emitDebug(
            "audio_playback_done: frames=$playbackFrameCount maxPeak=$maxPlaybackPeak avgPeak=${"%.1f".format(avgPeak)} lowPeakFrames=$lowPeakPlaybackFrames lastPeak=$lastPlaybackPeak gain=${"%.2f".format(lastPlaybackGain)} underruns=$underruns dump=${debugFile.absolutePath}",
        )
    }

    private suspend fun waitForPlaybackDrain(track: AudioTrack) {
        val deadlineMs = System.currentTimeMillis() + 1_500L
        while (System.currentTimeMillis() < deadlineMs) {
            val remainingFrames = playbackFramesWritten - track.playbackHeadPosition.toLong()
            if (remainingFrames <= 0L) {
                break
            }
            delay(12)
        }
    }

    private fun emitDebug(message: String) {
        if (debugLoggingEnabled) {
            debugListener?.invoke(message)
        }
    }

}
