package me.xiaozhi.androidclient.integration

import android.util.Log
import com.ss.api.serialport.SerialPort
import java.io.File
import java.nio.charset.StandardCharsets

private const val LOG_TAG = "XiaozhiClient"
private const val NANO_BAUD_RATE = 9_600
private const val MAX_LINE_LENGTH = 160

class NanoSerialBridge(
    private val onLineReceived: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) : SerialPort.Callback {
    private val receiveBuffer = StringBuilder()
    private val writeLock = Any()

    @Volatile
    private var serialPort: SerialPort? = null

    @Volatile
    private var currentDeviceState: String = "IDLE"

    fun start() {
        if (serialPort != null) {
            return
        }

        for (path in listOf("/dev/ttyS4", "/dev/ttyS3")) {
            val port = open(path) ?: continue
            serialPort = port
            onStatus("Nano 串口已打开：$path @ $NANO_BAUD_RATE")
            sendLine("STATE,$currentDeviceState")
            return
        }
        onStatus("Nano 串口打开失败：未能访问 ttyS4/ttyS3")
    }

    fun setDeviceState(state: String) {
        if (state == currentDeviceState) {
            return
        }
        currentDeviceState = state
        sendLine("STATE,$state")
    }

    fun resendDeviceState() {
        sendLine("STATE,$currentDeviceState")
    }

    fun sendLine(line: String): Boolean {
        val port = serialPort ?: return false
        return runCatching {
            synchronized(writeLock) {
                port.sendMsg("${line.trim()}\n")
            }
            if (shouldLogLine(line)) {
                Log.d(LOG_TAG, "[NANO] => ${line.trim()}")
            }
            true
        }.getOrElse { error ->
            onStatus("Nano 串口发送失败：${error.message.orEmpty()}")
            false
        }
    }

    fun close() {
        val port = serialPort ?: return
        serialPort = null
        runCatching { port.closeSerialPort() }
        synchronized(receiveBuffer) { receiveBuffer.clear() }
    }

    override fun onDataReceived(bytes: ByteArray, size: Int) {
        if (size <= 0) {
            return
        }
        val chunk = String(bytes, 0, size, StandardCharsets.UTF_8)
        synchronized(receiveBuffer) {
            for (character in chunk) {
                when (character) {
                    '\r' -> Unit
                    '\n' -> emitBufferedLine()
                    else -> {
                        if (receiveBuffer.length < MAX_LINE_LENGTH) {
                            receiveBuffer.append(character)
                        } else {
                            receiveBuffer.clear()
                            onStatus("Nano 串口接收行过长，已丢弃")
                        }
                    }
                }
            }
        }
    }

    private fun emitBufferedLine() {
        val line = receiveBuffer.toString().trim()
        receiveBuffer.clear()
        if (line.isBlank()) {
            return
        }
        if (shouldLogLine(line)) {
            Log.d(LOG_TAG, "[NANO] <= $line")
        }
        onLineReceived(line)
    }

    private fun shouldLogLine(line: String): Boolean =
        line.startsWith("PERSON_NEAR,") ||
            line.startsWith("NANO_READY,") ||
            line.startsWith("NANO_ERROR,") ||
            line.startsWith("EVENT_")

    private fun open(path: String): SerialPort? {
        val device = File(path)
        if (!device.exists()) {
            return null
        }
        return runCatching {
            SerialPort(device, NANO_BAUD_RATE, 0).also { it.setCallback(this) }
        }.onFailure { error ->
            Log.w(LOG_TAG, "[NANO] open failed: $path", error)
        }.getOrNull()
    }
}
