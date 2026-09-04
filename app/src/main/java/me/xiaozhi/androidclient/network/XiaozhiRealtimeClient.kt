package me.xiaozhi.androidclient.network

import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import me.xiaozhi.androidclient.model.ConnectParams
import me.xiaozhi.androidclient.model.ListeningMode
import me.xiaozhi.androidclient.model.ServerHello
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONException
import org.json.JSONObject

private const val CLIENT_INPUT_SAMPLE_RATE = 16000
private const val CLIENT_INPUT_CHANNELS = 1
private const val CLIENT_FRAME_DURATION_MS = 60
private const val WS_BINARY_V2_HEADER_SIZE = 16
private const val WS_BINARY_V3_HEADER_SIZE = 4

class XiaozhiRealtimeClient(private val okHttpClient: OkHttpClient) {
    sealed interface RealtimeEvent {
        data class Log(val message: String) : RealtimeEvent
        data class Connected(val hello: ServerHello) : RealtimeEvent
        data class JsonMessage(val type: String?, val rawText: String) : RealtimeEvent
        data class BinaryMessage(val payload: ByteArray, val size: Int) : RealtimeEvent
        data class Disconnected(val code: Int, val reason: String) : RealtimeEvent
        data class Error(val message: String) : RealtimeEvent
    }

