package me.xiaozhi.androidclient.digitalhuman

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import me.xiaozhi.androidclient.model.DigitalHumanSlot
import me.xiaozhi.androidclient.model.RoleProfile

/**
 * 扫码上传的**目标**。
 *
 * 原来这套只服务「某一段角色形象视频」，但立绘和头像同样是"用户手机里的一个文件"——
 * 而客户那边**没有 ADB、也不方便用设备上的文件选择器**，只给「本机」入口等于这条路走不通。
 * 所以三者共用同一条扫码通道，差异（标题、接受的文件类型、导入后调哪个方法）收在这里。
 */
sealed interface UploadTarget {
    /** 显示给用户的槽位名，会出现在扫码弹窗标题和上传页上。 */
    val label: String

    /** `<input accept>` 的值。 */
    val accept: String

    /** 上传页的图标与标题。 */
    val icon: String
    val title: String

    /** 上传页的提示文案。 */
    val hint: String

    /** 页面里 `xhr.setRequestHeader('Content-Type', ...)` 用的值。 */
    val contentType: String

    /** 落到缓存目录时用的扩展名。 */
    val tempSuffix: String

    data class Video(val slot: DigitalHumanSlot) : UploadTarget {
        override val label get() = slot.label
        override val accept get() = "video/mp4,video/*"
        override val icon get() = "🎬"
        override val title get() = "上传角色形象视频"
        override val hint get() = "点击选择本地 MP4 视频 · 建议时长 0.5 ~ 15 秒，文件 ≤ 100MB"
        override val contentType get() = "video/mp4"
        override val tempSuffix get() = ".mp4"
    }

    data object Portrait : UploadTarget {
        override val label get() = "角色立绘"
        override val accept get() = "image/*"
        override val icon get() = "🖼️"
        override val title get() = "上传角色立绘"
        override val hint get() = "点击选择图片 · 建议竖图，会自动压缩到长边 2048"
        override val contentType get() = "application/octet-stream"
        override val tempSuffix get() = ".img"
    }

    data object Avatar : UploadTarget {
        override val label get() = "角色头像"
        override val accept get() = "image/*"
        override val icon get() = "🙂"
        override val title get() = "上传角色头像"
        override val hint get() = "点击选择图片 · 建议正方形，会自动压缩到长边 512"
        override val contentType get() = "application/octet-stream"
        override val tempSuffix get() = ".img"
    }
}

data class UploadSession(
    val token: String,
    val port: Int,
    val role: RoleProfile,
    val target: UploadTarget,
    val url: String,
)

/**
 * 局域网扫码上传服务：设备上开一个一次性 HTTP 口，手机扫码打开页面选文件传过来。
 *
 * 这个类**只负责收文件**，收完交给 [importFile] 去解析/校验/落盘。
 * 视频、立绘、头像的差异全部由 [UploadTarget] 描述，这里不再有"只认 mp4"的假设。
 *
 * @param importFile 把收到的临时文件导入角色，返回成功消息或失败原因。
 * @param onImported 导入成功后的回调，第一个参数是**本次会话的 token**。
 *   带 token 是必要的：旧会话的上传可能在我们已经开了新弹窗之后才完成，
 *   调用方据此判断"这个结果是不是当前这次会话的"，否则会把上一个目标的结果
 *   显示在新弹窗里（用户还没传就显示"成功"）。
 */
