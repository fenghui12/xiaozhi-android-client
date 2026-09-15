package me.xiaozhi.androidclient.debug

import android.util.Log
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开发期调试接口，让 agent 可以自己驱动真机测试，不必每次请人点屏幕。
 *
 * 只在 debug 构建里启动，只绑定 127.0.0.1，从开发机通过
 * `adb forward tcp:8898 tcp:8898` 访问，不会暴露到局域网。
 *
 *   GET  /state                当前 UiState 摘要（JSON）
 *   GET  /log?since=N          第 N 条之后的日志
 *   POST /send   text=...      等价于用户在输入框打字并发送
 *   POST /action name=...      等价于点屏幕：listen / stop / abort / settings / chat / clear_media
 *
 * 设计约束：/send 必须走和真人输入完全一样的路径（MainViewModel.sendDraftMessage），
 * 不允许为了测试方便绕过任何门控，否则测出来的结论不算数。
 */
class DebugControlServer(
    private val stateJson: () -> String,
    private val logsJson: (Int) -> String,
    private val onSendText: (String) -> String,
    private val onAction: (String) -> String,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Synchronized
    fun start(port: Int = DEFAULT_PORT) {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({
            try {
                val socket = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                Log.i(TAG, "debug control server listening on 127.0.0.1:$port")
                while (running.get() && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread({ handle(client) }, "debug-http").start()
                }
            } catch (error: Exception) {
                Log.e(TAG, "debug control server stopped", error)
            }
        }, "debug-control").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val (path, query) = parts[1].split("?", limit = 2).let {
                it[0] to (it.getOrNull(1).orEmpty())
            }

            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val chunk = input.read(buffer, read, contentLength - read)
                    if (chunk < 0) break
                    read += chunk
                }
                String(buffer, 0, read)
            } else {
                ""
            }

            val params = parseParams(query) + parseParams(body)
            val (status, payload) = runCatching {
                when {
                    method == "GET" && path == "/state" -> 200 to stateJson()
                    method == "GET" && path == "/log" -> 200 to logsJson(
                        params["since"]?.toIntOrNull() ?: 0,
                    )
                    method == "POST" && path == "/send" -> {
                        val text = params["text"].orEmpty()
                        if (text.isBlank()) 400 to errorJson("text 不能为空")
                        else 200 to onSendText(text)
                    }
                    method == "POST" && path == "/action" -> {
                        val name = params["name"].orEmpty()
                        if (name.isBlank()) 400 to errorJson("name 不能为空")
                        else 200 to onAction(name)
                    }
                    else -> 404 to errorJson("未知接口 $method $path")
                }
            }.getOrElse { error -> 500 to errorJson(error.message.orEmpty()) }

            respond(socket, status, payload)
        }
    }

    private fun respond(socket: Socket, status: Int, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${if (status == 200) "OK" else "ERROR"}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        BufferedOutputStream(socket.getOutputStream()).use { out ->
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private fun parseParams(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = URLDecoder.decode(pair.substringBefore("="), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
            key to value
        }.toMap()
    }

    private fun errorJson(message: String) =
        """{"ok":false,"error":${quote(message)}}"""

    companion object {
        private const val TAG = "XiaozhiDebug"
        private const val DEFAULT_PORT = 8898
        private const val SOCKET_TIMEOUT_MS = 15_000

        fun quote(value: String): String {
            val builder = StringBuilder("\"")
            value.forEach { char ->
                when (char) {
                    '"' -> builder.append("\\\"")
                    '\\' -> builder.append("\\\\")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '\t' -> builder.append("\\t")
                    else -> if (char < ' ') builder.append("\\u%04x".format(char.code)) else builder.append(char)
                }
            }
            return builder.append("\"").toString()
        }
    }
}
