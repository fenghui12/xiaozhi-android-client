package me.xiaozhi.androidclient.audio

import java.util.concurrent.atomic.AtomicReference

internal class PlaybackSessionState {
    private enum class Phase { OPEN, DRAINING, IDLE }

    private data class State(val epoch: Int, val phase: Phase)

    private val state = AtomicReference(State(0, Phase.OPEN))

    val epoch: Int
        get() = state.get().epoch

    val isActive: Boolean
        get() = state.get().phase != Phase.IDLE

    fun begin(): Int = state.updateAndGet { State(it.epoch + 1, Phase.OPEN) }.epoch

    fun frameEpoch(): Int? = state.get().let { current ->
        current.epoch.takeIf { current.phase == Phase.OPEN }
    }

    fun isCurrent(epoch: Int): Boolean = state.get().let {
        it.epoch == epoch && it.phase != Phase.IDLE
    }

    fun isFinished(epoch: Int): Boolean = state.get().let {
        it.epoch == epoch && it.phase == Phase.IDLE
    }

    fun finish(): Int? {
        while (true) {
            val current = state.get()
            when (current.phase) {
                Phase.IDLE -> return null
                Phase.DRAINING -> return current.epoch
                Phase.OPEN -> {
                    if (state.compareAndSet(current, current.copy(phase = Phase.DRAINING))) {
                        return current.epoch
                    }
                }
            }
        }
    }

    fun complete(epoch: Int): Boolean {
        val current = state.get()
        return current.epoch == epoch && current.phase == Phase.DRAINING &&
            state.compareAndSet(current, current.copy(phase = Phase.IDLE))
    }

    fun interrupt(): Int = state.updateAndGet { State(it.epoch + 1, Phase.IDLE) }.epoch
}
