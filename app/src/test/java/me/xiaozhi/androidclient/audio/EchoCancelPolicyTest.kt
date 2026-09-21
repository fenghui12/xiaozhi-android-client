package me.xiaozhi.androidclient.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回声消除策略的**行为级**守卫。
 *
 * 这不是一条"源码里有这行字"的静态检查——它真的加载 [ECHO_CANCEL_MODE] 这些常量、
 * 真的求值，所以有人把档位改到危险值时会立刻红。
 *
 * ## 为什么这条测试值得单独存在
 *
 * 2026-09-15 实测：在 hello 里声明 `features.aec = true`、请求服务端帮忙消回声之后，
 * 官方云 `api.tenclass.net` **并不支持**——设备把自己刚播报的话原样听回去、当成用户提问：
 *
 * ```
 * 22:21:05  助手说："好啦好啦，是我啦！"
 * 22:21:11  <= stt: "好啦好啦，是我啦。"   ← 自己的播报被当成用户输入回传
 * ```
 *
 * 然后助手再说一遍，再被听回去，**无限复读**。
 *
 * 所以「播报期间继续上传麦克风音频」是**本项目最危险的一个开关**：它一旦被打开，
 * 设备会陷入自激死循环，只能靠人把设备重启。这几条断言就是那个开关的锁。
 *
 * 证伪方式（复核这些断言还有没有效）：
 *   1. 把 `ECHO_CANCEL_MODE` 改成 `EchoCancelMode.SERVER_SIDE`
 *      → `shipped mode is NONE` 与 `uploadDuringPlayback is off` 应立刻变红；
 *   2. 把 `uploadDuringPlayback` 的 `!=` 改成 `==`
 *      → `uploadDuringPlayback is off` 应变红。
 */
class EchoCancelPolicyTest {

    @Test
    fun `shipped mode is NONE`() {
        assertEquals(
            "当前必须锁定在 NONE（半双工）。改成 SERVER_SIDE 会在官方云上自激复读；" +
                "改成 DEVICE_SIDE 需要采集与播放同卡，而本机扬声器在板载 codec、麦克风在 USB 摄像头，永远跨卡。",
            EchoCancelMode.NONE,
            ECHO_CANCEL_MODE,
        )
    }

    @Test
    fun `uploadDuringPlayback is off`() {
        assertFalse(
            "NONE 档下播报期间绝不能上传音频——否则设备会把自己的播报当成用户提问，陷入复读死循环。",
            uploadDuringPlayback,
        )
    }

    @Test
    fun `server side aec is not declared`() {
        assertFalse(
            "不要在 hello 里声明 aec:true。官方云不支持，实测会复读。",
            declareServerSideAec,
        )
    }

    @Test
    fun `device side aec is not enabled`() {
        assertFalse(
            "设备端 AEC 需要采集与播放同卡拿回采参考；本机扬声器在 card 1、USB 麦克风在 card 2，" +
                "effect 创建得出来但不生效。启用它只会让人误以为回声已被消掉。",
            useDeviceAec,
        )
    }

    @Test
    fun `the three derived flags are mutually consistent with NONE`() {
        // 这三个派生开关必须整体自洽。任何一个单独被改成 true，
        // 都意味着"有人在没有对应硬件/服务端能力的前提下打开了它"。
        val profile = Triple(uploadDuringPlayback, declareServerSideAec, useDeviceAec)
        assertEquals(
            "NONE 档下三个派生开关必须全为 false",
            Triple(false, false, false),
            profile,
        )
    }
}
