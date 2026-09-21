package me.xiaozhi.androidclient.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 挑一个"真的能拾音"的输入设备。
 *
 * ## 为什么需要它（2026-09-21 真机事故）
 *
 * 这台 RK3568 工程样机有两个输入设备：
 *  - `card 2` USB 摄像头麦（DECXIN）—— **唯一真正能拾音的**；
 *  - `card 1` 板载 codec 的采集通路 —— **板上没有焊咪头**，ADC 采到的是平坦噪声
 *    （实测 40 秒录音 RMS 恒定 7.4~7.6，毫无起伏）。
 *
 * 原本的写法是"启动时 `getDevices()` 查一次，选完就再也不管"。而 **USB 音频设备常常
 * 在 App 启动之后才被系统枚举出来** —— 实测抓到同一个进程里：
 *
 * ```
 * 16:11:57  [KWS] preferred input device=SSRK3568-HMI.6.1.75 type=15   ← 板载麦，聋的
 * 16:13:08  [KWS] preferred input device=USB-Audio - DECXIN  type=11   ← 一分钟后 USB 才出现
 * ```
 *
 * 于是设备绑死在板载麦上：**唤醒不了、接话也没反应，而界面还写着「正在监听唤醒词」**，
 * 用户完全不知道发生了什么，只能重启。这一版又把底栏的手动麦克风键去掉了，
 * 连"手动点一下"的兜底也没有了，所以必须在这里根治。
 *
 * ## 做法
 *
 * 找不到 USB/有线麦时**等一小会儿**（USB 枚举通常几百毫秒到几秒），仍然没有才退到板载麦，
 * 并且**大声记一条警告**——下次再出这种事，日志里第一眼就能看到，不用再从头查。
 */
object InputDeviceSelector {

    private const val TAG = "XiaozhiClient"

    /** 找不到 USB/有线麦时，最多等它出现这么久。 */
    private const val USB_WAIT_MS = 2_000L

    /** 等待时的轮询间隔。 */
    private const val POLL_STEP_MS = 200L

    fun isUsbInput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> false
    }

    fun isWiredInput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> false
    }

    fun inputsOf(manager: AudioManager): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        } else {
            emptyList()
        }

    /**
     * 选输入设备，**会为 USB 麦短暂等待**。只能在后台线程调用。
     *
     * @param preferBuiltin 设备端 AEC 要求采集与播放落在同一张声卡上，那种模式下必须钉板载麦
     *   （本机 `ECHO_CANCEL_MODE` 是 NONE，所以走的是 false 这条路）。
     * @param waitMs 等待 USB/有线麦出现的上限。**只是想"看一眼当前路由是什么"时传 0**，
     *   否则光是刷新一下状态栏文案就会卡两秒。
     */
    fun select(
        manager: AudioManager,
        preferBuiltin: Boolean,
        waitMs: Long = USB_WAIT_MS,
    ): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val devices = inputsOf(manager)
        if (devices.isEmpty()) return null
        val builtin = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        if (preferBuiltin) {
            return builtin ?: devices.firstOrNull(::isUsbInput) ?: devices.first()
        }

        val immediate = devices.firstOrNull(::isUsbInput) ?: devices.firstOrNull(::isWiredInput)
        if (immediate != null) return immediate

        // 走到这里说明"这一刻"没有 USB/有线麦。它很可能只是还没枚举完 —— 等一会儿再看。
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < waitMs) {
            SystemClock.sleep(POLL_STEP_MS)
            val again = inputsOf(manager)
            val found = again.firstOrNull(::isUsbInput) ?: again.firstOrNull(::isWiredInput)
            if (found != null) {
                Log.d(
                    TAG,
                    "[CAPTURE] 等待 ${SystemClock.elapsedRealtime() - startedAt}ms 后等到 USB/有线麦克风：" +
                        "${found.productName} type=${found.type}",
                )
                return found
            }
        }

        Log.w(
            TAG,
            "[CAPTURE] 没有 USB/有线麦克风，只能退回板载麦（${builtin?.productName}）。" +
                "⚠ 本机板载采集通路没有焊咪头，若确实退到了它，设备会完全听不见且界面不会有任何提示 —— " +
                "请检查 USB 摄像头是否插好。",
        )
        return builtin
            ?: devices.firstOrNull(::isUsbInput)
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: devices.first()
    }
}