class LanUploadServer(
    private val context: Context,
    private val importFile: (RoleProfile, UploadTarget, File) -> Result<String>,
    private val onImported: (String, RoleProfile, UploadTarget, String) -> Unit,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    @Synchronized
    fun start(role: RoleProfile, target: UploadTarget): UploadSession {
        stop()
        val socket = ServerSocket(0)
        serverSocket = socket
        val token = UUID.randomUUID().toString().replace("-", "")
        val host = resolveLocalIp()
        val url = "http://$host:${socket.localPort}/upload?token=$token"
        executor.execute { acceptLoop(socket, role, target, token) }
        return UploadSession(
            token = token,
            port = socket.localPort,
            role = role,
            target = target,
            url = url,
        )
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket, role: RoleProfile, target: UploadTarget, token: String) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute {
                    runCatching {
                        client.use { handle(it, role, target, token) }
                    }
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handle(client: Socket, role: RoleProfile, target: UploadTarget, token: String) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val header = readHeader(input)

        if (header.startsWith("GET /favicon.ico")) {
            respond(client, 404, "Not Found", "text/plain")
            return
        }

        if (header.startsWith("GET /upload?token=$token ") || header.startsWith("GET /upload?token=$token\r") || header.startsWith("GET /upload?token=$token\n")) {
            val html = buildUploadHtml(role.displayName, target, token)
            respond(client, 200, html, "text/html; charset=utf-8")
            return
        }

        if (header.startsWith("POST /upload?token=$token ") || header.startsWith("POST /upload?token=$token\r") || header.startsWith("POST /upload?token=$token\n")) {
            val length = Regex("(?im)^Content-Length:\\s*(\\d+)").find(header)?.groupValues?.get(1)?.toLongOrNull()
                ?: run {
                    respondJson(client, 400, false, "缺少文件长度 (Content-Length)")
                    return
                }

            if (length !in 1..(100L * 1024L * 1024L)) {
                respondJson(client, 400, false, "文件大小超出限制（需在 100MB 以内）")
                return
            }

            val temporary = File(context.cacheDir, "upload-${System.nanoTime()}${target.tempSuffix}")
            try {
                temporary.outputStream().use { output ->
                    var remaining = length
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    require(remaining == 0L) { "上传内容不完整" }
                }

                val imported = importFile(role, target, temporary).getOrThrow()
                onImported(token, role, target, imported)
                respondJson(client, 200, true, imported)
            } catch (e: Exception) {
                respondJson(client, 400, false, "${target.label}导入校验失败: ${e.message ?: "未知错误"}")
            } finally {
                temporary.delete()
            }
            return
        }

        respond(client, 403, "Forbidden or Invalid Token", "text/plain")
    }

    private fun readHeader(input: BufferedInputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val byte = input.read()
            if (byte == -1) break
            out.write(byte)
            matched = when {
                matched == 0 && byte == '\r'.code -> 1
                matched == 1 && byte == '\n'.code -> 2
                matched == 2 && byte == '\r'.code -> 3
                matched == 3 && byte == '\n'.code -> 4
                byte == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
            if (out.size() > 16 * 1024) break
        }
        return out.toString("ISO-8859-1")
    }

    private fun respondJson(client: Socket, status: Int, success: Boolean, message: String) {
        respond(client, status, """{"ok":$success,"message":"${escapeJson(message)}"}""", "application/json; charset=utf-8")
    }

    private fun respond(client: Socket, status: Int, content: String, contentType: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().use { out ->
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    private fun escapeJson(str: String): String =
        str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    private fun resolveLocalIp(): String {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = Formatter.formatIpAddress(wifi.connectionInfo.ipAddress)
            if (ip.isNotBlank() && ip != "0.0.0.0") return ip
        }
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is InetAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        }
        return "127.0.0.1"
    }

    companion object {
        fun buildUploadHtml(roleName: String, target: UploadTarget, token: String): String {
            val icon = target.icon
            val title = target.title
            val slotLabel = target.label
            val accept = target.accept
            val hint = target.hint
            val contentType = target.contentType
            return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>$title - 小智</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Helvetica Neue", Arial, sans-serif;
            background-color: #f4f6f8;
            color: #1f2937;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            padding: 16px;
        }
        .card {
            background: #ffffff;
            border-radius: 20px;
            box-shadow: 0 10px 25px rgba(0, 0, 0, 0.06);
            width: 100%;
            max-width: 440px;
            padding: 28px 24px;
            text-align: center;
        }
        .icon {
            font-size: 48px;
            margin-bottom: 12px;
        }
        h1 {
            font-size: 20px;
            font-weight: 700;
            color: #111827;
            margin-bottom: 6px;
        }
        .meta-tag {
            display: inline-block;
            background: #eef2ff;
            color: #4f46e5;
            font-size: 13px;
            font-weight: 600;
            padding: 4px 12px;
            border-radius: 9999px;
            margin-bottom: 20px;
        }
        .dropzone {
            border: 2px dashed #d1d5db;
            border-radius: 14px;
            padding: 24px 16px;
            background: #fafafa;
            cursor: pointer;
            transition: border-color 0.2s, background 0.2s;
            margin-bottom: 20px;
        }
        .dropzone:active, .dropzone.dragover {
            border-color: #4f46e5;
            background: #f5f3ff;
        }
        .file-info {
            font-size: 14px;
            color: #374151;
            word-break: break-all;
        }
        .hint {
            font-size: 13px;
            color: #9ca3af;
            margin-top: 6px;
        }
        .btn {
            width: 100%;
            border: none;
            border-radius: 12px;
            padding: 14px;
            font-size: 16px;
            font-weight: 600;
            color: #ffffff;
            background: #4f46e5;
            cursor: pointer;
            transition: opacity 0.2s;
        }
        .btn:disabled {
            background: #c7d2fe;
            cursor: not-allowed;
        }
        .progress-box {
            display: none;
            margin-top: 18px;
        }
        .progress-bar-bg {
            height: 8px;
            background: #e5e7eb;
            border-radius: 9999px;
            overflow: hidden;
        }
        .progress-bar-fg {
            height: 100%;
            width: 0%;
            background: #4f46e5;
            transition: width 0.2s;
        }
        .progress-text {
            font-size: 13px;
            color: #6b7280;
            margin-top: 8px;
        }
        .msg {
            display: none;
            margin-top: 16px;
            padding: 12px;
            border-radius: 10px;
            font-size: 14px;
            line-height: 1.5;
        }
        .msg.success {
            background: #ecfdf5;
            color: #065f46;
            display: block;
        }
        .msg.error {
            background: #fef2f2;
            color: #991b1b;
            display: block;
        }
    </style>
</head>
<body>
    <div class="card">
        <div class="icon">$icon</div>
        <h1>$title</h1>
        <div class="meta-tag">角色：$roleName · 状态：$slotLabel</div>

        <input type="file" id="fileInput" accept="$accept" style="display: none;">

        <div class="dropzone" id="dropzone">
            <div id="dropPrompt">
                <p style="font-size: 15px; font-weight: 600; color: #374151;">$hint</p>
            </div>
            <div id="fileInfo" class="file-info" style="display: none;"></div>
        </div>

        <button id="uploadBtn" class="btn" disabled>确认上传至设备</button>

        <div class="progress-box" id="progressBox">
            <div class="progress-bar-bg">
                <div class="progress-bar-fg" id="progressBar"></div>
            </div>
            <div class="progress-text" id="progressText">准备上传...</div>
        </div>

        <div id="statusMsg" class="msg"></div>
    </div>

    <script>
        const dropzone = document.getElementById('dropzone');
        const fileInput = document.getElementById('fileInput');
        const dropPrompt = document.getElementById('dropPrompt');
        const fileInfo = document.getElementById('fileInfo');
        const uploadBtn = document.getElementById('uploadBtn');
        const progressBox = document.getElementById('progressBox');
        const progressBar = document.getElementById('progressBar');
        const progressText = document.getElementById('progressText');
        const statusMsg = document.getElementById('statusMsg');

        let selectedFile = null;

        // 统一的提示出口。
        //
        // 必须**显式写内联 display**：`.msg` 基础类带 `display: none`，而下面把它藏起来时
        // 用的是 `style.display = 'none'`（**内联样式**），内联优先级高于
        // `.msg.error { display: block }` —— 只改 className 的话失败提示永远不会显示。
        // 实测踩过：选错文件后手机上什么都不发生，用户以为卡死。
        function showStatus(kind, text) {
            statusMsg.className = 'msg ' + kind;
            statusMsg.style.display = 'block';
            statusMsg.textContent = text;
        }

        // 失败后必须把界面**恢复成可以重选**。原来只在开始时禁用、失败分支没恢复 fileInput，
        // 于是选错一次就只能刷新网页才能重来。
        function resetForRetry() {
            uploadBtn.disabled = false;
            fileInput.disabled = false;
            dropzone.style.pointerEvents = 'auto';
        }

        dropzone.addEventListener('click', () => fileInput.click());

        fileInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files.length > 0) {
                selectedFile = e.target.files[0];
                dropPrompt.style.display = 'none';
                fileInfo.style.display = 'block';
                const sizeMb = (selectedFile.size / (1024 * 1024)).toFixed(2);
                fileInfo.innerHTML = '已选择：<strong>' + escapeHtml(selectedFile.name) + '</strong> (' + sizeMb + ' MB)';
                uploadBtn.disabled = false;
                statusMsg.style.display = 'none';
            }
        });

        uploadBtn.addEventListener('click', () => {
            if (!selectedFile) return;

            uploadBtn.disabled = true;
            fileInput.disabled = true;
            dropzone.style.pointerEvents = 'none';
            progressBox.style.display = 'block';
            statusMsg.style.display = 'none';

            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/upload?token=$token', true);
            xhr.setRequestHeader('Content-Type', '$contentType');

            xhr.upload.onprogress = (e) => {
                if (e.lengthComputable) {
                    const percent = Math.round((e.loaded / e.total) * 100);
                    progressBar.style.width = percent + '%';
                    progressText.textContent = '上传中 ' + percent + '% (' + (e.loaded / 1048576).toFixed(1) + 'MB / ' + (e.total / 1048576).toFixed(1) + 'MB)';
                }
            };

            xhr.onload = () => {
                let resp = null;
                try {
                    resp = JSON.parse(xhr.responseText);
                } catch (err) {}

                if (xhr.status === 200 && resp && resp.ok) {
                    progressBar.style.width = '100%';
                    progressText.textContent = '校验并导入成功！';
                    showStatus('success', '🎉 ' + (resp.message || '导入成功！小智设备已同步更新。'));
                    uploadBtn.style.display = 'none';
                    dropzone.style.display = 'none';
                } else {
                    const errMsg = (resp && resp.message) ? resp.message : ('上传失败 (HTTP ' + xhr.status + ')');
                    showStatus('error', '❌ ' + errMsg);
                    progressBox.style.display = 'none';
                    resetForRetry();
                }
            };

            xhr.onerror = () => {
                showStatus('error', '❌ 网络连接错误，请检查是否与小智处于同一 WiFi。');
                progressBox.style.display = 'none';
                resetForRetry();
            };

            xhr.send(selectedFile);
        });

        function escapeHtml(str) {
            return str.replace(/[&<>'"]/g, tag => ({
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                "'": '&#39;',
                '"': '&quot;'
            }[tag] || tag));
        }
    </script>
</body>
</html>
            """.trimIndent()
        }
    }
}
