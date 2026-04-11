package me.xiaozhi.androidclient.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

private const val RESTART_DELAY_MS = 650L
private const val BUSY_RESTART_DELAY_MS = 1_400L
private const val DETECTION_COOLDOWN_MS = 2_500L

class WakeWordRecognizer(
    context: Context,
    private val onWakeWordDetected: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private data class WakeWord(
        val displayText: String,
        val normalizedText: String,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var configuredWakeWords: List<WakeWord> = emptyList()
    private var shouldListen: Boolean = false
    private var released: Boolean = false
    private var lastDetectedAtMs: Long = 0L
    private var lastStatus: String = ""

    fun start(wakeWords: String) {
        if (released) {
            return
        }

        val parsedWakeWords = wakeWords
            .split(',', '，', ';', '；', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { WakeWord(displayText = it, normalizedText = normalizeText(it)) }
            .filter { it.normalizedText.isNotBlank() }
            .distinctBy(WakeWord::normalizedText)

        if (parsedWakeWords.isEmpty()) {
            shouldListen = false
            stop(updateStatus = false)
            publishStatus("请先在设置里填写唤醒词")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            shouldListen = false
            publishStatus("系统没有可用的语音识别服务")
            return
        }

        configuredWakeWords = parsedWakeWords
        shouldListen = true
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post(::startListeningInternal)
    }

    fun stop(updateStatus: Boolean = false) {
        shouldListen = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        if (updateStatus) {
            publishStatus("未启用")
        }
    }

    fun release() {
        released = true
        stop(updateStatus = false)
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun startListeningInternal() {
        if (!shouldListen || released) {
            return
        }
        val recognizer = ensureRecognizer() ?: return
        try {
            publishStatus("正在监听唤醒词")
            recognizer.cancel()
            recognizer.startListening(buildIntent())
        } catch (error: Exception) {
            onError("启动唤醒监听失败：${error.message.orEmpty()}")
            scheduleRestart(BUSY_RESTART_DELAY_MS)
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (speechRecognizer != null) {
            return speechRecognizer
        }
        return runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(listener)
                speechRecognizer = it
            }
        }.getOrElse { error ->
            onError("创建语音识别器失败：${error.message.orEmpty()}")
            null
        }
    }

    private fun buildIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
    }

    private fun scheduleRestart(delayMs: Long = RESTART_DELAY_MS) {
        if (!shouldListen || released) {
            return
        }
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed(::startListeningInternal, delayMs)
    }

    private fun handleRecognitionResults(results: Bundle?) {
        val matches = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val matchedPhrase = matches.firstNotNullOfOrNull(::matchWakeWord)
        if (matchedPhrase != null) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDetectedAtMs < DETECTION_COOLDOWN_MS) {
                scheduleRestart()
                return
            }
            lastDetectedAtMs = now
            shouldListen = false
            speechRecognizer?.cancel()
            publishStatus("已唤醒：$matchedPhrase")
            onWakeWordDetected(matchedPhrase)
        } else if (matches.isNotEmpty()) {
            publishStatus("待命中")
            scheduleRestart()
        }
    }

    private fun matchWakeWord(text: String): String? {
        val normalized = normalizeText(text)
        if (normalized.isBlank()) {
            return null
        }
        return configuredWakeWords.firstOrNull { wakeWord ->
            normalized.contains(wakeWord.normalizedText)
        }?.displayText
    }

    private fun normalizeText(text: String): String {
        return text
            .lowercase(Locale.CHINA)
            .filter(Char::isLetterOrDigit)
    }

    private fun publishStatus(status: String) {
        if (lastStatus == status) {
            return
        }
        lastStatus = status
        onStatusChanged(status)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            publishStatus("正在监听唤醒词")
        }

        override fun onBeginningOfSpeech() {
            publishStatus("检测到说话")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            publishStatus("识别中")
        }

        override fun onError(error: Int) {
            if (!shouldListen || released) {
                return
            }

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    publishStatus("待命中")
                    scheduleRestart(RESTART_DELAY_MS)
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    publishStatus("语音识别服务忙，稍后重试")
                    scheduleRestart(BUSY_RESTART_DELAY_MS)
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    publishStatus("语音识别服务暂时不可用")
                    scheduleRestart(BUSY_RESTART_DELAY_MS)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onError("语音唤醒缺少录音权限")
                }

                else -> {
                    publishStatus("待命中")
                    scheduleRestart(BUSY_RESTART_DELAY_MS)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            handleRecognitionResults(results)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            handleRecognitionResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