    private val _events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 128)
    val events = _events.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var sessionId: String? = null
    private var handshakeDeferred: CompletableDeferred<ServerHello> = CompletableDeferred()
    private var activeProtocolVersion: Int = 1

    suspend fun connect(params: ConnectParams): Result<ServerHello> = runCatching {
        disconnect(notify = false)

        activeProtocolVersion = params.protocolVersion
        handshakeDeferred = CompletableDeferred()
        sessionId = null

        val requestBuilder = Request.Builder()
            .url(params.url)
            .header("Protocol-Version", params.protocolVersion.toString())
            .header("Device-Id", params.deviceId)
            .header("Client-Id", params.clientId)

        normalizeToken(params.token)?.let { requestBuilder.header("Authorization", it) }

        webSocket = okHttpClient.newWebSocket(
            requestBuilder.build(),
            socketListener(),
        )

        withTimeout(20_000) { handshakeDeferred.await() }
    }

    fun disconnect(notify: Boolean = true) {
        if (!handshakeDeferred.isCompleted) {
            handshakeDeferred.cancel()
        }
        webSocket?.close(1000, "client_disconnect")
        webSocket = null
        sessionId = null
        if (notify) {
            emit(RealtimeEvent.Log("已断开连接"))
        }
    }

    fun sendStartListening(mode: ListeningMode): Boolean {
        val sid = sessionId ?: return failSend("服务端会话尚未建立，无法开始录音")
        return sendJson(
            JSONObject()
                .put("session_id", sid)
                .put("type", "listen")
                .put("state", "start")
                .put("mode", mode.wireValue),
            "listen/start",
        )
    }

    fun sendStopListening(): Boolean {
        val sid = sessionId ?: return failSend("服务端会话尚未建立，无法停止录音")
        return sendJson(
            JSONObject()
                .put("session_id", sid)
                .put("type", "listen")
                .put("state", "stop"),
            "listen/stop",
        )
    }

    fun sendDetectText(text: String): Boolean {
        val sid = sessionId ?: return failSend("服务端会话尚未建立，无法发送文字")
        val prompt = text.trim()
        if (prompt.isBlank()) {
            return failSend("文字内容不能为空")
        }
        return sendJson(
            JSONObject()
                .put("session_id", sid)
                .put("type", "listen")
                .put("state", "detect")
                .put("text", prompt),
            "listen/detect",
        )
    }

    fun sendAbort(reason: String = "user_abort"): Boolean {
        val sid = sessionId ?: return failSend("服务端会话尚未建立，无法打断")
        return sendJson(
            JSONObject()
                .put("session_id", sid)
                .put("type", "abort")
                .put("reason", reason),
            "abort",
        )
    }

    fun sendMcp(payloadText: String): Boolean {
        val sid = sessionId ?: return failSend("服务端会话尚未建立，无法发送 MCP 请求")
        val payload = try {
            JSONObject(payloadText)
        } catch (_: JSONException) {
            return failSend("MCP 负载必须是 JSON 对象")
        }

        return sendJson(
            JSONObject()
                .put("session_id", sid)
                .put("type", "mcp")
                .put("payload", payload),
            "mcp",
        )
    }

    fun sendAudioFrame(opusFrame: ByteArray): Boolean {
        val socket = webSocket ?: return failSend("WebSocket 尚未连接")
        val payload = wrapAudioPayload(opusFrame)
        val success = socket.send(payload.toByteString())
        if (!success) {
            emit(RealtimeEvent.Error("发送音频帧失败"))
        }
        return success
    }

    private fun socketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (this@XiaozhiRealtimeClient.webSocket != webSocket) {
                    return
                }
                emit(RealtimeEvent.Log("WebSocket 已打开: ${response.code}"))
                sendHello()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@XiaozhiRealtimeClient.webSocket != webSocket) {
                    return
                }
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (this@XiaozhiRealtimeClient.webSocket != webSocket) {
                    return
                }
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (this@XiaozhiRealtimeClient.webSocket != webSocket) {
                    return
                }
                sessionId = null
                this@XiaozhiRealtimeClient.webSocket = null
                emit(RealtimeEvent.Log("WebSocket 正在关闭: $code $reason"))
                emit(RealtimeEvent.Disconnected(code, reason.ifBlank { "server_closing" }))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@XiaozhiRealtimeClient.webSocket != webSocket) {
                    return
                }
                sessionId = null
                this@XiaozhiRealtimeClient.webSocket = null
                emit(RealtimeEvent.Disconnected(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@XiaozhiRealtimeClient.webSocket != webSocket) {
                    return
                }
                if (!handshakeDeferred.isCompleted) {
                    handshakeDeferred.completeExceptionally(t)
                }
                sessionId = null
                this@XiaozhiRealtimeClient.webSocket = null
                val responseCode = response?.code?.toString() ?: "no-response"
                emit(RealtimeEvent.Error("WebSocket 失败: $responseCode ${t.message.orEmpty()}"))
            }
        }
    }

    private fun handleTextMessage(text: String) {
        try {
            val root = JSONObject(text)
            val type = root.optString("type").takeIf { it.isNotBlank() }
            if (type == "hello") {
                val transport = root.optString("transport")
                if (transport != "websocket") {
                    val error = IOException("不支持的传输类型: $transport")
                    if (!handshakeDeferred.isCompleted) {
                        handshakeDeferred.completeExceptionally(error)
                    }
                    emit(RealtimeEvent.Error(error.message ?: "不支持的传输类型"))
                    return
                }

                val audioParams = root.optJSONObject("audio_params")
                sessionId = root.optString("session_id").takeIf { it.isNotBlank() }
                val hello = ServerHello(
                    sessionId = sessionId,
                    sampleRate = audioParams?.optIntNullable("sample_rate"),
                    frameDuration = audioParams?.optIntNullable("frame_duration"),
                    raw = root,
                )
                if (!handshakeDeferred.isCompleted) {
                    handshakeDeferred.complete(hello)
                }
                emit(RealtimeEvent.Connected(hello))
                emit(RealtimeEvent.Log("握手完成，session_id=${sessionId ?: "<none>"}"))
                return
            }

            emit(RealtimeEvent.JsonMessage(type, text))
        } catch (error: JSONException) {
            emit(RealtimeEvent.Error("服务端返回了无效 JSON: ${error.message}"))
        }
    }

    private fun handleBinaryMessage(rawBytes: ByteArray) {
        val payload = unwrapAudioPayload(rawBytes) ?: run {
            emit(RealtimeEvent.Error("解析音频二进制包失败"))
            return
        }
        emit(RealtimeEvent.BinaryMessage(payload = payload, size = payload.size))
    }

    private fun sendHello() {
        sendJson(
            JSONObject()
                .put("type", "hello")
                .put("version", activeProtocolVersion)
                .put("features", JSONObject().put("mcp", true))
                .put("transport", "websocket")
                .put(
                    "audio_params",
                    JSONObject()
                        .put("format", "opus")
                        .put("sample_rate", CLIENT_INPUT_SAMPLE_RATE)
                        .put("channels", CLIENT_INPUT_CHANNELS)
                        .put("frame_duration", CLIENT_FRAME_DURATION_MS),
                ),
            "hello",
        )
    }

    private fun sendJson(payload: JSONObject, label: String): Boolean {
        val socket = webSocket ?: return failSend("WebSocket 尚未连接")
        val raw = payload.toString()
        val success = socket.send(raw)
        if (success) {
            emit(RealtimeEvent.Log("=> [$label] $raw"))
        } else {
            emit(RealtimeEvent.Error("发送 $label 失败"))
        }
        return success
    }

    private fun wrapAudioPayload(opusFrame: ByteArray): ByteArray {
        return when (activeProtocolVersion) {
            2 -> ByteBuffer.allocate(WS_BINARY_V2_HEADER_SIZE + opusFrame.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(2.toShort())
                .putShort(0.toShort())
                .putInt(0)
                .putInt(SystemClock.elapsedRealtime().toInt())
                .putInt(opusFrame.size)
                .put(opusFrame)
                .array()

            3 -> ByteBuffer.allocate(WS_BINARY_V3_HEADER_SIZE + opusFrame.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(0.toByte())
                .put(0.toByte())
                .putShort(opusFrame.size.toShort())
                .put(opusFrame)
                .array()

            else -> opusFrame
        }
    }

    private fun unwrapAudioPayload(rawBytes: ByteArray): ByteArray? {
        return when (activeProtocolVersion) {
            2 -> {
                if (rawBytes.size < WS_BINARY_V2_HEADER_SIZE) {
                    return null
                }
                val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN)
                val version = buffer.short.toInt() and 0xFFFF
                val type = buffer.short.toInt() and 0xFFFF
                buffer.int
                buffer.int
                val payloadSize = buffer.int
                if (version != 2 || type != 0 || payloadSize < 0 || payloadSize > rawBytes.size - WS_BINARY_V2_HEADER_SIZE) {
                    return null
                }
                rawBytes.copyOfRange(
                    WS_BINARY_V2_HEADER_SIZE,
                    WS_BINARY_V2_HEADER_SIZE + payloadSize,
                )
            }

            3 -> {
                if (rawBytes.size < WS_BINARY_V3_HEADER_SIZE) {
                    return null
                }
                val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN)
                val type = buffer.get().toInt() and 0xFF
                buffer.get()
                val payloadSize = buffer.short.toInt() and 0xFFFF
                if (type != 0 || payloadSize > rawBytes.size - WS_BINARY_V3_HEADER_SIZE) {
                    return null
                }
                rawBytes.copyOfRange(
                    WS_BINARY_V3_HEADER_SIZE,
                    WS_BINARY_V3_HEADER_SIZE + payloadSize,
                )
            }

            else -> rawBytes
        }
    }

    private fun failSend(message: String): Boolean {
        emit(RealtimeEvent.Error(message))
        return false
    }

    private fun emit(event: RealtimeEvent) {
        _events.tryEmit(event)
    }

    private fun normalizeToken(rawToken: String?): String? {
        val token = rawToken?.trim().orEmpty()
        if (token.isBlank()) {
            return null
        }
        return if (' ' in token) token else "Bearer $token"
    }

    private fun JSONObject.optIntNullable(key: String): Int? {
        return if (has(key) && !isNull(key)) optInt(key) else null
    }
}
