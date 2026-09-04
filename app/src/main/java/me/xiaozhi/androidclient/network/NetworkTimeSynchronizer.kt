package me.xiaozhi.androidclient.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MIN_VALID_UNIX_TIME_MS = 1_735_689_600_000L // 2025-01-01 UTC
private const val NTP_PACKET_SIZE = 48
private const val NTP_UNIX_EPOCH_OFFSET_SECONDS = 2_208_988_800L
private const val SOCKET_TIMEOUT_MS = 2_500
private const val RETRY_DELAY_MS = 3_000L

class NetworkTimeSynchronizer {
    private val ntpServers = listOf(
        "ntp.aliyun.com",
        "ntp.tencent.com",
        "cn.pool.ntp.org",
        "time.cloudflare.com",
    )

    suspend fun awaitValidSystemTime(log: (String) -> Unit) {
        if (isSystemTimeValid()) {
            return
        }

        log("系统时间异常，正在通过网络自动校时")
        var attempt = 0
        while (!isSystemTimeValid()) {
            attempt += 1
            val networkTime = queryNetworkTime(attempt)
            if (networkTime != null && setSystemTime(networkTime.epochMillis)) {
                log("网络校时完成：${networkTime.source}")
                return
            }

            if (attempt == 1 || attempt % ntpServers.size == 0) {
                log("网络时间暂不可用，等待 WiFi 后重试")
            }
            delay(RETRY_DELAY_MS)
        }
        log("系统网络时间已同步")
    }

    private suspend fun queryNetworkTime(attempt: Int): NetworkTime? = withContext(Dispatchers.IO) {
        val server = ntpServers[(attempt - 1) % ntpServers.size]
        queryNtp(server)?.let { return@withContext NetworkTime(it, "NTP $server") }

        if (attempt % ntpServers.size == 0) {
            queryHttpDate()?.let { return@withContext NetworkTime(it, "HTTP Date") }
        }
        null
    }

    private fun queryNtp(server: String): Long? = runCatching {
        val request = ByteArray(NTP_PACKET_SIZE).apply {
            this[0] = 0x1B
        }
        DatagramSocket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val address = InetAddress.getByName(server)
            socket.send(DatagramPacket(request, request.size, address, 123))

            val response = ByteArray(NTP_PACKET_SIZE)
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            require(packet.length >= NTP_PACKET_SIZE)

            val leapIndicator = (response[0].toInt() ushr 6) and 0x03
            val mode = response[0].toInt() and 0x07
            val stratum = response[1].toInt() and 0xFF
            require(leapIndicator != 3 && mode in 4..5 && stratum in 1..15)

            val seconds = readUnsignedInt(response, 40)
            val fraction = readUnsignedInt(response, 44)
            val unixMillis = (seconds - NTP_UNIX_EPOCH_OFFSET_SECONDS) * 1_000L +
                (fraction * 1_000L ushr 32)
            require(unixMillis >= MIN_VALID_UNIX_TIME_MS)
            unixMillis
        }
    }.getOrNull()

    private fun queryHttpDate(): Long? = runCatching {
        val connection = (URL("http://www.baidu.com/").openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = SOCKET_TIMEOUT_MS
            readTimeout = SOCKET_TIMEOUT_MS
            useCaches = false
        }
        try {
            connection.connect()
            connection.date.takeIf { it >= MIN_VALID_UNIX_TIME_MS }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun setSystemTime(epochMillis: Long): Boolean = runCatching {
        val process = ProcessBuilder(
            "su",
            "0",
            "date",
            "@${epochMillis / 1_000L}",
        )
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(4, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.inputStream.bufferedReader().use { it.readText() }
        process.exitValue() == 0 && isSystemTimeValid()
    }.getOrDefault(false)

    private fun isSystemTimeValid(): Boolean = System.currentTimeMillis() >= MIN_VALID_UNIX_TIME_MS

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private data class NetworkTime(
        val epochMillis: Long,
        val source: String,
    )
}
