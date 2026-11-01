package me.xiaozhi.androidclient.integration

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val LOG_TAG = "XiaozhiClient"

/** J35 pin 7 / IO6：Nano D2 开漏回传，有人靠近时被拉低。 */
private const val EVENT_GPIO = 92

/** J35 pin 8 / IO7：驱动 Nano D9，高电平脉宽表示设备状态。 */
private const val COMMAND_GPIO = 93

private const val SU_TIMEOUT_SECONDS = 5L
private const val POLL_INTERVAL_MS = 50L

/** 连续 3 次（约 150ms）读到同一电平才算变化，滤掉毛刺。 */
private const val STABLE_READS_NEEDED = 3

/** 两个脉冲之间至少留出的低电平，给 Nano 的边沿中断喘口气。 */
private const val PULSE_GAP_MS = 150L

/** Nano 断电重启后会回到 IDLE；定期重发当前状态把两边对齐。 */
private const val STATE_RESEND_INTERVAL_MS = 30_000L

/**
 * 与 `nano_pet_gpio.ino` 一致的脉宽表（容差 ±50ms）。
 * 改这里时必须同步改 Nano 固件。
 */
internal val NANO_STATE_PULSE_MS = mapOf(
    "IDLE" to 100L,
    "LISTENING" to 250L,
    "PROCESSING" to 400L,
    "SPEAKING" to 550L,
)

/**
 * 安卓 ↔ Nano 的两根 GPIO 线，取代早期的 `NanoSerialBridge`（RS232 串口）。
 *
 * - 下行：IO7 发单个高电平脉冲，脉宽编码状态；只发最新状态，中间态被合并。
 * - 上行：轮询 IO6，电平去抖后通过 [onEventLineChanged] 回调（`true` = 被拉低 = 有人）。
 *
 * sysfs 节点默认只有 root 可写，这里用 `su 0` 导出并 chmod 666（厂家 SDK 调的 `fsu` 在本机固件上不存在），
 * 之后由 App 进程直接读写文件。
 */
class NanoGpioBridge(
    private val onEventLineChanged: (low: Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val desiredState = AtomicReference("IDLE")
    private val pulseSignal = Object()

    @Volatile
    private var running = false
    private var commandValue: RandomAccessFile? = null
    private var eventValue: RandomAccessFile? = null

    fun start() {
        if (running) {
            return
        }
        running = true
        thread(name = "nano-gpio", isDaemon = true) {
            if (!openGpio()) {
                running = false
                return@thread
            }
            onStatus("Nano GPIO 已就绪：IO6=gpio$EVENT_GPIO 输入，IO7=gpio$COMMAND_GPIO 输出")
            thread(name = "nano-gpio-pulse", isDaemon = true) { pulseLoop() }
            pollLoop()
        }
    }

    fun setDeviceState(state: String) {
        if (state !in NANO_STATE_PULSE_MS) {
            return
        }
        desiredState.set(state)
        synchronized(pulseSignal) { pulseSignal.notifyAll() }
    }

    fun close() {
        running = false
        synchronized(pulseSignal) { pulseSignal.notifyAll() }
    }

    private fun openGpio(): Boolean {
        val script = buildString {
            for (gpio in listOf(EVENT_GPIO, COMMAND_GPIO)) {
                append("[ -d /sys/class/gpio/gpio$gpio ] || echo $gpio > /sys/class/gpio/export\n")
            }
            append("echo in > /sys/class/gpio/gpio$EVENT_GPIO/direction\n")
            // low 同时设置方向和初值，避免切成输出的瞬间冒出一个高脉冲
            append("echo low > /sys/class/gpio/gpio$COMMAND_GPIO/direction\n")
            append("chmod 666 /sys/class/gpio/gpio$EVENT_GPIO/value /sys/class/gpio/gpio$COMMAND_GPIO/value\n")
        }
        runCatching {
            val process = ProcessBuilder("su", "0", "sh", "-c", script).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0 || output.isNotEmpty()) {
                Log.w(LOG_TAG, "[NANO-GPIO] su prepare: $output")
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "[NANO-GPIO] su prepare failed", error)
        }
        return runCatching {
            eventValue = RandomAccessFile(File("/sys/class/gpio/gpio$EVENT_GPIO/value"), "r")
            commandValue = RandomAccessFile(File("/sys/class/gpio/gpio$COMMAND_GPIO/value"), "rw")
            writeCommand(false)
            true
        }.getOrElse { error ->
            onStatus("Nano GPIO 打开失败：${error.message.orEmpty()}（需 su 权限导出 gpio$EVENT_GPIO/$COMMAND_GPIO）")
            false
        }
    }

    private fun pollLoop() {
        var reportedLow: Boolean? = null
        var candidateLow: Boolean? = null
        var stableReads = 0
        while (running) {
            val low = readEventLow()
            if (low != null) {
                if (low == candidateLow) {
                    stableReads++
                } else {
                    candidateLow = low
                    stableReads = 1
                }
                if (stableReads >= STABLE_READS_NEEDED && low != reportedLow) {
                    reportedLow = low
                    Log.d(LOG_TAG, "[NANO-GPIO] IO6 ${if (low) "LOW（有人）" else "HIGH（释放）"}")
                    onEventLineChanged(low)
                }
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        runCatching { eventValue?.close() }
    }

    private fun pulseLoop() {
        var sentState: String? = null
        var lastSentAt = 0L
        while (running) {
            val state = desiredState.get()
            val now = SystemClock.elapsedRealtime()
            if (state != sentState || now - lastSentAt >= STATE_RESEND_INTERVAL_MS) {
                val widthMs = NANO_STATE_PULSE_MS.getValue(state)
                if (sendPulse(widthMs)) {
                    if (state != sentState) {
                        Log.d(LOG_TAG, "[NANO-GPIO] => $state (${widthMs}ms)")
                    }
                    sentState = state
                    lastSentAt = SystemClock.elapsedRealtime()
                }
                SystemClock.sleep(PULSE_GAP_MS)
                continue
            }
            synchronized(pulseSignal) {
                if (running && desiredState.get() == sentState) {
                    pulseSignal.wait(STATE_RESEND_INTERVAL_MS)
                }
            }
        }
        runCatching { writeCommand(false) }
        runCatching { commandValue?.close() }
    }

    private fun sendPulse(widthMs: Long): Boolean = runCatching {
        writeCommand(true)
        SystemClock.sleep(widthMs)
        writeCommand(false)
        true
    }.getOrElse { error ->
        // 保证线最终回到低电平，否则 Nano 会一直等下降沿
        runCatching { writeCommand(false) }
        Log.w(LOG_TAG, "[NANO-GPIO] pulse failed", error)
        false
    }

    private fun writeCommand(high: Boolean) {
        val file = checkNotNull(commandValue)
        file.seek(0)
        file.write(if (high) '1'.code else '0'.code)
    }

    private fun readEventLow(): Boolean? = runCatching {
        val file = checkNotNull(eventValue)
        file.seek(0)
        when (file.read()) {
            '0'.code -> true
            '1'.code -> false
            else -> null
        }
    }.getOrNull()
}
