package me.xiaozhi.androidclient.audio

/**
 * 回声消除（AEC）策略。**语音打断能不能用，完全取决于这里。**
 *
 * ## 为什么需要它
 *
 * 设备播报时扬声器的声音会被自己的麦克风拾取。如果这时把麦克风音频原样上传，
 * 服务端会把「设备自己说的话」识别成用户提问——助手刚说完一句，两秒后又把这句话
 * 当成新问题回答一遍，形成自激复读死循环。
 *
 * 2026-09-15 在官方云 `api.tenclass.net` 上完整复现过：
 * ```
 * 22:21:05  助手说："好啦好啦，是我啦！"
 * 22:21:11  <= stt: "好啦好啦，是我啦。"   ← 自己的播报被当成用户输入回传
 * ```
 * 所以「播报期间继续上传音频」只有在回声确实被消掉之后才成立。
 *
 * ## 三种选择
 *
 * - [NONE]　不消回声。播报期间**停止上传**（半双工）。代价是没有语音打断，
 *   但绝对不会自激。**当前采用这一档**，也是最安全的兜底。
 *
 * - [SERVER_SIDE]　让服务端消。设备在 hello 里声明 `"aec": true` 并给每帧打时间戳，
 *   服务端据此对齐自己发过的 TTS，再从上行音频里减掉回声。
 *   **实测：官方云不支持。** 声明 `aec:true` 之后服务端照样把设备自己的播报识别成
 *   用户输入，复读死循环照旧（上面那段日志就是开着 `aec:true` 录到的）。
 *   代码保留，将来换自建服务端时可以启用。
 *
 * - [DEVICE_SIDE]　让设备自己消。前提是**采集和播放必须在同一张声卡上**——
 *   安卓的 `AcousticEchoCanceler` 需要音频 HAL 提供回采参考，跨卡时拿不到参考信号，
 *   effect 创建得出来但实际不生效。
 *
 *   ⚠️ **本机目前用不了这一档，卡在硬件上。** 2026-09-15 实测：
 *
 *   | 声卡 | 播放 | 采集 | 说明 |
 *   |---|---|---|---|
 *   | card 1 rockchip_rk809-codec | ✅ pcmC1D0p | ✅ pcmC1D0c | 扬声器在这里；板载麦通路存在但**没有咪头** |
 *   | card 2 DECXIN (USB 摄像头) | ❌ | ✅ | 唯一真正能拾音的麦克风 |
 *
 *   `card 1` 的 `Capture MIC Path` 驱动档位齐全（MIC OFF / Main Mic / Hands Free Mic /
 *   BT Sco Mic），打开任意档位后确实能采到数据，但**录到的是一条完全平坦的噪声底**：
 *   40 秒里每 0.5 秒的交流 RMS 恒定在 7.4~7.6（约 -72 dBFS，直流偏置 -40.8，峰值 78），
 *   用户持续讲话 40 秒，波形**没有任何起伏**。这是 ADC 在转换一个悬空输入的特征——
 *   **板子上没有焊麦克风。**（同一轮测试里 USB 麦能清楚听到扬声器里助手的说话声，
 *   那正是上面复读死循环的成因，对照组成立。）
 *
 *   要在本机启用这一档，需要先做**硬件改动**：给 RK809 的 mic 输入接一个麦克风
 *   （板子上若有 MIC 排针/座子，插一个驻极体麦模块即可）。接上之后把本文件改成
 *   [DEVICE_SIDE] 重新编译，AudioEngine 会自动优先选板载麦并打开 AEC。
 *
 *   另一条不依赖硬件改动的路是**软件 AEC**（WebRTC AEC3 / speexdsp），
 *   用解码后的播放 PCM 当远端参考——这个参考比 ESP32 那种模拟回采更干净，
 *   而且不受声卡拓扑限制。代价是引入原生库和额外 CPU 占用。尚未实现。
 */
enum class EchoCancelMode {
    NONE,
    SERVER_SIDE,
    DEVICE_SIDE,
}

/**
 * 当前生效的回声消除策略。
 *
 * 改成 [EchoCancelMode.DEVICE_SIDE] 之前，必须先确认设备端 AEC 真的有效：
 * 播报一段长 TTS，看日志里有没有 `[CAPTURE] AEC ... → 已生效`，以及播报期间还会不会
 * 出现把助手刚说的话当用户输入回传的 `<= {"type":"stt", ...}`。若还会，说明 AEC 没起作用，
 * 必须立刻退回 [EchoCancelMode.NONE]——否则设备会陷入复读死循环。
 */
val ECHO_CANCEL_MODE: EchoCancelMode = EchoCancelMode.NONE

/** 播报期间是否继续向服务端上传麦克风音频。只有回声确实被消掉时才允许为 true。 */
val uploadDuringPlayback: Boolean
    get() = ECHO_CANCEL_MODE != EchoCancelMode.NONE

/** 是否在 hello 里向服务端声明 `"aec": true`。 */
val declareServerSideAec: Boolean
    get() = ECHO_CANCEL_MODE == EchoCancelMode.SERVER_SIDE

/** 此刻是否应该启用安卓的 `AcousticEchoCanceler`。 */
val useDeviceAec: Boolean
    get() = ECHO_CANCEL_MODE == EchoCancelMode.DEVICE_SIDE
