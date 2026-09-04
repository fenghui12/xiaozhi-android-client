package me.xiaozhi.androidclient.digitalhuman

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import me.xiaozhi.androidclient.model.DigitalHumanSlot
import me.xiaozhi.androidclient.model.RoleProfile

data class VideoUploadSession(
    val url: String,
    val token: String,
    val roleId: String,
    val slot: DigitalHumanSlot,
)

/** Minimal, short-lived LAN upload endpoint. It is intentionally only active while the user imports one file. */
class LanVideoUploadServer(
    private val context: Context,
    private val assetManager: DigitalHumanAssetManager,
    private val onImported: (RoleProfile, DigitalHumanSlot, String) -> Unit,
) {
    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var session: VideoUploadSession? = null

    @Synchronized
    fun start(role: RoleProfile, slot: DigitalHumanSlot): VideoUploadSession {
        stop()
        val socket = ServerSocket(0, 1)
        serverSocket = socket
        val token = ByteArray(18).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val host = localIpv4() ?: error("未找到局域网地址")
        val next = VideoUploadSession("http://$host:${socket.localPort}/upload?token=$token", token, role.id, slot)
        session = next
        executor.execute { acceptLoop(socket, role, slot, token) }
        return next
    }

    @Synchronized
    fun stop() {
        session = null
        serverSocket?.close()
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket, role: RoleProfile, slot: DigitalHumanSlot, token: String) {
        runCatching { socket.accept().use { handle(it, role, slot, token) } }
        stop()
    }

    private fun handle(client: Socket, role: RoleProfile, slot: DigitalHumanSlot, token: String) {
        val input = BufferedInputStream(client.getInputStream())
        val header = readHeaders(input)
        if (header.startsWith("GET /upload?token=$token ")) {
            respond(client, 200, "选择视频后点击上传。")
            return
        }
        if (!header.startsWith("POST /upload?token=$token ")) {
            respond(client, 403, "令牌无效或上传地址已过期")
            return
        }
        val length = Regex("(?im)^Content-Length:\\s*(\\d+)").find(header)?.groupValues?.get(1)?.toLongOrNull()
            ?: run { respond(client, 400, "缺少文件长度"); return }
        require(length in 1..(100L * 1024L * 1024L)) { "文件过大" }
        val temporary = File(context.cacheDir, "video-upload-${System.nanoTime()}.mp4")
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
        temporary.delete()
        onImported(role, slot, imported)
        respond(client, 200, "导入成功，可以返回 APP")
    }

    private fun readHeaders(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 16_384) {
            val value = input.read()
            if (value < 0) break
            bytes += value.toByte()
            if (bytes.takeLast(4).toByteArray().contentEquals(byteArrayOf(13, 10, 13, 10))) break
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun respond(client: Socket, status: Int, message: String) {
        val body = "<html><meta charset=\"utf-8\"><body>$message</body></html>".toByteArray()
        client.getOutputStream().use { output ->
            output.write("HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(body)
        }
    }

    private fun localIpv4(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
        ?.hostAddress
}
