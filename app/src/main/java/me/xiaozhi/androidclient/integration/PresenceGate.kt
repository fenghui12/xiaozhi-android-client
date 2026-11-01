package me.xiaozhi.androidclient.integration

/**
 * 主动招呼的二重判断：超声波（Nano 拉低 IO6）只算「有东西靠近」，
 * 必须再由摄像头确认是人，才允许打招呼。
 *
 * 纯状态机、不碰硬件和时钟，方便单测；时间由调用方传入。
 *
 * Nano 那边的约定：靠近触发后 IO6 一直保持低电平，直到人离开（≥700mm 持续 2s）
 * 且过了 30s 冷却才释放。所以「一次低电平」= 一次靠近事件，在这段时间内：
 * - 摄像头没看到人 → 隔一会儿再看，最多 [maxChecks] 次（人可能还没转过脸来）；
 * - 看到人 → 招呼一次，之后直到 IO6 释放都不再招呼。
 */
class PresenceGate(
    private val armStableHighMs: Long = 1_000L,
    private val retryIntervalMs: Long = 5_000L,
    private val maxChecks: Int = 4,
) {
    enum class Verdict { PERSON, NO_PERSON, CAMERA_UNAVAILABLE }

    sealed interface Action {
        /** 现在去拍一帧做人体确认。 */
        data object CheckCamera : Action

        /** 已确认是人，执行主动招呼。 */
        data object Greet : Action

        /** 本次靠近事件放弃，原因写日志。 */
        data class GiveUp(val reason: String) : Action
    }

    private var lineLow: Boolean? = null
    private var highSinceMs: Long? = null
    private var armed = false
    private var episodeActive = false
    private var episodeDone = false
    private var checksDone = 0
    private var nextCheckAtMs = 0L
    private var checkInFlight = false

    /** 「Nano 未上电」的提示只需要说一次。 */
    var reportedStuckLow = false
        private set

    val isEpisodeActive: Boolean get() = episodeActive

    /**
     * IO6 电平变化。
     *
     * 只有先稳定看到过 [armStableHighMs] 的高电平，低电平才算数：
     * Nano 没上电时，D2 的保护二极管会把 IO6 钳到低电平，看起来像「一直有人」。
     */
    fun onLineChanged(low: Boolean, nowMs: Long) {
        lineLow = low
        if (!low) {
            highSinceMs = nowMs
            resetEpisode()
            return
        }
        highSinceMs = null
        if (!armed) {
            return
        }
        episodeActive = true
        episodeDone = false
        checksDone = 0
        nextCheckAtMs = nowMs
    }

    /**
     * 周期调用（约 0.5s 一次）。[appIdle] 为 false 时（正在对话/播报）不去开相机，
     * 等空闲了再确认。返回 null 表示什么都不用做。
     */
    fun tick(nowMs: Long, appIdle: Boolean): Action? {
        val since = highSinceMs
        if (!armed && since != null && nowMs - since >= armStableHighMs) {
            armed = true
        }
        if (lineLow == true && !armed && !reportedStuckLow) {
            reportedStuckLow = true
            return Action.GiveUp("IO6 一开始就是低电平：Nano 可能没上电或线没接好，暂不触发主动招呼")
        }
        if (!episodeActive || episodeDone || checkInFlight || !appIdle || nowMs < nextCheckAtMs) {
            return null
        }
        checkInFlight = true
        checksDone++
        return Action.CheckCamera
    }

    fun onCheckResult(verdict: Verdict, nowMs: Long): Action? {
        checkInFlight = false
        if (!episodeActive || episodeDone) {
            // 等结果期间人已经走了（IO6 已释放），结果作废
            return null
        }
        return when (verdict) {
            Verdict.PERSON -> {
                episodeDone = true
                Action.Greet
            }

            Verdict.CAMERA_UNAVAILABLE -> {
                // 相机坏了/被占用/遮挡盖关着时不说话，避免对着空气打招呼
                episodeDone = true
                Action.GiveUp("摄像头不可用，本次靠近不打招呼")
            }

            Verdict.NO_PERSON -> {
                if (checksDone >= maxChecks) {
                    episodeDone = true
                    Action.GiveUp("超声波触发但摄像头 $checksDone 次都没看到人，判定为物体，本次不打招呼")
                } else {
                    nextCheckAtMs = nowMs + retryIntervalMs
                    null
                }
            }
        }
    }

    private fun resetEpisode() {
        episodeActive = false
        episodeDone = false
        checksDone = 0
        checkInFlight = false
    }
}
