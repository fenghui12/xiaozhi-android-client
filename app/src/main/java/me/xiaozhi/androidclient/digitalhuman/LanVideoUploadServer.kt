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

data class VideoUploadSession(
    val token: String,
    val port: Int,
    val role: RoleProfile,
    val slot: DigitalHumanSlot,
    val url: String,
)

class LanVideoUploadServer(
    private val context: Context,
    private val assetManager: DigitalHumanAssetManager,
    private val onImported: (RoleProfile, DigitalHumanSlot, String) -> Unit,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    @Synchronized
    fun start(role: RoleProfile, slot: DigitalHumanSlot): VideoUploadSession {
        stop()
        val socket = ServerSocket(0)
        serverSocket = socket
        val token = UUID.randomUUID().toString().replace("-", "")
        val host = resolveLocalIp()
        val url = "http://$host:${socket.localPort}/upload?token=$token"
        executor.execute { acceptLoop(socket, role, slot, token) }
        return VideoUploadSession(
            token = token,
            port = socket.localPort,
            role = role,
            slot = slot,
            url = url,
        )
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket, role: RoleProfile, slot: DigitalHumanSlot, token: String) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute {
                    runCatching {
                        client.use { handle(it, role, slot, token) }
                    }
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handle(client: Socket, role: RoleProfile, slot: DigitalHumanSlot, token: String) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val header = readHeader(input)

        if (header.startsWith("GET /favicon.ico")) {
            respond(client, 404, "Not Found", "text/plain")
            return
        }

        if (header.startsWith("GET /upload?token=$token ") || header.startsWith("GET /upload?token=$token\r") || header.startsWith("GET /upload?token=$token\n")) {
            val html = buildUploadHtml(role.displayName, slot.label, token)
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

            val temporary = File(context.cacheDir, "video-upload-${System.nanoTime()}.mp4")
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

                val imported = assetManager.importVideoFile(role, slot, temporary).getOrThrow()
                onImported(role, slot, imported)
                respondJson(client, 200, true, "上传并导入成功！已同步至小智设备。")
            } catch (e: Exception) {
                respondJson(client, 400, false, "视频导入校验失败: ${e.message ?: "未知错误"}")
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
            when {
                matched == 0 && byte == '\r'.code -> matched = 1
                matched == 1 && byte == '\n'.code -> matched = 2
                matched == 2 && byte == '\r'.code -> matched = 3
                matched == 3 && byte == '\n'.code -> break
                byte == '\n'.code -> break
                else -> matched = 0
            }
            if (out.size() > 8192) break
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun respondJson(client: Socket, status: Int, success: Boolean, message: String) {
        val json = """{"ok":$success,"message":"${escapeJson(message)}"}"""
        respond(client, status, json, "application/json; charset=utf-8")
    }

    private fun respond(client: Socket, status: Int, content: String, contentType: String) {
        val body = content.toByteArray(Charsets.UTF_8)
        val statusMsg = if (status == 200) "OK" else if (status == 404) "Not Found" else if (status == 403) "Forbidden" else "Bad Request"
        runCatching {
            val output = client.getOutputStream()
            output.write("HTTP/1.1 $status $statusMsg\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body)
            output.flush()
        }
    }

    private fun escapeJson(str: String): String =
        str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun resolveLocalIp(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (ipInt != 0) {
            return Formatter.formatIpAddress(ipInt)
        }
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        for (item in interfaces.toList()) {
            if (!item.isUp || item.isLoopback) continue
            for (addr in item.inetAddresses.toList()) {
                val host = addr.hostAddress
                if (!addr.isLoopbackAddress && addr is InetAddress && host != null && !host.contains(':')) {
                    return host
                }
            }
        }
        return "127.0.0.1"
    }

    companion object {
        fun buildUploadHtml(roleName: String, slotLabel: String, token: String): String {
            return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>导入数字人视频 - 小智</title>
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
            margin-top: 8px;
            font-weight: 500;
            word-break: break-all;
        }
        .hint {
            font-size: 12px;
            color: #6b7280;
            line-height: 1.5;
            margin-top: 6px;
        }
        .btn {
            display: block;
            width: 100%;
            background: #4f46e5;
            color: #ffffff;
            font-size: 16px;
            font-weight: 600;
            padding: 13px;
            border-radius: 12px;
            border: none;
            cursor: pointer;
            transition: background 0.2s, opacity 0.2s;
        }
        .btn:disabled {
            background: #9ca3af;
            cursor: not-allowed;
            opacity: 0.7;
        }
        .progress-box {
            display: none;
            margin-top: 18px;
        }
        .progress-bar-bg {
            background: #e5e7eb;
            height: 8px;
            border-radius: 9999px;
            overflow: hidden;
            margin-bottom: 6px;
        }
        .progress-bar-fg {
            background: #4f46e5;
            height: 100%;
            width: 0%;
            transition: width 0.15s ease-out;
        }
        .progress-text {
            font-size: 12px;
            color: #4b5563;
        }
        .msg {
            margin-top: 16px;
            font-size: 14px;
            font-weight: 500;
            display: none;
            padding: 10px;
            border-radius: 10px;
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
        <div class="icon">🎬</div>
        <h1>上传数字人视频</h1>
        <div class="meta-tag">角色：$roleName · 状态：$slotLabel</div>

        <input type="file" id="fileInput" accept="video/mp4,video/*" style="display: none;">

        <div class="dropzone" id="dropzone">
            <div id="dropPrompt">
                <p style="font-size: 15px; font-weight: 600; color: #374151;">点击选择本地 MP4 视频</p>
                <p class="hint">建议时长 0.5 ~ 15 秒，文件 ≤ 100MB</p>
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

        dropzone.addEventListener('click', () => fileInput.click());

        fileInput.addEventListener('change', (e) => {
            if (e.target.files && e.target.files.length > 0) {
                selectedFile = e.target.files[0];
                dropPrompt.style.display = 'none';
                fileInfo.style.display = 'block';
                const sizeMb = (selectedFile.size / (1024 * 1024)).toFixed(2);
                fileInfo.innerHTML = '已选择：<strong>' + escapeHtml(selectedFile.name) + '</strong> (' + sizeMb + ' MB)';
                uploadBtn.disabled = false;
                statusMsg.className = 'msg';
                statusMsg.style.display = 'none';
            }
        });

        uploadBtn.addEventListener('click', () => {
            if (!selectedFile) return;

            uploadBtn.disabled = true;
            fileInput.disabled = true;
            dropzone.style.pointerEvents = 'none';
            progressBox.style.display = 'block';
            statusMsg.className = 'msg';
            statusMsg.style.display = 'none';

            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/upload?token=$token', true);
            xhr.setRequestHeader('Content-Type', 'video/mp4');

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
                    statusMsg.className = 'msg success';
                    statusMsg.textContent = '🎉 ' + (resp.message || '导入成功！小智设备已同步更新。');
                    uploadBtn.style.display = 'none';
                    dropzone.style.display = 'none';
                } else {
                    const errMsg = (resp && resp.message) ? resp.message : ('上传失败 (HTTP ' + xhr.status + ')');
                    statusMsg.className = 'msg error';
                    statusMsg.textContent = '❌ ' + errMsg;
                    uploadBtn.disabled = false;
                    dropzone.style.pointerEvents = 'auto';
                }
            };

            xhr.onerror = () => {
                statusMsg.className = 'msg error';
                statusMsg.textContent = '❌ 网络连接错误，请检查是否与小智处于同一 WiFi。';
                uploadBtn.disabled = false;
                dropzone.style.pointerEvents = 'auto';
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
