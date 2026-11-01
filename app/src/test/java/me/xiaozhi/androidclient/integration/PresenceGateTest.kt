package me.xiaozhi.androidclient.integration

import me.xiaozhi.androidclient.integration.PresenceGate.Action
import me.xiaozhi.androidclient.integration.PresenceGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceGateTest {
    private fun armedGate(): PresenceGate = PresenceGate(
        armStableHighMs = 1_000L,
        retryIntervalMs = 5_000L,
        maxChecks = 3,
    ).apply {
        onLineChanged(low = false, nowMs = 0L)
        assertNull(tick(nowMs = 1_000L, appIdle = true))
    }

    @Test
    fun personConfirmedGreetsOnceUntilLineReleased() {
        val gate = armedGate()
        gate.onLineChanged(low = true, nowMs = 2_000L)

        assertEquals(Action.CheckCamera, gate.tick(2_000L, appIdle = true))
        assertEquals(Action.Greet, gate.onCheckResult(Verdict.PERSON, 3_000L))
        // 人还站着（IO6 仍低），不能反复打招呼
        assertNull(gate.tick(60_000L, appIdle = true))

        gate.onLineChanged(low = false, nowMs = 70_000L)
        gate.onLineChanged(low = true, nowMs = 80_000L)
        assertEquals(Action.CheckCamera, gate.tick(80_000L, appIdle = true))
    }

    @Test
    fun objectWithoutFaceIsRetriedThenRejected() {
        val gate = armedGate()
        gate.onLineChanged(low = true, nowMs = 2_000L)

        assertEquals(Action.CheckCamera, gate.tick(2_000L, appIdle = true))
        assertNull(gate.onCheckResult(Verdict.NO_PERSON, 3_000L))
        assertNull(gate.tick(7_000L, appIdle = true))
        assertEquals(Action.CheckCamera, gate.tick(8_000L, appIdle = true))
        assertNull(gate.onCheckResult(Verdict.NO_PERSON, 9_000L))
        assertEquals(Action.CheckCamera, gate.tick(14_000L, appIdle = true))

        val last = gate.onCheckResult(Verdict.NO_PERSON, 15_000L)
        assertTrue(last is Action.GiveUp)
        assertNull(gate.tick(60_000L, appIdle = true))
    }

    @Test
    fun personTurningAroundOnRetryIsGreeted() {
        val gate = armedGate()
        gate.onLineChanged(low = true, nowMs = 2_000L)
        gate.tick(2_000L, appIdle = true)
        gate.onCheckResult(Verdict.NO_PERSON, 3_000L)

        assertEquals(Action.CheckCamera, gate.tick(8_000L, appIdle = true))
        assertEquals(Action.Greet, gate.onCheckResult(Verdict.PERSON, 9_000L))
    }

    @Test
    fun lowAtBootIsTreatedAsUnpoweredNano() {
        val gate = PresenceGate()
        gate.onLineChanged(low = true, nowMs = 0L)

        assertTrue(gate.tick(500L, appIdle = true) is Action.GiveUp)
        assertNull(gate.tick(60_000L, appIdle = true))
        assertTrue(gate.reportedStuckLow)
    }

    @Test
    fun shortHighGlitchDoesNotArm() {
        val gate = PresenceGate(armStableHighMs = 1_000L)
        gate.onLineChanged(low = false, nowMs = 0L)
        gate.onLineChanged(low = true, nowMs = 300L)

        gate.tick(1_500L, appIdle = true)
        assertNull(gate.tick(2_000L, appIdle = true))
    }

    @Test
    fun busyConversationDefersCameraCheck() {
        val gate = armedGate()
        gate.onLineChanged(low = true, nowMs = 2_000L)

        assertNull(gate.tick(2_000L, appIdle = false))
        assertNull(gate.tick(20_000L, appIdle = false))
        assertEquals(Action.CheckCamera, gate.tick(21_000L, appIdle = true))
    }

    @Test
    fun cameraUnavailableStaysSilent() {
        val gate = armedGate()
        gate.onLineChanged(low = true, nowMs = 2_000L)
        gate.tick(2_000L, appIdle = true)

        assertTrue(gate.onCheckResult(Verdict.CAMERA_UNAVAILABLE, 3_000L) is Action.GiveUp)
        assertNull(gate.tick(10_000L, appIdle = true))
    }

    @Test
    fun resultArrivingAfterPersonLeftIsDiscarded() {
        val gate = armedGate()
        gate.onLineChanged(low = true, nowMs = 2_000L)
        gate.tick(2_000L, appIdle = true)
        gate.onLineChanged(low = false, nowMs = 2_500L)

        assertNull(gate.onCheckResult(Verdict.PERSON, 3_000L))
    }
}
