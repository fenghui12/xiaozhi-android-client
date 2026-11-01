package me.xiaozhi.androidclient.integration

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val LOG_TAG = "XiaozhiClient"
private const val DMC_DEVFREQ = "/sys/class/devfreq/dmc"
private const val SU_TIMEOUT_SECONDS = 5L

/**
 * 把 RK3568 的 DDR 频率钉在最高档。
 *
 * `dmc_ondemand` 默认每秒在 324/528/780/1560MHz 之间切 4～5 次，每次切换都会打断
 * 全速 USB 声卡（OHCI）的等时传输，表现为「说着说着没声」或整段静音。
 * 钉住 min_freq 后 A/B/A 实测问题消失；本机常插电，功耗和温升可忽略（满载约 44°C）。
 *
 * 该设置重启即失效，所以每次 App 启动时重写一次。没有 su 或没有 dmc 节点的机器静默跳过。
 */
object DdrFrequencyLock {
    fun apply(onStatus: (String) -> Unit) {
        thread(name = "ddr-freq-lock", isDaemon = true) {
            val script = """
                d=$DMC_DEVFREQ
                [ -e ${'$'}d/min_freq ] || { echo missing; exit 0; }
                cat ${'$'}d/max_freq > ${'$'}d/min_freq
                echo "${'$'}(cat ${'$'}d/min_freq) ${'$'}(cat ${'$'}d/cur_freq)"
            """.trimIndent()
            val output = runCatching {
                val process = ProcessBuilder("su", "0", "sh", "-c", script).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText().trim()
                if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroy()
                }
                text
            }.getOrElse { error ->
                Log.w(LOG_TAG, "[DDR] lock failed", error)
                onStatus("DDR 频率锁定跳过：无法获取 root（${error.message.orEmpty()}）")
                return@thread
            }
            val parts = output.split(' ')
            when {
                output == "missing" -> Log.i(LOG_TAG, "[DDR] no dmc devfreq node, skip")
                parts.size == 2 && parts.all { it.toLongOrNull() != null } && parts[0] == parts[1] ->
                    onStatus("DDR 频率已锁定 ${parts[0].toLong() / 1_000_000}MHz（防 USB 声卡断音）")
                else -> onStatus("DDR 频率锁定未生效：$output")
            }
        }
    }
}
